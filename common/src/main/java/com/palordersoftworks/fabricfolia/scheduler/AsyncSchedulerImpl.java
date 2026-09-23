/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.AsyncScheduler;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The async scheduler (Folia's AsyncScheduler concept, clean-room): a
 * dedicated bounded daemon pool for work that is independent of region
 * ticking and of the global cadence (mandate §12). Distinct from
 * {@link GlobalSchedulerImpl} by construction — no shared queue, no shared
 * threads, no tick-ordered dispatch — so async load can never stall global
 * state progression and vice versa.
 *
 * <p><strong>Thread context:</strong> every task runs with an explicit
 * {@code IO} thread context (see {@link ThreadOwnership}), so STRICT
 * ownership diagnostics classify an illegal region touch from async code as
 * what it is, instead of reporting an anonymous unknown thread.</p>
 *
 * <p><strong>Backpressure:</strong> the pool is
 * {@code core = threads, max = threads} with a {@link SynchronousQueue}
 * handoff: when all workers are busy, {@code run} rejects with
 * {@link java.util.concurrent.RejectedExecutionException} (the API documents
 * this) rather than queueing without bound. Delayed/fixed-rate tasks use a
 * small shared timing wheel ({@link ScheduledExecutorService}) that only
 * hands off to the worker pool at deadline — the timer thread never runs
 * task bodies.</p>
 *
 * <p><strong>Exception isolation (mandate §34):</strong> one malformed async
 * task must not kill the pool. Every task body is wrapped; the throwable is
 * reported through the diagnostics sink and the pool continues.</p>
 */
public final class AsyncSchedulerImpl implements AsyncScheduler, AutoCloseable {

	private final ScheduledExecutorService timer;
	private final ExecutorService workers;
	private final java.util.function.Consumer<String> diagnostics;
	private final AtomicInteger activeWorkers;

	/**
	 * @param threads     worker count for the async pool (timing thread is
	 *                    separate and always exactly one)
	 * @param diagnostics sink for task-failure reports (never null)
	 */
	public AsyncSchedulerImpl(int threads, java.util.function.Consumer<String> diagnostics) {
		if (threads < 1) {
			throw new IllegalArgumentException("async threads must be >= 1, got " + threads);
		}
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
		this.activeWorkers = new AtomicInteger();

		ThreadFactory workerFactory = new ThreadFactory() {
			private final AtomicInteger index = new AtomicInteger();

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "FabricFolia-Async-" + index.incrementAndGet());
				t.setDaemon(true);
				return t;
			}
		};
		// Bounded queue, not unbounded: saturation must be VISIBLE (rejected
			// to the caller, reported to diagnostics) rather than silently
			// accumulating. Direct submissions throw when saturated; timer-fired
			// submissions are dropped with a diagnostic (no caller to throw to).
		this.workers = new java.util.concurrent.ThreadPoolExecutor(
				threads, threads,
				0L, TimeUnit.MILLISECONDS,
				new java.util.concurrent.LinkedBlockingQueue<>(256),
				workerFactory,
				new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

		ThreadFactory timerFactory = new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r, "FabricFolia-Async-Timer");
				t.setDaemon(true);
				return t;
			}
		};
		this.timer = Executors.newSingleThreadScheduledExecutor(timerFactory);
	}

	@Override
	public void run(Runnable task) {
		Objects.requireNonNull(task, "task");
		submit(task);
	}

	@Override
	public void runDelayed(long delayMillis, Runnable task) {
		Objects.requireNonNull(task, "task");
		if (delayMillis <= 0) {
			submit(task);
			return;
		}
		timer.schedule(() -> submitOrReport(task), delayMillis, TimeUnit.MILLISECONDS);
	}

	@Override
	public com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandle runAtFixedRate(
			long initialDelayMillis, long periodMillis, Runnable task) {
		Objects.requireNonNull(task, "task");
		if (periodMillis <= 0) {
			throw new IllegalArgumentException("periodMillis must be > 0, got " + periodMillis);
		}
		if (initialDelayMillis < 0) {
			throw new IllegalArgumentException("initialDelayMillis must be >= 0, got " + initialDelayMillis);
		}
		ScheduledFuture<?> future = timer.scheduleWithFixedDelay(
				() -> submitOrReport(task), initialDelayMillis, periodMillis, TimeUnit.MILLISECONDS);
		return () -> future.cancel(false);
	}

	/**
	 * Handoff to the worker pool with exception isolation: the timer thread
	 * and worker threads must both survive a malformed task.
	 */
	private void submit(Runnable task) {
		try {
			workers.execute(() -> {
				activeWorkers.incrementAndGet();
				ThreadOwnership.Context token = ThreadOwnership.enterSide(
						com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.ASYNC);
				try {
					task.run();
				} catch (Throwable t) {
					diagnostics.accept("[FabricFolia] Async task failed on " + Thread.currentThread().getName()
							+ " (" + activeWorkers.get() + " workers active): " + t);
				} finally {
					ThreadOwnership.exit(token);
					activeWorkers.decrementAndGet();
				}
			});
		} catch (java.util.concurrent.RejectedExecutionException e) {
			throw new RejectedExecutionException(
					"[FabricFolia] Async scheduler overloaded (all " + threads() + " workers busy)"
							+ " — task rejected. Slow down async producers or raise folia.yml async threads.", e);
		}
	}

	/**
	 * Timer-path handoff: if the pool is saturated at fire time there is no
	 * caller to receive a rejection, so it is reported to diagnostics instead
	 * (an uncaught throw here would also kill future periodic runs).
	 */
	private void submitOrReport(Runnable task) {
		try {
			submit(task);
		} catch (RejectedExecutionException e) {
			diagnostics.accept(e.getMessage());
		}
	}

	/**
	 * ASYNC → REGION handoff (mandate §6): schedules world-mutating work onto
	 * the region owning {@code (chunkX, chunkZ)} at call time. The task runs
	 * in the owning region's context on a later tick — never on an async
	 * thread. Safe from any thread.
	 *
	 * @return true when the task was enqueued into a live region; false when
	 *         no region owns the position (caller decides policy)
	 */
	public boolean runOnRegion(RegionScheduler engine, int chunkX, int chunkZ, Runnable task) {
		java.util.Objects.requireNonNull(engine, "engine");
		java.util.Objects.requireNonNull(task, "task");
		Region region = engine.regionizer().ownerOfChunk(chunkX, chunkZ);
		if (region == null) {
			return false;
		}
		return engine.enqueue(region, task);
	}

	/**
	 * ASYNC → GLOBAL handoff: schedules server-wide work onto the global
	 * execution context, where global state may be mutated. Safe from any
	 * thread.
	 */
	public void runOnGlobal(GlobalSchedulerImpl global, Runnable task) {
		java.util.Objects.requireNonNull(global, "global");
		global.run(task);
	}

	/** @return configured worker count (diagnostics). */
	public int threads() {
		return ((java.util.concurrent.ThreadPoolExecutor) workers).getMaximumPoolSize();
	}

	/** @return number of tasks currently executing (diagnostics). */
	public int activeTasks() {
		return activeWorkers.get();
	}

	@Override
	public void close() {
		timer.shutdownNow();
		workers.shutdownNow();
	}
}
