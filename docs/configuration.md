# Configuration reference

Fabric-Folia generates config/fabric-folia.yml at first startup. The file is schema-validated, missing keys are repaired, and comments are retained during writes. The current schema version is 2.

There is no /folia config command and no /folia reload command. Edit the file on disk and restart the server. Do not assume settings are hot-reloaded.

## Safe first-run settings

| Key | Default | Notes |
|---|---:|---|
| general.enabled | true | Loads the Fabric-Folia engine and its integration layer |
| general.regionized-gameplay | false | Opts into selected entity/block-entity staged execution |
| general.regionized-random-ticks | false | Opts into the separate random-tick interception |
| gameplay.stage-player-path | false | Opts into player connection/packet staging; experimental |
| threads.worker-threads | auto | Negative/auto values are resolved from available CPUs |
| threads.thread-check-mode | WARN | OFF, WARN, or STRICT |
| diagnostics.debug | false | Diagnostic detail toggle |
| diagnostics.debug-logging | false | Higher-volume dispatch/worker logging |
| diagnostics.profiling | false | Profiling collection |
| diagnostics.metrics | true | Runtime metrics collection |
| diagnostics.watchdog | true | Region-stall watchdog |
| patches.enabled | true | Master optimization-patch switch |
| patches.fabricfolia.chunk-regionization | true | Registers loaded chunks with the regionizer; prerequisite for region ownership |
| patches.fabricfolia.region-lookup | true | Selects the region ownership lookup index |
| patches.fabricfolia.scheduler-dispatch | true | Scheduler dispatch optimization |
| patches.fabricfolia.task-queue | true | Queue accounting/drain optimization |
| gpu.enabled | false | Experimental OpenCL broadphase acceleration |

The safe defaults intentionally keep staged gameplay, random ticks, and player connection staging on vanilla execution. Enabling these options is an operator opt-in, not a production compatibility certification.

## Feature gates

### Regionized gameplay

Set general.regionized-gameplay to true only in a disposable world first. Selected entity and block-entity bodies are captured from vanilla's server-thread passes and later dispatched to owning-region workers. Vanilla still chooses what should tick. This is not a full independent Folia world tick loop.

### Player connection staging

gameplay.stage-player-path is a separate experimental gate. It moves selected player connection processing to region-owned contexts. Keep it disabled unless the player path, packet handoffs, teleport, respawn, migration and mod compatibility have been tested.

### Regionized random ticks

general.regionized-random-ticks is separately gated and disabled by default. Some mod interactions can suppress or invalidate this path; test random tick events and world mutation timing before enabling it.

### Thread checking

threads.thread-check-mode accepts OFF, WARN, or STRICT. STRICT throws on detected ownership violations and is recommended for disposable development/test environments. WARN reports and continues. OFF disables checks. Passing these checks does not prove third-party mod safety.

### Diagnostics

diagnostics.debug and diagnostics.debug-logging increase diagnostic output. Enable them temporarily while collecting an incident reproduction; debug logging can be noisy. diagnostics.profiling and diagnostics.metrics control profiling and metrics collection. diagnostics.watchdog enables the periodic region-stall scan.

### Patch toggles

patches.enabled controls the optimization-patch layer. Individual keys live under patches.minecraft, patches.network and patches.fabricfolia. The chunk-regionization option is distinct from the region-lookup option; disabling chunk regionization prevents chunk-to-region registration and therefore disables the basis for staged region work. Do not change it on an active test without understanding the feature gates.

### GPU

gpu.enabled is an experimental OpenCL path for bulk axis-aligned bounding-box overlap filtering. It performs a boot parity check and falls back to CPU when unavailable or invalid. It is not required for regionized execution.

## Configuration recovery

If a setting is rejected, inspect the startup log for the key and expected value. Back up the file before hand-editing it. The loader repairs missing schema keys, but invalid values fail closed instead of silently guessing what the operator intended.
