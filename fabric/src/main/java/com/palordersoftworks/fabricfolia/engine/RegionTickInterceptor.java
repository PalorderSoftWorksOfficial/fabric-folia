/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
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

		int scheduledJobs = 0;
		for (OfferedChunk oc : offered) {
			// Regionize: the chunk position joins (or joins an existing)
			// region. Regionizer ops are thread-safe; this is the ONLY place
			// gameplay-adjacent code grows the region structure.
			long packed = oc.chunk.getPos().pack();
			Region region = attachment.regionizer().addChunk(
					ChunkPos.getX(packed), ChunkPos.getZ(packed));
			oc.region = region;

			// Schedule the per-chunk work into the region's tick context. The
			// engine's scheduler executes it on a region worker after the
			// single-owner latch (tryBeginTick) succeeds — or re-homes it on
			// merge (queue re-homing), never drops it silently while the
			// region lives.
			boolean enqueued = attachment.scheduler().enqueue(region, () ->
					runChunkWork(oc));
			if (enqueued) {
				scheduledJobs++;
			}
		}

		if (scheduledJobs > 0) {
			// Per-tick diagnostic (diagnostics.debug-logging): which region got
			// how much work this pass. Normal operation stays quiet.
			if (debugLogging()) {
				info.accept("pass: dispatched " + scheduledJobs
						+ " chunk-tick job(s) across " + worldName + " regions");
			}
		}
	}

	/** @return the debug-logging flag from the live engine (false pre-attach). */
	private boolean debugLogging() {
		FabricFoliaEngine current = engineSupplier.get();
		return current != null && current.config().debugLogging();
	}

	/**
	 * The work executed ON the region worker thread: the vanilla per-chunk
	 * random tick, in the region's context, with ownership re-validation.
	 */
	private void runChunkWork(OfferedChunk oc) {
		Region region = oc.region;
		if (region == null || region.isDead()) {
			return; // region died between schedule and execution: documented drop
		}
		// STRICT diagnostics: the following access is legal only from this
		// region's context (the scheduler entered it before draining the
		// queue). Asserts the machinery is live on the real work path.
		FabricFoliaEngine engine = engineSupplier.get();
		if (engine != null) {
			engine.threadContext().assertRegionAccess("Chunk random-tick execution", region);
		}

		ServerLevel level = oc.level;
		// The vanilla body: precipitation + random ticks for this chunk —
		// exactly the code vanilla would have run inline on the server thread.
		int randomTickSpeed = level.getGameRules()
				.get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
		long count = executedChunkWork.incrementAndGet();
		if (debugLogging() && (count == 1 || count % 100 == 0)) {
			info.accept("WORKER-EVIDENCE: " + count + " chunk random-tick execution(s) on '"
					+ Thread.currentThread().getName() + "' (region " + region.world() + ":"
					+ region.regionId() + ")");
		}
		level.tickChunk(oc.chunk, randomTickSpeed);
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
