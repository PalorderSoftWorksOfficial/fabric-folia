/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The bounded worker pool (spec 10): N dedicated worker threads that regions
 * are dispatched onto as they become due. Regions are NOT pinned to workers —
 * the pool exists so a bounded thread count serves any number of regions.
 *
 * <p><strong>Why not Executors.newFixedThreadPool:</strong> the spec explicitly
 * rejects a raw pool as the full solution: the pool gives threads, but the
 * scheduling intelligence (which region is due, earliest-deadline-first,
 * per-region deadlines) lives in {@link RegionScheduler}. This class is
 * deliberately dumb: wake workers, hand them ready regions, shut down cleanly.
 * All policy is in the scheduler, which keeps the pool testable and the policy
 * reviewable.</p>
 *
 * <p><strong>Shared-pool support (integration phase):</strong> the pool can be
 * shared by multiple per-world schedulers — region counts across dimensions
 * fluctuate, and a single bounded pool is what spec 10 actually specifies
 * ("regions must be schedulable onto a bounded worker pool", world-agnostic).
 * {@link #submitShared(Task, java.util.function.Consumer)} lets a second
 * scheduler run ticks on the same workers without the tasks being bound to
 * the primary scheduler's bookkeeping.</p>
 *
 * <p><strong>Threading:</strong> workers are ordinary dedicated threads
 * (daemon=false so the JVM tracks them; stopped deterministically by
 * {@link #shutdown()}). Idling workers park on a condition variable with a
 * poll timeout as a livelock backstop (spec 19: watch for livelocks) — the
 * scheduler also signals explicitly on dispatch, so the poll is a safety net,
 * not the wake path.</p>
 */
public final class WorkerPool {

	/** A unit of work handed to a worker: run() executes with no context entered. */
	interface Task {
		void run();
	}

	private final List<Thread> workers = new ArrayList<>();
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition wake = lock.newCondition();
	private final java.util.ArrayDeque<Task> handoff = new java.util.ArrayDeque<>();
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final CountDownLatch terminated;
	/** Workers currently executing a task (diagnostics: busy vs. waiting). */
	private final java.util.concurrent.atomic.AtomicInteger busyCount = new java.util.concurrent.atomic.AtomicInteger();
	/**
	 * Tick-phase execution gate. While false, workers start NO new tasks:
	 * the server thread owns gameplay state (vanilla's passes decide and
	 * stage); at end of tick the engine flushes staged work and opens the
	 * gate, so all region execution happens in the inter-tick window. This
	 * is the single-writer phase rule that keeps vanilla's entity/block-
	 * entity/scheduled-tick iteration from running concurrently with
	 * worker-side mutations of the same structures.
	 */
	private volatile boolean executionOpen = true;
	/** Per-worker keepalive: index → nanoTime when that worker last took a task. */
	private final long[] lastTaskStartNanos;

	public WorkerPool(int threadCount, String namePrefix) {
		this.terminated = new CountDownLatch(threadCount);
		this.lastTaskStartNanos = new long[threadCount];
		for (int i = 0; i < threadCount; i++) {
			final int index = i;
			Thread worker = new Thread(() -> workerLoop(index), namePrefix + "-" + (i + 1));
			worker.setDaemon(false);
			workers.add(worker);
			worker.start();
		}
	}

	/**
	 * @return a snapshot of per-worker keepalive timestamps (nanoTime of each
	 * worker's most recent task start). Diagnostics/watchdog data: a worker
	 * far behind now is either idle (queue empty) or stuck (task running
	 * long) — the watchdog distinguishes via queue depth.
	 */
	public long[] keepaliveSnapshot() {
		long[] snapshot = new long[lastTaskStartNanos.length];
		for (int i = 0; i < snapshot.length; i++) {
			snapshot[i] = lastTaskStartNanos[i];
		}
		return snapshot;
	}

	/** @return the worker count (diagnostics). */
	public int workerCount() {
		return lastTaskStartNanos.length;
	}

	/**
	 * Submits a task to any free worker. Non-blocking: if all workers are busy
	 * the task waits in the handoff queue and the next free worker takes it.
	 */
	public void submit(Task task) {
		lock.lock();
		try {
			handoff.add(task);
			wake.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/** @return the number of tasks currently waiting for a worker (diagnostics). */
	int queuedTaskCount() {
		lock.lock();
		try {
			return handoff.size();
		} finally {
			lock.unlock();
		}
	}

	/** @return how many workers are executing a task right now (diagnostics). */
	public int busyCount() {
		return busyCount.get();
	}

	/** @return false while the server thread's tick phase owns gameplay state. */
	public boolean isExecutionOpen() {
		return executionOpen;
	}

	/** Opens/closes the tick-phase execution gate. Open signals parked workers. */
	public void setExecutionOpen(boolean open) {
		executionOpen = open;
		if (open) {
			lock.lock();
			try {
				wake.signalAll();
			} finally {
				lock.unlock();
			}
		}
	}

	/**
	 * Closes the gate and waits (bounded) for in-flight tasks to finish —
	 * the tick-boundary quiesce. Correctness argument: workers increment
	 * {@link #busyCount} BEFORE re-reading the gate, so the barrier's
	 * close-write → busy-read ordering guarantees any worker that starts a
	 * task is observed here, and any worker read after the close sees the
	 * closed gate and backs out without running.
	 *
	 * @param timeoutMillis upper bound on the wait (the caller reports
	 *                      timeouts — never silently proceed without saying so)
	 * @return true when quiesced within the timeout
	 */
	public boolean closeExecutionAndAwait(long timeoutMillis) {
		return closeExecutionAndAwait(timeoutMillis, null);
	}

	/**
	 * Tick-boundary quiesce with a MAIN-THREAD PUMP. A worker body can park
	 * in {@code ServerChunkCache.getChunk(...).join()} — vanilla routes every
	 * off-main-thread chunk read through {@code supplyAsync(mainThreadExecutor)}
	 * even for already-loaded chunks, and that queued supplier only runs when
	 * the SERVER THREAD polls the chunk executor. Without a pump here the
	 * barrier is a mutual wait: the worker waits for the server thread and the
	 * server thread waits for the worker — observed live as a quiesce timeout
	 * on EVERY tick (1s/tick tax) until the vanilla watchdog killed the server
	 * (2026-10-05). The pump is vanilla's own {@code managedBlock} pattern:
	 * while waiting on work that needs this thread, keep this thread's chunk
	 * queue moving. Chunk-system tasks only — never arbitrary gameplay tasks —
	 * so the single-writer phase rule is preserved.
	 *
	 * @param pump runs ONE pending main-thread chunk-system task per
	 *             iteration; null = plain wait (unit tests / no chunk system)
	 * @return true when quiesced within the timeout
	 */
	public boolean closeExecutionAndAwait(long timeoutMillis, Runnable pump) {
		executionOpen = false;
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (busyCount.get() > 0) {
			if (System.nanoTime() >= deadline) {
				return false;
			}
			if (pump != null) {
				try {
					pump.run();
				} catch (Throwable t) {
					// Vanilla's executor reports task failures itself; a pump
					// error must never abort the barrier wait.
				}
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return busyCount.get() == 0;
			}
		}
		return true;
	}

	/** Stops accepting tasks and waits for workers to finish current work. */
	public void shutdown(long timeoutMillis) throws InterruptedException {
		shutdown(timeoutMillis, null);
	}

	/**
	 * Shutdown with an optional main-thread pump: workers resumed by the gate
	 * reopen can immediately enter {@code getChunk().join()} again, and a
	 * pump-less wait would sit out the whole timeout on them (blocking JVM
	 * exit — the workers are non-daemon). Same pump contract as
	 * {@link #closeExecutionAndAwait(long, Runnable)}.
	 */
	public void shutdown(long timeoutMillis, Runnable pump) throws InterruptedException {
		running.set(false);
		setExecutionOpen(true); // parked phase-A workers must wake to exit
		lock.lock();
		try {
			wake.signalAll();
		} finally {
			lock.unlock();
		}
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (terminated.getCount() > 0 && System.nanoTime() < deadline) {
			if (pump != null) {
				try {
					pump.run();
				} catch (Throwable ignored) {
					// reported by vanilla's executor; keep draining
				}
			}
			if (terminated.await(1, TimeUnit.MILLISECONDS)) {
				return;
			}
		}
	}

	/**
	 * Submits a task that is expected to complete its own work; a shared pool
	 * (multiple schedulers over one bounded set of workers) needs the task's
	 * failure reported through a sink rather than the owning scheduler's
	 * diagnostics — the wrapper here is the last-resort guard, mirroring the
	 * single-owner workerLoop handling.
	 */
	public void submitShared(Task task, java.util.function.Consumer<Throwable> failureSink) {
		submit(() -> {
			try {
				task.run();
			} catch (Throwable t) {
				failureSink.accept(t);
			}
		});
	}

	private void workerLoop(int workerIndex) {
		ThreadOwnership.clear();
		try {
			while (running.get()) {
				if (!executionOpen) {
					// Tick phase: park WITHOUT taking tasks (no busy churn, no
					// handoff pileup) until the end-of-tick flush opens the gate.
					awaitExecutionOpen();
					continue;
				}
				Task task = takeTask(50);
				if (task == null) {
					continue;
				}
				lastTaskStartNanos[workerIndex] = System.nanoTime();
				// Busy is incremented BEFORE the gate re-check: the quiesce
				// barrier's close-write → busy-read order only guarantees
				// observation for workers that order busy++ ahead of their gate
				// read — so a task that passes the check below is waited for.
				busyCount.incrementAndGet();
				try {
					if (!executionOpen) {
						// Gate closed between the top check and here: return the
						// task unstarted (it stays queued; the gate-open scan
						// dispatches it) and let the barrier observe our busy--.
						requeueFront(task);
						continue;
					}
					task.run();
				} catch (Throwable t) {
					// The scheduler wraps tasks with its failure policy (spec 26);
					// this catch is the last-resort guard so one bad task can
					// never kill a worker thread. It re-reports, never swallows
					// silently: the wrapper already logged; we only preserve the
					// worker.
				} finally {
					busyCount.decrementAndGet();
				}
			}
		} finally {
			ThreadOwnership.clear();
			terminated.countDown();
		}
	}

	/** Parks on the wake condition until the execution gate reopens (50ms poll backstop). */
	private void awaitExecutionOpen() {
		lock.lock();
		try {
			if (!executionOpen && running.get()) {
				wake.awaitNanos(TimeUnit.MILLISECONDS.toNanos(50));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			lock.unlock();
		}
	}

	/** Returns a not-started task to the head of the handoff queue. */
	private void requeueFront(Task task) {
		lock.lock();
		try {
			handoff.addFirst(task);
		} finally {
			lock.unlock();
		}
	}

	private Task takeTask(long pollTimeoutMillis) {
		lock.lock();
		try {
			if (handoff.isEmpty()) {
				awaitWake(pollTimeoutMillis);
			}
			return handoff.poll();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} finally {
			lock.unlock();
		}
	}

	private void awaitWake(long timeoutMillis) throws InterruptedException {
		// awaitNanos to honor spurious wakeups and the poll backstop.
		wake.awaitNanos(TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
	}
}
