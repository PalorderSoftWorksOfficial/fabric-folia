package com.palordersoftworks.fabricfolia.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial coverage for the O(1) pending counter: every queue mutation
 * must pair exactly one count change with exactly one structural change.
 * The old removeTask decremented blindly after a void iterator remove, so a
 * poll racing the scan drove the counter permanently negative (hidden by
 * size()'s clamp, poisoning the slow-motion gate and backlog math).
 */
class RegionTaskQueueConcurrencyTest {

	@Test
	void removeAbsentTaskChangesNothing() {
		RegionTaskQueue queue = new RegionTaskQueue();
		Runnable present = () -> {
		};
		queue.add(present);
		assertFalse(queue.removeTask(() -> {
		}), "removing a task that was never added must fail");
		assertEquals(1, queue.size(), "failed remove must not touch the counter");
		assertTrue(queue.removeTask(present), "the added task must be removable");
		assertEquals(0, queue.size());
	}

	@Test
	void removeTaskTakesExactlyOneDuplicate() {
		RegionTaskQueue queue = new RegionTaskQueue();
		Runnable body = () -> {
		};
		queue.add(body);
		queue.add(body);
		assertEquals(2, queue.size());
		assertTrue(queue.removeTask(body), "one duplicate must be removable");
		assertEquals(1, queue.size(), "exactly one duplicate must leave");
		assertEquals(1, queue.drainEntries().size(), "the other duplicate must still drain");
		assertEquals(0, queue.size());
	}

	@Test
	void removeAfterPollDoesNotDoubleDecrement() {
		// The exact old-bug shape, sequenced: the entry leaves via poll, so
		// the later remove finds nothing and must not touch the counter.
		RegionTaskQueue queue = new RegionTaskQueue();
		Runnable body = () -> {
		};
		queue.add(body);
		assertEquals(body, queue.pollEntry().task(), "poll must take the entry");
		assertFalse(queue.removeTask(body), "remove after poll must fail");
		assertEquals(0, queue.size(), "failed remove must not drive the counter negative");
	}

	@Test
	void lifecycleTransitionsKeepExactAccounting() {
		RegionTaskQueue queue = new RegionTaskQueue();
		for (int i = 0; i < 10; i++) {
			final int id = i;
			queue.add(() -> {
			}, id);
		}
		assertEquals(10, queue.size());

		// Merge: everything moves, source empties, nothing created or lost.
		RegionTaskQueue merged = new RegionTaskQueue();
		queue.rehomeInto(merged);
		assertEquals(0, queue.size(), "donor must empty on merge");
		assertEquals(10, merged.size(), "target must hold every entry after merge");

		// Drop: returns the count and empties.
		assertEquals(10, merged.dropAll(), "dropAll must report every dropped entry");
		assertEquals(0, merged.size(), "dropped queue must read empty");
		assertEquals(0, merged.drainEntries().size(), "dropped entries must not drain");
	}

	@Test
	void splitMovesExactlyTheMatchingEntries() {
		RegionTaskQueue parent = new RegionTaskQueue();
		Runnable positioned = () -> {
		};
		Runnable unpositioned = () -> {
		};
		parent.add(positioned, 0x1111L);
		parent.add(positioned, 0x2222L);
		parent.add(unpositioned);
		RegionTaskQueue child = new RegionTaskQueue();
		int moved = parent.partitionOnSplit(pos -> pos == 0x2222L ? child : null);
		assertEquals(1, moved, "only the matching entry may move");
		assertEquals(2, parent.size(), "parent keeps the rest");
		assertEquals(1, child.size(), "child holds exactly the moved entry");
		assertEquals(0x2222L, child.drainEntries().get(0).chunkPos(),
				"the moved entry must be the matching one");
	}

	@Test
	void hammerKeepsExactAccounting() throws Exception {
		RegionTaskQueue queue = new RegionTaskQueue();
		int producers = 4;
		int perProducer = 5_000;
		// Small shared pool of runnables forces removeTask to hit live entries.
		List<Runnable> bodies = new ArrayList<>();
		for (int i = 0; i < 64; i++) {
			bodies.add(() -> {
			});
		}
		AtomicInteger added = new AtomicInteger();
		AtomicInteger polled = new AtomicInteger();
		AtomicInteger removed = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(producers + 2);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int p = 0; p < producers; p++) {
				futures.add(pool.submit(() -> {
					try {
						start.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
					for (int i = 0; i < perProducer; i++) {
						Runnable body = bodies.get(i % bodies.size());
						queue.add(body);
						added.incrementAndGet();
						if ((i & 1) == 0 && queue.pollEntry() != null) {
							polled.incrementAndGet();
						}
						if ((i & 3) == 0 && queue.removeTask(body)) {
							removed.incrementAndGet();
						}
					}
				}));
			}
			// A dedicated drainer racing everything, like a region tick would.
			AtomicInteger drained = new AtomicInteger();
			Future<?> drainer = pool.submit(() -> {
				try {
					start.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				while (!Thread.currentThread().isInterrupted()) {
					List<RegionTaskQueue.Entry> batch = queue.drainEntries();
					if (!batch.isEmpty()) {
						drained.addAndGet(batch.size());
					} else {
						Thread.yield();
					}
					boolean producersDone = true;
					for (Future<?> f : futures) {
						producersDone &= f.isDone();
					}
					if (producersDone && queue.size() == 0) {
						return;
					}
				}
			});
			start.countDown();
			for (Future<?> f : futures) {
				f.get(60, TimeUnit.SECONDS);
			}
			drainer.get(60, TimeUnit.SECONDS);
			// Final drain of anything the drainer left mid-handoff.
			drained.addAndGet(queue.drainEntries().size());
			assertEquals(0, queue.size(), "counter must settle at exactly zero");
			assertEquals(added.get(), polled.get() + removed.get() + drained.get(),
					"every added entry must leave exactly once");
		} finally {
			pool.shutdownNow();
		}
	}
}
