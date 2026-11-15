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

Regionized gameplay is disabled by default. Check general.regionized-gameplay in config/fabric-folia.yml. Player connection staging is separately controlled by gameplay.stage-player-path; random tick staging is controlled by general.regionized-random-ticks. Restart after changing these settings. There is no /folia config or reload command.

## Worker queue grows or server stalls

Check worker utilization, pending region queue entries, tick duration, quiesce wait, and per-region health. Do not clear queues or kill worker threads as a workaround. Backpressure retains queued work rather than intentionally skipping a gameplay round; sustained backlog means the workload or execution model is not keeping up.

A server-thread/worker wait cycle may indicate a deferred operation waiting on the wrong phase. Capture a thread dump before restarting and include the relevant region/queue diagnostics.

## Mixin or startup failure

Confirm the exact version constraints in fabric.mod.json and remove untested mods from a disposable copy of the server. Verify that the mixin configuration is present and that required injections apply. A successful Gradle build does not prove that every injection applies against a real server runtime.

## World or save problems

Do not troubleshoot on the only copy of a production world. Back up the world, test on a disposable copy, and validate shutdown, restart, chunk unload/reload, and world save before enabling experimental execution.
