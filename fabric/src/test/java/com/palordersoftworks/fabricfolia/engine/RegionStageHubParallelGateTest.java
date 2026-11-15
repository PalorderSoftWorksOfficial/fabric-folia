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
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parallel-headroom dispatch gate ({@code MIN_PARALLEL_BODIES}): a flush
 * only ships bodies to region workers when cross-region concurrency can pay
 * for the hop. Single-region (or overwhelmingly dominant-region) batches run
 * inline at flush — measured regression 2026-10-06: unconditional dispatch of
 * a 1500-entity single-region clump cost throughput (10.9 TPS staged vs
 * 18.5 TPS un-staged), latched the backlog gate, and burned 6.4 s/min in
 * quiesce waits.
 */
class RegionStageHubParallelGateTest {

	private static final int MIN_PARALLEL_DEFAULT = 32;
	private static final int MIN_DISPATCH_DEFAULT = 64;
	private static final int BODIES_PER_TASK_DEFAULT = 32;

	@AfterEach
	void cleanup() {
		ThreadOwnership.clear();
		RegionStageHub.MIN_PARALLEL_BODIES = MIN_PARALLEL_DEFAULT;
		RegionStageHub.MIN_DISPATCH_TOTAL = MIN_DISPATCH_DEFAULT;
		RegionStageHub.BODIES_PER_TASK = BODIES_PER_TASK_DEFAULT;
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
	void singleRegionBatchRunsInlineAtFlushWithoutDispatch() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });

		ChunkPos chunk = new ChunkPos(0, 0);
		AtomicInteger runs = new AtomicInteger();
		long inlineBefore = RegionStageHub.inlineNoParallel();
		for (int i = 0; i < 40; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
		}
		hub.flushStaged();

		assertEquals(40, runs.get(), "a single-region batch must run inline at flush");
		assertEquals(0, hub.pendingBodies(), "inline execution must not create backlog");
		assertEquals(inlineBefore + 40, RegionStageHub.inlineNoParallel(),
				"every inline-at-flush body must be counted");
		assertFalse(hub.stagingSuppressed(), "no backlog means no suppression");
		assertEquals(40, metrics.value(RegionMetrics.Counter.ENTITY_TICKS_EXECUTED),
				"inline-at-flush bodies must still tick their slice metric");
		scheduler.close();
	}

	@Test
	void batchWithParallelHeadroomDispatchesToRegionQueues() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		rz.addChunk(512, 512);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });

		ChunkPos near = new ChunkPos(0, 0);
		ChunkPos far = new ChunkPos(512, 512);
		AtomicInteger runs = new AtomicInteger();
		long inlineBefore = RegionStageHub.inlineNoParallel();
		for (int i = 0; i < 40; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(near, runs)));
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(far, runs)));
		}
		hub.flushStaged();

		assertEquals(80, hub.pendingBodies(),
				"balanced two-region batches must dispatch to region queues");
		assertEquals(inlineBefore, RegionStageHub.inlineNoParallel(),
				"dispatched bodies must not be counted inline");
		assertEquals(0, runs.get(), "dispatched bodies must not also run at flush");
		scheduler.close();
	}

	@Test
	void dominantRegionBatchStaysInline() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		rz.addChunk(512, 512);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });

		ChunkPos near = new ChunkPos(0, 0);
		ChunkPos far = new ChunkPos(512, 512);
		AtomicInteger runs = new AtomicInteger();
		long inlineBefore = RegionStageHub.inlineNoParallel();
		for (int i = 0; i < 40; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(near, runs)));
		}
		assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(far, runs)));
		hub.flushStaged();

		// total(41) - largest(40) = 1 < 32: concurrency could save at most
		// one body's time — not worth the dispatch round trip.
		assertEquals(41, runs.get(), "a dominant-region batch must run inline at flush");
		assertEquals(0, hub.pendingBodies(), "inline execution must not create backlog");
		assertEquals(inlineBefore + 41, RegionStageHub.inlineNoParallel(),
				"the whole batch must be counted inline, not just the minority region");
		scheduler.close();
	}

	@Test
	void largeSingleRegionBatchDispatchesForIsolation() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });

		ChunkPos chunk = new ChunkPos(0, 0);
		AtomicInteger runs = new AtomicInteger();
		for (int i = 0; i < 100; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
		}
		hub.flushStaged();

		// total >= MIN_DISPATCH_TOTAL: a hot single region is isolated
		// onto its own worker rather than paid inline.
		assertEquals(100, hub.pendingBodies(),
				"a large single-region batch must dispatch for isolation");
		assertEquals(0, runs.get(), "dispatched bodies must not also run at flush");
		scheduler.close();
	}

	@Test
	void batchIsChunkedIntoBoundedQueueTasks() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		Region region = rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });

		ChunkPos chunk = new ChunkPos(0, 0);
		AtomicInteger runs = new AtomicInteger();
		for (int i = 0; i < 600; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
		}
		hub.flushStaged();

		assertEquals(600, hub.pendingBodies(), "chunking must not change pending accounting");
		assertEquals((600 + BODIES_PER_TASK_DEFAULT - 1) / BODIES_PER_TASK_DEFAULT,
				scheduler.queueOf(region).size(),
				"600 bodies must chunk into exactly ceil(600/BODIES_PER_TASK) queue tasks");
		scheduler.close();
	}


	@Test
	void queuedBatchesArePartitionedByChunkOnRegionSplit() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world",
				new RegionizerConfig(2, 1, 2, 4, 40),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		for (int x = 0; x <= 19; x++) {
			rz.addChunk(x, 0);
		}
		Region parent = rz.ownerOfChunk(0, 0);
		assertEquals(parent, rz.ownerOfChunk(19, 0));
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler,
				new RegionMetrics(1), message -> { });
		RegionStageHub.MIN_PARALLEL_BODIES = 0;
		RegionStageHub.MIN_DISPATCH_TOTAL = 0;
		ChunkPos left = new ChunkPos(0, 0);
		ChunkPos right = new ChunkPos(19, 0);
		AtomicInteger runs = new AtomicInteger();
		for (int i = 0; i < 32; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(left, runs)));
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(right, runs)));
		}
		hub.flushStaged();
		assertEquals(64, hub.pendingBodies());
		assertEquals(2, scheduler.queueOf(parent).size(),
				"one bounded queue entry must be created per target chunk");

		for (int x = 2; x <= 17; x++) {
			rz.removeChunk(x, 0);
		}
		assertTrue(rz.tryBeginTick(parent));
		rz.completeTick(parent, 1);

		Region leftOwner = rz.ownerOfChunk(0, 0);
		Region rightOwner = rz.ownerOfChunk(19, 0);
		assertNotEquals(leftOwner, rightOwner);
		assertEquals(1, scheduler.queueOf(leftOwner).size(),
				"the left chunk's queued batch must move to its split child");
		assertEquals(1, scheduler.queueOf(rightOwner).size(),
				"the right chunk's queued batch must move to its split child");
		assertEquals(2, scheduler.queueOf(leftOwner).size() + scheduler.queueOf(rightOwner).size(),
				"both position-targeted batches must be owned by the post-split regions");
		assertEquals(leftOwner == parent || rightOwner == parent ? 1 : 0, scheduler.queueOf(parent).size(),
				"the original region object may survive as the owner of the largest component");

		scheduler.close();
	}

	@Test
	void backedUpRegionRetainsEveryStagedRound() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		Region region = rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });

		ChunkPos chunk = new ChunkPos(0, 0);
		AtomicInteger runs = new AtomicInteger();
		for (int i = 0; i < 80; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
		}
		hub.flushStaged();
		assertEquals(80, hub.pendingBodies());
		int queuedAfterFirst = scheduler.queueOf(region).size();

		for (int i = 0; i < 80; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
		}
		hub.flushStaged();
		assertEquals(160, hub.pendingBodies(),
				"an overloaded region must retain the next round rather than discard it");
		assertTrue(scheduler.queueOf(region).size() > queuedAfterFirst);
		assertEquals(0, runs.get(), "queued work must not also run inline");

		scheduler.queueOf(region).dropAll();
		hub.flushStaged();
		assertEquals(0, hub.pendingBodies());
		for (int i = 0; i < 80; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
		}
		hub.flushStaged();
		assertEquals(80, hub.pendingBodies());
		scheduler.close();
	}

	@Test
	void playerSliceAlsoRetainsItsQueuedRound() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, message -> { });

		ChunkPos chunk = new ChunkPos(0, 0);
		AtomicInteger runs = new AtomicInteger();
		for (int i = 0; i < 80; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new CountingBody(chunk, runs)));
		}
		hub.flushStaged();
		assertEquals(80, hub.pendingBodies(), "round 1 must dispatch");

		for (int i = 0; i < 80; i++) {
			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.PLAYER, new CountingBody(chunk, runs)));
		}
		hub.flushStaged();
		assertEquals(160, hub.pendingBodies(), "PLAYER bodies must be queued without discarding the ENTITY or PLAYER round");
		scheduler.close();
	}
}