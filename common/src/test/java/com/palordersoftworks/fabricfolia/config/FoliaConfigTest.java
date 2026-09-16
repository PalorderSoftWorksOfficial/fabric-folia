/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle tests for FoliaConfig: first-run template generation, fail-closed
 * validation, freeze semantics, and future-version refusal.
 */
class FoliaConfigTest {

	@Test
	void firstRunGeneratesCompleteTemplate(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("fabric-folia.yml");
		FoliaConfig config = FoliaConfig.at(file);
		config.load();

		String text = Files.readString(file, StandardCharsets.UTF_8);
		// The default file must be a complete, documented template (spec 11),
		// not a near-empty stub.
		for (String key : new String[] {
				"config-version", "enabled", "regionized-random-ticks", "region-section-size",
				"worker-threads", "thread-check-mode", "debug", "profiling", "metrics"}) {
			assertTrue(text.contains(key + ":"), "template missing key: " + key);
		}
		assertTrue(text.contains("Restart required"), "template missing documentation");
		assertTrue(text.contains("Valid values"), "template missing documentation");

		// And the values validate into typed accessors.
		config.freeze();
		assertTrue(config.enabled());
		// The interception opt-in must default OFF (pure vanilla until an
		// administrator asks for it).
		assertFalse(config.regionizedRandomTicks());
		assertEquals(8, config.regionSectionSize());
		assertEquals(-1, config.workerThreads());
		assertEquals("WARN", config.threadCheckMode());
	}

	@Test
	void partialConfigIsRepairedNotFatal(@TempDir Path dir) throws IOException {
		// Regression (found by the production compatibility harness): a minimal
		// hand-written config used to fail validation with "missing key ... after
		// repair" — validation ran BEFORE the repair/merge step that backfills
		// schema defaults. The correct contract: missing keys are backfilled and
		// saved; only genuinely bad VALUES fail closed.
		Path file = dir.resolve("fabric-folia.yml");
		Files.writeString(file, String.join("\n",
				"config-version: 1",
				"general:",
				"  enabled: true",
				""), StandardCharsets.UTF_8);
		FoliaConfig config = FoliaConfig.at(file);
		config.load();
		config.freeze();

		assertTrue(config.enabled());
		// Everything else came from the schema defaults via repair.
		assertFalse(config.regionizedRandomTicks());
		assertEquals(8, config.regionSectionSize());
		assertEquals("WARN", config.threadCheckMode());
		// And the repaired file was persisted with the defaults added.
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.contains("region-section-size:"), "repair did not backfill defaults");
	}

	@Test
	void nonUtf8ConfigMessageIsActionable(@TempDir Path dir) throws IOException {
		// Regression (found by the production compatibility harness): a config
		// saved in a legacy code page produced Java's raw
		// MalformedInputException with no guidance. The message must tell the
		// admin the file must be UTF-8 and how to fix it.
		Path file = dir.resolve("fabric-folia.yml");
		Files.write(file, new byte[] {(byte) 'k', (byte) 'e', (byte) 'y', (byte) ':', (byte) ' ',
				(byte) 0x93, (byte) 'x', (byte) 0x94, (byte) '\n'});
		FoliaConfig config = FoliaConfig.at(file);
		ConfigException e = assertThrows(ConfigException.class, config::load);
		String message = e.getMessage();
		assertTrue(message.contains("UTF-8"), "message must name the encoding: " + message);
		assertTrue(message.contains("re-save"), "message must give the remedy: " + message);
	}

	@Test
	void futureVersionFailsClosed(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("config.yml");
		Files.writeString(file, String.join("\n",
				"config-version: 999",
				"general:",
				"  enabled: true",
				""), StandardCharsets.UTF_8);

		FoliaConfig config = FoliaConfig.at(file);
		assertThrows(ConfigException.class, config::load);
	}

	@Test
	void invalidValueFailsClosed(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("config.yml");
		Files.writeString(file, String.join("\n",
				"config-version: 1",
				"regions:",
				"  region-section-size: 7",
				""), StandardCharsets.UTF_8);

		FoliaConfig config = FoliaConfig.at(file);
		assertThrows(ConfigException.class, config::load);
	}

	@Test
	void secondLoadIsIdempotentAndPreservesComments(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("config.yml");
		FoliaConfig first = FoliaConfig.at(file);
		first.load();

		// Operator edits the file between runs.
		String edited = Files.readString(file, StandardCharsets.UTF_8)
				.replace("worker-threads: -1", "worker-threads: 6");
		Files.writeString(file, edited, StandardCharsets.UTF_8);

		FoliaConfig second = FoliaConfig.at(file);
		second.load();
		second.freeze();
		assertEquals(6, second.workerThreads());

		// Double-load must not duplicate options or comments.
		FoliaConfig third = FoliaConfig.at(file);
		third.load();
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertEquals(1, countOccurrences(text, "worker-threads:"),
				"load duplicated a key");
	}

	private static int countOccurrences(String haystack, String needle) {
		int count = 0;
		int idx = 0;
		while ((idx = haystack.indexOf(needle, idx)) != -1) {
			count++;
			idx += needle.length();
		}
		return count;
	}

	@Test
	void workerThreadAutoResolution() {
		// Auto resolves to at least 2 and roughly half the cores.
		int auto = FoliaConfig.WorkerThreads.resolve(-1);
		assertTrue(auto >= 2);
		int cores = Runtime.getRuntime().availableProcessors();
		assertEquals(Math.max(2, cores / 2), auto);

		// Explicit values pass through; 0 and negatives other than -1 are
		// rejected at validation, but resolve() still guards.
		assertEquals(4, FoliaConfig.WorkerThreads.resolve(4));
	}
}
