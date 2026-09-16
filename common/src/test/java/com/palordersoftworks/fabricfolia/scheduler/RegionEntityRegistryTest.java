/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.ThreadContextViolationException;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entity migration tests (mandate §15): unique ownership, atomic
 * migration, retire-on-death, retire-on-split-homelessness, region-local
 * entity sets under context enforcement, and the entity scheduler following
 * a migration. Structural transitions are driven through the real
 * regionizer, exactly as production does.
 */
class RegionEntityRegistryTest {

	/** Fake home-chunk resolver: identity map handle → packed chunk pos. */
	private static final class HomeChunks implements java.util.function.Function<Object, Long> {
		final ConcurrentHashMap<Object, Long> homes = new ConcurrentHashMap<>();

		@Override
		public Long apply(Object handle) {
			return homes.get(handle);
		}
	}

	@AfterEach
	void clearContext() {
		ThreadOwnership.clear();
	}

	private static WorldRegionizer newRegionizer() {
		return new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	private static RegionEntityRegistry newRegistry(WorldRegionizer rz, HomeChunks homes) {
		RegionDataHub hub = new RegionDataHub();
		hub.attachTo(rz);
		RegionEntityRegistry registry = new RegionEntityRegistry(rz, hub, homes);
		registry.attach(); // AFTER the hub — the load-bearing listener order
		return registry;
	}

	private static Set<Object> entitySetInContext(RegionEntityRegistry registry, Region region) {
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(region);
		try {
			return registry.perRegionEntities().get(region);
		} finally {
			ThreadOwnership.exit(token);
		}
	}


	@Test
	void registerCreatesUniqueOwnership() {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0); // beyond merge radius: distinct region
		assertTrue(a != b, "test geometry needs two distinct regions");
		RegionEntityRegistry registry = newRegistry(rz, new HomeChunks());

		assertTrue(registry.register("entity-1", a), "first registration succeeds");
		assertFalse(registry.register("entity-1", b), "second registration of the same entity must fail");
		assertSame(a, registry.ownerOfHandle("entity-1"), "owner must stay the first region");

		assertThrows(IllegalArgumentException.class, () -> registry.register(null, a));
		assertThrows(IllegalArgumentException.class, () -> registry.migrate("entity-1", null));
		assertNull(registry.ownerOfHandle("never-registered"));
		assertNull(registry.ownerOfHandle(null), "null handle has no owner, not an exception");
	}

	@Test
	void migrateIsAtomicAndComplete() {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0);
		RegionEntityRegistry registry = newRegistry(rz, new HomeChunks());
		registry.register("entity-1", a);

		assertTrue(registry.migrate("entity-1", b), "migration from a to b must happen");
		assertSame(b, registry.ownerOfHandle("entity-1"));

		// Exactly one region owns the entity — never both, never neither.
		assertTrue(entitySetInContext(registry, a).isEmpty(), "old region must have lost the entity");
		assertTrue(entitySetInContext(registry, b).contains("entity-1"), "new region must have gained it");
		assertEquals(1, registry.trackedCount(), "exactly one authoritative mapping");

		// No-op migration: same region returns false and changes nothing.
		assertFalse(registry.migrate("entity-1", b));
		assertFalse(registry.migrate("ghost", b), "unregistered entity cannot migrate");
		assertEquals(0, registry.migrationsCompleted() - 1, "only the real migration counted");
	}

	@Test
	void unregisterRemovesEverywhere() {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0);
		RegionEntityRegistry registry = newRegistry(rz, new HomeChunks());
		registry.register("entity-1", a);

		assertTrue(registry.unregister("entity-1"));
		assertNull(registry.ownerOfHandle("entity-1"));
		assertTrue(entitySetInContext(registry, a).isEmpty());
		assertFalse(registry.unregister("entity-1"), "double unregister is false");
		assertFalse(registry.migrate("entity-1", b), "unregistered entity cannot migrate");
	}

	@Test
	void regionDeathRetiresEntities() {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		HomeChunks homes = new HomeChunks();
		RegionEntityRegistry registry = newRegistry(rz, homes);
		registry.register("entity-1", region);
		AtomicReference<Object> retired = new AtomicReference<>();
		registry.addRetirementListener(retired::set);

		// Kill the region through the real lifecycle: empty it, tick, purge.
		rz.removeChunk(0, 0);
		assertTrue(rz.tryBeginTick(region));
		rz.completeTick(region, 1);

		assertNull(registry.ownerOfHandle("entity-1"), "entity must be retired when its region dies");
		assertSame("entity-1", retired.get(), "retirement listener must fire with the handle");
		assertEquals(1, registry.retiredCount());
		assertTrue(registry.perRegionEntities().peekForDiagnostics(region) == null
				|| registry.perRegionEntities().peekForDiagnostics(region).isEmpty(),
				"dead region must hold no entity set");
	}

	@Test
	void splitRetargetsEntitiesByHomeChunkAndRetiresHomeless() {
		// Wide dead gap so the split is real: clusters at chunks 0-9 and
		// 24-33, gap 10..23 emptied (sections 7..9 die beyond both halos,
		// tick-end purge clears the 10% gate, components split).
		WorldRegionizer rz = new WorldRegionizer("test:world", new RegionizerConfig(2, 1, 2, 4, 10),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
		HomeChunks homes = new HomeChunks();
		RegionEntityRegistry registry = newRegistry(rz, homes);

		for (int x = 0; x <= 33; x++) rz.addChunk(x, 0);
		Region parent = rz.ownerOfChunk(0, 0);

		// Three entities: one homed left, one homed right, one homeless
		// (its home chunk is in the purged middle).
		registry.register("left", parent);
		registry.register("right", parent);
		registry.register("doomed", parent);
		homes.homes.put("left", RegionScheduler.packChunkPos(2, 0));
		homes.homes.put("right", RegionScheduler.packChunkPos(30, 0));
		homes.homes.put("doomed", RegionScheduler.packChunkPos(16, 0));

		for (int x = 10; x <= 23; x++) rz.removeChunk(x, 0);
		assertTrue(rz.tryBeginTick(parent));
		rz.completeTick(parent, 1);

		Region left = rz.ownerOfChunk(0, 0);
		Region right = rz.ownerOfChunk(33, 0);
		assertNotNull(right, "split must be real");
		assertTrue(left != right, "split must produce two regions");

		assertSame(left, registry.ownerOfHandle("left"), "left entity must follow its home chunk");
		assertSame(right, registry.ownerOfHandle("right"), "right entity must follow its home chunk");
		assertNull(registry.ownerOfHandle("doomed"), "homeless entity must be retired");
		assertEquals(1, registry.retiredCount(), "exactly one retirement");
		assertTrue(entitySetInContext(registry, left).contains("left"));
		assertTrue(entitySetInContext(registry, right).contains("right"));
	}

	@Test
	void perRegionSetEnforcesOwnerContext() {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0);
		RegionEntityRegistry registry = newRegistry(rz, new HomeChunks());
		registry.register("entity-1", a);

		// From B's context, A's entity set is a cross-region violation —
		// never a silent hand-out (mandate §7/§24).
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(b);
		try {
			assertThrows(ThreadContextViolationException.class,
					() -> registry.perRegionEntities().get(a));
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void entitySchedulerFollowsMigration() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0);
		RegionEntityRegistry registry = newRegistry(rz, new HomeChunks());
		registry.register("entity-1", a);

		try (RegionScheduler scheduler = newScheduler(rz, 2)) {
			EntitySchedulerImpl entityScheduler = new EntitySchedulerImpl(scheduler, registry.resolver());
			AtomicReference<Region> executedIn = new AtomicReference<>();
			CountDownLatch ran = new CountDownLatch(1);
			AtomicBoolean retiredRan = new AtomicBoolean(false);

			// Schedule while the entity is owned by A, then migrate it to B
			// BEFORE the task runs (pre-start: deterministic ordering).
			entityScheduler.run("entity-1", () -> {
				executedIn.set(ThreadOwnership.currentRegion().map(r -> (Region) r).orElse(null));
				ran.countDown();
			}, () -> retiredRan.set(true));

			assertTrue(registry.migrate("entity-1", b), "migration before execution must succeed");
			scheduler.start();

			assertTrue(ran.await(10, TimeUnit.SECONDS), "task did not run");
			assertFalse(retiredRan.get(), "a migrated (not removed) entity must not retire its tasks");
			assertSame(b, executedIn.get(), "task must FOLLOW the entity to region B");
		}
	}

	@Test
	void entitySchedulerRetiresWhenEntityUnregistered() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		RegionEntityRegistry registry = newRegistry(rz, new HomeChunks());
		registry.register("entity-1", a);

		try (RegionScheduler scheduler = newScheduler(rz, 1)) {
			EntitySchedulerImpl entityScheduler = new EntitySchedulerImpl(scheduler, registry.resolver());
			CountDownLatch retired = new CountDownLatch(1);
			AtomicBoolean taskRan = new AtomicBoolean(false);

			entityScheduler.run("entity-1", () -> taskRan.set(true), retired::countDown);
			registry.unregister("entity-1"); // entity gone before execution
			scheduler.start();

			assertTrue(retired.await(10, TimeUnit.SECONDS), "retired callback must run");
			assertFalse(taskRan.get(), "the task must NOT run for a removed entity");
		}
	}

	/**
	 * The concurrency storm (mandate §44/§45): many threads migrating many
	 * entities between two regions concurrently. Invariants afterwards: every
	 * entity exactly one authoritative mapping, present in exactly one
	 * region's set, no duplicates, no losses.
	 */
	@Test
	void migrationStormKeepsOwnershipUnique() throws Exception {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0);
		RegionEntityRegistry registry = newRegistry(rz, new HomeChunks());

		final int entities = 200;
		for (int i = 0; i < entities; i++) {
			assertTrue(registry.register("entity-" + i, a));
		}

		final int movers = 8;
		CountDownLatch startGun = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(movers);
		for (int m = 0; m < movers; m++) {
			final int mover = m;
			Thread t = new Thread(() -> {
				try {
					startGun.await();
					for (int round = 0; round < 25; round++) {
						for (int i = mover; i < entities; i += movers) {
							Region target = (round % 2 == 0) ? b : a;
							registry.migrate("entity-" + i, target);
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			}, "mover-" + m);
			t.start();
		}
		startGun.countDown();
		assertTrue(done.await(30, TimeUnit.SECONDS), "movers must finish");

		// Invariant 1: every entity still has exactly one authoritative mapping.
		assertEquals(entities, registry.trackedCount(), "no entity may be lost");

		// Invariant 2: every entity appears in exactly one region's set —
		// cross-checked against the authoritative map (read here only for
		// diagnostics; the test thread is not a region context).
		Set<Object> aSet = registry.perRegionEntities().peekForDiagnostics(a);
		Set<Object> bSet = registry.perRegionEntities().peekForDiagnostics(b);
		assertNotNull(aSet);
		assertNotNull(bSet);
		AtomicInteger counted = new AtomicInteger();
		aSet.forEach(h -> {
			counted.incrementAndGet();
			assertSame(a, registry.ownerOfHandle(h), h + " in A's set must map to A");
		});
		bSet.forEach(h -> {
			counted.incrementAndGet();
			assertSame(b, registry.ownerOfHandle(h), h + " in B's set must map to B");
		});
		assertEquals(entities, counted.get(), "set membership must cover every entity exactly once");
		assertEquals(0, registry.retiredCount(), "no entity may be retired by a storm");
		assertTrue(registry.migrationsCompleted() > 0, "migrations must actually have happened");
	}

	private static RegionScheduler newScheduler(WorldRegionizer rz, int workers) {
		return new RegionScheduler(rz, workers, com.palordersoftworks.fabricfolia.api.ValidationMode.STRICT,
				region -> { }, s -> { });
	}
}
