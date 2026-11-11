package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.metrics.RegionMetrics;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tick-scoped chunk caches in {@link RegionStageHub}: the 3x3 loadedness probe
 * and the ownership resolve run per entity (12k entities in ~20 chunks on the
 * stress world; JFR 2026-11-10: ~30% of server-thread runnable CPU), so both
 * answers are cached per chunk for the tick. The cache must never leak across
 * the flush boundary, and the execution-time re-check stays the net for
 * mid-tick unloads.
 */
class RegionStageHubTickCacheTest {

	@AfterEach
	void cleanup() {
		ThreadOwnership.clear();
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

	private RegionStageHub hub(WorldRegionizer rz, RegionScheduler scheduler, RegionMetrics metrics) {
		return new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });
	}

	private static void markNeighborhood(String world, int cx, int cz) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkResidency.markResident(world, cx + dx, cz + dz);
			}
		}
	}

	@Test
	void loadednessAnswerIsCachedWithinTheTickAndFreshAfterFlush() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = hub(rz, scheduler, metrics);
		ChunkPos chunk = new ChunkPos(0, 0);

		markNeighborhood("test:world", 0, 0);
		assertTrue(hub.vanillaNeighborhoodLoaded(chunk), "resident neighborhood must probe true");
		long hitsBefore = RegionStageHub.tickProbeCacheHits();

		ChunkResidency.markUnresident("test:world", 0, 0);
		assertTrue(hub.vanillaNeighborhoodLoaded(chunk),
				"within a tick the cached answer wins over a mid-tick unload (execution-time re-check stays the net)");
		assertEquals(hitsBefore + 1, RegionStageHub.tickProbeCacheHits(),
				"the repeated probe must be served from the tick cache");

		hub.flushStaged();
		assertFalse(hub.vanillaNeighborhoodLoaded(chunk),
				"after the flush boundary the probe must resolve fresh");
		scheduler.close();
	}

	@Test
	void sameChunkRunsGroupWithoutPerBodyMapLookups() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		rz.addChunk(0, 1);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = hub(rz, scheduler, metrics);
		ChunkPos first = new ChunkPos(0, 0);
		ChunkPos second = new ChunkPos(0, 1);
		AtomicInteger runs = new AtomicInteger();
		long hitsBefore = RegionStageHub.dispatchRunHits();
		for (int i = 0; i < 40; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(first, runs)));
		}
		for (int i = 0; i < 40; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(second, runs)));
		}
		hub.flushStaged();

		assertEquals(80, hub.pendingBodies(),
				"two-chunk batches must dispatch to region queues, never inline");
		// 40 + 40 consecutive same-chunk bodies = 78 run-length hits (the
		// first body of each run resolves normally).
		assertEquals(hitsBefore + 78, RegionStageHub.dispatchRunHits(),
				"consecutive same-chunk bodies must skip the per-body map lookups");
		scheduler.close();
	}

	@Test
	void flushBoundaryClearsTheOwnerCache() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = hub(rz, scheduler, metrics);
		ChunkPos chunk = new ChunkPos(0, 0);
		AtomicInteger runs = new AtomicInteger();

		assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
		hub.flushStaged();
		assertEquals(1, runs.get(), "single-region batch runs inline at flush");

		rz.removeChunk(0, 0);
		assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
		hub.flushStaged();
		assertEquals(2, runs.get(),
				"the second flush must re-resolve ownership (stale cache would dispatch at a dead region)");
		assertEquals(0, hub.pendingBodies(), "unowned bodies run inline, never queue");
		scheduler.close();
	}
}
