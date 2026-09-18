/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.metrics.RegionMetrics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry of global-state ownership (mandate §11): the machine-readable
 * counterpart to the global region. Every server-wide state domain the tick
 * pipeline touches is classified exactly once — GAME (owned by the global
 * execution context: daylight, weather, world border, game rules) or REGION
 * (owned per-world by regions: positions, entities, blocks) — and code that
 * runs outside its owning context can consult this classification to route
 * or refuse, instead of each site re-deriving the answer.
 *
 * <p><strong>What this is not:</strong> a lock, a queue, or a dispatch
 * mechanism. The global scheduler and {@link RegionStageHub} do the moving;
 * this class is the single source of truth for <em>who owns what</em>, and
 * the /folia diagnostics surface prints it so administrators can see the
 * classification without reading source.</p>
 *
 * <p><strong>Threading:</strong> populated once at engine start (server
 * thread), read from any thread via a concurrent map. Entries are immutable.</p>
 */
public final class GlobalStateRegistry {

	/** The server-wide state domains a tick pipeline touches. */
	public enum Domain {
		DAYLIGHT_TIME("daylight/game-time progression", Ownership.GAME),
		WEATHER("weather state (rain/thunder cycles)", Ownership.GAME),
		WORLD_BORDER("world border size/center/warning", Ownership.GAME),
		GAME_RULES("gamerule storage", Ownership.GAME),
		PLAYER_LIST("player list / op / whitelist state", Ownership.GAME),
		SCOREBOARD("scoreboard objectives and teams", Ownership.GAME),
		ENTITY_POSITIONS("entity and player positions", Ownership.REGION),
		BLOCKS_AND_TICKS("block state, scheduled block/fluid ticks", Ownership.REGION),
		BLOCK_ENTITIES("block entity ticking", Ownership.REGION),
		CHUNKS("chunk load/generate/unload", Ownership.REGION);

		private final String description;
		private final Ownership ownership;

		Domain(String description, Ownership ownership) {
			this.description = description;
			this.ownership = ownership;
		}

		/** @return human-readable description (diagnostics). */
		public String description() {
			return description;
		}

		/** @return the owning context kind for this domain. */
		public Ownership ownership() {
			return ownership;
		}
	}

	/** Which execution context owns a domain. */
	public enum Ownership {
		/** The global region: daylight, weather, border, game rules, lists. */
		GAME,
		/** Per-world regions: everything positional. */
		REGION
	}

	/** Runtime overrides registered by subsystems (domain -> owner label). */
	private static final Map<Domain, String> ACTIVE_OWNERS = new ConcurrentHashMap<>();
	private static final AtomicBoolean PRINTED = new AtomicBoolean();

	private GlobalStateRegistry() {
	}

	/**
	 * Registers the live owner of a domain for diagnostics (called by the
	 * engine when a subsystem activates; e.g. the global dispatch thread
	 * registers GAME domains, each regionizer registers REGION domains).
	 *
	 * @param ownerLabel e.g. "FabricFolia-Global" or "world=minecraft:overworld"
	 */
	public static void activate(Domain domain, String ownerLabel) {
		ACTIVE_OWNERS.put(Objects.requireNonNull(domain), Objects.requireNonNull(ownerLabel));
	}

	/** Clears all registrations (engine shutdown / test isolation). */
	public static void reset() {
		ACTIVE_OWNERS.clear();
		PRINTED.set(false);
	}

	/** @return true when the domain's owner has been registered. */
	public static boolean isActive(Domain domain) {
		return ACTIVE_OWNERS.containsKey(domain);
	}

	/** @return the registered owner label, or null when inactive. */
	public static String ownerOf(Domain domain) {
		return ACTIVE_OWNERS.get(domain);
	}

	/** @return true when the domain belongs to the global (GAME) context. */
	public static boolean isGlobalWork(Domain domain) {
		return domain.ownership() == Ownership.GAME;
	}

	/**
	 * One-line-per-domain classification for /folia diagnostics: shows the
	 * static ownership plus the live owner label once the engine activates.
	 */
	public static List<String> classificationLines() {
		List<String> lines = new java.util.ArrayList<>();
		for (Domain domain : Domain.values()) {
			String owner = ACTIVE_OWNERS.get(domain);
			lines.add(domain.name() + " -> " + domain.ownership()
					+ (owner != null ? " (" + owner + ")" : " (inactive)"));
		}
		return lines;
	}

	/** Metrics counter bump helper (keeps the class dependency-light). */
	public static long globalTasksExecuted(RegionMetrics metrics) {
		return metrics.value(RegionMetrics.Counter.GLOBAL_TASKS_EXECUTED);
	}
}
