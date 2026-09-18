/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.RegionInfo;
import com.palordersoftworks.fabricfolia.api.RegionScheduler.MissingRegionPolicy;
import com.palordersoftworks.fabricfolia.api.RegionScheduler.Position;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import java.util.Optional;

/**
 * Adapts the public {@link com.palordersoftworks.fabricfolia.api.RegionScheduler}
 * API onto the engine's {@link RegionScheduler} + {@link WorldRegionizer}.
 *
 * <p><strong>Position resolution:</strong> the public API takes positions, not
 * region handles — the owner is resolved at schedule time for {@code run} (the
 * region that owns the position runs it on its next tick) and re-resolved at
 * execution time for delayed/repeating tasks (each re-schedule re-resolves the
 * owner, so a task whose position migrated regions follows the state it
 * targets; a position with no owner goes through {@link MissingRegionPolicy}).</p>
 *
 * <p><strong>Delay semantics:</strong> the delay is expressed against the
 * owning region's tick counter at schedule time ({@code currentTick + delay});
 * if the region merges before the deadline, the queue re-homes into the
 * absorber whose counter continues forward — the deadline stays meaningful.</p>
 */
public final class ApiRegionScheduler implements com.palordersoftworks.fabricfolia.api.RegionScheduler {

	private final RegionScheduler engine;
	private final WorldRegionizer regionizer;
	private final MissingRegionPolicy missingPolicy;

	public ApiRegionScheduler(RegionScheduler engine, WorldRegionizer regionizer,
	                          MissingRegionPolicy missingPolicy,
	                          com.palordersoftworks.fabricfolia.api.GlobalScheduler globalFallback) {
		this.engine = engine;
		this.regionizer = regionizer;
		this.missingPolicy = missingPolicy;
		this.globalFallback = globalFallback;
	}

	/** The live global scheduler for the RUN_ON_GLOBAL missing-region policy. */
	private final com.palordersoftworks.fabricfolia.api.GlobalScheduler globalFallback;

	@Override
	public void run(Position position, Runnable task) {
		sendOrPolicy(position, task);
	}

	@Override
	public void runDelayed(Position position, int delay, Runnable task) {
		Region region = regionizer.ownerOfChunk(position.chunkX(), position.chunkZ());
		if (region == null) {
			applyMissingPolicy(task);
			return;
		}
		engine.enqueueDelayed(region, delay, task);
	}

	@Override
	public CancelHandle runAtFixedRate(Position position, int initialDelayTicks, int periodTicks, Runnable task) {
		if (periodTicks <= 0) {
			throw new IllegalArgumentException("periodTicks must be positive");
		}
		// Self-rescheduling fixed rate: each run re-schedules the next from the
		// position's CURRENT owner, so the task follows the state it targets
		// (re-resolved every period, per the API contract).
		ApiCancelHandle handle = new ApiCancelHandle();
		scheduleRepeating(position, initialDelayTicks, periodTicks, task, handle);
		return handle;
	}

	private void scheduleRepeating(Position position, int initialDelay, int period, Runnable task,
	                               ApiCancelHandle handle) {
		runDelayed(position, initialDelay, () -> {
			if (handle.isCancelled()) {
				return;
			}
			try {
				task.run();
			} finally {
				if (!handle.isCancelled()) {
					scheduleRepeating(position, period, period, task, handle);
				}
			}
		});
	}

	@Override
	public void runOrSchedule(Position position, Runnable task) {
		Region owner = regionizer.ownerOfChunk(position.chunkX(), position.chunkZ());
		ThreadOwnership.Context current = ThreadOwnership.current();
		if (current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
				&& current.region() != null
				&& current.region() == owner) {
			task.run(); // already the owning context: run inline, propagate exceptions
			return;
		}
		sendOrPolicy(position, task);
	}

	@Override
	public boolean send(Position position, Runnable task) {
		Region region = regionizer.ownerOfChunk(position.chunkX(), position.chunkZ());
		if (region == null) {
			return false;
		}
		return engine.enqueue(region, task,
				RegionScheduler.packChunkPos(position.chunkX(), position.chunkZ()));
	}

	private void sendOrPolicy(Position position, Runnable task) {
		Region region = regionizer.ownerOfChunk(position.chunkX(), position.chunkZ());
		if (region == null) {
			applyMissingPolicy(task);
			return;
		}
		engine.enqueue(region, task);
	}

	/**
	 * Applies the configured policy for a position no region owns:
	 * RUN_ON_GLOBAL hands the task to the global scheduler (safe for global
	 * state, per the API contract); DROP lets best-effort work vanish. A
	 * silent wrong-context execution is never an option.
	 */
	private void applyMissingPolicy(Runnable task) {
		if (missingPolicy == MissingRegionPolicy.RUN_ON_GLOBAL && globalFallback != null) {
			globalFallback.run(task);
			return;
		}
		// DROP: task vanishes; the position had no owner (unloaded chunks).
	}

	@Override
	public Optional<RegionInfo> ownerOf(Position position) {
		Region region = regionizer.ownerOfChunk(position.chunkX(), position.chunkZ());
		return Optional.ofNullable(region);
	}

	/** Simple cancel handle: monotonic, safe from any thread. */
	static final class ApiCancelHandle implements CancelHandle {
		private volatile boolean cancelled;

		boolean isCancelled() {
			return cancelled;
		}

		@Override
		public void cancel() {
			cancelled = true;
		}
	}
}
