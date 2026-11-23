# Configuration reference

Fabric-Folia generates config/fabric-folia.yml at first startup. The file is schema-validated, missing keys are repaired, and comments are retained during writes. The current schema version is 2.

There is no /folia config command and no /folia reload command. Edit the file on disk and restart the server. Do not assume settings are hot-reloaded.

## Safe first-run settings

| Key | Default | Notes |
|---|---:|---|
| general.enabled | true | Loads the engine and ownership/scheduler infrastructure; false resolves all 13 patches inactive and leaves vanilla execution |
| general.regionized-gameplay | false | Requests selected entity/block-entity staged execution; currently blocked by the R4 safety interlock |
| general.regionized-random-ticks | false | Requests random-tick staging; currently blocked by the R4 safety interlock |
| gameplay.stage-player-path | false | Requests player connection/physics staging; currently blocked by the R4 safety interlock |
| patches.minecraft.entity-tick-optimization | false | Requests parallel entity ticking; currently blocked by the R4 safety interlock |
| patches.minecraft.chunk-tick-optimization | false | Requests block-entity and scheduled block/fluid tick staging; currently blocked by the R4 safety interlock |
| patches.minecraft.random-tick-optimization | false | Requests parallel chunk random ticks; currently blocked by the R4 safety interlock |
| threads.worker-threads | auto | Negative/auto values are resolved from available CPUs |
| threads.thread-check-mode | WARN | OFF, WARN, or STRICT |
| diagnostics.debug | false | Diagnostic detail toggle |
| diagnostics.debug-logging | false | Higher-volume dispatch/worker logging |
| diagnostics.profiling | false | Profiling collection |
| diagnostics.metrics | false | Declared metrics toggle; `/folia metrics` reports current counters regardless |
| diagnostics.watchdog | true | Region-stall watchdog |
| patches.enabled | true | Master optimization-patch switch |
| patches.fabricfolia.chunk-regionization | true | Registers loaded chunks with the regionizer; prerequisite for region ownership |
| patches.fabricfolia.region-lookup | true | Selects the region ownership lookup index |
| patches.fabricfolia.scheduler-dispatch | true | Scheduler dispatch optimization |
| patches.fabricfolia.task-queue | true | Queue accounting/drain optimization |
| gpu.enabled | false | Experimental OpenCL broadphase acceleration |

The current R4 safety interlock keeps entity ticking, block-entity ticking, scheduled ticks, random ticks, player physics, and packet-to-region mutation routing on vanilla execution even when their config requests are true. Startup emits a warning when it overrides a request. Region ownership, diagnostics, scheduler services and independent performance patches can remain active. This is a fail-closed safety measure, not a cross-region mutation bridge; the worker-side gameplay speedup is unavailable until that protocol is implemented and validated.

## Feature gates

### Regionized gameplay

general.regionized-gameplay is the master request for staged entity and chunk gameplay. The individual gates patches.minecraft.entity-tick-optimization and patches.minecraft.chunk-tick-optimization are also requests. The R4 safety interlock currently forces these built-in worker stages off regardless of those values, so vanilla owns all entity, block-entity and scheduled block/fluid ticking.

Keep entity staging disabled when mods depend on main-thread entity behavior. This includes item entity pickup, merging, movement, and despawn logic. Keep chunk staging disabled for redstone, block entities, pistons, droppers, rails, trapdoors, scaffolding, and other blocks whose scheduled updates assume server-thread execution. Vanilla still chooses what should tick. This is not a full independent Folia world tick loop.

### Player connection staging

gameplay.stage-player-path is a separate experimental request. It would move player connection processing and the player physics chain to region-owned contexts. The R4 interlock currently forces it off; player movement and interactions therefore remain on vanilla server-thread execution. Packet dispatch is also forced to the vanilla queue until region-owned packet mutation is safe.

### Regionized random ticks

general.regionized-random-ticks and patches.minecraft.random-tick-optimization are separate requests. The R4 interlock currently forces random-tick worker execution off, so vanilla performs random ticks on the server thread.

### Thread checking

threads.thread-check-mode accepts OFF, WARN, or STRICT. STRICT throws on detected ownership violations and is recommended for disposable development/test environments. WARN reports and continues. OFF disables checks. Passing these checks does not prove third-party mod safety.

### Diagnostics

diagnostics.debug and diagnostics.debug-logging increase diagnostic output. Enable them temporarily while collecting an incident reproduction; debug logging can be noisy. diagnostics.profiling controls per-region timing collection. diagnostics.metrics defaults to false in `ConfigSchema`; it does not currently gate the `/folia metrics` command, which reports the engine's current counters regardless of its value. diagnostics.watchdog enables the periodic region-stall scan.

### Patch toggles

patches.enabled controls the optimization-patch layer. Individual keys live under patches.minecraft, patches.network and patches.fabricfolia. Entity, block-entity/scheduled-tick, random-tick and packet-dispatch requests are forced off by the R4 interlock even if set to true. The chunk-regionization option is distinct from the region-lookup option; disabling chunk regionization prevents chunk-to-region registration and therefore disables the basis for staged region work. Do not change it on an active test without understanding the feature gates.

### GPU

gpu.enabled is an experimental OpenCL path for bulk axis-aligned bounding-box overlap filtering. It performs a boot parity check and falls back to CPU when unavailable or invalid. It is not required for regionized execution.

## Configuration recovery

If a setting is rejected, inspect the startup log for the key and expected value. Back up the file before hand-editing it. The loader repairs missing schema keys, but invalid values fail closed instead of silently guessing what the operator intended.
