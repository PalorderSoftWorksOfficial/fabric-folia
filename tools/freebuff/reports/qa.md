# QA, performance and release audit — time-boxed first pass

Date: 2026-11-22. Scope: working tree at `HEAD=94976ef` (`main`, 4 commits behind `origin/main`) plus uncommitted user work. All claims below were produced by reading the tree and by read-only shell inspection; **no Gradle build, test run, server boot or RCON session was performed**, per the time-box and the shared-worktree rules.

Nothing outside `tools/freebuff/qa/` and `tools/freebuff/reports/` was created or modified. The dirty tree and `fabric/run/` are untouched.

## 1. Verdict

The automated suite is substantial and the release *verifier* is real. What is missing is everything between JUnit and a published artifact: nothing in this repository can currently measure the mod's runtime behaviour, prove a mixin applies, or produce a repeatable benchmark. Jenkins validates tests, metadata and checksums, then archives a candidate — it never boots the jar.

Three consequences for the manager:

1. **No release candidate can be produced from this checkout.** `scripts/verify_release.py` fails any build whose `git status` is non-empty. That is correct behaviour, but it means current evidence must be regenerated after the dirty work is committed and the 4 upstream commits are integrated.
2. **The last recorded test run is stale.** Build artifacts contain 263 executed tests (`api 10`, `common 145`, `fabric 108`, 0 failures/errors/skips, dated 2026-11-17 17:09). The tree now declares 264 `@Test` (fabric 109): `StallProbeTest.startupInitializationIsSuppressedUntilTheFirstServerTick` was added at 17:19, after that run, and the dirty changes touch staging, deferral and routing code. Upstream additionally adds 5 test classes and 3 mixins that this checkout does not contain.
3. **The runtime evidence classes in `docs/status.md` stay accurate only because they are honest about being absent.** There is no soak, no benchmark, no disposable-world gameplay run, and no compatibility matrix anywhere in the repository, and no tooling to produce them.

No production certification is implied here.

## 2. What exists (verified)

### Test suites

| Module | `@Test` in source | Last recorded run | Notes |
|---|---|---|---|
| api | 10 | 10 | Public surface/contract tests exist (previously reported as absent) |
| common | 145 | 145 | Regionizer, schedulers, queues, config, patches, metrics, GPU parity |
| fabric | 109 | 108 | Real-engine staging, deferral, routing, commands, stall probe |
| total | 264 | 263 | plain JUnit 5, no Mockito, global 120 s timeout |

Only self-skipping test: `GpuParityTest` (`assumeTrue`), which `verify_release.py` explicitly allowlists. `Thread.sleep` appears in 9 test classes (22 call sites); concurrency timing is the main flake surface.

### Jenkins (`Jenkinsfile`)

Stages: Checkout → Tests (`clean :common:test :api:test :fabric:test`, `junit allowEmptyResults: false`) → optional Cross-OS → Build → Verify Candidate (`scripts/verify_release.py`) → Archive Candidate → optional Production Approval → Publish Stable (`main` + `PRODUCTION_RELEASE`).

Actually enforced by `scripts/verify_release.py`: per-module test floors (common 144, api 10, fabric 81), zero failures/errors, unexpected-skip rejection, single distributable jar, exact `fabric.mod.json` id/name/version/dependencies, mixin classes present and `required`/`defaultRequire>=1`, nested API jar identity, standalone API jar without Minecraft classes, SHA-256 manifest, clean checkout. Production release additionally requires `release/production-approval.json` status `APPROVED` for the exact version+commit with three checked-in evidence files (currently `BLOCKED`).

Not enforced: any server boot, any mixin-application assertion, any gameplay test, any benchmark/soak, any JFR/Spark capture, artifact re-download verification.

## 3. Required-case coverage

`EXISTING` = named automated tests only. Nothing in this table is live evidence.

| Required case | Status | Existing automated coverage | Gap |
|---|---|---|---|
| Vanilla and mod baseline | NONE | — | no boot harness, no paired-run procedure |
| Multi-region throughput | PARTIAL | `RegionizerConcurrencyTest`, `RegionPipelineTest` (regions form/drain) | no throughput measurement, no workload |
| One hot island | PARTIAL | `RegionStageHubParallelGateTest`, `RegionStageHubBacklogTest` | no hot-region workload, no per-region percentile |
| Split/merge and chunk churn | EXISTING | `RegionizerChunkLifecycleTest`, `TickLifecycleTest`, `RegionPipelineTest`, `WorkerPoolGateTest.churnOfSubmitAndQuiesceLosesNothing`, `ChunkResidencyTest` | no live chunk load/unload churn |
| Migration / teleport / portals / mount / respawn | PARTIAL | `PlayerTeleportRoutingTest`, `PlayerRespawnRoutingTest`, `RegionTransitionIdentityTest`, `RegionEntityRegistryTest`, `PlayerSliceAtomicityTest` (world change between captures) | no portal dispatch test, no mount/dismount test, no live verification |
| Packet / inventory / container / disconnect | PARTIAL | `NetworkDispatchTest`, `PlayerRespawnRoutingTest.containerInteractionPacketsStayOnTheServerThreadForModCallbacks`, `ServerThreadDeferralTest` | no disconnect/retirement path, no inventory-transaction ordering test |
| Scheduled/random ticks, redstone, farms, mob caps, block entities, POI, raids | PARTIAL | `RegionPendingTicksTest`, `StageRetryTest`, `RegionTickInterceptorPolicyTest`, `RegionStageHubBacklogTest` | no redstone/hopper chain, no mob-cap/POI/raid test, no ordered block-entity tick test |
| Save / restart / recovery | NONE | — | `WorkerPoolGateTest.burstQueuedWhileClosedAllRunAfterReopen` is pool reopen, not world persistence |
| Worker crash / saturation / interruption / handoff / shutdown | PARTIAL | `WorkerPoolGateTest` (quiesce, wedged task, shutdown abandonment), `RegionFailurePolicyTest`, `RegionStageHubFatalTest`, `DeferredBarrierDeadlockTest`, `ServerThreadDeferralTest` | no worker-death capacity test, no drain-before-save test, abandonment counter not observable |
| Compatibility matrix | NONE | docs-only per-mod notes in `docs/compatibility/` | no harness, no pinned mod jars available offline, no matrix artifacts |

## 4. Benchmark contract: what is measurable today

To satisfy the required benchmark discipline (fixed game build, seed, pregenerated world, JVM flags, heap, affinity, mods, clients, workload; Spark/JFR; p50/p95/p99 MSPT, main-thread wait, per-region TPS, worker utilization, queues, memory, GC), the following is the honest state of the instrumentation:

| Field | Available | Source / limitation |
|---|---|---|
| Server MSPT p50/p95/p99/max | yes | engine rings, `Server MSPT (tick work)` line; last 100 samples only |
| Main-thread wait | partial | `quiesce/flush/drain/body` p50/p95 rings; server-thread **deferral** wait time is not timed |
| Per-region TPS | derived only | `/folia regions verbose` exposes `ticks=`; TPS must be computed from sample deltas |
| Per-region MSPT p50/p95/p99 | **no** | only `mspt=` (EWMA avg) and `peak=` per region; no per-region ring accessor |
| Worker utilization | approximate | engine `utilization~%` (busy nanos / elapsed / threads) and `/folia workers` busy count |
| Queues | yes | `peakQueueDepth`, `queued region tasks`, `due regions` |
| Memory / GC | external only | no field in `/folia metrics`; must come from `jcmd GC.heap_info` / PerfCounter or JFR |
| Machine-readable output | **no** | `/folia metrics` is text only |
| TPS | yes, if measured correctly | `time query gametime` deltas over RCON; never warning cadence |
| JFR / Spark | JFR yes (JVM flag), Spark **absent** | no Spark jar in the tree or caches |

Two traps that would silently invalidate any clientless benchmark on 26.2:
- `pause-when-empty-seconds` (confirmed present in the 26.2 server `DedicatedServerProperties`) pauses an empty server; it must be `0` and recorded, or TPS/MSPT measure the pause, not the engine. `StallProbe` suppresses empty-server pauses above a 1200-tick threshold, so the engine will look healthy while the benchmark is meaningless.
- The dev-server default run directory `fabric/run/` already holds a world/config/log tree. Benchmarks must never use it; use a disposable instance.

## 5. Findings (QA/perf/release scope)

| ID | Severity | Evidence | Finding |
|---|---|---|---|
| QA-101 | HIGH | VERIFIED | No boot or mixin-application gate exists in CI, and the only in-tree boot automation is now broken: `:fabric:verifyServerGui` executes `compat/gui_icon_check.py`, but `compat/` was deleted in `8a7dd96`. The task would fail if invoked and is not invoked. |
| QA-102 | HIGH | VERIFIED | No runtime QA harness: no protocol client, no disposable-world driver, no workload scripts, no benchmark worlds, no Spark jar, no mod jars for the compatibility matrix. `TESTING.md` lists these as production requirements with no implementation path. |
| QA-103 | HIGH | VERIFIED | Last recorded test evidence (263 tests, 17:09) predates the current tree (264 `@Test`, edits until 17:39) and the tree is 4 commits behind `origin/main`, which adds 5 test classes and 3 mixins. Any coverage claim must be re-established after integration, not reused. |
| QA-104 | MEDIUM | VERIFIED | `TESTING.md` records 239 tests (common 144, fabric 85, api 10); the tree has 264 (145/109/10). `verify_release.py` floors (144/10/81) are 25 below the tree, so a large test-class deletion would still pass the floor. |
| QA-105 | MEDIUM | VERIFIED | Per-region percentiles and deferral wait time are not instrumented, and `/folia metrics` has no machine-readable export. The required benchmark report cannot be produced from existing metrics alone; it needs the sampler plus explicit per-field caveats. |
| QA-106 | MEDIUM | VERIFIED | `WorkerPool.abandonedTaskCount()` exists and is tested, but no reader surfaces it: it is absent from `/folia metrics` and from `/folia status`. Shutdown work loss is therefore counted internally and invisible to operators (QA-041 partially closed). |
| QA-107 | MEDIUM | VERIFIED | Clientless 26.2 runs idle-pause by default (`pause-when-empty-seconds`), and `StallProbe` deliberately suppresses that pause. A benchmark that does not set it to `0` and record it produces plausible-looking but meaningless MSPT/TPS. |
| QA-108 | LOW | VERIFIED | Cross-OS stage still wraps agent allocation *and* the test body in a 1-minute timeout and converts any timeout into "skipped" (QA-003 unresolved). |
| QA-109 | LOW | VERIFIED | No JFR, Spark, metrics snapshot or benchmark artifact is collected or archived by CI, so no runtime evidence can be attached to a commit automatically. |
| QA-110 | INFO | VERIFIED | Dirty work fixes three prior blockers: dedicated `patches.fabricfolia.chunk-regionization` key with a test (QA-020), gates pushed at scheduler creation in `FabricFoliaEngine` (QA-021), staged/player paths default **off** in `ConfigSchema` (QA-072), and `MIXINS.md` now documents all 26 mixins (QA-052). None of these fixes adds a test pinning the scheduler/regionizer gate push. |

## 6. Next tests, in priority order

1. **Patch-gate application** — `fabric/src/test/.../FabricFoliaPatchConfigTest`: build an engine with `patches.fabricfolia.scheduler-dispatch=false` and `region-lookup=false`, assert `RegionScheduler`/`WorldRegionizer` report the gate off. Pins QA-021 against regression.
2. **Shutdown accounting is observable** — `common/.../scheduler/WorkerPoolGateTest`: assert `abandonedTaskCount()` equals the queued count after `shutdown`, and add a `/folia metrics` line assertion (production change required; test can be written against the accessor first).
3. **Drain before save** — engine-level test that `stopEngine` returns only after all region queues drain and no worker is busy, plus a move of the staged-body flush to the drain-capable path (QA-041/042).
4. **Worker death** — fatal throw in one worker task: assert capacity is reported (live worker count or `WORKER_DIED`), not silently reduced (QA-044).
5. **Portal and mount/dismount routing** — cover `Entity.handlePortal` → `teleport(TeleportTransition)` virtual dispatch for a player, and ownership re-pin across mount/dismount within one tick (the class of bug Fabrolia's own notes record).
6. **Disconnect mid-slice** — connection slice staged while the player is removed; assert no body runs against a retired player and no work is dropped unreported.
7. **Block-entity ordering** — two block entities in one chunk whose tick bodies must preserve vanilla order; assert per-chunk batch ordering survives staging (redstone/hopper prerequisite).
8. **Per-region percentile plumbing** — expose `MsptRing.percentileMillis` per region so the benchmark contract can report per-region p95 rather than average only.

## 7. Harness delivered (not run)

Added, isolated, no Gradle invocation and no server boot:

- `tools/freebuff/qa/sample_metrics.py` — dependency-free RCON client (raw protocol, no `mcrcon` module) with three modes:
  - `--mode sample --port <isolated> --password <p> --out <dir> --duration 300 --interval 5 --pid <server pid> --tag <name>`: samples `time query gametime` (TPS from deltas), `/folia metrics`, `/folia regions verbose`, `/folia workers`, `/folia health`, `/folia scheduler`, `jcmd GC.heap_info` and `jcmd PerfCounter.print`; writes `samples.csv`, `metrics.json` and per-sample raw text. Percentiles reported are the engine's own; TPS is never inferred from warning cadence; per-region TPS is derived from `ticks=` deltas; every non-instrumented field is listed in `metrics.json.limits`.
  - `--mode exec --workload <file>`: executes a declarative workload (`CMD`, `REPEAT n`, `WAIT s`, `REQUIRES_CLIENT`); a workload that needs clients reports `blocked_client` instead of pretending to run.
  - `--mode stop`: clean RCON stop.
- `tools/freebuff/qa/qa_lib.sh` — shared helpers for the orchestrator still to be written: process/port inventory (`ss`/`netstat`), isolated port-pair selection from `QA_PORT_BASE` (default 26800), `QA_HOME` outside the repository (default `~/.cache/fabric-folia-qa`), SHA-256, port-close wait, and a guard that signals only a PID whose command line references the instance directory (SIGTERM to its own process group, then SIGKILL, never a blanket `pkill`).

Both files pass `python3 -m py_compile` / `bash -n`; nothing was executed against a server, so this report contains **no measured numbers**.

Still required before a benchmark can be quoted: `assemble_server.sh` (extract the 26.2 bundler into a disposable instance, add loader + Fabric API + mod jar, write isolated `server.properties` with `pause-when-empty-seconds=0` and RCON, seed pinned), `run_bench.sh` (fixed JVM flags/heap/affinity, `-XX:StartFlightRecording`, `Done (` wait, mixin-error fail, RCON stop, trap cleanup, artifact manifest), pinned workload files, and a client driver for the packet/container/mount/disconnect cases.

## 8. Limits of this pass

- No build, test, boot or RCON command was run; every count is from source or from the stale 2026-11-17 build artifacts.
- Upstream `origin/main` (4 commits) is not integrated; its 3 extra mixins and 5 extra test classes change the mixin/test totals and may invalidate specific routing findings.
- The sampler's parsers were written against the current `snapshotLines()`/`FoliaDiagnostics` formats and verified only by compilation, not by a live sample.
- Nothing here changes a release gate, and nothing here supports calling the project production-ready.
