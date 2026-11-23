# Troubleshooting

## Start with the built-in diagnostics

Run these commands as an operator:

1. /folia health
2. /folia status
3. /folia workers
4. /folia scheduler
5. /folia regions verbose
6. /folia metrics

Record the full server log around startup and the first failure. Include the exact Minecraft, Fabric Loader, Fabric API, Java, Fabric-Folia, and modpack versions.

## Regionized gameplay does not run

Gameplay staging is disabled by default. Check general.regionized-gameplay and the individual patches.minecraft.entity-tick-optimization, patches.minecraft.chunk-tick-optimization, and patches.minecraft.random-tick-optimization gates in config/fabric-folia.yml. Player physics/connection staging is separately controlled by gameplay.stage-player-path. Keep these paths disabled for main-thread-only mods and redstone builds. Restart after changing these settings. There is no /folia config or reload command.

## Known unsupported paths

These boundaries apply only with the relevant staged path enabled:

- Player login placement is not region-aware. Login stays on vanilla's server-thread path; there is no region-owned placement or handoff at login.
- Portal search and creation stay on the origin context. Only the resulting teleport transition is routed to the destination context.
- Explosion block and entity damage is not region-aware. An explosion triggered from a staged entity body executes in that body's region context and can affect neighbouring regions.
- Direct `PlayerList.respawn` callers from other mods are not covered by the packet-level carve-out; only the normal respawn command is routed to the server thread.
- Redstone and hopper timing use global tick semantics. Per-region redstone time is not implemented.

These are design boundaries, not regressions. Disable the relevant staged path before investigating them on a disposable world, and report a reproducible case with the exact mod and versions.

## Worker queue grows or server stalls

Check worker utilization, pending region queue entries, tick duration, quiesce wait, and per-region health. Do not clear queues or kill worker threads as a workaround. When a world's staged backlog exceeds its retention limit, staging is suspended for that world and further bodies run vanilla-inline on the server thread until the queue drains. Staged entity, block-entity and scheduled-tick work is retained, not skipped; a sustained backlog means the workload or execution model is not keeping up, and tick rate may fall as a result.

The random-tick slice is different: a region whose queue is above its pass limit skips that random-tick pass, and a later pass re-offers the chunks. The skip can repeat while the queue stays above the limit, so expect growth or melting to be delayed under sustained saturation; this is not a lost or corrupted world.

Use `/folia scheduler` for queue depth and due regions, `/folia workers` for busy and idle workers, and `/folia metrics` for the staging, backlog and quiesce counters. A worker that never finishes can hold the tick barrier open; capture a thread dump before restarting. A region worker asking for an absent chunk without creating it answers `null` and increments `worker getChunk absent no-create served null`; a create or status-upgrade request keeps vanilla's blocking join and increments `worker getChunk creating/below-status vanilla joins`. A rising join count means a staged body is waiting on chunk work: keep those reads on the server thread or use an asynchronous chunk API without blocking on it.

A server-thread/worker wait cycle may indicate a deferred operation waiting on the wrong phase. Capture a thread dump before restarting and include the relevant region/queue diagnostics.

## Mixin or startup failure

Confirm the exact version constraints in fabric.mod.json and remove untested mods from a disposable copy of the server. Verify that the mixin configuration is present and that required injections apply. A successful Gradle build does not prove that every injection applies against a real server runtime.

## Diagnostic toggles

All diagnostics are configured in `config/fabric-folia.yml` and require a restart; there is no live reload.

| Key | Default | Use |
|---|---:|---|
| `diagnostics.debug` | false | Regionizer and scheduler decision logging; very verbose |
| `diagnostics.debug-logging` | false | Per-tick dispatch and worker-execution evidence plus the installed-mod classification table |
| `diagnostics.profiling` | false | Per-region tick timing diagnostics |
| `diagnostics.watchdog` | true | Periodic region-stall scan |
| `threads.thread-check-mode` | WARN | `STRICT` throws on ownership violations; `OFF` hides them |

Enable one toggle at a time, reproduce the incident, then turn it off. Debug logging does not change behavior, but it can dominate the log and the disk on a busy server.

## World or save problems

Do not troubleshoot on the only copy of a production world. Back up the world, test on a disposable copy, and validate shutdown, restart, chunk unload/reload, and world save before enabling experimental execution.
