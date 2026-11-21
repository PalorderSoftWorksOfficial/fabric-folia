package com.palordersoftworks.fabricfolia.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionMetricsWorkCountersTest {

	private static String workLine(RegionMetrics metrics) {
		return metrics.snapshotLines().stream()
				.filter(s -> s.startsWith("Work:"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Work line missing"));
	}

	@Test
	void randomTickChunksStartAtZeroAndRender() {
		RegionMetrics metrics = new RegionMetrics(1);
		assertTrue(workLine(metrics).contains("randomTickChunks=0"),
				"Work line must expose the random-tick chunk counter, got: " + workLine(metrics));
	}

	@Test
	void randomTickChunksIncrementExactly() {
		RegionMetrics metrics = new RegionMetrics(1);
		metrics.increment(RegionMetrics.Counter.RANDOM_TICK_CHUNKS_EXECUTED);
		metrics.increment(RegionMetrics.Counter.RANDOM_TICK_CHUNKS_EXECUTED);
		metrics.increment(RegionMetrics.Counter.RANDOM_TICK_CHUNKS_EXECUTED);
		String line = workLine(metrics);
		assertTrue(line.contains("randomTickChunks=3"), "Work line must count executed chunks, got: " + line);
		assertEquals(3, metrics.value(RegionMetrics.Counter.RANDOM_TICK_CHUNKS_EXECUTED));
	}
}
