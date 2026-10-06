package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.metrics.RegionMetrics;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog backpressure and batched dispatch (the OOM regression, 2026-10-05):
 * staging must refuse past the gate limit (caller runs vanilla-inline — never
 * dropped), pending counts must reconcile after drops, and one batch per
 * region must execute every staged body exactly once on a real worker.
 */
class RegionStageHubBacklogTest {

	private static final int FLOOR_DEFAULT = 8192;
	private static final int MULTIPLE_DEFAULT = 2;
	private static final int MIN_PARALLEL_DEFAULT = 32;

	@BeforeEach
	void alwaysDispatchConfig() {
		// These tests pin the always-dispatch configuration (threshold 0):
		// unconditional queue dispatch is the contract under test here; the
		// parallel-headroom gate itself has its own test class.
		RegionStageHub.MIN_PARALLEL_BODIES = 0;
	}

	@AfterEach
	void cleanup() {
		ThreadOwnership.clear();
		RegionStageHub.BACKLOG_FLOOR = FLOOR_DEFAULT;
		RegionStageHub.BACKLOG_MULTIPLE = MULTIPLE_DEFAULT;
		RegionStageHub.MIN_PARALLEL_BODIES = MIN_PARALLEL_DEFAULT;
		ServerThreadDeferral.clearAll();
		ChunkResidency.clearAll();
	}

	private record CountingBody(ChunkPos pos, AtomicInteger runs)
			implements RegionStageHub.Positioned, Runnable {
		@Override
		public ChunkPos fabricfolia$position() {
			return pos;
		}

		@Override
		public void run() {
			runs.incrementAndGet();
		}
	}

	@Test
	void backlogGateSuspendsStagingAndRecoversAfterQueuesDrain() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		Region region = rz.addChunk(0, 0);
		// Scheduler never started: nothing drains, so pending is deterministic.
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });

		// limit = max(FLOOR, MULTIPLE × lastFlush); force a tiny limit.
		RegionStageHub.BACKLOG_FLOOR = 4;
		RegionStageHub.BACKLOG_MULTIPLE = 0;
		long suppressedBefore = RegionStageHub.suppressedBacklog();
		ChunkPos chunk = new ChunkPos(0, 0);
		AtomicInteger runs = new AtomicInteger();

		// Under the limit: staging accepts (5 bodies, limit 4 — over after flush).
		for (int i = 0; i < 5; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)),
					"staging must accept while under the backlog limit");
		}

		// Flush dispatches to the (never-started) scheduler: pending grows.
		hub.flushStaged();
		assertEquals(5, hub.pendingBodies(), "dispatched bodies must count toward the backlog");
		assertTrue(hub.stagingSuppressed(),
				"pending over the limit must suppress staging");
		assertFalse(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)),
				"a suppressed world must refuse staging (caller runs vanilla-inline)");
		assertEquals(suppressedBefore + 1, RegionStageHub.suppressedBacklog(),
				"every refused body must be counted — never silently dropped");
		assertEquals(0, runs.get(), "refused body must NOT have executed anywhere");

		// Queues die empty (region-death/shutdown drop shape): the next flush
		// reconciles the leaked pending count and staging resumes.
		scheduler.queueOf(region).dropAll();
		hub.flushStaged();
		assertEquals(0, hub.pendingBodies(), "idle queues must reconcile the pending gauge to zero");
		assertFalse(hub.stagingSuppressed(), "an empty queue must lift the suppression");
		assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)),
				"staging must resume once the backlog clears");
		hub.flushStaged();
		scheduler.close();
	}

	@Test
	void batchedDispatchExecutesEveryBodyExactlyOnceOnWorker() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });

		// The3x3 neighborhood probe must pass for chunk (0,0) — mark resident.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkResidency.markResident("test:world", dx, dz);
			}
		}

		try {
			scheduler.start();
			int bodies = 200;
			ChunkPos chunk = new ChunkPos(0, 0);
			AtomicInteger runs = new AtomicInteger();
			long executedBefore = metrics.value(RegionMetrics.Counter.ENTITY_TICKS_EXECUTED);
			for (int i = 0; i < bodies; i++) {
				assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
			}
			hub.flushStaged();

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
			while (System.nanoTime() < deadline && runs.get() < bodies) {
				Thread.sleep(10);
			}
			assertEquals(bodies, runs.get(),
					"every staged body must execute exactly once via its region batch");
			assertEquals(executedBefore + bodies,
					metrics.value(RegionMetrics.Counter.ENTITY_TICKS_EXECUTED),
					"slice metric must advance by the batched body count");

			deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline && hub.pendingBodies() > 0) {
				Thread.sleep(10);
			}
			assertEquals(0, hub.pendingBodies(), "batch completion must release the backlog count");
		} finally {
			scheduler.close();
		}
	}
}
