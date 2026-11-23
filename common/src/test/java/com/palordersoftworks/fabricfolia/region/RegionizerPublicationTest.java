
package com.palordersoftworks.fabricfolia.region;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionizerPublicationTest {

	private static WorldRegionizer newRegionizer() {
		return newRegionizer(RegionizerConfig.forTests());
	}

	private static WorldRegionizer newRegionizer(RegionizerConfig config) {
		return new WorldRegionizer("test:world", config,
				new AtomicLong(1)::getAndIncrement, System::nanoTime);
	}

	private static final class OffLockReader implements AutoCloseable {
		private final WorldRegionizer regionizer;
		private final ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "off-lock-owner-reader");
			thread.setDaemon(true);
			return thread;
		});

		OffLockReader(WorldRegionizer regionizer) {
			this.regionizer = regionizer;
		}

		Region read(int chunkX, int chunkZ) {
			Future<Region> future = pool.submit(() -> regionizer.ownerOfChunk(chunkX, chunkZ));
			try {
				return future.get(5, TimeUnit.SECONDS);
			} catch (TimeoutException e) {
				throw new AssertionError(
						"ownerOfChunk blocked on the structure lock while a structural thread held it");
			} catch (ExecutionException e) {
				throw new AssertionError("ownerOfChunk failed", e.getCause());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}

		@Override
		public void close() {
			pool.shutdownNow();
		}
	}

	private static int chunkX(long sectionKey, int sectionSizeChunks) {
		return (int) (sectionKey >> 32) * sectionSizeChunks;
	}

	private static int chunkZ(long sectionKey, int sectionSizeChunks) {
		return ((int) sectionKey) * sectionSizeChunks;
	}

	@Test
	void ownerOfChunkDoesNotTakeTheStructureLock() throws Exception {
		WorldRegionizer regionizer = newRegionizer();
		Region owner = regionizer.addChunk(0, 0);

		CountDownLatch lockHeld = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread structuralThread = new Thread(() -> regionizer.underStructureLock(() -> {
			lockHeld.countDown();
			try {
				release.await(20, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}), "structure-lock-holder");
		structuralThread.start();
		assertTrue(lockHeld.await(5, TimeUnit.SECONDS), "the structure lock must be held for the check");

		try (OffLockReader reader = new OffLockReader(regionizer)) {
			assertSame(owner, reader.read(0, 0),
					"a read must answer from the published view, not wait for the structure lock");
			assertNull(reader.read(5000, 5000), "an unowned position stays a null answer");
		} finally {
			release.countDown();
			structuralThread.join(5000);
		}
		assertFalse(structuralThread.isAlive(), "the lock holder must have exited");
	}

	@Test
	void mergePublishesTheSurvivorsSectionsBeforeTheMergeIsAnnounced() {
		WorldRegionizer regionizer = newRegionizer();
		int sectionSize = RegionizerConfig.forTests().sectionSizeChunks();
		List<Region> announcedSurvivors = new ArrayList<>();
		regionizer.addListener(new WorldRegionizer.Listener() {
			@Override
			public void onRegionMerged(Region donor, Region into) {
				announcedSurvivors.add(into);
				try (OffLockReader reader = new OffLockReader(regionizer)) {
					for (Long key : into.sections.keySet()) {
						assertSame(into, reader.read(chunkX(key, sectionSize), chunkZ(key, sectionSize)),
								"section " + key + " must already be published to the survivor while the merge is announcing");
					}
				}
			}

			@Override
			public void onRegionSplit(Region parent, List<Region> children) {
			}

			@Override
			public void onRegionDead(Region region) {
			}
		});

		regionizer.addChunk(0, 0);
		regionizer.addChunk(20, 0);
		for (int section = 2; section <= 8; section++) {
			regionizer.addChunk(section * 2, 0);
		}

		assertFalse(announcedSurvivors.isEmpty(), "the bridge must have merged at least one region");
		assertSame(regionizer.ownerOfChunk(0, 0), regionizer.ownerOfChunk(20, 0),
				"after the merge both clusters must resolve to the same region");
	}

	@Test
	void splitPublishesChildrensSectionsBeforeTheSplitIsAnnounced() {
		RegionizerConfig config = new RegionizerConfig(2, 1, 2, 4, 40);
		WorldRegionizer regionizer = newRegionizer(config);
		int sectionSize = config.sectionSizeChunks();
		List<Region> announcedChildren = new ArrayList<>();
		regionizer.addListener(new WorldRegionizer.Listener() {
			@Override
			public void onRegionMerged(Region donor, Region into) {
			}

			@Override
			public void onRegionSplit(Region parent, List<Region> children) {
				announcedChildren.addAll(children);
				try (OffLockReader reader = new OffLockReader(regionizer)) {
					for (Region child : children) {
						assertFalse(child.sections.isEmpty(), "a split child must own sections");
						for (Long key : child.sections.keySet()) {
							assertSame(child, reader.read(chunkX(key, sectionSize), chunkZ(key, sectionSize)),
									"section " + key + " must already be published to its child while the split is announcing");
						}
					}
					for (Long key : parent.sections.keySet()) {
						assertSame(parent, reader.read(chunkX(key, sectionSize), chunkZ(key, sectionSize)),
								"the parent's remaining section " + key + " must stay published to the parent");
					}
				}
			}

			@Override
			public void onRegionDead(Region region) {
			}
		});

		for (int x = 0; x <= 19; x++) {
			regionizer.addChunk(x, 0);
		}
		Region parent = regionizer.ownerOfChunk(0, 0);
		assertNotNull(parent, "the bridge must unify everything into one region");
		assertSame(parent, regionizer.ownerOfChunk(19, 0));
		for (int x = 2; x <= 17; x++) {
			regionizer.removeChunk(x, 0);
		}
		assertTrue(regionizer.tryBeginTick(parent));
		regionizer.completeTick(parent, 1);

		assertFalse(announcedChildren.isEmpty(), "the dead bridge must have split the region");
		Region left = regionizer.ownerOfChunk(0, 0);
		Region right = regionizer.ownerOfChunk(19, 0);
		assertNotNull(left);
		assertNotNull(right);
		assertNotSame(left, right, "ownership must not bridge the dead gap");
	}

	@Test
	void deathUnpublishesOwnershipBeforeTheDeathIsAnnounced() {
		WorldRegionizer regionizer = newRegionizer();
		int sectionSize = RegionizerConfig.forTests().sectionSizeChunks();
		java.util.concurrent.atomic.AtomicReference<Set<Long>> announcedKeys =
				new java.util.concurrent.atomic.AtomicReference<>();
		regionizer.addListener(new WorldRegionizer.Listener() {
			@Override
			public void onRegionMerged(Region donor, Region into) {
			}

			@Override
			public void onRegionSplit(Region parent, List<Region> children) {
			}

			@Override
			public void onRegionDead(Region region) {
				try (OffLockReader reader = new OffLockReader(regionizer)) {
					for (Long key : announcedKeys.get()) {
						assertNull(reader.read(chunkX(key, sectionSize), chunkZ(key, sectionSize)),
								"section " + key + " must be unpublished while the death is announcing");
					}
				}
			}
		});

		Region region = regionizer.addChunk(0, 0);
		Set<Long> ownedKeys = new HashSet<>(region.sections.keySet());
		assertFalse(ownedKeys.isEmpty(), "the region must own at least its own section");				announcedKeys.set(ownedKeys);

		regionizer.removeChunk(0, 0);
		assertTrue(regionizer.tryBeginTick(region));
		regionizer.completeTick(region, 1);

		assertTrue(region.isDead(), "an emptied region must die at tick end");
		assertNull(regionizer.ownerOfChunk(0, 0), "a dead region must never be published as an owner");
	}
}
