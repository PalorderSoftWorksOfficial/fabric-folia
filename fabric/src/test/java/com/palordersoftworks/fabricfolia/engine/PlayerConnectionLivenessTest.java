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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerConnectionLivenessTest {

	private static final UUID PLAYER_ID = UUID.fromString("1a2b3c4d-5e6f-4708-9a0b-1c2d3e4f5061");
	private static final String OVERWORLD = "minecraft:overworld";
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
		Path dir = Files.createTempDirectory("folia-player-liveness");
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
		private final ChunkPos position;
		private final List<String> order;
		private final List<ThreadOwnership.Context> contexts;
		private final AtomicBoolean done = new AtomicBoolean();

		private RecordingBody(String label, ChunkPos position, List<String> order,
		                      List<ThreadOwnership.Context> contexts) {
			this.position = position;
			this.order = order;
			this.contexts = contexts;
			this.label = label;
		}

		private final String label;

		@Override
		public ChunkPos fabricfolia$position() {
			return position;
		}

		@Override
		public void run() {
			order.add(label);
			contexts.add(ThreadOwnership.current());
			done.set(true);
		}
	}

	private static final class PlainBody implements Runnable {
		private final List<String> order;
		private final List<ThreadOwnership.Context> contexts;

		private PlainBody(List<String> order, List<ThreadOwnership.Context> contexts) {
			this.order = order;
			this.contexts = contexts;
		}

		@Override
		public void run() {
			order.add("connection");
			contexts.add(ThreadOwnership.current());
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
	void liveConnectionBodyStillRunsOnTheOwnerWorker() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			Region region = regionizer.addChunk(0, 0);
			RegionStageHub hub = hub(regionizer, engine.schedulerFor(OVERWORLD));
			markNeighborhood(OVERWORLD, 0, 0);
			dispatchEverything();

			List<String> order = new CopyOnWriteArrayList<>();
			List<ThreadOwnership.Context> contexts = new CopyOnWriteArrayList<>();
			PlainBody body = new PlainBody(order, contexts);
			long deferredBefore = RegionPlayerRouting.deferredConnectionTicks();

			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.PLAYER,
							new RegionPlayerRouting.StagedConnectionBody(new ChunkPos(0, 0),
									() -> true, () -> true, body)),
					"a live connection body stages normally");

			hub.flushStaged();
			assertEquals(0, awaitPendingBodies(hub), "the body must complete");
			assertEquals(List.of("connection"), order, "a live connection ticks exactly once");
			assertEquals(ThreadContext.Kind.REGION, contexts.get(0).kind(),
					"a live connection body runs on its region worker");
			assertEquals(region.regionId(), contexts.get(0).region().regionId());
			assertEquals(deferredBefore, RegionPlayerRouting.deferredConnectionTicks(),
					"a live connection is never deferred");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void closedTransportAtExecutionDefersTheAdmittedBodyExactlyOnce() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			RegionStageHub hub = hub(regionizer, engine.schedulerFor(OVERWORLD));
			markNeighborhood(OVERWORLD, 0, 0);
			dispatchEverything();

			List<String> order = new CopyOnWriteArrayList<>();
			List<ThreadOwnership.Context> contexts = new CopyOnWriteArrayList<>();
			PlainBody body = new PlainBody(order, contexts);
			AtomicBoolean connected = new AtomicBoolean(true);
			long closedBefore = RegionPlayerRouting.closedTransportDeferredTicks();
			long invalidBefore = RegionPlayerRouting.invalidPlayerDeferredTicks();
			long queuedBefore = ServerThreadDeferral.deferred();

			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.PLAYER,
							new RegionPlayerRouting.StagedConnectionBody(new ChunkPos(0, 0),
									connected::get, () -> true, body)),
					"the connection body stages while the transport is open");
			connected.set(false);

			hub.flushStaged();
			assertEquals(0, awaitPendingBodies(hub));
			assertTrue(order.isEmpty(), "a closed transport must not tick on the region worker");
			assertEquals(closedBefore + 1, RegionPlayerRouting.closedTransportDeferredTicks(),
					"the already-admitted tick is deferred, not dropped");
			assertEquals(invalidBefore, RegionPlayerRouting.invalidPlayerDeferredTicks());
			assertEquals(queuedBefore + 1, ServerThreadDeferral.deferred());
			assertEquals(1, ServerThreadDeferral.pendingCount(), "exactly one body waits for the server thread");

			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			assertEquals(1, ServerThreadDeferral.drainAll(), "the admitted body runs once on the server thread");
			assertEquals(List.of("connection"), order, "exactly one connection tick this tick");
			assertNotEquals(ThreadContext.Kind.REGION, contexts.get(0).kind());
			assertEquals(0, ServerThreadDeferral.pendingCount(), "nothing may remain queued");

			hub.flushStaged();
			assertEquals(0, ServerThreadDeferral.drainAll());
			assertEquals(List.of("connection"), order, "no second tick for the same admission");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void closedTransportTakesPrecedenceOverPlayerInvalidity() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			RegionStageHub hub = hub(regionizer, engine.schedulerFor(OVERWORLD));
			markNeighborhood(OVERWORLD, 0, 0);
			dispatchEverything();

			List<String> order = new CopyOnWriteArrayList<>();
			List<ThreadOwnership.Context> contexts = new CopyOnWriteArrayList<>();
			PlainBody body = new PlainBody(order, contexts);
			long closedBefore = RegionPlayerRouting.closedTransportDeferredTicks();
			long invalidBefore = RegionPlayerRouting.invalidPlayerDeferredTicks();

			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.PLAYER,
							new RegionPlayerRouting.StagedConnectionBody(new ChunkPos(0, 0),
									() -> false, () -> false, body)));
			hub.flushStaged();
			assertEquals(0, awaitPendingBodies(hub));

			assertEquals(closedBefore + 1, RegionPlayerRouting.closedTransportDeferredTicks(),
					"the transport verdict wins");
			assertEquals(invalidBefore, RegionPlayerRouting.invalidPlayerDeferredTicks(),
					"one deferral per body, counted once");
			assertTrue(order.isEmpty());
			assertEquals(1, ServerThreadDeferral.pendingCount());

			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			assertEquals(1, ServerThreadDeferral.drainAll());
			assertEquals(List.of("connection"), order, "the body still runs exactly once");
			assertEquals(0, ServerThreadDeferral.pendingCount());
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void invalidPlayerOnOpenTransportDefersToTheServerThread() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			RegionStageHub hub = hub(regionizer, engine.schedulerFor(OVERWORLD));
			markNeighborhood(OVERWORLD, 0, 0);
			dispatchEverything();

			List<String> order = new CopyOnWriteArrayList<>();
			List<ThreadOwnership.Context> contexts = new CopyOnWriteArrayList<>();
			PlainBody body = new PlainBody(order, contexts);
			AtomicBoolean playerValid = new AtomicBoolean(true);
			long closedBefore = RegionPlayerRouting.closedTransportDeferredTicks();
			long invalidBefore = RegionPlayerRouting.invalidPlayerDeferredTicks();
			long queuedBefore = ServerThreadDeferral.deferred();

			assertTrue(hub.stageIfRoom(RegionStageHub.Slice.PLAYER,
							new RegionPlayerRouting.StagedConnectionBody(new ChunkPos(0, 0),
									() -> true, playerValid::get, body)));
			playerValid.set(false);

			hub.flushStaged();
			assertEquals(0, awaitPendingBodies(hub));
			assertTrue(order.isEmpty(), "an invalid player must not tick on the region worker");
			assertEquals(closedBefore, RegionPlayerRouting.closedTransportDeferredTicks(),
					"the transport is still open, so this is not a closed-transport deferral");
			assertEquals(invalidBefore + 1, RegionPlayerRouting.invalidPlayerDeferredTicks());
			assertEquals(queuedBefore + 1, ServerThreadDeferral.deferred());

			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			assertEquals(1, ServerThreadDeferral.drainAll());
			assertEquals(List.of("connection"), order,
					"vanilla's connection body runs exactly once, on the server thread");
			assertNotEquals(ThreadContext.Kind.REGION, contexts.get(0).kind());
			assertEquals(0, ServerThreadDeferral.pendingCount());
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void stagedEntityBodyIsReclaimedInlineWhenTheConnectionStageRefuses() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			RegionStageHub hub = hub(regionizer, engine.schedulerFor(OVERWORLD));
			markNeighborhood(OVERWORLD, 0, 0);
			dispatchEverything();

			List<String> order = new CopyOnWriteArrayList<>();
			List<ThreadOwnership.Context> contexts = new CopyOnWriteArrayList<>();
			long reclaimedBefore = RegionStageHub.playerEntityReclaimed();

			assertTrue(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0), RegionStageHub.Slice.ENTITY,
							position -> new RecordingBody("entity", position, order, contexts)),
					"the entity pass stages the player body");
			assertTrue(order.isEmpty(), "the staged body has not run yet");

			assertTrue(hub.reclaimPlayerEntityBody(PLAYER_ID),
					"a disconnected player's staged body is reclaimed");
			assertEquals(List.of("entity"), order, "the body runs inline exactly once");
			assertNotEquals(ThreadContext.Kind.REGION, contexts.get(0).kind(),
					"the reclaimed body runs on the server thread");
			assertEquals(reclaimedBefore + 1, RegionStageHub.playerEntityReclaimed());

			hub.flushStaged();
			assertEquals(List.of("entity"), order, "the flush must not run the reclaimed body again");
			assertEquals(0, hub.pendingBodies());
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void stagedEntityBodyIsCanceledWhenThePlayerIsAlreadyRemoved() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			RegionStageHub hub = hub(regionizer, engine.schedulerFor(OVERWORLD));
			markNeighborhood(OVERWORLD, 0, 0);
			dispatchEverything();

			List<String> order = new CopyOnWriteArrayList<>();
			List<ThreadOwnership.Context> contexts = new CopyOnWriteArrayList<>();
			long canceledBefore = RegionStageHub.playerEntityCanceled();

			assertTrue(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0), RegionStageHub.Slice.ENTITY,
					position -> new RecordingBody("entity", position, order, contexts)));

			assertTrue(hub.cancelPlayerEntityBody(PLAYER_ID),
					"a removed player's staged body is discarded");
			assertEquals(canceledBefore + 1, RegionStageHub.playerEntityCanceled());

			hub.flushStaged();
			assertTrue(order.isEmpty(), "a removed player must not continue staged ticking");
			assertEquals(0, hub.pendingBodies(), "no body may be left in flight");
			assertFalse(hub.cancelPlayerEntityBody(PLAYER_ID),
					"the pin is consumed exactly once");
		} finally {
			engine.shutdown(5000);
		}
	}

	@Test
	void livenessReleaseClearsThePinSoTheNextTickRePins() throws Exception {
		FabricFoliaEngine engine = bootEngine();
		try {
			engine.attachWorld(OVERWORLD, 10, null);
			WorldRegionizer regionizer = engine.regionizerFor(OVERWORLD);
			regionizer.addChunk(0, 0);
			regionizer.addChunk(4000, 0);
			RegionStageHub hub = hub(regionizer, engine.schedulerFor(OVERWORLD));
			markNeighborhood(OVERWORLD, 0, 0);
			markNeighborhood(OVERWORLD, 4000, 0);
			dispatchEverything();

			List<String> order = new CopyOnWriteArrayList<>();
			List<ThreadOwnership.Context> contexts = new CopyOnWriteArrayList<>();

			assertTrue(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(0, 0), RegionStageHub.Slice.ENTITY,
					position -> new RecordingBody("first", position, order, contexts)));
			assertTrue(hub.cancelPlayerEntityBody(PLAYER_ID));

			assertTrue(hub.stagePlayerTick(PLAYER_ID, new ChunkPos(4000, 0), RegionStageHub.Slice.ENTITY,
							position -> new RecordingBody("second", position, order, contexts)),
					"the next tick re-pins instead of reusing a stale decision");
			hub.flushStaged();
			assertEquals(0, awaitPendingBodies(hub));
			assertEquals(List.of("second"), order, "only the re-pinned body may run");
		} finally {
			engine.shutdown(5000);
		}
	}
}
