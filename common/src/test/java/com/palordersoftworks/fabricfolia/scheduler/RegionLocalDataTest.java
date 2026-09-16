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

import java.util.ArrayList;
import java.util.List;
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
 * Region-local data lifecycle tests (mandate §7/§26): data is created and
 * read only under the owning region's context, merges reconcile donor data
 * into the survivor, splits produce per-child data, and destroy releases.
 * All structural transitions are driven through the real regionizer, exactly
 * as the production scheduler drives them.
 */
class RegionLocalDataTest {

	/** Simple per-region payload: a list of strings plus a released flag. */
	private static final class Payload {
		final List<String> items = new ArrayList<>();
		boolean released;
	}

	private static final class TestLifecycle implements RegionLocalData.Lifecycle<Payload> {
		final AtomicReference<Payload> lastDestroyed = new AtomicReference<>();

		@Override
		public Payload create(Region region) {
			return new Payload();
		}

		@Override
		public void onMerge(Region donor, Region into, Payload donorData, Payload intoData) {
			intoData.items.addAll(donorData.items);
		}

		@Override
		public Payload onSplit(Region parent, Region child, Payload parentData) {
			Payload childPayload = new Payload();
			// Realistic redistribution: split the parent's items between the
			// parent and the child (half stays, half moves).
			int half = parentData.items.size() / 2;
			childPayload.items.addAll(parentData.items.subList(half, parentData.items.size()));
			parentData.items.subList(half, parentData.items.size()).clear();
			return childPayload;
		}

		@Override
		public void onDestroy(Region region, Payload data) {
			data.released = true;
			lastDestroyed.set(data);
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


	private static Payload getInContext(RegionLocalData<Payload> data, Region region) {
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(region);
		try {
			return data.get(region);
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void getOutsideOwnerContextThrows() {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		RegionDataHub hub = new RegionDataHub();
		RegionLocalData<Payload> data = new RegionLocalData<>("test", new TestLifecycle(), hub);

		// Test thread has NO region context: accessing region-owned data must
		// be a violation, never a silent hand-out.
		assertThrows(ThreadContextViolationException.class, () -> data.get(region));
	}

	@Test
	void wrongRegionContextThrows() {
		WorldRegionizer rz = newRegionizer();
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(1000, 1000); // far beyond any merge radius
		RegionDataHub hub = new RegionDataHub();
		RegionLocalData<Payload> data = new RegionLocalData<>("test", new TestLifecycle(), hub);

		// Enter A's context, touch B's data: a cross-region access — exactly
		// what the ownership check exists to catch.
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(a);
		try {
			assertThrows(ThreadContextViolationException.class, () -> data.get(b));
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void getCreatesLazilyInOwnerContext() {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		RegionDataHub hub = new RegionDataHub();
		RegionLocalData<Payload> data = new RegionLocalData<>("test", new TestLifecycle(), hub);

		assertNull(data.peekForDiagnostics(region), "no data before first access");

		Payload first = getInContext(data, region);
		assertNotNull(first);
		first.items.add("a");
		assertSame(first, getInContext(data, region), "second access returns the same instance");
		assertEquals(1, data.entryCount());
	}

	@Test
	void mergeReconcilesDonorDataIntoSurvivor() {
		WorldRegionizer rz = newRegionizer();
		RegionDataHub hub = new RegionDataHub();
		RegionLocalData<Payload> data = new RegionLocalData<>("test", new TestLifecycle(), hub);
		hub.attachTo(rz);

		// Two regions FAR beyond the merge+creation radius (3 sections under
		// the test config): they must be distinct at creation. (Chunk 6 would
		// unify immediately — section 3 is within radius 3 of section 0.)
		Region a = rz.addChunk(0, 0);
		Region b = rz.addChunk(60, 0);
		assertTrue(a != b, "test geometry must start with two distinct regions");

		// Populate both regions' data in their own contexts.
		getInContext(data, a).items.add("from-a");
		getInContext(data, b).items.add("from-b");
		assertEquals(2, data.entryCount());

		// Drive the real merge: a ticks; the bridge (added under a's tick)
		// lands in transient holders that owe a merge into a and absorbs b on
		// the way; tick end processes the deferred merges into a.
		assertTrue(rz.tryBeginTick(a));
		for (int x = 4; x <= 56; x += 4) {
			rz.addChunk(x, 0);
		}
		rz.completeTick(a, 1);

		Region survivor = rz.ownerOfChunk(60, 0);
		assertSame(a, survivor, "the ticking region must absorb everything at tick end");
		Payload survivorData = data.peekForDiagnostics(survivor);
		assertNotNull(survivorData, "survivor must own reconciled data");
		assertTrue(survivorData.items.contains("from-a") && survivorData.items.contains("from-b"),
				"merge must reconcile BOTH sides' data, got " + survivorData.items);
		assertEquals(1, data.entryCount(), "donor entries must be removed after merge");
	}

	@Test
	void splitProducesPerChildData() {
		// Low dead-section gate: the halo keeps 2-section alive margins around
		// each cluster, so 10% (not the production-like 40%) makes the tick-end
		// recalculation deterministic at this geometry's scale.
		WorldRegionizer rz = new WorldRegionizer("test:world", new RegionizerConfig(2, 1, 2, 4, 10),
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
		RegionDataHub hub = new RegionDataHub();
		RegionLocalData<Payload> data = new RegionLocalData<>("test", new TestLifecycle(), hub);
		hub.attachTo(rz);

		// One long line: a single region owning sections 0..16 (2 chunks each).
		for (int x = 0; x <= 33; x++) rz.addChunk(x, 0);
		Region parent = rz.ownerOfChunk(0, 0);

		// Parent data: four items; the split must redistribute them.
		getInContext(data, parent).items.addAll(List.of("i1", "i2", "i3", "i4"));

		// Empty the middle (chunks 10..23 = sections 5..11): sections 7..9 sit
		// beyond BOTH clusters' halo margins (radius 2) and die; tick-end purges
		// them (3 dead of 17 clears the 10% gate), the two remaining alive
		// components split, and the smaller one becomes a new child region.
		for (int x = 10; x <= 23; x++) rz.removeChunk(x, 0);
		assertTrue(rz.tryBeginTick(parent));
		rz.completeTick(parent, 1);

		Region left = rz.ownerOfChunk(0, 0);
		Region right = rz.ownerOfChunk(33, 0);
		assertNotNull(right, "split child must own a live component (null here means no real split)");
		assertTrue(left != right, "region did not split across the dead gap");

		Payload leftData = data.peekForDiagnostics(left);
		Payload rightData = data.peekForDiagnostics(right);
		assertNotNull(leftData, "parent keeps its (reduced) data after split");
		assertNotNull(rightData, "split child must receive its redistributed data");
		assertEquals(2, leftData.items.size(), "parent keeps half the items");
		assertEquals(2, rightData.items.size(), "child gets half the items");
		assertEquals(4, leftData.items.size() + rightData.items.size(), "no items lost in split");
		assertEquals(2, data.entryCount(), "parent + child = exactly two live entries");
	}

	@Test
	void destroyReleasesData() {
		WorldRegionizer rz = newRegionizer();
		Region region = rz.addChunk(0, 0);
		RegionDataHub hub = new RegionDataHub();
		TestLifecycle lifecycle = new TestLifecycle();
		RegionLocalData<Payload> data = new RegionLocalData<>("test", lifecycle, hub);
		hub.attachTo(rz);

		Payload payload = getInContext(data, region);
		payload.items.add("doomed");

		// Kill the region: empty every chunk, then a tick-end purges + kills.
		rz.removeChunk(0, 0);
		assertTrue(rz.tryBeginTick(region));
		rz.completeTick(region, 1);

		assertEquals(0, data.entryCount(), "destroyed region must not keep data entries");
		Payload destroyed = lifecycle.lastDestroyed.get();
		assertNotNull(destroyed, "destroy callback must have run");
		assertSame(payload, destroyed, "the region's own payload must be the released one");
		assertTrue(destroyed.released, "destroy must mark the payload released");
	}
}
