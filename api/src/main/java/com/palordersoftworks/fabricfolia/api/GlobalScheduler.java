/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;
import com.palordersoftworks.fabricfolia.api.annotations.GlobalThread;

/**
 * Schedules work on the global execution context: the context that owns genuinely
 * server-wide state — game rules, world border definition, the regionizer
 * itself, cross-world teleport bookkeeping (spec section 4).
 *
 * <p>The architectural reference is Folia's global region task queue (documented
 * design, clean-room implementation): one always-running 20 TPS context that owns
 * what no region can own. Global work is scheduled here rather than executed
 * inline on callers' threads so global state has exactly one owning context at
 * any moment.</p>
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
	 * global context's worker; executes before the global context's next tick pass
	 * completes; the task may access global-owned state directly, must not block,
	 * and must not touch region-owned state (route region work through
	 * {@link RegionScheduler}).</p>
	 */
	void run(Runnable task);

	/**
	 * Executes the task on the global context after {@code delay} global ticks.
	 *
	 * <p><strong>Thread contract:</strong> as {@link #run(Runnable)}, but executes
	 * when the global tick counter reaches {@code currentTick + delay}.</p>
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
	 * <p><strong>Thread contract:</strong> callable from any thread; when run
	 * inline the exception behavior of {@code task} propagates to the caller;
	 * otherwise as {@link #run(Runnable)}.</p>
	 */
	@GlobalThread
	void runOrSchedule(Runnable task);
}
