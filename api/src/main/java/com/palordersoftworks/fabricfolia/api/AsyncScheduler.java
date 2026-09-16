/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;

/**
 * Executes work that is independent of region ticking AND of the global
 * region's tick cadence — the fourth scheduler of Folia's model (region,
 * global, async, entity; spec §12).
 *
 * <p><strong>Why it is distinct from {@link GlobalScheduler}:</strong> the
 * global context is a tick-ordered execution context that OWNS server-wide
 * state; it must stay fast and predictable or every global concern stalls.
 * Async work (IO completion, mod background jobs, network-side prep, metrics
 * flushes) has no ordering relationship to that cadence and would otherwise
 * either pollute the global queue's ordering or be force-serialized onto it.
 * Folia therefore provides a separate async scheduler, and so does this
 * implementation: tasks here run on a dedicated bounded daemon pool, NOT on
 * the global dispatch thread and NOT on region workers.</p>
 *
 * <p><strong>Thread contract:</strong> callable from any thread. Tasks run on
 * async pool threads with NO region context and NO global context attached —
 * they must not touch region-owned or global-owned state directly; route such
 * work through {@link RegionScheduler}, {@link GlobalScheduler}, or
 * {@link EntityScheduler}. Blocking is permitted (bounded by pool capacity:
 * {@code threads + queue limit}); unbounded queue growth is rejected with
 * {@link java.util.concurrent.RejectedExecutionException} rather than
 * silently accumulating.</p>
 */
@AnyThread
public interface AsyncScheduler {
	/** Executes the task on the async pool as soon as a worker is free. */
	void run(Runnable task);

	/**
	 * Executes the task on the async pool after at least {@code delayMillis}
	 * of wall-clock time. Time base is wall clock (not any tick counter):
	 * async work has no tick identity.
	 */
	void runDelayed(long delayMillis, Runnable task);

	/**
	 * Executes the task on the async pool every {@code periodMillis} of
	 * wall-clock time until cancelled. The first run is at least
	 * {@code initialDelayMillis} after scheduling. A run that is still
	 * executing when its next period elapses does not overlap itself: the
	 * next run starts after the previous completes.
	 *
	 * @return handle safe to call from any thread; idempotent
	 */
	RegionScheduler.CancelHandle runAtFixedRate(long initialDelayMillis, long periodMillis, Runnable task);
}
