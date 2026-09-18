/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.AsyncScheduler;
import com.palordersoftworks.fabricfolia.api.EntityScheduler;
import com.palordersoftworks.fabricfolia.api.FabricFoliaApi;
import com.palordersoftworks.fabricfolia.api.GlobalScheduler;
import com.palordersoftworks.fabricfolia.api.RegionInfo;
import com.palordersoftworks.fabricfolia.api.RegionScheduler;
import com.palordersoftworks.fabricfolia.api.ThreadContext;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live {@link FabricFoliaApi} implementation: hands mods the real
 * engine-backed schedulers (mandate 41, spec 9). Created at engine bootstrap
 * and delivered to mods declaring the {@code fabricfolia} entrypoint.
 *
 * <p><strong>Threading:</strong> every accessor is a stable read of engine
 * state — safe from any context, including mod entrypoints running before
 * worlds attach. The schedulers' own contracts govern where submitted tasks
 * may run; send() returns false rather than silently misrouting, and the
 * region scheduler's missing-region policy (RUN_ON_GLOBAL) is real: the
 * adapter hands the common-module facade the live global scheduler.</p>
 *
 * <p><strong>Post-shutdown behavior:</strong> after the engine closes, every
 * accessor that could enqueue work throws IllegalStateException with an
 * explicit message — honest failure, never a zombie engine (spec 16).</p>
 */
public final class FabricFoliaApiImpl implements FabricFoliaApi {

	private final FabricFoliaEngine engine;
	/** Flipped at shutdown: API calls after it fail honestly. */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	FabricFoliaApiImpl(FabricFoliaEngine engine) {
		this.engine = engine;
	}

	/** Marks the API closed (engine shutdown). Idempotent. */
	void close() {
		closed.set(true);
	}

	private void assertOpen() {
		if (closed.get()) {
			throw new IllegalStateException(
					"Fabric Folia is shut down; schedulers are no longer available.");
		}
	}

	@Override
	public RegionScheduler regionScheduler() {
		assertOpen();
		return new ApiRegionSchedulerAdapter();
	}

	@Override
	public EntityScheduler entityScheduler() {
		assertOpen();
		return new ApiEntitySchedulerAdapter();
	}

	@Override
	public GlobalScheduler globalScheduler() {
		assertOpen();
		return engine.globalScheduler();
	}

	@Override
	public AsyncScheduler asyncScheduler() {
		assertOpen();
		return engine.asyncScheduler();
	}

	@Override
	public ThreadContext threadContext() {
		return engine.threadContext();
	}

	@Override
	public boolean isEnabled() {
		return !closed.get();
	}

	/**
	 * The API region scheduler delegates to the common-module facade,
	 * resolving positions against the engine's world registry at call time
	 * (multi-world safe; per-world adapters would strand other dimensions).
	 */
	private final class ApiRegionSchedulerAdapter implements RegionScheduler {

		private com.palordersoftworks.fabricfolia.scheduler.ApiRegionScheduler delegateFor(Position position) {
			com.palordersoftworks.fabricfolia.region.WorldRegionizer regionizer =
					engine.regionizerFor(position.world());
			com.palordersoftworks.fabricfolia.scheduler.RegionScheduler scheduler =
					engine.schedulerFor(position.world());
			if (regionizer == null || scheduler == null) {
				throw new IllegalStateException("World '" + position.world()
						+ "' is not attached to Fabric Folia (engine disabled, or the"
						+ " world has no region scheduler).");
			}
			return new com.palordersoftworks.fabricfolia.scheduler.ApiRegionScheduler(
					scheduler, regionizer,
					RegionScheduler.MissingRegionPolicy.RUN_ON_GLOBAL,
					engine.globalScheduler());
		}

		@Override
		public void run(Position position, Runnable task) {
			delegateFor(position).run(position, task);
		}

		@Override
		public void runDelayed(Position position, int delay, Runnable task) {
			delegateFor(position).runDelayed(position, delay, task);
		}

		@Override
		public CancelHandle runAtFixedRate(Position position, int initialDelayTicks, int periodTicks, Runnable task) {
			return delegateFor(position).runAtFixedRate(position, initialDelayTicks, periodTicks, task);
		}

		@Override
		public void runOrSchedule(Position position, Runnable task) {
			delegateFor(position).runOrSchedule(position, task);
		}

		@Override
		public boolean send(Position position, Runnable task) {
			return delegateFor(position).send(position, task);
		}

		@Override
		public java.util.Optional<RegionInfo> ownerOf(Position position) {
			return delegateFor(position).ownerOf(position);
		}
	}

	/**
	 * The API entity scheduler: entity-following work resolves the entity's
	 * CURRENT owning region at execution time (mandate 14) through the
	 * per-world entity schedulers; the API accepts an entity from any world.
	 */
	private final class ApiEntitySchedulerAdapter implements EntityScheduler {

		private com.palordersoftworks.fabricfolia.scheduler.EntitySchedulerImpl delegateFor(Entity entity) {
			String worldName = entity.level().dimension().identifier().toString();
			com.palordersoftworks.fabricfolia.scheduler.EntitySchedulerImpl delegate =
					engine.entitySchedulerFor(worldName);
			if (delegate == null) {
				throw new IllegalStateException("No entity scheduler for world '" + worldName
						+ "' (engine disabled, or the world is not tracked).");
			}
			return delegate;
		}

		@Override
		public void run(Object entityHandle, Runnable task, Runnable retired) {
			delegateFor(asEntity(entityHandle)).run(entityHandle, task, retired);
		}

		@Override
		public void runDelayed(Object entityHandle, int delay, Runnable task, Runnable retired) {
			delegateFor(asEntity(entityHandle)).runDelayed(entityHandle, delay, task, retired);
		}

		private Entity asEntity(Object handle) {
			if (!(handle instanceof Entity entity)) {
				throw new IllegalArgumentException(
						"entityHandle must be a Minecraft Entity instance; got: "
								+ (handle == null ? "null" : handle.getClass().getName()));
			}
			return entity;
		}
	}
}
