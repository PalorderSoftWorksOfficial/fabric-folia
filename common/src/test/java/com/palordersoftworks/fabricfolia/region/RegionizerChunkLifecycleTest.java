/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chunk lifecycle + structural-metrics contract (mandates §20, §35):
 * registration counting, unload-driven section death, merge/split/abort
 * accounting through the sinks the fabric engine installs.
 */
class RegionizerChunkLifecycleTest {

	private static WorldRegionizer newRegionizer() {
		return new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	@Test
	void chunkRegistrationCountsPerPosition() {
		WorldRegionizer rz = newRegionizer();
		AtomicInteger registered = new AtomicInteger();
		AtomicInteger unregistered = new AtomicInteger();
		rz.setChunkMetricSink(new WorldRegionizer.ChunkMetricSink() {
			@Override public void chunkRegistered() { registered.incrementAndGet(); }
			@Override public void chunkUnregistered() { unregistered.incrementAndGet(); }
		});

		// forTests: section = 2x2 chunks. All four chunks of section (0,0):
		// ownership is position-exact, so each distinct chunk is one event.
		rz.addChunk(0, 0);
		rz.addChunk(1, 0);
		rz.addChunk(0, 1);
		rz.addChunk(1, 1);
		assertEquals(4, registered.get(), "one event per newly owned chunk position");
		assertNotNull(rz.ownerOfChunk(0, 0));

		// Re-offers (the vanilla entity-ticking pass repeats every tick) are
		// idempotent: no double registration, no counter inflation.
		rz.addChunk(0, 0);
		rz.addChunk(1, 1);
		assertEquals(4, registered.get(), "re-offer of an owned position adds nothing");

		// Each unload releases exactly its position; registered-unregistered
		// always equals the currently owned count.
		rz.removeChunk(0, 0);
		assertEquals(1, unregistered.get(), "each released chunk is one event");
		rz.removeChunk(1, 0);
		rz.removeChunk(0, 1);
		rz.removeChunk(1, 1);
		assertEquals(4, unregistered.get(), "all positions released");
	}

	@Test
	void unmatchedRemoveIsSilentNoOp() {
		WorldRegionizer rz = newRegionizer();
		AtomicInteger unregistered = new AtomicInteger();
		rz.setChunkMetricSink(new WorldRegionizer.ChunkMetricSink() {
			@Override public void chunkRegistered() { }
			@Override public void chunkUnregistered() { unregistered.incrementAndGet(); }
		});
		rz.removeChunk(500, 500); // never added
		rz.removeChunk(500, 500); // twice
		assertEquals(0, unregistered.get(), "defensive no-op must not count");
	}

	@Test
	void mergeAcrossDistanceIsCounted() {
		WorldRegionizer rz = newRegionizer();
		AtomicInteger merges = new AtomicInteger();
		rz.setStructuralMetricSink(new WorldRegionizer.StructuralMetricSink() {
			@Override public void regionMerged() { merges.incrementAndGet(); }
			@Override public void regionSplit() { }
			@Override public void regionAborted() { }
		});

		// forTests: 2 chunks/section, merge radius 2, creation radius 2.
		// Two clusters 8 sections apart are independent regions.
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(20, 0); // section 10
		assertNotEquals(a, b, "disconnected areas must be independent regions");

		// Chain sections 2..8 toward B; the final adds fall within the
		// merge+creation radius (4 sections) of B's region and force the
		// invariant-2 merge.
		for (int section = 2; section <= 8; section++) {
			rz.addChunk(section * 2, 0);
		}
		Region finalOwner = rz.ownerOfChunk(20, 0);
		assertEquals(finalOwner, rz.ownerOfChunk(0, 0),
				"bridge must have merged both clusters into one region");
		assertTrue(merges.get() >= 1, "at least one merge must be recorded, got " + merges.get());
	}

	@Test
	void splitAfterUnloadIsCountedAndChildrenAreReady() {
		// Split-recipe config proven in TickLifecycleTest.splitCreatesIndependentReadyRegions
		// (recalculate at 4 sections, split gate 40% dead, support radius 2).
		RegionizerConfig cfg = new RegionizerConfig(2, 1, 2, 4, 40);
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
		AtomicInteger splits = new AtomicInteger();
		rz.setStructuralMetricSink(new WorldRegionizer.StructuralMetricSink() {
			@Override public void regionMerged() { }
			@Override public void regionSplit() { splits.incrementAndGet(); }
			@Override public void regionAborted() { }
		});

		// One bar of 11 sections (chunks 0..22 on z=0, 2 chunks per section).
		for (int x = 0; x <= 22; x++) rz.addChunk(x, 0);
		Region bar = rz.ownerOfChunk(0, 0);
		assertEquals(bar, rz.ownerOfChunk(22, 0), "bar must be a single region");

		// Unload the 9 middle sections (chunks 2..21): empty sections within
		// support radius 2 of a survivor stay alive, so of the 9 emptied only
		// the 5 deep-middle ones die. Dead = 5/11 = 45% >= the 40% gate; after
		// the purge the survivors (sections 0 and 10) form 2 components -> split.
		for (int x = 2; x <= 21; x++) rz.removeChunk(x, 0);
		int before = splits.get();
		assertTrue(rz.tryBeginTick(bar));
		rz.completeTick(bar, 1);

		assertTrue(splits.get() > before, "unload-driven split must be recorded, got " + splits.get());
		assertNotEquals(rz.ownerOfChunk(0, 0), rz.ownerOfChunk(22, 0),
				"ownership must not bridge the dead gap after the split");
	}

	@Test
	void abortTickIsCounted() {
		WorldRegionizer rz = newRegionizer();
		AtomicInteger aborts = new AtomicInteger();
		rz.setStructuralMetricSink(new WorldRegionizer.StructuralMetricSink() {
			@Override public void regionMerged() { }
			@Override public void regionSplit() { }
			@Override public void regionAborted() { aborts.incrementAndGet(); }
		});
		Region region = rz.addChunk(0, 0);
		assertTrue(rz.tryBeginTick(region));
		rz.abortTick(region, new RuntimeException("test"));
		assertEquals(1, aborts.get());
		assertFalse(rz.tryBeginTick(region), "aborted region must never tick again");
	}
}
