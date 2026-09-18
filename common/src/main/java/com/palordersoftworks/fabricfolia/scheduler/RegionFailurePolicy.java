/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.region.Region;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Exception isolation per region (mandate §34): a failure inside one region's
 * tick work is contained, classified, and either retried, tolerated, or the
 * region aborted — without corrupting unrelated regions and without crashing
 * the whole server for a region-local problem.
 *
 * <p><strong>Policy (per region, configurable, exposed for /folia):</strong></p>
 * <ul>
 *   <li><strong>LOG_AND_CONTINUE</strong> (default) — report with full
 *       context (region, world, task description, thread) and let the region
 *       continue ticking; the failure is treated like vanilla treats a
 *       failing ticking entry.</li>
 *   <li><strong>ABORT_REGION</strong> — after {@code maxFailuresBeforeAbort}
 *       consecutive failures in the same region, the region is aborted via
 *       {@link RegionAbortion} (the engine routes it to the regionizer's
 *       fail-safe kill — a region that fails every tick is a wedge risk for
 *       everything it owns, and a bounded loud loss beats a silent freeze).</li>
 * </ul>
 *
 * <p><strong>What this class is NOT:</strong> it does not run tasks itself —
 * the scheduler's dispatch drains queues and calls into this policy at its
 * existing catch sites. It is the missing "policy" half of the scheduler's
 * current report-always behavior (mandate §33/§34: a single malformed task
 * must not destroy the scheduler; a region that fails every tick must not
 * freeze its chunks forever).</p>
 */
public final class RegionFailurePolicy {

	/** Default abort threshold when no stricter policy is configured. */
	public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 5;

	/** What happens to a region that keeps failing. */
	public enum Action {
		/** Report and continue (the region stays healthy). */
		LOG_AND_CONTINUE,
		/** Abort the region (engine routes to the regionizer's fail-safe kill). */
		ABORT_REGION
	}

	/** Carries an abort decision to the engine (which owns the regionizer). */
	public interface RegionAbortion {
		void abort(Region region, Throwable cause);
	}

	private final Consumer<String> diagnostics;
	private final RegionAbortion abortion;
	private final int maxConsecutiveFailures;

	/** consecutive failures per region id; reset on any success. */
	private final Map<Long, AtomicLong> consecutiveFailures = new ConcurrentHashMap<>();

	public RegionFailurePolicy(Consumer<String> diagnostics,
	                           RegionAbortion abortion,
	                           int maxConsecutiveFailures) {
		this.diagnostics = java.util.Objects.requireNonNull(diagnostics, "diagnostics");
		this.abortion = java.util.Objects.requireNonNull(abortion, "abortion");
		if (maxConsecutiveFailures < 1) {
			throw new IllegalArgumentException("maxConsecutiveFailures must be >= 1");
		}
		this.maxConsecutiveFailures = maxConsecutiveFailures;
	}

	/**
	 * Observe a failure in {@code region}'s tick work. Returns the action the
	 * caller must take for this region.
	 */
	public Action onFailure(Region region, String taskDescription, Throwable cause) {
		AtomicLong counter = consecutiveFailures
				.computeIfAbsent(region.id, id -> new AtomicLong());
		long failures = counter.incrementAndGet();
		diagnostics.accept(String.format(Locale.ROOT,
				"Region task failed in %s (world %s, thread %s): %s — failure %d/%d",
				region, region.world(), Thread.currentThread().getName(),
				taskDescription == null ? String.valueOf(cause) : taskDescription + ": " + cause,
				failures, maxConsecutiveFailures));
		if (cause != null) {
			// Full stack for the issue tracker (mandate §34's report format).
			java.io.StringWriter sw = new java.io.StringWriter();
			cause.printStackTrace(new java.io.PrintWriter(sw));
			diagnostics.accept(sw.toString());
		}
		if (failures >= maxConsecutiveFailures) {
			counter.set(0);
			diagnostics.accept("Region " + region + " reached " + maxConsecutiveFailures
					+ " consecutive failures - aborting the region (its chunks will"
					+ " re-regionize on next activity).");
			return Action.ABORT_REGION;
		}
		return Action.LOG_AND_CONTINUE;
	}

	/** Observe a success (resets the region's consecutive-failure counter). */
	public void onSuccess(Region region) {
		AtomicLong counter = consecutiveFailures.get(region.id);
		if (counter != null) {
			counter.set(0);
		}
	}

	/** Clears bookkeeping for a dead region (merge/death cleanup). */
	public void onRegionRemoved(Region region) {
		consecutiveFailures.remove(region.id);
	}

	/** @return the configured consecutive-failure threshold. */
	public int maxConsecutiveFailures() {
		return maxConsecutiveFailures;
	}

	/** Internal helper kept for the scheduler's existing per-task report call. */
	public void reportTaskFailure(Region region, String description, Throwable cause) {
		onFailure(region, description, cause);
	}

	/** Summarizes per-region health for /folia (diagnostics). */
	public List<String> diagnosticsLines() {
		List<String> lines = new java.util.ArrayList<>();
		for (Map.Entry<Long, AtomicLong> e : consecutiveFailures.entrySet()) {
			long failures = e.getValue().get();
			if (failures > 0) {
				lines.add("  region #" + e.getKey() + ": " + failures
						+ " consecutive failure(s) (abort at " + maxConsecutiveFailures + ")");
			}
		}
		if (lines.isEmpty()) {
			lines.add("  all regions healthy");
		}
		return lines;
	}
}
