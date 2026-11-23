package com.palordersoftworks.fabricfolia.engine;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ServerThreadDeferral {

	private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();
	private static final Queue<BarrierSafeEntry> BARRIER_SAFE = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger PENDING_COUNT = new AtomicInteger();
	private static final AtomicLong DEFERRED = new AtomicLong();
	private static final AtomicLong REPLAYED = new AtomicLong();
	private static final AtomicLong DROPPED = new AtomicLong();
	private static final AtomicLong FAILED = new AtomicLong();
	private static final AtomicInteger BARRIER_SAFE_WAITERS = new AtomicInteger();
	private static final AtomicLong BARRIER_DRAINED = new AtomicLong();
	private static final AtomicReference<Throwable> REPLAY_FAILURE = new AtomicReference<>();
	private static volatile Thread serverThread;
	private static volatile Consumer<String> diagnostics = message -> { };

	private ServerThreadDeferral() {
	}

	public static void noteServerThread(Thread thread) {
		serverThread = thread;
	}

	public static boolean isServerThread() {
		Thread owner = serverThread;
		return owner == null || Thread.currentThread() == owner;
	}

	public static void installDiagnostics(Consumer<String> sink) {
		diagnostics = sink == null ? message -> { } : sink;
	}

	public static void defer(Runnable mutation) {
		PENDING.add(mutation);
		PENDING_COUNT.incrementAndGet();
		DEFERRED.incrementAndGet();
	}

	public static CompletableFuture<Object> deferBarrierSafe(Supplier<CompletableFuture<?>> serverThreadCall) {
		CompletableFuture<Object> bridge = new BridgeFuture();
		BARRIER_SAFE.add(new BarrierSafeEntry(bridge, () -> {
			try {
				CompletableFuture<?> load = serverThreadCall.get();
				if (load == null) {
					bridge.complete(null);
					return;
				}
				load.whenComplete((result, error) -> {
					if (error != null) {
						bridge.completeExceptionally(error);
					} else {
						bridge.complete(result);
					}
				});
			} catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
				bridge.completeExceptionally(fatal);
				throw fatal;
			} catch (Throwable t) {
				bridge.completeExceptionally(t);
				if (t instanceof RuntimeException runtime) {
					throw runtime;
				}
				if (t instanceof Error error) {
					throw error;
				}
				throw new RuntimeException(t);
			}
		}));
		PENDING_COUNT.incrementAndGet();
		DEFERRED.incrementAndGet();
		return bridge;
	}

	public static int drainAll() {
		return drain(PENDING, Integer.MAX_VALUE) + drainBarrierSafeQueue(Integer.MAX_VALUE);
	}

	public static int drainBarrierSafe(int limit) {
		return drainBarrierSafeQueue(limit);
	}

	public static int barrierSafeWaiters() {
		return BARRIER_SAFE_WAITERS.get();
	}

	private static int drainBarrierSafeQueue(int limit) {
		int attempted = 0;
		int replayed = 0;
		BarrierSafeEntry entry;
		while (attempted < limit && replayIsHealthy() && (entry = BARRIER_SAFE.poll()) != null) {
			attempted++;
			PENDING_COUNT.decrementAndGet();
			if (runDeferred(entry.call())) {
				replayed++;
				BARRIER_DRAINED.incrementAndGet();
			}
		}
		return replayed;
	}

	private static int drain(Queue<Runnable> queue, int limit) {
		int attempted = 0;
		int replayed = 0;
		Runnable mutation;
		while (attempted < limit && replayIsHealthy() && (mutation = queue.poll()) != null) {
			attempted++;
			PENDING_COUNT.decrementAndGet();
			if (runDeferred(mutation)) {
				replayed++;
			}
		}
		return replayed;
	}

	private static boolean replayIsHealthy() {
		return REPLAY_FAILURE.get() == null;
	}

	private static boolean runDeferred(Runnable mutation) {
		try {
			mutation.run();
			REPLAYED.incrementAndGet();
			return true;
		} catch (VirtualMachineError | ThreadDeath | LinkageError fatal) {
			FAILED.incrementAndGet();
			REPLAY_FAILURE.compareAndSet(null, fatal);
			throw fatal;
		} catch (Throwable t) {
			FAILED.incrementAndGet();
			REPLAY_FAILURE.compareAndSet(null, t);
			diagnostics.accept("Deferred vanilla mutation failed on the server thread: " + t);
			if (t instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (t instanceof Error error) {
				throw error;
			}
			throw new RuntimeException(t);
		}
	}

	public static void clearAll() {
		int dropped = 0;
		while (PENDING.poll() != null) {
			PENDING_COUNT.decrementAndGet();
			dropped++;
		}
		BarrierSafeEntry entry;
		while ((entry = BARRIER_SAFE.poll()) != null) {
			PENDING_COUNT.decrementAndGet();
			dropped++;
			entry.bridge().completeExceptionally(new RejectedExecutionException(
					"Fabric-Folia stopped before the deferred ticket load was applied"));
		}
		if (dropped > 0) {
			DROPPED.addAndGet(dropped);
		}
		REPLAY_FAILURE.set(null);
	}

	public static Throwable replayFailure() {
		return REPLAY_FAILURE.get();
	}

	public static Throwable takeReplayFailure() {
		return REPLAY_FAILURE.getAndSet(null);
	}

	public static long deferred() {
		return DEFERRED.get();
	}

	public static long replayed() {
		return REPLAYED.get();
	}

	public static long dropped() {
		return DROPPED.get();
	}

	public static long failed() {
		return FAILED.get();
	}

	public static long barrierDrained() {
		return BARRIER_DRAINED.get();
	}

	public static int pendingCount() {
		return Math.max(0, PENDING_COUNT.get());
	}

	private record BarrierSafeEntry(CompletableFuture<Object> bridge, Runnable call) {
	}

	private static final class BridgeFuture extends CompletableFuture<Object> {

		@Override
		public Object join() {
			BARRIER_SAFE_WAITERS.incrementAndGet();
			try {
				return super.join();
			} finally {
				BARRIER_SAFE_WAITERS.decrementAndGet();
			}
		}

		@Override
		public Object get() throws InterruptedException, ExecutionException {
			BARRIER_SAFE_WAITERS.incrementAndGet();
			try {
				return super.get();
			} finally {
				BARRIER_SAFE_WAITERS.decrementAndGet();
			}
		}

		@Override
		public Object get(long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			BARRIER_SAFE_WAITERS.incrementAndGet();
			try {
				return super.get(timeout, unit);
			} finally {
				BARRIER_SAFE_WAITERS.decrementAndGet();
			}
		}
	}
}
