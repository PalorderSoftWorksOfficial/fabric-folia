/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;

/**
 * Schedules work on the server-thread global execution context. Tasks are drained
 * during the server tick after region workers have quiesced and before vanilla
 * world mutation begins. This is not an independently ticking Folia global region.
 *
 * <p><strong>Anti-bottleneck rule (spec 4):</strong> the global context exists for
 * state that is actually global — it must never become the catch-all route for
 * "hard to make region-local" region gameplay.</p>
 */
@AnyThread
public interface GlobalScheduler {
	/**
	 * Executes the task on the global context as soon as it can after scheduling.
	 *
	 * <p><strong>Thread contract:</strong> callable from any thread; runs on the
	 * Minecraft server thread in GLOBAL context before the next vanilla tick
	 * mutation phase; must not block or access region-owned state.</p>
	 */
	void run(Runnable task);

	/**
	 * Executes the task on the global context after {@code delay} global ticks.
	 *
	 * <p><strong>Thread contract:</strong> as {@link #run(Runnable)}, but executes
	 * after the requested number of server ticks.</p>
	 */
	void runDelayed(int delay, Runnable task);

	/**
	 * Executes the task on the global context every {@code period} ticks until
	 * cancelled.
	 *
	 * <p><strong>Thread contract:</strong> as {@link #run(Runnable)}, repeating.
	 * The returned handle is safe to call from any thread.</p>
	 */
	RegionScheduler.CancelHandle runAtFixedRate(int initialDelayTicks, int periodTicks, Runnable task);

	/**
	 * Executes the task immediately if the calling thread IS the global context;
	 * otherwise schedules it. Used by console command handling and other global
	 * entry points that may be invoked from arbitrary threads.
	 *
	 * <p><strong>Thread contract:</strong> callable from any thread; when already
	 * in GLOBAL context the task runs inline, otherwise it is queued.</p>
	 */
	void runOrSchedule(Runnable task);
}
