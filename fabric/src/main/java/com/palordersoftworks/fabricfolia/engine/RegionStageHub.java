/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.metrics.RegionMetrics;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.scheduler.StageRetry;
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
	private static final AtomicLong EXECUTED_ON_SERVER = new AtomicLong();
	private static final AtomicLong PROBE_PASS = new AtomicLong();
	private static final AtomicLong PROBE_FAIL_WORLD_DOWN = new AtomicLong();
	private static final AtomicLong PROBE_FAIL_CHUNK_MISSING = new AtomicLong();
	private static final AtomicLong SUPPRESSED_BACKLOG = new AtomicLong();
	private static final AtomicLong INLINE_NO_PARALLEL = new AtomicLong();
	private static final AtomicLong REGION_BEHIND_SKIPPED = new AtomicLong();

	/**
	 * Backlog gate (backpressure): staging suspends for a world once its
	 * pending staged bodies exceed {@code max(BACKLOG_FLOOR,
	 * BACKLOG_MULTIPLE × last-flush size)}. While suspended, bodies execute
	 * vanilla-inline on the server thread — exact vanilla semantics, bounded
	 * memory, zero lost ticks. Non-final for tests.
	 */
	static volatile int BACKLOG_FLOOR = 8192;
	static volatile int BACKLOG_MULTIPLE = 2;

	/**
	 * Parallel-headroom gate for batch dispatch: a flush only ships its
	 * bodies to region workers when
	 * {@code totalBodies - largestRegionBodies >= MIN_PARALLEL_BODIES} —
	 * i.e. when concurrent execution across regions can actually recover
	 * more time than the stage/dispatch/worker-hop costs. Single-writer
	 * alternation means a dispatched body only RELOCATES serial work (the
	 * server waits it out at the next quiesce anyway); it saves wall time
	 * only through cross-region concurrency. On a one-hot-region load
	 * (measured 2026-10-06: 1500-entity clump, staging ON = 10.9 TPS vs
	 * staging OFF = 18.5 TPS, plus a suppressed-backlog latch and 6.4 s/min
	 * of quiesce blocking) unconditional dispatch was a net loss. Below the
	 * threshold the flush runs the batch inline on the server thread —
	 * same moment the workers would have started, gate still closed, zero
	 * queue/round-trip overhead. Non-final for tests (0 = always dispatch).
	 */
	static volatile int MIN_PARALLEL_BODIES = 32;

	/**
	 * Dispatch floor for single-region batches: at or above this many bodies
	 * a batch dispatches even without cross-region headroom — the point is
	 * ISOLATION. Chunked queue tasks (see {@link #BODIES_PER_TASK}) let a
	 * hot region's round drain across many inter-tick windows while the
	 * server never waits for more than one in-flight task, so shedding a
	 * large body load onto the region's own worker keeps the server tick
	 * fast instead of paying the bodies inline. Below the floor the batch
	 * runs inline at flush (dispatch overhead would exceed the saving).
	 * Non-final for tests.
	 */
	static volatile int MIN_DISPATCH_TOTAL = 64;

	/**
	 * Bodies per queue task. Bounds what a tick-start quiesce can wait on
	 * (one in-flight task, not a whole round: 256 × ~46 µs worst-case clump
	 * body ≈ 12 ms) and lets a slow region's round spread across windows.
	 * Non-final for tests.
	 */
	static volatile int BODIES_PER_TASK = 256;

	/**
	 * Region slow-motion intake gate: when a region's queue already holds
	 * more than this many TASKS at flush time, this round's bodies for that
	 * region are SKIPPED (counted, never run inline, never queued) — the
	 * region ticks at its own pace (Folia-style slow-motion for an
	 * overloaded region) instead of stalling the server tick or inflating
	 * the backlog until the suppression latch fires. Healthy regions drain
	 * between flushes and never hit this. PLAYER slices are exempt (packet
	 * processing must run every tick). Non-final for tests.
	 */
	static volatile int REGION_SKIP_THRESHOLD = 3;

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
		RegionStageHub hub = hubFor(level);
		return hub != null && hub.stagingEnabled(slice);
	}

	/**
	 * @return this level's hub, or null when staging is inactive for it.
	 * One map lookup — hot paths fetch the hub once and reuse it (the old
	 * isStaging-then-stage pattern cost two lookups plus a PatchRegistry
	 * map get per entity per tick).
	 */
	public static RegionStageHub hubFor(Level level) {
		return ACTIVE.get() ? HUBS_BY_LEVEL.get(level) : null;
	}

	/**
	 * @return true when this world's slice is registered as staged this
	 * session. Patch states are STARTUP_ONLY, so the per-slice flags are
	 * read from the registry exactly once (at first use, and again only if
	 * the registry's resolved state itself changes) — the hot path is an
	 * array read.
	 */
	public boolean stagingEnabled(Slice slice) {
		boolean resolved = com.palordersoftworks.fabricfolia.patches.PatchRegistry.isResolved();
		if (resolved != flagsResolved) {
			for (Slice s : Slice.values()) {
				stagingEnabledByPatch[s.ordinal()] = com.palordersoftworks.fabricfolia.patches.PatchRegistry
						.isEnabled(patchIdOf(s));
			}
			flagsResolved = resolved;
		}
		return stagingEnabledByPatch[slice.ordinal()];
	}

	private static String patchIdOf(Slice slice) {
		return switch (slice) {
			case ENTITY -> "stage-entity-ticks";
			case BLOCK_ENTITY -> "stage-block-entity-ticks";
			case SCHEDULED_TICK -> "stage-scheduled-ticks";
			case PLAYER -> "stage-player-path";
		};
	}

	/**
	 * Stages one vanilla execution body instead of running it on the server
	 * thread. Server thread only (vanilla's passes).
	 *
	 * @return true when the body was staged; false means the CALLER MUST
	 *         execute it inline (vanilla) — either staging is inactive or
	 *         the world's backlog gate is suppressing staging.
	 */
	public static boolean stage(Level level, Slice slice, Runnable body) {
		RegionStageHub hub = hubFor(level);
		return hub != null && hub.stageIfRoom(slice, body);
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

	public static long executedOnServerThread() {
		return EXECUTED_ON_SERVER.get();
	}

	public static long probePass() {
		return PROBE_PASS.get();
	}

	public static long probeFailWorldDown() {
		return PROBE_FAIL_WORLD_DOWN.get();
	}

	public static long probeFailChunkMissing() {
		return PROBE_FAIL_CHUNK_MISSING.get();
	}

	/** @return bodies that would have staged but ran vanilla-inline because of the backlog gate. */
	public static long suppressedBacklog() {
		return SUPPRESSED_BACKLOG.get();
	}

	/** @return bodies that flushed without parallel headroom and ran inline on the server thread (diagnostics). */
	public static long inlineNoParallel() {
		return INLINE_NO_PARALLEL.get();
	}

	/** @return bodies skipped at flush because their region's queue was backed up (slow-motion intake gate). */
	public static long regionBehindSkipped() {
		return REGION_BEHIND_SKIPPED.get();
	}

	// =================================================================================
	// Per-world hub
	// =================================================================================

	private final ServerLevel level;

	/** @return the live level this hub stages for (diagnostics). */
	public ServerLevel level() {
		return level;
	}

	/** @return bodies dispatched but not yet completed (backlog gauge). */
	public int pendingBodies() {
		return pendingBodies.get();
	}

	/** @return true while the backlog gate is forcing vanilla-inline execution. */
	public boolean stagingSuppressed() {
		return stagingSuppressed;
	}

	/** Test hook: forces the backlog gate state (null-safe; tests only). */
	void setStagingSuppressedForTests(boolean suppressed) {
		stagingSuppressed = suppressed;
	}

	/**
	 * Staging-time loadedness in VANILLA'S OWN terms: every chunk in the
	 * body's 3x3 neighborhood must be resolvable by
	 * {@code ServerChunkCache.getChunkNow} right now. Server thread only
	 * (vanilla's passes) — getChunkNow is main-thread-safe there and is the
	 * authoritative "vanilla would not join" check. A body that fails here
	 * runs inline on the server thread (vanilla semantics, including any
	 * sync load — which vanilla does itself, on its own thread).
	 *
	 * <p>Why this exists beyond {@link ChunkResidency}: residency is an
	 * engine-side approximation; a stale positive lets a body reach a worker
	 * and block in {@code getChunk(...).join()} — a synchronous chunk load
	 * no worker can pump. Under the tick-phase gate that becomes a quiesce
	 * livelock (observed live: fluid-tick bodies joining every tick,
	 * watchdog kill, 2026-10-05). The vanilla probe closes the gap at the
	 * only moment vanilla truth is cheap and safe to ask.</p>
	 *
	 * <p>No phase-A mutation happens between this staging check and the
	 * body's execution (workers only run in the inter-tick window), so a
	 * pass here means the body cannot join when it executes.</p>
	 */
	public boolean vanillaNeighborhoodLoaded(net.minecraft.world.level.ChunkPos pos) {
		if (level == null) {
			// Unit tests: no Minecraft level — fall back to engine residency.
			return ChunkResidency.isNeighborhoodResident(worldKey, pos.x(), pos.z());
		}
		var source = level.getChunkSource();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (source.getChunkNow(pos.x() + dx, pos.z() + dz) == null) {
					return false;
				}
			}
		}
		return true;
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

	/** Backlog gate state (see {@link #BACKLOG_FLOOR}); read per stage() call. */
	private volatile boolean stagingSuppressed;
	/** Bodies dispatched but not yet completed (or dropped with their batch). */
	private final java.util.concurrent.atomic.AtomicInteger pendingBodies =
			new java.util.concurrent.atomic.AtomicInteger();
	/** Bodies staged at the last flush — sizes the backlog limit (2× this, floor-bounded). */
	private volatile int lastFlushBodies;
	/** Cached per-slice patch flags (STARTUP_ONLY — resolved once, see stagingEnabled). */
	private final boolean[] stagingEnabledByPatch = new boolean[Slice.values().length];
	private volatile boolean flagsResolved;

	RegionStageHub(ServerLevel level, WorldRegionizer regionizer, RegionScheduler scheduler,
	               RegionMetrics metrics, Consumer<String> diagnostics) {
		this(level, level.dimension().identifier().toString(), regionizer, scheduler, metrics, diagnostics);
	}

	/** Full constructor: explicit level + world key (the delegating entry points above/below). */
	RegionStageHub(ServerLevel level, String worldKey, WorldRegionizer regionizer,
	               RegionScheduler scheduler, RegionMetrics metrics, Consumer<String> diagnostics) {
		this.level = level;
		this.worldKey = worldKey;
		this.regionizer = regionizer;
		this.scheduler = scheduler;
		this.metrics = metrics;
		this.diagnostics = diagnostics;
		regionizer.addListener(new StagingListener());
	}

	/** Package-private constructor keyed by world key (unit tests — no Minecraft level). */
	RegionStageHub(String worldKey, WorldRegionizer regionizer, RegionScheduler scheduler,
	               RegionMetrics metrics, Consumer<String> diagnostics) {
		this(null, worldKey, regionizer, scheduler, metrics, diagnostics);
	}

	/** This level's dimension key (drain scoping for the retry ledger). */
	private final String worldKey;

	/**
	 * Flush counter, fed to the retry ledger as its "tick": flush runs once
	 * per server tick, so spacing measured in flushes is spacing in ticks.
	 */
	private final java.util.concurrent.atomic.AtomicLong flushTick = new java.util.concurrent.atomic.AtomicLong();

	private void stage(Slice slice, Runnable body) {
		staged.get(slice).add(body);
		STAGED_TOTAL.incrementAndGet();
	}

	/**
	 * The staging decision with backpressure: refuses new work while the
	 * world's staged backlog exceeds the gate limit. Refusal is counted and
	 * the caller runs the body vanilla-inline — never dropped.
	 *
	 * @return true when staged; false → caller must run the body inline
	 */
	public boolean stageIfRoom(Slice slice, Runnable body) {
		if (stagingSuppressed) {
			SUPPRESSED_BACKLOG.incrementAndGet();
			return false;
		}
		stage(slice, body);
		return true;
	}

	/**
	 * Flushes this world's staged batches: groups every body by the region
	 * that owns its position and enqueues it onto that region's task queue.
	 * Server thread (engine end-of-server-tick hook), after all of vanilla's
	 * passes — the ownership picture is final for this tick.
	 */
	void flushStaged() {
		flushTick.incrementAndGet();
		int flushed = 0;
		// Replay bodies that failed on a transient concurrent-modification
		// race last tick (c2me async entity load): they are drained ahead of
		// the fresh batch so a retried body keeps its relative tick order.
		StageRetry.drain(worldKey, () -> {
			FabricFoliaEngine engine = com.palordersoftworks.fabricfolia.FabricFoliaMod.engine();
			return engine != null && engine.randomTickInterceptSuppressed();
		}, (w, body, attempt) -> {
			dispatchRetry((RetryBody) body, attempt);
			return true;
		}, flushTick::get);
		for (Map.Entry<Slice, List<Runnable>> entry : staged.entrySet()) {
			List<Runnable> batch = entry.getValue();
			if (batch.isEmpty()) {
				continue;
			}
			flushed += batch.size();
			dispatch(entry.getKey(), batch);
			entry.getValue().clear();
		}
		lastFlushBodies = flushed;
		// Leak reconciliation: every region queue empty AND no worker in
		// flight means any residual pending count belonged to batches
		// dropped with a dead region or at shutdown — resync so the gate
		// cannot stick on. (Region-queue sum, NOT the pool handoff: the
		// pool only holds coordinator-submitted tick jobs.)
		if (pendingBodies.get() > 0
				&& scheduler.pendingRegionQueueEntries() == 0
				&& scheduler.workerPool().busyCount() == 0) {
			pendingBodies.set(0);
		}
		// Backpressure: past the limit, staging suspends and bodies run
		// vanilla-inline until workers catch up (bounded memory, no lost ticks).
		int limit = Math.max(BACKLOG_FLOOR, lastFlushBodies * BACKLOG_MULTIPLE);
		boolean suppressed = pendingBodies.get() > limit;
		if (suppressed != stagingSuppressed) {
			stagingSuppressed = suppressed;
			diagnostics.accept(suppressed
					? "Region queue backlog " + pendingBodies.get() + " staged bodies exceeds limit "
							+ limit + " — suspending staging for " + worldKey
							+ "; bodies run vanilla-inline until workers catch up"
						: "Region queue backlog cleared — resuming staged execution for " + worldKey);
		}
		// Visibility: the peak queue watermark was never fed before.
		metrics.observeQueueDepth(scheduler.queuedRegionTasks());
	}

	private void dispatch(Slice slice, List<Runnable> batch) {
		dispatch(slice, batch, true);
	}

	/**
	 * @param allowRegionSkip false for retry replays — a retry that skipped
	 *        would be lost (the ledger already drained it), so retries always
	 *        execute even into a backed-up region.
	 */
	private void dispatch(Slice slice, List<Runnable> batch, boolean allowRegionSkip) {
		Map<Region, List<Runnable>> byRegion = new java.util.IdentityHashMap<>();
		Counters inline = null;
		for (Runnable body : batch) {
			Region region = regionFor(body);
			if (region == null) {
				// Unowned position: run inline on the server thread (rare).
				if (inline == null) {
					inline = new Counters();
				}
				runBody(slice, body, null, inline);
				continue;
			}
			byRegion.computeIfAbsent(region, r -> new java.util.ArrayList<>()).add(body);
		}
		if (inline != null) {
			inline.flush(this, slice);
		}
		// Region slow-motion intake gate: a region whose queue is still
		// holding prior rounds does not receive this round — its entities
		// tick again when it catches up (counted, never silently dropped,
		// never run inline: the inline fallback is what turned an overloaded
		// region into a server-tick stall). PLAYER bodies always proceed.
		if (allowRegionSkip && slice != Slice.PLAYER && !byRegion.isEmpty()) {
			java.util.Iterator<Map.Entry<Region, List<Runnable>>> slowMotion = byRegion.entrySet().iterator();
			while (slowMotion.hasNext()) {
				Map.Entry<Region, List<Runnable>> entry = slowMotion.next();
				var queue = scheduler.queueOf(entry.getKey());
				if (queue != null && queue.size() > REGION_SKIP_THRESHOLD) {
					REGION_BEHIND_SKIPPED.addAndGet(entry.getValue().size());
					slowMotion.remove();
				}
			}
		}
		// Parallel-headroom gate: dispatch only what cross-region concurrency can
		// pay for (see MIN_PARALLEL_BODIES). The flush runs on the server thread
		// with the execution gate still closed, so inline execution here is the
		// single-writer-safe equivalent of staged execution — minus the queue,
		// worker hop, and next-tick quiesce tail.
		int total = 0;
		int largest = 0;
		for (List<Runnable> group : byRegion.values()) {
			total += group.size();
			if (group.size() > largest) {
				largest = group.size();
			}
		}
		if (total - largest < MIN_PARALLEL_BODIES && total < MIN_DISPATCH_TOTAL) {
			if (total > 0) {
				INLINE_NO_PARALLEL.addAndGet(total);
				Counters counters = new Counters();
				for (Map.Entry<Region, List<Runnable>> entry : byRegion.entrySet()) {
					for (Runnable body : entry.getValue()) {
						runBody(slice, body, entry.getKey(), counters);
					}
				}
				counters.flush(this, slice);
			}
			return;
		}
		// ONE queue entry per CHUNK per region per slice: bounded task size
		// keeps the tick-start quiesce to one small in-flight task and lets a
		// hot region's round drain across many inter-tick windows. The whole
		// chunk list goes in a single enqueueBatch call, whose dead-region
		// drop is all-or-nothing — pending accounting stays exact.
		for (Map.Entry<Region, List<Runnable>> entry : byRegion.entrySet()) {
			List<Runnable> bodies = entry.getValue();
			List<Runnable> tasks = new java.util.ArrayList<>((bodies.size() + BODIES_PER_TASK - 1) / BODIES_PER_TASK);
			for (int start = 0; start < bodies.size(); start += BODIES_PER_TASK) {
				int end = Math.min(start + BODIES_PER_TASK, bodies.size());
				Runnable[] chunk = bodies.subList(start, end).toArray(Runnable[]::new);
				tasks.add(new StagedBatch(slice, chunk, entry.getKey()));
			}
			pendingBodies.addAndGet(bodies.size());
			int dropped = scheduler.enqueueBatch(entry.getKey(), tasks);
			if (dropped > 0) {
				pendingBodies.addAndGet(-bodies.size());
				DROPPED_DEAD_REGION.addAndGet(bodies.size());
			}
		}
	}

	/** Resolves the owning region for a staged body (its chunk position). */
	private Region regionFor(Runnable body) {
		if (body instanceof Positioned positioned) {
			ChunkPos pos = positioned.fabricfolia$position();
			return pos == null ? null : regionizer.ownerOfChunk(pos.x(), pos.z());
		}
		return null;
	}


	/**
	 * Executes ONE staged body under shared batch counters. {@code region}
	 * is the owning region captured at dispatch (null = server-thread inline
	 * execution: unowned bodies at flush, and bounced bodies replayed in the
	 * next tick phase).
	 */
	private void runBody(Slice slice, Runnable body, Region region, Counters counters) {
		if (region != null
				&& ThreadOwnership.current().kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
				&& !bodyNeighborhoodLoaded(body, counters)) {
			// Execution-time bounce: the staging capture's loadedness check can
			// go stale when a region's queue is backed up — by the time this
			// body runs, its edge chunks may have unloaded (a walking player's
			// wake). Such a body would park a worker on a SYNCHRONOUS chunk
			// load the worker cannot pump (observed live as a region latched
			// TICKING for minutes while every packet and movement task for its
			// chunks starved behind it). The server thread owns the chunk
			// system, so hand the body back there — through
			// ServerThreadDeferral, which drains on the server thread during
			// the TICK PHASE with workers quiesced. A raw server.execute would
			// pump during the inter-tick window, i.e. concurrently with
			// phase-B workers — exactly the cross-thread access the phase gate
			// exists to remove.
			counters.bounced++;
			ServerThreadDeferral.defer(() -> runStandalone(slice, body));
			return;
		}
		try {
			body.run();
			if (region != null
					&& ThreadOwnership.current().kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION) {
				counters.executedWorkers++;
			} else {
				counters.executedServer++;
			}
			counters.sliceTicks++;
		} catch (Throwable t) {
			metrics.increment(RegionMetrics.Counter.EXCEPTIONS_ISOLATED);
			if (fabricfolia$isConcurrentEntityAccessFailure(t)) {
				// TRANSIENT, NOT FATAL: a foreign async entity loader (c2me's
				// "Async entity load" guard) threw because this body touched an
				// entity section it was mid-load on. The body never ran; dropping
				// it silently loses a gameplay tick (production: suppressed CMEs
				// on use_item, Palorder Central 2026-09-25). Fix, not suppress:
				// schedule a tick-spaced retry through the world's own dispatch.
				int attempt = (body instanceof RetryBody retry) ? retry.attempt() : 0;
				StageRetry.schedule(worldKey, retryOf(slice, body, attempt + 1), attempt, flushTick::get);
				diagnostics.accept("Staged " + slice + " body hit a concurrent entity access race; retry "
						+ (attempt + 1) + "/" + StageRetry.MAX_ATTEMPTS + " scheduled"
						+ (region != null ? " in region " + region.world() + ":" + region.regionId() : ""));
				return;
			}
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
	private boolean bodyNeighborhoodLoaded(Runnable body, Counters counters) {
		if (!(body instanceof Positioned positioned)) {
			return true;
		}
		net.minecraft.world.level.ChunkPos pos = positioned.fabricfolia$position();
		if (pos == null) {
			return true;
		}
		// worldKey is the hub's cached dimension string — the old code built
		// a fresh String per probe per body here.
		if (!ChunkResidency.isNeighborhoodResident(worldKey, pos.x(), pos.z())) {
			if (ChunkResidency.residentCount(worldKey) == 0) {
				counters.probeFailWorldDown++;
			} else {
				counters.probeFailChunkMissing++;
			}
			return false;
		}
		counters.probePass++;
		return true;
	}

	/** Runs one body on the calling (server) thread, flushing its counters immediately. */
	private void runStandalone(Slice slice, Runnable body) {
		Counters counters = new Counters();
		try {
			runBody(slice, body, null, counters);
		} finally {
			counters.flush(this, slice);
		}
	}

	/**
	 * Per-batch tallies: plain locals on the hot path, flushed as ONE round
	 * of atomic adds per batch — the old path took 4+ contended
	 * AtomicLong CASes and a metrics-map lookup per body per tick.
	 */
	private static final class Counters {
		long executedWorkers;
		long executedServer;
		long probePass;
		long probeFailWorldDown;
		long probeFailChunkMissing;
		long bounced;
		long sliceTicks;

		void flush(RegionStageHub hub, Slice slice) {
			if (executedWorkers > 0) {
				EXECUTED_ON_WORKERS.addAndGet(executedWorkers);
			}
			if (executedServer > 0) {
				EXECUTED_ON_SERVER.addAndGet(executedServer);
			}
			if (probePass > 0) {
				PROBE_PASS.addAndGet(probePass);
			}
			if (probeFailWorldDown > 0) {
				PROBE_FAIL_WORLD_DOWN.addAndGet(probeFailWorldDown);
			}
			if (probeFailChunkMissing > 0) {
				PROBE_FAIL_CHUNK_MISSING.addAndGet(probeFailChunkMissing);
			}
			if (bounced > 0) {
				BOUNCED_TO_SERVER.addAndGet(bounced);
			}
			if (sliceTicks > 0) {
				hub.metrics.add(counterOf(slice), sliceTicks);
			}
		}
	}

	/**
	 * ONE region-queue task carrying a whole slice's bodies for one region.
	 * Executed on the owning region's worker in REGION context; completion
	 * (or any throw) releases the batch's pending-backlog count.
	 */
	private final class StagedBatch implements Runnable {
		private final Slice slice;
		private final Runnable[] bodies;
		private final Region target;

		private StagedBatch(Slice slice, Runnable[] bodies, Region target) {
			this.slice = slice;
			this.bodies = bodies;
			this.target = target;
		}

		@Override
		public void run() {
			Counters counters = new Counters();
			try {
				for (int i = 0; i < bodies.length; i++) {
					runBody(slice, bodies[i], target, counters);
				}
			} finally {
				counters.flush(RegionStageHub.this, slice);
				// Clamp at zero: if a flush-time reconcile already zeroed the
				// gauge (queue empty + no worker in flight), this completion
				// must not drive it negative.
				pendingBodies.updateAndGet(p -> Math.max(0, p - bodies.length));
			}
		}
	}

	// =================================================================================
	// Transient-race retries (c2me async entity load): fix, not suppress
	// =================================================================================

	/**
	 * A staged body wrapper carrying its slice and retry attempt through the
	 * dispatch pipeline (fresh bodies are never wrapped; attempt 0 = original
	 * execution failed, 1.. = a retry failed). Delegates the position probe to
	 * the inner body so region resolution and the loadedness pre-flight see
	 * the real body — a wrapper must be dispatch-transparent.
	 */
	public record RetryBody(Slice slice, Runnable body, int attempt) implements Positioned, Runnable {
		@Override
		public ChunkPos fabricfolia$position() {
			return (body instanceof Positioned positioned) ? positioned.fabricfolia$position() : null;
		}

		@Override
		public void run() {
			body.run();
		}
	}

	/** Re-dispatches one due retry through the same pipeline as a fresh body (never skipped — the ledger already drained it). */
	private void dispatchRetry(RetryBody retry, int attempt) {
		dispatch(retry.slice(), java.util.List.<Runnable>of(retry), false);
	}

	/** Wraps a failed body for retry, preserving its slice. */
	private static RetryBody retryOf(Slice slice, Runnable body, int attempt) {
		return (body instanceof RetryBody retry)
				? new RetryBody(retry.slice(), retry.body(), attempt)
				: new RetryBody(slice, body, attempt);
	}

	/**
	 * @return true when {@code t} is the transient concurrent-entity-access
	 * failure mode (a {@link java.util.ConcurrentModificationException} —
	 * c2me's async entity loader throws its fail-fast guard as a CME).
	 * Deliberately narrow: generic CMEs from body bugs stay on the plain
	 * failure path with their full diagnostic.
	 */
	private static boolean fabricfolia$isConcurrentEntityAccessFailure(Throwable t) {
		return t instanceof java.util.ConcurrentModificationException;
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
