/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionState;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		CountDownLatch abortDecided = new CountDownLatch(1);
		CountDownLatch queueDropped = new CountDownLatch(1);
		WorldRegionizer rz = newRegionizer();
		Region bad = rz.addChunk(0, 0);

		try (RegionScheduler scheduler = new RegionScheduler(rz, 1,
				ValidationMode.STRICT, region -> { }, message -> {
					diag.add(message);
					if (message.contains("aborting the region")) {
						abortDecided.countDown();
					}
					if (message.contains("died: dropped")) {
						queueDropped.countDown();
					}
				})) {
			scheduler.start();
			int threshold = RegionFailurePolicy.DEFAULT_MAX_CONSECUTIVE_FAILURES;
			CountDownLatch failuresRan = new CountDownLatch(threshold);
			for (int i = 0; i < threshold; i++) {
				final int idx = i;
				scheduler.enqueue(bad, () -> {
					failuresRan.countDown();
					throw new RuntimeException("persist-" + idx);
				});
			}
			AtomicBoolean survivorRan = new AtomicBoolean(false);
			scheduler.enqueue(bad, () -> survivorRan.set(true));

			assertTrue(failuresRan.await(10, TimeUnit.SECONDS), "the failing tasks must run");
			assertTrue(abortDecided.await(10, TimeUnit.SECONDS), "the policy must decide the abort");
			assertTrue(queueDropped.await(10, TimeUnit.SECONDS),
					"the abort must drop the remaining queue with a count");

			String joined = String.join("\n", diag);
			assertTrue(bad.isDead(), "the region must actually be aborted, not just logged: " + joined);
			assertSame(RegionState.DEAD, bad.state());
			assertFalse(rz.liveRegions().contains(bad), "a dead region must leave the live set");
			assertFalse(survivorRan.get(),
					"the drain must stop at the abort — later queued work must not run");
			assertTrue(joined.contains("died: dropped 1 queued task(s)"), joined);
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
			int belowThreshold = RegionFailurePolicy.DEFAULT_MAX_CONSECUTIVE_FAILURES - 1;
			CountDownLatch done = new CountDownLatch((belowThreshold * 2) + 2);
			for (int block = 0; block < 2; block++) {
				for (int i = 0; i < belowThreshold; i++) {
					final int idx = i;
					scheduler.enqueue(region, () -> {
						done.countDown();
						throw new RuntimeException("pulse-" + idx);
					});
				}
				scheduler.enqueue(region, done::countDown);
			}
			assertTrue(done.await(10, TimeUnit.SECONDS),
					"all tasks must run; still pending: " + done.getCount() + " — " + String.join(" | ", diag));

			String joined = String.join("\n", diag);
			assertTrue(joined.contains("pulse-0"), joined);
			assertFalse(joined.contains("aborting the region"),
					"a success between failures must reset the streak: " + joined);
			assertFalse(region.isDead(), "the region must still be live");
			assertSame(RegionState.READY, region.state());
		}
	}

	@Test
	void failurePolicyBookkeepingIsReleasedOnRegionMerge() throws Exception {
		List<String> diag = new CopyOnWriteArrayList<>();
		WorldRegionizer rz = newRegionizer();			Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(20, 0);
		RegionFailurePolicy policy = new RegionFailurePolicy(diag::add, rz::abortTick,
				RegionFailurePolicy.DEFAULT_MAX_CONSECUTIVE_FAILURES);
		RegionScheduler scheduler = new RegionScheduler(rz,
				new WorkerPool(1, "RegionFailurePolicyTest-Worker"), 1,
				ValidationMode.STRICT, r -> { }, diag::add, true, policy);
		try {
			policy.reportTaskFailure(a, "probe-a", new IllegalStateException("a"));
			policy.reportTaskFailure(b, "probe-b", new IllegalStateException("b"));
			String before = String.join("\n", policy.diagnosticsLines());
			assertTrue(before.contains("#" + a.id), before);
			assertTrue(before.contains("#" + b.id), before);

			for (int section = 2; section <= 8; section++) {
				rz.addChunk(section * 2, 0);
			}
			assertSame(rz.ownerOfChunk(0, 0), rz.ownerOfChunk(20, 0),
					"the bridge must merge both clusters into one region");
			assertTrue(a.isDead() || b.isDead(), "a merge must have absorbed one of the two regions");

			String after = String.join("\n", policy.diagnosticsLines());
			for (Region region : List.of(a, b)) {
				if (region.isDead()) {
					assertFalse(after.contains("#" + region.id),
							"a merged-away region's failure bookkeeping must be released: " + after);
				} else {
					assertTrue(after.contains("#" + region.id),
							"the survivor keeps its own streak: " + after);
				}
			}
		} finally {
			scheduler.close();
		}
	}

	@Test
	void failurePolicyBookkeepingIsReleasedOnRegionDeath() throws Exception {
		List<String> diag = new CopyOnWriteArrayList<>();
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(8, 8);
		RegionFailurePolicy policy = new RegionFailurePolicy(diag::add, rz::abortTick,
				RegionFailurePolicy.DEFAULT_MAX_CONSECUTIVE_FAILURES);
		RegionScheduler scheduler = new RegionScheduler(rz,
				new WorkerPool(1, "RegionFailurePolicyTest-Worker"), 1,
				ValidationMode.STRICT,
				r -> {
					throw new IllegalStateException("body failure");
				},
				diag::add, true, policy);
		try {
			scheduler.start();
			CountDownLatch ran = new CountDownLatch(2);
			for (int i = 0; i < 2; i++) {
				final int idx = i;
				scheduler.enqueue(region, () -> {
					ran.countDown();
					throw new RuntimeException("bookkeeping-" + idx);
				});
			}
			assertTrue(ran.await(10, TimeUnit.SECONDS), "the failing tasks must run");

			String before = String.join("\n", policy.diagnosticsLines());
			assertTrue(before.contains("#" + region.id),
					"a live streak must be reported before death: " + before);

			scheduler.close();
			rz.removeChunk(8, 8);
			assertTrue(rz.tryBeginTick(region), "the region must still be tickable after dispatch stopped");
			rz.completeTick(region, 1);

			assertTrue(region.isDead(), "emptying the region must kill it");
			String after = String.join("\n", policy.diagnosticsLines());
			assertFalse(after.contains("#" + region.id),
					"a dead region's failure bookkeeping must be released: " + after);
		} finally {
			scheduler.close();
		}
	}
}
