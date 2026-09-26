package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
	void failingReplayIsIsolatedCountedAndReported() {
		List<String> survived = new ArrayList<>();
		List<String> reports = new ArrayList<>();
		ServerThreadDeferral.installDiagnostics(reports::add);
		long failedBefore = ServerThreadDeferral.failed();
		ServerThreadDeferral.defer(() -> survived.add("before"));
		ServerThreadDeferral.defer(() -> {
			throw new IllegalStateException("boom");
		});
		ServerThreadDeferral.defer(() -> survived.add("after"));
		assertEquals(2, ServerThreadDeferral.drainAll(),
				"a mutation that throws is not counted as replayed");
		assertEquals(List.of("before", "after"), survived);
		assertEquals(failedBefore + 1, ServerThreadDeferral.failed());
		assertEquals(1, reports.size());
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
}
