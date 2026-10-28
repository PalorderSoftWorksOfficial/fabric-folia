package com.palordersoftworks.fabricfolia.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionMetricsServerTickTest {

	@Test
	void serverTickPercentilesAndMissedDeadlinesAreExact() {
		RegionMetrics metrics = new RegionMetrics(1);
		for (int i = 1; i <= 100; i++) {
			metrics.recordServerTick(i * 1_000_000L);
		}

		List<String> lines = metrics.snapshotLines();
		String line = lines.stream()
				.filter(s -> s.startsWith("Server MSPT (tick work)"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("server MSPT line missing: " + lines));

		assertTrue(line.contains("p50=50.00"), line);
		assertTrue(line.contains("p95=95.00"), line);
		assertTrue(line.contains("p99=99.00"), line);
		assertTrue(line.contains("max=100.00"), line);
		assertTrue(line.contains("missed(>50ms)=50"), line);
		assertTrue(line.contains("recent-window=50/100"), line);
	}

	@Test
	void emptyRingReportsZeros() {
		RegionMetrics metrics = new RegionMetrics(1);
		String line = metrics.snapshotLines().stream()
				.filter(s -> s.startsWith("Server MSPT (tick work)"))
				.findFirst()
				.orElseThrow();
		assertTrue(line.contains("p50=0.00"), line);
		assertTrue(line.contains("missed(>50ms)=0"), line);
		assertTrue(line.contains("recent-window=0/0"), line);
	}
}
