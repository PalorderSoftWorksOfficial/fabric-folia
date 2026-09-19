/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionizerConfig;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pending-scheduled-tick ledger tests (mandate §21): record/release balance,
 * merge folding, split partitioning by chunk owner, death dropping, and the
 * context rule (only the capturing region records). Structural transitions
 * are driven through the real regionizer exactly as production drives them.
 */
class RegionPendingTicksTest {

	private static WorldRegionizer newRegionizer() {
		return new WorldRegionizer("test:world", RegionizerConfig.forTests(),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	@AfterEach
	void clearContext() {
		ThreadOwnership.clear();
	}

	@Test
	void recordRequiresRegionContext() {
		WorldRegionizer rz = newRegionizer();
		RegionPendingTicks ledger = new RegionPendingTicks("pendingBlockTicks", rz, new RegionDataHub());

		// No context at all: a capture-side record from a non-region thread
		// would mean the deferral protocol leaked — fail loudly.
		assertThrows(com.palordersoftworks.fabricfolia.api.ThreadContextViolationException.class,
				() -> ledger.record(0, 0));
	}

	@Test
	void recordAndReleaseBalancePerChunk() {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		RegionDataHub hub = new RegionDataHub();
		RegionPendingTicks ledger = new RegionPendingTicks("pendingBlockTicks", rz, hub);
		hub.attachTo(rz);

		recordInContext(ledger, a, 0, 0);
		recordInContext(ledger, a, 0, 0);
		recordInContext(ledger, a, 1, 0); // neighboring tick-chunk, same region
		assertEquals(3, ledger.pendingCount(a), "three pending ticks recorded");

		assertTrue(ledger.isPending(0, 0), "recorded chunk is pending");
		assertTrue(ledger.isPending(1, 0), "second recorded chunk is pending");
		assertFalse(ledger.isPending(5, 5), "unrecorded chunk is not pending");

		// Drain executed one of the two ticks on chunk (0,0): count drops
		// to one, the key must STAY pending.
		ledger.release(0, 0);
		assertEquals(2, ledger.pendingCount(a));
		assertTrue(ledger.isPending(0, 0), "count 1 still pending");

		ledger.release(0, 0);
		ledger.release(1, 0);
		assertEquals(0, ledger.pendingCount(a), "ledger empty after all releases");
		assertFalse(ledger.isPending(0, 0), "fully released chunk is not pending");
		assertFalse(ledger.isPending(1, 0));
	}

	@Test
	void releaseFromUnknownRegionIsSilent() {
		WorldRegionizer rz = newRegionizer();
		RegionPendingTicks ledger = new RegionPendingTicks("pendingBlockTicks", rz, new RegionDataHub());

		// No region owns this chunk, and no region ever recorded: release
		// must be a no-op, never an exception (the drain thread is hot).
		ledger.release(1000, 1000);
		assertEquals(0, ledger.ledgerCount());
	}

	@Test
	void mergeFoldsDonorCountsIntoSurvivor() {
		WorldRegionizer rz = newRegionizer();
		RegionDataHub hub = new RegionDataHub();
		RegionPendingTicks ledger = new RegionPendingTicks("pendingBlockTicks", rz, hub);
		hub.attachTo(rz);

		// Two regions far beyond merge radius (test config radius 3).
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0);
		assertTrue(a != b, "test geometry must start with two distinct regions");

		recordInContext(ledger, a, 0, 0);
		recordInContext(ledger, b, 60, 0);
		recordInContext(ledger, b, 60, 0);

		// Real merge through the tick-end protocol (proven recipe).
		assertTrue(rz.tryBeginTick(a));
		for (int x = 4; x <= 56; x += 4) rz.addChunk(x, 0);
		rz.completeTick(a, 1);

		Region survivor = rz.ownerOfChunk(60, 0);
		assertSame(a, survivor, "the ticking region must absorb everything");
		assertEquals(3, ledger.pendingCount(a),
				"merge must fold donor counts (1+2) into the survivor");
		assertTrue(ledger.isPending(60, 0), "folded entries answer queries");
	}

	@Test
	void splitPartitionsEntriesByChunkOwner() {
		// Proven split geometry (TickLifecycleTest): 2 chunks/section,
		// 20-chunk bar, bridge middle empties, tick-end recalculation splits.
		RegionizerConfig cfg = new RegionizerConfig(2, 1, 2, 4, 40);
		WorldRegionizer rz = new WorldRegionizer("test:world", cfg,
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
		RegionDataHub hub = new RegionDataHub();
		RegionPendingTicks ledger = new RegionPendingTicks("pendingBlockTicks", rz, hub);
		hub.attachTo(rz);

		for (int x = 0; x <= 19; x++) rz.addChunk(x, 0);
		Region region = rz.ownerOfChunk(0, 0);

		// Pending ticks on BOTH future survivors (chunk 0 and chunk 19).
		recordInContext(ledger, region, 0, 0);
		recordInContext(ledger, region, 19, 0);
		assertEquals(2, ledger.pendingCount(region));

		for (int x = 2; x <= 17; x++) rz.removeChunk(x, 0);
		assertTrue(rz.tryBeginTick(region));
		rz.completeTick(region, 1); // tick-end recalculation splits

		Region left = rz.ownerOfChunk(0, 0);
		Region right = rz.ownerOfChunk(19, 0);
		assertTrue(left != null && right != null && left != right, "must be two regions");

		// Entries moved with their chunks; each survivor releases its own.
		assertEquals(1, ledger.pendingCount(left), "left keeps only its chunk's entry");
		assertEquals(1, ledger.pendingCount(right), "right keeps only its chunk's entry");
		ledger.release(0, 0);
		ledger.release(19, 0);
		assertEquals(0, ledger.pendingCount(left));
		assertEquals(0, ledger.pendingCount(right));
	}

	@Test
	void foreignRegionQueryFallsBackToStructuralOwner() {
		WorldRegionizer rz = newRegionizer();
		RegionDataHub hub = new RegionDataHub();
		RegionPendingTicks ledger = new RegionPendingTicks("pendingBlockTicks", rz, hub);
		hub.attachTo(rz);

		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0);
		assertTrue(a != b);
		recordInContext(ledger, a, 0, 0);

		// B's context asks about A's chunk: the fast path misses (B's ledger
		// is empty), the structural lookup answers from A's ledger.
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(b);
		try {
			assertTrue(ledger.isPending(0, 0), "foreign query resolves the owner's ledger");
			assertFalse(ledger.isPending(59, 0), "no entry in the queried chunk");
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	private static void recordInContext(RegionPendingTicks ledger, Region region, int cx, int cz) {
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(region);
		try {
			ledger.record(cx, cz);
		} finally {
			ThreadOwnership.exit(token);
		}
	}
}
