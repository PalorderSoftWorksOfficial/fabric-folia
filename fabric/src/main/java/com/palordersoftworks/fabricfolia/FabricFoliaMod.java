/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia;

import com.palordersoftworks.fabricfolia.config.ConfigException;
import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.console.CompatScanner;
import com.palordersoftworks.fabricfolia.console.Console;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.engine.RegionTickInterceptor;
import com.palordersoftworks.fabricfolia.scheduler.LegacyDispatchPolicy;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Set;
import java.util.List;

/**
 * The mod entrypoint.
 *
 * <p><strong>Startup sequence (administrator-facing):</strong> mod load
 * (brand, version, compatibility scan + summary) → server starting (config
 * load + configuration summary, scheduler bootstrap) → server started (world
 * attachments, ready line). Every line goes through {@link Console}, so the
 * prefix, severity wording, and color policy are applied in exactly one
 * place.</p>
 *
 * <p><strong>Integration-phase scope:</strong> with
 * {@code general.regionized-random-ticks: true}, the per-chunk random-tick
 * pass (precipitation + random ticks — crop growth, grass spread, fire,
 * ice/snow, leaf decay) of every attached dimension executes on region worker
 * threads under the EDF scheduler, with the regionizer's four invariants and
 * the STRICT/WARN thread diagnostics active on the real work path. Everything
 * else (entities, block entities, scheduled ticks, worldgen, spawning)
 * remains on the server thread — the documented boundary of this slice,
 * reported at startup and by {@code /folia status} (spec 17: scoped, labeled,
 * never faked).</p>
 *
 * <p><strong>Fail-closed config policy:</strong> if the configuration cannot
 * be loaded or validated, the mod disables itself with an actionable error
 * rather than guessing operator intent. The server itself continues on
 * vanilla execution — a config problem is an ERROR, never presented as a
 * server failure.</p>
 */
public class FabricFoliaMod implements ModInitializer {
	public static final String MOD_ID = "fabricfolia";

	/** The engine instance once started; null while disabled. */
	private static volatile FabricFoliaEngine engine;
	/** The vanilla intercept layer; non-null whenever the engine is live. */
	private static volatile RegionTickInterceptor interceptor;
	/** The startup compatibility scan result (for /folia compat). */
	private static volatile List<CompatScanner.Entry> compatEntries = List.of();

	@Override
	public void onInitialize() {
		Console.info("Starting Fabric Folia...");
		Console.info("Fabric Folia version: " + FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown")
				+ ", Minecraft " + FabricLoader.getInstance().getModContainer("minecraft")
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
		Console.info("Scanning installed mods for compatibility declarations...");

		compatEntries = CompatScanner.scanAndReport();
		declareLegacyDestinations();
		declarePatches();
		// Chunk activity → regionizer registration (mandate §20, the regions=0
		// root-cause fix): every chunk that loads from now on joins its world's
		// regionizer. Inert per event while the engine is down.
		com.palordersoftworks.fabricfolia.engine.ChunkRegionization.attach();
		ServerLifecycleEvents.SERVER_STARTING.register(FabricFoliaMod::startEngine);
		ServerLifecycleEvents.SERVER_STARTED.register(FabricFoliaMod::attachWorlds);
		ServerLifecycleEvents.SERVER_STOPPING.register(FabricFoliaMod::stopEngine);
		// End-of-tick gameplay flush (mandates 15/27): after vanilla's passes
		// (entity loops, block-entity pass, scheduled-tick drains, weather),
		// hand each world's staged bodies to their owning regions and replay
		// any scheduled ticks the region workers deferred. No-op when the
		// engine is down (both calls are inert without activation).
		ServerTickEvents.END_SERVER_TICK.register(FabricFoliaMod::flushGameplayStaging);
		com.palordersoftworks.fabricfolia.command.FoliaCommand.register();
	}

	private static void startEngine(MinecraftServer server) {
		Path configPath = Path.of("config", "fabric-folia.yml");
		FoliaConfig config;
		try {
			config = FoliaConfig.at(configPath);
			config.load();
			config.freeze();
		} catch (ConfigException e) {
			// Actionable, not fatal-to-the-server: vanilla execution continues.
			Console.error("Configuration is invalid - Fabric Folia is DISABLED and vanilla execution will be used. "
					+ "Nothing is broken; fix the problem below and restart to enable regionized execution.");
			Console.error("  Problem: " + e.getMessage());
			Console.error("  See TROUBLESHOOTING.md (configuration) and the generated template in config/fabric-folia.yml.");
			return;
		} catch (Exception e) {
			Console.error("Failed to load configuration - Fabric Folia is DISABLED. Problem: " + e);
			return;
		}

		if (!config.enabled()) {
			Console.config("Configuration");
			Console.config("  Region system: disabled (general.enabled=false) - vanilla execution, no worker threads.");
			return;
		}

		// Patch-layer resolution (spec 25): each declared patch's own toggle
		// mirrors the feature gate that actually implements it; the global
		// patches.enabled switch disables the whole optimization layer.
		resolvePatchStates(config);

		// Measured-compatibility detection happens before the summary so every
		// line an admin reads reflects what will actually run.
		boolean c2meDetected = compatEntries.stream().anyMatch(e -> "c2me".equals(e.id()));

		// Configuration summary (requirement 8): the effective settings an
		// administrator actually cares about - not a config dump.
		int workers = FoliaConfig.WorkerThreads.resolve(config.workerThreads());
		Console.config("Configuration");
		Console.config("  Region system: enabled");
		Console.config("  Region workers: " + workers + (config.workerThreads() < 0
				? " (auto: derived from available CPUs; set threads.worker-threads to override)" : " (configured)"));
		Console.config("  Global scheduler: enabled");
		Console.config("  Thread-ownership checks: " + config.threadCheckMode()
				+ ("STRICT".equals(config.threadCheckMode())
						? " (development setting: real per-access cost - see THREADING.md)" : ""));
		Console.config("  Regionized random ticks: " + (!config.regionizedRandomTicks()
				? "disabled (vanilla execution; set general.regionized-random-ticks=true to opt in)"
				: c2meDetected
						? "SUPPRESSED this session (C2ME interaction - see docs/compatibility/c2me.md)"
						: "enabled (per-chunk random ticks on region workers)"));
		Console.config("  Debug logging: " + (config.debugLogging() ? "ON (per-tick dispatch/worker evidence in the log)"
				: "off (normal operation is quiet; set diagnostics.debug-logging=true while troubleshooting)"));

		try {
			// Measured-compatibility policy (COMPATIBILITY.md): C2ME's
			// runtime-measured interaction (CheckedThreadLocalRandom guard vs.
			// the worker-thread tick body — see docs/compatibility/c2me.md)
			// makes this slice's world mutations not land. Rather than run a
			// silently degraded slice, the intercept is suppressed and the
			// admin is told. This is a measured, version-pinned decision —
			// never a guess; re-validate by re-running the harness.
			if (config.regionizedRandomTicks() && c2meDetected) {
				Console.warning("C2ME detected: the regionized random-tick slice will NOT run this session.");
				Console.warning("  What happened: a measured interaction between C2ME and Fabric Folia's worker threads prevents this slice's world mutations from landing (C2ME's random-access guard rejects the region-worker tick body). Nothing is broken: vanilla random ticks run normally on the server thread, and all other Fabric Folia features are active.");
				Console.warning("  What to do: no action needed. Removing C2ME re-enables the slice; see docs/compatibility/c2me.md for the measured evidence and re-validation commands.");
			}
			// The interceptor resolves the engine lazily (bootstrap ordering:
			// the engine field is assigned right after construction).
			interceptor = new RegionTickInterceptor(FabricFoliaMod::engine, Console::sched, Console::error);
			engine = FabricFoliaEngine.bootstrap(config, Console::sched, Console::error);
			if (c2meDetected && config.regionizedRandomTicks()) {
				engine.suppressRandomTickIntercept("C2ME detected at startup (measured interaction: worker-tick mutations do not land - see docs/compatibility/c2me.md)");
			}
			engine.setInterceptor(interceptor);
			Console.sched("Region scheduler initialized.");
			Console.sched("  Workers: " + workers + " (FabricFolia-Worker-1 .. -" + workers
					+ "); regions are not pinned to threads (see THREADING.md).");
			if (!config.regionizedRandomTicks()) {
				Console.info("Regionized random-tick interception is disabled by config: vanilla execution is untouched.");
			}
		} catch (Exception e) {
			engine = null;
			interceptor = null;
			Console.error("Engine bootstrap failed - staying on vanilla execution. The server can continue normally. "
					+ "Problem: " + e.getMessage());
			Console.error("  If this repeats, see TROUBLESHOOTING.md and report the stack trace below to the Fabric Folia issue tracker.");
			Console.error("Engine bootstrap failure detail:", e);
		}
	}

	/**
	 * Attaches every live dimension once the server is fully started (worlds
	 * and their tick registration are complete at this event; SERVER_STARTING
	 * is too early for level iteration). Runs on the server thread.
	 */
	private static void attachWorlds(MinecraftServer server) {
		FabricFoliaEngine current = engine;
		RegionTickInterceptor currentInterceptor = interceptor;
		if (current == null) {
			Console.info("Fabric Folia is ready (vanilla execution mode - see the reasons logged above).");
			return;
		}
		int simulationDistance = server.getPlayerList().getSimulationDistance();
		for (ServerLevel level : server.getAllLevels()) {
			String worldName = level.dimension().identifier().toString();
			// attachWorld honors the config flag intercept flag internally
			// (interceptor null is treated as structure-only attachment).
			current.attachWorld(worldName, simulationDistance, currentInterceptor);
			// Chunk→regionizer registration (mandate §20): backfill the spawn
			// and player chunks that loaded before the engine attached. Steady
			// state arrives via the CHUNK_LOAD hook registered in onInitialize.
			com.palordersoftworks.fabricfolia.engine.ChunkRegionization.backfillWorld(level, current);
			// Entity ownership tracking (mandate §15) is independent of the
			// random-tick intercept: it attaches whenever the engine is live.
			current.attachEntityTracking(level);
		}
		// Arm the first-tick catch-up: chunks loaded during world init (the
		// spawn area) fired their events before any regionizer existed; the
		// next server tick registers the still-loaded ones.
		com.palordersoftworks.fabricfolia.engine.ChunkRegionization.markWorldsAttached();
		Console.success("Fabric Folia is ready.");
		Console.info("  Regionized gameplay is active (entity ticking - players included - block entities, scheduled-tick drains, and random ticks on region workers). Still on the server thread: worldgen, spawning. Player path: "
				+ (playerPathStaging()
						? "staged (connection ticks on the player's owning region)."
						: "disabled (vanilla server-thread execution).")
				+ " (see THREADING.md).");

		deliverApiEntrypoints(current);
	}

	/**
	 * Hands the live {@code FabricFoliaApi} to every mod that declared the
	 * {@code fabricfolia} entrypoint (mandate 41): region-aware mods get the
	 * real engine-backed schedulers at server start, on the server thread,
	 * before any tick runs. A mod entrypoint that throws is reported and
	 * skipped — one broken mod must not prevent the others from receiving
	 * the API, and the engine keeps running.
	 */
	private static void deliverApiEntrypoints(FabricFoliaEngine current) {
		com.palordersoftworks.fabricfolia.engine.FabricFoliaApiImpl api = current.api();
		int delivered = 0;
		for (var ep : FabricLoader.getInstance().getEntrypointContainers(
				"fabricfolia",
				com.palordersoftworks.fabricfolia.api.FabricFoliaApi.Initializer.class)) {
			String modId = ep.getProvider().getMetadata().getId();
			if (modId.equals(FabricFoliaMod.MOD_ID)) {
				continue; // self
			}
			try {
				ep.getEntrypoint().onInitialized(api);
				Console.info("  Region-aware mod initialized: " + modId);
				delivered++;
			} catch (Throwable t) {
				Console.error("Region-aware mod '" + modId
						+ "' failed to initialize (the mod may misbehave): " + t);
			}
		}
		if (delivered > 0) {
			Console.info("  " + delivered + " region-aware mod(s) received the Fabric Folia API.");
		}
	}

	/**
	 * End-of-server-tick gameplay flush (mandates 15/27/21): dispatches each
	 * world's staged entity/block-entity bodies to their owning regions'
	 * task queues (they execute on region workers, serialized per region,
	 * parallel across regions) and replays the scheduled ticks those workers
	 * deferred, on this thread, through vanilla's own containers.
	 */
	private static void flushGameplayStaging(MinecraftServer server) {
		FabricFoliaEngine current = engine;
		if (current != null) {
			current.flushGameplay();
			String health = com.palordersoftworks.fabricfolia.engine.ChunkRegionization
					.healthCheckLine(current, server.getAllLevels());
			if (health != null) {
				Console.warning(health);
			}
		}
	}

	/**
	 * Declares FabricFolia's own toggleable performance patches (spec 25)
	 * and resolves their state from the config. Runs once at mod init; the
	 * state is stable for the server's lifetime (see PatchRegistry).
	 */
	private static void declarePatches() {
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("regionize-chunk-load", "Chunk Load Regionization",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.REGION,
						"chunks join their world's regionizer on load so gameplay stages execute region-parallel",
						"ServerChunkEvents.CHUNK_LOAD -> WorldRegionizer.addChunk",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("stage-entity-ticks", "Entity Tick Staging",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.MINECRAFT,
						"entity tick bodies execute on the owning region's worker instead of the server thread",
						"Level.guardEntityTick consumer.accept",
						Set.of("regionize-chunk-load"), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("stage-block-entity-ticks", "Block Entity Tick Staging",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.MINECRAFT,
						"block-entity tick bodies execute on the owning region's worker instead of the server thread",
						"Level.tickBlockEntities TickingBlockEntity.tick",
						Set.of("regionize-chunk-load"), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("stage-scheduled-ticks", "Scheduled Tick Staging",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.MINECRAFT,
						"scheduled tick executions captured from workers execute region-owned",
						"worker-side LevelTicks schedule calls",
						Set.of("stage-block-entity-ticks"), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("stage-player-path", "Player Path Staging",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.NETWORK,
						"player connection ticks and packet re-homes execute on the owning region",
						"Connection.tick + PacketProcessor.scheduleIfPossible",
						Set.of("regionize-chunk-load"), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("regionized-random-ticks", "Regionized Random Ticks",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.MINECRAFT,
						"per-chunk random ticks execute on the owning region's worker (opt-in via general.regionized-random-ticks)",
						"ServerChunkCache.tickChunks forEachBlockTickingChunk",
						Set.of("regionize-chunk-load"), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("packet-dispatch", "Packet Dispatch",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.NETWORK,
						"packet re-home dispatch routes to the owning region where ownership allows",
						"PacketProcessor.scheduleIfPossible",
						Set.of("regionize-chunk-load"), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("allocation-reduction", "Drain Allocation Reduction",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.NETWORK,
						"empty-drain short-circuit and zero-allocation sizing of drain buffers",
						"RegionStageHub flush + RegionTaskQueue.drainEntries",
						Set.of(), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("region-lookup", "Region Ownership Lookup",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.REGION,
						"O(1) section-to-region ownership index replaces the linear scan in ownerOfChunk",
						"WorldRegionizer.ownerOfChunk",
						Set.of(), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("scheduler-dispatch", "Scheduler Dispatch Scan",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.SCHEDULER,
						"zero-allocation liveRegions scan in the 1ms dispatch loop",
						"RegionScheduler.coordinateLoop",
						Set.of(), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.register("task-queue", "Task Queue Sizing",
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.SCHEDULER,
						"O(1) queue size counter and exact-size drain buffers",
						"RegionTaskQueue add/drain/size",
						Set.of(), Set.of(),
						com.palordersoftworks.fabricfolia.patches.PatchRegistry.Lifecycle.STARTUP_ONLY);
	}

	/**
	 * Resolves each patch's own toggle from the loaded config (the
	 * patches' semantics mirror the feature gates that already exist):
	 * stage patches follow regionized-gameplay / stage-player-path, the
	 * random-tick patch follows general.regionized-random-ticks. When the
	 * global patches.enabled is false every layer is switched off.
	 */
	private static void resolvePatchStates(FoliaConfig config) {
		var patches = com.palordersoftworks.fabricfolia.patches.PatchRegistry.class;
		boolean global = config.patchesEnabled();
		for (var layer : com.palordersoftworks.fabricfolia.patches.PatchRegistry.Layer.values()) {
			com.palordersoftworks.fabricfolia.patches.PatchRegistry.setLayerEnabled(layer, global);
		}
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("regionize-chunk-load",
				config.patchEnabled(com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_REGION_LOOKUP, true));
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("stage-entity-ticks",
				config.regionizedGameplay() && config.patchEnabled(
						com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_ENTITY_TICK, true));
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("stage-block-entity-ticks",
				config.regionizedGameplay() && config.patchEnabled(
						com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_CHUNK_TICK, true));
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("stage-scheduled-ticks",
				config.regionizedGameplay() && config.patchEnabled(
						com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_CHUNK_TICK, true));
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("stage-player-path",
				config.valueOrNull(com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PLAYER_PATH) instanceof Boolean b ? b : true);
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("regionized-random-ticks",
				config.regionizedRandomTicks() && config.patchEnabled(
						com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_RANDOM_TICK, true));
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("packet-dispatch",
				config.patchEnabled(com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_PACKET_DISPATCH, true));
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("allocation-reduction",
				config.patchEnabled(com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_ALLOCATION_REDUCTION, true));
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("region-lookup",
				config.patchEnabled(com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_REGION_LOOKUP, true));
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("scheduler-dispatch",
				config.patchEnabled(com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_SCHEDULER_DISPATCH, true));
		com.palordersoftworks.fabricfolia.patches.PatchRegistry.setRequested("task-queue",
				config.patchEnabled(com.palordersoftworks.fabricfolia.config.ConfigSchema.KEY_PATCH_TASK_QUEUE, true));

		var blocked = com.palordersoftworks.fabricfolia.patches.PatchRegistry.resolveAndApply();
		int active = 0;
		for (var patch : com.palordersoftworks.fabricfolia.patches.PatchRegistry.patches()) {
			if (patch.status() == com.palordersoftworks.fabricfolia.patches.PatchRegistry.Status.ACTIVE) {
				active++;
			}
		}
		Console.config("  Patches: " + active + " active / "
				+ com.palordersoftworks.fabricfolia.patches.PatchRegistry.patches().size()
				+ " registered" + (blocked.isEmpty() ? "" : " (" + blocked.size() + " blocked)"));

		boolean lookupActive = com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.isEnabled("region-lookup");
		boolean schedulerActive = com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.isEnabled("scheduler-dispatch");
		boolean taskQueueActive = com.palordersoftworks.fabricfolia.patches.PatchRegistry
				.isEnabled("task-queue");
		if (engine != null) {
			for (var scheduler : engine.schedulers().values()) {
				scheduler.setSchedulerDispatchPatch(schedulerActive);
				scheduler.setOwnerLookupIndex(lookupActive);
			}
		}
		com.palordersoftworks.fabricfolia.scheduler.RegionTaskQueue.setGlobalTaskQueuePatch(taskQueueActive);
	}

	/**
	 * Populates the legacy dispatch policy (mandate §39) from each mod's
	 * metadata declaration ({@code custom.fabricfolia.legacy-dispatch}:
	 * "direct" | "global" | "region"), so a mod that knows its contract
	 * opts into the cheapest correct context; everything undeclared keeps
	 * the conservative global default. Runs on the mod-init thread before
	 * any server exists — pure registry population.
	 */
	private static void declareLegacyDestinations() {
		var policy = engine != null ? engine.legacyDispatchPolicy() : null;
		if (policy == null) {
			policy = new com.palordersoftworks.fabricfolia.scheduler.LegacyDispatchPolicy();
		}
		FabricFoliaMod.pendingPolicy = policy;
		for (var mod : FabricLoader.getInstance().getAllMods()) {
			var custom = mod.getMetadata().getCustomValue("fabricfolia");
			if (!(custom instanceof net.fabricmc.loader.api.metadata.CustomValue.CvObject obj)) {
				continue;
			}
			var dest = obj.get("legacy-dispatch");
			if (dest == null || dest.getAsString() == null) {
				continue;
			}
			var destination = switch (dest.getAsString()) {
				case "direct" -> LegacyDispatchPolicy.Destination.RUN_DIRECT;
				case "region" -> LegacyDispatchPolicy.Destination.RUN_ON_REGION;
				case "global" -> LegacyDispatchPolicy.Destination.RUN_ON_GLOBAL;
				default -> null;
			};
			if (destination != null) {
				policy.declare(mod.getMetadata().getId(), destination);
			}
		}
	}

	/** Policy populated at init, handed to the engine at start. */
	private static volatile LegacyDispatchPolicy pendingPolicy;

	private static void stopEngine(MinecraftServer server) {
		FabricFoliaEngine current = engine;
		RegionTickInterceptor currentInterceptor = interceptor;
		if (current == null) {
			return;
		}
		try {
			// Remove the intercept hooks FIRST: vanilla falls through to its
			// own pass for any tick that lands during shutdown, so no chunk
			// work is lost in the window.
			if (currentInterceptor != null) {
				currentInterceptor.detachAll();
			}
			current.shutdown(5000);
			Console.success("Engine shut down cleanly. All region work drained; vanilla saved the worlds.");
		} catch (Exception e) {
			Console.error("Engine shutdown reported a problem (worlds were still saved by vanilla): " + e.getMessage(), e);
		} finally {
			engine = null;
			interceptor = null;
		}
	}

	/** @return the live engine, or null when disabled (commands check this). */
	public static FabricFoliaEngine engine() {
		return engine;
	}	/**
	 * Containment sink for entity-hook failures (mixin hooks catch and route
	 * here): bookkeeping must never break vanilla gameplay, so the failure is
	 * logged and the hook continues inert.
	 */
	public static void reportTrackerFailure(String operation, String worldName, RuntimeException e) {
		Console.error("Fabric Folia entity tracking failure (" + operation
				+ " in " + worldName + "): " + e
				+ " - the hook is inert for this entity; gameplay continues on vanilla execution.");
		Console.error("  Stack trace for the Fabric Folia issue tracker:", e);
	}

	/**
	 * Whether the player path (connection tick + packet re-home) stages onto
	 * owning regions. Resolution order: the config gate
	 * ({@code gameplay.stage-player-path}) when the engine is live; true when
	 * the engine is live but the config is not yet bound (the default);
	 * false when the engine is down (vanilla threading).
	 */
	public static boolean playerPathStaging() {
		FabricFoliaEngine current = engine;
		if (current == null) {
			return false;
		}
		if (!com.palordersoftworks.fabricfolia.patches.PatchRegistry.isEnabled("stage-player-path")) {
			return false;
		}
		Boolean gate = current.playerPathGate();
		return gate == null || gate;
	}

	/** @return the intercept layer, or null when the engine is disabled. */
	public static RegionTickInterceptor interceptorOrNull() {
		RegionTickInterceptor current = interceptor;
		FabricFoliaEngine currentEngine = engine;
		return (current != null && currentEngine != null) ? current : null;
	}

	/** @return the startup compatibility scan entries (for /folia compat). */
	public static List<CompatScanner.Entry> compatEntries() {
		return compatEntries;
	}

	/** @return the policy populated at init (engine consumes at start). */
	public static LegacyDispatchPolicy pendingDispatchPolicy() {
		return pendingPolicy;
	}
}
