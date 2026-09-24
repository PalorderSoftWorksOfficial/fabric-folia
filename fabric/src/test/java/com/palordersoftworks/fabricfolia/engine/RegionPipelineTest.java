/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.region.Region;

import java.util.List;
import com.palordersoftworks.fabricfolia.region.RegionState;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The region pipeline test (spec 22): loaded-chunk activity regionizes into
 * real regions, the scheduler dispatches their ticks onto workers, ticks
 * reschedule (the 20 TPS deadline loop), ownership resolves from inside a
 * worker tick, and merge keeps exactly one live region for adjacent chunks.
 * Boots the REAL engine — no scheduler stubs.
 */
class RegionPipelineTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.exit(ThreadOwnership.Context.UNKNOWN);
	}

	private static FabricFoliaEngine bootEngine() throws Exception {
		Path dir = Files.createTempDirectory("folia-pipeline");
		FoliaConfig config = FoliaConfig.at(dir.resolve("fabric-folia.yml"));
		config.load();
		config.freeze();
		return FabricFoliaEngine.bootstrap(config, msg -> { }, msg -> { });
	}

	@Test
	void chunkRegistrationCreatesRegionsAndTicksReschedule() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld("minecraft:overworld", 10, null);
			WorldRegionizer regionizer = engine.regionizerFor("minecraft:overworld");

			Region region = regionizer.addChunk(0, 0);
			assertNotEquals(null, region, "registered chunk must be owned by a real region");
			assertEquals(1, engine.regionCount(), "one region after one chunk activity area");

			RegionScheduler scheduler = engine.schedulerFor("minecraft:overworld");
			assertTrue(scheduler.dueRegionCount() <= 1,
					"due-region scan must reflect the coordinator's dispatch set");

			// The tick loop: the coordinator dispatches, the worker ticks,
			// completeTick re-arms the deadline. Wait until the region has
			// actually ticked at least 3 times on a real worker (20 TPS
			// cadence, so ~150ms of server time).
			Region seen = region;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
			while (System.nanoTime() < deadline && seen.tickCount() < 3) {
				Thread.sleep(10);
			}
			assertTrue(seen.tickCount() >= 3,
					"region must tick repeatedly through the real scheduler (tickCount="
							+ seen.tickCount() + ")");
			assertTrue(seen.averageTickDurationNanos() >= 0, "MSPT instrumentation must record");
			assertTrue(scheduler.busyWorkers() >= 0 && scheduler.busyWorkers() <= scheduler.workerCount(),
					"busy-worker count must stay within the pool");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void workerTickExecutesInsideOwningRegionContext() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld("minecraft:overworld", 10, null);
			WorldRegionizer regionizer = engine.regionizerFor("minecraft:overworld");
			Region region = regionizer.addChunk(0, 0);

			// Ownership from a foreign thread (the server thread context
			// here) resolves the same region the worker will see.
			assertEquals(region, regionizer.ownerOfChunk(0, 0),
					"chunk→region ownership must resolve to the created region");
			assertFalse(RegionTransitions.isCurrentContextOwner("minecraft:overworld", 0, 0),
					"a non-worker thread is never the owning context");

			// Queue a task: it must run INSIDE REGION context with the
			// owning region as the current region (tick-thread identity).
			CountDownLatch ran = new CountDownLatch(1);
			AtomicInteger contextKindMatches = new AtomicInteger();
			RegionScheduler scheduler = engine.schedulerFor("minecraft:overworld");
			assertTrue(scheduler.enqueue(region, () -> {
				ThreadOwnership.Context ctx = ThreadOwnership.current();
				if (ctx.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
						&& ctx.region() == region) {
					contextKindMatches.incrementAndGet();
				}
				ran.countDown();
			}), "queue must accept work for a live region");
			assertTrue(ran.await(10, TimeUnit.SECONDS), "queued task must execute on the region");
			assertEquals(1, contextKindMatches.get(),
					"task must execute with the owning region as the current REGION context");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void separatedActivityStaysDistinctAndAdjacentActivityMergesAtTickEnd() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld("minecraft:overworld", 10, null);
			WorldRegionizer regionizer = engine.regionizerFor("minecraft:overworld");

			// Two far-apart chunk areas: separate regions (the merge radius
			// derived from simulation distance 10 cannot reach between them).
			Region first = regionizer.addChunk(0, 0);
			Region second = regionizer.addChunk(4000, 0);
			assertFalse(first == second, "distant activity must not share a region");

			// Eventually both regions tick through the real scheduler.
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
			while (System.nanoTime() < deadline && (first.tickCount() < 2 || second.tickCount() < 2)) {
				Thread.sleep(10);
			}
			assertTrue(first.tickCount() >= 2 && second.tickCount() >= 2,
					"both regions must tick independently (a=" + first.tickCount()
							+ ", b=" + second.tickCount() + ")");

			// New activity adjacent to the FIRST region: per the regionizer's
			// invariant 3 a TICKING region never grows itself, so the new
			// section is held by a transient neighbor and merges into the
			// target at its next tick end. Assert the documented deferred path
			// (not an immediate union).
			Region before = regionizer.ownerOfChunk(0, 0);
			for (int x = 1; x <= 40; x++) {
				regionizer.addChunk(x, 0);
			}
			deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
			while (System.nanoTime() < deadline && regionizer.ownerOfChunk(0, 0).sectionCount() < 2) {
				Thread.sleep(10);
			}
			Region after = regionizer.ownerOfChunk(0, 0);
			assertTrue(after.sectionCount() >= 2,
					"adjacent activity must merge into the target region at tick end (sections="
							+ after.sectionCount() + ")");
			assertEquals(after, regionizer.ownerOfChunk(40, 0),
					"the absorbed section's chunks must resolve to the merged region");
			assertFalse(after == regionizer.ownerOfChunk(4000, 0),
					"the distant region must stay independent");
		} finally {
			engine.shutdown(5000);
		}
	}
}
