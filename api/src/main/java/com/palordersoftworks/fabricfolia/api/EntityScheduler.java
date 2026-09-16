/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;

/**
 * Schedules work against a specific entity's current owning region.
 *
 * <p>The architectural reference is Folia's EntityScheduler (documented design,
 * clean-room implementation per spec 14): entities are owned by regions, they
 * migrate between regions as they move, and tasks aimed at an entity must follow
 * it — or notice that it vanished — rather than aiming at a fixed position.</p>
 *
 * <p><strong>Why a handle, not an entity reference:</strong> the API module has no
 * Minecraft types. Implementations receive a caller-supplied opaque handle that
 * the implementation can map back to the live entity. This keeps the API surface
 * clean while allowing the fabric module to pass strong references internally.</p>
 */
@AnyThread
public interface EntityScheduler {
	/**
	 * Executes the task on the context of the region that owns the entity when
	 * the task comes due.
	 *
	 * <p><strong>Thread contract:</strong>
	 * <ul>
	 *   <li>Callable from: any thread.</li>
	 *   <li>Runs on: the entity's owning region's context at execution time.</li>
	 *   <li>When: next tick of the owning region (or after {@code delay} ticks of
	 *       that region's counter for the delayed variant).</li>
	 *   <li>If the entity is gone (removed, dead, or unloaded) by execution time:
	 *       {@code retired} runs instead with no guarantees about context state —
	 *       it may not touch any region-owned state. If {@code retired} is null
	 *       the task is silently dropped. If the entity migrated regions between
	 *       scheduling and execution, the task runs in the NEW owner's context —
	 *       following the entity is the whole point.</li>
	 *   <li>May it access region-owned state directly? Yes — the owner region's
	 *       state, including the entity itself.</li>
	 *   <li>May it block? No.</li>
	 *   <li>May it cross region boundaries? Not directly; schedule follow-up work
	 *       through the schedulers.</li>
	 * </ul></p>
	 *
	 * @param entityHandle opaque handle the implementation maps to a live entity
	 * @param task         the work to run in the owner's context
	 * @param retired      fallback run if the entity no longer exists, or null
	 */
	void run(Object entityHandle, Runnable task, Runnable retired);

	/**
	 * Delayed variant of {@link #run(Object, Runnable, Runnable)}: the delay is
	 * measured in the owning region's own tick counter at schedule time, and
	 * re-validated against the entity's owner at execution time. Migration
	 * between schedule and execution follows the entity, per {@link #run}.
	 *
	 * @param entityHandle opaque entity handle
	 * @param delay        non-negative delay in ticks of the owning region
	 * @param task         the work to run
	 * @param retired      fallback run if the entity no longer exists, or null
	 */
	void runDelayed(Object entityHandle, int delay, Runnable task, Runnable retired);
}
