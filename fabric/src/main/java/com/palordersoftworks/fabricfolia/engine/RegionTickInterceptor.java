/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.scheduler.RegionTaskQueue;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The vanilla intercept layer for the first regionized slice: per-chunk
 * random-tick work (vanilla {@code ServerLevel.tickChunk}) executes on the
 * owning region's worker thread instead of the server thread.
 *
 * <p><strong>How the pipeline works end to end (all verified against the
 * 26.2 jar, see SCHEDULING.md):</strong> vanilla's
 * {@code ServerChunkCache.tickChunks(ProfilerFiller, long)} reaches its
 * per-chunk site through
 * {@code ChunkMap.forEachBlockTickingChunk(Consumer<LevelChunk>)}; the
 * {@code ServerChunkCacheTickMixin} redirects that call when this interceptor
 * is attached, passing a <em>collecting</em> consumer. Vanilla therefore still
 * decides WHICH chunks tick (its DistanceManager-driven entity-ticking set —
 * unchanged semantics), but the WORK is handed to the engine: the collecting
 * consumer registers each chunk with {@link #offer}, and when vanilla's
 * {@code tickChunks} returns, the server thread calls {@link #flushPass},
 * which groups the offered chunks into the regionizer and schedules one job
 * per region. Each job runs on a region worker (single-owner latch enforced
 * by the regionizer), re-validates every chunk against its region's section
 * set, and executes {@code ServerLevel.tickChunk(chunk, randomTickSpeed)} in
 * the region's thread context — with the STRICT diagnostics active.</p>
 *
 * <p><strong>Thread-context discipline (spec 8):</strong> the collect/offer
 * path runs on the server thread; the pass bookkeeping is a per-world
 * ConcurrentHashMap (multi-producer legal: the offer happens inside vanilla's
 * own pass on the server thread, flush on the same thread — the concurrent
 * structure is belt-and-braces for future re-entrancy, not a safety
 * mechanism). The tick path enters {@link ThreadOwnership}'s region context
 * before touching anything region-owned and exits in a finally.</p>
 *
 * <p><strong>State classification (spec 4):</strong> the offered-chunk
 * bookkeeping is GLOBAL (per-world pass state, lifetime = one vanilla tick
 * pass); the offered chunks themselves are REGION-LOCAL once grouped; the
 * work executed is vanilla gameplay state owned by the region for the tick's
 * duration (invariant 3).</p>
 *
 * <p><strong>Known boundary (documented, not hidden — spec 27):</strong> this
 * slice moves ONLY the per-chunk random-tick pass. Scheduled ticks, block
 * entities, and entities still run on the server thread, so they can mutate
 * neighboring blocks while a region worker random-ticks the same area — the
 * interleaving is safe at the block-state level (chunk data access is
 * confined to the region that owns the chunk during the tick; the server
 * thread's other passes see either the before or after state) but is NOT yet
 * a full regionization of ticking. This is exactly the documented scope of
 * the {@code regionized-random-ticks} opt-in flag.</p>
 */
public final class RegionTickInterceptor {

	/** Per-world pass state: the chunks offered during the current vanilla tick pass. */
	private final Map<String, Pass> passesByWorld = new ConcurrentHashMap<>();
	/** Per-world attachments (regionizer + scheduler), set at attach time. */
	private record Attachment(WorldRegionizer regionizer, RegionScheduler scheduler) {}
	private final Map<String, Attachment> attachments = new ConcurrentHashMap<>();
	/** Resolves the engine lazily (bootstrap ordering: the interceptor is
	 * constructed just before the engine finishes booting). */
	private final java.util.function.Supplier<FabricFoliaEngine> engineSupplier;
	private final Consumer<String> info;
	private final Consumer<String> error;
	/** Cumulative chunk-work executions, for worker-thread attribution
	 * evidence: world mutation proof must name the thread that mutated. */
	private final java.util.concurrent.atomic.AtomicLong executedChunkWork = new java.util.concurrent.atomic.AtomicLong();

	public RegionTickInterceptor(java.util.function.Supplier<FabricFoliaEngine> engineSupplier,
	                             Consumer<String> info,
	                             Consumer<String> error) {
		this.engineSupplier = engineSupplier;
		this.info = info;
		this.error = error;
	}

	// =================================================================================
	// Attach / detach (server lifecycle)
	// =================================================================================

	/** Called by the engine when a world attaches (server start event). */
	public void onWorldAttached(String worldName, WorldRegionizer regionizer, RegionScheduler scheduler) {
		attachments.put(worldName, new Attachment(regionizer, scheduler));
		passesByWorld.put(worldName, new Pass());
	}

	/**
	 * Removes all attachments (server stop). Vanilla's redirect checks
	 * attachment presence and falls through to its own consumer afterwards —
	 * no ticks are lost in the shutdown window.
	 */
	public void detachAll() {
		attachments.clear();
		passesByWorld.clear();
	}

	/** @return true if the named world is being intercepted (the mixin checks this). */
	public boolean isIntercepting(String worldName) {
		return attachments.containsKey(worldName);
	}

	// =================================================================================
	// The redirected per-chunk site
	// =================================================================================

	/**
	 * Called INSTEAD of vanilla's consumer.accept(chunk) for each chunk vanilla
	 * selected for block ticking — but only to REGISTER it; the actual work
	 * happens in {@link #flushPass} after vanilla's enumeration completes.
	 * Runs on the server thread (vanilla's tickChunks).
	 */
	public void offer(String worldName, ServerLevel level, LevelChunk chunk) {
		Pass pass = passesByWorld.get(worldName);
		if (pass == null) {
			// Not attached (or detached mid-pass): drop silently — vanilla
			// semantics for a chunk that lost its ticket between selection and
			// acceptance is the same skip.
			return;
		}
		pass.chunks.add(new OfferedChunk(level, chunk));
	}

	/**
	 * Groups the chunks offered this pass into regions and schedules the work
	 * onto region workers. Called on the server thread immediately after
	 * vanilla's forEachBlockTickingChunk call site returns (the mixin does
	 * this), i.e. inside vanilla's tickChunks where vanilla would have been
	 * executing the work inline.
	 *
	 * <p><strong>Regionize-first discipline:</strong> chunks are registered
	 * with the regionizer BEFORE jobs are scheduled, so the ownership picture
	 * the jobs see is complete for this pass. Jobs run asynchronously; the
	 * server thread does NOT wait (regions tick in parallel and finish on
	 * their own — vanilla's next-tick code that depends on random-tick
	 * results reads world state, which is single-writer per chunk by
	 * construction: each chunk belongs to exactly one region, invariant 1).</p>
	 */
	public void flushPass(String worldName) {
		Pass pass = passesByWorld.get(worldName);
		if (pass == null) {
			return;
		}
		List<OfferedChunk> offered = pass.chunks;
		if (offered.isEmpty()) {
			return;
		}
		pass.chunks = new ArrayList<>();

		Attachment attachment = attachments.get(worldName);
		if (attachment == null) {
			return; // detached between offer and flush: drop (documented)
		}

		// Group by owning region FIRST, then enqueue ONE batched job per
		// region per pass. One task per chunk was observed to outrun the
		// region's 50ms tick cadence under load (~250 chunks/pass on the
		// server thread vs one drain per tick): the backlog compounded into
		// multi-second ticks (and, in the extreme, a region latched TICKING
		// while its queue grew to 455k entries, freezing player movement).
		// Batching bounds the enqueue rate to one job per region per pass.
		Map<Region, List<OfferedChunk>> byRegion = new java.util.HashMap<>();
		for (OfferedChunk oc : offered) {
			// Regionize: the chunk position joins (or joins an existing)
			// region. Regionizer ops are thread-safe; this is the ONLY place
			// gameplay-adjacent code grows the region structure.
			long packed = oc.chunk.getPos().pack();
			Region region = attachment.regionizer().addChunk(
					ChunkPos.getX(packed), ChunkPos.getZ(packed));
			oc.region = region;

			// Edge guard: a chunk whose 3x3 neighborhood is not fully loaded
			// keeps its work off the worker this pass — vanilla's per-chunk
			// tick queries edge-adjacent blocks (precipitation heightmap),
			// and from a worker that request parks on a synchronous chunk
			// load the worker cannot pump. The next pass re-offers the chunk.
			if (!fabricfolia$neighborhoodLoaded(oc.level, ChunkPos.getX(packed), ChunkPos.getZ(packed))) {
				continue;
			}
			byRegion.computeIfAbsent(region, r -> new ArrayList<>()).add(oc);
		}

		int scheduledJobs = 0;
		int backpressured = 0;
		for (Map.Entry<Region, List<OfferedChunk>> e : byRegion.entrySet()) {
			Region region = e.getKey();
			List<OfferedChunk> batch = e.getValue();
			// Backpressure: if a region's queue is already carrying more than
			// ~2 passes' worth of batched work, skip this pass for it. Random
			// ticks are advisory gameplay — dropping a pass under saturation is
			// strictly better than starving the region's REAL work (player
			// movement and packet handling share this queue). The next pass
			// re-offers the same chunks: nothing is lost but one pass's delay.
			RegionTaskQueue queue = attachment.scheduler().queueOf(region);
			if (queue != null && queue.size() > 512) {
				backpressured++;
				continue;
			}
			// The engine's scheduler executes the batch on a region worker
			// after the single-owner latch (tryBeginTick) succeeds — or
			// re-homes it on merge (queue re-homing), never drops it silently
			// while the region lives.
			boolean enqueued = attachment.scheduler().enqueue(region, () ->
					runChunkWorkBatch(region, batch));
			if (enqueued) {
				scheduledJobs++;
			}
		}

		if (scheduledJobs > 0 || backpressured > 0) {
			// Per-tick diagnostic (diagnostics.debug-logging): which region got
			// how much work this pass. Normal operation stays quiet.
			if (debugLogging()) {
				info.accept("pass: dispatched " + scheduledJobs + " chunk-tick batch(es)"
						+ (backpressured > 0 ? " (backpressure skipped " + backpressured + ")" : "")
						+ " across " + worldName + " regions");
			}
		}
	}

	/** @return the debug-logging flag from the live engine (false pre-attach). */
	private boolean debugLogging() {
		FabricFoliaEngine current = engineSupplier.get();
		return current != null && current.config().debugLogging();
	}

	/** @return true when every chunk in the 3x3 neighborhood is loaded now (server thread; non-blocking probe). */
	private boolean fabricfolia$neighborhoodLoaded(ServerLevel level, int chunkX, int chunkZ) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (level.getChunkSource().getChunkNow(chunkX + dx, chunkZ + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * The work executed ON the region worker thread: the vanilla per-chunk
	 * random tick for every chunk in one pass's batch, in the region's
	 * context. The scheduler entered the region's context before draining the
	 * queue, so the whole batch runs under one ownership acquisition.
	 */
	private void runChunkWorkBatch(Region region, List<OfferedChunk> batch) {
		if (region.isDead()) {
			return; // region died between schedule and execution: documented drop
		}
		// STRICT diagnostics: the following access is legal only from this
		// region's context (the scheduler entered it before draining the
		// queue). Asserts the machinery is live on the real work path.
		FabricFoliaEngine engine = engineSupplier.get();
		if (engine != null) {
			engine.threadContext().assertRegionAccess("Chunk random-tick execution", region);
		}

		ServerLevel level = batch.get(0).level;
		// The vanilla body: precipitation + random ticks per chunk — exactly
		// the code vanilla would have run inline on the server thread.
		int randomTickSpeed = level.getGameRules()
				.get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
		long count = executedChunkWork.addAndGet(batch.size());
		if (debugLogging() && (count <= batch.size() || count % 2000 < batch.size())) {
			info.accept("WORKER-EVIDENCE: " + count + " chunk random-tick execution(s) on '"
					+ Thread.currentThread().getName() + "' (region " + region.world() + ":"
					+ region.regionId() + ", batch=" + batch.size() + ")");
		}
		for (OfferedChunk oc : batch) {
			// Execution-time edge check: the capture-time guard can go stale
			// when the region's queue is backed up (the walking player's wake
			// unloads edge chunks between capture and run). tickChunk queries
			// edge-adjacent blocks, and from a worker that request parks on a
			// synchronous chunk load the worker cannot pump — so such a chunk
			// bounces to the server thread, the chunk system's owner, exactly
			// where vanilla would have run it. The next pass re-offers loaded
			// chunks; a bounced chunk is a vanilla-consistent delayed pass.
			if (!fabricfolia$neighborhoodLoaded(level,
					oc.chunk.getPos().x(), oc.chunk.getPos().z())) {
				level.getServer().execute(() -> level.tickChunk(oc.chunk, randomTickSpeed));
				continue;
			}
			level.tickChunk(oc.chunk, randomTickSpeed);
		}
	}

	/** Builds the region tick body hook the engine's scheduler executes per tick. */
	public java.util.function.Consumer<Region> regionTickBody(String worldName) {
		return region -> {
			// Per-tick bookkeeping placeholder: chunk work is enqueued per
			// offer (flushPass), not per tick body; the body exists so the
			// scheduler's dispatch machinery treats this world's regions as
			// full tick participants and logs their liveness. The first tick
			// on a worker thread is the thread-evidence log the spec asks for.
			ThreadOwnership.Context context = ThreadOwnership.current();
			if (context.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
					&& context.region() == region
					&& region.tickCount() == 1
					&& debugLogging()) {
				info.accept("EVIDENCE: region " + region.world() + ":"
						+ region.regionId() + " ticked on worker thread '"
						+ Thread.currentThread().getName() + "'");
			}
		};
	}

	/**
	 * One vanilla per-chunk pass's worth of offered work for one world.
	 * Multi-producer safe (server thread today); swapped wholesale on flush.
	 */
	private static final class Pass {
		volatile List<OfferedChunk> chunks = new ArrayList<>();
	}

	/** A chunk offered for ticking, plus its resolved region once grouped. */
	private static final class OfferedChunk {
		final ServerLevel level;
		final LevelChunk chunk;
		volatile Region region;

		OfferedChunk(ServerLevel level, LevelChunk chunk) {
			this.level = level;
			this.chunk = chunk;
		}
	}
}
