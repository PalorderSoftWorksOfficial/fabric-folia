package com.palordersoftworks.fabricfolia.engine;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackerEnumerationRaceRegressionTest {

	private static final class SimulationChunkTrackerModel {

		final Long2ByteOpenHashMap chunks = new Long2ByteOpenHashMap();
		final AtomicBoolean enumerationWindowOpen = new AtomicBoolean(false);
		final AtomicInteger windowViolations = new AtomicInteger();

		void setLevel(long key, int level) {
			if (level >= 33) {
				chunks.remove(key);
			} else {
				chunks.put(key, (byte) level);
			}
		}

		void guardAndSetLevel(long key, int level) {
			if (ServerThreadDeferral.isServerThread()) {
				observedWhileEnumerating(key, level);
			} else {
				ServerThreadDeferral.defer(() -> observedWhileEnumerating(key, level));
			}
		}

		void observedWhileEnumerating(long key, int level) {
			if (enumerationWindowOpen.get()) {
				windowViolations.incrementAndGet();
			}
			setLevel(key, level);
		}

		int forEachEntityTickingChunk(LongConsumer visitor) {
			int visited = 0;
			for (Long2ByteMap.Entry entry : Long2ByteMaps.fastIterable(chunks)) {
				if (entry.getByteValue() < 33) {
					visited++;
					visitor.accept(entry.getLongKey());
				}
			}
			return visited;
		}
	}

	@AfterEach
	void resetLedger() {
		ServerThreadDeferral.clearAll();
		ServerThreadDeferral.noteServerThread(null);
	}

	@Test
	void workerMutationsNeverTouchTheTrackerWhileForEachEntityTickingChunkIterates() throws Exception {
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		SimulationChunkTrackerModel tracker = new SimulationChunkTrackerModel();
		for (long key = 0; key < 64; key++) {
			tracker.setLevel(key, 10);
		}
		CountDownLatch workerSubmitted = new CountDownLatch(1);
		Thread worker = new Thread(() -> {
			tracker.guardAndSetLevel(1000, 10);
			tracker.guardAndSetLevel(1001, 10);
			workerSubmitted.countDown();
		}, "region-worker");
		tracker.enumerationWindowOpen.set(true);
		worker.start();
		assertTrue(workerSubmitted.await(10, TimeUnit.SECONDS),
				"worker submissions complete while the enumeration window is open");
		int visited = tracker.forEachEntityTickingChunk(key -> {
		});
		tracker.enumerationWindowOpen.set(false);
		worker.join(10_000);
		ServerThreadDeferral.drainAll();
		assertEquals(0, tracker.windowViolations.get(),
				"no tracker mutation may execute inside the forEachEntityTickingChunk window");
		assertEquals(64, visited, "enumeration covers exactly the pre-existing tracker keys");
		assertEquals((byte) 10, tracker.chunks.get(1000), "deferred ticket placement lands at drain");
		assertEquals((byte) 10, tracker.chunks.get(1001), "deferred ticket placement lands at drain");
	}

	@Test
	void stressFifteenHundredTicketKeysWithConcurrentEnumerationSurvivesIntact() throws Exception {
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		SimulationChunkTrackerModel tracker = new SimulationChunkTrackerModel();
		int workers = 8;
		int keysPerWorker = 188;
		int totalKeys = workers * keysPerWorker;
		long deferredBefore = ServerThreadDeferral.deferred();
		long replayedBefore = ServerThreadDeferral.replayed();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(workers);
		List<Thread> threads = new ArrayList<>();
		for (int w = 0; w < workers; w++) {
			final long keyBase = (long) w * keysPerWorker;
			Thread thread = new Thread(() -> {
				try {
					start.await();
					for (int i = 0; i < keysPerWorker; i++) {
						long key = keyBase + i;
						tracker.guardAndSetLevel(key, 10);
					if (key % 2 == 0) {
						tracker.guardAndSetLevel(key, 35);
						tracker.guardAndSetLevel(key, 12);
					} else {
						tracker.guardAndSetLevel(key, 12);
						tracker.guardAndSetLevel(key, 35);
					}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			}, "region-worker-" + w);
			threads.add(thread);
			thread.start();
		}
		start.countDown();
		int enumerationRounds = 0;
		while (done.getCount() > 0) {
			tracker.enumerationWindowOpen.set(true);
			tracker.forEachEntityTickingChunk(key -> {
			});
			tracker.enumerationWindowOpen.set(false);
			ServerThreadDeferral.drainAll();
			enumerationRounds++;
		}
		assertTrue(done.await(30, TimeUnit.SECONDS));
		for (Thread thread : threads) {
			thread.join(10_000);
		}
		tracker.enumerationWindowOpen.set(true);
		tracker.forEachEntityTickingChunk(key -> {
		});
		tracker.enumerationWindowOpen.set(false);
		ServerThreadDeferral.drainAll();

		assertEquals(0, tracker.windowViolations.get(),
				"no tracker mutation may execute inside the forEachEntityTickingChunk window");
		assertTrue(enumerationRounds > 0, "the server thread enumerated while workers were submitting");
		assertEquals(0, ServerThreadDeferral.pendingCount(), "every deferred mutation was replayed");
		long expectedMutations = (long) totalKeys * 3;
		assertEquals(deferredBefore + expectedMutations, ServerThreadDeferral.deferred());
		assertEquals(replayedBefore + expectedMutations, ServerThreadDeferral.replayed());
		int expectedLiveKeys = totalKeys / 2;
		assertEquals(expectedLiveKeys, tracker.chunks.size(), "removed keys stay absent, surviving keys stay live");
		for (long key = 0; key < totalKeys; key++) {
			if (key % 2 == 0) {
				assertEquals((byte) 12, tracker.chunks.get(key), "key " + key + " replayed its final level");
			} else {
				assertTrue(!tracker.chunks.containsKey(key), "key " + key + " stayed removed");
			}
		}
	}
}
