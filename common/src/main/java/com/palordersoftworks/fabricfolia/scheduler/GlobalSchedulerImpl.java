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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Server-thread queue for state that is owned globally rather than by a
 * particular world region.
 *
 * <p>The queue accepts submissions from any thread and is drained by the
 * server thread after region workers have quiesced.</p>
 *
 * <p>Delays and repeating periods are measured in server ticks.</p>
 */
public final class GlobalSchedulerImpl implements GlobalScheduler, AutoCloseable {

	private final ReentrantLock lock = new ReentrantLock();
	private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

	/**
	 * The server tick counter used by global delayed tasks.
	 */
	private volatile long tickCount;
	private boolean closed;

	public GlobalSchedulerImpl() {
	}

	@Override
	public void run(Runnable task) {
		java.util.Objects.requireNonNull(task, "task");
		lock.lock();
		try {
			ensureOpen();
			tasks.add(task);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void runDelayed(int delay, Runnable task) {
		java.util.Objects.requireNonNull(task, "task");
		lock.lock();
		try {
			ensureOpen();
			if (delay <= 0) {
				tasks.add(task);
			} else {
				tasks.add(new DelayedGlobalTask(tickCount + delay, task));
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandle runAtFixedRate(int initialDelayTicks, int periodTicks, Runnable task) {
		java.util.Objects.requireNonNull(task, "task");
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
		java.util.Objects.requireNonNull(task, "task");
		if (ThreadOwnership.current().kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.GLOBAL) {
			lock.lock();
			try {
				ensureOpen();
			} finally {
				lock.unlock();
			}
			task.run();
			return;
		}
		run(task);
	}

	/**
	 * Advances the global tick counter by one and drains all due pending tasks
	 * in the global context. Called exactly once per global tick by the
	 * server tick after region workers have quiesced. MUST be called from only
	 * the server thread.
	 */
	public void tick() {
		List<Runnable> due = new ArrayList<>();
		List<Runnable> requeue = new ArrayList<>();
		lock.lock();
		try {
			tickCount++;
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
	 * Drains and runs all pending tasks immediately in the global context,
	 * ignoring delays. Called by the engine on the server thread during shutdown.
	 */
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
		lock.lock();
		try {
			closed = true;
		} finally {
			lock.unlock();
		}
	}

	private void ensureOpen() {
		if (closed) {
			throw new java.util.concurrent.RejectedExecutionException("Global scheduler is closed");
		}
	}

	/** Global-context task deferred to a future global tick. */
	private record DelayedGlobalTask(long targetTick, Runnable delegate) implements Runnable {
		@Override
		public void run() {
			delegate.run();
		}
	}

	/** Repeating tasks read the cancelled flag through the shared API view. */
	private static final class CancelHandleView
			extends com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandleView {
	}
}
