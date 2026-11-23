# Project status

<sup>Last reviewed: 2026-11-23</sup>

## Release decision

**NOT PRODUCTION READY. Production publication is blocked.**

The current work is an experimental staged-region execution project for Minecraft 26.2. It is not a complete independent Folia tick implementation. The R4 safety interlock forces built-in entity, block-entity/scheduled-tick, random-tick, player-physics and packet-to-region mutation staging off regardless of config. Region ownership and scheduler infrastructure can still run, but built-in gameplay ticks remain on the vanilla server thread.

The checked-in release approval remains BLOCKED. On the local dirty tree at 2026-11-23 (base HEAD c2fbe21, worktree /tmp/ff-reconcile), the coordinated module test pass completed **334 tests** — API 10, common 171, Fabric 153 — with zero failures, errors or skips. bash ./gradlew build --stacktrace exited 0. The resulting Fabric JAR (fabric/build/libs/fabric-folia-0.1.0.jar, SHA-256 fd528b632997838eaf5a01997eda27d1d58f5e7c9654049b71835005b6d9cf77) booted in disposable Minecraft 26.2 / Fabric API 0.161.0+26.2 instances. With general.enabled=false it reached Done (0.431s) with 0 active patches out of 13 and no worker-pool, world-attachment or stall-probe startup. With the engine enabled and all worker mutation options requested, the R4 interlock logged that entity/block-entity/scheduled/random/player/packet mutation staging remains disabled; the server also reached Done (0.431s). In both modes, an RCON redstone-lamp test confirmed the lamp changed to lit=true next to a redstone block and back to lit=false after power removal. No Mixin errors or missing classes were logged. The test instances were stopped. This is unit/build/boot/redstone smoke evidence only; player disconnect soak, persistence, production-world, compatibility and performance proof remain outstanding. The tree remains uncommitted; Jenkins release verification and production approval have not run.

On 2026-11-16 a candidate artifact was deployed to a Pelican-hosted Fabric 26.2 server running Fabric API 0.161.0+26.2 and restarted. The server reached `Done (` and reported `Fabric Folia is ready`; the startup compatibility scan reported 248 undeclared mods, zero declared supported mods and zero incompatible classifications. The existing config had `general.regionized-gameplay` and `gameplay.stage-player-path` enabled, and no players were connected. That evidence covers startup and mixin application only. Entity, block-entity, scheduled-tick, random-tick and player-path behavior were not exercised, and no soak, benchmark, persistence or production-world test was performed. A passing Gradle build or a startup-only smoke cannot satisfy the missing gameplay evidence.

## Runtime audit findings on the reconciled tree

Current defect status: R1 teleport routing is **accepted at E3** (14 focused tests and a clean boot) but still needs live /tp and portal verification. R2 disconnect/player-removal routing is **implemented at E3** with seven deterministic tests; live /kick, proxy-drop and reconnect testing remains outstanding, and exactly-once PlayerList.remove is structurally rather than live verified. R3 ownerOfChunk uses a concurrent published ownership map without the structure lock, with four deterministic publication tests; the enqueue phase-2 lock, transient false-null window, R11b split lock hold time and latency measurements remain open. R4 now has a **fail-closed built-in safety interlock**: entity, block-entity, scheduled-tick, random-tick, player-path and packet-dispatch worker paths are forced inactive regardless of config. Disposable redstone tests passed both with general.enabled=false and with the engine enabled while all staging requests were true. The synchronous cross-region mutation protocol itself remains unimplemented, so worker-side gameplay performance is unavailable and arbitrary plugin-scheduled region tasks still require owner-confined behavior. R5 deferred-mutation failure propagation is implemented at E3 with five tests; live crash-report injection remains unrun. L1 is fixed at E3 with bundled Adventure key and Examination transitives. R6 positionless producer migration remains open after the accepted-task retirement half was fixed; R11 register locking is fixed at E3, while R11b remains open. R1/R2/R4/R5 runtime gaps, R3 residuals and remaining D5–D9 work are tracked in the defect ledger and dated reports. Production approval remains blocked.

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
| Server-thread deferral | Ordinary world/player mutations remain queued until all region workers quiesce. The `addTicketAndLoadWithRadius` future bridge uses a separate barrier-safe queue that the server-thread quiesce pump replays while it drains chunk-executor work, allowing a worker blocked on that chunk request to finish. The fail-safe still waits indefinitely if a worker cannot be released; a REGION worker asking for an absent chunk with `create=false` now answers vanilla's own `null` immediately (counted as `worker getChunk absent no-create served null`), while `create=true` and below-status reads keep vanilla's blocking join, which the quiesce pump drains; the residual deadlock risk for a create that needs a later tick's chunk promotion is still open, and arbitrary blocking region tasks can still hold the barrier. |
| Player ownership | The follow-up adds one world-qualified per-player owner decision shared by entity and connection slices, with tests for chunk movement, backpressure and a cross-world change fallback. Active-player gameplay, login, teleport and respawn cases still need live verification. |
| Cross-world identity | Player-slice pins include a world key and cross-world capture falls back to vanilla-inline for the pair; teleport idempotence also uses world-qualified identity. Automated unit coverage exists, but successful cross-dimension live movement remains unverified. |
| Runtime startup | The 2026-11-16 smoke is one startup of one deployed artifact and reported no mixin error. The log is external, not checked in against a commit, and no repeated boot of the exact release commit is recorded. |
| Gameplay correctness | The 2026-11-16 smoke had no players connected. Entity, block entity, player, scheduled tick, random tick, teleport, respawn, unload and save paths still need disposable-world tests. |
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

The 2026-11-23 disposable 26.2 boot partially demonstrates gate 3 for the local dirty artifact: startup completed without a reported mixin or class-loading error, and diagnostic commands responded. It does not satisfy gates 4 through 6; no player gameplay or persistence test was run. The 2026-11-16 deployed artifact remains historical evidence only.

A normal Jenkins run creates a candidate. Only an explicitly requested, fully approved production release may publish to stable.
