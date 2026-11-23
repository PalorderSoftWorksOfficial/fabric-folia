
package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ThreadContext.Kind;
import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.engine.RegionTransitions.Decision;
import com.palordersoftworks.fabricfolia.engine.RegionTransitions.EntityTransitionKey;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.scheduler.RegionTaskQueue;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EntityTeleportDispatchTest {

	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";

	@BeforeEach
	void noteServerThread() {
		ServerThreadDeferral.clearAll();
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
	}

	@AfterEach
	void clearContext() {
		ThreadOwnership.exit(ThreadOwnership.Context.UNKNOWN);
		ServerThreadDeferral.clearAll();
	}

	private static FabricFoliaEngine bootEngine() throws Exception {
		Path dir = Files.createTempDirectory("folia-entity-teleport");
		FoliaConfig config = FoliaConfig.at(dir.resolve("fabric-folia.yml"));
		config.load();
		config.freeze();
		return FabricFoliaEngine.bootstrap(config, msg -> { }, msg -> { });
	}

	private static EntityTransitionKey key(String world, int entityId) {
		return new EntityTransitionKey(world, entityId);
	}

	private static Region currentRegion() {
		return ThreadOwnership.currentRegion().map(r -> (Region) r).orElse(null);
	}

	private static void await(java.util.function.BooleanSupplier condition, String message)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(5);
		}
		fail(message);
	}

	@Test
	void regionOwnerDestinationRunsInline() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			Region destination = regionizer.addChunk(0, 0);

			ThreadOwnership.enterRegion(destination);
			AtomicBoolean ran = new AtomicBoolean();
			Decision decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					0, 0, key(OVERWORLD, 901), false, () -> ran.set(true));

			assertEquals(Decision.INLINE, decision,
					"a REGION context already owning the destination must run vanilla inline");
			assertFalse(ran.get(), "an inline decision leaves the body to the caller");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void regionForeignDestinationDispatchesAndReentersInline() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			Region destination = regionizer.addChunk(0, 0);
			Region source = regionizer.addChunk(400, 400);

			ThreadOwnership.enterRegion(source);

			AtomicReference<Decision> reentry = new AtomicReference<>();
			AtomicReference<Region> bodyRegion = new AtomicReference<>();
			AtomicInteger runs = new AtomicInteger();
			CountDownLatch ran = new CountDownLatch(1);
			Decision decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					0, 0, key(OVERWORLD, 902), false, () -> {
						runs.incrementAndGet();
						bodyRegion.set(currentRegion());
						reentry.set(RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
								0, 0, key(OVERWORLD, 902), false, () -> { }));
						ran.countDown();
					});

			assertEquals(Decision.DISPATCHED, decision);
			assertTrue(ran.await(10, TimeUnit.SECONDS),
					"the accepted body must execute on the destination context");
			assertEquals(1, runs.get(), "the vanilla body must run exactly once");
			assertSame(destination, bodyRegion.get());
			assertEquals(Decision.INLINE, reentry.get(),
					"the re-entered call inside the body must run vanilla inline");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void nestedTransitionToAnotherEntityAndDestinationIsNotInline() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			Region outerDestination = regionizer.addChunk(0, 0);
			Region nestedDestination = regionizer.addChunk(400, 400);
			Region source = regionizer.addChunk(800, 800);

			AtomicReference<Decision> nested = new AtomicReference<>();
			AtomicReference<Decision> sameEntityOtherChunk = new AtomicReference<>();
			AtomicReference<Region> nestedBodyRegion = new AtomicReference<>();
			AtomicInteger outerRuns = new AtomicInteger();
			AtomicInteger nestedRuns = new AtomicInteger();
			CountDownLatch nestedRan = new CountDownLatch(1);

			ThreadOwnership.Context previous = ThreadOwnership.enterRegion(source);
			Decision outer;
			try {
				outer = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
						0, 0, key(OVERWORLD, 920), false, () -> {
							outerRuns.incrementAndGet();
							sameEntityOtherChunk.set(RegionTransitions.resolveEntityTransition(
									engine, OVERWORLD, 400, 400, key(OVERWORLD, 920), false,
									() -> { }));
							nested.set(RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
									400, 400, key(OVERWORLD, 921), false, () -> {
										nestedRuns.incrementAndGet();
										nestedBodyRegion.set(currentRegion());
										nestedRan.countDown();
									}));
						});
			} finally {
				ThreadOwnership.exit(previous);
			}

			assertEquals(Decision.DISPATCHED, outer);
			assertTrue(nestedRan.await(10, TimeUnit.SECONDS));
			assertEquals(1, outerRuns.get(), "the outer body must run exactly once");
			assertEquals(Decision.DISPATCHED, nested.get(),
					"a nested transition for another entity and chunk must be dispatched, not inline");
			assertSame(nestedDestination, nestedBodyRegion.get(),
					"the nested body must run in the region owning its own destination");
			assertEquals(1, nestedRuns.get());
			assertEquals(Decision.DISPATCHED, sameEntityOtherChunk.get(),
					"a nested transition to a different chunk must be dispatched even for the same entity");
			assertNotSame(outerDestination, nestedDestination);
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void globalContextWithNoOwnerRunsInline() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);

			ThreadOwnership.enterGlobal();
			AtomicBoolean ran = new AtomicBoolean();
			Decision decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					5000, 5000, key(OVERWORLD, 903), false, () -> ran.set(true));

			assertEquals(Decision.INLINE, decision,
					"a GLOBAL context with no destination owner must run vanilla inline");
			assertFalse(ran.get());
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void unownedDestinationBodyReentersInlineOnTheGlobalLane() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			Region source = regionizer.addChunk(0, 0);

			ThreadOwnership.enterRegion(source);
			AtomicReference<Kind> bodyKind = new AtomicReference<>();
			AtomicReference<Decision> reentry = new AtomicReference<>();
			AtomicInteger runs = new AtomicInteger();
			Decision decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					5000, 5000, key(OVERWORLD, 904), false, () -> {
						runs.incrementAndGet();
						bodyKind.set(ThreadOwnership.current().kind());
						reentry.set(RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
								5000, 5000, key(OVERWORLD, 904), false, () -> { }));
					});

			assertEquals(Decision.DISPATCHED, decision);
			engine.globalScheduler().tick();

			assertEquals(1, runs.get(), "the global-lane body must execute once");
			assertEquals(Kind.GLOBAL, bodyKind.get());
			assertEquals(Decision.INLINE, reentry.get(),
					"a global-lane body must not re-dispatch itself");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void serverThreadSameRegionRunsInline() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			engine.regionizerFor(OVERWORLD).addChunk(0, 0);

			AtomicBoolean ran = new AtomicBoolean();
			Decision decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					0, 0, key(OVERWORLD, 905), true, () -> ran.set(true));

			assertEquals(Decision.INLINE, decision,
					"a same-region server-thread teleport must keep vanilla order");
			assertFalse(ran.get());
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void serverThreadCrossRegionDispatches() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			Region destination = regionizer.addChunk(0, 0);
			regionizer.addChunk(400, 400);

			AtomicReference<Region> bodyRegion = new AtomicReference<>();
			AtomicReference<Thread> bodyThread = new AtomicReference<>();
			CountDownLatch ran = new CountDownLatch(1);
			Decision decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					0, 0, key(OVERWORLD, 906), false, () -> {
						bodyRegion.set(currentRegion());
						bodyThread.set(Thread.currentThread());
						ran.countDown();
					});

			assertEquals(Decision.DISPATCHED, decision,
					"a cross-region server-thread teleport must dispatch, not run inline");
			assertTrue(ran.await(10, TimeUnit.SECONDS));
			assertSame(destination, bodyRegion.get());
			assertNotSame(Thread.currentThread(), bodyThread.get(),
					"the body must execute on the destination region's worker");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void networkContextNeverRunsInline() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			Region destination = regionizer.addChunk(0, 0);
			Region source = regionizer.addChunk(400, 400);

			ThreadOwnership.enterRegion(source);
			ThreadOwnership.Context previous = ThreadOwnership.enterSide(Kind.NETWORK);
			try {
				AtomicReference<Kind> bodyKind = new AtomicReference<>();
				CountDownLatch ran = new CountDownLatch(1);
				Decision decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
						0, 0, key(OVERWORLD, 907), false, () -> {
							bodyKind.set(ThreadOwnership.current().kind());
							ran.countDown();
						});

				assertEquals(Decision.DISPATCHED, decision);
				assertTrue(ran.await(10, TimeUnit.SECONDS));
				assertEquals(Kind.REGION, bodyKind.get(),
						"a NETWORK caller must never mutate the world inline");
				assertSame(destination, regionizer.ownerOfChunk(0, 0));
			} finally {
				ThreadOwnership.exit(previous);
			}
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void rejectedRegionDispatchDefersToServerThread() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			Region source = regionizer.addChunk(400, 400);

			ThreadOwnership.enterRegion(source);
			RegionScheduler scheduler = engine.schedulerFor(OVERWORLD);
			scheduler.close();

			AtomicBoolean ran = new AtomicBoolean();
			Decision decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					0, 0, key(OVERWORLD, 908), false, () -> ran.set(true));

			assertEquals(Decision.DEFERRED_TO_SERVER, decision,
					"a rejected dispatch must never fall back to vanilla on a foreign region thread");
			assertFalse(ran.get());
			assertTrue(ServerThreadDeferral.pendingCount() > 0);

			ThreadOwnership.exit(ThreadOwnership.Context.UNKNOWN);
			ServerThreadDeferral.drainAll();
			assertTrue(ran.get(), "the deferred transition must still execute");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void rejectedAsyncDispatchDefersAndNeverRunsInline() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			engine.regionizerFor(OVERWORLD).addChunk(0, 0);
			engine.schedulerFor(OVERWORLD).close();

			ThreadOwnership.Context previous = ThreadOwnership.enterSide(Kind.ASYNC);
			AtomicBoolean ran = new AtomicBoolean();
			Decision decision;
			try {
				decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
						0, 0, key(OVERWORLD, 909), false, () -> ran.set(true));
			} finally {
				ThreadOwnership.exit(previous);
			}

			assertEquals(Decision.DEFERRED_TO_SERVER, decision);
			assertFalse(ran.get(), "an ASYNC caller must never run the vanilla body inline");
			ServerThreadDeferral.drainAll();
			assertTrue(ran.get());
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void distinctDestinationsInTheSameChunkBothRun() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			Region source = regionizer.addChunk(400, 400);

			ThreadOwnership.enterRegion(source);
			Set<String> executed = Collections.synchronizedSet(new java.util.HashSet<>());
			CountDownLatch both = new CountDownLatch(2);
			Decision first = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					0, 0, key(OVERWORLD, 910), false, () -> {
						executed.add("0.25,64.0,0.25");
						both.countDown();
					});
			Decision second = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					0, 0, key(OVERWORLD, 910), false, () -> {
						executed.add("14.75,70.0,14.75");
						both.countDown();
					});

			assertEquals(Decision.DISPATCHED, first);
			assertEquals(Decision.DISPATCHED, second,
					"a different exact destination in the same chunk must not collapse");
			assertTrue(both.await(10, TimeUnit.SECONDS));
			assertEquals(Set.of("0.25,64.0,0.25", "14.75,70.0,14.75"), executed);
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void twoIdenticalInvocationsBothExecute() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			Region source = regionizer.addChunk(400, 400);

			ThreadOwnership.enterRegion(source);
			AtomicInteger runs = new AtomicInteger();
			CountDownLatch both = new CountDownLatch(2);
			Decision first = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					0, 0, key(OVERWORLD, 911), false, () -> {
						runs.incrementAndGet();
						both.countDown();
					});
			Decision second = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
					0, 0, key(OVERWORLD, 911), false, () -> {
						runs.incrementAndGet();
						both.countDown();
					});

			assertEquals(Decision.DISPATCHED, first);
			assertEquals(Decision.DISPATCHED, second,
					"two independent invocations must not be collapsed");
			assertTrue(both.await(10, TimeUnit.SECONDS));
			assertEquals(2, runs.get(), "both vanilla bodies must execute");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void acceptedTaskFollowsDestinationChunkAfterSplit() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 1, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			RegionScheduler scheduler = engine.schedulerFor(OVERWORLD);

			engine.beginTickPhase();
			for (int x = 0; x <= 151; x++) {
				regionizer.addChunk(x, 0);
			}
			Region parent = regionizer.ownerOfChunk(10, 0);
			Region source = regionizer.addChunk(600, 600);
			assertNotNull(parent);

			CountDownLatch ran = new CountDownLatch(1);
			AtomicReference<Region> executedIn = new AtomicReference<>();
			Decision decision;
			ThreadOwnership.Context previous = ThreadOwnership.enterRegion(source);
			try {
				decision = RegionTransitions.resolveEntityTransition(engine, OVERWORLD,
						10, 0, key(OVERWORLD, 912), false, () -> {
							executedIn.set(currentRegion());
							ran.countDown();
						});
			} finally {
				ThreadOwnership.exit(previous);
			}
			assertEquals(Decision.DISPATCHED, decision);

			List<RegionTaskQueue.Entry> queued = new ArrayList<>(
					scheduler.queueOf(parent).drainEntries());
			assertEquals(1, queued.size());
			assertEquals(RegionScheduler.packChunkPos(10, 0), queued.get(0).chunkPos(),
					"the transition must be queued with its destination position");

			RegionTaskQueue.Entry entry = queued.get(0);
			assertTrue(scheduler.enqueue(parent, entry.task(), entry.chunkPos()));

			for (int x = 24; x <= 119; x++) {
				regionizer.removeChunk(x, 0);
			}
			assertTrue(regionizer.tryBeginTick(parent));
			regionizer.completeTick(parent, 1);
			Region child = regionizer.ownerOfChunk(10, 0);
			assertNotNull(child, "the split must create the destination chunk's new owner");
			assertNotSame(parent, child);
			assertEquals(1, scheduler.queueOf(child).size(),
					"the split must re-home the positioned transition to the owning child");
			assertEquals(0, scheduler.queueOf(parent).size());

			engine.endTickPhase();
			assertTrue(ran.await(10, TimeUnit.SECONDS));
			assertSame(child, executedIn.get(),
					"the transition must execute in the region that now owns its destination");
		} finally {
			engine.endTickPhase();
			engine.shutdown(5000);
		}
	}

	@Test
	void unattachedWorldNeverRunsInlineFromNetwork() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			long unattachedBefore = RegionTransitions.snapshot().unattached();

			ThreadOwnership.Context previous = ThreadOwnership.enterSide(Kind.NETWORK);
			AtomicBoolean ran = new AtomicBoolean();
			Decision decision;
			try {
				decision = RegionTransitions.resolveEntityTransition(engine, NETHER,
						0, 0, key(OVERWORLD, 913), false, () -> ran.set(true));
			} finally {
				ThreadOwnership.exit(previous);
			}

			assertEquals(Decision.DEFERRED_TO_SERVER, decision,
					"an unattached destination must not run vanilla inline on a NETWORK thread");
			assertEquals(unattachedBefore + 1, RegionTransitions.snapshot().unattached());
			assertFalse(ran.get());

			ServerThreadDeferral.drainAll();
			assertTrue(ran.get());
		} finally {
			engine.shutdown(5000);
		}
	}
}
