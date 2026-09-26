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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transition-body context regression for the ticket-safety contract: vanilla
 * ticket bookkeeping may be mutated from ANY context EXCEPT a region worker
 * (region ticks and staged bodies race the server thread's
 * {@code forEachEntityTickingChunk} enumeration; every other engine context
 * does not tick worlds). The unowned-destination hop runs on the global
 * scheduler — pin that it is NOT a REGION context.
 */
class TransitionBodyServerThreadTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.exit(ThreadOwnership.Context.UNKNOWN);
	}

	private static FabricFoliaEngine bootEngine() throws Exception {
		Path dir = Files.createTempDirectory("folia-transition");
		FoliaConfig config = FoliaConfig.at(dir.resolve("fabric-folia.yml"));
		config.load();
		config.freeze();
		return FabricFoliaEngine.bootstrap(config, msg -> { }, msg -> { });
	}

	@Test
	void unownedDestinationRunsInANonRegionContext() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld("minecraft:overworld", 10, null);
			WorldRegionizer regionizer = engine.regionizerFor("minecraft:overworld");
			regionizer.addChunk(0, 0); // source region exists…
			// …but (5000,5000) is unowned: the destination must be the global
			// scheduler (its dedicated dispatch thread), whose context kind is
			// NOT REGION, and not the server thread — vanilla-state mutations
			// here defer to the server thread through ServerThreadDeferral.

			ThreadOwnership.enterRegion(regionizer.ownerOfChunk(0, 0));

			AtomicReference<String> bodyThreadName = new AtomicReference<>();
			AtomicBoolean bodyContextWasRegion = new AtomicBoolean(true);
			CountDownLatch ran = new CountDownLatch(1);
			boolean dispatched = RegionTransitions.dispatchKeyed(engine,
					"minecraft:overworld", 5000, 5000, null, () -> {
						bodyThreadName.set(Thread.currentThread().getName());
						bodyContextWasRegion.set(ThreadOwnership.current().kind()
								== com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION);
						ran.countDown();
					});
			assertTrue(dispatched);
			assertTrue(ran.await(10, TimeUnit.SECONDS));
			assertFalse(bodyContextWasRegion.get(),
					"unowned-destination bodies must never run in a REGION context "
							+ "(region ticks may not mutate ticket state)");
			assertTrue(bodyThreadName.get().contains("FabricFolia-Global"),
					"the unowned hop runs on the global dispatch thread, observed: "
							+ bodyThreadName.get());
		} finally {
			engine.shutdown(5000);
		}
	}
}
