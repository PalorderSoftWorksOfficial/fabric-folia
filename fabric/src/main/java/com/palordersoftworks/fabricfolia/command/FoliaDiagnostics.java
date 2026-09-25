/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.command;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.RegionState;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Diagnostic data for the /folia command, read from the REAL running
 * engine — the same regionizer, scheduler, worker pool and patch registry
 * executing the server (spec 36: one source of truth; no parallel stats
 * collector). Pure snapshot reads; nothing here mutates runtime state.
 */
public final class FoliaDiagnostics {

	/** Health check result. */
	public record Check(boolean ok, boolean warning, String message) {
		public static Check ok(String message) {
			return new Check(true, false, message);
		}

		public static Check warn(String message) {
			return new Check(false, true, message);
		}

		public static Check error(String message) {
			return new Check(false, false, message);
		}
	}

	private FoliaDiagnostics() {
	}

	/** @return aggregate region counts across all attached worlds. */
	public static RegionSummary regionSummary(FabricFoliaEngine engine) {
		int total = 0;
		int ready = 0;
		int ticking = 0;
		int transientRegions = 0;
		int due = 0;
		int queuedTasks = 0;
		long merges = 0;
		long splits = 0;
		for (RegionScheduler scheduler : engine.schedulers().values()) {
			for (Region region : scheduler.liveRegions()) {
				total++;
				switch (region.state()) {
					case READY -> ready++;
					case TICKING -> ticking++;
					case TRANSIENT -> transientRegions++;
					default -> {
					}
				}
			}
			due += scheduler.dueRegionCount();
			queuedTasks += scheduler.queuedRegionTasks();
			merges += scheduler.regionMerges();
			splits += scheduler.regionSplits();
		}
		return new RegionSummary(total, ready, ticking, transientRegions, due, queuedTasks, merges, splits);
	}

	/** Aggregate region counts (see {@link #regionSummary}). */
	public record RegionSummary(int total, int ready, int ticking, int transientRegions,
			int due, int queuedTasks, long merges, long splits) {
	}

	/** @return per-region detail lines (/folia regions verbose). */
	public static List<String> regionDetail(FabricFoliaEngine engine, boolean verbose) {
		List<String> lines = new ArrayList<>();
		for (var entry : engine.schedulers().entrySet()) {
			for (Region region : entry.getValue().liveRegions()) {
				if (!verbose) {
					continue;
				}
				long avg = region.averageTickDurationNanos();
				long peak = region.peakTickDurationNanos();
				lines.add("  " + entry.getKey() + " #" + region.regionId()
						+ ": state=" + region.stateName()
						+ ", ticks=" + region.tickCount()
						+ ", sections=" + region.sectionCount()
						+ (avg > 0 ? String.format(Locale.ROOT, ", mspt=%.2f, peak=%.2fms",
								avg / 1_000_000.0, peak / 1_000_000.0) : ""));
			}
		}
		return lines;
	}

	/** @return worker-pool lines (/folia workers). */
	public static List<String> workerLines(FabricFoliaEngine engine) {
		var pool = engine.workerPool();
		int total = pool.workerCount();
		int busy = pool.busyCount();
		int waiting = total - busy;
		int queued = 0;
		int due = 0;
		for (RegionScheduler scheduler : engine.schedulers().values()) {
			queued += scheduler.queuedRegionTasks();
			due += scheduler.dueRegionCount();
		}
		List<String> lines = new ArrayList<>();
		lines.add("workers total=" + total + " busy=" + busy + " waiting=" + waiting);
		lines.add("queued region tasks=" + queued + ", due regions=" + due);
		if (busy == 0 && due == 0) {
			lines.add("state: healthy idle — workers are parked efficiently (no region is due)");
		} else if (busy == 0 && due > 0) {
			lines.add("state: PROBLEM — regions are due but no worker picked work up");
		} else if (busy == total) {
			lines.add("state: saturated — every worker is executing a region tick");
		} else {
			lines.add("state: working — " + busy + " worker(s) executing region ticks");
		}
		lines.add("utilization=" + String.format(Locale.ROOT, "%.0f%%", total == 0 ? 0 : 100.0 * busy / total));
		return lines;
	}

	/** @return scheduler lines (/folia scheduler), with stall diagnosis. */
	public static List<String> schedulerLines(FabricFoliaEngine engine) {
		FoliaDiagnostics.RegionSummary summary = regionSummary(engine);
		List<String> lines = new ArrayList<>();
		lines.add("regions total=" + summary.total() + " ready=" + summary.ready()
				+ " ticking=" + summary.ticking() + " transient=" + summary.transientRegions());
		lines.add("due now=" + summary.due() + ", queued region tasks=" + summary.queuedTasks());
		lines.add("lifecycle totals: merges=" + summary.merges() + ", splits=" + summary.splits());
		if (summary.total() == 0 && engine.hasLoadedChunks()) {
			lines.add("DIAGNOSIS: active chunks detected but NO tickable regions —"
					+ " chunk→regionizer registration is failing; see /folia patches"
					+ " (regionize-chunk-load) and the server log.");
		} else if (summary.ticking() == 0 && summary.ready() > 0 && summary.due() == 0) {
			lines.add("state: all regions between ticks (healthy); next deadline pending");
		} else if (summary.due() > 0) {
			lines.add("state: dispatching " + summary.due() + " due region(s) this cycle");
		} else {
			lines.add("state: normal");
		}
		return lines;
	}

	/** @return the health checks (/folia health). */
	public static List<Check> healthChecks(FabricFoliaEngine engine) {
		List<Check> checks = new ArrayList<>();
		FoliaDiagnostics.RegionSummary summary = regionSummary(engine);

		boolean anyLoadedSpawn = false;
		for (ServerLevel level : engine.levels()) {
			var pos = level.getRespawnData().pos();
			if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null) {
				anyLoadedSpawn = true;
				break;
			}
		}
		if (summary.total() > 0) {
			checks.add(Check.ok("region creation (" + summary.total() + " live regions)"));
		} else if (anyLoadedSpawn) {
			checks.add(Check.error("region creation — loaded chunks exist but regions == 0"));
		} else {
			checks.add(Check.warn("region creation — no regions and no loaded spawn chunks (server idle?)"));
		}

		long tickedRecently = 0;
		for (RegionScheduler scheduler : engine.schedulers().values()) {
			for (Region region : scheduler.liveRegions()) {
				if (region.tickCount() > 0) {
					tickedRecently++;
				}
			}
		}
		if (tickedRecently > 0) {
			checks.add(Check.ok("region ticking (" + tickedRecently + " region(s) have ticked)"));
		} else if (summary.total() > 0) {
			checks.add(Check.warn("region ticking — regions exist but none has ticked yet (fresh start?)"));
		} else {
			checks.add(Check.warn("region ticking — nothing to tick yet"));
		}

		var pool = engine.workerPool();
		int busy = pool.busyCount();
		int due = summary.due();
		if (due > 0 && busy == 0) {
			// A due region is dispatched within the coordinator's 1ms scan; a
			// sample taken between scan cycles can read stale. Re-probe once
			// after a short settle before declaring a failure (spec 37: log a
			// real violation, never a sampling artifact).
			try {
				Thread.sleep(50);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			busy = pool.busyCount();
			due = 0;
			for (RegionScheduler scheduler : engine.schedulers().values()) {
				due += scheduler.dueRegionCount();
			}
		}
		if (pool.workerCount() > 0 && (busy > 0 || due == 0)) {
			checks.add(Check.ok("worker pool (" + pool.workerCount() + " workers, " + busy + " busy)"));
		} else if (due > 0 && busy == 0) {
			checks.add(Check.error("worker pool — regions are due but no worker is executing them"));
		} else {
			checks.add(Check.warn("worker pool — no workers running"));
		}

		if (summary.queuedTasks() > 0 && busy == 0) {
			checks.add(Check.error("scheduler — " + summary.queuedTasks()
					+ " queued region tasks but no worker picked them up"));
		} else {
			checks.add(Check.ok("scheduler dispatch (queue=" + summary.queuedTasks() + ")"));
		}

		ThreadOwnership.Context context = ThreadOwnership.current();
		checks.add(Check.ok("thread ownership active (this thread: " + context.kind() + ")"));

		int activePatches = 0;
		for (var patch : com.palordersoftworks.fabricfolia.patches.PatchRegistry.patches()) {
			if (patch.status() == com.palordersoftworks.fabricfolia.patches.PatchRegistry.Status.ACTIVE) {
				activePatches++;
			}
		}
		checks.add(Check.ok("patch layer (" + activePatches + " active patches)"));

		return checks;
	}
}
