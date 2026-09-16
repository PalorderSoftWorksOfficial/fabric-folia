/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.ThreadContext.Kind;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Async scheduler tests (mandate §12): a distinct pool (never the global
 * dispatch thread, never region workers), wall-clock delays, fixed-rate with
 * non-overlap, cancel, exception isolation (one malformed task must not kill
 * the pool), backpressure rejection, and IO context tagging so STRICT
 * diagnostics classify async violations correctly.
 */
class AsyncSchedulerImplTest {

	@AfterEach
	void clearContext() {
		ThreadOwnership.clear();
	}

	private static AsyncSchedulerImpl newScheduler(int threads) {
		return new AsyncSchedulerImpl(threads, s -> { });
	}

	@Test
	void runExecutesOnDedicatedAsyncThreads() throws Exception {
		try (AsyncSchedulerImpl scheduler = newScheduler(2)) {
			AtomicReference<String> threadName = new AtomicReference<>();
			AtomicReference<Kind> contextKind = new AtomicReference<>();
			CountDownLatch ran = new CountDownLatch(1);

			scheduler.run(() -> {
				threadName.set(Thread.currentThread().getName());
				contextKind.set(ThreadOwnership.current().kind());
				ran.countDown();
			});

			assertTrue(ran.await(10, TimeUnit.SECONDS));
			assertTrue(threadName.get().startsWith("FabricFolia-Async-"),
					"must run on a dedicated async worker, got " + threadName.get());
			assertEquals(Kind.IO, contextKind.get(),
					"async tasks must carry the IO context so diagnostics classify them");
		}
	}

	@Test
	void delayedTaskRunsAfterWallClockDelay() throws Exception {
		try (AsyncSchedulerImpl scheduler = newScheduler(1)) {
			CountDownLatch ran = new CountDownLatch(1);
			long start = System.nanoTime();
			scheduler.runDelayed(150, ran::countDown);
			assertTrue(ran.await(10, TimeUnit.SECONDS));
			long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
			assertTrue(elapsedMillis >= 100,
					"delayed task must respect the wall-clock delay, ran after " + elapsedMillis + "ms");
		}
	}

	@Test
	void fixedRateDoesNotOverlapItself() throws Exception {
		try (AsyncSchedulerImpl scheduler = newScheduler(1)) {
			AtomicInteger concurrent = new AtomicInteger();
			AtomicInteger maxConcurrent = new AtomicInteger();
			AtomicInteger completions = new AtomicInteger();
			CountDownLatch enough = new CountDownLatch(3);

			scheduler.runAtFixedRate(0, 50, () -> {
				concurrent.incrementAndGet();
				maxConcurrent.accumulateAndGet(concurrent.get(), Math::max);
				try {
					Thread.sleep(40); // longer than the period would allow naively
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					concurrent.decrementAndGet();
					completions.incrementAndGet();
					enough.countDown();
				}
			});

			assertTrue(enough.await(10, TimeUnit.SECONDS), "fixed-rate task must keep firing");
			assertEquals(1, maxConcurrent.get(), "scheduleWithFixedDelay must never overlap runs");
		}
	}

	@Test
	void cancelStopsFixedRateTask() throws Exception {
		try (AsyncSchedulerImpl scheduler = newScheduler(1)) {
			AtomicInteger runs = new AtomicInteger();
			var handle = scheduler.runAtFixedRate(0, 30, runs::incrementAndGet);
			Thread.sleep(120);
			handle.cancel();
			int atCancel = runs.get();
			Thread.sleep(150);
			assertTrue(runs.get() - atCancel <= 1,
					"cancelling must stop (or at most land one in-flight) run, ran " + runs.get());
		}
	}

	@Test
	void exceptionDoesNotKillPool() throws Exception {
		try (AsyncSchedulerImpl scheduler = newScheduler(1)) {
			scheduler.run(() -> { throw new RuntimeException("deliberate"); });
			CountDownLatch secondRan = new CountDownLatch(1);
			scheduler.run(secondRan::countDown);
			assertTrue(secondRan.await(10, TimeUnit.SECONDS),
					"the pool must survive a malformed task and run the next");
		}
	}

	@Test
	void saturatedPoolRejectsInsteadOfQueueingUnbounded() throws Exception {
		try (AsyncSchedulerImpl scheduler = newScheduler(1)) {
			CountDownLatch blockerStarted = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger rejected = new AtomicInteger();
			AtomicInteger accepted = new AtomicInteger();
			scheduler.run(() -> {
				blockerStarted.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(blockerStarted.await(10, TimeUnit.SECONDS), "blocker must start");

			// Saturate: the worker is blocked, so submissions fill the bounded
			// queue (capacity 256) and then must be REJECTED — burst absorbed,
				// overload visible (documented backpressure contract).
			for (int i = 0; i < 300; i++) {
				try {
					scheduler.run(() -> { });
					accepted.incrementAndGet();
				} catch (RejectedExecutionException e) {
					rejected.incrementAndGet();
				}
			}
			assertTrue(rejected.get() > 0,
					"saturation must reject, accepted " + accepted.get() + " of 300");
			assertTrue(accepted.get() <= 257,
					"at most worker(1) + queue(256) can be accepted, got " + accepted.get());
			release.countDown();
		}
	}

	@Test
	void delayedTaskReportsRejectionThroughDiagnostics() throws Exception {
		AtomicReference<String> reported = new AtomicReference<>();
		try (AsyncSchedulerImpl scheduler = new AsyncSchedulerImpl(1, reported::set)) {
			CountDownLatch blockerStarted = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			scheduler.run(() -> {
				blockerStarted.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(blockerStarted.await(10, TimeUnit.SECONDS));

			// Fill the bounded queue completely (256), so the delayed task
			// finds a saturated pool at fire time. Timer-fired work has no
			// caller to throw to: the rejection must reach the diagnostics
			// sink (documented timer-path contract), never silently vanish.
			for (int i = 0; i < 256; i++) {
				scheduler.run(() -> { });
			}
			scheduler.runDelayed(50, () -> { });
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while ((reported.get() == null || !reported.get().contains("overloaded"))
					&& System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
			assertNotNull(reported.get(), "fire-time rejection must be reported");
			assertTrue(reported.get().contains("overloaded"), reported.get());
			release.countDown();
		}
	}

	@Test
	void diagnosticsSinkReceivesTaskFailures() throws Exception {
		AtomicReference<String> reported = new AtomicReference<>();
		try (AsyncSchedulerImpl scheduler = new AsyncSchedulerImpl(1, reported::set)) {
			CountDownLatch reported1 = new CountDownLatch(1);
			scheduler.run(() -> { throw new IllegalStateException("probe"); });
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (reported.get() == null && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}
			assertNotNull(reported.get(), "task failure must reach the diagnostics sink");
			assertTrue(reported.get().contains("Async task failed"), reported.get());
		}
	}
}
