package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StallProbeTest {

	@TempDir
	Path tempDir;

	@Test
	void emptyServerPauseIsSuppressedOnlyAfterConfiguredThreshold() {
		assertFalse(StallProbe.shouldSuppressEmptyPause(true, 60, 0, 1199));
		assertTrue(StallProbe.shouldSuppressEmptyPause(true, 60, 0, 1200));
		assertFalse(StallProbe.shouldSuppressEmptyPause(true, 60, 1, 1200));
		assertFalse(StallProbe.shouldSuppressEmptyPause(true, 0, 0, 1200));
		assertFalse(StallProbe.shouldSuppressEmptyPause(false, 60, 0, 1200));
	}

	@Test
	void dumpRetentionKeepsOnlyTenNewestStallFiles() throws IOException {
		long now = 2_000_000_000L;
		for (int i = 0; i < 12; i++) {
			Path file = tempDir.resolve(String.format("stall-%04d.log", i));
			Files.writeString(file, "dump-" + i);
			Files.setLastModifiedTime(file, FileTime.fromMillis(now + i));
		}
		Path unrelated = Files.writeString(tempDir.resolve("other.log"), "keep");
		StallProbe.pruneDumps(tempDir, now);
		List<String> remaining = listNames();
		assertEquals(11, remaining.size());
		assertFalse(remaining.contains("stall-0000.log"));
		assertFalse(remaining.contains("stall-0001.log"));
		assertTrue(remaining.contains("stall-0011.log"));
		assertTrue(Files.exists(unrelated));
	}

	@Test
	void dumpRetentionRemovesExpiredFiles() throws IOException {
		long now = 2_000_000_000L;
		Path expired = Files.writeString(tempDir.resolve("stall-expired.log"), "old");
		Files.setLastModifiedTime(expired, FileTime.fromMillis(now - 7L * 24 * 60 * 60 * 1000 - 1));
		Path recent = Files.writeString(tempDir.resolve("stall-recent.log"), "new");
		Files.setLastModifiedTime(recent, FileTime.fromMillis(now));
		StallProbe.pruneDumps(tempDir, now);
		assertFalse(Files.exists(expired));
		assertTrue(Files.exists(recent));
	}

	@Test
	void dumpRetentionEnforcesTotalByteLimit() throws IOException {
		long now = 2_000_000_000L;
		Path older = tempDir.resolve("stall-older.log");
		Path newer = tempDir.resolve("stall-newer.log");
		byte[] payload = new byte[6 * 1024 * 1024];
		Files.write(older, payload);
		Files.write(newer, payload);
		Files.setLastModifiedTime(older, FileTime.fromMillis(now - 1));
		Files.setLastModifiedTime(newer, FileTime.fromMillis(now));
		StallProbe.pruneDumps(tempDir, now);
		assertFalse(Files.exists(older));
		assertTrue(Files.exists(newer));
	}
	
	private List<String> listNames() throws IOException {
		try (var files = Files.list(tempDir)) {
			return files.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}
}
