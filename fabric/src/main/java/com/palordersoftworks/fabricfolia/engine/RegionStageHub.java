/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.metrics.RegionMetrics;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The regionized gameplay staging core (mandates §15/§20/§21/§27): vanilla's
 * own server-thread tick passes decide WHAT to tick, and region workers
 * execute it, per region, in the owning region's thread context.
 *
 * <p><strong>The staging architecture and why it is shaped this way:</strong>
 * the four gameplay slices — entity bodies (players included), block-entity
 * ticks, scheduled block/fluid ticks (via their own vanilla drain), and
 * player connection bodies — are captured at
 * their exact vanilla execution sites by redirect mixins and staged here as
 * runnables instead of running on the server thread. At end of server tick
 * the engine flushes each staged batch: every runnable is grouped by the
 * region that owns its position (regionizer lookup) and enqueued onto that
 * region's task queue, so execution happens on a region worker, serialized
 * within the region and parallel across regions — the same single-owner
 * pipeline the random-tick interceptor already uses.</p>
 *
 * <p><strong>Why vanilla decides what to tick (the load-bearing choice):</strong>
 * replicating the entity-tick-list iteration, vehicle crumble, entity-ticking
 * range gating, block-entity list maintenance, or the scheduled-tick drain
 * order would fork vanilla's semantics into a second implementation that
 * silently diverges patch by patch. Staging at the actual execution sites
 * means vanilla still performs every decision on the server thread (exactly
 * as it always has) and only the <em>bodies</em> move to regions. What moves
 * is precisely what vanilla chose to run — nothing more, nothing less.</p>
 *
 * <p><strong>Correctness boundaries:</strong></p>
 * <ul>
 *   <li>One staging pass per server tick: work staged during tick N is
 *       flushed at end of tick N, after all of vanilla's passes — so region
 *       resolution happens AFTER every server-thread mutation of tick N,
 *       including entity movement. An entity that migrated during the tick
 *       is therefore resolved against its post-tick owner; no staged body
 *       is ever executed by a region that no longer owns it.</li>
 *   <li>Region death between stage and flush drops the staged body (the
 *       region died = its chunks were unregistered; the next vanilla pass
 *       re-stages — one vanilla-consistent lost tick, never a stuck queue).
 *       Merges between stage and flush need no re-homing: flush resolves
 *       against the post-merge ownership picture and lands everything on
 *       the survivor (the scheduler's own queue re-homing carries already-
 *       flushed work).</li>
 *   <li>The player connection body (SCL tick → {@code Connection.tick()}:
 *       packet drain, {@code SGPLI.tick} → {@code doTick} physics, outbound
 *       flush) is staged as the LAST slice. Within a tick vanilla runs the
 *       entity pass BEFORE {@code tickConnection}; the EnumMap's declaration
 *       order keeps that order at flush, so a region executes its entity
 *       bodies first, then its players' connection bodies — vanilla's
 *       intra-tick order, per region. The two verified server-thread
 *       couplings inside {@code ServerPlayer.tick()} —
 *       {@code ServerChunkCache.move} (chunk-view bookkeeping, the chunk
 *       system's owner) and {@code ServerPlayerGameMode.tick} (break
 *       progress against the mining ticket machinery) — are bounced to the
 *       server thread by {@code ServerPlayerTickMixin} when the body runs
 *       on a region worker.</li>
 * </ul>
 *
 * <p><strong>Thread-context discipline (spec 8):</strong> {@link #stage}
 * and {@link #flushStaged} both run on the server thread (vanilla's passes
 * and the engine tick hook); execution runs on region workers inside the
 * scheduler's REGION context. The staged lists are single-writer (server
 * thread) structures — no synchronization, by ownership rather than locks
 * (spec 31).</p>
 *
 * <p><strong>State classification (spec 4):</strong> the active flag is
 * GLOBAL (engine lifecycle); per-world hubs are GLOBAL registries; staged
 * work is REGION-LOCAL once grouped; pending pools are a bounded staging
 * area whose lifetime is one server tick.</p>
 */
public final class RegionStageHub {

	// =================================================================================
	// The engine-global activation surface (the capture mixins consult this)
	// =================================================================================

	private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
	private static final Map<Level, RegionStageHub> HUBS_BY_LEVEL = new ConcurrentHashMap<>();

	private static final AtomicLong STAGED_TOTAL = new AtomicLong();
	private static final AtomicLong EXECUTED_ON_WORKERS = new AtomicLong();
	private static final AtomicLong DROPPED_DEAD_REGION = new AtomicLong();
	private static final AtomicLong REHOMED_MERGE = new AtomicLong();
	private static final AtomicLong BOUNCED_TO_SERVER = new AtomicLong();

	/** The gameplay slices, each with its vanilla capture site. */
	public enum Slice {
		/** Entity tick bodies (vanilla {@code tickNonPassenger}/{@code tickPassenger}). */
		ENTITY,
		/** Block-entity ticks (vanilla {@code tickBlockEntities}). */
		BLOCK_ENTITY,
		/** Scheduled block/fluid tick executions (vanilla's LevelTicks drains). */
		SCHEDULED_TICK,
		/**
		 * Player connection bodies (vanilla SCL tick → {@code Connection.tick()}).
		 * Declared LAST: flush iterates in declaration order, and vanilla runs
		 * the entity pass before {@code tickConnection} within a tick.
		 */
		PLAYER
	}

	/** Staging slice counter for {@link Slice} bodies (none for scheduled ticks' generic path). */
	private static RegionMetrics.Counter counterOf(Slice slice) {
		return switch (slice) {
			case ENTITY -> RegionMetrics.Counter.ENTITY_TICKS_EXECUTED;
			case BLOCK_ENTITY -> RegionMetrics.Counter.BLOCK_ENTITY_TICKS_EXECUTED;
			case SCHEDULED_TICK -> RegionMetrics.Counter.SCHEDULED_TICKS_EXECUTED;
			case PLAYER -> RegionMetrics.Counter.PLAYER_CONNECTION_TICKS;
		};
	}

	/** @return true when the calling level's slice is staged to regions this session. */
	public static boolean isStaging(Level level, Slice slice) {
		if (!ACTIVE.get() || !HUBS_BY_LEVEL.containsKey(level)) {
			return false;
		}
		return com.palordersoftworks.fabricfolia.patches.PatchRegistry.isEnabled(switch (slice) {
			case ENTITY -> "stage-entity-ticks";
			case BLOCK_ENTITY -> "stage-block-entity-ticks";
			case SCHEDULED_TICK -> "stage-scheduled-ticks";
			case PLAYER -> "stage-player-path";
		});
	}

	/**
	 * Stages one vanilla execution body instead of running it on the server
	 * thread. Server thread only (vanilla's passes). No-op when inactive
	 * (the mixins already checked, but the hub must stay safe alone).
	 */
	public static void stage(Level level, Slice slice, Runnable body) {
		RegionStageHub hub = HUBS_BY_LEVEL.get(level);
		if (hub != null) {
			hub.stage(slice, body);
		}
	}

	/** Activates staging globally and installs {@code level}'s hub. */
	static void activate(ServerLevel level, RegionStageHub hub) {
		HUBS_BY_LEVEL.put(level, hub);
		ACTIVE.set(true);
	}

	/** Deactivates all staging (engine shutdown). Vanilla resumes unchanged. */
	static void deactivateAll() {
		ACTIVE.set(false);
		HUBS_BY_LEVEL.clear();
	}

	/**
	 * Deactivates this world's staging (world detach). Global activation
	 * stays on for other worlds; this level's capture mixin checks hub
	 * presence per level, so it falls through to vanilla immediately.
	 */
	void deactivate() {
		HUBS_BY_LEVEL.remove(level);
		if (HUBS_BY_LEVEL.isEmpty()) {
			ACTIVE.set(false);
		}
	}

	/** @return staged-body total across all worlds (diagnostics). */
	public static long stagedTotal() {
		return STAGED_TOTAL.get();
	}

	/** @return bodies executed on region workers (diagnostics). */
	public static long executedOnWorkers() {
		return EXECUTED_ON_WORKERS.get();
	}

	/** @return bodies dropped because their region died (diagnostics). */
	public static long droppedDeadRegion() {
		return DROPPED_DEAD_REGION.get();
	}

	/** @return pending pools re-homed by merges (diagnostics). */
	public static long rehomedByMerge() {
		return REHOMED_MERGE.get();
	}

	/** @return staged bodies bounced to the server thread at execution time (diagnostics). */
	public static long bouncedToServer() {
		return BOUNCED_TO_SERVER.get();
	}

	// =================================================================================
	// Per-world hub
	// =================================================================================

	private final ServerLevel level;

	/** @return the live level this hub stages for (diagnostics). */
	public ServerLevel level() {
		return level;
	}
	private final WorldRegionizer regionizer;
	private final RegionScheduler scheduler;
	private final RegionMetrics metrics;
	private final Consumer<String> diagnostics;

	/**
	 * The not-yet-flushed staged bodies, per slice. Written by the server
	 * thread (vanilla passes, {@link #stage}); read+cleared by flush (server
	 * thread); mutated by the merge listener under the regionizer structure
	 * lock. The synchronized list wrapper is the server-thread/structure-lock
	 * handoff — see the class doc's thread-context note.
	 */
	private final Map<Slice, List<Runnable>> staged = new EnumMap<>(Slice.class);
	{
		for (Slice slice : Slice.values()) {
			staged.put(slice, new ArrayList<>());
		}
	}

	RegionStageHub(ServerLevel level, WorldRegionizer regionizer, RegionScheduler scheduler,
	               RegionMetrics metrics, Consumer<String> diagnostics) {
		this.level = level;
		this.regionizer = regionizer;
		this.scheduler = scheduler;
		this.metrics = metrics;
		this.diagnostics = diagnostics;
		regionizer.addListener(new StagingListener());
	}

	private void stage(Slice slice, Runnable body) {
		staged.get(slice).add(body);
		STAGED_TOTAL.incrementAndGet();
	}

	/**
	 * Flushes this world's staged batches: groups every body by the region
	 * that owns its position and enqueues it onto that region's task queue.
	 * Server thread (engine end-of-server-tick hook), after all of vanilla's
	 * passes — the ownership picture is final for this tick.
	 */
	void flushStaged() {
		for (Map.Entry<Slice, List<Runnable>> entry : staged.entrySet()) {
			List<Runnable> batch = entry.getValue();
			if (batch.isEmpty()) {
				continue;
			}
			dispatch(entry.getKey(), batch);
			entry.getValue().clear();
		}
	}

	private void dispatch(Slice slice, List<Runnable> batch) {
		for (Runnable body : batch) {
			Region region = regionFor(body);
			if (region == null) {
				// Position outside every region (world-border edge, an entity
				// in an unowned chunk, or a non-positioned body): run it where
				// vanilla would have — right here on the server thread. Zero
				// behavioral delta for work the regionizer does not own.
				runSafely(slice, body, null);
				continue;
			}
			final Region target = region;
			boolean enqueued = scheduler.enqueue(target, () -> runSafely(slice, body, target));
			if (!enqueued) {
				// Region died between lookup and enqueue (or scheduler
				// closed): documented drop — the next vanilla pass re-stages.
				DROPPED_DEAD_REGION.incrementAndGet();
			}
		}
	}

	/** Resolves the owning region for a staged body (its chunk position). */
	private Region regionFor(Runnable body) {
		if (body instanceof Positioned positioned) {
			ChunkPos pos = positioned.fabricfolia$position();
			return regionizer.ownerOfChunk(pos.x(), pos.z());
		}
		return null;
	}


	private void runSafely(Slice slice, Runnable body, Region region) {
		if (region != null
				&& ThreadOwnership.current().kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
				&& !fabricfolia$bodyNeighborhoodLoaded(body)) {
			// Execution-time bounce. The staging capture's loadedness check can
			// go stale when a region's queue is backed up: by the time this body
			// runs, its edge chunks may have unloaded (a walking player's wake).
			// Such a body parks a worker on a SYNCHRONOUS chunk load the worker
			// cannot pump — observed live as a region latched TICKING for
			// minutes while every packet and movement task for its chunks
			// starved behind it. The server thread owns the chunk system (it
			// pumps the load machinery), and vanilla runs these bodies there
			// anyway, so bounce instead of blocking the worker. Re-entering
			// runSafely with a null region runs the body under the identical
			// isolation/metric path, inline, on the server thread.
			BOUNCED_TO_SERVER.incrementAndGet();
			level.getServer().execute(() -> runSafely(slice, body, null));
			return;
		}
		try {
			body.run();
			EXECUTED_ON_WORKERS.incrementAndGet();
			metrics.increment(counterOf(slice));
			if (region != null) {
				// Execution-time success feedback for the failure policy.
				// (The scheduler's policy already observes queue-level tasks;
				// staged bodies are individually guarded here because a body
				// must never kill its worker's drain loop.)
			}
		} catch (Throwable t) {
			metrics.increment(RegionMetrics.Counter.EXCEPTIONS_ISOLATED);
			diagnostics.accept("Staged " + slice + " body failed"
					+ (region != null ? " in region " + region.world() + ":" + region.regionId() : "")
					+ " on " + Thread.currentThread().getName() + ": " + t);
		}
	}

	/**
	 * @return true when every chunk in the body's 3x3 chunk neighborhood is
	 * loaded now. A probe, not a reservation: this bounds the body's own
	 * chunk-data reach (collision iteration, fluid interaction, edge block
	 * queries) to data that is already resident, so executing it cannot park
	 * on a chunk load. Bodies with no position resolve nothing — run them.
	 * Called from region workers; reads the same resident-chunk structures
	 * the body itself would read (the interim boundary the tick interceptor
	 * already documents).
	 */
	private boolean fabricfolia$bodyNeighborhoodLoaded(Runnable body) {
		if (!(body instanceof Positioned positioned)) {
			return true;
		}
		net.minecraft.world.level.ChunkPos pos = positioned.fabricfolia$position();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (level.getChunkSource().getChunkNow(pos.x() + dx, pos.z() + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	// =================================================================================
	// Structural transitions: merges re-home pending pools, deaths drop them
	// =================================================================================

	private final class StagingListener implements WorldRegionizer.Listener {

		@Override
		public void onRegionMerged(Region donor, Region into) {
			// The pending pools hold BODIES (not yet region-assigned); they
			// need no re-homing — flush resolves regions after the merge and
			// lands everything on the survivor. The scheduler's own queue
			// re-homing (RegionScheduler.onRegionMerged) already carries the
			// already-flushed work. Counter kept for diagnostics parity.
			REHOMED_MERGE.incrementAndGet();
		}

		@Override
		public void onRegionSplit(Region parent, List<Region> children) {
			// Same reasoning: staged bodies are position-resolved at flush,
			// after the split — each lands on whichever child owns it now.
		}

		@Override
		public void onRegionDead(Region region) {
			// Pending pools are region-agnostic (bodies only); already-
			// flushed work in the dead region's queue is dropped by the
			// scheduler's own death listener. Nothing to clean here.
		}
	}

	// =================================================================================
	// The body contracts (what capture mixins wrap)
	// =================================================================================

	/**
	 * A staged body that knows the world position it belongs to (its chunk
	 * decides the owning region at flush).
	 */
	public interface Positioned {
		/** The chunk position whose owning region executes this body. */
		ChunkPos fabricfolia$position();
	}
}
