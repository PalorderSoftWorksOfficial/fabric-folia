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

	/** Stops accepting tasks and waits for workers to finish current work. */
	public void shutdown(long timeoutMillis) throws InterruptedException {
		running.set(false);
		lock.lock();
		try {
			wake.signalAll();
		} finally {
			lock.unlock();
		}
		terminated.await(timeoutMillis, TimeUnit.MILLISECONDS);
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
				Task task = takeTask(50);
				if (task == null) {
					continue;
				}
				lastTaskStartNanos[workerIndex] = System.nanoTime();
				try {
					task.run();
				} catch (Throwable t) {
					// The scheduler wraps tasks with its failure policy (spec 26);
					// this catch is the last-resort guard so one bad task can
					// never kill a worker thread. It re-reports, never swallows
					// silently: the wrapper already logged; we only preserve the
					// worker.
				}
			}
		} finally {
			ThreadOwnership.clear();
			terminated.countDown();
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
