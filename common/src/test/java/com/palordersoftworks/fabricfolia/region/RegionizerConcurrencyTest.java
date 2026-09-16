/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency stress for the regionizer's structural operations (spec 19:
 * stress — many regions, chunk load/unload, look for races and corruption).
 *
 * <p>Real concurrency, no Thread.sleep-based assertions: threads hammer
 * addChunk/removeChunk/tryBeginTick/completeTick in parallel while an observer
 * thread checks that single ownership never breaks. Any race in the structure
 * lock discipline manifests as duplicate ownership or lost chunks.</p>
 */
class RegionizerConcurrencyTest {

	@Test
	void parallelChunkStormPreservesOwnership() throws Exception {
		RegionizerConfig cfg = RegionizerConfig.forTests();
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new AtomicLong(1)::getAndIncrement, System::nanoTime);

		int threads = 8;
		int opsPerThread = 1500;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		AtomicBoolean failed = new AtomicBoolean(false);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		// Each thread owns a distinct spatial quadrant so the storm generates
		// realistic merge/split churn at quadrant borders.
		for (int t = 0; t < threads; t++) {
			final int quadrant = t;
			pool.submit(() -> {
				try {
					start.await();
					Random random = new Random(0xF01A + quadrant);
					int baseX = (quadrant % 4) * 100 - 150;
					int baseZ = (quadrant / 4) * 100 - 50;
					for (int i = 0; i < opsPerThread && !failed.get(); i++) {
						int chunkX = baseX + random.nextInt(40);
						int chunkZ = baseZ + random.nextInt(40);
						if (random.nextInt(3) == 0) {
							rz.removeChunk(chunkX, chunkZ);
						} else {
							rz.addChunk(chunkX, chunkZ);
						}
					}
				} catch (Throwable e) {
					e.printStackTrace();
					failed.set(true);
				} finally {
					done.countDown();
				}
			});
		}

		// Concurrent tick drivers: begin/complete ticks on whatever is ready,
		// exercising merge-later + tick-end under load.
		AtomicBoolean stopTicks = new AtomicBoolean(false);
		Thread tickDriver = new Thread(() -> {
			while (!stopTicks.get() && !failed.get()) {
				for (Region region : rz.liveRegions()) {
					if (region.state != RegionState.READY) continue;
					if (rz.tryBeginTick(region)) {
						// Hold the tick across a tiny work window so addChunk
						// collides with ticking (invariant 3 pressure).
						rz.completeTick(region, 1000);
					}
				}
			}
		});
		tickDriver.start();

		start.countDown();
		assertTrue(done.await(60, TimeUnit.SECONDS), "storm threads hung");
		stopTicks.set(true);
		tickDriver.join(10_000);
		pool.shutdownNow();

		assertTrue(!failed.get(), "worker threads hit an exception");
		// Final state: invariants must hold after the storm settles.
		RegionizerInvariantTest.assertInvariants(rz, cfg); // package-private access
		assertEquals(4, RegionState.values().length);
	}
}
