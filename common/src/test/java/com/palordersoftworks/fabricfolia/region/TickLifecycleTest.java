/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tick lifecycle contract tests: tryMarkTicking/markNotTicking behavior and the
 * documented tick-end protocol order (merges → transient check → dead-section
 * removal → split attempt).
 */
class TickLifecycleTest {

	private static WorldRegionizer newRegionizer() {
		return new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	@Test
	void tryMarkTickingOnlyFromReady() {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		assertEquals(RegionState.READY, region.state);

		assertTrue(rz.tryBeginTick(region));
		assertEquals(RegionState.TICKING, region.state);

		// A second attempt must fail: single-owner-at-a-time.
		assertFalse(rz.tryBeginTick(region));
	}

	@Test
	void doubleCompleteTickFails() {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		rz.tryBeginTick(region);
		rz.completeTick(region, 1);
		assertThrows(IllegalStateException.class, () -> rz.completeTick(region, 1));
	}

	@Test
	void tickCounterAdvancesPerStartedTick() {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		assertEquals(0, region.tickCount());
		// Time base = ticks STARTED: the executing context advances the counter
		// right after tryBeginTick succeeds (uniform semantics whether a delayed
		// task is enqueued mid-tick or between ticks).
		rz.tryBeginTick(region);
		region.advanceTickCounter();
		rz.completeTick(region, 1);
		assertEquals(1, region.tickCount());
		rz.tryBeginTick(region);
		region.advanceTickCounter();
		rz.completeTick(region, 1);
		assertEquals(2, region.tickCount());
	}

	@Test
	void transientRegionCannotTick() {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0); // beyond merge radius (test config r=1 section, 2 chunks/section)

		// Force b into a merge-pending (transient) position: tick a, then add
		// chunks bridging toward b while a ticks.
		assertTrue(rz.tryBeginTick(a));
		// Bridge chunks toward b; each add lands in a transient region that
		// owes a merge into a (or into its transient holder).
		for (int x = 4; x <= 56; x += 4) {
			rz.addChunk(x, 0);
		}
		rz.completeTick(a, 1);

		// All bridging regions merged: after tick-end the merged region must be
		// READY only if no further mergeLater obligations exist.
		Region merged = rz.ownerOfChunk(0, 0);
		assertTrue(merged.state == RegionState.READY || merged.state == RegionState.TRANSIENT,
				"unexpected state: " + merged.state);
		// If TRANSIENT, tryMarkTicking must refuse (contract).
		if (merged.state == RegionState.TRANSIENT) {
			assertFalse(rz.tryBeginTick(merged));
		}
	}

	@Test
	void splitCreatesIndependentReadyRegions() {
		// Aggressive split knobs: recalculate after 4 sections, split once 40%
		// of them are dead. Buffer radius stays 2 (empty-section creation radius
		// must be >= merge radius 1).
		RegionizerConfig cfg = new RegionizerConfig(2, 1, 2, 4, 40);
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new AtomicLong(1)::getAndIncrement, System::nanoTime);

		// Geometry (2 chunks/section): left cluster sections (-2..-1, 0) are
		// chunks x -4..-1; right cluster sections (1..2, 0) are chunks x 2..5.
		// The gap between the clusters' non-empty sections spans sections 0 vs 1
		// — adjacency distance 1 — so the halo (radius 2) bridges them and both
		// join one region. After the RIGHT cluster empties, its sections die
		// (nothing non-empty within radius 2 of section 0 except... section 1
		// itself is empty), and the remaining region recalculate-splits.
		for (int x = -4; x <= -1; x++) rz.addChunk(x, 0);   // left cluster: 2 sections
		for (int x = 2; x <= 5; x++) rz.addChunk(x, 0);     // right cluster: 2 sections

		Region region = rz.ownerOfChunk(-4, 0);
		Region sameRight = rz.ownerOfChunk(5, 0);
		assertEquals(region, sameRight, "halo adjacency should unify the clusters into one region");

		// The right cluster empties entirely (4 removes). Its sections and the
		// shared halo go dead; a full tick purges them and runs the split.
		for (int x = 2; x <= 5; x++) rz.removeChunk(x, 0);
		assertTrue(rz.tryBeginTick(region));
		rz.completeTick(region, 1);

		// After recalculation, ownership must not bridge the dead gap.
		Region left = rz.ownerOfChunk(-4, 0);
		Region right = rz.ownerOfChunk(5, 0);
		assertTrue(left != right, "region did not split across the dead gap");
	}

	private static int countLiveComponents(WorldRegionizer rz, Region region) {
		// Flood fill over the region's alive sections, mirroring the regionizer.
		Set<Long> visited = new HashSet<>();
		int components = 0;
		for (RegionSection start : region.snapshotSections()) {
			if (!start.alive || visited.contains(WorldRegionizer.sectionKey(start.x, start.z))) continue;
			components++;
			java.util.ArrayDeque<RegionSection> stack = new java.util.ArrayDeque<>();
			stack.push(start);
			visited.add(WorldRegionizer.sectionKey(start.x, start.z));
			while (!stack.isEmpty()) {
				RegionSection cur = stack.pop();
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dz == 0) continue;
						long key = WorldRegionizer.sectionKey(cur.x + dx, cur.z + dz);
						if (visited.contains(key)) continue;
						for (RegionSection s : region.snapshotSections()) {
							if (WorldRegionizer.sectionKey(s.x, s.z) == key && s.alive) {
								visited.add(key);
								stack.push(s);
							}
						}
					}
				}
			}
		}
		return components;
	}

	@Test
	void nextTickDeadlineIsFiftyMillisOut() {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		long before = System.nanoTime();
		rz.tryBeginTick(region);
		rz.completeTick(region, 1);
		long expectedFloor = before + 50_000_000L - 5_000_000L; // generous scheduling slack
		assertTrue(region.nextTickDeadlineNanos >= expectedFloor,
				"next tick deadline not ~50ms after tick end");
	}
}
