/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retry-ledger contract for transient concurrent-modification failures in
 * staged bodies (the c2me "Async entity load" production signature, Palorder
 * Central 2026-09-25): a body that failed must be retried tick-spaced, never
 * silently swallowed; the attempt cap stops pathological loops; suppression
 * windows keep bodies pending instead of forcing them through; drops are
 * counted, never silent.
 */
class StageRetryTest {

	private static final String WORLD_A = "test:retry-a";
	private static final String WORLD_B = "test:retry-b";

	private final AtomicLong tick = new AtomicLong(100);

	@AfterEach
	void clearPending() {
		StageRetry.clearWorld(WORLD_A);
		StageRetry.clearWorld(WORLD_B);
	}

	@Test
	void failedBodyRetriesAfterTickSpacingOnItsOwnWorldOnly() {
		AtomicInteger runs = new AtomicInteger();
		long scheduledBefore = StageRetry.scheduled();
		long retriedBefore = StageRetry.retried();

		StageRetry.schedule(WORLD_A, runs::incrementAndGet, 0, tick::get);

		assertEquals(scheduledBefore + 1, StageRetry.scheduled(), "schedule must accept the failed body");

		// Not yet due (spacing is RETRY_SPACING_TICKS): drain must not run it.
		StageRetry.drain(WORLD_A, () -> false, (w, b, a) -> {
			b.run();
			return true;
		}, tick::get);
		assertEquals(0, runs.get(), "a body must not retry before its tick spacing elapses");

		// Advance past the spacing: due now, and only world A's drain sees it.
		tick.addAndGet(StageRetry.RETRY_SPACING_TICKS);
		StageRetry.drain(WORLD_B, () -> false, (w, b, a) -> {
			b.run();
			return true;
		}, tick::get);
		assertEquals(0, runs.get(), "a foreign world's drain must not replay world A's retry");

		StageRetry.drain(WORLD_A, () -> false, (w, b, a) -> {
			assertEquals(1, a, "the retry must carry attempt+1 from the failed attempt");
			b.run();
			return true;
		}, tick::get);
		assertEquals(1, runs.get(), "the owning world's drain must replay the due retry");
		assertEquals(retriedBefore + 1, StageRetry.retried(), "successful requeues are counted");
		assertEquals(0, StageRetry.pendingCount(), "no residue after a successful retry");
	}

	@Test
	void attemptCapStopsTheLoopAndCountsTheExhaustion() {
		long exhaustedBefore = StageRetry.exhausted();
		AtomicInteger accepted = new AtomicInteger();

		// The LAST allowed attempt just failed: scheduling again must be refused.
		StageRetry.schedule(WORLD_A, accepted::incrementAndGet, StageRetry.MAX_ATTEMPTS, tick::get);

		assertEquals(0, accepted.get(), "past the attempt cap nothing may be scheduled");
		assertEquals(exhaustedBefore + 1, StageRetry.exhausted(),
				"cap refusals must be counted, never silent");
	}

	@Test
	void suppressionWindowKeepsBodiesPendingInsteadOfForcingThem() {
		AtomicInteger runs = new AtomicInteger();
		long retriedBefore = StageRetry.retried();

		StageRetry.schedule(WORLD_A, runs::incrementAndGet, 0, tick::get);
		tick.addAndGet(StageRetry.RETRY_SPACING_TICKS);

		StageRetry.drain(WORLD_A, () -> true, (w, b, a) -> {
			b.run();
			return true;
		}, tick::get);

		assertEquals(0, runs.get(), "a suppressed window must not execute retries");
		assertEquals(retriedBefore, StageRetry.retried(), "a suppressed window must not count retries");
		assertTrue(StageRetry.pendingCount() > 0, "the body must stay pending through suppression");

		// Suppression lifts: the very next drain replays it.
		StageRetry.drain(WORLD_A, () -> false, (w, b, a) -> {
			b.run();
			return true;
		}, tick::get);
		assertEquals(1, runs.get(), "the body must run once suppression lifts");
	}

	@Test
	void refusedRequeueStaysPendingForTheNextFlush() {
		AtomicInteger runs = new AtomicInteger();
		long retriedBefore = StageRetry.retried();

		StageRetry.schedule(WORLD_A, runs::incrementAndGet, 0, tick::get);
		tick.addAndGet(StageRetry.RETRY_SPACING_TICKS);

		StageRetry.drain(WORLD_A, () -> false, (w, b, a) -> false, tick::get);

		assertEquals(retriedBefore, StageRetry.retried(), "a refused requeue is not a retry");
		assertTrue(StageRetry.pendingCount() > 0, "a refused requeue must stay pending");

		StageRetry.drain(WORLD_A, () -> false, (w, b, a) -> {
			b.run();
			return true;
		}, tick::get);
		assertEquals(1, runs.get(), "the next flush must retry the refused body");
	}

	@Test
	void clearWorldDropsPendingRetriesAndCountsThem() {
		long exhaustedBefore = StageRetry.exhausted();
		StageRetry.schedule(WORLD_A, () -> { }, 0, tick::get);
		StageRetry.schedule(WORLD_A, () -> { }, 0, tick::get);

		StageRetry.clearWorld(WORLD_A);

		assertEquals(0, StageRetry.pendingCount(), "clearWorld removes the world's pending entries");
		assertEquals(exhaustedBefore + 2, StageRetry.exhausted(),
				"dropped retries are counted exhausted, never silent");
	}
}
