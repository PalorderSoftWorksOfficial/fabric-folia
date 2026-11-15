# Project status

<sup>Last reviewed: 2026-11-15</sup>

## Release decision

**NOT PRODUCTION READY. Production publication is blocked.**

The current work is an experimental staged-region execution project for Minecraft 26.2. It is not a complete independent Folia tick implementation. Regionized gameplay and player connection staging are disabled by default; operators must explicitly opt in after testing their exact modpack.

The checked-in release approval is BLOCKED. No live dedicated-server boot or soak was performed during this engineering pass, and no production world was touched. A passing Gradle build cannot satisfy the missing runtime evidence.

## What is implemented

- Multi-module Java project: public API, loader-agnostic concurrency core, and Fabric integration.
- Region ownership and lifecycle management with split/merge hooks.
- Bounded worker pool, region scheduler, entity/global/async API surfaces.
- Staged execution at selected vanilla tick interception points.
- Operator diagnostics under /folia.
- Configuration schema and generated config/fabric-folia.yml.
- Jenkins candidate validation and SHA-256 artifact manifest.

## Fixes included in this candidate work

- Global scheduler callbacks moved off the independent daemon and onto the server thread after worker quiescence.
- Global scheduler now rejects submissions after close and measures delayed/repeating work in server ticks.
- Worker shutdown now prevents a dequeued task from starting after shutdown begins; abandoned queue entries are counted.
- Region chunk-registration config no longer reads the region-lookup setting.
- Scheduler-dispatch and owner-lookup config values are applied when each world scheduler is created.
- Staged bodies are grouped by target chunk so split handling can partition queued batches.
- Removed the overload rule that discarded whole staged gameplay rounds.
- Added focused regression tests for global execution context, shutdown, config toggles, split routing and retained staged rounds.
- Added standalone Maven Local API publication, API tests and developer documentation.
- Jenkins no longer publishes every main build directly to the stable channel.

## Open production blockers

| Area | Remaining evidence or work |
|---|---|
| Server-thread deferral | Deferrals are queued asynchronously; no blocking wait was found in the current deferral API. The quiesce path intentionally waits beyond its first timeout while in-flight workers remain, so a stuck worker can stall the server indefinitely. This needs a bounded operational recovery policy and a live soak. |
| Player ownership | Entity simulation and connection work must be proven atomic across all supported player paths and migration cases. |
| Cross-world identity | Region-player routing and teleport idempotence need world-qualified ownership keys and tests. |
| Runtime startup | A clean 26.2 dedicated-server boot with all required mixins applied has not been verified for this candidate. |
| Gameplay correctness | Entity, block entity, player, scheduled tick, random tick, teleport, respawn, unload and save paths need disposable-world tests. |
| Performance | Need repeatable multi-region and one-hot-region benchmarks, p95/p99 quiesce measurements, and long-running backlog/memory soak. |
| Compatibility | Need a version-pinned modpack matrix, including mods that assume main-thread execution. |
| Release review | Independent review of the exact release commit and all runtime evidence is required. |

## Release gates

1. All module tests and strict JUnit report validation pass.
2. The exact mod and API JARs pass metadata, mixin-class, archive and checksum validation.
3. An isolated dedicated server boots on the pinned environment and logs every required mixin application.
4. Disposable-world gameplay and persistence tests pass.
5. A long-running high-load soak shows no simulation loss, worker death, deadlock, save corruption or unbounded queue growth.
6. Compatibility matrix and known limitations are reviewed.
7. Independent review approves the exact commit and version.
8. release/production-approval.json is updated with the exact commit and checked-in evidence paths.

A normal Jenkins run creates a candidate. Only an explicitly requested, fully approved production release may publish to stable.
