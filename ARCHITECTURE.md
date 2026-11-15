# Architecture

## Implementation model

Fabric-Folia is an experimental staged-region execution layer for Fabric. It does not replace vanilla's world tick loop with independent region-owned loops.

The current execution sequence is:

1. Vanilla performs its ordinary server-thread selection and determines which tick bodies should run.
2. Interception points capture selected bodies and their world/chunk positions.
3. The stage hub groups captured bodies by region and chunk, splits them into bounded queue tasks, and enqueues them.
4. Region schedulers execute queued bodies through a bounded worker pool in a REGION ownership context.
5. At the next server tick, the server thread closes the worker execution gate and waits for in-flight region work to quiesce.
6. Once workers are quiescent, queued GLOBAL callbacks run on the Minecraft server thread before the next vanilla world-mutation phase.

The server thread remains the authority for vanilla tick selection. This reduces the amount of vanilla behavior reimplemented by Fabric-Folia, but it also means the server-thread coordination window remains a performance constraint.

## Modules

| Module | Responsibility | Dependency rule |
|---|---|---|
| api | Public scheduler, region, and thread-context contracts | No Minecraft or Fabric Loader types |
| common | Regionizer, worker pool, schedulers, ownership tracking, configuration | Depends on api |
| fabric | Fabric lifecycle, mixins, commands, runtime adapters | Depends on common and api |

## Ownership and scheduling

A chunk position resolves to the region that currently owns it. A region identity is not a stable routing handle across splits and merges. Position-targeted queued work therefore carries its chunk position so split handling can partition it into the new owners' queues.

RegionScheduler schedules region-owned work. EntityScheduler follows an entity's current region. GlobalScheduler runs short server-wide work on the Minecraft server thread after worker quiescence. AsyncScheduler is for work independent of world state.

## Correctness boundaries

The scheduler and regionizer have unit and concurrency tests, but those tests do not model every Minecraft subsystem, Fabric mod, mixin interaction, world-save path, or real server lifecycle. The public mod integration remains experimental.

Known boundaries include vanilla-side tick selection, server-thread coordination barriers, separately gated random-tick interception, separately gated player connection staging, and incomplete third-party mod compatibility evidence.

## Current release posture

The release is not production-ready until a clean dedicated-server boot, mixin application check, gameplay tests on a disposable world, repeated save/restart validation, long-running region split/merge soak, supported-mod compatibility checks, and independent release review are recorded for the exact commit.
