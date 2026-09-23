/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.metrics;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The performance-metrics registry (mandate §35): real parallelism measured —
 * region MSPT distribution, worker utilization, scheduler queue depths,
 * structural transition counts, cross-region traffic, and scheduler latency —
 * backed by atomic counters that are cheap to bump on the hot path and
 * aggregated only when someone asks (a /folia command, the compat harness).
 *
 * <p><strong>Design:</strong> no background sampler thread, no locks on the
 * hot path — each counter is an {@link AtomicLong} (or a striped set for the
 * highest-frequency ones); {@link #snapshotLines()} walks them on demand.
 * Region MSPT samples are ring-bucketed (last 100 ticks per region) so
 * percentiles are computed without unbounded history.</p>
 *
 * <p><strong>State classification (spec 4):</strong> GLOBAL — this is
 * diagnostics, not gameplay state; everything here must be safe to read from
 * any thread at any time.</p>
 */
public final class RegionMetrics {

	/** High-frequency event counters (bumped on hot paths). */
	public enum Counter {
		CROSS_REGION_TASKS,
		ENTITY_MIGRATIONS,
		REGION_MERGES,
		REGION_SPLITS,
		REGION_ABORTS,
		SCHEDULED_TICKS_EXECUTED,
		ENTITY_TICKS_EXECUTED,
		BLOCK_ENTITY_TICKS_EXECUTED,
		PLAYER_CONNECTION_TICKS,
		CHUNK_REGISTRATIONS,
		CHUNK_UNREGISTRATIONS,
		GLOBAL_TASKS_EXECUTED,
		ASYNC_TASKS_EXECUTED,
		EXCEPTIONS_ISOLATED
	}

	private final Map<Counter, AtomicLong> counters = new ConcurrentHashMap<>();
	/** Per-region MSPT ring buckets, keyed by region id. */
	private final Map<Long, MsptRing> msptByRegion = new ConcurrentHashMap<>();
	/** Worker busy-tick counters (busy ticks per worker index). */
	private final AtomicLong[] workerBusyTicks;
	/** Worker tick totals since start (for utilization vs. elapsed ticks). */
	private final AtomicLong workerBusyNanos = new AtomicLong();
	private final AtomicLong workerInvocations = new AtomicLong();
	/** Deepest queue depth observed between polls (peak watermark). */
	private final AtomicLong peakQueueDepth = new AtomicLong();
	private final long createdNanos = System.nanoTime();

	/**
	 * @return a metrics instance wired to nothing — used by schedulers that
	 * run outside the engine (tests) until real metrics are installed.
	 */
	public static RegionMetrics detached() {
		return new RegionMetrics(1);
	}

	public RegionMetrics(int workerCount) {
		for (Counter c : Counter.values()) {
			counters.put(c, new AtomicLong());
		}
		this.workerBusyTicks = new AtomicLong[Math.max(1, workerCount)];
		for (int i = 0; i < workerBusyTicks.length; i++) {
			workerBusyTicks[i] = new AtomicLong();
		}
	}

	/** Bumps a counter (hot path: one atomic increment). */
	public long increment(Counter counter) {
		return counters.get(counter).incrementAndGet();
	}

	/** Adds to a counter (batched paths). */
	public void add(Counter counter, long delta) {
		counters.get(counter).addAndGet(delta);
	}

	/** @return the current value of a counter. */
	public long value(Counter counter) {
		return counters.get(counter).get();
	}

	/**
	 * Records one region tick's duration into the region's MSPT ring and the
	 * worker-utilization aggregates.
	 */
	public void recordRegionTick(long regionId, int workerIndex, long durationNanos) {
		msptByRegion.computeIfAbsent(regionId, id -> new MsptRing()).record(durationNanos);
		workerBusyNanos.addAndGet(durationNanos);
		workerInvocations.incrementAndGet();
		if (workerIndex >= 0 && workerIndex < workerBusyTicks.length) {
			workerBusyTicks[workerIndex].incrementAndGet();
		}
	}

	/** Observes the deepest total queue depth seen (call from dispatch scans). */
	public void observeQueueDepth(long totalQueued) {
		peakQueueDepth.accumulateAndGet(totalQueued, Math::max);
	}

	/** @return the peak observed total queue depth. */
	public long peakQueueDepth() {
		return peakQueueDepth.get();
	}

	/**
	 * Human-readable snapshot for /folia metrics and the compat harness.
	 * One line per metric; MSPT lines show last/max/avg per region.
	 */
	public List<String> snapshotLines() {
		List<String> lines = new java.util.ArrayList<>();
		lines.add("Structural: merges=" + value(Counter.REGION_MERGES)
				+ " splits=" + value(Counter.REGION_SPLITS)
				+ " aborts=" + value(Counter.REGION_ABORTS));
		lines.add("Work: entityTicks=" + value(Counter.ENTITY_TICKS_EXECUTED)
				+ " blockEntityTicks=" + value(Counter.BLOCK_ENTITY_TICKS_EXECUTED)
				+ " scheduledTicks=" + value(Counter.SCHEDULED_TICKS_EXECUTED)
				+ " playerConnectionTicks=" + value(Counter.PLAYER_CONNECTION_TICKS));
		lines.add("Cross-region: tasks=" + value(Counter.CROSS_REGION_TASKS)
				+ " migrations=" + value(Counter.ENTITY_MIGRATIONS));
		lines.add("Chunks: registered=" + value(Counter.CHUNK_REGISTRATIONS)
				+ " unregistered=" + value(Counter.CHUNK_UNREGISTRATIONS));
		lines.add("Scheduler: globalTasks=" + value(Counter.GLOBAL_TASKS_EXECUTED)
				+ " asyncTasks=" + value(Counter.ASYNC_TASKS_EXECUTED)
				+ " peakQueueDepth=" + peakQueueDepth.get());
		lines.add("Isolation: exceptionsContained=" + value(Counter.EXCEPTIONS_ISOLATED));
		long elapsedNanos = Math.max(1, System.nanoTime() - createdNanos);
		double utilization = 100.0 * workerBusyNanos.get() / (elapsedNanos * (double) workerBusyTicks.length);
		lines.add(String.format(Locale.ROOT, "Workers: invocations=%d utilization~%.1f%% (busy nanos / elapsed / threads)",
				workerInvocations.get(), utilization));
		for (Map.Entry<Long, MsptRing> e : msptByRegion.entrySet()) {
			MsptRing ring = e.getValue();
			lines.add(String.format(Locale.ROOT, "Region #%d MSPT: last=%.2f avg=%.2f max=%.2f (n=%d)",
					e.getKey(), ring.lastMillis(), ring.avgMillis(), ring.maxMillis(), ring.count()));
		}
		return lines;
	}

	/**
	 * A fixed-size ring of the most recent MSPT samples for one region —
	 * percentiles without unbounded history.
	 */
	private static final class MsptRing {
		private static final int CAPACITY = 100;
		private final long[] samples = new long[CAPACITY];
		private int index;
		private int filled;
		private long last;
		private long max;

		synchronized void record(long durationNanos) {
			samples[index] = durationNanos;
			index = (index + 1) % CAPACITY;
			if (filled < CAPACITY) {
				filled++;
			}
			last = durationNanos;
			max = Math.max(max, durationNanos);
		}

		synchronized double lastMillis() {
			return last / 1_000_000.0;
		}

		synchronized double maxMillis() {
			return max / 1_000_000.0;
		}

		synchronized double avgMillis() {
			if (filled == 0) {
				return 0;
			}
			long sum = 0;
			for (int i = 0; i < filled; i++) {
				sum += samples[i];
			}
			return sum / (double) filled / 1_000_000.0;
		}

		synchronized int count() {
			return filled;
		}
	}
}
