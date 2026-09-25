/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The self-contained in-game documentation (spec 34): every command and
 * concept explained in-game — no external markdown hunting. Topics are
 * concise, operator-focused, and rendered through the shared MiniMessage
 * layer.
 */
public final class FoliaHelp {

	private FoliaHelp() {
	}

	/** @return the command overview lines (/folia help). */
	public static List<String> overview() {
		return List.of(
				"<gold><bold>FabricFolia Commands</bold></gold>",
				FoliaMessages.RULE,
				"<aqua>/folia</aqua> <dark_gray>—</dark_gray> <gray>server information overview</gray>",
				"<aqua>/folia version</aqua> <dark_gray>—</dark_gray> <gray>version and build information</gray>",
				"<aqua>/folia regions [verbose]</aqua> <dark_gray>—</dark_gray> <gray>region statistics and per-region detail</gray>",
				"<aqua>/folia workers</aqua> <dark_gray>—</dark_gray> <gray>worker-pool state and utilization</gray>",
				"<aqua>/folia scheduler</aqua> <dark_gray>—</dark_gray> <gray>scheduler health and queue diagnostics</gray>",
				"<aqua>/folia patches</aqua> <dark_gray>—</dark_gray> <gray>active optimization patches and their state</gray>",
				"<aqua>/folia health</aqua> <dark_gray>—</dark_gray> <gray>overall system health with reasons</gray>",
				"<aqua>/folia threads</aqua> <dark_gray>—</dark_gray> <gray>thread and region-ownership diagnostics</gray>",
				"<aqua>/folia help [topic]</aqua> <dark_gray>—</dark_gray> <gray>this documentation</gray>",
				FoliaMessages.RULE,
				"<gray>Diagnostics are OP-only; /folia and /folia version are open. Use </gray><aqua>/folia help <topic></aqua><gray> for details.</gray>");
	}

	/** @return the topic names accepted by /folia help <topic>. */
	public static java.util.Set<String> topics() {
		return TOPICS.keySet();
	}

	/** @return one topic's lines, or null when the topic is unknown. */
	public static List<String> topic(String name) {
		return TOPICS.get(name.toLowerCase(java.util.Locale.ROOT));
	}

	private static final Map<String, List<String>> TOPICS = new LinkedHashMap<>();

	static {
		TOPICS.put("regions", List.of(
				"<gold><bold>Help: regions</bold></gold>",
				"<gray>Regions group nearby loaded chunks into independently",
				"<gray>ticking execution units. Each chunk belongs to exactly one",
				"<gray>region; regions can merge when they become connected and",
				"<gray>split when they become independent again.</gray>",
				"<gray>Use </gray><aqua>/folia regions</aqua><gray> for counts, and</gray>",
				"<gray></gray><aqua>/folia regions verbose</aqua><gray> for per-region detail.</gray>"));
		TOPICS.put("workers", List.of(
				"<gold><bold>Help: workers</bold></gold>",
				"<gray>Workers are a bounded pool of threads that execute region",
				"<gray>ticks. Regions are not pinned to workers; a region may run",
				"<gray>on a different worker each tick. Waiting workers are healthy",
				"<gray>when no region is due; the scheduler wakes them when work",
				"<gray>arrives. Use </gray><aqua>/folia workers</aqua><gray> to see busy vs. waiting.",
				"<gray>Workers waiting while regions are overdue indicates a",
				"<gray>scheduling problem — see </gray><aqua>/folia scheduler</aqua><gray>.</gray>"));
		TOPICS.put("scheduler", List.of(
				"<gold><bold>Help: scheduler</bold></gold>",
				"<gray>The scheduler scans every millisecond for regions whose next",
				"<gray>tick deadline is due and dispatches them onto the worker pool.",
				"<gray>Each executed region tick re-arms its own deadline (20 TPS).",
				"<gray>Use </gray><aqua>/folia scheduler</aqua><gray> to see due regions, queue depth",
				"<gray>and reschedule failures; a clear diagnosis is shown when the",
				"<gray>pipeline stalls (for example chunks loaded but no regions)."));
		TOPICS.put("patches", List.of(
				"<gold><bold>Help: patches</bold></gold>",
				"<gray>FabricFolia optimizations are individually toggleable patches.",
				"<gray>Each patch takes an optimized execution path when enabled and",
				"<gray>falls back to the original vanilla/Fabric path when disabled;",
				"<gray>regionization and thread checks are never affected. Patches",
				"<gray>resolve once at server start (config changes need a restart).",
				"<gray>Use </gray><aqua>/folia patches</aqua><gray> to see every patch and its status."));
		TOPICS.put("health", List.of(
				"<gold><bold>Help: health</bold></gold>",
				"<gray>Runs the built-in invariants: region creation, region",
				"<gray>ticking, worker pool, scheduler, ownership, patch layer.",
				"<gray>Each check reports OK, WARNING or ERROR with a reason. Use",
				"<gray></gray><aqua>/folia health</aqua><gray> as the first stop when the server feels off."));
		TOPICS.put("threads", List.of(
				"<gold><bold>Help: threads</bold></gold>",
				"<gray>Shows the FabricFolia threads: the global scheduler thread,",
				"<gray>worker threads and per-thread region ownership. States shown:",
				"<gray>RUNNABLE, WAITING, TIMED_WAITING (efficient idle), BLOCKED.",
				"<gray>Workers are not pinned to regions; ownership changes per tick."));
		TOPICS.put("configuration", List.of(
				"<gold><bold>Help: configuration</bold></gold>",
				"<gray>config/fabric-folia.yml — general.enabled master switch,",
				"<gray>regionized-gameplay and regionized-random-ticks execution",
				"<gray>slices, threads.worker-threads, diagnostics toggles, and the",
				"<gray>patches section (each optimization's toggle). Patches and",
				"<gray>scheduler behavior resolve at startup: restart to apply."));
		TOPICS.put("thread checks", List.of(
				"<gold><bold>Help: thread checks</bold></gold>",
				"<gray>Regionized execution forbids cross-region state access. The",
				"<gray>thread-check mode (threads.thread-check-mode) controls what",
				"<gray>happens on violations: STRICT throws with a full report,",
				"<gray>WARN logs and continues, OFF disables checking. WARN is the",
				"<gray>default; keep the checks on — they are the proof the region",
				"<gray>model is functioning, not noise to disable."));
		TOPICS.put("regionization", List.of(
				"<gold><bold>Help: regionization</bold></gold>",
				"<gray>When chunks load they join the world's regionizer and form",
				"<gray>regions (with a buffer halo between them). Region state",
				"<gray>cycles READY → TICKING → READY; a ticking region never grows",
				"<gray>(new nearby activity waits in a transient region and merges",
				"<gray>at the tick's end). Dead sections purge and independent",
				"<gray>areas split off during the tick-end protocol."));
	}
}
