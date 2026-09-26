package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkResidencyTest {

	@AfterEach
	void reset() {
		ChunkResidency.clearAll();
	}

	@Test
	void neighborhoodPassesOnlyWhenAllNineChunksAreResident() {
		String world = "test:world";
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkResidency.markResident(world, dx, dz);
			}
		}
		assertTrue(ChunkResidency.isNeighborhoodResident(world, 0, 0));
		ChunkResidency.markUnresident(world, 1, 1);
		assertFalse(ChunkResidency.isNeighborhoodResident(world, 0, 0));
	}

	@Test
	void untrackedNeighborhoodNeverPasses() {
		assertFalse(ChunkResidency.isNeighborhoodResident("test:absent", 5, 5));
	}

	@Test
	void clearWorldDropsResidency() {
		String world = "test:world";
		ChunkResidency.markResident(world, 0, 0);
		ChunkResidency.clearWorld(world);
		assertFalse(ChunkResidency.isNeighborhoodResident(world, 0, 0));
		assertEquals(0, ChunkResidency.residentCount(world));
	}

	@Test
	void repeatedMarksStayIdempotent() {
		ChunkResidency.markResident("test:world", 3, 4);
		ChunkResidency.markResident("test:world", 3, 4);
		assertEquals(1, ChunkResidency.residentCount("test:world"));
	}

	@Test
	void readersNeverFailWhileTheWriterChurnsSnapshots() throws Exception {
		String world = "test:stress";
		AtomicBoolean stop = new AtomicBoolean(false);
		AtomicInteger failures = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(2);
		for (int t = 0; t < 2; t++) {
			Thread reader = new Thread(() -> {
				try {
					while (!stop.get()) {
						ChunkResidency.isNeighborhoodResident(world, 0, 0);
						ChunkResidency.residentCount(world);
					}
				} catch (Throwable e) {
					failures.incrementAndGet();
				} finally {
					done.countDown();
				}
			}, "residency-reader-" + t);
			reader.start();
		}
		for (int i = 0; i < 2000; i++) {
			ChunkResidency.markResident(world, i, 0);
			if (i % 3 == 0) {
				ChunkResidency.markUnresident(world, i - 1, 0);
			}
		}
		stop.set(true);
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertEquals(0, failures.get(), "snapshot readers never observe corruption");
		assertTrue(ChunkResidency.residentCount(world) > 0);
	}
}
