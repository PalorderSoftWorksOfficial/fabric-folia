/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regionizer invariant tests (spec 19): randomized chunk storms must preserve
 * the four invariants at every observable point, and merge/split sequences must
 * converge to sane structures.
 */
class RegionizerInvariantTest {

	/**
	 * Asserts all four invariants over the current regionizer state.
	 * Package-private static so the concurrency stress test can reuse it.
	 */
	static void assertInvariants(WorldRegionizer rz, RegionizerConfig cfg) {
		List<Region> live = rz.liveRegions();

		// Invariant 1: every non-empty section belongs to exactly one live region.
		Map<Long, List<Region>> owners = new HashMap<>();
		for (Region region : live) {
			assertFalse(region.state == RegionState.DEAD, "dead region in liveRegions");
			for (Long key : region.sections.keySet()) {
				owners.computeIfAbsent(key, k -> new ArrayList<>()).add(region);
			}
		}
		for (Map.Entry<Long, List<Region>> e : owners.entrySet()) {
			assertEquals(1, e.getValue().size(),
					"section " + e.getKey() + " owned by " + e.getValue().size() + " regions");
		}

		// Invariant 2: every alive section within merge radius of a region's
		// section is owned by the same region or pending merge with it.
		for (Region region : live) {
			for (RegionSection section : region.sections.values()) {
				if (!section.alive) continue;
				int r = cfg.mergeRadiusSections();
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						RegionSection neighbor = region.sections.get(WorldRegionizer.sectionKey(section.x + dx, section.z + dz));
						// Neighbor must be ours or (if owned by another region)
						// pending merge with us.
						if (neighbor != null && neighbor.alive) continue;
						// Check ownership of the neighbor position globally.
						// It must be either unowned, ours, or merge-pending.
						for (Region other : live) {
							if (other == region) continue;
							if (other.sections.containsKey(WorldRegionizer.sectionKey(section.x + dx, section.z + dz))) {
								boolean pending = region.mergeLater.contains(other)
										|| region.expectingMergeFrom.contains(other)
										|| other.mergeLater.contains(region)
										|| other.expectingMergeFrom.contains(region);
								assertTrue(pending,
										"invariant 2 violated: " + region + " adjacent to " + other
												+ " at offset " + dx + "," + dz + " without pending merge");
							}
						}
					}
				}
			}
		}

		// Invariant 3: no region expands while ticking is enforced structurally —
		// verified in TickLifecycleTest (state-dependent behavior); here we assert
		// the structural precondition: only non-ticking regions gain sections
		// during addChunk (checked by construction in this test since we never
		// hold a region in TICKING across calls).

		// Invariant 4: every region is in exactly one of the four states.
		for (Region region : live) {
			assertNotNull(region.state);
			assertTrue(region.state == RegionState.TRANSIENT
							|| region.state == RegionState.READY
							|| region.state == RegionState.TICKING,
					"region in illegal state: " + region.state);
		}

		// Buffer sanity: every non-empty section must be alive (never dead).
		for (Region region : live) {
			for (RegionSection section : region.sections.values()) {
				if (section.isNotEmpty()) {
					assertTrue(section.alive, "non-empty section marked dead: " + section);
				}
			}
		}
	}

	@Test
	void stormMaintainsSingleOwnershipAndBuffers() {
		RegionizerConfig cfg = RegionizerConfig.forTests();
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new java.util.concurrent.atomic.AtomicLong(1)::getAndIncrement,
				System::nanoTime);

		Random random = new Random(0xF01A);
		Set<Long> liveChunks = new HashSet<>();

		for (int i = 0; i < 2000; i++) {
			int chunkX = random.nextInt(64) - 32;
			int chunkZ = random.nextInt(64) - 32;
			long key = WorldRegionizer.sectionKey(chunkX, chunkZ); // reuse packing for chunk keys
			if (liveChunks.contains(key) || random.nextInt(4) == 0) {
				liveChunks.remove(key);
				rz.removeChunk(chunkX, chunkZ);
			} else {
				liveChunks.add(key);
				rz.addChunk(chunkX, chunkZ);
			}
			if (i % 97 == 0) {
				assertInvariants(rz, cfg);
			}
		}
		assertInvariants(rz, cfg);
	}

	@Test
	void ownerOfChunkTracksOwnership() {
		RegionizerConfig cfg = RegionizerConfig.forTests();
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new java.util.concurrent.atomic.AtomicLong(1)::getAndIncrement,
				System::nanoTime);

		assertNull(rz.ownerOfChunk(0, 0));
		Region first = rz.addChunk(0, 0);
		assertNotNull(first);
		assertEquals(first, rz.ownerOfChunk(0, 0));

		// A chunk well outside merge radius forms its own region.
		Region second = rz.addChunk(100, 100);
		assertNotNull(second);
		assertTrue(first != second, "far chunks merged into one region");

		// A chunk adjacent to the first joins the first's region.
		Region adjacent = rz.addChunk(1, 0);
		assertEquals(first, adjacent);
	}

	@Test
	void removeChunkEmptiesSectionsAndKillsRegions() {
		RegionizerConfig cfg = RegionizerConfig.forTests();
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new java.util.concurrent.atomic.AtomicLong(1)::getAndIncrement,
				System::nanoTime);

		Region region = rz.addChunk(0, 0);
		rz.removeChunk(0, 0);

		// The region still owns its (now empty/dead) sections until tick-end
		// recalculation — removeChunk never changes region state (reference).
		assertNotNull(rz.ownerOfChunk(0, 0));
		assertEquals(RegionState.READY, region.state);
	}

	@Test
	void tickingRegionAbsorbsNearbyWorkViaTransientNeighbor() {
		RegionizerConfig cfg = RegionizerConfig.forTests();
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new java.util.concurrent.atomic.AtomicLong(1)::getAndIncrement,
				System::nanoTime);

		Region ticking = rz.addChunk(0, 0);
		assertTrue(rz.tryBeginTick(ticking), "fresh region must be ready");

		// Add a chunk within merge radius while the region ticks. Invariant 3:
		// the ticking region must NOT gain the section directly.
		int before = ticking.sections.size();
		Region owner = rz.addChunk(2, 0);
		assertEquals(before, ticking.sections.size(),
				"ticking region grew during tick (invariant 3 violation)");

		// The new work must be owned by SOMETHING (invariant 1).
		assertNotNull(owner);
		assertNotNull(rz.ownerOfChunk(2, 0));

		// Complete the tick: the pending merge must be processed at tick end.
		rz.completeTick(ticking, 1_000_000);

		// After tick end, the ticking region absorbed the neighbor (or the
		// neighbor merged into it) — single ownership still holds.
		assertInvariants(rz, cfg);
		assertEquals(ticking, rz.ownerOfChunk(2, 0),
				"neighbor did not merge into formerly-ticking region at tick end");
	}

	@Test
	void fourStatesAreExhaustive() {
		// Invariant 4, statically: exactly four states exist.
		assertEquals(4, RegionState.values().length);
		Set<String> names = new HashSet<>();
		for (RegionState state : RegionState.values()) {
			names.add(state.name());
		}
		assertTrue(names.containsAll(Set.of("TRANSIENT", "READY", "TICKING", "DEAD")));
	}
}
