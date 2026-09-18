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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-dispatch routing tests (mandate 28), driven through the real
 * engine: non-network contexts run inline; network-context work hops to the
 * owning region when one exists and to the global context otherwise;
 * engine-down drops (never inline on the event loop).
 */
class NetworkDispatchTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.exit(ThreadOwnership.Context.UNKNOWN);
	}

	private static FabricFoliaEngine bootEngine() throws Exception {
		Path dir = Files.createTempDirectory("folia-nettest");
		FoliaConfig config = FoliaConfig.at(dir.resolve("fabric-folia.yml"));
		config.load();
		config.freeze();
		return FabricFoliaEngine.bootstrap(config, msg -> { }, msg -> { });
	}

	@Test
	void nonNetworkContextRunsInline() {
		AtomicBoolean ran = new AtomicBoolean();
		// Current thread is UNKNOWN (not network): inline execution, no hop.
		NetworkDispatch.runOnOwner(null, null, 0, 0, () -> ran.set(true));
		assertTrue(ran.get(), "non-network caller executes inline");
	}

	@Test
	void networkContextHopsToOwningRegion() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld("minecraft:overworld", 10, null);
			WorldRegionizer regionizer = engine.regionizerFor("minecraft:overworld");
			assertNotNull(regionizer, "attached world must expose its regionizer");
			regionizer.addChunk(0, 0); // own the target chunk

			ThreadOwnership.enterSide(com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.NETWORK);
			assertTrue(NetworkDispatch.isNetworkContext());

			CountDownLatch ran = new CountDownLatch(1);
			NetworkDispatch.runOnOwner(engine, "minecraft:overworld", 0, 0, () -> ran.countDown());
			assertTrue(ran.await(10, TimeUnit.SECONDS),
					"network-origin task must execute on the owning region's worker");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void networkContextWithUnownedPositionHopsToGlobal() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld("minecraft:overworld", 10, null);

			ThreadOwnership.enterSide(com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.NETWORK);

			// No region owns (5000,5000): the dispatch must fall back to the
			// global scheduler, which executes on its dispatch thread.
			CountDownLatch ran = new CountDownLatch(1);
			NetworkDispatch.runOnOwner(engine, "minecraft:overworld", 5000, 5000, () -> ran.countDown());
			assertTrue(ran.await(10, TimeUnit.SECONDS),
					"unowned-position network work must hop to the global context");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void engineDownDropsNetworkOriginWork() {
		ThreadOwnership.enterSide(com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.NETWORK);
		AtomicBoolean ran = new AtomicBoolean();
		NetworkDispatch.runOnOwner(null, null, 0, 0, () -> ran.set(true));
		assertFalse(ran.get(),
				"network-origin work with no engine must drop, never run on the event loop");
	}
}
