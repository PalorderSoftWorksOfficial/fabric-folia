# Live runtime evidence and comparative benchmark

<sup>Author: runtime validation agent · date: 2026-11-22 · worktree: `/tmp/ff-reconcile` ·
tree: `c2fbe2135945e4e0ef42fc70094d3605ef03627d`, 45 dirty entries at build time ·
no commit, push, deploy or production contact</sup>

This report records what was actually executed and observed in this pass. Every
number has a path under §10. Sections §2 and §9 separate what is **tested** from
what remains **unverified**, and §7 records every deviation including the edits I
made and the edits other sessions made afterwards.

**One-line verdict:** the 26.2 Fabric toolchain, mixin loading and the server
lifecycle work on this tree — three 180 s runs on the pre-rewrite jar plus one
freshness boot on the final-tree jar (boot `Done`, zero mixin errors, 20 TPS
clientless, clean RCON stop) — but the entire `/folia` command surface fails at
runtime with a missing Adventure class, so MSPT/region/worker metrics are
unavailable and no engine performance claim can be made. The Fabrolia comparison
is **BLOCKED** on a Minecraft version boundary; nothing was compared and no
winner is named.

---

## 1. Host and limits

| Property | Value | Source |
| --- | --- | --- |
| CPU | 8 logical CPUs | `nproc`, `inventory-before.txt` |
| RAM | 15,692,364 kB total (≈14.6 GiB), ≈4.2–5.2 GiB available | `/proc/meminfo` at start/end |
| Swap | 6,291,448 kB total (≈1.7 GiB free at end) | `/proc/meminfo` |
| OS | Ubuntu 26.04.1 LTS, kernel 7.0.0-43-generic x86_64 | `/etc/os-release`, `uname` |
| Java | `java 25.0.1 2025-10-21 LTS` (Oracle, 25.0.1+8-LTS-27) | `java -version` |
| Gradle | wrapper 9.5.1, `org.gradle.jvmargs=-Xmx2G` | `gradle/wrapper/gradle-wrapper.properties` |
| Server heap | `-Xms2G -Xmx2G`, G1, AlwaysPreTouch (smoke profile) | instance `start.sh` |

Environmental confounders present for every run, not started by this session and
**not** killed: a foreign Minecraft server `java -jar leaf.jar` (PID 98738,
≈2.55 GiB RSS, 17.4 % CPU as a lifetime average over 2.7 h), a Velocity proxy
(port 25577), Jenkins (8081), Redis (6379). Benchmark numbers below are therefore
valid for this host's conditions but not a clean-room measurement.

The 40-client profile was not attempted: this host has ≈14 GiB RAM, and no
verified native 26.2 protocol client exists in the harness. The full profile's own
`HOST_MIN_RAM_GB=32` gate would refuse it by construction.

---

## 2. Tested facts (class V — observed here)

1. The tree **did not compile** on arrival. `:fabric:compileJava` failed at
   `EntityTeleportMixin.java:72`: `transitionKey(Entity) is not public in
   RegionTransitions; cannot be accessed from outside package`.
   Log: `/tmp/ff-runtime-evidence/build-full.log` (EXIT=1), repeated in
   `build-full-2.log` (EXIT=1).
2. After the visibility fix and the test-fixture correction in §7.1, one command
   produced a green build: `BUILD SUCCESSFUL`, `EXIT=0`, elapsed 6 s
   (`build-full-4.log`), with **129** `:fabric:test` tests executed and
   `:common:test` / `:api:test` up-to-date from the same tree state.
3. Across modules the tree carries **300** tests — api 10, common 161, fabric 129
   — with **0 failures, 0 errors, 0 skipped**. Per-module provenance is in §3.
4. The 26.2 Fabric server loads the built jar, applies all mixins and reaches
   `Done (`. Three independent boots: `Done (5.241s)`, `Done (5.038s)`,
   `Done (4.946s)`; wall time from launcher start to `Done` was ≈18 s each.
5. **Zero mixin warnings and zero mixin errors** appear in any of the three
   consoles. The boot gate's mixin-error section is empty in all three runs.
6. The server ticks at 20 TPS clientless with no players connected, measured from
   `time query gametime` deltas (method and values in §5).
7. `save-all flush`, `forceload add/remove`, `time set day` and `time query
   gametime` all work over RCON.
8. Every `/folia` command is broken at runtime: 134 command responses in run 3
   carry `Error executing: folia … (java.lang.NoClassDefFoundError: Could not
   initialize class com.palordersoftworks.fabricfolia.command.FoliaMessages)`, and
   `/folia metrics` additionally reports `NoClassDefFoundError:
   net/kyori/examination/Examinable` (P1 defect, §6).
9. Each run stopped cleanly: the engine logged `SUCCESS: Engine shut down
   cleanly. All region work drained; vanilla saved the worlds.`, the JVM exited,
   and ports 26800/26801 were closed afterwards (verified again after run 5).
10. No crash reports were produced by any run (`crash-reports none` in each
    `steps.tsv`), and no leftover server or Gradle process remains.
11. The released Fabrolia binaries declare `minecraft: ~26.3`. Therefore a
    same-version comparison against this 26.2 baseline is impossible (§8).
12. The **final R1 tree** was rebuilt (`:fabric:jar`, EXIT=0), verified by `javap`
    to contain `TransitionToken.matches`, and booted once (run 6): `Done (4.920s)`,
    48 mods, 0 mixin warnings/errors, 60 s at ~20.0 TPS, clean stop. The
    stale-jar gap is closed (§4.2).

---

## 3. Build and unit-suite evidence

### 3.1 Commands and outcome

| # | Command | Result | Log |
| --- | --- | --- | --- |
| 1 | `bash ./gradlew :common:test :fabric:test :api:test build --stacktrace` | EXIT=1, `:fabric:compileJava` failed (visibility) | `/tmp/ff-runtime-evidence/build-full.log` |
| 2 | same, after visibility fix attempt 1 | EXIT=1, `invalid canonical constructor in record EntityTransitionKey` | `build-full-2.log` |
| 3 | same, after visibility fix attempt 2 | EXIT=1, `129 tests completed, 7 failed` (`EntityTeleportDispatchTest` ×5, `PlayerTeleportRoutingTest` ×2) | `build-full-3.log` |
| 4 | same, after test-fixture correction | **EXIT=0, BUILD SUCCESSFUL in 6 s** | `build-full-4.log` |

Commands were run from `/tmp/ff-reconcile`. `./gradlew` is not executable on this
checkout and `/tmp` is `noexec`, so `bash ./gradlew` is used. Tree at test time:
`c2fbe2135945e4e0ef42fc70094d3605ef03627d`, 45 dirty entries
(`/tmp/ff-runtime-evidence/tree.txt`, `git-status.txt`).

### 3.2 Test counts and provenance

| Module | Classes | Tests | Failures | Errors | Skipped | Executed when |
| --- | --- | --- | --- | --- | --- | --- |
| api | 2 | 10 | 0 | 0 | 0 | earlier run, inputs unchanged (Gradle `UP-TO-DATE` in run 4) |
| common | 28 | 161 | 0 | 0 | 0 | run 1 (02:28:11), module untouched by later edits |
| fabric | 31 | 129 | 0 | 0 | 0 | run 4 (02:30:46), the green run |
| **total** | **61** | **300** | **0** | **0** | **0** | |

Correction to an earlier figure: the reconciled tree carries **300** tests at this
point, not 135 and not the 280 recorded in `manager-review.md`. `common` grew from
148 to 161 and `fabric` from 122 to 129 with the new suites
(`RegionSchedulerSplitRehomeTest`, `EntitySchedulerLifecycleTest`,
`EntityTeleportDispatchTest`, `PlayerSliceAtomicityTest`,
`DeferredBarrierDeadlockTest`, `RegionChunkAccessGuardTest`). The source of
truth is the XML set under `{api,common,fabric}/build/test-results/test/`.

`ConfigSchemaPatchTest` (previously excluded and untested) ran in this session as
part of the common 161 and passed.

### 3.3 The 7 failures, before the fixture correction

All seven were the same shape: the decision was `INLINE` where the fixture
expected `DISPATCHED`/`DROPPED`. Root cause is geometric, not logic: at
`attachWorld(…, 10, …)` the regionizer derives `mergeRadius=3` and
`emptyRadius=4`, and `addChunk` merges every region within
`mergeRadius + emptyRadius = 7` sections (8 chunks per section). Chunk `(0,0)`
(section 0) and chunk `(50,50)` (section 6) are **6 sections apart**, so they are
one region, and the current thread really is on the destination region. The
fixtures' comment "a DIFFERENT region" was wrong. Moving the source to
`(400,400)` (section 50) makes the separation real. Assertions were not weakened.

---

## 4. Isolated server boot

Instance: `/home/administrator/.cache/ff-qa-evidence/instances/smoke-01`, built by
`tools/freebuff/qa/assemble_server.sh --run-id smoke-01`, outside the repository,
loopback only, `pause-when-empty-seconds=0`, `online-mode=false`,
`max-tick-time=60000`, ports 26800/26801, seed `4242424242`, world `qa-world`.

| Role | Artifact | SHA-256 |
| --- | --- | --- |
| game bundle | `~/.gradle/caches/fabric-loom/26.2/minecraft-server.jar` | `cdacdfb25898de5e4b4b0e5ddcc2722f77067e46605709c2d886c000ebb63ec5` |
| loader | `fabric-loader-0.19.3.jar` | `73eed8c34bbad0320a2a3cba5346351e822f74f82b3f3c060574068474132958` |
| API | `fabric-api-0.161.0+26.2.jar` | `e5b858ceb13290c274e31cb888ff5a1a40cb91067e7ffab0b5b775aec51e216a` |
| mod under test | `fabric-folia-0.1.0.jar` | `0ac65238959e237e8036a35b91a192330cd772a579f22ae17905e595ab7b048e` |
| config | `tools/freebuff/qa/configs/baseline.yml` | `f7cdb897f86be2b6169f8d8b6f151218513e8ddc6eb77add8deabc80f95143b8` |

Two mod jars exist and both are identified exactly:

| Jar | Built | SHA-256 | Covers |
| --- | --- | --- | --- |
| benchmark jar (runs 3–5) | 2026-11-22 02:28:55 +0100, 1,274,049 B | `0ac65238959e237e8036a35b91a192330cd772a579f22ae17905e595ab7b048e` | tree **before** the runtime session's 02:40 R1 rewrite |
| final-tree jar (run 6) | 2026-11-22 02:52:26 +0100, 1,273,790 B | `06e852018bd19858683362deb0ad54341b83e830d5e9c1921eaacd6ba3b8d76a` | current tree, `RegionTransitions.java` mtime 02:40:11 |

**Attribution:** runs 3–5 are the benchmark evidence for the 02:28 tree. Run 6
(§4.2) is a freshness check that proves the **final R1 tree** compiles, loads and
boots; it is not a benchmark (60 s window).

The mod declares `fabricloader >=0.19.3`, `minecraft ~26.2`, `java >=25`,
`fabric-api >=0.160.0+26.2` and nests five jars: adventure-api, adventure-text-
minimessage, common, fabric-folia-api, snakeyaml-engine.

### 4.1 Boot sequence (run 3; runs 4 and 5 identical in shape)

- `Loading 48 mods:` … `fabricloader 0.19.3`, `minecraft 26.2`, `java 25`,
  `fabricfolia 0.1.0` with its five nested jars.
- Mixin subsystem `0.8.7`, MixinExtras `0.5.4`, compatibility level `JAVA_25`.
- `[FabricFolia] Region workers: 4`, async scheduler 4 threads, patches
  `6 active / 13 registered (7 blocked)`.
- Regions attached for `minecraft:overworld`, `the_nether`, `the_end`
  ("structure-only" mode; regionized random ticks disabled by config).
- `SUCCESS: Fabric Folia is ready.`
- `Done (5.241s)! For help, type "help"`.

Only JVM warnings are present: `--enable-native-access` for JNA, and
`sun.misc.Unsafe::objectFieldOffset` from JOML. No mixin warnings of any kind.

### 4.2 Final-tree freshness run (run 6)

Command: `bash ./gradlew :fabric:jar --console=plain` — `BUILD SUCCESSFUL`,
**EXIT=0**, 2 s, `9 actionable tasks: 1 executed` (`:fabric:jar`);
`:fabric:compileJava` was `UP-TO-DATE` because the runtime session had already
compiled the rewritten source at 02:40:25, after the source mtime 02:40:11. The
jar therefore contains the current classes, proven by disassembly:
`javap -p -c -cp fabric/build/libs/fabric-folia-0.1.0.jar
com.palordersoftworks.fabricfolia.engine.RegionTransitions` shows the call
`RegionTransitions$TransitionToken.matches:(Ljava/lang/String;IIL…EntityTransitionKey;)Z`,
a symbol that does not exist in the pre-rewrite jar.

Log: `/tmp/ff-runtime-evidence/build-jar-run6.log`. Tree unchanged:
`c2fbe2135945e4e0ef42fc70094d3605ef03627d`, 45 dirty entries.

Instance `smoke-02` (fresh assembly, manifest records the new SHA-256):

| Observation | Value |
| --- | --- |
| `Done` line | `[02:53:04] [Server thread/INFO]: Done (4.920s)! For help, type "help"` |
| Mods loaded | 48 (`Loading 48 mods`) |
| Mixin warnings / errors | **0 / 0** (`boot-gate.txt` mixin section empty; console grep 0) |
| Sampling | 60 s window, 5 s interval, 9 samples (8 TPS intervals) |
| TPS (gametime deltas) | mean 20.000, min 19.938, p50 19.983, p95 20.082, max 20.082 |
| Heap used (last) | 703.2 MiB of 2 GB |
| JFR | `…/runs/smoke-01-run6/recording.jfr`, 8,513,259 B |
| `/folia health` | still fails: `NoClassDefFoundError: Could not initialize class …FoliaMessages` — L1 unchanged, not fixed |
| Stop | RCON `stop`; engine `SUCCESS: Engine shut down cleanly. All region work drained; vanilla saved the worlds.`; JVM exited; ports closed |
| Steps | `stop:rcon` = 1 (known socket-close artifact), `stop:wait` = 0, `crash-reports` = 0, rest 0 |

Run 6 confirms the final R1 tree loads and runs on 26.2. It does **not** make the
engine metrics available (L1 is unchanged) and does not replace runs 3–5 as
benchmark evidence.

---

## 5. Metrics (clientless)

Method: `tools/freebuff/qa/sample_metrics.py --mode sample` issues
`time query gametime` every 5 s over RCON; TPS is total tick delta ÷ elapsed
wall time between the first and last successful sample. It never derives TPS from
warning cadence. `protocol_clients = 0` in every run; the manifest records
`requested_client_target: 0` for the smoke profile.

| Run | Sampling window | Samples | TPS mean | TPS min | TPS p50 | TPS p95 | TPS max | Heap used (last) | JFR |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| smoke-01-run3 | 180 s / 5 s | 26 (+1) | 20.003 | 19.908 | 20.011 | 20.090 | 20.091 | 793.6 MiB | 9,525,097 B |
| smoke-01-run4 | 180 s / 5 s | 26 (+1) | 20.000 | 19.892 | 19.986 | 20.114 | 20.129 | 788.1 MiB | 9,701,149 B |
| smoke-01-run5 | 180 s / 5 s | 26 (+1) | 20.002 | 19.901 | 19.995 | 20.086 | 20.112 | 789.9 MiB | 9,511,111 B |
| smoke-01-run6 (final tree, 60 s) | 60 s / 5 s | 8 (+1) | 20.000 | 19.938 | 19.983 | 20.082 | 20.082 | 703.2 MiB | 8,513,259 B |

Runs 3–5 are the benchmark set (180 s each, pre-rewrite jar). Run 6 is the
final-tree freshness check (60 s, new jar); its TPS is quoted for completeness,
not as a performance comparison between the two jars.

Independent cross-check from the raw CSV: run 5 `gametime` advanced 71 → 3,547
ticks over 175 s ≈ 19.95 TPS, consistent with the sampler.

Heartbeat/liveness: `gametime` advances monotonically, all 27 samples succeeded,
and `liveness` (alive after sampling) is 0 in all three runs.

Heap and GC from `jcmd`/JFR (run 5): `GC.heap_info` at end of run — G1 total
reserved/committed 2,097,152 K, **used 733,050 K ≈ 715.9 MiB**; JFR (443 s,
9.5 MB) records **9 young** and **6 old** garbage collections, 30
`GCHeapSummary` events, 3,189 `ExecutionSample`, 21,397
`NativeMethodSample`, 44,945 `ThreadPark`. All four
`FabricFolia-Worker-1..4` threads are present in `Thread.print.txt`.

**Not measured, and why:** MSPT p50/p95/p99/max, worker utilization, queue depth,
region count and per-region TPS are all `null`. They are produced by `/folia
metrics`, `/folia health`, `/folia workers`, `/folia regions` and
`/folia scheduler`, and every one of those commands fails (§6). These fields are
**command failures, not idle values**, and must not be read as "0 ms" or "no
regions". `/folia metrics` is the only path to the engine's 100-sample MSPT rings.

---

## 6. Defect found by the live boot — P1, not repaired

**Symptom.** Every `/folia` subcommand returns an error over RCON:

```
Error executing: folia metrics (java.lang.NoClassDefFoundError: net/kyori/examination/Examinable)
Error executing: folia health (java.lang.NoClassDefFoundError: Could not initialize class com.palordersoftworks.fabricfolia.command.FoliaMessages)
Error executing: folia workers / regions verbose / scheduler (… Could not initialize class … FoliaMessages)
```

134 such responses in run 3's workload, plus 1 `Examinable` failure.

**Raw evidence paths**
(`R = /home/administrator/.cache/ff-qa-evidence/runs`):

- `R/smoke-01-run3/sample/raw/20261122T013334-metrics.txt` — the `Examinable` failure
- `R/smoke-01-run3/sample/raw/20261122T013334-health.txt` — `FoliaMessages` init failure
- `R/smoke-01-run3/sample/raw/20261122T013334-{workers,regions,scheduler}.txt`
- `R/smoke-01-run{3,4,5}/sample/raw/*` — same failures in all three runs
- `R/smoke-01-run*/workload.json` — `{"status":"executed","commands":14}` (the
  commands ran; the mod raised the error)
- `R/smoke-01-run*/jcmd/` — heap, thread dump, class histogram
- `R/smoke-01-run*/recording.jfr` — JFR

**Observation, not diagnosis.** The built jar nests `adventure-api-4.23.0.jar` and
`adventure-text-minimessage-4.23.0.jar` but the class `net/kyori/examination/
Examinable`, which `adventure-api` requires, is absent from the nested set and
from the server classpath; `FoliaMessages` then fails static initialisation and
every command that renders a message dies. Per the scope freeze I did **not**
change build files or Java. This is a packaging/classpath defect to be fixed by
the runtime/core owner with a regression that asserts the nested jars cover
Adventure's transitive `api`+`examination`+`key` set and that `/folia health`
answers over RCON in a boot test.

---

## 7. Deviations, and the edits I made

### 7.1 My edits (disclosed; nothing reverted)

| File | Exact change | Why | mtime (local) |
| --- | --- | --- | --- |
| `fabric/src/main/java/…/engine/RegionTransitions.java` | `static record EntityTransitionKey` → `public static record`; its compact constructor `EntityTransitionKey {` → `public EntityTransitionKey {`; `static EntityTransitionKey transitionKey(Entity)` → `public static …` | The tree could not compile at all: `EntityTeleportMixin` (package `…mixin`) called the factory from package `…engine`. No logic, field or behaviour changed. | first ~02:28:56, follow-up ~02:29:10; file later rewritten by the runtime session at 02:40:11, my visibility changes still present at lines 102–110 |
| `fabric/src/test/java/…/engine/EntityTeleportDispatchTest.java` | 5 × `addChunk(50, 50)` → `addChunk(400, 400)` | 5 tests failed because the fixture assumed chunk 50 is a different region; at simulation distance 10 the merge radius makes it the same region (§3.3). Assertions untouched. | ~02:30:20; file later rewritten by runtime at 02:40:19 |
| `fabric/src/test/java/…/engine/PlayerTeleportRoutingTest.java` | 2 × `addChunk(50, 50)` and 2 × `ownerOfChunk(50, 50)` → `(400, 400)` | Same fixture defect, 2 failing tests. Assertions untouched. | 02:30:37 (still this version) |

No production config, Gradle file or mixin was edited. Nothing in this list was
reverted; `RegionTransitions` and `EntityTeleportDispatchTest` are now owned by
the runtime session.

### 7.2 Evidence attribution (before vs after my edits)

- `build-full.log` and `build-full-2.log`: **before** the fixture change; compile
  failures, caused by the visibility block, not by tests.
- `build-full-3.log`: **after** the visibility fix, **before** the fixture
  change; 129 fabric tests, 7 failures (§3.3).
- `build-full-4.log`: **after** the fixture change; green, 129 fabric tests
  executed, common/api up-to-date.
- Runs 3–5 used the jar built at 02:28:55, i.e. from the tree **after** my
  visibility fix and fixture change but **before** the runtime session's 02:40
  rewrite. Those runs remain the benchmark evidence and say nothing about the
  later R1 code.
- Run 6 used the jar built at 02:52:26 from the final tree (source mtime 02:40:11,
  classes 02:40:25, `TransitionToken.matches` present in the class), so it is the
  only runtime evidence that covers the final R1 rewrite; it is a load/boot check,
  not a benchmark.

### 7.3 Harness defects and environment deviations

| # | Deviation | Effect | Workaround (outside the repo) |
| --- | --- | --- | --- |
| H1 | `/tmp` is mounted `noexec` | `assemble_server.sh` succeeds but `start.sh` exits 126 (`Permission denied`) | QA_HOME relocated to `/home/administrator/.cache/ff-qa-evidence` (exec-capable, outside repo) |
| H2 | Assembled classpath has the vanilla bundle libraries + loader jar only; the loader needs its own deps | run 2 died: `IllegalStateException: ASM not detected on the classpath` | `loader-deps.txt` in the instance + 6-line append in that instance's generated `start.sh`, adding ASM 9.10.1 (×5) and sponge-mixin 0.17.3 exactly as resolved by `:fabric:dependencies` |
| H3 | Harness never writes `eula.txt` | run 3's first attempt refused to start (`You need to agree to the EULA`) | `eula.txt` with `eula=true` written in the disposable instance |
| H4 | `sample_metrics.py --mode stop` raises `RconError: connection closed by server` when the server closes RCON as it shuts down | `stop:rcon` step = 1, so `steps_all_zero=false` even though the process exited cleanly (`stop:wait 0`, engine "shut down cleanly") | recorded, not repaired |
| H5 | Sampler treats a failed `/folia` command as missing data (nulls) rather than a hard failure | silent null MSPT/region columns | exposed here as §6; sampler should fail the run when a metrics command errors |
| H6 | Instance reused for three runs, world directory deleted before runs 4 and 5 | keeps all three runs "fresh world, same seed"; run 3 was also fresh | documented; separate instance per run would be stricter |
| H7 | Foreign `leaf.jar` server (2.5 GiB RSS) and other daemons on the host | CPU/memory contention during all runs | documented; not killed |

Two aborted boots (run 1 = noexec; run 2 = missing ASM; run 3's first attempt =
EULA) are harness failures, not benchmark data. Only runs 3, 4 and 5 are used.

---

## 8. Fabrolia black-box reference — **BLOCKED**

> **Update 2026-11-23:** the blocker is now proven in both directions. Fabrolia
> 3.2.6 boots on MC 26.3 (`Done (6.090s)`, TPS 20.001, 180 s) and Fabric-Folia
> 0.1.0 boots on 26.2, but each rejects the other's version in Fabric Loader
> (`HARD_DEP_INCOMPATIBLE_PRESELECTED`), and no 26.3 protocol client exists
> (minecraft-data stops at 1.21.11; Azalea HEAD is protocol 776/26.2). Full
> evidence, protocol and gameplay findings:
> `tools/freebuff/reports/qa-26.3-benchmark.md`. The verdict here is unchanged:
> nothing compared, no winner.

### 8.1 Pinned candidate

| Field | Value |
| --- | --- |
| Project | `https://github.com/DUzzL/fabrolia` |
| Release tag | `f_3.2.6` — `https://github.com/DUzzL/fabrolia/releases/tag/f_3.2.6` |
| Asset | `fabrolia-alpha_3.2.6.jar` — `https://github.com/DUzzL/fabrolia/releases/download/f_3.2.6/fabrolia-alpha_3.2.6.jar` |
| Size | 2,845,205 bytes |
| SHA-256 | `42678345eca6bc5b99ed9f3e405f98bc4d40f39ead15bcc1a55d5815a90dd3ca` |
| Declared version | `3.2.6-alpha` (`fabric.mod.json`) |
| Declared Minecraft | **`~26.3`** (≥26.3, <26.4) |
| Declared loader / API / Java | `fabricloader: *`, `fabric-api: *`, `java: >=25` |
| Declared breaks | `moonrise *`, `openpartiesandclaims *`, `c2me <0.4.2-alpha.0.88` |
| License | **GPL-3.0** (declared in `fabric.mod.json`; `LICENSE_Fabrolia` present in the jar) |
| Published | 2026-11-21T13:46:32Z |

Other published releases were checked and are also 26.3, so no 26.2 build exists:

| Release | Asset SHA-256 | Declared Minecraft |
| --- | --- | --- |
| `f_3.2.1` | `e24dad66bd8a726c1ed5519d3ddf66e5b84710246adc32f8722e19752151bbb3` | `~26.3` |
| `f_3.2.0` | `70b5e7d5c4ab4a72ed0bae354fb16bead211db628ff24e99fc9d3ed039623063` | `~26.3` |

Artifacts and hashes are kept outside the repository at
`/tmp/ff-runtime-evidence/fabrolia/` (`fabrolia-alpha_3.2.6.jar`, `3.2.1`, `3.2.0`,
`sha256.txt`). Release bodies were fetched from the public GitHub API for release
notes only.

### 8.2 Blocker

The host project baseline is Minecraft **26.2**; the released Fabrolia binaries
require **26.3** and the 26.3 loader/API matrix (0.19.5 / 0.161.0+26.3 as recorded
in the earlier reference reports). Installing a 26.3 toolchain, booting Fabrolia,
or comparing it to this tree would compare unlike versions and would prove
nothing, so **none of that was done**:

- Fabrolia was **not** installed, **not** booted, **not** benchmarked.
- No 26.3 toolchain was downloaded or assembled.
- No A/B run exists; therefore **no winner, no speedup, no TPS/equivalence claim**
  between Fabric-Folia and Fabrolia.
- No Fabrolia source was fetched, read, copied, ported or derived. Only the
  published jar's `fabric.mod.json`, release notes and file listing were
  inspected as black-box metadata.

A same-version comparison becomes possible only when a Fabrolia build declaring
`~26.2` is published (or this project moves to 26.3 and re-baselines), on a host
that can run both with identical MC/loader/API/Java, seed, distances, JVM flags,
heap, mod list and duration, with alternated run order.

---

## 9. Benchmark methodology and status

Method intended and partially executed: same host, OS, Java build, MC 26.2,
seed `4242424242`, view distance 6 / simulation distance 4, identical JVM flags
and 2 GB heap, identical mod list (Fabric API + the mod under test only), 180 s
window at 5 s intervals, fresh world per run, sequential runs (alternation is
moot because there is only one candidate).

What exists: three Fabric-Folia clientless runs (§5). What does **not** exist:
MSPT percentiles, region counts, queue depth, worker utilization, a second
candidate, and any player-load measurement. Consequently this is a **baseline, not
a comparison**. It must be labelled *clientless* wherever cited, and it must not
be used to infer performance under connected players.

---

## 10. Evidence index

| Path | Contents |
| --- | --- |
| `/tmp/ff-runtime-evidence/tree.txt`, `git-status.txt` | HEAD and 45-entry dirty set at build time |
| `/tmp/ff-runtime-evidence/build-full{,-2,-3,-4}.log` | compile failures, 7-test failure, green build with counts |
| `/home/administrator/.cache/ff-qa-evidence/instances/smoke-01/` | disposable instance (benchmark jar 0ac6523…): `server.properties`, `instance.env`, `qa/instance-manifest.json`, `qa/bundle-extract.json`, `qa/artifacts.tsv`, patched `start.sh`, `loader-deps.txt`, `eula.txt` |
| `/home/administrator/.cache/ff-qa-evidence/instances/smoke-02/` | disposable instance for run 6 (final-tree jar 06e8520…), same layout, manifest records the new SHA-256 |
| `/home/administrator/.cache/ff-qa-evidence/runs/smoke-01-run6/` | final-tree freshness run: boot gate, console, 60 s sample, raw `/folia` errors, jcmd, 8.5 MB JFR, steps, manifest |
| `/tmp/ff-runtime-evidence/build-jar-run6.log` | `:fabric:jar` rebuild: BUILD SUCCESSFUL, EXIT=0, elapsed, task list |
| `…/runs/smoke-01-run3/` | first full run: `boot-gate.txt`, `server-console.log`, `sample/{metrics.json,samples.csv,raw/}`, `workload.json`, `jcmd/`, `recording.jfr`, `qa/steps.tsv`, `manifest.json` |
| `…/runs/smoke-01-run4/`, `…/run5/` | identical layout, runs 2 and 3 |
| `…/runs/smoke-01-run1/`, `…/run2/`, `…/run3/` (first attempt) | aborted boots: noexec, missing ASM, EULA — kept as failure evidence |
| `/tmp/ff-runtime-evidence/fabrolia/` | pinned Fabrolia jars (3.2.6, 3.2.1, 3.2.0), `sha256.txt` — outside the repo |
| `/tmp/ff-runtime-evidence/qa/` | early noexec attempt (assembly only) |

Every run's `manifest.json` records `protocol_clients: 0`,
`evidence_level: clientless-server-side`, the artifact SHA-256 list, ports, heap
and sampling parameters. `steps_all_zero` is `false` in all three runs solely
because of the `stop:rcon` step (H4).

---

## 11. Verdict and next actions

**Tested (class V, this host):** the tree compiles only after a visibility fix;
300 tests pass (api 10 / common 161 / fabric 129; 0 failures, 0 errors, 0 skipped);
the 26.2 Fabric server with this mod loads all mixins with zero mixin warnings,
reaches `Done` in ≈5 s (≈18 s wall), holds ~20.0 TPS clientless across three
180 s runs, uses ~716–794 MiB of a 2 GB heap with 9 young / 6 old collections in
443 s, and stops cleanly over RCON with no crash reports and no leftover
processes. Separately, the **final R1 tree** (jar `06e8520…`, source 02:40:11) was
rebuilt at exit 0, verified to contain `TransitionToken.matches`, and booted once
(run 6): `Done (4.920s)`, 48 mods, 0 mixin warnings/errors, ~20.0 TPS over 60 s,
clean stop — so the freshness gap is closed while the pre-rewrite runs remain the
benchmark evidence.

**Blocked (class B/E0):** the `/folia` command surface, and therefore all engine
MSPT/region/worker metrics, is unusable due to the missing Adventure
`examination` class (P1). Fabrolia comparison is blocked on the 26.3 vs 26.2
boundary. The 40-client benchmark is deferred (14 GiB host, no verified native
26.2 client).

**Unverified:** anything about R1 teleport **behaviour** at runtime (run 6 proves
the final jar boots; it exercised no teleport, portal, mount or disconnect path);
player-driven load, packet latency, mounts,
containers, portals, disconnect durability; behaviour with C2ME/Lithium/VMP/TIS
or any other mod; memory or TPS stability beyond 443 s; and any claim that
Fabric-Folia is faster, slower or equivalent to Fabrolia.

**Next actions, in order:**

1. Runtime/core: fix the missing Adventure transitive nesting and add a boot
   regression that asserts `/folia health` answers over RCON (P1, §6).
2. Runtime: R1 final tree is now proven to load (run 6); the remaining R1 work is
   behavioural, so add a disposable-world teleport/portal check to turn R1 from
   E1 into E3/E4.
3. QA: teach `assemble_server.sh` about the loader's own dependencies, write
   `eula.txt`, treat RCON close-after-`stop` as success, and fail the run when a
   metrics command errors (H2–H5).
4. QA/manager: re-baseline on a 32 GiB host for the full profile and the
   40-client stand once a native 26.2 protocol client is verified.
5. Manager: keep Fabrolia labelled BLOCKED until a `~26.2` asset exists or this
   project moves to 26.3.
