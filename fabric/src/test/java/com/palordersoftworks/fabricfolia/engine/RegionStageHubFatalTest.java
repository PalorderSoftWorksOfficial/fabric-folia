package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.metrics.RegionMetrics;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RegionStageHubFatalTest {

	@AfterEach
	void cleanup() {
		ThreadOwnership.clear();
		RegionStageHub.MIN_PARALLEL_BODIES = 32;
		RegionStageHub.MIN_DISPATCH_TOTAL = 64;
		RegionStageHub.REGION_SKIP_THRESHOLD = 3;
		RegionStageHub.BODIES_PER_TASK = 32;
		ServerThreadDeferral.clearAll();
		ChunkResidency.clearAll();
	}

	private record FailingBody(ChunkPos pos, RuntimeException failure, AtomicInteger runs)
			implements RegionStageHub.Positioned, Runnable {
		@Override
		public ChunkPos fabricfolia$position() {
			return pos;
		}

		@Override
		public void run() {
			runs.incrementAndGet();
			if (failure != null) {
				throw failure;
			}
		}
	}

	private record FatalBody(ChunkPos pos, LinkageError fatal) implements RegionStageHub.Positioned, Runnable {
		@Override
		public ChunkPos fabricfolia$position() {
			return pos;
		}

		@Override
		public void run() {
			throw fatal;
		}
	}

	@Test
	void fatalBodyErrorPropagatesThroughInlineFlush() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", com.palordersoftworks.fabricfolia.region.RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		List<String> diagnostics = new ArrayList<>();
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, diagnostics::add);

		ChunkPos chunk = new ChunkPos(0, 0);
		LinkageError fatal = new LinkageError("boom");
		hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new FatalBody(chunk, fatal));
		try {
			hub.flushStaged();
			fail("a fatal body error must propagate, never be swallowed as an isolated tick failure");
		} catch (LinkageError e) {
			assertTrue(e == fatal, "the exact fatal instance must propagate");
		} finally {
			scheduler.close();
		}
	}

	@Test
	void ordinaryBodyFailureStaysIsolatedAndSiblingsRun() throws Exception {
		WorldRegionizer rz = new WorldRegionizer("test:world", com.palordersoftworks.fabricfolia.region.RegionizerConfig.forTests(),
				new AtomicInteger(1)::getAndIncrement, System::nanoTime);
		rz.addChunk(0, 0);
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> { }, message -> { });
		RegionMetrics metrics = new RegionMetrics(1);
		List<String> diagnostics = new ArrayList<>();
		RegionStageHub hub = new RegionStageHub("test:world", rz, scheduler, metrics, diagnostics::add);

		ChunkPos chunk = new ChunkPos(0, 0);
		AtomicInteger runs = new AtomicInteger();
		long isolatedBefore = metrics.value(RegionMetrics.Counter.EXCEPTIONS_ISOLATED);
		hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new FailingBody(chunk, null, runs));
		hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new FailingBody(chunk, new IllegalStateException("boom"), runs));
		hub.stageIfRoom(RegionStageHub.Slice.ENTITY, new FailingBody(chunk, null, runs));
		hub.flushStaged();
		assertEquals(3, runs.get(), "every body runs exactly once even when a sibling fails");
		assertEquals(isolatedBefore + 1, metrics.value(RegionMetrics.Counter.EXCEPTIONS_ISOLATED));
		assertTrue(diagnostics.size() == 1, "the isolated failure must be reported, got: " + diagnostics);
		scheduler.close();
	}
}
