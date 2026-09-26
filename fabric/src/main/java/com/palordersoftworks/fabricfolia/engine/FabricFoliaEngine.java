/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.metrics.RegionMetrics;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.region.WorldRegionizerRegistry;
import com.palordersoftworks.fabricfolia.scheduler.AsyncSchedulerImpl;
import com.palordersoftworks.fabricfolia.scheduler.GlobalSchedulerImpl;
import com.palordersoftworks.fabricfolia.scheduler.RegionDataHub;
import com.palordersoftworks.fabricfolia.scheduler.RegionPendingTicks;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadContextImpl;
import com.palordersoftworks.fabricfolia.thread.ViolationReporter;

import java.util.List;

/**
 * Fabric-side holder for the engine: the shared worker pool with one scheduler
 * per dimension, the global context, and the thread-context diagnostics.
 *
 * <p><strong>Multi-world model (integration phase):</strong> each dimension
 * gets its own {@link WorldRegionizer} (regions never span worlds — the
 * spec's invariant 1 is per-world) and its own {@link RegionScheduler}
 * coordinator, all sharing ONE bounded worker pool (spec 10: regions are
 * schedulable onto a bounded worker pool; the pool is world-agnostic).
 * {@link #attachWorld} creates the pair at server start for every live
 * dimension; the vanilla intercept layer resolves worlds through
 * {@link #regionizerFor}.</p>
 *
 * <p><strong>Interception status (honest, spec 17):</strong> with
 * {@code regionized-random-ticks} enabled, the per-chunk random-tick pass
 * (and its precipitation pass) of every attached dimension executes on region
 * worker threads — see {@code ServerChunkCacheTickMixin} and
 * {@link RegionTickInterceptor}. Everything else (entities, block entities,
 * scheduled ticks, worldgen, spawning) remains on the server thread; that
 * boundary is logged at attach time and reported by the commands.</p>
 */
public final class FabricFoliaEngine {

	private final FoliaConfig config;
	/** Legacy dispatch policy (mandate §39): where undeclared work runs. */
	private final com.palordersoftworks.fabricfolia.scheduler.LegacyDispatchPolicy legacyDispatchPolicy =
			new com.palordersoftworks.fabricfolia.scheduler.LegacyDispatchPolicy();
	/** The one bounded worker pool every world's scheduler dispatches onto. */
	private final com.palordersoftworks.fabricfolia.scheduler.WorkerPool workerPool;
	private final int workerThreadCount;
	private final WorldRegionizerRegistry regionizers = new WorldRegionizerRegistry();
	/** Per-world schedulers sharing {@link #workerPool} (one per dimension). */
	private final java.util.concurrent.ConcurrentHashMap<String, RegionScheduler> schedulersByWorld =
			new java.util.concurrent.ConcurrentHashMap<>();
	/** Per-world region-data hubs (region-local data lifecycle fan-out). */
	private final java.util.concurrent.ConcurrentHashMap<String, com.palordersoftworks.fabricfolia.scheduler.RegionDataHub> hubsByWorld =
			new java.util.concurrent.ConcurrentHashMap<>();
	/** Per-world entity-ownership trackers (mandate §15, live vanilla hooks). */
	private final java.util.concurrent.ConcurrentHashMap<String, com.palordersoftworks.fabricfolia.entity.EntityRegionTracker> entityTrackersByWorld =
			new java.util.concurrent.ConcurrentHashMap<>();
	/** Per-world entity schedulers (mandate §14 — follow the owning region). */
	private final java.util.concurrent.ConcurrentHashMap<String, com.palordersoftworks.fabricfolia.scheduler.EntitySchedulerImpl> entitySchedulersByWorld =
			new java.util.concurrent.ConcurrentHashMap<>();
	private final GlobalSchedulerImpl global;
	private final AsyncSchedulerImpl async;
	private final ThreadContextImpl threadContext;
	private final ViolationReporter reporter;
	private final Thread globalDispatchThread;
	private final java.util.function.Consumer<String> info;
	private final java.util.function.Consumer<String> error;
	private RegionTickInterceptor interceptor;
	/** Per-world gameplay staging hubs (entity/block-entity bodies to region workers, mandate 27). */
	private final java.util.concurrent.ConcurrentHashMap<String, RegionStageHub> stagingHubsByWorld =
			new java.util.concurrent.ConcurrentHashMap<>();
	/**
	 * Per-world pending-tick ledgers (mandate 21), keyed "world:container"
	 * (block / fluid): region-owned accounting of captured scheduled ticks.
	 */
	private final java.util.concurrent.ConcurrentHashMap<String, com.palordersoftworks.fabricfolia.scheduler.RegionPendingTicks> pendingTickLedgersByWorld =
			new java.util.concurrent.ConcurrentHashMap<>();
	/** Server-wide gameplay metrics (mandate 35; reported by /folia metrics). */
	private RegionMetrics metrics;
	/** The public mod-facing API (mandate 41); created at bootstrap. */
	private FabricFoliaApiImpl api;
	/**
	 * Why the regionized-random-ticks intercept was suppressed at startup, or
	 * null when it armed normally. Pure presentation state: the Mixin consults
	 * suppression (not config) per tick pass, so what the admin is told is
	 * exactly what runs.
	 */
	private volatile String interceptSuppressedReason;
	/** Region-aware stall watchdog (created+started at bootstrap). */
	private com.palordersoftworks.fabricfolia.scheduler.RegionWatchdog watchdog;

	private FabricFoliaEngine(FoliaConfig config,
	                          com.palordersoftworks.fabricfolia.scheduler.WorkerPool workerPool,
	                          int workerThreadCount,
	                          GlobalSchedulerImpl global,
	                          AsyncSchedulerImpl async,
	                          ThreadContextImpl threadContext,
	                          ViolationReporter reporter,
	                          Thread globalDispatchThread,
	                          java.util.function.Consumer<String> info,
	                          java.util.function.Consumer<String> error) {
		this.config = config;
		this.metrics = new RegionMetrics(FoliaConfig.WorkerThreads.resolve(config.workerThreads()));
		this.api = new FabricFoliaApiImpl(this);
		this.workerPool = workerPool;
		this.workerThreadCount = workerThreadCount;
		this.global = global;
		this.async = async;
		this.threadContext = threadContext;
		this.reporter = reporter;
		this.globalDispatchThread = globalDispatchThread;
		this.info = info;
		this.error = error;
	}

	/**
	 * Bootstraps the engine from validated config.
	 *
	 * @param info info-level log sink
	 * @param error error-level log sink
	 */
	public static FabricFoliaEngine bootstrap(FoliaConfig config,
	                                          java.util.function.Consumer<String> info,
	                                          java.util.function.Consumer<String> error) {
		ValidationMode mode = ValidationMode.valueOf(config.threadCheckMode());
		int workerThreads = FoliaConfig.WorkerThreads.resolve(config.workerThreads());

		// The engine owns the shared worker pool directly: worlds attach through
		// attachWorld() and their schedulers share it. There is no primary
		// scheduler — a pool owner that never receives chunks is not a concept.
		com.palordersoftworks.fabricfolia.scheduler.WorkerPool workerPool =
				new com.palordersoftworks.fabricfolia.scheduler.WorkerPool(workerThreads, "FabricFolia-Worker");

		GlobalSchedulerImpl global = new GlobalSchedulerImpl();

		// The async scheduler (mandate §12): distinct pool from the global
		// dispatch thread and region workers — async load must never stall
		// global state progression. Task failures are reported through the
		// diagnostics sink (same convention as region task failures).
		AsyncSchedulerImpl async = new AsyncSchedulerImpl(workerThreads, info::accept);
		info.accept("Initializing asynchronous scheduler (" + workerThreads + " dedicated async threads).");

		ViolationReporter reporter = new ViolationReporter(mode,
				(message, throwable) -> {
					error.accept(message);
					if (throwable != null) {
						info.accept(String.valueOf(throwable));
					}
				});
		ViolationReporter.installProcessReporter(reporter);
		ThreadContextImpl threadContext = new ThreadContextImpl(reporter);

		// Region workers get their own RandomSource instances while the engine
		// lives (measured LegacyRandomSource cross-thread defect — see
		// WorkerRandoms); deactivation restores vanilla's single-instance
		// behavior exactly at shutdown. Inert whenever the engine is disabled.
		com.palordersoftworks.fabricfolia.thread.WorkerRandoms.activate();
		// Global context cadence: one tick() per 50ms on a dedicated daemon
		// thread (the single consumer of the global queue — see
		// GlobalSchedulerImpl's contract). Folding this into the pool's
		// dispatch is a later optimization with benchmark evidence (spec 24/20).
		Thread globalDispatch = new Thread(() -> {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					Thread.sleep(50);
					global.tick();
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "FabricFolia-Global");
		globalDispatch.setDaemon(true);
		globalDispatch.start();

		FabricFoliaEngine bootstrapped = new FabricFoliaEngine(config, workerPool, workerThreads,
				global, async, threadContext, reporter, globalDispatch, info, error);
		bootstrapped.adoptLegacyDispatchPolicy(
				com.palordersoftworks.fabricfolia.FabricFoliaMod.pendingDispatchPolicy());
		// Region-aware stall detection (mandate §26): watches all attached
		// worlds' regions for overdue TICKING states and reports with
		// region/ownership context.
		bootstrapped.watchdog = new com.palordersoftworks.fabricfolia.scheduler.RegionWatchdog(
				() -> {
					java.util.List<com.palordersoftworks.fabricfolia.region.Region> all =
							new java.util.ArrayList<>();
					for (RegionScheduler s : bootstrapped.schedulersByWorld.values())	{
						all.addAll(s.liveRegions());
					}
					return all;
				},
				workerPool::keepaliveSnapshot,
				info::accept,
				com.palordersoftworks.fabricfolia.scheduler.RegionWatchdog.DEFAULT_INTERVAL_MILLIS);
		if (config.watchdog()) {
			bootstrapped.watchdog.start();
		}
		// Global-state ownership registration (mandate §11): the global
		// dispatch context owns the server-wide domains; regions own the rest.
		GlobalStateRegistry.reset();
		GlobalStateRegistry.activate(GlobalStateRegistry.Domain.DAYLIGHT_TIME, "FabricFolia-Global");
		GlobalStateRegistry.activate(GlobalStateRegistry.Domain.WEATHER, "FabricFolia-Global");
		GlobalStateRegistry.activate(GlobalStateRegistry.Domain.WORLD_BORDER, "FabricFolia-Global");
		GlobalStateRegistry.activate(GlobalStateRegistry.Domain.GAME_RULES, "FabricFolia-Global");
		GlobalStateRegistry.activate(GlobalStateRegistry.Domain.PLAYER_LIST, "FabricFolia-Global");
		GlobalStateRegistry.activate(GlobalStateRegistry.Domain.SCOREBOARD, "FabricFolia-Global");
		return bootstrapped;
	}

	/**
	 * Wires the intercept layer (called once by the mod entrypoint after
	 * bootstrap; the engine needs it before the first tick pass).
	 */
	public void setInterceptor(RegionTickInterceptor interceptor) {
		this.interceptor = interceptor;
	}

	/**
	 * Attaches a live dimension: creates its regionizer (derived from the
	 * effective simulation distance) and its scheduler (sharing the worker
	 * pool), and — when the intercept is opted in — wires the region tick
	 * body that executes vanilla random ticks on region workers.
	 *
	 * <p><strong>Contract:</strong> called once per dimension from the server
	 * start event (server thread). Idempotent per world name; a later call for
	 * an already-attached world returns the existing attachment unchanged
	 * (config is applied at creation, per-world).</p>
	 *
	 * @param worldName stable name of the dimension (e.g. {@code minecraft:overworld})
	 * @param simulationDistanceChunks the dimension's effective simulation distance
	 * @param interceptor the vanilla intercept hook for this world, or null if
	 *                    the intercept is disabled (regionizer still attached
	 *                    so diagnostics and commands see real structure)
	 */
	public void attachWorld(String worldName, int simulationDistanceChunks,
	                        RegionTickInterceptor interceptor) {
		boolean interceptActive = config.regionizedRandomTicks() && interceptor != null;
		RegionizerConfig regionizerConfig = RegionizerConfig.derive(simulationDistanceChunks);
		WorldRegionizer regionizer = regionizers.getOrCreate(worldName, name -> regionizerConfig);

		RegionScheduler scheduler = schedulersByWorld.computeIfAbsent(worldName, name -> {
			RegionScheduler shared = new RegionScheduler(regionizer,
					workerPool,
					workerThreadCount,
					ValidationMode.valueOf(config.threadCheckMode()),
					interceptActive ? interceptor.regionTickBody(worldName) : region -> { },
					message -> info.accept(message));
			shared.setMetrics(metrics);
			shared.start();
			return shared;
		});

		// Region-local data hub for this world (created once; the entity
		// registry and future per-region systems register with it).
		hubsByWorld.computeIfAbsent(worldName, name -> {
			com.palordersoftworks.fabricfolia.scheduler.RegionDataHub hub =
					new com.palordersoftworks.fabricfolia.scheduler.RegionDataHub();
			hub.attachTo(regionizer);
			return hub;
		});

		// Ownership-check authority (mandates §7/§8): the regionizer is the
		// single source for reverse ownership lookups (checks, commands).
		com.palordersoftworks.fabricfolia.thread.RegionChecks.installAuthority(worldName, regionizer);

		// Structural + chunk lifecycle metrics (mandate §35): the regionizer
		// fires the sinks; the engine maps them onto the shared metric
		// registry. Per-world regionizers share one counter set — totals.
		regionizer.setChunkMetricSink(new WorldRegionizer.ChunkMetricSink() {
			@Override
			public void chunkRegistered() {
					metrics.increment(RegionMetrics.Counter.CHUNK_REGISTRATIONS);
				}

				@Override
				public void chunkUnregistered() {
					metrics.increment(RegionMetrics.Counter.CHUNK_UNREGISTRATIONS);
				}
		});
		regionizer.setStructuralMetricSink(new WorldRegionizer.StructuralMetricSink() {
			@Override
			public void regionMerged() {
				metrics.increment(RegionMetrics.Counter.REGION_MERGES);
			}

			@Override
			public void regionSplit() {
				metrics.increment(RegionMetrics.Counter.REGION_SPLITS);
			}

			@Override
			public void regionAborted() {
				metrics.increment(RegionMetrics.Counter.REGION_ABORTS);
			}
		});

		boolean suppressed = interceptSuppressedReason != null;
		if (interceptActive && suppressed) {
			info.accept("World attached: " + worldName
					+ " (regionized random ticks SUPPRESSED - vanilla execution for this slice; "
					+ suppressionDetailLine() + ").");
		} else if (interceptActive) {
			interceptor.onWorldAttached(worldName, regionizer, scheduler);
			info.accept("World attached: " + worldName
					+ " (regionized random ticks ACTIVE on worker threads; "
					+ "section size " + config.regionSectionSize()
					+ ", merge/creation radii derived from simulation distance "
					+ simulationDistanceChunks + ").");
		} else {
			info.accept("World attached: " + worldName
					+ " (structure-only: regionized random ticks disabled, vanilla execution untouched).");
		}
	}

	/**
	 * Routes a vanilla chunk-unload event to the owning world's regionizer
	 * (mandate §20): the section loses its chunk registration, may empty and
	 * die, and the tick-end split path gains its real input. Called from the
	 * server-thread unload hook; defensive no-op when the world is unknown.
	 *
	 * @param levelContextHolder the ServerLevel whose chunk unloaded (mixin
	 *                          {@code this}, cast here to keep MC types out of
	 *                          mixin signatures)
	 * @param chunk             the chunk being unloaded
	 */
	public void onChunkUnloaded(Object levelContextHolder, net.minecraft.world.level.chunk.LevelChunk chunk) {
		net.minecraft.server.level.ServerLevel level =
				(net.minecraft.server.level.ServerLevel) levelContextHolder;
		String worldName = level.dimension().identifier().toString();
		var pos = chunk.getPos();
		ChunkResidency.markUnresident(worldName, pos.x(), pos.z());
		WorldRegionizer regionizer = regionizers.get(worldName);
		if (regionizer == null) {
			return;
		}
		regionizer.removeChunk(pos.x(), pos.z());
	}

	/**
	 * Attaches entity-ownership tracking to a live dimension (mandate §15):
	 * creates the per-world entity registry on the region-data hub, backfills
	 * every entity vanilla has already loaded, and wires the entity scheduler
	 * that follows entities across region migrations.
	 *
	 * <p><strong>Contract:</strong> called once per dimension AFTER
	 * {@link #attachWorld} (the hub and regionizer must exist), from the
	 * server thread. Idempotent per world name.</p>
	 *
	 * @param level the dimension to track
	 */
	public void attachEntityTracking(net.minecraft.server.level.ServerLevel level) {
		String worldName = level.dimension().identifier().toString();
		com.palordersoftworks.fabricfolia.scheduler.RegionDataHub hub = hubsByWorld.get(worldName);
		WorldRegionizer regionizer = regionizers.get(worldName);
		RegionScheduler scheduler = schedulersByWorld.get(worldName);
		if (hub == null || regionizer == null || scheduler == null) {
			// World not attached (engine disabled mid-startup): no tracking.
			return;
		}
		entityTrackersByWorld.computeIfAbsent(worldName, name -> {
			com.palordersoftworks.fabricfolia.entity.EntityRegionTracker tracker =
					new com.palordersoftworks.fabricfolia.entity.EntityRegionTracker(
							level, name, regionizer, hub, info::accept);
			tracker.backfillExisting();
			return tracker;
		});
		// The entity scheduler follows the registry's resolver: tasks resolve
		// the owning region at execution time (mandate §14).
		entitySchedulersByWorld.computeIfAbsent(worldName, name ->
				new com.palordersoftworks.fabricfolia.scheduler.EntitySchedulerImpl(
						scheduler, entityTrackersByWorld.get(name).resolver()));
		// Ownership-check entity resolver (mandate §7): the registry's
		// migration protocol is the entity-ownership authority.
		com.palordersoftworks.fabricfolia.scheduler.EntitySchedulerImpl.EntityResolver resolver =
				entityTrackersByWorld.get(worldName).resolver();
		com.palordersoftworks.fabricfolia.thread.RegionChecks.installEntityResolver(resolver::apply);
		info.accept("  Entity ownership tracking active for " + worldName
				+ " (add/remove/move hooks live).");

		// Regionized gameplay staging (mandates 15/27): entity tick bodies
		// and block-entity tick bodies execute on the owning region's worker.
		// Vanilla still decides WHAT to tick (its passes run on the server
		// thread); only the bodies move. The scheduled-tick deferral buffers
		// worker-side scheduleTick calls and replays them server-thread.
		if (config.regionizedGameplay()) {
			RegionStageHub stageHub = stagingHubsByWorld.computeIfAbsent(worldName, name ->
					new RegionStageHub(level, regionizer, scheduler, metrics, info::accept));
			ScheduledTickDeferral.registerLevel(level);
			// Region-owned pending-tick ledgers (mandate 21): one per
			// container, registered with the deferral so worker capture
			// records into them and drain execution releases from them.
			RegionDataHub worldHub = hubsByWorld.get(worldName);
			RegionPendingTicks blockLedger = pendingTickLedgersByWorld
					.computeIfAbsent(worldName + ":block", name ->
							new RegionPendingTicks(name, regionizer, worldHub));
			RegionPendingTicks fluidLedger = pendingTickLedgersByWorld
					.computeIfAbsent(worldName + ":fluid", name ->
							new RegionPendingTicks(name, regionizer, worldHub));
			ScheduledTickDeferral.registerLedger(level.getBlockTicks(), blockLedger);
			ScheduledTickDeferral.registerLedger(level.getFluidTicks(), fluidLedger);
			RegionStageHub.activate(level, stageHub);
			ScheduledTickDeferral.activate();
			info.accept("  Regionized gameplay ACTIVE for " + worldName
					+ " (entity and block-entity tick bodies execute on region workers; "
					+ "scheduled-tick writes from workers are deferred to the server thread; "
					+ "see THREADING.md).");
		}
	}

	/**
	 * End-of-server-tick gameplay flush (mandates 15/27/21): dispatch each
	 * world's staged entity/block-entity bodies to their owning regions and
	 * replay the scheduled ticks region workers deferred. Server thread
	 * (the END_SERVER_TICK event fires there). Inert when gameplay staging
	 * never activated.
	 */
	public void flushGameplay() {
		for (RegionStageHub hub : stagingHubsByWorld.values()) {
			hub.flushStaged();
		}
		com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral.replayOnServerThread();
	}

	/** @return the server-wide gameplay metrics snapshot (mandate 35). */
	public RegionMetrics metrics() {
		return metrics;
	}

	/** @return the public mod-facing API instance (delivered to entrypoints). */
	public FabricFoliaApiImpl api() {
		return api;
	}

	/** @return the legacy dispatch policy (populated from mod declarations). */
	public com.palordersoftworks.fabricfolia.scheduler.LegacyDispatchPolicy legacyDispatchPolicy() {
		return legacyDispatchPolicy;
	}

	/** Routes a subsystem error line to the engine's error sink (any thread). */
	public void reportError(String message) {
		error.accept(message);
	}

	/**
	 * Adopts the pre-populated policy from mod metadata scanning (called once
	 * at bootstrap; null keeps this engine's fresh empty instance).
	 */
	public void adoptLegacyDispatchPolicy(
			com.palordersoftworks.fabricfolia.scheduler.LegacyDispatchPolicy adopted) {
		if (adopted == null) {
			return;
		}
		adopted.declarations().forEach(legacyDispatchPolicy::declare);
		legacyDispatchPolicy.setDefaultDestination(adopted.defaultDestination());
	}

	/** @return gameplay-staging metrics lines ({@code /folia metrics}). */
	public List<String> metricsLines() {
		List<String> lines = new java.util.ArrayList<>(metrics.snapshotLines());
		lines.addAll(com.palordersoftworks.fabricfolia.engine.RegionTransitions.metricsLines());
		lines.addAll(com.palordersoftworks.fabricfolia.engine.NetworkDispatch.metricsLines());
		lines.add(com.palordersoftworks.fabricfolia.engine.RegionPlayerRouting.metricsLine());
		lines.add("staged bodies total: " + RegionStageHub.stagedTotal());
		lines.add("staged bodies executed on workers: " + RegionStageHub.executedOnWorkers());
		lines.add("staged bodies executed on server thread: " + RegionStageHub.executedOnServerThread());
		lines.add("staged bodies bounced to server thread (probe stale): " + RegionStageHub.bouncedToServer());
		lines.add("staged bodies dropped (region died): " + RegionStageHub.droppedDeadRegion());
		lines.add("neighborhood probes: pass=" + RegionStageHub.probePass()
				+ " world-down=" + RegionStageHub.probeFailWorldDown()
				+ " chunk-missing=" + RegionStageHub.probeFailChunkMissing());
		lines.add("chunks regionized (chunk-load): " + com.palordersoftworks.fabricfolia.engine.ChunkRegionization.chunkLoadRegistrations());
		lines.add("chunks regionized (attach backfill): " + com.palordersoftworks.fabricfolia.engine.ChunkRegionization.backfillRegistrations());
		lines.add("chunk-load events with no attached world: " + com.palordersoftworks.fabricfolia.engine.ChunkRegionization.unattachedRefusals());
		lines.add("scheduled ticks deferred by workers: "
				+ com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral.deferredTotal());
		lines.add("scheduled ticks replayed server-thread: "
				+ com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral.replayedTotal());
		lines.add("scheduled ticks buffered now: "
				+ com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral.pendingCount());
		lines.add("pending-tick ledger: records="
				+ com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral.ledgerRecords()
				+ " releases=" + com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral.ledgerReleases());
		lines.add("vanilla state mutations deferred to server thread: "
				+ com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral.deferred()
				+ " (replayed server-thread: "
				+ com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral.replayed()
				+ ", pending now: "
				+ com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral.pendingCount()
				+ ", failed: "
				+ com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral.failed()
				+ ")");
		lines.add("staged-body retries (async entity load): scheduled="
				+ com.palordersoftworks.fabricfolia.scheduler.StageRetry.scheduled()
				+ " retried=" + com.palordersoftworks.fabricfolia.scheduler.StageRetry.retried()
				+ " pending=" + com.palordersoftworks.fabricfolia.scheduler.StageRetry.pendingCount()
				+ " exhausted=" + com.palordersoftworks.fabricfolia.scheduler.StageRetry.exhausted());
		return lines;
	}

	/**
	 * @return the entity-ownership tracker for a world, or null when the
	 * world is not tracked (mixin hooks consult this and no-op on null).
	 */
	public com.palordersoftworks.fabricfolia.entity.EntityRegionTracker entityTrackerOrNull(String worldName) {
		return entityTrackersByWorld.get(worldName);
	}

	/** @return the entity scheduler for a world, or null if not attached. */
	public com.palordersoftworks.fabricfolia.scheduler.EntitySchedulerImpl entitySchedulerFor(String worldName) {
		return entitySchedulersByWorld.get(worldName);
	}

	/**
	 * Per-world entity-tracking diagnostics lines ({@code /folia entities},
	 * mandate §35 metrics). The tracked-count read is a snapshot of live
	 * state — raciness is bounded and acceptable for display.
	 */
	public java.util.List<String> entityTrackingLines() {
		java.util.List<String> lines = new java.util.ArrayList<>();
		if (entityTrackersByWorld.isEmpty()) {
			lines.add("  (no worlds tracked)");
			return lines;
		}
		for (var entry : entityTrackersByWorld.entrySet()) {
			lines.add("  " + entry.getKey() + ": " + entry.getValue().diagnosticsLine());
		}
		return lines;
	}

	/**
	 * Sets the suppression reason (startup, before worlds attach). Suppression
	 * only affects the regionized-random-ticks slice; the engine otherwise
	 * runs normally — regions, schedulers, diagnostics, commands all live.
	 */
	public void suppressRandomTickIntercept(String reason) {
		this.interceptSuppressedReason = reason;
	}

	/** @return the suppression reason, or null when the intercept armed. */
	public String interceptSuppressedReason() {
		return interceptSuppressedReason;
	}

	/** @return true when the intercept was suppressed by startup policy. */
	public boolean randomTickInterceptSuppressed() {
		return interceptSuppressedReason != null;
	}

	/**
	 * The admin-facing detail for the suppression warning: what happens, why
	 * it matters, and where the evidence lives. One line; the decision and
	 * full context are in docs/compatibility/c2me.md.
	 */
	public String suppressionDetailLine() {
		return "a measured interaction (COMPATIBILITY.md) makes worker-tick world mutations unreliable under this mod combination";
	}

	/**
	 * @return the vanilla intercept layer if the regionized-random-ticks
	 * intercept is active, else null — the Mixin consults this per tick pass;
	 * null means "forward to vanilla unchanged".
	 */
	public RegionTickInterceptor interceptorOrNull() {
		return config.regionizedRandomTicks() && !randomTickInterceptSuppressed()
				? this.interceptor : null;
	}

	/** Detaches a world (server stop): stops its scheduler, drops its queues. */
	public void detachWorld(String worldName) {
		ChunkResidency.clearWorld(worldName);
		com.palordersoftworks.fabricfolia.entity.EntityRegionTracker tracker = entityTrackersByWorld.remove(worldName);
		if (tracker != null) {
			tracker.close();
		}
		entitySchedulersByWorld.remove(worldName);
		hubsByWorld.remove(worldName);
		pendingTickLedgersByWorld.keySet().removeIf(name -> name.startsWith(worldName + ":"));
		com.palordersoftworks.fabricfolia.thread.RegionChecks.removeAuthority(worldName);
		RegionStageHub hub = stagingHubsByWorld.remove(worldName);
		if (hub != null) {
			// Deactivate this world's staging first (the mixins check per
			// level), then flush anything already staged so pending work is
			// dispatched (or server-thread-executed) before the scheduler dies.
			hub.deactivate();
			hub.flushStaged();
		}
		RegionScheduler scheduler = schedulersByWorld.remove(worldName);
		if (scheduler != null) {
			try {
				scheduler.close();
			} catch (Exception e) {
				error.accept("World scheduler shutdown problem for "
						+ worldName + ": " + e);
			}
		}
		regionizers.remove(worldName);
	}

	/** @return the regionizer for a world, or null if not attached. */
	public WorldRegionizer regionizerFor(String worldName) {
		return regionizers.get(worldName);
	}

	/** @return the scheduler for a world, or null if not attached. */
	public RegionScheduler schedulerFor(String worldName) {
		return schedulersByWorld.get(worldName);
	}

	/** @return the async scheduler (mandate §12: the fourth scheduler). */
	public AsyncSchedulerImpl asyncScheduler() {
		return async;
	}

	public GlobalSchedulerImpl globalScheduler() {
		return global;
	}

	/** @return the size of the shared worker pool (diagnostics). */
	public int primaryWorkerCount() {
		return workerThreadCount;
	}

	public ThreadContextImpl threadContext() {
		return threadContext;
	}

	public FoliaConfig config() {
		return config;
	}

	/**
	 * The player-path staging gate ({@code gameplay.stage-player-path}), or
	 * null when the config does not carry the key (older config versions):
	 * the mod's default-on resolution treats null as enabled.
	 */
	public Boolean playerPathGate() {
		Object value = config.valueOrNull(com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PLAYER_PATH);
		return value instanceof Boolean b ? b : null;
	}

	/** @return the shared worker pool (worker/busy diagnostics, shutdown wiring). */
	public com.palordersoftworks.fabricfolia.scheduler.WorkerPool workerPool() {
		return workerPool;
	}

	/** @return the per-world schedulers (diagnostics read-only view). */
	public java.util.Map<String, com.palordersoftworks.fabricfolia.scheduler.RegionScheduler> schedulers() {
		return java.util.Collections.unmodifiableMap(schedulersByWorld);
	}

	/**
	 * @return whether any attached world has its spawn chunk loaded right
	 * now — the health invariant's "server has tickable world state" probe.
	 */
	public boolean hasLoadedChunks() {
		for (net.minecraft.server.level.ServerLevel level : levels()) {
			var pos = level.getRespawnData().pos();
			if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null) {
				return true;
			}
		}
		return false;
	}

	/** @return the live server levels this engine is attached to. */
	public java.util.List<net.minecraft.server.level.ServerLevel> levels() {
		java.util.List<net.minecraft.server.level.ServerLevel> levels = new java.util.ArrayList<>();
		for (RegionStageHub hub : stagingHubsByWorld.values()) {
			levels.add(hub.level());
		}
		return levels;
	}

	/** @return live region count across ALL attached worlds (diagnostics). */
	public int regionCount() {
		int total = 0;
		for (RegionScheduler scheduler : schedulersByWorld.values()) {
			total += scheduler.liveRegions().size();
		}
		return total;
	}

	/** @return per-world live region counts (diagnostics). */
	public List<String> regionCountsByWorld() {
		List<String> lines = new java.util.ArrayList<>();
		for (var entry : schedulersByWorld.entrySet()) {
			var scheduler = entry.getValue();
			long ticking = scheduler.liveRegions().stream()
					.filter(r -> r.state() == com.palordersoftworks.fabricfolia.region.RegionState.TICKING)
					.count();
			lines.add(entry.getKey() + ": " + scheduler.liveRegions().size()
					+ " region(s), " + ticking + " ticking, " + scheduler.dueRegionCount()
					+ " due, queue=" + scheduler.queuedRegionTasks());
		}
		return lines;
	}

	/**
	 * Per-region diagnostic lines (the {@code /folia regions} detail view):
	 * state, tick count, size, and last tick duration where known. Deliberately
	 * NOT printed during normal operation - admins opt in via the command
	 * (console presentation policy: quiet by default).
	 */
	public List<String> regionDetailLines() {
		List<String> lines = new java.util.ArrayList<>();
		for (var entry : schedulersByWorld.entrySet()) {
			for (Region region : entry.getValue().liveRegions()) {
				long avgNanos = region.averageTickDurationNanos();
				long peakNanos = region.peakTickDurationNanos();
				lines.add("  " + region.world() + " #" + region.regionId() + ": state="
						+ region.stateName() + ", ticks=" + region.tickCount()
						+ ", sections=" + region.sectionCount()
						+ (avgNanos > 0
								? String.format(java.util.Locale.ROOT, ", mspt=%.2f, peak=%.2fms",
										avgNanos / 1_000_000.0, peakNanos / 1_000_000.0)
								: "")
						+ " (workers are not pinned to regions)");
			}
		}
		return lines;
	}

	public void shutdown(long timeoutMillis) throws Exception {
		// Order matters (spec 16 groundwork): stop each world's scheduler and
		// its interceptor (queue drains/drops are per-scheduler), then stop the
		// global cadence, then the pool-owning primary scheduler. The intercept
		// hooks unregister themselves first (see RegionTickInterceptor.detachAll),
		// so vanilla falls back to its own pass with no window of lost ticks.
		// Staging deactivation is first: the capture mixins check activation on
		// every call, so vanilla resumes its own execution immediately.
		RegionStageHub.deactivateAll();
		com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral.deactivate();
		if (api != null) {
			api.close(); // post-shutdown API calls fail honestly (spec 16)
		}
		for (String worldName : List.copyOf(schedulersByWorld.keySet())) {
			detachWorld(worldName);
		}
		// Worker-thread dispatch off BEFORE the pool drains its last tasks:
		// from this point any still-running region task sees the original
		// (vanilla) source — same semantics as the intercept-detach window.
		com.palordersoftworks.fabricfolia.thread.WorkerRandoms.deactivate();
		globalDispatchThread.interrupt();
		globalDispatchThread.join(1000);
		// Quiescence (spec 16): the cadence is stopped, so drain whatever the
		// last ticks queued once on this thread instead of dropping it.
		global.dispatchPending();
		if (watchdog != null) {
			watchdog.close();
		}
		async.close();
		workerPool.shutdown(5000);
	}
}
