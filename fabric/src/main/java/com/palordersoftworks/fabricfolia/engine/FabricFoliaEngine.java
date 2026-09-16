/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.region.WorldRegionizerRegistry;
import com.palordersoftworks.fabricfolia.scheduler.AsyncSchedulerImpl;
import com.palordersoftworks.fabricfolia.scheduler.GlobalSchedulerImpl;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadContextImpl;
import com.palordersoftworks.fabricfolia.thread.ViolationReporter;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
	private final RegionScheduler primaryScheduler;
	private final WorldRegionizerRegistry regionizers = new WorldRegionizerRegistry();
	/** Per-world schedulers for worlds attached after the primary (shared pool). */
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
	/**
	 * Why the regionized-random-ticks intercept was suppressed at startup, or
	 * null when it armed normally. Pure presentation state: the Mixin consults
	 * suppression (not config) per tick pass, so what the admin is told is
	 * exactly what runs.
	 */
	private volatile String interceptSuppressedReason;

	private FabricFoliaEngine(FoliaConfig config,
	                          RegionScheduler primaryScheduler,
	                          GlobalSchedulerImpl global,
	                          AsyncSchedulerImpl async,
	                          ThreadContextImpl threadContext,
	                          ViolationReporter reporter,
	                          Thread globalDispatchThread,
	                          java.util.function.Consumer<String> info,
	                          java.util.function.Consumer<String> error) {
		this.config = config;
		this.primaryScheduler = primaryScheduler;
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

		// The primary scheduler exists to own the shared worker pool; worlds
		// attach through attachWorld() and share it. Its regionizer is a
		// placeholder that never receives chunks.
		RegionizerConfig placeholderConfig = RegionizerConfig.derive(8);
		WorldRegionizer placeholderRegionizer = new WorldRegionizer("<pool-owner>", placeholderConfig,
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
		RegionScheduler scheduler = new RegionScheduler(placeholderRegionizer, workerThreads, mode,
				region -> { /* pool-owner scheduler runs no tick bodies */ },
				info::accept);

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
		ThreadContextImpl threadContext = new ThreadContextImpl(reporter);

		scheduler.start();
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

		return new FabricFoliaEngine(config, scheduler, global, async, threadContext, reporter,
				globalDispatch, info, error);
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
					primaryScheduler.workerPool(),
					primaryScheduler.workerCount(),
					ValidationMode.valueOf(config.threadCheckMode()),
					interceptActive ? interceptor.regionTickBody(worldName) : region -> { },
					message -> info.accept(message));
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
					+ simulationDistanceChunks + "). Entities, block entities, and "
					+ "scheduled ticks remain on the server thread (see THREADING.md).");
		} else {
			info.accept("World attached: " + worldName
					+ " (structure-only: regionized random ticks disabled, vanilla execution untouched).");
		}
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
		info.accept("  Entity ownership tracking active for " + worldName
				+ " (add/remove/move hooks live; ticking stays on the server thread this phase).");
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
		com.palordersoftworks.fabricfolia.entity.EntityRegionTracker tracker = entityTrackersByWorld.remove(worldName);
		if (tracker != null) {
			tracker.close();
		}
		entitySchedulersByWorld.remove(worldName);
		hubsByWorld.remove(worldName);
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
		return primaryScheduler.workerCount();
	}

	public ThreadContextImpl threadContext() {
		return threadContext;
	}

	public FoliaConfig config() {
		return config;
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
			lines.add(entry.getKey() + ": " + entry.getValue().liveRegions().size()
					+ " region(s), " + entry.getValue().liveRegions().stream()
					.filter(r -> r.state() == com.palordersoftworks.fabricfolia.region.RegionState.TICKING)
					.count() + " ticking");
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
				long lastNanos = region.lastTickDurationNanos();
				lines.add("  " + region.world() + " #" + region.regionId() + ": state="
						+ region.stateName() + ", ticks=" + region.tickCount()
						+ ", sections=" + region.sectionCount()
						+ (lastNanos > 0
								? String.format(java.util.Locale.ROOT, ", last-tick=%.2fms", lastNanos / 1_000_000.0)
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
		for (String worldName : List.copyOf(schedulersByWorld.keySet())) {
			detachWorld(worldName);
		}
		// Worker-thread dispatch off BEFORE the pool drains its last tasks:
		// from this point any still-running region task sees the original
		// (vanilla) source — same semantics as the intercept-detach window.
		com.palordersoftworks.fabricfolia.thread.WorkerRandoms.deactivate();
		globalDispatchThread.interrupt();
		globalDispatchThread.join(1000);
		async.close();
		primaryScheduler.close();
	}
}
