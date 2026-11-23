# Engine-off vanilla fallback and redstone smoke

Date: 2026-11-23
Minecraft: 26.2
Fabric API: 0.161.0+26.2
Artifact: fabric/build/libs/fabric-folia-0.1.0.jar
SHA-256: fd528b632997838eaf5a01997eda27d1d58f5e7c9654049b71835005b6d9cf77

## Code changes verified

- general.enabled=false is resolved before startup returns. Patch layers are explicitly disabled before the master-off early exit.
- The engine-off path does not attach the chunk-regionization callback, create a region scheduler/worker pool, or start the stall probe.
- Server-thread registration and deferred-work diagnostics are installed only after the master switch is confirmed on.
- The server brand and dedicated-server GUI overrides are gated by general.enabled.
- Connection queue draining uses the original vanilla Consumer.accept path when the engine is absent or packet dispatch is disabled. The previous null-engine dispatch path could discard pending connection actions.
- Packet network-context tagging and entity-command re-home are gated by the active packet-dispatch patch.
- TicketStorage, ChunkMapTracking, ServerChunkCache ticket loading, and light-engine mutation deferrals bypass their custom queues when the engine is absent.
- NetworkDispatch connection actions and light-engine mutations execute inline when the engine is absent.
- The R4 safety interlock overrides all requests for entity ticks, block-entity ticks, scheduled block/fluid ticks, player connection/physics staging, random ticks, and packet-to-region mutation dispatch. The interlock is fail-closed even when an operator configures those keys as true.

## Unit and build evidence

One coordinated test run:

    bash ./gradlew :common:test :fabric:test :api:test --rerun-tasks --stacktrace

Result: exit 0, 334 tests — common 171, Fabric 153, API 10 — zero failures, errors or skips.

Full build:

    bash ./gradlew build --stacktrace

Result: exit 0. Non-fatal warnings remain for ThreadDeath deprecation on Java 25, unchecked operations in LevelScheduleTickMixin, OpenCL FFM restricted native access, and Gradle deprecations.

## Test A — general.enabled=false

Disposable instance ff-l1-health, config set to general.enabled: false while patches.enabled and other patch keys remained true.

Observed:
- Server reached Done (0.431s).
- Startup logged Patches: 0 active / 13 registered (13 blocked).
- Startup logged Region system: disabled (general.enabled=false) - vanilla execution, no worker threads.
- No region scheduler initialization, worker thread, world-attachment or stall-probe startup messages.
- No Mixin application errors, missing classes or startup exceptions.

RCON commands in a force-loaded test chunk:

    forceload add 0 0
    setblock 0 100 0 minecraft:redstone_lamp
    setblock 1 100 0 minecraft:redstone_block
    execute if block 0 100 0 minecraft:redstone_lamp[lit=true] run say REDSTONE_POWERED_OK
    execute unless block 0 100 0 minecraft:redstone_lamp[lit=true] run say REDSTONE_POWERED_FAIL
    setblock 1 100 0 minecraft:air
    execute if block 0 100 0 minecraft:redstone_lamp[lit=false] run say REDSTONE_UNPOWERED_OK
    execute unless block 0 100 0 minecraft:redstone_lamp[lit=false] run say REDSTONE_UNPOWERED_FAIL

Server log contains REDSTONE_POWERED_OK and REDSTONE_UNPOWERED_OK; neither failure marker was logged.

## Test B — engine enabled, staging requested, R4 interlock active

Disposable instance config requested all of:
- general.regionized-gameplay: true
- general.regionized-random-ticks: true
- gameplay.stage-player-path: true
- entity, chunk, and random-tick optimization patches true
- patches.network.packet-dispatch: true

Observed:
- Startup logged the R4 safety-interlock warning.
- Patches: 5 active / 13 registered (8 blocked).
- Region system initialized with four idle worker threads.
- Regionized gameplay bodies: disabled (R4 mutation-safety interlock).
- Player connection staging: disabled (R4 mutation-safety interlock).
- Regionized random ticks: disabled (vanilla execution; R4 mutation-safety interlock).
- Server reached Done (0.431s).
- No Mixin application errors or missing classes.

The same powered/unpowered redstone-lamp RCON checks passed. Server log contains R4_INTERLOCK_POWERED_OK and R4_INTERLOCK_UNPOWERED_OK; neither failure marker was logged.

Both test servers were stopped, forceload removed, no test-server process remains, and the disposable instance config was restored to its original values.

## Limits

This verifies a redstone lamp's neighbor-update behavior in a disposable world, not every redstone mechanism or arbitrary third-party mod behavior.

R4's synchronous cross-region mutation protocol is not implemented. The interlock prevents built-in Fabric-Folia staged gameplay and packet-routing paths from executing world mutation on region workers. Region scheduler/API tasks supplied by third-party mods remain subject to their ownership contract; no general cross-region vanilla write bridge is claimed. The performance benefit from parallel built-in gameplay ticking is intentionally unavailable until that protocol is implemented and tested.
