/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.GlobalScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The global execution context: one task queue, dispatched once per global
 * tick (50ms cadence, Folia's "global region" concept, clean-room). Owns what
 * no region can own: game rules, world border, the regionizer itself,
 * cross-world bookkeeping (spec 4).
 *
 * <p><strong>Threading:</strong> the queue is multi-producer (any thread may
 * schedule) and single-consumer (only the global dispatch drains it — the
 * engine must call {@link #dispatchPending()} from exactly one dispatch
 * context). The lock protects only the queue's internal deque; it is not a
 * gameplay-safety mechanism (spec 18 review note). The dispatcher thread lives
 * in the fabric module's engine bootstrap — keeping it out of this class keeps
 * the queue testable without real time passing.</p>
 *
 * <p><strong>Delayed/repeating semantics:</strong> delays are measured in
 * global ticks ({@link #tickCount()}); a delayed task's wrapper re-queues
 * itself until its deadline tick, mirroring the region mechanism so both time
 * bases behave identically through the engine's lifecycle.</p>
 */
public final class GlobalSchedulerImpl implements GlobalScheduler, AutoCloseable {

	private final ReentrantLock lock = new ReentrantLock();
	private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
	private final Condition hasWork = lock.newCondition();

	/**
	 * The global tick counter (the global context's time base). Written once
	 * per {@link #tick()}, read by delayed-task wrappers (same thread) and
	 * diagnostics.
	 */
	private long tickCount;

	public GlobalSchedulerImpl() {
	}

	@Override
	public void run(Runnable task) {
		lock.lock();
		try {
			tasks.add(task);
			hasWork.signalAll();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void runDelayed(int delay, Runnable task) {
		if (delay <= 0) {
			run(task);
			return;
		}
		// Expressed against the global tick counter like the region variant —
		// the wrapper re-queues until due, so it survives being enqueued early.
		long target = tickCount + delay;
		run(new DelayedGlobalTask(target, task));
	}

	@Override
	public com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandle runAtFixedRate(int initialDelayTicks, int periodTicks, Runnable task) {
		if (periodTicks <= 0) {
			throw new IllegalArgumentException("periodTicks must be positive");
		}
		CancelHandleView handle = new CancelHandleView();
		scheduleRepeating(initialDelayTicks, periodTicks, task, handle);
		return handle;
	}

	private void scheduleRepeating(int delay, int period, Runnable task,
	                               CancelHandleView handle) {
		runDelayed(delay, () -> {
			if (handle.isCancelled()) {
				return;
			}
			try {
				task.run();
			} finally {
				if (!handle.isCancelled()) {
					scheduleRepeating(period, period, task, handle);
				}
			}
		});
	}

	@Override
	public void runOrSchedule(Runnable task) {
		if (ThreadOwnership.current().kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.GLOBAL) {
			task.run();
			return;
		}
		run(task);
	}

	/**
	 * Advances the global tick counter by one and drains all due pending tasks
	 * in the global context. Called exactly once per global tick by the
	 * engine's global dispatch; MUST be called from only one thread at a time
	 * (single-consumer contract — see class docs).
	 */
	public void tick() {
		tickCount++;
		List<Runnable> due = new ArrayList<>();
		List<Runnable> requeue = new ArrayList<>();
		lock.lock();
		try {
			Runnable task;
			while ((task = tasks.poll()) != null) {
				if (task instanceof DelayedGlobalTask delayed
						&& delayed.targetTick() > tickCount) {
					requeue.add(task); // not due yet: keeps waiting
					continue;
				}
				due.add(task);
			}
			tasks.addAll(requeue);
		} finally {
			lock.unlock();
		}
		ThreadOwnership.Context token = ThreadOwnership.enterGlobal();
		try {
			for (Runnable task : due) {
				try {
					task.run();
				} catch (Throwable t) {
					// Global task failures are reported (spec 26); the global
					// context cannot be "isolated" like a region — a failing
					// global task never halts the loop, but it is never silent.
					System.getLogger("Fabric-Folia").log(System.Logger.Level.ERROR,
							"Global task failed", t);
				}
			}
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	/**
	 * Drains and runs ALL pending tasks immediately in the global context,
	 * ignoring delays. Quiescence path only (spec 16): used at shutdown after
	 * the tick cadence has stopped, where "drop everything" is worse than
	 * "finish everything once".
	 *
	 * @deprecated superseded by {@link #tick()}; retained for the quiescence
	 *             drain until the disable state machine (spec 16) lands.
	 */
	@Deprecated
	public void dispatchPending() {
		List<Runnable> pending;
		lock.lock();
		try {
			if (tasks.isEmpty()) {
				return;
			}
			pending = new ArrayList<>(tasks);
			tasks.clear();
		} finally {
			lock.unlock();
		}
		ThreadOwnership.Context token = ThreadOwnership.enterGlobal();
		try {
			for (Runnable task : pending) {
				try {
					task.run();
				} catch (Throwable t) {
					System.getLogger("Fabric-Folia").log(System.Logger.Level.ERROR,
							"Global task failed", t);
				}
			}
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	/** @return the global tick counter. */
	public long tickCount() {
		return tickCount;
	}

	@Override
	public void close() {
		// Queue is drained by the engine during quiescence (spec 16); nothing
		// to release here beyond documenting the policy.
	}

	/** Global-context task deferred to a future global tick. */
	private record DelayedGlobalTask(long targetTick, Runnable delegate) implements Runnable {
		@Override
		public void run() {
			delegate.run();
		}
	}

	/**
	 * Mutable view over a {@link CancelHandle}: the API type exposes only
	 * {@code cancel()}, so the repeating-task machinery needs this adapter to
	 * read back the cancelled flag. Single-purpose, created per schedule.
	 */
	private static final class CancelHandleView implements
			com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandle {
		private volatile boolean cancelled;

		boolean isCancelled() {
			return cancelled;
		}

		@Override
		public void cancel() {
			cancelled = true;
		}
	}
}
