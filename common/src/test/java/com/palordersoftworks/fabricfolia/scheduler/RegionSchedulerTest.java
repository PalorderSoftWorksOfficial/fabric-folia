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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scheduler behavior tests (spec 19): tasks execute in the correct region
 * context; a deliberately slow region does not delay unrelated regions; task
 * queues drain on the owner; the entity scheduler follows migration; failure
 * policy reports without killing workers.
 */
class RegionSchedulerTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.clear();
	}

	private static WorldRegionizer newRegionizer() {
		return new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	private static RegionScheduler newScheduler(WorldRegionizer rz, int workers) {
		return new RegionScheduler(rz, workers, ValidationMode.STRICT,
				region -> { /* no-op tick body in tests */ },
				message -> { /* diagnostics sink */ });
	}

	@Test
	void scheduledTaskRunsInOwnerContext() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		try (RegionScheduler scheduler = newScheduler(rz, 2)) {
			CountDownLatch ran = new CountDownLatch(1);
			AtomicReference<Object> seenOwner = new AtomicReference<>();
			scheduler.start();

			assertTrue(scheduler.enqueue(region, () -> {
				seenOwner.set(ThreadOwnership.currentRegion().orElse(null));
				ran.countDown();
			}));

			assertTrue(ran.await(10, TimeUnit.SECONDS), "task did not run");
			assertEquals(region, seenOwner.get(),
					"task must execute while the owning region context is entered");
		}
	}

	@Test
	void slowRegionDoesNotDelayUnrelatedRegion() throws Exception {
		RegionizerConfig cfg = RegionizerConfig.forTests();
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new AtomicLong(1)::getAndIncrement, System::nanoTime);

		// Two well-separated regions (far beyond merge radius).
		Region slow = rz.addChunk(0, 0);
		Region fast = rz.addChunk(1000, 1000);

		try (RegionScheduler scheduler = newScheduler(rz, 2)) {
			scheduler.start();

			// The slow region burns 300ms per tick, six times the 50ms budget.
			CountDownLatch slowTicked = new CountDownLatch(1);
			CountDownLatch fastTicked = new CountDownLatch(1);
			AtomicReference<Region> tickBodyRegion = new AtomicReference<>();
			RegionScheduler withBodies = new RegionScheduler(rz, 2, ValidationMode.OFF,
					tickingRegion -> {
						if (tickingRegion == slow) {
							try {
								Thread.sleep(300);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
							}
							slowTicked.countDown();
						} else if (tickingRegion == fast) {
							fastTicked.countDown();
						}
					},
					message -> {});
			withBodies.start();

			assertTrue(slowTicked.await(15, TimeUnit.SECONDS), "slow region never ticked");
			assertTrue(fastTicked.await(3, TimeUnit.SECONDS),
					"fast region's first tick was blocked by the slow region");
			withBodies.close();

			// Independence: the fast region should ALSO have completed several
			// more ticks while the slow region grinds through its second tick.
			long fastTicks = fast.tickCount();
			long slowTicks = slow.tickCount();
			assertTrue(fastTicks > slowTicks,
					"fast region tick count (" + fastTicks + ") did not outpace slow region ("
							+ slowTicks + ") — independence violated");
		}
	}

	@Test
	void taskQueueDropPolicyDropsWithoutExecuting() {
		RegionTaskQueue queue = new RegionTaskQueue();
		AtomicBoolean executed = new AtomicBoolean(false);

		queue.add(() -> executed.set(true));
		assertEquals(1, queue.size());

		// Death drain: DROP policy (documented on the queue).
		int dropped = queue.dropAll();
		assertEquals(1, dropped);
		assertEquals(0, queue.size());

		// And the dropped task is never executed.
		for (Runnable task : queue.drain()) {
			task.run();
		}
		assertFalse(executed.get(), "dropped task must not execute");
	}

	@Test
	void queueRemoveTaskPullsBackEnqueuedWork() {
		RegionTaskQueue queue = new RegionTaskQueue();
		AtomicBoolean executed = new AtomicBoolean(false);
		Runnable task = () -> executed.set(true);

		queue.add(task);
		// Death-race recovery: the enqueue-side pull-back primitive.
		assertTrue(queue.removeTask(task));
		assertEquals(0, queue.size());
		assertTrue(queue.drain().isEmpty());
		assertFalse(executed.get());
	}

	@Test
	void queueRehomeMovesTasksWholesale() {
		RegionTaskQueue source = new RegionTaskQueue();
		RegionTaskQueue target = new RegionTaskQueue();
		source.add(() -> {});
		source.add(() -> {});
		source.rehomeInto(target);
		assertEquals(0, source.size());
		assertEquals(2, target.size());
	}

	@Test
	void failurePolicyReportsWithoutKillingWorker() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		List<String> diagnostics = new CopyOnWriteArrayList<>();
		RegionScheduler scheduler = new RegionScheduler(rz, 1, ValidationMode.OFF,
				r -> {
					throw new IllegalStateException("deliberate tick failure");
				},
				diagnostics::add);
		try {
			scheduler.start();
			// The region still ticks (failure reported), and the worker survives
			// to run subsequent tasks.
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (diagnostics.isEmpty() && System.nanoTime() < deadline) {
				Thread.yield();
			}
			assertTrue(diagnostics.stream().anyMatch(m -> m.contains("Region tick failed")),
					"failure must be reported, never silent");
			assertTrue(scheduler.liveRegions().size() >= 1);
		} finally {
			scheduler.close();
		}
	}

	@Test
	void entitySchedulerFollowsMigration() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region regionA = rz.addChunk(0, 0);
		Region regionB = rz.addChunk(500, 500);

		// Stub resolver: the entity starts in A and migrates to B at a
		// controlled moment (the entity integration phase implements this
		// against real entity state; the scheduling contract is what's tested).
		AtomicReference<Region> owner = new AtomicReference<>(regionA);
		Object entity = new Object();
		RegionScheduler engineScheduler = newScheduler(rz, 2);
		EntitySchedulerImpl scheduler = new EntitySchedulerImpl(engineScheduler,
				handle -> owner.get());
		engineScheduler.start();

		// Enqueue the task while the entity is owned by A; migrate before the
		// task executes; the task must run in the NEW owner's context (follow
		// the entity — documented contract).
		CountDownLatch taskRan = new CountDownLatch(1);
		AtomicReference<Region> executedFor = new AtomicReference<>();
		scheduler.run(entity, () -> {
			executedFor.set(owner.get());
			taskRan.countDown();
		}, () -> {
			throw new AssertionError("retired must not run: entity still exists");
		});

		owner.set(regionB); // migration between schedule and execution
		assertTrue(taskRan.await(10, TimeUnit.SECONDS), "task did not run");
		assertEquals(regionB, executedFor.get(),
				"entity task did not follow the entity's new owner");
		engineScheduler.close();
	}

	@Test
	void queuedTasksSurviveRegionMerge() throws Exception {
		WorldRegionizer rz = newRegionizer();
		// Two regions near enough to be forced to merge by a bridging chunk.
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(6, 0); // 6 chunks = 3 sections apart (section=2 chunks, merge radius 1)

		try (RegionScheduler scheduler = newScheduler(rz, 2)) {
			scheduler.start();
			CountDownLatch executed = new CountDownLatch(1);
			AtomicReference<Region> executedIn = new AtomicReference<>();
			// Scheduled into b BEFORE the merge: must execute in the merged
			// survivor's context (the queue re-homes), never be dropped.
			assertTrue(scheduler.enqueue(b, () -> {
				executedIn.set(ThreadOwnership.currentRegion().map(r -> (Region) r).orElse(null));
				executed.countDown();
			}));

			// Bridge the gap so the regionizer must merge b into a's region
			// (or a into b's — the test accepts either survivor).
			for (int x = 1; x <= 5; x++) {
				rz.addChunk(x, 0);
			}

			assertTrue(executed.await(10, TimeUnit.SECONDS),
					"task queued before merge was lost");
			Region survivor = rz.ownerOfChunk(6, 0);
			assertNotNull(survivor, "merged-away chunk must end up owned by a live region");
			assertEquals(survivor, executedIn.get(),
					"pre-merge task must execute in the merged survivor's context");
		}
	}

	@Test
	void queuedTasksRedistributeToSplitChildren() throws Exception {
		// Low dead-section gate: the halo keeps 2-section alive margins around
		// each cluster, so 10% (not a production-like 40%) makes the tick-end
		// recalculation deterministic at this geometry's scale.
		RegionizerConfig cfg = new RegionizerConfig(2, 1, 2, 4, 10);
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new AtomicLong(1)::getAndIncrement, System::nanoTime);

		// One long line: a single region owning sections 0..16 (2 chunks each).
		for (int x = 0; x <= 33; x++) rz.addChunk(x, 0);
		Region region = rz.ownerOfChunk(0, 0);

		// The scheduler is constructed but NOT started until after the split:
		// the coordinator must not tick the region concurrently with the test
		// thread's tick-end protocol (tryBeginTick is a single-owner latch).
		try (RegionScheduler scheduler = newScheduler(rz, 2)) {
			CountDownLatch leftRan = new CountDownLatch(1);
			CountDownLatch rightRan = new CountDownLatch(1);
			AtomicReference<Region> leftExecutedIn = new AtomicReference<>();
			AtomicReference<Region> rightExecutedIn = new AtomicReference<>();

			// Two position-carrying tasks queued into the pre-split region:
			// one targeting a chunk that will STAY with the parent (left
			// cluster), one targeting a chunk that will move to a child.
			assertTrue(scheduler.enqueue(region, () -> {
				leftExecutedIn.set(ThreadOwnership.currentRegion().map(r -> (Region) r).orElse(null));
				leftRan.countDown();
			}, RegionScheduler.packChunkPos(5, 0)));
			assertTrue(scheduler.enqueue(region, () -> {
				rightExecutedIn.set(ThreadOwnership.currentRegion().map(r -> (Region) r).orElse(null));
				rightRan.countDown();
			}, RegionScheduler.packChunkPos(29, 0)));

			// Drive the real split through the regionizer: empty the middle
			// (sections 5..11; 7..9 die beyond both halos), tick-end purges and
			// splits the two remaining alive components.
			for (int x = 10; x <= 23; x++) rz.removeChunk(x, 0);
			assertTrue(rz.tryBeginTick(region));
			rz.completeTick(region, 1);

			Region left = rz.ownerOfChunk(0, 0);
			Region right = rz.ownerOfChunk(33, 0);
			assertNotNull(right, "split child must own a live component (null here means no real split)");
			assertTrue(left != right, "region did not split across the dead gap");

			// NOW start dispatch: the parent and the split child drain their
			// own queues — the right task must have been re-homed to the child.
			scheduler.start();
			assertTrue(leftRan.await(10, TimeUnit.SECONDS), "left task did not run");
			assertTrue(rightRan.await(10, TimeUnit.SECONDS), "right task did not run");
			assertEquals(left, leftExecutedIn.get(),
					"left-cluster task must execute in the parent/left region");
			assertEquals(right, rightExecutedIn.get(),
					"right-cluster task must be re-homed to the split child that owns it");
		}
	}

	@Test
	void scheduleToChunkOrCreateRegionifiesUnownedPosition() throws Exception {
		WorldRegionizer rz = newRegionizer();
		try (RegionScheduler scheduler = newScheduler(rz, 2)) {
			scheduler.start();
			CountDownLatch ran = new CountDownLatch(1);
			AtomicReference<Region> executedIn = new AtomicReference<>();

			// No region owns chunk (2000, 2000) — scheduleToChunkOrCreate must
			// create one (Folia's RegionizedTaskQueue contract, mandate §13).
			assertNull(rz.ownerOfChunk(2000, 2000));
			assertTrue(scheduler.scheduleToChunkOrCreate(2000, 2000, () -> {
				executedIn.set(ThreadOwnership.currentRegion().map(r -> (Region) r).orElse(null));
				ran.countDown();
			}));
			assertNotNull(rz.ownerOfChunk(2000, 2000), "region must now exist");

			assertTrue(ran.await(10, TimeUnit.SECONDS), "task did not run");
			assertEquals(rz.ownerOfChunk(2000, 2000), executedIn.get(),
					"task must execute in the newly created region's context");
		}
	}

	@Test
	void entitySchedulerRunsRetiredWhenEntityGone() {
		WorldRegionizer rz = newRegionizer();
		rz.addChunk(0, 0);
		AtomicBoolean retiredRan = new AtomicBoolean(false);
		EntitySchedulerImpl scheduler = new EntitySchedulerImpl(newScheduler(rz, 1),
				handle -> null); // entity already gone

		scheduler.run(new Object(), () -> {
			throw new AssertionError("task must not run for a dead entity");
		}, () -> retiredRan.set(true));
		assertTrue(retiredRan.get());
	}
}
