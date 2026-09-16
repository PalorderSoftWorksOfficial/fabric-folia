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
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
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

		ServerLifecycleEvents.SERVER_STARTING.register(FabricFoliaMod::startEngine);
		ServerLifecycleEvents.SERVER_STARTED.register(FabricFoliaMod::attachWorlds);
		ServerLifecycleEvents.SERVER_STOPPING.register(FabricFoliaMod::stopEngine);
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
				Console.info(config.regionizedRandomTicks()
					? (c2meDetected
							? "Regionized random-tick interception suppressed for this session (see the C2ME warning above)."
							: "Regionized random-tick interception is enabled.")
					: "Regionized random-tick interception is disabled by config: vanilla execution is untouched.");
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
			// attachWorld honors the config flag internally (interceptor null
			// is treated as structure-only attachment).
			current.attachWorld(worldName, simulationDistance, currentInterceptor);
		}
		Console.success("Fabric Folia is ready.");
		Console.info("  Still on the server thread in this release: entities, block entities, scheduled ticks, worldgen, spawning (see SCHEDULING.md).");
	}

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
}
