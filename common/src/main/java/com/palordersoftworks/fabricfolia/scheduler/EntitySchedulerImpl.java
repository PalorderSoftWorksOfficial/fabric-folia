/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.EntityScheduler;
import com.palordersoftworks.fabricfolia.region.Region;

import java.util.Objects;
import java.util.function.Function;

/**
 * The entity scheduler (Folia's EntityScheduler concept, clean-room and
 * Fabric-native): tasks scheduled against an entity execute in the context of
 * the region that owns the entity AT EXECUTION TIME — following the entity
 * through region migration — or run the {@code retired} callback if the entity
 * no longer exists.
 *
 * <p><strong>Handle resolution:</strong> the API module has no Minecraft types,
 * so callers supply an opaque handle; the engine supplies a resolver that maps
 * handle → (owning region or absent). The resolver is injected by the fabric
 * module during the entity integration phase; the scheduling machinery here is
 * complete and tested against a stub resolver.</p>
 *
 * <p><strong>Why there is no per-entity registry:</strong> each task carries
 * its own re-validation closure — at execution time the resolver is consulted
 * again and the task either follows the entity to its new owner, runs, or
 * retires. There is no mutable per-entity bookkeeping to corrupt, and no
 * cleanup to leak when an entity is forgotten (spec 18 review note: a
 * registry would be a third structure to keep consistent; this design has
 * none).</p>
 */
public final class EntitySchedulerImpl implements EntityScheduler {

	/** Resolves a handle to its current owning region, or null if the entity is gone. */
	public interface EntityResolver extends Function<Object, Region> {
	}

	private final RegionScheduler engine;
	private final EntityResolver resolver;

	public EntitySchedulerImpl(RegionScheduler engine, EntityResolver resolver) {
		this.engine = engine;
		this.resolver = Objects.requireNonNull(resolver, "resolver");
	}

	@Override
	public void run(Object entityHandle, Runnable task, Runnable retired) {
		Objects.requireNonNull(entityHandle, "entityHandle");
		Region regionAtSchedule = resolver.apply(entityHandle);
		if (regionAtSchedule == null) {
			// Entity gone at schedule time: retired callback, per contract.
			if (retired != null) {
				retired.run();
			}
			return;
		}
		boolean enqueued = engine.enqueue(regionAtSchedule, () ->
				executeFollowing(entityHandle, task, retired));
		if (!enqueued) {
			// Region died between resolve and enqueue: the entity's owner no
			// longer exists, so the entity is gone as far as this task cares.
			if (retired != null) {
				retired.run();
			}
		}
	}

	@Override
	public void runDelayed(Object entityHandle, int delay, Runnable task, Runnable retired) {
		Objects.requireNonNull(entityHandle, "entityHandle");
		Region regionAtSchedule = resolver.apply(entityHandle);
		if (regionAtSchedule == null) {
			if (retired != null) {
				retired.run();
			}
			return;
		}
		if (delay <= 0) {
			run(entityHandle, task, retired);
			return;
		}
		// Delay in ticks of the owning region's counter; the wrapper re-checks
		// entity existence and migration when the deadline arrives, and the
		// deadline itself survives merges (queue re-homing keeps tick-counter
		// deadlines meaningful in the absorbing region).
		engine.enqueueDelayed(regionAtSchedule, delay, () ->
				executeFollowing(entityHandle, task, retired));
	}

	@Override
	public com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandle runAtFixedRate(
			Object entityHandle, int initialDelayTicks, int periodTicks, Runnable task, Runnable retired) {
		Objects.requireNonNull(entityHandle, "entityHandle");
		if (periodTicks <= 0) {
			throw new IllegalArgumentException("periodTicks must be positive");
		}
		com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandleView handle =
				new com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandleView();
		scheduleRepeating(entityHandle, initialDelayTicks, periodTicks, task, retired, handle);
		return handle;
	}

	private void scheduleRepeating(Object entityHandle, int delay, int period, Runnable task,
			Runnable retired, com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandleView handle) {
		runDelayed(entityHandle, delay, () -> {
			if (handle.isCancelled()) {
				return;
			}
			try {
				task.run();
			} finally {
				if (!handle.isCancelled()) {
					scheduleRepeating(entityHandle, period, period, task, retired, handle);
				}
			}
		}, retired);
	}

	/**
	 * The execution-time gate shared by immediate and delayed variants: runs
	 * the task only if the entity still exists AND its current owner is this
	 * region; follows the entity to its new owner otherwise; retires it if the
	 * entity is gone. MUST run inside a region context (it is only ever
	 * enqueued into region queues).
	 */
	private void executeFollowing(Object entityHandle, Runnable task, Runnable retired) {
		Region current = resolver.apply(entityHandle);
		if (current == null) {
			// Entity gone between schedule and execution: retired (contract).
			if (retired != null) {
				retired.run();
			}
			return;
		}
		com.palordersoftworks.fabricfolia.api.RegionInfo executingOwner =
				com.palordersoftworks.fabricfolia.thread.ThreadOwnership
						.currentRegion().orElse(null);
		if (executingOwner != null && current != executingOwner) {
			// Entity migrated after this task was enqueued into the old
			// owner's queue: follow the entity — re-enqueue into the NEW
			// owner (documented contract). The old owner never executes
			// entity state mutation it no longer owns.
			boolean followed = engine.enqueue(current, () ->
					executeFollowing(entityHandle, task, retired));
			if (!followed && retired != null) {
				// The new owner died in the resolve→enqueue window: the entity's
				// owner no longer exists, so the task retires rather than
				// vanishing silently.
				retired.run();
			}
			return;
		}
		task.run();
	}
}
