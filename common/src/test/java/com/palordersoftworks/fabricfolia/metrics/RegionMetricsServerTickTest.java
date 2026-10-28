package com.palordersoftworks.fabricfolia.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionMetricsServerTickTest {

	private static String serverTickLine(RegionMetrics metrics) {
		return metrics.snapshotLines().stream()
				.filter(s -> s.startsWith("Server MSPT (tick work)"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("server MSPT line missing"));
	}

	@Test
	void serverTickPercentilesAndMissedDeadlinesAreExact() {
		RegionMetrics metrics = new RegionMetrics(1);
		for (int i = 1; i <= 100; i++) {
			metrics.recordServerTick(i * 1_000_000L);
		}

		// Exact full-line match: substring checks would pass with extra
		// digits or reordered fields; the format is deterministic.
		assertEquals("Server MSPT (tick work): p50=50.00 p95=95.00 p99=99.00 max=100.00 "
				+ "missed(>50ms)=50 recent-window=50/100",
				serverTickLine(metrics));
	}

	@Test
	void deadlineBoundaryIsStrictlyGreater() {
		RegionMetrics metrics = new RegionMetrics(1);
		metrics.recordServerTick(50_000_000L);
		metrics.recordServerTick(50_000_001L);
		// Exactly 50ms is ON the deadline, not over it: only the 50ms+1ns
		// tick may count as a miss, in both the cumulative and the window.
		assertEquals("Server MSPT (tick work): p50=50.00 p95=50.00 p99=50.00 max=50.00 "
				+ "missed(>50ms)=1 recent-window=1/2",
				serverTickLine(metrics));
	}

	@Test
	void emptyRingReportsZeros() {
		RegionMetrics metrics = new RegionMetrics(1);
		assertEquals("Server MSPT (tick work): p50=0.00 p95=0.00 p99=0.00 max=0.00 "
				+ "missed(>50ms)=0 recent-window=0/0",
			serverTickLine(metrics));
	}
}
