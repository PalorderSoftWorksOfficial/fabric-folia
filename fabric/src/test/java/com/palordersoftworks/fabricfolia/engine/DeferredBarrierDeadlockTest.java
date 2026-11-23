package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredBarrierDeadlockTest {

	private FabricFoliaEngine engine;
	private final List<String> errors = new CopyOnWriteArrayList<>();

	@AfterEach
	void resetEngineAndLedger() throws Exception {
		if (engine != null) {
			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			engine.shutdown(5000);
			engine = null;
		}
		ServerThreadDeferral.clearAll();
		ServerThreadDeferral.noteServerThread(null);
		ServerThreadDeferral.installDiagnostics(null);
	}

	private FabricFoliaEngine boot() throws Exception {
		Path dir = Files.createTempDirectory("folia-deferral-barrier");
		FoliaConfig config = FoliaConfig.at(dir.resolve("fabric-folia.yml"));
		config.load();
		config.freeze();
		engine = FabricFoliaEngine.bootstrap(config, msg -> { }, errors::add);
		engine.attachWorld("minecraft:overworld", 10, null);
		return engine;
	}

	private RegionScheduler scheduler() {
		return engine.schedulerFor("minecraft:overworld");
	}

	private Region region(int chunkX) {
		return engine.regionizerFor("minecraft:overworld").addChunk(chunkX, 0);
	}

	private static void await(BooleanCondition condition, String message) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(5);
		}
		assertTrue(condition.getAsBoolean(), message);
	}

	private interface BooleanCondition {
		boolean getAsBoolean();
	}

	private void runBarrier(AtomicReference<Thread> barrierThread, Future<?>[] slot,
			long timeoutSeconds) throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "deferral-barrier-server-thread");
			thread.setDaemon(true);
			return thread;
		});
		Future<?> tick = executor.submit(() -> {
			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			barrierThread.set(Thread.currentThread());
			engine.beginTickPhase();
		});
		slot[0] = tick;
		try {
			tick.get(timeoutSeconds, TimeUnit.SECONDS);
		} finally {
			executor.shutdown();
		}
	}

	@Test
	void barrierReleasesWorkerParkedOnTicketLoadBridge() throws Exception {
		boot();
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch workerDone = new CountDownLatch(1);
		AtomicReference<Thread> mutationThread = new AtomicReference<>();
		AtomicReference<Object> bridgeResult = new AtomicReference<>();
		AtomicReference<Thread> barrierThread = new AtomicReference<>();
		long barrierDrainedBefore = ServerThreadDeferral.barrierDrained();
		long deferredBefore = ServerThreadDeferral.deferred();
		Future<?>[] slot = new Future<?>[1];
		try {
			assertTrue(scheduler().enqueue(region(0), () -> {
				started.countDown();
				CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(() -> {
					mutationThread.set(Thread.currentThread());
					return CompletableFuture.completedFuture("loaded");
				});
				bridgeResult.set(bridge.join());
				workerDone.countDown();
			}));
			assertTrue(started.await(10, TimeUnit.SECONDS));
			await(() -> ServerThreadDeferral.barrierSafeWaiters() > 0,
					"the worker never parked on the bridge");

			runBarrier(barrierThread, slot, 5);

			assertTrue(workerDone.await(5, TimeUnit.SECONDS),
					"the barrier must release the worker parked on the deferred ticket load");
			assertTrue(errors.stream().noneMatch(m -> m.contains("quiesce timed out")),
					"the wait must be released inside the barrier: " + errors);
			assertSame(barrierThread.get(), mutationThread.get());
			assertEquals("loaded", bridgeResult.get());
			assertEquals(0, ServerThreadDeferral.barrierSafeWaiters());
			assertEquals(0, ServerThreadDeferral.pendingCount());
			assertEquals(barrierDrainedBefore + 1, ServerThreadDeferral.barrierDrained());
			assertEquals(deferredBefore + 1, ServerThreadDeferral.deferred());
		} finally {
			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			ServerThreadDeferral.drainAll();
			if (slot[0] != null) {
				try {
					slot[0].get(2, TimeUnit.SECONDS);
				} catch (Exception ignored) {
				}
			}
		}
	}

	@Test
	void derivedStageWaiterIsReleasedByTheBarrier() throws Exception {
		boot();
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch workerDone = new CountDownLatch(1);
		AtomicReference<Object> result = new AtomicReference<>();
		AtomicReference<Thread> barrierThread = new AtomicReference<>();
		Future<?>[] slot = new Future<?>[1];
		try {
			assertTrue(scheduler().enqueue(region(0), () -> {
				started.countDown();
				CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(
						() -> CompletableFuture.completedFuture("loaded"));
				result.set(bridge.thenApply(value -> value + "!").join());
				workerDone.countDown();
			}));
			assertTrue(started.await(10, TimeUnit.SECONDS));

			runBarrier(barrierThread, slot, 5);

			assertTrue(workerDone.await(5, TimeUnit.SECONDS),
					"a worker waiting on a derived stage must still be released by the barrier");
			assertEquals("loaded!", result.get());
			assertEquals(0, ServerThreadDeferral.pendingCount());
		} finally {
			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			ServerThreadDeferral.drainAll();
			if (slot[0] != null) {
				try {
					slot[0].get(2, TimeUnit.SECONDS);
				} catch (Exception ignored) {
				}
			}
		}
	}

	@Test
	void ordinaryDeferralIsNotRunWhileAWorkerRemainsBusy() throws Exception {
		boot();
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		Region regionA = region(0);
		Region regionB = region(4000);
		CountDownLatch ordinaryDeferred = new CountDownLatch(1);
		AtomicBoolean ordinaryRan = new AtomicBoolean(false);
		AtomicBoolean ordinaryPendingAtRelease = new AtomicBoolean(false);
		CountDownLatch bridgeParked = new CountDownLatch(1);
		CountDownLatch workerBStarted = new CountDownLatch(1);
		AtomicReference<Thread> barrierThread = new AtomicReference<>();
		Future<?>[] slot = new Future<?>[1];
		try {
			assertTrue(scheduler().enqueue(regionA, () -> {
				ServerThreadDeferral.defer(() -> ordinaryRan.set(true));
				ordinaryDeferred.countDown();
				try {
					Thread.sleep(400);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertTrue(ordinaryDeferred.await(10, TimeUnit.SECONDS));

			assertTrue(scheduler().enqueue(regionB, () -> {
				workerBStarted.countDown();
				CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(() -> {
					ordinaryPendingAtRelease.set(!ordinaryRan.get()
							&& ServerThreadDeferral.pendingCount() == 1);
					return CompletableFuture.completedFuture(null);
				});
				bridgeParked.countDown();
				bridge.join();
			}));
			assertTrue(workerBStarted.await(10, TimeUnit.SECONDS));
			assertTrue(bridgeParked.await(10, TimeUnit.SECONDS));
			await(() -> ServerThreadDeferral.barrierSafeWaiters() > 0,
					"worker B never parked on the bridge");

			runBarrier(barrierThread, slot, 5);

			assertTrue(ordinaryPendingAtRelease.get(),
					"the ordinary handoff must still be queued when the barrier-safe call runs");
			assertFalse(ordinaryRan.get(),
					"an ordinary deferral must never run while any worker can still be active");
			assertEquals(1, ServerThreadDeferral.pendingCount());

			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			assertEquals(1, ServerThreadDeferral.drainAll(),
					"the normal tick-path drain applies the handoff once workers have quiesced");
			assertTrue(ordinaryRan.get());
			assertEquals(0, ServerThreadDeferral.pendingCount());
		} finally {
			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			ServerThreadDeferral.drainAll();
			if (slot[0] != null) {
				try {
					slot[0].get(2, TimeUnit.SECONDS);
				} catch (Exception ignored) {
				}
			}
		}
	}

	@Test
	void barrierSafeDrainPreservesFifoAndCountsAcrossTwoWaiters() throws Exception {
		boot();
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		List<String> order = new CopyOnWriteArrayList<>();
		CountDownLatch firstParked = new CountDownLatch(1);
		CountDownLatch secondParked = new CountDownLatch(1);
		AtomicReference<Thread> barrierThread = new AtomicReference<>();
		Future<?>[] slot = new Future<?>[1];
		long barrierDrainedBefore = ServerThreadDeferral.barrierDrained();
		try {
			assertTrue(scheduler().enqueue(region(0), () -> {
				CompletableFuture<Object> first = ServerThreadDeferral.deferBarrierSafe(() -> {
					order.add("first");
					return CompletableFuture.completedFuture(null);
				});
				firstParked.countDown();
				first.join();
			}));
			assertTrue(firstParked.await(10, TimeUnit.SECONDS));
			await(() -> ServerThreadDeferral.barrierSafeWaiters() == 1,
					"the first waiter never parked");

			assertTrue(scheduler().enqueue(region(4000), () -> {
				CompletableFuture<Object> second = ServerThreadDeferral.deferBarrierSafe(() -> {
					order.add("second");
					return CompletableFuture.completedFuture(null);
				});
				secondParked.countDown();
				second.join();
			}));
			assertTrue(secondParked.await(10, TimeUnit.SECONDS));
			await(() -> ServerThreadDeferral.barrierSafeWaiters() == 2,
					"the second waiter never parked");

			runBarrier(barrierThread, slot, 5);

			assertEquals(List.of("first", "second"), order);
			assertEquals(0, ServerThreadDeferral.pendingCount());
			assertEquals(barrierDrainedBefore + 2, ServerThreadDeferral.barrierDrained());
		} finally {
			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			ServerThreadDeferral.drainAll();
			if (slot[0] != null) {
				try {
					slot[0].get(2, TimeUnit.SECONDS);
				} catch (Exception ignored) {
				}
			}
		}
	}

	@Test
	void failingTicketLoadFailsTheTickAndStillReleasesTheWorker() throws Exception {
		boot();
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		CountDownLatch parked = new CountDownLatch(1);
		CountDownLatch workerDone = new CountDownLatch(1);
		AtomicReference<Throwable> workerFailure = new AtomicReference<>();
		AtomicReference<Thread> barrierThread = new AtomicReference<>();
		Future<?>[] slot = new Future<?>[1];
		long failedBefore = ServerThreadDeferral.failed();
		try {
			assertTrue(scheduler().enqueue(region(0), () -> {
				CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(() -> {
					throw new IllegalStateException("load refused");
				});
				parked.countDown();
				try {
					bridge.join();
				} catch (CompletionException e) {
					workerFailure.set(e.getCause());
				} finally {
					workerDone.countDown();
				}
			}));
			assertTrue(parked.await(10, TimeUnit.SECONDS));
			await(() -> ServerThreadDeferral.barrierSafeWaiters() > 0,
					"the worker never parked on the failing bridge");

			java.util.concurrent.ExecutionException tickFailure = org.junit.jupiter.api.Assertions.assertThrows(
					java.util.concurrent.ExecutionException.class, () -> runBarrier(barrierThread, slot, 5),
					"a failed deferred ticket load must fail the tick");
			assertTrue(tickFailure.getCause() instanceof IllegalStateException,
					"the tick must fail with the deferred failure: " + tickFailure.getCause());
			assertEquals("load refused", tickFailure.getCause().getMessage());

			assertTrue(workerDone.await(5, TimeUnit.SECONDS));
			assertTrue(workerFailure.get() instanceof IllegalStateException,
					"the failure must reach the waiting worker, not be swallowed: " + workerFailure.get());
			assertEquals(failedBefore + 1, ServerThreadDeferral.failed());
			assertEquals(0, ServerThreadDeferral.pendingCount());
		} finally {
			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			ServerThreadDeferral.drainAll();
			if (slot[0] != null) {
				try {
					slot[0].get(2, TimeUnit.SECONDS);
				} catch (Exception ignored) {
				}
			}
		}
	}

	@Test
	void barrierSafeEntryEnqueuedWhileThePumpRunsIsStillReleased() throws Exception {
		boot();
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		List<String> order = new CopyOnWriteArrayList<>();
		CountDownLatch firstParked = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		CountDownLatch secondDone = new CountDownLatch(1);
		AtomicReference<Thread> barrierThread = new AtomicReference<>();
		Future<?>[] slot = new Future<?>[1];
		long barrierDrainedBefore = ServerThreadDeferral.barrierDrained();
		try {
			assertTrue(scheduler().enqueue(region(0), () -> {
				CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(() -> {
					order.add("early");
					return CompletableFuture.completedFuture(null);
				});
				firstParked.countDown();
				bridge.join();
			}));
			assertTrue(firstParked.await(10, TimeUnit.SECONDS));
			await(() -> ServerThreadDeferral.barrierSafeWaiters() > 0,
					"the first waiter never parked");

			assertTrue(scheduler().enqueue(region(4000), () -> {
				secondStarted.countDown();
				try {
					Thread.sleep(250);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(() -> {
					order.add("late");
					return CompletableFuture.completedFuture(null);
				});
				bridge.join();
				secondDone.countDown();
			}));
			assertTrue(secondStarted.await(10, TimeUnit.SECONDS));

			runBarrier(barrierThread, slot, 10);

			assertTrue(secondDone.await(5, TimeUnit.SECONDS),
					"an entry enqueued after the pump began must still be observed and released");
			assertEquals(List.of("early", "late"), order);
			assertEquals(0, ServerThreadDeferral.pendingCount());
			assertEquals(barrierDrainedBefore + 2, ServerThreadDeferral.barrierDrained());
		} finally {
			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			ServerThreadDeferral.drainAll();
			if (slot[0] != null) {
				try {
					slot[0].get(2, TimeUnit.SECONDS);
				} catch (Exception ignored) {
				}
			}
		}
	}

	@Test
	void shutdownReleasesWorkerParkedOnTicketLoadBridge() throws Exception {
		boot();
		ServerThreadDeferral.noteServerThread(Thread.currentThread());
		CountDownLatch parked = new CountDownLatch(1);
		CountDownLatch workerDone = new CountDownLatch(1);
		AtomicReference<Thread> mutationThread = new AtomicReference<>();
		long begin = System.nanoTime();
		try {
			assertTrue(scheduler().enqueue(region(0), () -> {
				CompletableFuture<Object> bridge = ServerThreadDeferral.deferBarrierSafe(() -> {
					mutationThread.set(Thread.currentThread());
					return CompletableFuture.completedFuture(null);
				});
				parked.countDown();
				bridge.join();
				workerDone.countDown();
			}));
			assertTrue(parked.await(10, TimeUnit.SECONDS));
			await(() -> ServerThreadDeferral.barrierSafeWaiters() > 0,
					"the worker never parked on the bridge");

			engine.shutdown(5000);
			long elapsedMillis = (System.nanoTime() - begin) / 1_000_000L;
			engine = null;

			assertTrue(workerDone.await(5, TimeUnit.SECONDS),
					"the shutdown pump must release the parked worker");
			assertTrue(elapsedMillis < 5_000L,
					"shutdown must not fall through the whole worker timeout (took " + elapsedMillis + "ms)");
			assertSame(Thread.currentThread(), mutationThread.get());
			assertEquals(0, ServerThreadDeferral.pendingCount());
		} finally {
			ServerThreadDeferral.noteServerThread(Thread.currentThread());
			ServerThreadDeferral.drainAll();
		}
	}
}
