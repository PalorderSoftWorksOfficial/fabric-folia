/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The typed schema of Fabric-Folia's configuration (spec section 11).
 *
 * <p><strong>Design:</strong> each option is an {@link Option} carrying its key,
 * type, default, validator, and the full documentation comment that appears in
 * the generated file. The schema is the single source of truth: the default file
 * is <em>generated from it</em>, values are validated against it, and unknown
 * keys are detected by diffing against it. Adding a config option is adding one
 * Option — documentation cannot drift from behavior because it is the same data.</p>
 *
 * <p><strong>Minimum option set (spec 11):</strong> enabled, region section size,
 * worker threads (with auto), thread-check mode, debug/profiling/metrics toggles.
 * No tuning knobs beyond what the implementation actually uses — options appear
 * only when a real administrator-relevant knob emerges.</p>
 */
public final class ConfigSchema {

	/** The current config-version. Bump on schema change; migrations map older versions. */
	public static final int CURRENT_VERSION = 1;

	// Key name constants — used by schema definition, loader, migration, and save.
	public static final String KEY_ENABLED = "general.enabled";
	public static final String KEY_RANDOM_TICKS = "general.regionized-random-ticks";
	public static final String KEY_GAMEPLAY = "general.regionized-gameplay";
	public static final String KEY_SECTION_SIZE = "regions.region-section-size";
	public static final String KEY_WORKER_THREADS = "threads.worker-threads";
	public static final String KEY_THREAD_CHECK = "threads.thread-check-mode";
	public static final String KEY_DEBUG = "diagnostics.debug";
	public static final String KEY_DEBUG_LOGGING = "diagnostics.debug-logging";
	public static final String KEY_PROFILING = "diagnostics.profiling";
	public static final String KEY_METRICS = "diagnostics.metrics";
	public static final String KEY_WATCHDOG = "diagnostics.watchdog";
	public static final String KEY_PLAYER_PATH = "gameplay.stage-player-path";
	public static final String KEY_VERSION = "config-version";
	public static final String KEY_SERVER_BRAND = "general.server-brand";
	public static final String KEY_SERVER_BRAND_NAME = "general.server-brand-name";

	private final Map<String, Option<?>> options = new LinkedHashMap<>();

	private ConfigSchema() {
		define();
	}

	/** @return the schema singleton. */
	public static ConfigSchema get() {
		return Holder.INSTANCE;
	}

	private static final class Holder {
		static final ConfigSchema INSTANCE = new ConfigSchema();
	}

	/** A single configuration option: identity, typing, validation, documentation. */
	public static final class Option<T> {
		private final String key;
		private final Class<T> type;
		private final T defaultValue;
		private final Function<T, T> validator;
		private final String[] commentLines;

		Option(String key, Class<T> type, T defaultValue,
		       Function<T, T> validator, String[] commentLines) {
			this.key = Objects.requireNonNull(key, "key");
			this.type = Objects.requireNonNull(type, "type");
			this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
			this.validator = Objects.requireNonNull(validator, "validator");
			this.commentLines = Objects.requireNonNull(commentLines, "commentLines");
		}

		public String key() {
			return key;
		}

		public Class<T> type() {
			return type;
		}

		public T defaultValue() {
			return defaultValue;
		}

		public String[] commentLines() {
			return commentLines.clone();
		}

		/**
		 * Validates an already type-resolved value.
		 *
		 * @throws ConfigException on validator rejection
		 */
		T validate(T value) {
			return validator.apply(value);
		}
	}

	private static <T> Option<T> opt(String key, Class<T> type, T def,
	                                 Function<T, T> validator, String[] comment) {
		return new Option<>(key, type, def, validator, comment);
	}

	private void define() {
		add(opt(KEY_ENABLED, Boolean.class, Boolean.TRUE, v -> v, new String[] {
				"Master switch for regionized execution.",
				"",
				"What it does:",
				"  When true, the server's gameplay tick is partitioned into",
				"  independently-ticking regions executed on a bounded worker pool.",
				"  When false, the mod stays out of the way: vanilla single-threaded",
				"  execution is used for everything.",
				"",
				"Changing this at runtime via /folia config enabled <true|false> does",
				"NOT flip a boolean: disabling triggers a safe quiescence transition",
				"(stop accepting work -> drain in-flight region work -> quiesce every",
				"region -> verify no worker is mutating region state -> return the",
				"tick loop to vanilla). It is deliberately not an instant shutdown.",
				"",
				"Re-enabling at runtime is refused with a clear message unless the",
				"re-enable path has been positively demonstrated safe (see",
				"THREADING.md). Until then: restart to re-enable.",
				"",
				"Default: true",
				"Valid values: true, false",
				"Performance: true enables the regionized execution model.",
				"Compatibility: third-party mods that assume a single server thread",
				"  may misbehave under regionized execution; see COMPATIBILITY.md.",
				"Safety: disabling returns to 100% vanilla behavior.",
				"Restart required: no for false (live quiescence); re-enable may",
				"  require a restart (see above)."
		}));
		add(opt(KEY_SERVER_BRAND, Boolean.class, Boolean.TRUE, v -> v, new String[] {
				"Controls whether Fabric-Folia overrides the Minecraft server brand.",
				"",
				"What it does:",
				"  When true, getServerModName() returns the configured server-brand-name.",
				"  When false, Fabric-Folia does not modify the server brand and",
				"  Minecraft's normal server brand is returned.",
				"",
				"Default: true",
				"Valid values: true, false",
				"Performance: no measurable impact.",
				"Compatibility: disabling this restores vanilla server branding.",
				"Restart required: no."
		}));
		add(opt(KEY_SERVER_BRAND_NAME, String.class, "fabricFolia", v -> {
			if (v == null || v.isBlank()) {
				throw new ConfigException("server-brand-name must not be empty");
			}
			if (v.length() > 64) {
				throw new ConfigException("server-brand-name must be 64 characters or fewer");
			}
			return v;
		}, new String[] {
				"Name returned by MinecraftServer#getServerModName() when server-brand is enabled.",
				"",
				"Default: fabricFolia",
				"Valid values: any non-empty string up to 64 characters.",
				"Restart required: no."
		}));
		add(opt(KEY_GAMEPLAY, Boolean.class, Boolean.TRUE, v -> v, new String[] {
				"Regionized gameplay execution (mandates 15/21/27/37).",
				"",
				"What it does:",
				"  When true, entity tick bodies and block-entity tick bodies",
				"  execute on the owning region's worker thread instead of the",
				"  server thread. Vanilla still decides WHAT to tick (its own",
				"  passes run unchanged on the server thread); the bodies move.",
				"",
				"  Scheduled block/fluid tick DRAINS stay on the server thread",
				"  this phase: their BiConsumer writes cross regions freely, so",
				"  server-thread execution is the correct owner-confined choice",
				"  (the drain itself is already O(due), not O(world)).",
				"",
				"  ServerPlayer bodies are never staged (packet processing is",
				"  server-thread; a player body driven from two contexts would",
				"  corrupt movement and connection state).",
				"",
				"Default: true",
				"Valid values: true, false",
				"Restart required: no (takes effect next server start; runtime",
				"  toggle follows the engine quiescence rules).",
				"Experimental: no"}));
		add(opt(KEY_RANDOM_TICKS, Boolean.class, Boolean.FALSE, v -> v, new String[] {
				"Regionized random-tick execution (the first vanilla interception).",
				"",
				"What it does:",
				"  When true, per-chunk random ticking (crop growth, grass spread,",
				"  fire burn-out, ice/snow melt, leaf decay, copper aging, and the",
				"  per-chunk precipitation pass) is diverted from the single server",
				"  thread into the regionizer: each affected chunk is grouped into a",
				"  dynamic region and its random ticks execute on that region's",
				"  worker thread, in parallel with other regions.",
				"",
				"  When false (default), 100% vanilla execution: this interception",
				"  is deliberately OFF until an administrator opts in.",
				"",
				"Known limitation (see THREADING.md): while enabled, random ticks",
				"  mutate blocks on region workers while scheduled ticks (block and",
				"  fluid, e.g. falling sand, water flow scheduling) still execute on",
				"  the server thread. These two categories do not yet share the",
				"  region boundary, so a mod or datapack touching both in the same",
				"  area can observe interleaved execution. Entities, block entities,",
				"  and redstone remain entirely on the server thread regardless of",
				"  this flag.",
				"",
				"Default: false",
				"Valid values: true, false",
				"Performance: random-tick work moves OFF the server thread; helps",
				"  most when random ticking is a measurable server-thread cost",
				"  (large view/simulation distances, many loaded chunks).",
				"Compatibility: mods hooking random tick events see the same calls,",
				"  now possibly from a non-server thread. Block state mutation",
				"  itself remains correct (all mutation for an area lands on the",
				"  region that owns it); timing interleavings change.",
				"Safety: experimental interception; disable to return to vanilla.",
				"Restart required: no - applies live at the next tick cycle."
		}));

		add(opt(KEY_SECTION_SIZE, Integer.class, 8, v -> {
			if (v < 1 || v > 32 || Integer.bitCount(v) != 1) {
				throw new ConfigException(String.format(
						"region-section-size must be a power of two between 1 and 32 (got %d)", v));
			}
			return v;
		}, new String[] {
				"Region SECTION size in chunks (NxN grid), the internal bookkeeping",
				"granularity of the regionizer.",
				"",
				"IMPORTANT (see REGIONS.md): this is NOT the size or shape of a tick",
				"region. Regions are dynamic, mergeable, splittable groups of these",
				"sections; final regions may be much larger or smaller than NxN.",
				"\"8x8\" here only means the default bookkeeping cell size.",
				"",
				"What it does:",
				"  The regionizer groups chunks into NxN-chunk sections and builds",
				"  regions out of whole sections. Larger sections = coarser, cheaper",
				"  bookkeeping but potentially more empty-buffer area kept loaded",
				"  around activity; smaller = finer parallelism, more bookkeeping.",
				"",
				"Default: 8 (i.e. 8x8 = 64 chunks per section)",
				"Valid values: any power of two from 1 to 32",
				"Performance: 8 is a balanced default; 4 halves bookkeeping cell area.",
				"Compatibility: safe at any supported value; this is internal",
				"  bookkeeping only and does not change game behavior.",
				"Safety: values outside the supported powers of two are rejected.",
				"Restart required: yes (affects live region geometry)."
		}));

		add(opt(KEY_WORKER_THREADS, Integer.class, -1, v -> {
			if (v < -1 || v == 0) {
				throw new ConfigException(String.format(
						"worker-threads must be a positive integer, or -1 for auto (got %d)", v));
			}
			return v;
		}, new String[] {
				"Number of worker threads in the region execution pool.",
				"",
				"What it does:",
				"  Regions are dispatched onto this bounded pool as they become due",
				"  (earliest-start-time-first, like Folia's scheduler). Regions are",
				"  NOT pinned to threads; a region may run on different workers on",
				"  different ticks.",
				"",
				"Default: -1 = auto. Auto resolves to roughly 50% of available CPU",
				"  cores, minimum 2, following Folia's guidance that region threads",
				"  should not exceed ~80% of cores (they compete with network and",
				"  chunk-IO threads).",
				"",
				"Valid values: -1 (auto), or any integer >= 1.",
				"Performance: more workers = more concurrent regions but more",
				"  contention with chunk loading and network IO. Extra workers sit",
				"  idle (cheap) when there are fewer regions than workers.",
				"Compatibility: none specific.",
				"Safety: too-high values can starve chunk IO on small-core machines.",
				"Restart required: yes."
		}));

		add(opt(KEY_THREAD_CHECK, String.class, "WARN", v -> {
			String upper = String.valueOf(v).toUpperCase(Locale.ROOT);
			return switch (upper) {
				case "STRICT", "WARN", "OFF" -> upper;
				default -> throw new ConfigException(String.format(
						"thread-check-mode must be one of STRICT, WARN, OFF (got %s)", v));
			};
		}, new String[] {
				"Thread-ownership violation checking (see THREADING.md).",
				"",
				"What it does:",
				"  Detects code touching region-owned state from the wrong context -",
				"  including from OTHER MODS - and reports it with an actionable",
				"  diagnostic naming the operation, both regions involved, the",
				"  physical thread, and the scheduler entry point that should have",
				"  been used instead.",
				"",
				"STRICT: throw immediately at the violating access. Use in",
				"  development; catches bugs at the exact call site. Has real",
				"  per-access cost.",
				"WARN: log the violation with full diagnostics and continue.",
				"  Intended production default.",
				"OFF: no checks at all, zero runtime cost.",
				"",
				"Default: WARN (production). Development builds of this mod run",
				"  STRICT via the dev config profile, not this file.",
				"Valid values: STRICT, WARN, OFF (case-insensitive)",
				"Performance: STRICT > WARN > OFF (roughly).",
				"Compatibility: your primary tool for diagnosing mod incompatibility",
				"  - a stream of violations naming another mod's classes is a",
				"  compatibility bug to report to that mod, not to ignore.",
				"Safety: OFF hides ownership bugs; do not use OFF unless you accept",
				"  silent world corruption as a possible outcome.",
				"Restart required: no - applies live."
		}));

		add(opt(KEY_DEBUG, Boolean.class, Boolean.FALSE, v -> v, new String[] {
				"Verbose debug logging from the regionizer and schedulers.",
				"",
				"What it does: logs region creation, merge, split, tick scheduling",
				"decisions, and task queue depths at DEBUG level. Very chatty.",
				"",
				"Default: false",
				"Valid values: true, false",
				"Performance: noticeable logging overhead; development only.",
				"Restart required: no - applies live."
		}));

		add(opt(KEY_DEBUG_LOGGING, Boolean.class, Boolean.FALSE, v -> v, new String[] {
				"Per-tick diagnostic logging for troubleshooting (default OFF).",
				"",
				"What it does: when true, logs what normal operation stays quiet",
				"  about - per-tick region dispatch lines, worker-thread execution",
				"  evidence, and the full installed-mod classification table.",
				"",
				"  When false (default), normal server operation is clean: the only",
				"  regular output is startup, the compatibility summary, command",
				"  replies, and WARN-level diagnostics.",
				"",
				"Default: false",
				"Valid values: true, false",
				"Performance: noticeable log volume at high chunk counts; enable",
				"  only while investigating. Never needed for normal operation.",
				"Compatibility: no effect on behavior - logging only.",
				"Restart required: no - applies live."
		}));

		add(opt(KEY_PROFILING, Boolean.class, Boolean.FALSE, v -> v, new String[] {
				"Per-region tick timing diagnostics.",
				"",
				"What it does: tracks and logs per-region tick durations, letting you",
				"identify which regions are slow. A slow region only slows itself;",
				"see THREADING.md for the independence guarantee.",
				"",
				"Default: false",
				"Valid values: true, false",
				"Performance: small constant overhead per region tick.",
				"Restart required: no - applies live."
		}));

		add(opt(KEY_WATCHDOG, Boolean.class, Boolean.TRUE, v -> v, new String[] {
				"Region-aware stall watchdog.",
				"",
				"What it does: scans every 10s for regions stuck in the TICKING",
				"state far past their own next-tick deadline and reports them with",
				"region id, tick count, last duration, and overdue time - the",
				"information needed to find the blocking frame in a thread dump.",
				"There is no single main thread to watch; each region is watched",
				"independently, and one stalled region never masks another.",
				"",
				"Default: true",
				"Valid values: true, false",
				"Performance: one lock-free region scan per 10s.",
				"Restart required: no - applies at next engine start."
		}));

		add(opt(KEY_PLAYER_PATH, Boolean.class, Boolean.TRUE, v -> v, new String[] {
				"Regionize the player path: stage each player's connection tick",
				"(packet drain, connection state, physics chain) onto the region",
				"owning the player's chunk, and route re-homed packet handlers",
				"there too.",
				"",
				"What it does: gives a player exactly one owning context per tick",
				"(the region), so packet handling and movement are serialized with",
				"the region's other work and parallel across regions. Two verified",
				"server-thread couplings inside the staged body (chunk-view move,",
				"game-mode tick) are bounced to the server thread automatically.",
				"",
				"Default: true",
				"Valid values: true, false",
				"Performance: one region-queue enqueue per player per tick.",
				"Restart required: no - applies at next engine start."
		}));

		add(opt(KEY_METRICS, Boolean.class, Boolean.FALSE, v -> v, new String[] {
				"Lightweight metrics: TPS per region, worker utilization,",
				"cross-region operation counts, queue depths.",
				"",
				"What it does: keeps rolling counters readable via /folia status.",
				"This is not a full metrics-export system (no Prometheus etc.) -",
				"it exists to back the benchmark methodology in the project docs.",
				"",
				"Default: false",
				"Valid values: true, false",
				"Performance: negligible.",
				"Restart required: no - applies live."
		}));
	}

	private void add(Option<?> option) {
		Option<?> existing = options.putIfAbsent(option.key, option);
		if (existing != null) {
			throw new IllegalStateException("Duplicate config key: " + option.key);
		}
	}

	/** @return the option for {@code key}, or null if unknown. */
	public Option<?> option(String key) {
		return options.get(key);
	}

	/** @return all options in declaration order (drives the generated file layout). */
	public Collection<Option<?>> options() {
		return Collections.unmodifiableCollection(options.values());
	}

	/** Builds the canonical ordered map of section -> ordered keys (file layout). */
	Map<String, List<String>> layout() {
		Map<String, List<String>> layout = new LinkedHashMap<>();
		for (Option<?> o : options.values()) {
			String section = o.key().substring(0, o.key().indexOf('.'));
			layout.computeIfAbsent(section, k -> new ArrayList<>()).add(o.key());
		}
		return layout;
	}
}
