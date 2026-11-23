
package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandle;
import com.palordersoftworks.fabricfolia.api.ValidationMode;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySchedulerLifecycleTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.clear();
	}

	private static WorldRegionizer newRegionizer() {
		return new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	private static RegionScheduler newEngine(WorldRegionizer rz, int workers) {
		return new RegionScheduler(rz, workers, ValidationMode.OFF, region -> { }, message -> { });
	}

	private static Region currentRegion() {
		return ThreadOwnership.currentRegion().map(r -> (Region) r).orElse(null);
	}

	@Test
	void runDelayedFiresRetiredWhenOwnerDiedAtScheduleTime() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		try (RegionScheduler engine = newEngine(rz, 1)) {
			AtomicBoolean killed = new AtomicBoolean(false);
			EntitySchedulerImpl scheduler = new EntitySchedulerImpl(engine, handle -> {
				if (killed.compareAndSet(false, true)) {
					rz.removeChunk(0, 0);
					assertTrue(rz.tryBeginTick(region));
					rz.completeTick(region, 1);
				}
				return region;
			});

			AtomicBoolean taskRan = new AtomicBoolean(false);
			AtomicInteger retiredRuns = new AtomicInteger();
			scheduler.runDelayed(new Object(), 5, () -> taskRan.set(true), retiredRuns::incrementAndGet);

			assertTrue(region.isDead());
			assertEquals(1, retiredRuns.get(),
					"the owner died before the delayed task was accepted: retired must fire exactly once");
			assertFalse(taskRan.get(), "the task must not run in a dead region");
		}
	}

	@Test
	void fixedRateStopsWhenInitialScheduleIsRejected() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		try (RegionScheduler engine = newEngine(rz, 1)) {
			AtomicBoolean killed = new AtomicBoolean(false);
			EntitySchedulerImpl scheduler = new EntitySchedulerImpl(engine, handle -> {
				if (killed.compareAndSet(false, true)) {
					rz.removeChunk(0, 0);
					assertTrue(rz.tryBeginTick(region));
					rz.completeTick(region, 1);
				}
				return region;
			});

			AtomicInteger taskRuns = new AtomicInteger();
			AtomicInteger retiredRuns = new AtomicInteger();
			CancelHandle handle = scheduler.runAtFixedRate(new Object(), 5, 1, () -> {
				taskRuns.incrementAndGet();
				throw new AssertionError("rejected chain must never run its body");
			}, retiredRuns::incrementAndGet);

			assertNotNull(handle);
			assertEquals(1, retiredRuns.get(), "retired must fire exactly once for the rejected chain");
			assertEquals(0, taskRuns.get());
			assertFalse(((com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandleView) handle)
					.isCancelled(), "the chain ended by rejection, not by cancellation");
		}
	}

	@Test
	void runDelayedStillRunsWhenTheOwnerSurvives() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		try (RegionScheduler engine = newEngine(rz, 2)) {
			EntitySchedulerImpl scheduler = new EntitySchedulerImpl(engine, handle -> region);
			engine.start();

			CountDownLatch ran = new CountDownLatch(1);
			AtomicInteger retiredRuns = new AtomicInteger();
			scheduler.runDelayed(new Object(), 1, ran::countDown, retiredRuns::incrementAndGet);

			assertTrue(ran.await(10, TimeUnit.SECONDS), "a delayed task on a live owner must still run");
			assertEquals(0, retiredRuns.get(), "a live owner must not retire its task");
		}
	}

	@Test
	void acceptedTaskRetiresOnceWhenItsRegionDiesBeforeExecution() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		try (RegionScheduler engine = newEngine(rz, 1)) {
			EntitySchedulerImpl scheduler = new EntitySchedulerImpl(engine,
					handle -> region.isDead() ? null : region);
			AtomicInteger retiredRuns = new AtomicInteger();
			AtomicBoolean taskRan = new AtomicBoolean(false);
			scheduler.run(new Object(), () -> taskRan.set(true), retiredRuns::incrementAndGet);
			assertEquals(1, engine.queueOf(region).size(), "the task must have been accepted");

			rz.removeChunk(0, 0);
			assertTrue(rz.tryBeginTick(region));
			rz.completeTick(region, 1);
			assertTrue(region.isDead(), "emptying the region must kill it");

			assertEquals(1, retiredRuns.get(),
					"a task dropped with its dead region must retire exactly once, never vanish");
			assertFalse(taskRan.get(), "the task must not run in a dead region");
		}
	}

	@Test
	void droppedTaskIsRecoveredIntoTheEntitysProvenLiveOwner() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0);
		try (RegionScheduler engine = newEngine(rz, 2)) {
			AtomicReference<Region> owner = new AtomicReference<>(a);
			EntitySchedulerImpl scheduler = new EntitySchedulerImpl(engine, handle -> {
				Region current = owner.get();
				return current.isDead() ? null : current;
			});
			AtomicInteger retiredRuns = new AtomicInteger();
			CountDownLatch ran = new CountDownLatch(1);
			AtomicReference<Region> executedIn = new AtomicReference<>();
			scheduler.run(new Object(), () -> {
				executedIn.set(currentRegion());
				ran.countDown();
			}, retiredRuns::incrementAndGet);

			owner.set(b);
			rz.removeChunk(0, 0);
			assertTrue(rz.tryBeginTick(a));
			rz.completeTick(a, 1);
			assertTrue(a.isDead(), "emptying the region must kill it");

			engine.start();
			assertTrue(ran.await(10, TimeUnit.SECONDS),
					"the dropped task must be recovered into the entity's live owner");
			assertSame(b, executedIn.get(), "the recovered task must run in the proven owner's context");
			assertEquals(0, retiredRuns.get(), "a live owner must not retire the recovered task");
		}
	}

	@Test
	void fixedRateKeepsRunningForALiveOwner() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		try (RegionScheduler engine = newEngine(rz, 2)) {
			EntitySchedulerImpl scheduler = new EntitySchedulerImpl(engine, handle -> region);
			engine.start();

			CountDownLatch runs = new CountDownLatch(3);
			AtomicInteger retiredRuns = new AtomicInteger();
			CancelHandle handle = scheduler.runAtFixedRate(new Object(), 1, 1, runs::countDown,
					retiredRuns::incrementAndGet);

			assertTrue(runs.await(10, TimeUnit.SECONDS), "the fixed-rate chain must keep running");
			assertEquals(0, retiredRuns.get());
			assertFalse(((com.palordersoftworks.fabricfolia.api.RegionScheduler.CancelHandleView) handle)
					.isCancelled());
		}
	}
}
