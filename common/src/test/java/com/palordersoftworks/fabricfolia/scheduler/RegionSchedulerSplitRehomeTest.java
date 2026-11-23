
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionSchedulerSplitRehomeTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.clear();
	}

	private static WorldRegionizer newRegionizer() {
		return new WorldRegionizer("test:world", new RegionizerConfig(2, 1, 2, 4, 10),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	private static RegionScheduler newScheduler(WorldRegionizer rz, int workers, List<String> diagnostics) {
		return new RegionScheduler(rz, workers, ValidationMode.STRICT,
				region -> { }, diagnostics::add);
	}

	private static Region split(WorldRegionizer rz) {
		for (int x = 0; x <= 33; x++) {
			rz.addChunk(x, 0);
		}
		Region parent = rz.ownerOfChunk(0, 0);
		for (int x = 10; x <= 23; x++) {
			rz.removeChunk(x, 0);
		}
		assertTrue(rz.tryBeginTick(parent), "the pre-split region must be tickable");
		rz.completeTick(parent, 1);
		return parent;
	}

	private static Region ownerOfLeft(WorldRegionizer rz) {
		return rz.ownerOfChunk(0, 0);
	}

	private static Region ownerOfRight(WorldRegionizer rz) {
		return rz.ownerOfChunk(33, 0);
	}

	private static Region currentRegion() {
		return ThreadOwnership.currentRegion().map(r -> (Region) r).orElse(null);
	}

	@Test
	void positionCarryingTaskQueuedIntoStaleRegionRunsInOwningChild() throws Exception {
		WorldRegionizer rz = newRegionizer();
		List<String> diag = new CopyOnWriteArrayList<>();
		try (RegionScheduler scheduler = newScheduler(rz, 2, diag)) {
			split(rz);
			Region left = ownerOfLeft(rz);
			Region right = ownerOfRight(rz);
			assertNotNull(right, "split must be real");
			assertNotSame(left, right, "split must produce two regions");

			CountDownLatch ran = new CountDownLatch(1);
			AtomicReference<Region> executedIn = new AtomicReference<>();
			AtomicInteger runs = new AtomicInteger();
			assertTrue(scheduler.enqueue(left, () -> {
				runs.incrementAndGet();
				executedIn.set(currentRegion());
				ran.countDown();
			}, RegionScheduler.packChunkPos(29, 0)));

			scheduler.start();
			assertTrue(ran.await(10, TimeUnit.SECONDS), "task did not run");
			assertSame(right, executedIn.get(),
					"a position-carrying entry must execute under the region that owns its chunk");
			assertEquals(1, runs.get(), "the entry must execute exactly once");
			assertEquals(1L, scheduler.tasksRehomedAtExecution());
			assertEquals(0L, scheduler.tasksExecutedWithoutOwner());
		}
	}

	@Test
	void postPartitionEnqueueIntoThePreSplitRegionIsCaughtAtExecution() throws Exception {
		WorldRegionizer rz = newRegionizer();
		List<String> diag = new CopyOnWriteArrayList<>();
		try (RegionScheduler scheduler = newScheduler(rz, 2, diag)) {
			Region parent = split(rz);
			Region left = ownerOfLeft(rz);
			Region right = ownerOfRight(rz);
			assertNotNull(right, "split must be real");
			Region foreignOwner = parent == left ? right : left;
			long foreignOwnedChunk = parent == left
					? RegionScheduler.packChunkPos(29, 0)
					: RegionScheduler.packChunkPos(5, 0);
			assertSame(foreignOwner, rz.ownerOfChunk(RegionScheduler.chunkPosX(foreignOwnedChunk),
					RegionScheduler.chunkPosZ(foreignOwnedChunk)));

			CountDownLatch ran = new CountDownLatch(1);
			AtomicReference<Region> executedIn = new AtomicReference<>();
			assertTrue(scheduler.enqueue(parent, () -> {
				executedIn.set(currentRegion());
				ran.countDown();
			}, foreignOwnedChunk));

			scheduler.start();
			assertTrue(ran.await(10, TimeUnit.SECONDS), "task did not run");
			assertSame(foreignOwner, executedIn.get(),
					"an entry that escaped partitionOnSplit must still run under the chunk's owner");
			assertEquals(1L, scheduler.tasksRehomedAtExecution());
		}
	}

	@Test
	void entryTargetingItsOwnRegionIsNotRehomed() throws Exception {
		WorldRegionizer rz = newRegionizer();
		List<String> diag = new CopyOnWriteArrayList<>();
		try (RegionScheduler scheduler = newScheduler(rz, 2, diag)) {
			split(rz);
			Region left = ownerOfLeft(rz);
			Region right = ownerOfRight(rz);
			assertNotNull(right, "split must be real");

			CountDownLatch ran = new CountDownLatch(1);
			AtomicReference<Region> executedIn = new AtomicReference<>();
			assertTrue(scheduler.enqueue(left, () -> {
				executedIn.set(currentRegion());
				ran.countDown();
			}, RegionScheduler.packChunkPos(5, 0)));

			scheduler.start();
			assertTrue(ran.await(10, TimeUnit.SECONDS), "task did not run");
			assertSame(left, executedIn.get(), "an entry whose chunk the region owns stays put");
			assertEquals(0L, scheduler.tasksRehomedAtExecution());
			assertNotSame(left, right);
		}
	}

	@Test
	void entryWithoutPositionKeepsLastKnownOwnerBehaviour() throws Exception {
		WorldRegionizer rz = newRegionizer();
		List<String> diag = new CopyOnWriteArrayList<>();
		try (RegionScheduler scheduler = newScheduler(rz, 2, diag)) {
			split(rz);
			Region left = ownerOfLeft(rz);
			assertNotNull(ownerOfRight(rz), "split must be real");

			CountDownLatch ran = new CountDownLatch(1);
			AtomicReference<Region> executedIn = new AtomicReference<>();
			assertTrue(scheduler.enqueue(left, () -> {
				executedIn.set(currentRegion());
				ran.countDown();
			}));

			scheduler.start();
			assertTrue(ran.await(10, TimeUnit.SECONDS), "task did not run");
			assertSame(left, executedIn.get());
			assertEquals(0L, scheduler.tasksRehomedAtExecution());
			assertEquals(0L, scheduler.tasksExecutedWithoutOwner());
		}
	}

	@Test
	void entryWhoseChunkHasNoOwnerRunsWhereItWasQueuedAndIsCounted() throws Exception {
		WorldRegionizer rz = newRegionizer();
		List<String> diag = new CopyOnWriteArrayList<>();
		try (RegionScheduler scheduler = newScheduler(rz, 2, diag)) {
			split(rz);
			Region left = ownerOfLeft(rz);
			assertNotNull(ownerOfRight(rz), "split must be real");
			assertEquals(null, rz.ownerOfChunk(16, 0));

			CountDownLatch ran = new CountDownLatch(1);
			AtomicReference<Region> executedIn = new AtomicReference<>();
			assertTrue(scheduler.enqueue(left, () -> {
				executedIn.set(currentRegion());
				ran.countDown();
			}, RegionScheduler.packChunkPos(16, 0)));

			scheduler.start();
			assertTrue(ran.await(10, TimeUnit.SECONDS), "an unowned position must still run, never be dropped");
			assertSame(left, executedIn.get());
			assertEquals(1L, scheduler.tasksExecutedWithoutOwner());
			assertEquals(0L, scheduler.tasksRehomedAtExecution());
			assertTrue(String.join("\n", diag).contains("had no live owner"),
					"unowned execution must be visible in diagnostics");
		}
	}

	@Test
	void rehomedEntriesKeepTheirRelativeOrder() throws Exception {
		WorldRegionizer rz = newRegionizer();
		List<String> diag = new CopyOnWriteArrayList<>();
		try (RegionScheduler scheduler = newScheduler(rz, 2, diag)) {
			split(rz);
			Region left = ownerOfLeft(rz);
			Region right = ownerOfRight(rz);
			assertNotNull(right, "split must be real");

			CountDownLatch both = new CountDownLatch(2);
			List<String> order = new CopyOnWriteArrayList<>();
			assertTrue(scheduler.enqueue(left, () -> {
				order.add("first");
				both.countDown();
			}, RegionScheduler.packChunkPos(26, 0)));
			assertTrue(scheduler.enqueue(left, () -> {
				order.add("second");
				both.countDown();
			}, RegionScheduler.packChunkPos(28, 0)));

			scheduler.start();
			assertTrue(both.await(10, TimeUnit.SECONDS), "both tasks must run");
			assertEquals(List.of("first", "second"), order);
			assertEquals(2L, scheduler.tasksRehomedAtExecution());
			assertSame(right, rz.ownerOfChunk(26, 0));
		}
	}

	@Test
	void staleOwnerAtRehomeIsReResolvedInsteadOfStrandingTheEntry() throws Exception {
		WorldRegionizer rz = newRegionizer();
		List<String> diag = new CopyOnWriteArrayList<>();
		try (RegionScheduler scheduler = newScheduler(rz, 1, diag)) {
			Region holder = rz.addChunk(300, 300);
			Region stale = rz.addChunk(0, 0);
			Region survivor = rz.addChunk(60, 0);
			assertNotSame(stale, survivor, "test geometry needs two distinct regions");
			rz.removeChunk(0, 0);
			assertTrue(rz.tryBeginTick(stale));
			rz.completeTick(stale, 1);
			assertTrue(stale.isDead(), "the stale owner must actually be gone");

			RegionTaskQueue.Entry entry = new RegionTaskQueue.Entry(() -> { },
					RegionScheduler.packChunkPos(60, 0));
			assertTrue(scheduler.rehomeToOwner(holder, stale, entry),
					"a stale owner must resolve to the live survivor");
			assertNull(scheduler.queueOf(stale), "no queue may be registered for a dead region");
			assertEquals(1, scheduler.queueOf(survivor).size(),
					"the entry must land in the survivor's queue exactly once");
			assertEquals(1L, scheduler.tasksRehomedAtExecution());
		}
	}

	@Test
	void rehomeWithNoLiveOwnerNeverRegistersAQueueForTheDeadRegion() throws Exception {
		WorldRegionizer rz = newRegionizer();
		List<String> diag = new CopyOnWriteArrayList<>();
		try (RegionScheduler scheduler = newScheduler(rz, 1, diag)) {
			Region holder = rz.addChunk(300, 300);
			Region dead = rz.addChunk(0, 0);
			rz.removeChunk(0, 0);
			assertTrue(rz.tryBeginTick(dead));
			rz.completeTick(dead, 1);
			assertTrue(dead.isDead(), "emptying the region must kill it");
			assertNull(scheduler.queueOf(dead));

			RegionTaskQueue.Entry entry = new RegionTaskQueue.Entry(() -> { },
					RegionScheduler.packChunkPos(0, 0));
			assertFalse(scheduler.rehomeToOwner(holder, dead, entry));
			assertNull(scheduler.queueOf(dead), "a dead region must never gain a queue");
			assertEquals(0L, scheduler.tasksRehomedAtExecution());
		}
	}

	@Test
	void rehomedEntryIsNotExecutedTwiceWhenTheStaleRegionTicksAgain() throws Exception {
		WorldRegionizer rz = newRegionizer();
		List<String> diag = new CopyOnWriteArrayList<>();
		try (RegionScheduler scheduler = newScheduler(rz, 2, diag)) {
			split(rz);
			Region left = ownerOfLeft(rz);
			assertNotNull(ownerOfRight(rz), "split must be real");

			AtomicInteger runs = new AtomicInteger();
			CountDownLatch ran = new CountDownLatch(1);
			assertTrue(scheduler.enqueue(left, () -> {
				runs.incrementAndGet();
				ran.countDown();
			}, RegionScheduler.packChunkPos(31, 0)));
			assertEquals(1, scheduler.queueOf(left).size());

			scheduler.start();
			assertTrue(ran.await(10, TimeUnit.SECONDS), "task did not run");
			assertEquals(1, runs.get());
			assertEquals(1L, scheduler.tasksRehomedAtExecution());
			assertEquals(0, scheduler.queueOf(left).size(),
					"the stale copy must leave the old region's queue, not linger for a later tick");
			assertFalse(left.isDead());
		}
	}
}
