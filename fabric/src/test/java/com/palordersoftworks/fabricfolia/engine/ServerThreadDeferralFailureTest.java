package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerThreadDeferralFailureTest {

	@BeforeEach
	@AfterEach
	void reset() {
		ServerThreadDeferral.clearAll();
		ServerThreadDeferral.noteServerThread(null);
		ServerThreadDeferral.installDiagnostics(null);
	}

	@Test
	void failingMutationThrowsTheOriginalAndStopsLaterWork() {
		List<String> ran = new ArrayList<>();
		List<String> reports = new ArrayList<>();
		ServerThreadDeferral.installDiagnostics(reports::add);
		long failedBefore = ServerThreadDeferral.failed();
		ServerThreadDeferral.defer(() -> ran.add("before"));
		IllegalStateException boom = new IllegalStateException("boom");
		ServerThreadDeferral.defer(() -> {
			throw boom;
		});
		ServerThreadDeferral.defer(() -> ran.add("after"));

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				ServerThreadDeferral::drainAll);

		assertSame(boom, thrown, "the original throwable must reach the tick, not a copy");
		assertEquals(List.of("before"), ran, "work behind a failed mutation must not run");
		assertEquals(failedBefore + 1, ServerThreadDeferral.failed());
		assertEquals(1, ServerThreadDeferral.pendingCount());
		assertEquals(1, reports.size());
	}

	@Test
	void partialMutationIsNotFollowedByLaterWork() {
		List<String> world = new ArrayList<>();
		ServerThreadDeferral.defer(() -> {
			world.add("block-set");
			throw new IllegalStateException("chunk state torn after the write");
		});
		ServerThreadDeferral.defer(() -> world.add("neighbour-update"));

		assertThrows(IllegalStateException.class, ServerThreadDeferral::drainAll);

		assertEquals(List.of("block-set"), world,
				"a torn mutation must be visible and must not be papered over by later work");
		assertEquals(1, ServerThreadDeferral.pendingCount());
	}

	@Test
	void failedReplayPoisonsBothLanesUntilTheFailureIsTaken() {
		List<String> ran = new ArrayList<>();
		RuntimeException boom = new RuntimeException("boom");
		ServerThreadDeferral.defer(() -> {
			throw boom;
		});
		ServerThreadDeferral.deferBarrierSafe(() -> {
			ran.add("barrier");
			return CompletableFuture.completedFuture(null);
		});

		assertThrows(RuntimeException.class, ServerThreadDeferral::drainAll);
		assertEquals(0, ServerThreadDeferral.drainAll(),
				"a lane with an untaken failure must not apply more mutations");
		assertEquals(0, ServerThreadDeferral.drainBarrierSafe(10));
		assertTrue(ran.isEmpty());

		assertSame(boom, ServerThreadDeferral.takeReplayFailure());
		assertNull(ServerThreadDeferral.replayFailure());
		assertEquals(1, ServerThreadDeferral.drainBarrierSafe(10),
				"taking the failure re-opens the lane");
		assertEquals(List.of("barrier"), ran);
		assertEquals(0, ServerThreadDeferral.pendingCount());
	}

	@Test
	void barrierSafeFailurePropagatesAndPoisonsTheOrdinaryLane() {
		List<String> ran = new ArrayList<>();
		ServerThreadDeferral.defer(() -> ran.add("handoff"));
		ServerThreadDeferral.deferBarrierSafe(() -> {
			throw new IllegalStateException("ticket load failed");
		});

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> ServerThreadDeferral.drainBarrierSafe(10));

		assertEquals("ticket load failed", thrown.getMessage());
		assertEquals(0, ServerThreadDeferral.drainBarrierSafe(10));
		assertTrue(ran.isEmpty());
		assertEquals(0, ServerThreadDeferral.drainAll(),
				"the ordinary lane stays poisoned until the failure is taken");
		ServerThreadDeferral.takeReplayFailure();
		assertEquals(1, ServerThreadDeferral.drainAll());
		assertEquals(List.of("handoff"), ran);
	}

	@Test
	void tickPhaseSurfacesAFailureTheQuiescePumpSwallowed() throws Exception {
		Path dir = Files.createTempDirectory("folia-deferral-failure");
		FoliaConfig config = FoliaConfig.at(dir.resolve("fabric-folia.yml"));
		config.load();
		config.freeze();
		FabricFoliaEngine engine = FabricFoliaEngine.bootstrap(config, msg -> { }, msg -> { });
		try {
			AtomicInteger pumpSwallowed = new AtomicInteger();
			IllegalStateException boom = new IllegalStateException("deferred mutation failed under the pump");

			ServerThreadDeferral.deferBarrierSafe(() -> {
				throw boom;
			});
			try {
				ServerThreadDeferral.drainBarrierSafe(10);
			} catch (IllegalStateException expected) {
				pumpSwallowed.incrementAndGet();
			}
			assertEquals(1, pumpSwallowed.get(),
					"the drain itself must propagate; only the pool pump may hide it");

			IllegalStateException surfaced = assertThrows(IllegalStateException.class,
					engine::beginTickPhase);
			assertSame(boom, surfaced,
					"the tick must fail with the deferred failure instead of continuing on torn state");
			assertNull(ServerThreadDeferral.replayFailure(), "surfacing must consume the failure");
		} finally {
			engine.shutdown(5000);
		}
	}
}
