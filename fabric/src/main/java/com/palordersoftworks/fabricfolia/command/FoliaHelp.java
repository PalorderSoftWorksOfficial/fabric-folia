package com.palordersoftworks.fabricfolia.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FoliaHelp {
    private FoliaHelp() {
    }

    public static List<String> overview() {
        return List.of(
                "<gold><bold>Fabric-Folia Commands</bold></gold>",
                FoliaMessages.RULE,
                "<aqua>/folia</aqua> <dark_gray>—</dark_gray> <gray>engine overview</gray>",
                "<aqua>/folia version</aqua> <dark_gray>—</dark_gray> <gray>version and build information</gray>",
                "<aqua>/folia help [topic]</aqua> <dark_gray>—</dark_gray> <gray>operator help</gray>",
                "<aqua>/folia status</aqua> <dark_gray>—</dark_gray> <gray>feature gates and engine state</gray>",
                "<aqua>/folia health</aqua> <dark_gray>—</dark_gray> <gray>invariant checks and health reasons</gray>",
                "<aqua>/folia regions [verbose]</aqua> <dark_gray>—</dark_gray> <gray>region and owner detail</gray>",
                "<aqua>/folia workers</aqua> <dark_gray>—</dark_gray> <gray>worker-pool utilization</gray>",
                "<aqua>/folia scheduler</aqua> <dark_gray>—</dark_gray> <gray>deadlines and queue diagnostics</gray>",
                "<aqua>/folia threads</aqua> <dark_gray>—</dark_gray> <gray>thread ownership diagnostics</gray>",
                "<aqua>/folia patches</aqua> <dark_gray>—</dark_gray> <gray>registered patch state</gray>",
                "<aqua>/folia vanilla status|kill</aqua> <dark_gray>—</dark_gray> <gray>vanilla override status and emergency kill switch</gray>",
                "<aqua>/folia entities</aqua> <dark_gray>—</dark_gray> <gray>entity tracking diagnostics</gray>",
                "<aqua>/folia metrics</aqua> <dark_gray>—</dark_gray> <gray>runtime counters and timings</gray>",
                "<aqua>/folia gpu [bench [boxes]]</aqua> <dark_gray>—</dark_gray> <gray>experimental OpenCL status and benchmark</gray>",
                "<aqua>/folia compat</aqua> <dark_gray>—</dark_gray> <gray>detected mod compatibility scan</gray>",
                "<aqua>/folia pin/unpin</aqua> <dark_gray>—</dark_gray> <gray>pin or unpin chunk coordinates</gray>",
                "<aqua>/folia shutdown</aqua> <dark_gray>—</dark_gray> <gray>request server halt; use /stop for routine shutdown</gray>",
                FoliaMessages.RULE,
                "<gray>Diagnostics need OP or the </gray><aqua>folia.command</aqua><gray> permission node.</gray>",
                "<gray>There is no /folia config or reload command; edit config/fabric-folia.yml and restart.</gray>");
    }

    public static java.util.Set<String> topics() {
        return TOPICS.keySet();
    }

    public static List<String> topic(String name) {
        return TOPICS.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    private static final Map<String, List<String>> TOPICS = new LinkedHashMap<>();

    static {
        TOPICS.put("regions", List.of(
                "<gold><bold>Help: regions</bold></gold>",
                "<gray>Chunks are grouped into region ownership areas. Region identities can change when areas split or merge.</gray>",
                "<gray>/folia regions shows totals; /folia regions verbose shows individual regions and ownership detail.</gray>"));
        TOPICS.put("workers", List.of(
                "<gold><bold>Help: workers</bold></gold>",
                "<gray>Workers are a bounded pool. A region is not pinned to one physical worker.</gray>",
                "<gray>Use /folia workers to inspect busy, waiting and queued work.</gray>",
                "<gray>Workers waiting while regions are overdue indicates a scheduling or coordination issue.</gray>"));
        TOPICS.put("scheduler", List.of(
                "<gold><bold>Help: scheduler</bold></gold>",
                "<gray>The scheduler tracks region deadlines and dispatches ready work to the bounded pool.</gray>",
                "<gray>Inspect queue depth, due regions, worker utilization and quiesce wait together.</gray>",
                "<gray>Staged work is retained under backlog; a growing queue means the current load is not keeping up.</gray>"));
        TOPICS.put("patches", List.of(
                "<gold><bold>Help: patches</bold></gold>",
                "<gray>Patch toggles select individual optimization and interception paths.</gray>",
                "<gray>patches.enabled is the master switch. The chunk-regionization patch is a prerequisite for chunk ownership.</gray>",
                "<gray>Do not disable chunk-regionization while relying on region-owned execution.</gray>",
                "<gray>Patch changes require a server restart.</gray>"));
        TOPICS.put("vanilla", List.of(
                "<gold><bold>Help: vanilla overrides</bold>",
                "<gray>patches.override-vanilla defaults to false and leaves the",
                "<gray>vanilla collision and entity-query paths untouched.</gray>",
                "<gray>Set it to true and restart to enable compatible overrides.</gray>",
                "<gray>/folia vanilla status shows the effective layer state.</gray>",
                "<gray>/folia vanilla kill immediately disables this layer until restart.</gray>"));
        TOPICS.put("health", List.of(
                "<gold><bold>Help: health</bold></gold>",
                "<gray>Reports the built-in invariants and available engine health checks.</gray>",
                "<gray>Use /folia health first, then /folia workers, scheduler, regions verbose and metrics.</gray>"));
        TOPICS.put("status", List.of(
                "<gold><bold>Help: status</bold></gold>",
                "<gray>Shows whether the engine is loaded and the configured feature gates.</gray>",
                "<gray>Regionized gameplay, player staging and random ticks are separate settings.</gray>",
                "<gray>Experimental execution is disabled by default and must be validated against your modpack.</gray>"));
        TOPICS.put("threads", List.of(
                "<gold><bold>Help: threads</bold></gold>",
                "<gray>Shows worker threads and their current logical ownership context.</gray>",
                "<gray>GLOBAL work runs on the Minecraft server thread after region workers quiesce.</gray>",
                "<gray>REGION is a logical context; worker threads may execute different regions over time.</gray>"));
        TOPICS.put("metrics", List.of(
                "<gold><bold>Help: metrics</bold></gold>",
                "<gray>Displays counters and timing measurements collected by the engine and dispatch layers.</gray>",
                "<gray>Compare queue depth, region duration and quiesce wait across repeated samples.</gray>",
                "<gray>Metrics do not replace a thread dump or a reproducible test case.</gray>"));
        TOPICS.put("gpu", List.of(
                "<gold><bold>Help: gpu</bold></gold>",
                "<gray>EXPERIMENTAL: optional OpenCL acceleration for bulk AABB overlap filtering.</gray>",
                "<gray>Enable gpu.enabled only after verifying the runtime and parity checks on the target hardware.</gray>",
                "<gray>The CPU path remains available. Use /folia gpu bench to measure the local workload.</gray>"));
        TOPICS.put("compatibility", List.of(
                "<gold><bold>Help: compatibility</bold></gold>",
                "<gray>/folia compat lists detected mods and the project's compatibility scan state.</gray>",
                "<gray>A scan result is not a guarantee that a mod is thread-safe.</gray>",
                "<gray>Test the exact production modpack with regionized features disabled first, then opt in on a disposable world.</gray>"));
        TOPICS.put("regionization", List.of(
                "<gold><bold>Help: regionization</bold></gold>",
                "<gray>Vanilla selects the tick work on the server thread. Fabric-Folia captures selected bodies and dispatches supported bodies to region-owned workers.</gray>",
                "<gray>Chunk ownership is updated as regions split and merge. Regions are execution contexts, not pinned worker threads.</gray>",
                "<gray>This is experimental staged-region execution, not a full independently ticking Folia world loop.</gray>"));
        TOPICS.put("configuration", List.of(
                "<gold><bold>Help: configuration</bold></gold>",
                "<gray>The file is config/fabric-folia.yml. Missing keys are repaired from the current schema.</gray>",
                "<gray>Regionized gameplay, player connection staging and random ticks are separate feature gates and are disabled by default.</gray>",
                "<gray>Edit the file on disk and restart. There is no /folia config or reload command.</gray>"));
        TOPICS.put("thread checks", List.of(
                "<gold><bold>Help: thread checks</bold></gold>",
                "<gray>threads.thread-check-mode accepts STRICT, WARN or OFF.</gray>",
                "<gray>STRICT throws on detected ownership violations. WARN reports and continues. OFF disables checks.</gray>",
                "<gray>Run STRICT on a disposable test server; these checks cannot prove every mod safe.</gray>"));
    }
}
