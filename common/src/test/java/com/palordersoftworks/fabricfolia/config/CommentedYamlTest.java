/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the comment-preserving YAML engine (spec 0 step 5, 11, 13).
 *
 * <p>These tests are load-bearing: the project's promise to administrators is
 * that their comments survive saves. They are verified here against the real
 * SnakeYAML Engine low-level path, not assumed from documentation.</p>
 */
class CommentedYamlTest {

	@Test
	void roundTripPreservesOperatorComments(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("config.yml");
		String original = String.join("\n",
				"# My server's custom note about the master switch.",
				"# We run anarchy; uptime matters.",
				"config-version: 1",
				"general:",
				"  # We tested STRICT in staging; WARN is our production choice.",
				"  enabled: true",
				"  regionized-random-ticks: false",
				"regions:",
				"  region-section-size: 8",
				"threads:",
				"  worker-threads: 4",
				"  thread-check-mode: WARN",
				"diagnostics:",
				"  debug: false",
				"  profiling: false",
				"  metrics: false",
				"");
		Files.writeString(file, original, StandardCharsets.UTF_8);

		FoliaConfig config = FoliaConfig.at(file);
		config.load();

		String after = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(after.contains("# My server's custom note about the master switch."),
				"operator comment above version key lost");
		assertTrue(after.contains("# We tested STRICT in staging; WARN is our production choice."),
				"operator comment above enabled lost");
	}

	@Test
	void missingKeysAreAppendedWithDocumentation() throws IOException {
		String partial = String.join("\n",
				"config-version: 1",
				"general:",
				"  enabled: true",
				"");
		CommentedYaml parsed = CommentedYaml.load(new StringReader(partial));
		var merged = ConfigWriter.mergePreservingComments(parsed, ConfigSchema.get());
		String text = YamlWriter.write(merged);

		assertTrue(text.contains("region-section-size"), "missing option not appended");
		assertTrue(text.contains("Valid values: any power of two from 1 to 32"),
				"appended option lacks its documentation");
		assertTrue(text.contains("enabled: true"), "existing value changed");
	}

	@Test
	void unknownKeysArePreservedAndFlagged() {
		String withUnknown = String.join("\n",
				"config-version: 1",
				"general:",
				"  enabled: true",
				"  my-custom-key: 42",
				"");
		CommentedYaml parsed = CommentedYaml.load(new StringReader(withUnknown));
		var merged = ConfigWriter.mergePreservingComments(parsed, ConfigSchema.get());
		String text = YamlWriter.write(merged);

		assertTrue(text.contains("my-custom-key: 42"), "unknown key dropped");
		assertTrue(text.contains("not recognized by this version"),
				"unknown key not flagged");
	}

	@Test
	void editedValuesSurviveAndVersionIsRewritten() {
		String edited = String.join("\n",
				"config-version: 1",
				"general:",
				"  enabled: false",
				"regions:",
				"  region-section-size: 4",
				"");
		CommentedYaml parsed = CommentedYaml.load(new StringReader(edited));
		var merged = ConfigWriter.mergePreservingComments(parsed, ConfigSchema.get());
		String text = YamlWriter.write(merged);

		assertTrue(text.contains("enabled: false"));
		assertTrue(text.contains("region-section-size: 4"));
		assertTrue(text.contains("config-version: 1"));
		assertFalse(text.contains("config-version: 2"));
	}

	@Test
	void duplicateKeysCollapseToLastValue() {
		// The low-level Composer does not enforce duplicate-key policy, so a
		// duplicated key parses into two sibling tuples. Load must collapse:
		// last value wins (YAML load semantics), the merge must not see two,
		// and the saved file must contain the key exactly once.
		String duplicated = String.join("\n",
				"config-version: 1",
				"general:",
				"  enabled: true",
				"  enabled: false",
				"");
		CommentedYaml parsed = CommentedYaml.load(new StringReader(duplicated));
		assertEquals(false, parsed.get("general.enabled"),
				"last duplicate value must win");

		var merged = ConfigWriter.mergePreservingComments(parsed, ConfigSchema.get());
		String text = YamlWriter.write(merged);
		assertEquals(1, countKey(text, "enabled:"),
				"duplicate must not survive a save");
		assertTrue(text.contains("enabled: false"));
	}

	private static int countKey(String haystack, String needle) {
		int count = 0;
		int idx = 0;
		while ((idx = haystack.indexOf(needle, idx)) != -1) {
			count++;
			idx += needle.length();
			if (idx < haystack.length() && !Character.isWhitespace(haystack.charAt(idx))) {
				// Part of a longer key like enabled-foo — not a duplicate.
				count--;
			}
		}
		return count;
	}

	@Test
	void generatedDefaultIsCompleteAndDocumented() throws IOException {
		MappingNodeHelper helper = new MappingNodeHelper();
		String template = helper.renderDefault();

		// Every schema option appears in the generated file.
		for (ConfigSchema.Option<?> option : ConfigSchema.get().options()) {
			String leaf = option.key().substring(option.key().lastIndexOf('.') + 1);
			assertTrue(template.contains(leaf + ":"), "missing option in template: " + option.key());
		}
		// Documentation quality bar: meaningful phrases present.
		assertTrue(template.contains("Restart required"));
		assertTrue(template.contains("Valid values"));
		assertTrue(template.contains("Default:"));
	}

	/** Small helper to render the default template through the real writer. */
	static final class MappingNodeHelper {
		String renderDefault() {
			var tree = ConfigWriter.buildDefaultTree(ConfigSchema.get());
			return YamlWriter.write(tree);
		}
	}

	@Test
	void invalidValuesFailClosed() {
		FoliaConfig config = FoliaConfig.at(Path.of("does-not-matter.yml"));
		// Direct validator checks — file-level failure paths covered in FoliaConfigTest.
		ConfigSchema.Option<?> section = ConfigSchema.get().option(ConfigSchema.KEY_SECTION_SIZE);
		assertThrows(ConfigException.class, () -> invokeValidate(section, 12));
		ConfigSchema.Option<?> threads = ConfigSchema.get().option(ConfigSchema.KEY_WORKER_THREADS);
		assertThrows(ConfigException.class, () -> invokeValidate(threads, 0));
		ConfigSchema.Option<?> mode = ConfigSchema.get().option(ConfigSchema.KEY_THREAD_CHECK);
		assertThrows(ConfigException.class, () -> invokeValidate(mode, "SOMETIMES"));
	}

	@SuppressWarnings("unchecked")
	private static <T> void invokeValidate(ConfigSchema.Option<T> option, Object value) {
		// Deliberate unchecked bridge for tests: validate() is package-private
		// to keep the surface clean; tests live in the same package.
		option.validate(option.type().cast(value));
	}
}
