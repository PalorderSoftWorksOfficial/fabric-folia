/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionState;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Region-aware stall detection (mandate §26). There is no single main
 * thread to watch: each region's tick is independently deadline-driven, so
 * the watchdog watches REGIONS, not threads. A region stuck in TICKING
 * well past its own next-tick deadline is a stall candidate; the report
 * names the region, its tick count, its last duration, and how far overdue
 * its next tick is — the information an operator needs to find the
 * blocking operation in a thread dump.
 *
 * <p><strong>What it deliberately does NOT do:</strong> no thread dumps from
 * the watchdog itself (JVM-specific, noisy), no killing of regions (a
 * blocked-but-alive tick usually completes — the wedge playtests showed
 * self-recovery; {@code RegionFailurePolicy} already aborts regions whose
 * ticks FAIL). The watchdog's job is to make a stall visible and repeated
 * stalls actionable.</p>
 *
 * <p><strong>Threading:</strong> one daemon thread; reads region state
 * without locks (volatile fields, advisory only — a torn read can at worst
 * skip one report); every field it reads is owned by the scheduler's
 * documented volatile discipline.</p>
 */
public final class RegionWatchdog implements AutoCloseable {

	/** Default interval between stall scans (milliseconds). */
	public static final long DEFAULT_INTERVAL_MILLIS = 10_000L;

	/** A region must be overdue by this fraction of one tick (50ms) to report. */
	private static final long OVERDUE_THRESHOLD_NANOS = 5_000_000_000L;

	private final java.util.function.Supplier<List<Region>> regionSource;
	/** Optional per-worker keepalive snapshot (nanoTime of last task start per worker). */
	private final java.util.function.Supplier<long[]> keepaliveSource;
	private final Consumer<String> diagnostics;
	private final long intervalMillis;
	private final AtomicLong reportedStalls = new AtomicLong();
	private volatile boolean running;
	private Thread thread;

	/**
	 * @param regionSource    live-region snapshot source (the engine supplies
	 *                        a per-world aggregate; never null after start)
	 * @param keepaliveSource per-worker last-task-start snapshot, or null
	 * @param diagnostics     report sink
	 * @param intervalMillis  scan cadence
	 */
	public RegionWatchdog(java.util.function.Supplier<List<Region>> regionSource,
	                      java.util.function.Supplier<long[]> keepaliveSource,
	                      Consumer<String> diagnostics,
	                      long intervalMillis) {
		this.regionSource = java.util.Objects.requireNonNull(regionSource, "regionSource");
		this.keepaliveSource = keepaliveSource;
		this.diagnostics = java.util.Objects.requireNonNull(diagnostics, "diagnostics");
		this.intervalMillis = Math.max(1_000L, intervalMillis);
	}

	/** Starts the watchdog thread. Idempotent. */
	public void start() {
		if (running) {
			return;
		}
		running = true;
		thread = new Thread(this::loop, "FabricFolia-Watchdog");
		thread.setDaemon(true);
		thread.start();
	}

	private void loop() {
		while (running) {
			try {
				Thread.sleep(intervalMillis);
			} catch (InterruptedException e) {
				return;
			}
			scan();
		}
	}

	/** One stall scan: reports regions TICKING far past their next deadline. */
	public void scan() {
		long now = System.nanoTime();
		for (Region region : regionSource.get()) {
			if (region.state() != RegionState.TICKING) {
				continue;
			}
			long deadline = region.nextTickDeadlineNanos();
			if (deadline == 0) {
				continue;
			}
			long overdue = now - deadline;
			if (overdue > OVERDUE_THRESHOLD_NANOS) {
				long stalls = reportedStalls.incrementAndGet();
				diagnostics.accept("REGION STALL: " + region + " has been TICKING for "
						+ (overdue / 1_000_000) + "ms past its next-tick deadline"
						+ " (last tick " + (region.lastTickDurationNanos() / 1_000_000)
						+ "ms, tick count " + region.tickCount() + ", stall report #" + stalls + ")"
						+ workerContext()
						+ " — take a thread dump of the FabricFolia-Worker-* threads now;"
						+ " the stuck frame is inside this region's tick body or queue drain.");
			}
		}
	}

	/**
	 * @return a one-line summary of the worker pool's keepalive state (the
	 * longest-idle worker tells "pool starved" from "one tick hung"), or an
	 * empty string when no pool was wired.
	 */
	private String workerContext() {
		java.util.function.Supplier<long[]> source = keepaliveSource;
		if (source == null) {
			return "";
		}
		long[] keepalive = source.get();
		if (keepalive.length == 0) {
			return "";
		}
		long now = System.nanoTime();
		long oldest = Long.MAX_VALUE;
		for (long stamp : keepalive) {
			oldest = Math.min(oldest, stamp);
		}
		return " [oldest worker task start: " + ((now - oldest) / 1_000_000) + "ms ago]";
	}

	/** @return total stall reports emitted (diagnostics). */
	public long reportedStalls() {
		return reportedStalls.get();
	}

	@Override
	public void close() {
		running = false;
		if (thread != null) {
			thread.interrupt();
		}
	}
}
