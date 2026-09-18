/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure-policy behavior tests (spec 33/34), exercised through the
 * scheduler's real catch sites: a malformed task is contained, reported with
 * the mandated diagnostic fields, and isolated from other regions; the
 * Nth consecutive failure aborts the region; a success resets the streak.
 */
class RegionFailurePolicyTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.clear();
	}

	private static WorldRegionizer newRegionizer() {
		return new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	@Test
	void taskFailuresAreContainedAndDiagnosed() throws Exception {
		List<String> diag = new CopyOnWriteArrayList<>();
		WorldRegionizer rz = newRegionizer();
		Region good = rz.addChunk(0, 0);
		Region bad = rz.addChunk(10, 0);

		try (RegionScheduler scheduler = new RegionScheduler(rz, 2,
				ValidationMode.STRICT, region -> { }, diag::add)) {
			scheduler.start();
			CountDownLatch badRan = new CountDownLatch(3);
			CountDownLatch goodRan = new CountDownLatch(1);
			for (int i = 0; i < 3; i++) {
				final int idx = i;
				scheduler.enqueue(bad, () -> {
					badRan.countDown();
					throw new RuntimeException("boom-" + idx);
				});
			}
			// The healthy region must be untouched by its neighbor's failures.
			scheduler.enqueue(good, goodRan::countDown);

			assertTrue(badRan.await(10, TimeUnit.SECONDS), "failing tasks must still execute (and be caught)");
			assertTrue(goodRan.await(10, TimeUnit.SECONDS), "unrelated region must be unaffected");

			// Mandated diagnostic fields: region, world, thread, task, cause.
			String joined = String.join("\n", diag);
			assertTrue(joined.contains("test:world"));
			assertTrue(joined.contains("boom-0"));
			assertTrue(joined.contains("queued task"));
		}
	}

	@Test
	void consecutiveFailuresAbortOnlyTheFailingRegion() throws Exception {
		List<String> diag = new CopyOnWriteArrayList<>();
		WorldRegionizer rz = newRegionizer();
		Region bad = rz.addChunk(0, 0);

		try (RegionScheduler scheduler = new RegionScheduler(rz, 1,
				ValidationMode.STRICT, region -> { }, diag::add)) {
			scheduler.start();
			// Defaults: maxConsecutiveFailures is the policy's configured
			// threshold. Enqueue enough failing tasks to trip it.
			CountDownLatch ran = new CountDownLatch(8);
			for (int i = 0; i < 8; i++) {
				final int idx = i;
				scheduler.enqueue(bad, () -> {
					ran.countDown();
					throw new RuntimeException("persist-" + idx);
				});
			}
			assertTrue(ran.await(10, TimeUnit.SECONDS));

			String joined = String.join("\n", diag);
			assertTrue(joined.contains("aborting the region"),
					"the policy must abort a region that fails every tick: " + joined);
		}
	}

	@Test
	void successResetsTheFailureStreak() throws Exception {
		List<String> diag = new CopyOnWriteArrayList<>();
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(4, 4);

		try (RegionScheduler scheduler = new RegionScheduler(rz, 1,
				ValidationMode.STRICT, r -> { }, diag::add)) {
			scheduler.start();
			// fail, succeed, fail, succeed ... never two consecutive fails.
			CountDownLatch done = new CountDownLatch(6);
			for (int i = 0; i < 3; i++) {
				final int idx = i;
				scheduler.enqueue(region, () -> {
					done.countDown();
					throw new RuntimeException("pulse-" + idx);
				});
				scheduler.enqueue(region, done::countDown);
			}
			assertTrue(done.await(10, TimeUnit.SECONDS));
			String joined = String.join("\n", diag);
			assertTrue(joined.contains("pulse-0"));
			// No abort diagnostic: the streak never reached the threshold.
			assertTrue(!joined.contains("aborting the region"), joined);
		}
	}
}
