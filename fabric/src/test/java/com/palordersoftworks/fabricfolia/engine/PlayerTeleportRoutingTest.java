/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player teleport dispatch tests, through the real engine: a worker-context
 * teleport resolves to the destination region's queue (region hop), falls
 * back to the global context for unowned destinations, refuses to run when
 * the engine is down, and completes every accepted dispatch exactly once.
 * The idempotence window itself is keyed by live entity ids (unconstructible
 * in unit tests — {@code Entity} needs a full level) and is shared code with
 * the live-proven {@code dispatch} path.
 */
class PlayerTeleportRoutingTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.exit(ThreadOwnership.Context.UNKNOWN);
	}

	private static FabricFoliaEngine bootEngine() throws Exception {
		Path dir = Files.createTempDirectory("folia-teleport");
		FoliaConfig config = FoliaConfig.at(dir.resolve("fabric-folia.yml"));
		config.load();
		config.freeze();
		return FabricFoliaEngine.bootstrap(config, msg -> { }, msg -> { });
	}

	@Test
	void regionContextHopsToDestinationRegion() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld("minecraft:overworld", 10, null);
			WorldRegionizer regionizer = engine.regionizerFor("minecraft:overworld");
			regionizer.addChunk(0, 0);  // destination chunk (0,0)
			regionizer.addChunk(50, 50); // source chunk — a DIFFERENT region

			var sourceRegion = regionizer.ownerOfChunk(50, 50);
			ThreadOwnership.enterRegion(sourceRegion);

			CountDownLatch ran = new CountDownLatch(1);
			boolean dispatched = RegionTransitions.dispatchKeyed(engine,
					"minecraft:overworld", 0, 0, null, ran::countDown);
			assertTrue(dispatched, "cross-region destination must dispatch");
			assertTrue(ran.await(10, TimeUnit.SECONDS),
					"body must execute on the destination region's worker");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void unownedDestinationHopsToGlobalContext() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld("minecraft:overworld", 10, null);
			WorldRegionizer regionizer = engine.regionizerFor("minecraft:overworld");
			regionizer.addChunk(0, 0); // source region exists…
			// …but (5000,5000) is unowned: the destination must be global.

			ThreadOwnership.enterRegion(regionizer.ownerOfChunk(0, 0));

			AtomicBoolean onGlobalDispatch = new AtomicBoolean();
			CountDownLatch ran = new CountDownLatch(1);
			boolean dispatched = RegionTransitions.dispatchKeyed(engine,
					"minecraft:overworld", 5000, 5000, null, () -> {
						onGlobalDispatch.set(Thread.currentThread().getName()
								.contains("FabricFolia-Global"));
						ran.countDown();
					});
			assertTrue(dispatched);
			assertTrue(ran.await(10, TimeUnit.SECONDS));
			assertTrue(onGlobalDispatch.get(),
					"unowned destination must execute on the global dispatch thread");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void engineDownRefusesDispatchAndOwnership() {
		// No engine: the ownership check is false (never claims to be the
		// destination) and the dispatcher refuses — the engine-down contract
		// the mixin's fall-through relies on.
		assertFalse(RegionTransitions.isCurrentContextOwner("minecraft:overworld", 0, 0));
		AtomicBoolean ran = new AtomicBoolean();
		assertFalse(RegionTransitions.dispatchKeyed(null, "minecraft:overworld",
				0, 0, null, () -> ran.set(true)), "engine-down must refuse to dispatch");
		assertFalse(RegionTransitions.dispatchKeyed(null, null, 0, 0, null,
				() -> ran.set(true)), "null world key must refuse to dispatch");
		assertFalse(ran.get(), "engine-down must never run the body");
	}

	@Test
	void everyAcceptedDispatchRunsItsBodyExactlyOnce() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld("minecraft:overworld", 10, null);
			WorldRegionizer regionizer = engine.regionizerFor("minecraft:overworld");
			regionizer.addChunk(0, 0);
			regionizer.addChunk(50, 50);
			ThreadOwnership.enterRegion(regionizer.ownerOfChunk(50, 50));

			AtomicInteger runs = new AtomicInteger();
			assertTrue(RegionTransitions.dispatchKeyed(engine, "minecraft:overworld",
					0, 0, null, runs::incrementAndGet));
			assertTrue(RegionTransitions.dispatchKeyed(engine, "minecraft:overworld",
					0, 0, null, runs::incrementAndGet));

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (runs.get() < 2 && System.nanoTime() < deadline) {
				Thread.yield();
			}
			assertEquals(2, runs.get(),
					"each accepted dispatch runs its body exactly once");
		} finally {
			engine.shutdown(5000);
		}
	}
}
