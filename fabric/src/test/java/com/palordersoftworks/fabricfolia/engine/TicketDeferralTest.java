/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.config.FoliaConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket-deferral regression tests for the production crash signature
 * (Palorder Central, 2026-09-25): worker-context portal / ender-pearl ticket
 * placements must never mutate vanilla's ticket/tracker maps directly — they
 * defer, and the owning world's server-thread tick replays them. These tests
 * pin the deferral contract: per-world scoping, freshness-over-FIFO
 * collapsing, suppression drops, detach cleanup, and the real-engine drain
 * supplier the mixin wires.
 */
class TicketDeferralTest {

	/**
	 * {@code null} ticket type: the deferral never dereferences it (it is
	 * carried opaquely into the replay lambda), and TOUCHING TicketType in a
	 * non-bootstrapped test JVM explodes in vanilla's registry clinit —
	 * production always carries the real record.
	 */
	private static TicketDeferral.Placement placement(String world, long chunk, int radius) {
		return new TicketDeferral.Placement(world, chunk, null, radius, -1);
	}

	@AfterEach
	void clearPending() {
		TicketDeferral.clearWorld("test:world-a");
		TicketDeferral.clearWorld("test:world-b");
		TicketDeferral.clearWorld("minecraft:overworld");
	}

	@Test
	void deferredPlacementsReplayOnTheirOwnWorldsTickOnly() {
		TicketDeferral.defer(placement("test:world-a", 100L, 3));
		TicketDeferral.defer(placement("test:world-a", 200L, 3));
		TicketDeferral.defer(placement("test:world-b", 300L, 2));

		List<TicketDeferral.Placement> applied = new ArrayList<>();
		TicketDeferral.drain("test:world-a", applied::add, () -> false);

		assertEquals(2, applied.size(), "world A's tick replays exactly world A's placements");
		assertEquals(100L, applied.get(0).packedChunk());
		assertEquals(200L, applied.get(1).packedChunk());
		assertEquals(1, TicketDeferral.pendingCount(),
				"world B's placement waits for world B's tick");
		assertEquals(3, TicketDeferral.deferred());
		assertEquals(2, TicketDeferral.drained());

		TicketDeferral.drain("test:world-b", applied::add, () -> false);
		assertEquals(3, applied.size());
		assertEquals(300L, applied.get(2).packedChunk());
		assertEquals(0, TicketDeferral.pendingCount());
	}

	@Test
	void duplicatePlacementCollapsesToTheLatest() {
		// A portal storm re-placing the same (chunk, type) ticket every tick
		// must not grow the pending map: the newest placement wins, older ones
		// vanish — vanilla's add is idempotent for the same (type, level), so
		// collapsing is behaviorally identical.
		long deferredBefore = TicketDeferral.deferred();
		long drainedBefore = TicketDeferral.drained();
		TicketDeferral.defer(placement("test:world-a", 42L, 1));
		TicketDeferral.defer(placement("test:world-a", 42L, 4));
		TicketDeferral.defer(placement("test:world-a", 42L, 4));

		List<TicketDeferral.Placement> applied = new ArrayList<>();
		TicketDeferral.drain("test:world-a", applied::add, () -> false);

		assertEquals(1, applied.size(), "collapses to one replay");
		assertEquals(4, applied.get(0).radius(), "the LATEST placement replays");
		assertEquals(deferredBefore + 3, TicketDeferral.deferred(), "every defer is counted");
		assertEquals(drainedBefore + 1, TicketDeferral.drained(), "only one replay happened");
	}

	@Test
	void suppressionDropsPendingPlacementsWithoutReplaying() {
		TicketDeferral.defer(placement("test:world-a", 7L, 3));
		TicketDeferral.defer(placement("test:world-b", 8L, 2));

		AtomicInteger applied = new AtomicInteger();
		TicketDeferral.drain("test:world-a", p -> applied.incrementAndGet(), () -> true);

		assertEquals(0, applied.get(), "a suppressed session must never replay");
		assertEquals(0, TicketDeferral.pendingCount(), "suppression DROPS the pending work");

		// The world-b placement was dropped too (global suppression): a later
		// healthy drain has nothing to replay.
		TicketDeferral.drain("test:world-b", p -> applied.incrementAndGet(), () -> false);
		assertEquals(0, applied.get());
	}

	@Test
	void clearWorldDropsOnlyThatWorldsPlacements() {
		TicketDeferral.defer(placement("test:world-a", 11L, 3));
		TicketDeferral.defer(placement("test:world-b", 12L, 2));

		TicketDeferral.clearWorld("test:world-a");

		List<TicketDeferral.Placement> applied = new ArrayList<>();
		TicketDeferral.drain("test:world-a", applied::add, () -> false);
		assertEquals(0, applied.size(), "cleared world has nothing to replay");
		TicketDeferral.drain("test:world-b", applied::add, () -> false);
		assertEquals(1, applied.size(), "the other world's placement survives");
		assertEquals(12L, applied.get(0).packedChunk());
	}

	@Test
	void realEngineDrainUsesTheEngineSuppressionSupplier() throws Exception {
		// The exact supplier wiring the mixin uses: an attached, unsuppressed
		// engine's randomTickInterceptSuppressed() gates the replay.
		Path dir = Files.createTempDirectory("folia-ticket");
		FoliaConfig config = FoliaConfig.at(dir.resolve("fabric-folia.yml"));
		config.load();
		config.freeze();
		FabricFoliaEngine engine = FabricFoliaEngine.bootstrap(config, msg -> { }, msg -> { });
		try {
			engine.attachWorld("minecraft:overworld", 10, null);

			TicketDeferral.defer(placement("minecraft:overworld", 64L, 3));
			List<TicketDeferral.Placement> applied = new ArrayList<>();
			TicketDeferral.drain("minecraft:overworld", applied::add,
					() -> engine.randomTickInterceptSuppressed());
			assertEquals(1, applied.size(), "unsuppressed engine drains normally");
			assertEquals(64L, applied.get(0).packedChunk());
		} finally {
			engine.shutdown(5000);
		}
		assertEquals(0, TicketDeferral.pendingCount(),
				"engine shutdown cleared the world's pending placements");
	}
}
