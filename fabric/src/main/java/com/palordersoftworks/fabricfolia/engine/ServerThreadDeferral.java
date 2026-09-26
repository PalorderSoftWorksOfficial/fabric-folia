package com.palordersoftworks.fabricfolia.engine;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class ServerThreadDeferral {

	private static final Queue<Runnable> PENDING = new ConcurrentLinkedQueue<>();
	private static final AtomicLong DEFERRED = new AtomicLong();
	private static final AtomicLong REPLAYED = new AtomicLong();
	private static final AtomicLong DROPPED = new AtomicLong();
	private static final AtomicLong FAILED = new AtomicLong();
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
		DEFERRED.incrementAndGet();
	}

	public static int drainAll() {
		if (PENDING.isEmpty()) {
			return 0;
		}
		int replayed = 0;
		Runnable mutation;
		while ((mutation = PENDING.poll()) != null) {
			try {
				mutation.run();
				replayed++;
			} catch (Throwable t) {
				FAILED.incrementAndGet();
				diagnostics.accept("Deferred vanilla mutation failed on the server thread: " + t);
			}
		}
		if (replayed > 0) {
			REPLAYED.addAndGet(replayed);
		}
		return replayed;
	}

	public static void clearAll() {
		int dropped = 0;
		while (PENDING.poll() != null) {
			dropped++;
		}
		if (dropped > 0) {
			DROPPED.addAndGet(dropped);
		}
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

	public static int pendingCount() {
		return PENDING.size();
	}
}
