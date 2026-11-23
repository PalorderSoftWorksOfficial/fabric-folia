package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.metrics.RegionMetrics;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSliceAtomicityTest {

	private static final UUID PLAYER_ID = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final int MIN_PARALLEL_DEFAULT = 32;
	private static final int MIN_DISPATCH_DEFAULT = 64;

	@AfterEach
	void cleanup() {
		ThreadOwnership.clear();
		ServerThreadDeferral.clearAll();
		ChunkResidency.clearAll();
		RegionStageHub.clearPlayerTickTargets();
		RegionStageHub.MIN_PARALLEL_BODIES = MIN_PARALLEL_DEFAULT;
		RegionStageHub.MIN_DISPATCH_TOTAL = MIN_DISPATCH_DEFAULT;
	}

	private static FabricFoliaEngine bootEngine() throws Exception {
		Path dir = Files.createTempDirectory("folia-player-atomicity");
		FoliaConfig config = FoliaConfig.at(dir.resolve("fabric-folia.yml"));
		config.load();
		config.freeze();
		return FabricFoliaEngine.bootstrap(config, message -> { }, message -> { });
	}

	private static void markNeighborhood(String world, int chunkX, int chunkZ) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				ChunkResidency.markResident(world, chunkX + dx, chunkZ + dz);
			}
		}
	}

	private static void dispatchEverything() {
		RegionStageHub.MIN_PARALLEL_BODIES = 0;
		RegionStageHub.MIN_DISPATCH_TOTAL = 1;
	}

	private static RegionStageHub hub(WorldRegionizer regionizer, RegionScheduler scheduler) {
		return new RegionStageHub(OVERWORLD, regionizer, scheduler, new RegionMetrics(1), message -> { });
	}

	private static final class RecordingBody implements RegionStageHub.Positioned, Runnable {
		private final String label;
		private final ChunkPos position;
		private final List<String> order;
		private final List<ThreadOwnership.Context> contexts;
		private final CountDownLatch done;

		private RecordingBody(String label, ChunkPos position, List<String> order,
		                      List<ThreadOwnership.Context> contexts, CountDownLatch done) {
			this.label = label;
			this.position = position;
			this.order = order;
			this.contexts = contexts;
			this.done = done;
		}

		@Override
		public ChunkPos fabricfolia$position() {
			return position;
		}

		@Override
		public void run() {
			synchronized (order) {
				order.add(label);
				contexts.add(ThreadOwnership.current());
			}
			done.countDown();
		}
	}

	private static int awaitPendingBodies(RegionStageHub hub) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (hub.pendingBodies() != 0 && System.nanoTime() < deadline) {
			Thread.sleep(2);
		}
		return hub.pendingBodies();
	}

	@Test
	void bothPlayerSlicesExecuteInTheOwnerPinnedByTheEntityCapture() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			Region near = regionizer.addChunk(0, 0);
			Region far = regionizer.addChunk(4000, 0);
			assertNotEquals(near, far, "the fixture needs two separate regions");
			RegionScheduler scheduler = engine.schedulerFor(OVERWORLD);
			RegionStageHub hub = hub(regionizer, scheduler);
			markNeighborhood(OVERWORLD, 0, 0);
			markNeighborhood(OVERWORLD, 4000, 0);
			dispatchEverything();

			List<String> order = new ArrayList<>();
			List<ThreadOwnership.Context> contexts = new ArrayList<>();
			CountDownLatch done = new CountDownLatch(2);

			assertTrue(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0),
							RegionStageHub.Slice.ENTITY,
							position -> new RecordingBody("entity", position, order, contexts, done)),
					"the entity slice stages the player in the near region");

			assertTrue(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(4000, 0),
							RegionStageHub.Slice.PLAYER,
							position -> new RecordingBody("connection", position, order, contexts, done)),
					"the connection slice reuses the entity slice's staged decision");

			hub.flushStaged();
			assertTrue(done.await(15, TimeUnit.SECONDS), "both staged player bodies must execute");

			assertEquals(List.of("entity", "connection"), order,
					"vanilla intra-tick order (entity pass, then tickConnection) must survive");
			for (ThreadOwnership.Context context : contexts) {
				assertEquals(ThreadContext.Kind.REGION, context.kind(),
						"a staged player body runs in the REGION context");
				assertEquals(OVERWORLD, context.region().world());
				assertEquals(near.regionId(), context.region().regionId(),
						"both player slices must execute in the entity-pass owner's region");
			}
			assertEquals(0, awaitPendingBodies(hub), "both bodies tick exactly once");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void connectionSliceStaysInlineWhenTheEntitySliceCannotStage() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			regionizer.addChunk(4000, 0);
			RegionScheduler scheduler = engine.schedulerFor(OVERWORLD);
			RegionStageHub hub = hub(regionizer, scheduler);
			markNeighborhood(OVERWORLD, 4000, 0);
			dispatchEverything();

			CountDownLatch none = new CountDownLatch(2);
			List<String> order = new ArrayList<>();
			List<ThreadOwnership.Context> contexts = new ArrayList<>();

			assertFalse(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0),
							RegionStageHub.Slice.ENTITY,
							position -> new RecordingBody("entity", position, order, contexts, none)),
					"an unloaded neighborhood keeps the entity body on the server thread");
			assertFalse(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(4000, 0),
							RegionStageHub.Slice.PLAYER,
							position -> new RecordingBody("connection", position, order, contexts, none)),
					"the connection body must not run alone when the entity body did not stage");
			assertEquals(0, hub.pendingBodies(), "no half-tick may be queued");
			assertTrue(order.isEmpty(), "nothing may execute on a worker in this tick");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void backlogSuppressionAtTheEntityCaptureKeepsThePairInline() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			RegionScheduler scheduler = engine.schedulerFor(OVERWORLD);
			RegionStageHub hub = hub(regionizer, scheduler);
			markNeighborhood(OVERWORLD, 0, 0);
			dispatchEverything();

			List<String> order = new ArrayList<>();
			List<ThreadOwnership.Context> contexts = new ArrayList<>();
			CountDownLatch none = new CountDownLatch(2);

			hub.setStagingSuppressedForTests(true);
			assertFalse(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0),
							RegionStageHub.Slice.ENTITY,
							position -> new RecordingBody("entity", position, order, contexts, none)),
					"backpressure refuses the entity body");
			hub.setStagingSuppressedForTests(false);
			assertFalse(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0),
							RegionStageHub.Slice.PLAYER,
							position -> new RecordingBody("connection", position, order, contexts, none)),
					"the tick's single decision stands even if backpressure clears mid-tick");
			assertEquals(0, hub.pendingBodies());
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void dimensionChangeBetweenCapturesRunsBothBodiesInlineInsteadOfStagingAcrossWorlds() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			engine.attachWorld(NETHER, 10, null);
			WorldRegionizer overworld = engine.regionizerFor(OVERWORLD);
			WorldRegionizer nether = engine.regionizerFor(NETHER);
			overworld.addChunk(0, 0);
			nether.addChunk(0, 0);
			RegionStageHub overworldHub = hub(overworld, engine.schedulerFor(OVERWORLD));
			RegionStageHub netherHub = new RegionStageHub(NETHER, nether,
					engine.schedulerFor(NETHER), new RegionMetrics(1), message -> { });
			markNeighborhood(OVERWORLD, 0, 0);
			markNeighborhood(NETHER, 0, 0);
			dispatchEverything();

			List<String> order = new ArrayList<>();
			List<ThreadOwnership.Context> contexts = new ArrayList<>();
			CountDownLatch reclaimed = new CountDownLatch(1);
			long changesBefore = RegionStageHub.playerTickWorldChanges();

			assertTrue(overworldHub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0),
					RegionStageHub.Slice.ENTITY,
					position -> new RecordingBody("entity", position, order, contexts, reclaimed)),
					"the entity slice stages the player before the dimension change");

			assertFalse(netherHub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0),
					RegionStageHub.Slice.PLAYER,
					position -> new RecordingBody("connection", position, order, contexts, reclaimed)),
					"a cross-world pin is never reused as an owner in the new world");

			assertEquals(List.of("entity"), order,
					"the pinned entity body is reclaimed inline; the connection body stays vanilla");
			assertTrue(contexts.get(0).kind() != ThreadContext.Kind.REGION,
					"the reclaimed entity body must not run in a REGION context");
			assertEquals(changesBefore + 1, RegionStageHub.playerTickWorldChanges(),
					"the cross-world case must be observable");

			overworldHub.flushStaged();
			netherHub.flushStaged();
			assertEquals(List.of("entity"), order,
					"the reclaimed body must run exactly once (no lost tick, no duplicate)");
			assertEquals(0, overworldHub.pendingBodies());
			assertEquals(0, netherHub.pendingBodies());
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void theNextTickRepinsThePlayersCurrentChunk() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			Region near = regionizer.addChunk(0, 0);
			Region far = regionizer.addChunk(4000, 0);
			RegionScheduler scheduler = engine.schedulerFor(OVERWORLD);
			RegionStageHub hub = hub(regionizer, scheduler);
			markNeighborhood(OVERWORLD, 0, 0);
			markNeighborhood(OVERWORLD, 4000, 0);
			dispatchEverything();

			List<String> order = new ArrayList<>();
			List<ThreadOwnership.Context> contexts = new ArrayList<>();

			CountDownLatch firstTick = new CountDownLatch(1);
			assertTrue(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0),
					RegionStageHub.Slice.ENTITY,
					position -> new RecordingBody("first", position, order, contexts, firstTick)));
			hub.flushStaged();
			assertTrue(firstTick.await(15, TimeUnit.SECONDS), "the first tick's body must execute");
			assertEquals(near.regionId(), contexts.get(0).region().regionId());

			CountDownLatch secondTick = new CountDownLatch(1);
			assertTrue(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(4000, 0),
					RegionStageHub.Slice.ENTITY,
					position -> new RecordingBody("second", position, order, contexts, secondTick)));
			hub.flushStaged();
			assertTrue(secondTick.await(15, TimeUnit.SECONDS),
					"the pin must not outlive its tick (no lost player tick)");
			assertEquals(far.regionId(), contexts.get(contexts.size() - 1).region().regionId(),
					"the next tick follows the player to its new chunk");
			assertEquals(List.of("first", "second"), order);
		} finally {
			engine.shutdown(5000);
		}
	}
}
