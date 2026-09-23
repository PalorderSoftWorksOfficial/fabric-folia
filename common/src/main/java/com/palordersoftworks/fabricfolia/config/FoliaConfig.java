/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.config;

import org.snakeyaml.engine.v2.nodes.MappingNode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The live configuration object: load → validate → migrate → save, with typed
 * accessors for the engine.
 *
 * <p><strong>Threading contract:</strong> load and save are called during startup
 * and quiescence (single-context moments). Typed accessors are safe from any
 * thread AFTER {@link #freeze()} — values are plain finals thereafter; no lock is
 * needed because mutation only happens during load/repair, which the lifecycle
 * confines to one context (spec 4 state classification: GLOBAL).</p>
 *
 * <p><strong>Fail-closed:</strong> invalid values throw {@link ConfigException};
 * the fabric module catches it and runs vanilla-execution-disabled with a clear
 * log explaining the offending key. We never guess operator intent (a silently
 * defaulting config could invert a safety-critical setting like
 * thread-check-mode).</p>
 */
public final class FoliaConfig {

	private final Path file;
	private final Map<String, Object> values = new HashMap<>();
	private boolean frozen;

	private FoliaConfig(Path file) {
		this.file = file;
	}

	/** @return a config bound to {@code file}, without loading it yet. */
	public static FoliaConfig at(Path file) {
		return new FoliaConfig(file);
	}

	/**
	 * Loads, validates, repairs (adds missing keys), migrates, and saves the
	 * config. Comments are preserved on save.
	 *
	 * @throws ConfigException on unparseable YAML or invalid values
	 */
	public void load() throws IOException {
		if (frozen) {
			throw new IllegalStateException("Config already frozen");
		}
		ConfigSchema schema = ConfigSchema.get();

		if (!Files.exists(file)) {
			// First run: generate the full documented template.
			MappingNode tree = ConfigWriter.buildDefaultTree(schema);
			save(tree);
			CommentedYaml parsed = parseFile();
			validateAgainst(schema, parsed);
			return;
		}

		CommentedYaml parsed = parseFile();
		migrateIfNeeded(schema, parsed);
		// Repair BEFORE validating: mergePreservingComments backfills schema
		// defaults for missing keys (the documented "adds missing keys"), so a
		// partial file is repaired and saved rather than rejected. Validation
		// then covers exactly what is on disk; genuinely bad VALUES still fail
		// closed. (Order found inverted by the compatibility harness feeding a
		// minimal config: validation-first turned a repairable file fatal.)
		MappingNode merged = ConfigWriter.mergePreservingComments(parsed, schema);
		save(merged);
		validateAgainst(schema, parseFile());
	}

	private CommentedYaml parseFile() throws IOException {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			return CommentedYaml.load(reader);
		}
	}

	private void validateAgainst(ConfigSchema schema, CommentedYaml parsed) {
		for (ConfigSchema.Option<?> option : schema.options()) {
			Object raw = parsed.get(option.key());
			if (raw == null) {
				throw new ConfigException("Config file is missing key '" + option.key()
						+ "' after repair; the file may be read-only or corrupted");
			}
			Object typed = coerce(option, raw);
			values.put(option.key(), typed);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object coerce(ConfigSchema.Option<?> option, Object raw) {
		Class<?> type = option.type();
		Object value;
		if (type == Boolean.class) {
			if (raw instanceof Boolean b) {
				value = b;
			} else {
				throw new ConfigException(String.format(
						"Key '%s' expects true/false, found: %s", option.key(), describe(raw)));
			}
		} else if (type == Integer.class) {
			if (raw instanceof Integer i) {
				value = i;
			} else if (raw instanceof String s) {
				try {
					value = Integer.parseInt(s.trim());
				} catch (NumberFormatException e) {
					throw new ConfigException(String.format(
							"Key '%s' expects an integer, found: %s", option.key(), describe(raw)));
				}
			} else {
				throw new ConfigException(String.format(
						"Key '%s' expects an integer, found: %s", option.key(), describe(raw)));
			}
		} else if (type == String.class) {
			value = String.valueOf(raw);
		} else {
			throw new IllegalStateException("Unsupported option type: " + type);
		}
		return ((ConfigSchema.Option) option).validate(value);
	}

	private static String describe(Object raw) {
		return raw == null ? "nothing" : raw.getClass().getSimpleName() + "(" + raw + ")";
	}

	/**
	 * Migration path for config-version changes (spec 11). Version 1 is current;
	 * older versions are migrated field-by-field here as they exist. An unknown
	 * FUTURE version fails closed: a newer config file implies a newer mod, and
	 * silently guessing semantics could invert safety-relevant settings.
	 */
	private void migrateIfNeeded(ConfigSchema schema, CommentedYaml parsed) {
		Object rawVersion = parsed.get(ConfigSchema.KEY_VERSION);
		int version;
		if (rawVersion instanceof Integer i) {
			version = i;
		} else if (rawVersion instanceof String s) {
			try {
				version = Integer.parseInt(s.trim());
			} catch (NumberFormatException e) {
				throw new ConfigException("config-version is not a number: " + describe(rawVersion));
			}
		} else if (rawVersion == null) {
			// Pre-versioning file (or hand-made): treat as version 1, unknown
			// keys are flagged by the merge, missing keys get repaired.
			version = 1;
		} else {
			throw new ConfigException("config-version is not a number: " + describe(rawVersion));
		}

		if (version > ConfigSchema.CURRENT_VERSION) {
			throw new ConfigException("Config file version " + version
					+ " is newer than this mod understands (" + ConfigSchema.CURRENT_VERSION
					+ "). Update Fabric-Folia, or restore a matching config.");
		}
		if (version < ConfigSchema.CURRENT_VERSION) {
			// Future migrations chain here, e.g. migrateV1toV2(parsed).
			// After this build there are no older versions; the branch exists so
			// the migration point is explicit, not implicit.
		}
	}

	private void save(MappingNode tree) throws IOException {
		String text = YamlWriter.write(tree);
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			writer.write(text);
		}
	}

	/**
	 * Freezes the config after load: accessors become safe from any thread.
	 */
	public void freeze() {
		this.frozen = true;
	}

	// =================================================================================
	// Typed accessors (post-freeze). Each documents its semantic mapping.
	// =================================================================================

	/** @return the master switch (spec 11 {@code general.enabled}). */
	public boolean enabled() {
		return (Boolean) values.get(ConfigSchema.KEY_ENABLED);
	}

	/**
	 * @return whether regionized gameplay staging is enabled
	 * ({@code general.regionized-gameplay}; default true). Entity tick bodies
	 * and block-entity tick bodies execute on the owning region's worker;
	 * false keeps those bodies on the vanilla server thread.
	 */
	public boolean regionizedGameplay() {
		return (Boolean) values.get(ConfigSchema.KEY_GAMEPLAY);
	}

	/**
	 * @return whether regionized random-tick interception is opted in
	 * ({@code general.regionized-random-ticks}; default false = pure vanilla).
	 */
	public boolean regionizedRandomTicks() {
		return (Boolean) values.get(ConfigSchema.KEY_RANDOM_TICKS);
	}

	/** @return region section size N (NxN chunks, power of two). */
	public int regionSectionSize() {
		return (Integer) values.get(ConfigSchema.KEY_SECTION_SIZE);
	}

	/**
	 * @return the configured worker thread count, or the resolved auto value
	 * ({@code -1} means auto and is resolved by
	 * {@link WorkerThreads#resolve(int)} at scheduler construction).
	 */
	public int workerThreads() {
		return (Integer) values.get(ConfigSchema.KEY_WORKER_THREADS);
	}

	/** @return the thread-check mode, upper-cased. */
	public String threadCheckMode() {
		return String.valueOf(values.get(ConfigSchema.KEY_THREAD_CHECK))
				.toUpperCase(Locale.ROOT);
	}

	public boolean debugLogging() {
		return (Boolean) values.get(ConfigSchema.KEY_DEBUG_LOGGING);
	}

	public boolean debug() {
		return (Boolean) values.get(ConfigSchema.KEY_DEBUG);
	}

	public boolean profiling() {
		return (Boolean) values.get(ConfigSchema.KEY_PROFILING);
	}

	public boolean metrics() {
		return (Boolean) values.get(ConfigSchema.KEY_METRICS);
	}

	/** @return whether the region stall watchdog runs ({@code diagnostics.watchdog}). */
	public boolean watchdog() {
		return (Boolean) values.get(ConfigSchema.KEY_WATCHDOG);
	}

	/**
	 * @return the raw typed value at {@code key}, or null when absent (for
	 * optional keys: callers resolve their own defaults).
	 */
	public Object valueOrNull(String key) {
		return values.get(key);
	}

	public boolean serverBrand() {
		return (Boolean) values.get(ConfigSchema.KEY_SERVER_BRAND);
	}

	public String serverBrandName() {
		return (String) values.get(ConfigSchema.KEY_SERVER_BRAND_NAME);
	}

	/** Resolves the {@code auto} sentinel for worker threads (also used by tests). */
	public static final class WorkerThreads {
		private WorkerThreads() {
		}

		/**
		 * @param configured the raw configured value (-1 = auto)
		 * @return the resolved thread count: max(2, cores/2) for auto; follows
		 * Folia's guidance not to exceed ~80% of cores for region threads.
		 */
		public static int resolve(int configured) {
			if (configured >= 1) {
				return configured;
			}
			int cores = Runtime.getRuntime().availableProcessors();
			return Math.max(2, cores / 2);
		}
	}
}
