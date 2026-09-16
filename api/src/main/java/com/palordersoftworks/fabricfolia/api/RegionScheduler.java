/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 *
 * The full Apache-2.0 license text is in the project LICENSE file and is
 * reproduced in every shipping source file; abbreviated headers in this
 * review are intentional. Full text:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;
import com.palordersoftworks.fabricfolia.api.annotations.RegionThread;

import java.util.concurrent.TimeUnit;

/**
 * Schedules work to execute inside a specific region's execution context.
 *
 * <p>This is the primary safe way to touch region-owned state (blocks, entities,
 * block entities) from any other context — including from another region, the
 * network thread, or an unrelated mod executor. It replaces direct access the
 * same way Folia's RegionScheduler does, with Fabric-native shapes: positions are
 * given as world-name + packed chunk/block coordinates, not Bukkit Locations.</p>
 *
 * <p><strong>Why positions, not region handles:</strong> region boundaries move as
 * regions merge and split. The scheduler resolves the <em>current</em> owner at
 * execution time, so a task always lands on the region that owns the target
 * position when the task runs — never on a region handle that has gone stale.</p>
 */
@AnyThread
public interface RegionScheduler {
	/** Policy applied when a task's target position has no owning region at execution time. */
	enum MissingRegionPolicy {
		/** The task is dropped; no error is raised. Use for best-effort cosmetic work. */
		DROP,
		/** The task is executed on the global context. Global state access remains safe. */
		RUN_ON_GLOBAL
	}

	/** Parameter object selecting a position-based target. */
	@AnyThread
	final class Position {
		private final String world;
		private final int chunkX;
		private final int chunkZ;

		Position(String world, int chunkX, int chunkZ) {
			this.world = world;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}

		public static Position of(String world, int chunkX, int chunkZ) {
			return new Position(world, chunkX, chunkZ);
		}

		public String world() { return world; }
		public int chunkX() { return chunkX; }
		public int chunkZ() { return chunkZ; }
	}

	/**
	 * Executes the task on the region that owns the given position at execution
	 * time, on that region's next tick pass.
	 *
	 * <p><strong>Thread contract</strong> (spec 21 — every scheduling method
	 * documents all six answers):
	 * <ul>
	 *   <li>Callable from: any thread.</li>
	 *   <li>Runs on: the owning region's context (a worker thread, changes between
	 *       ticks — never assume a specific physical thread).</li>
	 *   <li>When: during the target region's next tick, after the tick begins.</li>
	 *   <li>If the target region is gone: handled per {@link MissingRegionPolicy}.</li>
	 *   <li>May it access region-owned state directly? Yes — the target region's
	 *       state, and nothing else.</li>
	 *   <li>May it block? No. Blocking stalls the region's tick.</li>
	 *   <li>May it cross region boundaries? Not directly: touching another region
	 *       from inside the task re-introduces the violation this API exists to
	 *       prevent; schedule further work through this scheduler instead.</li>
	 * </ul></p>
	 *
	 * @param position world + chunk coordinates whose owner should run the task
	 * @param task     the work to run; exceptions are handled by the region
	 *                 failure policy (spec 26)
	 */
	void run(Position position, Runnable task);

	/**
	 * Executes the task on the owning region's context after at least
	 * {@code delay} ticks of <em>that region's</em> tick counter.
	 *
	 * <p><strong>Thread contract:</strong> callable from any thread; runs on the
	 * owner's context; executes when the region's tick counter reaches
	 * {@code currentTick + delay} (region-local time — a slow region delays its
	 * own scheduled tasks, not other regions'); missing region handled per policy;
	 * region-state access same as {@link #run(Position, Runnable)}; must not block;
	 * must not cross regions directly.</p>
	 *
	 * @param position target position
	 * @param delay    non-negative delay in target-region ticks
	 * @param task     the work to run
	 */
	void runDelayed(Position position, int delay, Runnable task);

	/**
	 * Executes the task on the owning region's context roughly every
	 * {@code period} ticks of the region's own tick counter until cancelled.
	 *
	 * <p><strong>Thread contract:</strong> callable from any thread; runs on the
	 * owner's context each period; a region merge keeps the task (the owner
	 * identity follows the merged region); a region death (split-away that loses
	 * the position) re-resolves the owner; missing region handled per policy;
	 * must not block; must not cross regions directly.</p>
	 *
	 * @param position target position
	 * @param initialDelayTicks delay before first execution, in region ticks
	 * @param periodTicks      period between executions, in region ticks
	 * @param task     the work to run
	 * @return a handle that cancels future executions; safe to call from any thread
	 */
	CancelHandle runAtFixedRate(Position position, int initialDelayTicks, int periodTicks, Runnable task);

	/**
	 * Executes the task inside the <em>current</em> region's context if the
	 * calling thread is a region context and owns {@code position}; otherwise
	 * behaves exactly like {@link #run(Position, Runnable)}.
	 *
	 * <p><strong>Thread contract:</strong> callable from any thread; if already
	 * in the owning context the task runs synchronously and any exception
	 * propagates to the caller; otherwise identical contract to {@code run}.</p>
	 *
	 * <p>This is the compatibility-friendly entry point for mod code written as
	 * if a single thread exists: calling it unconditionally is always safe
	 * (spec 23).</p>
	 *
	 * @param position target position
	 * @param task     the work to run
	 */
	void runOrSchedule(Position position, Runnable task);

	/**
	 * Sends {@code task} to the region that owns {@code position} when this call
	 * is made, bypassing re-resolution at execution time. Returns false if there
	 * is currently no owner — the caller decides what to do, unlike the
	 * policy-based variants.
	 *
	 * <p><strong>Thread contract:</strong> callable from any thread; runs on the
	 * snapshot owner's context during its next tick; if that region dies before
	 * executing, the task is dropped (documented: dead-region queues are drained,
	 * not executed, at death); must not block; must not cross regions directly.</p>
	 *
	 * @return true if the task was enqueued into a live region's queue
	 */
	boolean send(Position position, Runnable task);

	/**
	 * Returns a view of the region that owns the given position right now, or
	 * empty if none. Diagnostic/scheduling aid; the view may go stale — region
	 * boundaries move. Never blocks, never throws for a missing region.
	 */
	@AnyThread
	java.util.Optional<RegionInfo> ownerOf(Position position);

	/**
	 * Handle for cancelling scheduled or repeating tasks. All implementations
	 * must be safe to call from any thread and must be idempotent.
	 */
	@AnyThread
	interface CancelHandle {
		/** Cancels the task if it has not yet run; repeating tasks stop firing. */
		void cancel();
	}
}
