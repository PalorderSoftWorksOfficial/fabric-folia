package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inter-tick drain-window loop ({@link FabricFoliaEngine#drainWindow}):
 * exit as soon as staged work is caught up, pump chunk executors while
 * waiting (the worker-unblock path), and never exceed the budget — a
 * budget-exhausted return is what {@code awaitStagedDrain} counts when the
 * window cannot keep up with the load.
 */
class StagedDrainWindowTest {

	@Test
	void exitsImmediatelyWhenAlreadyCaughtUp() {
		AtomicInteger pumps = new AtomicInteger();
		AtomicInteger parks = new AtomicInteger();

		boolean exhausted = FabricFoliaEngine.drainWindow(TimeUnit.SECONDS.toNanos(5),
				() -> true, pumps::incrementAndGet, nanos -> parks.incrementAndGet());

		assertFalse(exhausted, "an idle window must never report exhaustion");
		assertEquals(0, pumps.get(), "an idle window must not pump");
		assertEquals(0, parks.get(), "an idle window must not park");
	}

	@Test
	void pumpsAndParksEachRoundUntilCaughtUp() {
		AtomicInteger pumps = new AtomicInteger();
		AtomicInteger parks = new AtomicInteger();

		boolean exhausted = FabricFoliaEngine.drainWindow(TimeUnit.SECONDS.toNanos(5),
				() -> pumps.get() >= 3, pumps::incrementAndGet, nanos -> parks.incrementAndGet());

		assertFalse(exhausted, "catching up inside the budget must not report exhaustion");
		assertEquals(3, pumps.get(), "each waiting round must pump once");
		assertEquals(3, parks.get(), "each waiting round must park once");
	}

	@Test
	void returnsExhaustedWhenBudgetExpiresWithWorkOutstanding() {
		AtomicInteger pumps = new AtomicInteger();
		long start = System.nanoTime();

		boolean exhausted = FabricFoliaEngine.drainWindow(TimeUnit.MILLISECONDS.toNanos(25),
				() -> false, pumps::incrementAndGet, nanos -> { });

		long elapsed = System.nanoTime() - start;
		assertTrue(exhausted, "work still pending at the deadline must report exhaustion");
		assertTrue(elapsed >= TimeUnit.MILLISECONDS.toNanos(25),
				"the loop must wait out the full budget before giving up (elapsed=" + elapsed + "ns)");
		assertTrue(elapsed < TimeUnit.SECONDS.toNanos(5),
				"the budget must bound the wait (elapsed=" + elapsed + "ns)");
		assertTrue(pumps.get() > 0, "a waiting window must keep pumping");
	}
}
