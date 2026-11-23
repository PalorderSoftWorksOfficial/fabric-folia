package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerThreadDeferralTest {

	@AfterEach
	void resetLedger() {
		ServerThreadDeferral.clearAll();
		ServerThreadDeferral.noteServerThread(null);
		ServerThreadDeferral.installDiagnostics(null);
	}

	@Test
	void mutationsReplayInFifoOrder() {
		List<Integer> order = new ArrayList<>();
		long deferredBefore = ServerThreadDeferral.deferred();
		ServerThreadDeferral.defer(() -> order.add(1));
		ServerThreadDeferral.defer(() -> order.add(2));
		ServerThreadDeferral.defer(() -> order.add(3));
		assertEquals(3, ServerThreadDeferral.pendingCount());
		assertEquals(3, ServerThreadDeferral.drainAll());
		assertEquals(List.of(1, 2, 3), order);
		assertEquals(0, ServerThreadDeferral.pendingCount());
		assertEquals(deferredBefore + 3, ServerThreadDeferral.deferred());
	}

	@Test
	void removeThenAddReplaysExactlyAsIssued() {
		List<String> order = new ArrayList<>();
		ServerThreadDeferral.defer(() -> order.add("remove"));
		ServerThreadDeferral.defer(() -> order.add("add"));
		ServerThreadDeferral.drainAll();
		assertEquals(List.of("remove", "add"), order);
	}

	@Test
	void recordedOwnerMutatesDirectlyWhileOtherThreadsDefer() throws Exception {
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		assertTrue(ServerThreadDeferral.isServerThread());
		AtomicBoolean workerIsServerThread = new AtomicBoolean(true);
		CountDownLatch done = new CountDownLatch(1);
		Thread worker = new Thread(() -> {
			workerIsServerThread.set(ServerThreadDeferral.isServerThread());
			done.countDown();
		}, "test-worker");
		worker.start();
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertFalse(workerIsServerThread.get());
	}

	@Test
	void unknownOwnerPassesThroughUntilTheServerThreadIsRecorded() {
		assertTrue(ServerThreadDeferral.isServerThread());
	}

	@Test
	void failingReplayFailsThePassAndStopsLaterWork() {
		List<String> survived = new ArrayList<>();
		List<String> reports = new ArrayList<>();
		ServerThreadDeferral.installDiagnostics(reports::add);
		long failedBefore = ServerThreadDeferral.failed();
		ServerThreadDeferral.defer(() -> survived.add("before"));
		ServerThreadDeferral.defer(() -> {
			throw new IllegalStateException("boom");
		});
		ServerThreadDeferral.defer(() -> survived.add("after"));
		IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
				IllegalStateException.class, ServerThreadDeferral::drainAll,
				"a deferred mutation failure must fail the tick, not be swallowed");
		assertEquals("boom", thrown.getMessage());
		assertEquals(List.of("before"), survived,
			"work queued behind a failed mutation must not run on torn state");
		assertEquals(failedBefore + 1, ServerThreadDeferral.failed());
		assertEquals(1, reports.size());
		assertEquals(1, ServerThreadDeferral.pendingCount());
		ServerThreadDeferral.clearAll();
	}

	@Test
	void clearAllDropsAndCountsEveryPendingMutation() {
		long droppedBefore = ServerThreadDeferral.dropped();
		ServerThreadDeferral.defer(() -> {
		});
		ServerThreadDeferral.defer(() -> {
		});
		ServerThreadDeferral.clearAll();
		assertEquals(0, ServerThreadDeferral.pendingCount());
		assertEquals(droppedBefore + 2, ServerThreadDeferral.dropped());
	}

	@Test
	void concurrentWorkersNeverLoseOrReorderTheirOwnMutations() throws Exception {
		int workers = 8;
		int perWorker = 500;
		List<List<Integer>> replayed = new ArrayList<>();
		for (int i = 0; i < workers; i++) {
			replayed.add(new ArrayList<>());
		}
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(workers);
		List<Thread> threads = new ArrayList<>();
		for (int w = 0; w < workers; w++) {
			final int workerIndex = w;
			Thread thread = new Thread(() -> {
				try {
					start.await();
					for (int i = 0; i < perWorker; i++) {
						final int value = i;
						ServerThreadDeferral.defer(() -> replayed.get(workerIndex).add(value));
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			}, "deferral-worker-" + w);
			threads.add(thread);
			thread.start();
		}
		start.countDown();
		assertTrue(done.await(30, TimeUnit.SECONDS));
		for (Thread thread : threads) {
			thread.join(10_000);
		}
		ServerThreadDeferral.drainAll();
		assertEquals(0, ServerThreadDeferral.pendingCount());
		for (int w = 0; w < workers; w++) {
			List<Integer> expected = new ArrayList<>();
			for (int i = 0; i < perWorker; i++) {
				expected.add(i);
			}
			assertEquals(expected, replayed.get(w), "worker " + w + " mutations replay in submission order");
		}
	}

	@Test
	void barrierSafeDrainLeavesTheHandoffLaneQueued() throws Exception {
		List<String> order = new CopyOnWriteArrayList<>();
		ServerThreadDeferral.defer(() -> order.add("handoff"));
		CountDownLatch parked = new CountDownLatch(1);
		CountDownLatch released = new CountDownLatch(1);
		Thread waiter = new Thread(() -> {
			CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(() -> {
				order.add("barrier-safe");
				return CompletableFuture.completedFuture(null);
			});
			parked.countDown();
			bridge.join();
			released.countDown();
		}, "barrier-safe-waiter");
		waiter.start();
		try {
			assertTrue(parked.await(5, TimeUnit.SECONDS));
			awaitWaiters(1);
			assertEquals(1, ServerThreadDeferral.drainBarrierSafe(10));
			assertTrue(released.await(5, TimeUnit.SECONDS));
			waiter.join(5_000);
			assertEquals(List.of("barrier-safe"), order,
					"the barrier-safe drain must never apply an ordinary handoff");
			assertEquals(1, ServerThreadDeferral.pendingCount());
			assertEquals(0, ServerThreadDeferral.drainBarrierSafe(10));
			assertEquals(1, ServerThreadDeferral.drainAll());
			assertEquals(List.of("barrier-safe", "handoff"), order);
			assertEquals(0, ServerThreadDeferral.pendingCount());
		} finally {
			waiter.interrupt();
			waiter.join(5_000);
		}
	}

	@Test
	void barrierSafeDrainIsBoundedPerPassAndKeepsFifo() throws Exception {
		List<Integer> order = new CopyOnWriteArrayList<>();
		CompletableFuture<Object> never = new CompletableFuture<>();
		CountDownLatch parked = new CountDownLatch(1);
		CountDownLatch released = new CountDownLatch(1);
		Thread waiter = new Thread(() -> {
			CompletableFuture<Object> first = ServerThreadDeferral.deferBarrierSafe(() -> {
				order.add(1);
				return CompletableFuture.completedFuture(null);
			});
			CompletableFuture<Object> second = ServerThreadDeferral.deferBarrierSafe(() -> {
				order.add(2);
				return CompletableFuture.completedFuture(null);
			});
			CompletableFuture<Object> third = ServerThreadDeferral.deferBarrierSafe(() -> {
				order.add(3);
				return CompletableFuture.completedFuture(null);
			});
			CompletableFuture<Object> last = ServerThreadDeferral.deferBarrierSafe(() -> {
				order.add(4);
				return never;
			});
			parked.countDown();
			first.join();
			second.join();
			third.join();
			last.join();
			released.countDown();
		}, "barrier-safe-fifo-waiter");
		waiter.start();
		try {
			assertTrue(parked.await(5, TimeUnit.SECONDS));
			awaitWaiters(1);
			assertEquals(2, ServerThreadDeferral.drainBarrierSafe(2));
			assertEquals(List.of(1, 2), order, "a pass must stop at its limit");
			assertEquals(2, ServerThreadDeferral.pendingCount());
			awaitWaiters(1);
			assertEquals(2, ServerThreadDeferral.drainBarrierSafe(2));
			assertEquals(List.of(1, 2, 3, 4), order, "later passes continue in FIFO order");
			assertEquals(0, ServerThreadDeferral.pendingCount());
			never.complete(null);
			assertTrue(released.await(5, TimeUnit.SECONDS));
		} finally {
			never.complete(null);
			waiter.interrupt();
			waiter.join(5_000);
		}
	}

	@Test
	void barrierSafeLaneDrainsWithoutAJoinWaiter() {
		List<String> order = new ArrayList<>();
		ServerThreadDeferral.deferBarrierSafe(() -> {
			order.add("applied");
			return CompletableFuture.completedFuture(null);
		});
		assertEquals(0, ServerThreadDeferral.barrierSafeWaiters());
		assertEquals(1, ServerThreadDeferral.drainBarrierSafe(10));
		assertEquals(List.of("applied"), order);
		assertEquals(0, ServerThreadDeferral.pendingCount());
	}

	@Test
	void derivedStageWaiterIsReleasedByTheBarrierSafeDrain() throws Exception {
		CountDownLatch parked = new CountDownLatch(1);
		CountDownLatch released = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicReference<Object> result =
				new java.util.concurrent.atomic.AtomicReference<>();
		Thread waiter = new Thread(() -> {
			CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(
					() -> CompletableFuture.completedFuture("loaded"));
			parked.countDown();
			result.set(bridge.thenApply(value -> value + "!").join());
			released.countDown();
		}, "barrier-safe-derived-waiter");
		waiter.start();
		try {
			assertTrue(parked.await(5, TimeUnit.SECONDS));
			assertTrue(ServerThreadDeferral.barrierSafeWaiters() <= 1);
			assertEquals(1, ServerThreadDeferral.drainBarrierSafe(10),
					"the drain must not depend on direct join/get instrumentation");
			assertTrue(released.await(5, TimeUnit.SECONDS),
					"a derived-stage waiter must be released too");
			assertEquals("loaded!", result.get());
			assertEquals(0, ServerThreadDeferral.pendingCount());
		} finally {
			waiter.interrupt();
			waiter.join(5_000);
		}
	}

	@Test
	void clearAllFailsAnOutstandingBridgeInsteadOfStrandingItsWaiter() throws Exception {
		java.util.concurrent.atomic.AtomicReference<Throwable> failure =
				new java.util.concurrent.atomic.AtomicReference<>();
		CountDownLatch created = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		Thread waiter = new Thread(() -> {
			CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(
					() -> new CompletableFuture<>());
			created.countDown();
			try {
				bridge.join();
			} catch (java.util.concurrent.CompletionException e) {
				failure.set(e.getCause());
			} finally {
				done.countDown();
			}
		}, "barrier-safe-cleared-waiter");
		waiter.start();
		assertTrue(created.await(5, TimeUnit.SECONDS));
		ServerThreadDeferral.clearAll();
		assertTrue(done.await(5, TimeUnit.SECONDS),
				"clearing must fail the bridge so no worker waits forever");
		assertTrue(failure.get() instanceof java.util.concurrent.RejectedExecutionException,
				"the waiter must see the refusal: " + failure.get());
		assertEquals(0, ServerThreadDeferral.pendingCount());
		waiter.join(5_000);
	}

	private static void awaitWaiters(int count) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline && ServerThreadDeferral.barrierSafeWaiters() < count) {
			Thread.sleep(1);
		}
		assertTrue(ServerThreadDeferral.barrierSafeWaiters() >= count,
				"expected " + count + " parked barrier-safe waiter(s)");
	}

	@Test
	void fatalErrorPropagatesAndAccountingStaysExact() {
		List<String> ran = new ArrayList<>();
		ServerThreadDeferral.defer(() -> ran.add("before"));
		LinkageError fatal = new LinkageError("boom");
		ServerThreadDeferral.defer(() -> {
			throw fatal;
		});
		ServerThreadDeferral.defer(() -> ran.add("after"));
		assertEquals(3, ServerThreadDeferral.pendingCount());
		long failedBefore = ServerThreadDeferral.failed();
		try {
			ServerThreadDeferral.drainAll();
			assertTrue(false, "drainAll must propagate fatal errors, not swallow them");
		} catch (LinkageError e) {
			assertTrue(e == fatal, "the exact fatal instance must propagate");
		}
		assertEquals(failedBefore + 1, ServerThreadDeferral.failed());
		assertEquals(1, ServerThreadDeferral.pendingCount(), "entries behind the fatal stay queued");
		assertEquals(List.of("before"), ran);
		assertEquals(fatal, ServerThreadDeferral.takeReplayFailure());
		assertEquals(1, ServerThreadDeferral.drainAll(), "the remaining entry must still replay");
		assertEquals(List.of("before", "after"), ran);
		assertEquals(0, ServerThreadDeferral.pendingCount());
	}
}