package com.palordersoftworks.fabricfolia.scheduler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tick-phase execution gate: while closed, workers start NO new tasks
 * (the server thread owns gameplay state); closeExecutionAndAwait is the
 * tick-boundary quiesce whose busy-before-gate-read ordering makes the
 * barrier correct.
 */
class WorkerPoolGateTest {

	@Test
	void closedGateParksWorkersAndOpenRunsQueuedTasks() throws Exception {
		WorkerPool pool = new WorkerPool(2, "GateTest-Park");
		try {
			AtomicBoolean ran = new AtomicBoolean(false);
			pool.setExecutionOpen(false);
			pool.submit(() -> ran.set(true));
			Thread.sleep(250);
			assertFalse(ran.get(), "a task must not start while the gate is closed");

			pool.setExecutionOpen(true);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline && !ran.get()) {
				Thread.sleep(10);
			}
			assertTrue(ran.get(), "queued task must run after the gate reopens");
		} finally {
			pool.shutdown(5_000);
		}
	}

	@Test
	void quiesceWaitsForInFlightTaskAndBlocksNewStarts() throws Exception {
		WorkerPool pool = new WorkerPool(1, "GateTest-Quiesce");
		try {
			CountDownLatch started = new CountDownLatch(1);
			AtomicBoolean firstDone = new AtomicBoolean(false);
			AtomicBoolean secondRan = new AtomicBoolean(false);

			pool.submit(() -> {
				started.countDown();
				try {
					Thread.sleep(300);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				firstDone.set(true);
			});
			assertTrue(started.await(5, TimeUnit.SECONDS), "first task never started");

			// Second task queued while the first runs; the quiesce must wait
			// for the in-flight task and leave the queued one unstarted.
			pool.submit(() -> secondRan.set(true));
			assertTrue(pool.closeExecutionAndAwait(5_000),
					"quiesce must observe the in-flight task and wait for it");
			assertTrue(firstDone.get(), "quiesce returned before the in-flight task finished");
			Thread.sleep(150);
			assertFalse(secondRan.get(), "a queued task started during the closed phase");

			pool.setExecutionOpen(true);
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() < deadline && !secondRan.get()) {
				Thread.sleep(10);
			}
			assertTrue(secondRan.get(), "task queued pre-close must run after reopen");
		} finally {
			pool.shutdown(5_000);
		}
	}

	@Test
	void quiescePumpUnblocksWorkerWaitingOnMainThreadTask() throws Exception {
		// The live deadlock shape: a worker body joins a future that only the
		// SERVER THREAD can complete (vanilla getChunk supplyAsync+join). A
		// pump-less quiesce sleeps while the worker waits — mutual wait until
		// timeout. With the pump, each barrier iteration runs the pending
		// main-thread task, the worker completes, the barrier drains.
		WorkerPool pool = new WorkerPool(1, "GateTest-Pump");
		try {
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch workerDone = new CountDownLatch(1);
			java.util.concurrent.atomic.AtomicBoolean pumpRan = new java.util.concurrent.atomic.AtomicBoolean(false);

			pool.submit(() -> {
				started.countDown();
				// Simulate CompletableFuture.join() on a server-thread future:
				// only the pump (server thread) can release this worker.
				try {
					workerDone.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(started.await(5, TimeUnit.SECONDS), "task never started");

			assertTrue(pool.closeExecutionAndAwait(5_000, () -> {
				pumpRan.set(true);
				workerDone.countDown();
			}), "quiesce with a pump must unblock the worker instead of timing out");
			assertTrue(pumpRan.get(), "the barrier must pump while waiting");
		} finally {
			pool.setExecutionOpen(true);
			pool.shutdown(5_000);
		}
	}

	@Test
	void burstSubmitLosesNoWakeup() throws Exception {
		// One signal per submit must suffice: a woken worker drains queued
		// tasks without parking, so 200 rapid submits to 4 workers must all
		// run. signalAll would also pass this — it pins the no-lost-wakeup
		// contract the signal() optimization relies on.
		WorkerPool pool = new WorkerPool(4, "GateTest-Burst");
		try {
			int tasks = 200;
		CountDownLatch done = new CountDownLatch(tasks);
			for (int i = 0; i < tasks; i++) {
				pool.submit(done::countDown);
		}
			assertTrue(done.await(15, TimeUnit.SECONDS),
					"all burst tasks must run (remaining=" + done.getCount() + ")");
		} finally {
			pool.shutdown(5_000);
		}
	}

	@Test
	void burstQueuedWhileClosedAllRunAfterReopen() throws Exception {
		WorkerPool pool = new WorkerPool(2, "GateTest-BurstClosed");
		try {
			int tasks = 50;
		CountDownLatch done = new CountDownLatch(tasks);
		pool.setExecutionOpen(false);
		for (int i = 0; i < tasks; i++) {
				pool.submit(done::countDown);
		}
		Thread.sleep(200);
		assertTrue(done.getCount() > 0, "tasks must not start while the gate is closed");
		pool.setExecutionOpen(true);
		assertTrue(done.await(15, TimeUnit.SECONDS),
				"every closed-gate task must run after reopen (remaining=" + done.getCount() + ")");
		} finally {
			pool.shutdown(5_000);
		}
	}

	@Test
	void churnOfSubmitAndQuiesceLosesNothing() throws Exception {
		// Stress through the real gate cycle: many rounds of burst-submit
		// then quiesce-then-reopen, the per-tick shape. Every task must run
		// exactly once, no quiesce may time out, and the pool must idle clean.
		WorkerPool pool = new WorkerPool(4, "GateTest-Churn");
		try {
			int rounds = 60;
		int perRound = 30;
		java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
			for (int r = 0; r < rounds; r++) {
				CountDownLatch round = new CountDownLatch(perRound);
				for (int i = 0; i < perRound; i++) {
					pool.submit(() -> {
						ran.incrementAndGet();
						round.countDown();
					});
				}
				assertTrue(round.await(15, TimeUnit.SECONDS),
						"round " + r + " lost tasks (ran=" + ran.get() + ")");
				assertTrue(pool.closeExecutionAndAwait(5_000),
						"quiesce must drain the round without timing out");
				pool.setExecutionOpen(true);
			}
			assertEquals(rounds * perRound, ran.get(), "every churned task must run exactly once");
			assertEquals(0, pool.busyCount(), "pool must idle clean after churn");
		} finally {
			pool.shutdown(5_000);
		}
	}

	@Test
	void shutdownAbandonsQueuedTasksWithoutRunningThem() throws Exception {
		// Adversarial: tasks queued while the gate is closed must never run
		// after shutdown — workers exit instead of draining. After shutdown
		// returns the workers are terminated, so this is deterministic: if
		// any task could still run, the pool would be broken.
		WorkerPool pool = new WorkerPool(2, "GateTest-Shutdown");
		java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean(false);
		pool.setExecutionOpen(false);
		for (int i = 0; i < 10; i++) {
			pool.submit(() -> ran.set(true));
		}
		pool.shutdown(5_000);
		assertFalse(ran.get(), "abandoned queued tasks must never execute after shutdown");
	}

	@Test
	void quiesceReportsTimeoutOnWedgedTask() throws Exception {
		WorkerPool pool = new WorkerPool(1, "GateTest-Timeout");
		try {
			CountDownLatch started = new CountDownLatch(1);
			pool.submit(() -> {
				started.countDown();
				try {
					Thread.sleep(1_500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(started.await(5, TimeUnit.SECONDS), "task never started");
			assertFalse(pool.closeExecutionAndAwait(200),
					"a wedged task must surface as a quiesce timeout, never a silent pass");
		} finally {
			pool.setExecutionOpen(true);
			pool.shutdown(5_000);
		}
	}
}
