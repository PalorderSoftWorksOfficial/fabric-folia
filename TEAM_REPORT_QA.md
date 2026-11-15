# Fabric-Folia — Independent QA / Security / Release Audit

**Role:** independent QA / security / release reviewer (read-only)
**Date:** 2026-11-15
**Scope:** repository state on `main` = `6db343d` ("docs: record Fill publisher permission fix"), working tree with only untracked team reports
**Deliverable:** this file only. No source, doc, build-file, Jenkinsfile, commit, push, build, test or server run was performed.
**Method:** full read of `FREEBUFF.md`, `Jenkinsfile`, all module build files, `gradle.properties`, `libs.versions.toml`, `fabric.mod.json`, `fabricfolia.mixins.json`, `README.md`, `TESTING.md`, `THREADING.md`, `MIXINS.md`, `COMPATIBILITY.md`, `docs/folia-parity.md`; source inspection of the engine, staging hub, schedulers, worker pool, command layer, config layer, watchdog/StallProbe, and every mixin; bytecode inspection of the named 26.2 jar with `javap`; set-difference checks between `mixins.json`/`MIXINS.md`, and between test sources and claimed coverage.

---

## 0. Verdict

The engine work is real and the test suite is genuine (209 `@Test` methods, real-engine fabric tests, no Mockito). The **release pipeline and several safety-critical wiring paths are not ready to carry a public artifact**:

1. **Nothing in CI ever boots the mod.** The only pipeline runs JUnit + `build` and then publishes. A 26.2 target drift, a nested-jar regression, or a mixin-apply failure ships green today. There is already a proven boot-verification pattern in-tree (`:fabric:verifyServerGui`) that CI does not use.
2. **Two patch-layer config keys are dead or cross-wired** (`patches.fabricfolia.region-lookup` also disables chunk regionization; `scheduler-dispatch`/`region-lookup` gates are never pushed because resolution runs before the engine exists). `/folia patches` can therefore report `DISABLED` for a patch that is running.
3. **Shutdown can discard work without a number.** Queue abandonment at `SERVER_STOPPING` (which happens *before* vanilla saves) is logged at most as a text line, and `WorkerPool.shutdown` returns silently when its 5 s deadline expires.
4. **The Fill publisher is unconditional on `main`, glob-selected, unsigned, unchecksummed, and unverified after publishing.**
5. **Documentation that ships inside the artifact (generated config comments) advertises a `/folia config` command and live reload semantics that do not exist**, and README/TESTING describe a much older milestone (and the wrong jar name).

None of this invalidates the live evidence recorded in `FREEBUFF.md`; all of it blocks a "stable" Fill publication.

**Publication recommendation:** do **not** publish `stable` to Fill from `main` until gates G1–G7 (§7) are hard-failing and findings QA-001, QA-004, QA-010, QA-020, QA-021, QA-040, QA-041 are resolved or explicitly accepted in writing.

---

## 1. Severity and evidence legend

| Severity | Meaning |
| --- | --- |
| **BLOCKER** | Must not ship a public/stable artifact until fixed or formally accepted. |
| **HIGH** | Release gate or correctness/safety defect; fix before next public build. |
| **MEDIUM** | Real defect or unverified claim; fix or add a gate this cycle. |
| **LOW** | Hygiene, diagnostics, or documentation; schedule normally. |

| Evidence | Meaning |
| --- | --- |
| **VERIFIED** | Proved in this audit by reading the exact code path, by `javap` against the named 26.2 jar, or by a mechanical set-difference. |
| **INSPECTED** | Read and reasoned about; the conclusion depends on a runtime condition this read-only audit could not execute (e.g. mixin application, Gradle/Loom behavior). |
| **UNKNOWN** | Cannot be established without a build or boot, which the reviewer is not permitted to run. |

---

## 2. Findings index

| ID | Area | Severity | Evidence | Finding |
| --- | --- | --- | --- | --- |
| QA-001 | CI / runtime smoke | **BLOCKER** | VERIFIED | No boot/mixin-apply/artifact-load gate anywhere in CI |
| QA-002 | CI / test reports | HIGH | VERIFIED | `allowEmptyResults: true`; no zero-test/skip gate; `:api:test` has no tests; tests run twice |
| QA-003 | CI / cross-OS | HIGH | VERIFIED | 1-minute `timeout` wraps the *test run*, and test timeouts are reported as "skipped" |
| QA-004 | Release / Fill | **BLOCKER** | VERIFIED | Publish on every `main` build, glob artifact selection, no checksum/signature/verification |
| QA-005 | Reproducibility | MEDIUM | VERIFIED | Loom `1.17-SNAPSHOT`, no `clean`, no timestamp normalization, no daemon pinning |
| QA-006 | Artifacts | MEDIUM | VERIFIED | README/artifact name mismatch (`fabric-0.1.0.jar` vs `folia-0.1.0.jar`) |
| QA-007 | CI / toolchain | MEDIUM | VERIFIED | No JDK 25 toolchain or Jenkins `tools` pin; `options.release = 25` only |
| QA-008 | CI hygiene | LOW | VERIFIED | No stage timeouts, no build discarder, no aggregated failure reporting |
| QA-010 | Metadata | HIGH | VERIFIED | `fabric-api: "*"` accepts any API version; no `breaks`/`conflicts` for documented-unsafe combos |
| QA-011 | Metadata | MEDIUM | UNKNOWN | Nested jars rely on Loom-generated `jars`; not present in source `fabric.mod.json` and unverifiable without an artifact |
| QA-012 | Metadata | LOW | VERIFIED | `logo.png` shipped but no `icon` declaration |
| QA-013 | Runtime budget | MEDIUM | INSPECTED | Async pool adds up to `worker-threads` threads + timer + global + watchdog + probe; config documents only the region pool |
| QA-020 | Config / patches | **BLOCKER** | VERIFIED | `regionize-chunk-load` is requested from the `region-lookup` key; disabling one cascades all staging patches to `BLOCKED_DEPENDENCY` |
| QA-021 | Config / patches | HIGH | VERIFIED | `scheduler-dispatch` and `region-lookup` gates are never applied (resolution runs while `engine == null`) |
| QA-022 | Config / reload | MEDIUM | VERIFIED | Generated config advertises `/folia config` and live reload; neither exists |
| QA-023 | Config / IO | MEDIUM | VERIFIED | Config rewritten every boot; read-only config disables the entire mod as "invalid" |
| QA-024 | Config / validation | LOW | VERIFIED | Unknown keys are flagged only as a YAML comment, never logged |
| QA-025 | Config / dead knobs | LOW | VERIFIED | `diagnostics.metrics` and `diagnostics.profiling` are never read |
| QA-030 | Commands / permissions | MEDIUM | VERIFIED | Only the pure OR rule is tested; real permission path, node identity and dispatcher registration untested |
| QA-031 | Commands / shutdown | LOW | VERIFIED | `/folia shutdown` text promises drain+save; `halt(false)` only clears `running` |
| QA-032 | Commands / threading | MEDIUM | INSPECTED | `CommandsMixin` cancels `performCommand` on network threads and re-dispatches fire-and-forget; untested |
| QA-040 | Gameplay correctness | **BLOCKER** (semantics) | VERIFIED | Slow-motion intake gate discards a backed-up region's whole staged round, permanently |
| QA-041 | Shutdown / task rejection | HIGH | VERIFIED | Queue/handoff abandonment at shutdown is silent (no metric), pool can time out without a diagnostic |
| QA-042 | Shutdown / save | HIGH | INSPECTED | No test or gate proves drain-before-save, or that a timed-out shutdown cannot mutate during `saveAllChunks` |
| QA-043 | Fatal handling | MEDIUM | VERIFIED | Fail-safe quiesce loop has no bound and no test; vanilla watchdog is the only exit |
| QA-044 | Fatal handling | MEDIUM | VERIFIED | A fatal error kills a worker permanently; no replacement, no metric |
| QA-045 | Fatal handling | MEDIUM | VERIFIED | `VirtualMachineError` swallowed in `RegionTransitions`, API delivery, and the outer region-tick catch |
| QA-046 | Mixin correctness | LOW | VERIFIED | `ServerLevelTickMixin` `@At("RETURN")` cannot clear the drain ThreadLocal on throw (single RETURN confirmed) |
| QA-047 | Diagnostics honesty | LOW | VERIFIED | `flushStaged` unconditionally reconciles `pendingBodies` to 0 when queues look empty |
| QA-048 | Ops | LOW | VERIFIED | `StallProbe` dump files have no retention cap |
| QA-049 | Diagnostics | LOW | VERIFIED | `engine.levels()` is derived from staging hubs → `/folia health` blind when gameplay staging is off |
| QA-050 | Mixin stability | MEDIUM | INSPECTED | Whole set is `required: true`/`require 1` with no priority tiers and no CI boot to catch target drift |
| QA-051 | Mixin stability | LOW | INSPECTED | GUI mixin is in the required set; only a headless boot can prove it applies |
| QA-052 | Mixin docs | LOW | VERIFIED | 3 of 26 mixins undocumented — including the two barrier hooks and the player-tick bounce |
| QA-053 | Hygiene | LOW | VERIFIED | `MinecraftServerMixin.java` is the only CRLF/4-space file in the tree |
| QA-054 | Mixin stability | LOW | INSPECTED | Ordinal-based `@ModifyArgs`/`@WrapOperation` targets are the most brittle form |
| QA-060 | Test coverage | HIGH | VERIFIED | No tests for permissions/dispatcher, config migration/IO, patch wiring, watchdog, shutdown, API module, artifact metadata |
| QA-061 | Test stability | MEDIUM | VERIFIED | 22 `Thread.sleep` sites; timing-based assertions on shared agents |
| QA-062 | Test stability | LOW | VERIFIED | GPU parity self-skips via `assumeTrue`; skip invisibility (see QA-002) |
| QA-070 | Docs | HIGH | VERIFIED | README/TESTING/parity describe older milestones and stale counts |
| QA-071 | Docs | MEDIUM | VERIFIED | `docs/mandates/` (referenced by AGENTS.md and dozens of comments) does not exist |
| QA-072 | Docs / defaults | HIGH | VERIFIED | Player-path staging defaults **on** in code while the handoff says default off; login placement is `NOT_IMPLEMENTED` |

---

## 3. Release pipeline, artifacts, reproducibility

### QA-001 — BLOCKER — no runtime smoke anywhere in CI (VERIFIED)

`Jenkinsfile` stages: Checkout → Test (`./gradlew :common:test :fabric:test :api:test`) → optional Cross-OS → Build (`./gradlew build`) → Archive (`fabric/build/libs/*.jar`) → Publish. There is no boot, no RCON, no mixin-apply assertion, and no check that the produced jar even loads.

Why it matters: the project's own history shows defects that only a boot catches — an invalid mixin descriptor, `MinecraftServerGuiMixin` targeting a GUI class, the brand NPE when `general.enabled=false`, the 26.2 `TicketType` bootstrap trap. AGENTS.md rule 5 requires a live boot *when mixins change*; the pipeline cannot tell whether mixins changed.

Already available in-tree and unused: `:fabric:verifyServerGui` (`fabric/build.gradle.kts`, an `Exec` task running `compat/gui_icon_check.py`) proves the pattern of "boot the assembled server, assert a log line, assert clean shutdown".

**Reproduction:** push a commit that renames `ServerLevelTickMixin`'s target method; pipeline stays green.
**Acceptance:** a mandatory stage that boots the built mod and fails the build on any of: no `Done (` line within 180 s; any `Mixin apply failed` / `InjectionError` / `MixinApplyError` / `ClassNotFoundException` naming `fabricfolia`; no clean RCON/console stop; JVM not exited within 60 s of stop. Gate G3.

### QA-002 — HIGH — test reports cannot fail on empty or hollow runs (VERIFIED)

* `junit allowEmptyResults: true` in both the Test and Cross-OS stages: a run that produces zero XML reports succeeds and publishes nothing.
* No gate on test count, skipped count, or on a test class disappearing.
* `:api:test` is part of the documented protocol (`AGENTS.md` §3/§5) but `api/src` has **no** `test` source set (`find api -type f` lists only `build.gradle.kts` plus 14 main sources). The task is `NO-SOURCE`; the "api tests are green" claim is vacuous.
* Tests execute twice per build (Test stage, then `build` re-invokes `test`), but only the first run is published; a divergence between runs would be invisible.

**Acceptance:** `allowEmptyResults: false`; assert `tests >= floor` and `failures == errors == 0` from the XML; assert `skipped <= allowlist` (only `GpuParityTest` may skip); either remove `:api:test` from the protocol or add real API-surface tests. Gate G1/G2.

### QA-003 — HIGH — cross-OS stage converts failures into "skipped" (VERIFIED)

`Cross-OS Test` wraps **agent allocation and the whole test run** in `timeout(time: 1, unit: 'MINUTES')`. The catch treats any `FlowInterruptedException` whose causes are all `ExceededTimeout` as "no matching agent became available" and echoes a skip. Therefore:

* a Windows/macOS run that takes longer than 60 s is killed and reported as skipped;
* a genuine hang (or a Gradle test timeout) inside the 60 s window is also reported as skipped.

Also, `README.md` still describes this as a "declarative matrix", which was replaced by parallel scripted branches in `57565a7`.

**Acceptance:** the timeout must cover **only** `node(label)` allocation (separate `timeout` inside/around acquisition), never the test body; a skip may be emitted only when the node step never started. Gate G7.

### QA-004 — BLOCKER — Fill publication is unconditional and unverified (VERIFIED)

`Publish to Fill` runs whenever `env.BRANCH_NAME == 'main'`: every merge, including docs-only commits, publishes version `0.1.0` (read from `gradle.properties`) as channel `stable` with the Jenkins build number. Specifically absent:

* no tag or manual-approval condition, no `currentBuild.resultIsBetterOrEqualTo('SUCCESS')` assertion of the *test and boot* gates in the same build;
* artifact selection is `for ARTIFACT in fabric/build/libs/*.jar` — every jar in the directory, whatever it is (dev, sources, an older version left from a previous build because the pipeline never runs `clean`);
* no checksum or signature is produced or published, and no verification that the published bytes match the tested bytes;
* no post-publish check (download/digest/HTTP status) and no record of what was published where;
* the publisher itself (`/home/administrator/palorder-fill/jenkins-publish.sh`) lives outside the repository, so its behaviour cannot be reviewed here — `UNKNOWN`.

**Acceptance:** publish only from an annotated tag `v${VERSION}` (or `main` + `PUBLISH` boolean defaulting to false); publish exactly `fabric/build/libs/folia-${VERSION}.jar`; emit `sha256sum` next to it and archive it; fail if the file count is not exactly 1; verify the published artifact after upload. Gate G5/G6.

### QA-005 — MEDIUM — artifact is not reproducible (VERIFIED)

* `loom_version=1.17-SNAPSHOT` in `gradle.properties` **and** `fabricLoom = "1.17-SNAPSHOT"` in `gradle/libs.versions.toml`: a mutating dependency in the build toolchain itself.
* No `clean` in the pipeline; `build/libs` can retain an older `folia-*.jar` across version bumps.
* `org.gradle.configuration-cache=false`; no `org.gradle.caching`; no `--no-daemon`.
* No `preserveFileTimestamps = false` / `reproducibleFileOrder = true` on `Jar`/`AbstractArchiveTask`, and `mod_version` is a static value unrelated to the commit.

**Acceptance:** pin Loom to a release version; `clean` in the gate; enable file-order/timestamp normalization; gate on `sha256` equality of two consecutive builds of the same commit. Gate G9.

### QA-006 — MEDIUM — artifact name documented wrong (VERIFIED)

`fabric/build.gradle.kts` sets `base { archivesName = "folia" }`; the artifact is `folia-<version>.jar`. `README.md` says `fabric/build/libs/fabric-0.1.0.jar` twice (in "What CI Verifies" and "Building"). Anyone following the README will not find the artifact, and the Fill step's glob hides the discrepancy.

### QA-007 — MEDIUM — Java 25 is not enforced anywhere but the compiler (VERIFIED)

`subprojects { java { sourceCompatibility = 25; targetCompatibility = 25 } ... options.release = 25 }` with **no** `java.toolchain` block. Gradle itself runs on whatever JDK the agent supplies; Jenkins declares `agent any` with no `tools { jdk ... }`. A JDK 21 agent fails with an opaque Loom/toolchain error rather than a clear requirement message.

**Acceptance:** `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }` in the root build, plus `tools { jdk 'jdk25' }` (or a labelled agent) in `Jenkinsfile`.

### QA-008 — LOW — pipeline hygiene

No `timeout` on Test/Build stages, no `buildDiscarder`, no aggregated failure report, `disableConcurrentBuilds()` only. A wedged Gradle daemon can hold the executor indefinitely.

---

## 4. Mod metadata and runtime dependencies

### QA-010 — HIGH — dependency metadata is too permissive for a threaded mod (VERIFIED)

```json
"depends": { "fabricloader": ">=0.19.5", "minecraft": "~26.2", "java": ">=25", "fabric-api": "*" }
```

* `fabric-api: "*"` accepts **any** Fabric API version, including older 26.2 lines missing hooks this mod relies on (`ServerChunkEvents.CHUNK_LOAD` 3-arg signature, `CommandRegistrationCallback`, lifecycle events). It should be `">=0.160.0+26.2"`.
* No `breaks`/`conflicts` for combinations the project itself documents as unsafe or self-suppressing (C2ME random-tick suppression, Lithium collision deferral). Mod metadata is the only place a server owner is warned before boot.
* `environment: "server"` is correct for a dedicated-server-only mod.

**Acceptance:** tighten `fabric-api`; add `breaks` entries for the measured-dangerous versions from `COMPATIBILITY.md`; add a unit/CI assertion that `fabric.mod.json` parses and satisfies the required keys.

### QA-011 — MEDIUM — nested jars are unverified (UNKNOWN)

`fabric/build.gradle.kts` bundles `:api`, `:common`, SnakeYAML Engine, Adventure API and MiniMessage via `include(...)`, and the comment claims fabric.mod.json's `"jars"` declares them. The source `fabric.mod.json` has **no** `jars` array. Loom normally injects it at remap time, but this audit has no `fabric/build/libs/*.jar` (the directory does not exist), so the claim cannot be confirmed.

**Why it matters:** if the nesting or `jars` declaration is wrong, the mod loads but throws `NoClassDefFoundError` for `org.snakeyaml...`/`net.kyori...` on first config load — i.e. at boot, in production, not in tests.
**Acceptance:** artifact-content assertion (Gate G4): `fabric.mod.json` version expanded, `jars` entries for api/common/snakeyaml/adventure, nested jar files present, `fabricfolia.mixins.json` present, `logo.png` present.

### QA-012 — LOW — icon not declared

`fabric/src/main/resources/logo.png` is shipped and used by the server GUI icon, but `fabric.mod.json` has no `"icon": "logo.png"`. Harmless for a server-only mod; add for completeness.

### QA-013 — MEDIUM — thread budget is larger than the documented knob (INSPECTED)

`threads.worker-threads` sizes the region `WorkerPool` (non-daemon). `FabricFoliaEngine.bootstrap` additionally creates an `AsyncSchedulerImpl` with the **same** count (up to 8 daemon threads + 1 timer), a `FabricFolia-Global` daemon, `FabricFolia-Watchdog`, and the `StallProbe` daemon. On a 16-core box the real thread count is roughly `2 × workers + 4`. The config comment describes only region workers and warns only about chunk-IO starvation. Operators sizing a container by `worker-threads` will under-provision.

**Acceptance:** either derive async threads independently (e.g. `max(1, workers/2)`) or document the derived budget and report the live total in `/folia threads` (it already lists `FabricFolia-*`).

---

## 5. Configuration: validation, migration, reload

### QA-020 — BLOCKER — patch request is wired to the wrong config key (VERIFIED)

`FabricFoliaMod.resolvePatchStates` (`FabricFoliaMod.java:417-418`):

```java
PatchRegistry.setRequested("regionize-chunk-load",
        config.patchEnabled(ConfigSchema.KEY_PATCH_REGION_LOOKUP, true));
```

`KEY_PATCH_REGION_LOOKUP` is `patches.fabricfolia.region-lookup`, documented as "the O(1) section-to-region ownership index … Ownership answers are identical either way". Consequences of an operator setting it to `false` to fall back to the linear scan:

1. `regionize-chunk-load` is requested off;
2. `stage-entity-ticks`, `stage-block-entity-ticks`, `stage-scheduled-ticks`, `stage-player-path`, `regionized-random-ticks` and `packet-dispatch` all declare it as a dependency;
3. `PatchRegistry.resolveAndApply` marks them `BLOCKED_DEPENDENCY`;
4. the server runs essentially vanilla while the operator only intended to disable an index.

There is no config key dedicated to chunk-load regionization, so this feature can neither be toggled independently nor disabled intentionally.

**Acceptance:** dedicated key (e.g. `patches.fabricfolia.chunk-load-regionization`, default true) wired to `regionize-chunk-load`; regression test asserting each patch id's requested state derives from its own key.

### QA-021 — HIGH — two patch gates are never applied at startup (VERIFIED)

`startEngine` calls `resolvePatchStates(config)` at line 137 and only creates the engine at line 190 (`FabricFoliaEngine.bootstrap`). Inside `resolvePatchStates`, the only call sites that push the gates into the running objects are guarded by `if (engine != null)` (line 471):

```java
if (engine != null) {
    for (var scheduler : engine.schedulers().values()) {
        scheduler.setSchedulerDispatchPatch(schedulerActive);
        scheduler.setOwnerLookupIndex(lookupActive);
    }
}
```

`engine` is a static field that is still `null` at that moment (and `engine.schedulers()` is empty before `attachWorld` even if it were not). The in-object defaults are `true` (`RegionScheduler.schedulerDispatchPatch = true`, `WorldRegionizer.ownerLookupIndex = true`), so:

* default config behaves as documented, and
* setting `patches.fabricfolia.scheduler-dispatch: false` or `patches.fabricfolia.region-lookup: false` changes **nothing** at runtime, while `/folia patches` and the startup summary report them `DISABLED`.

That is a false diagnostic — the worst kind for a performance mod — and it is exactly the class of defect the patch layer exists to avoid.

**Acceptance:** push gates after `attachWorld` (or re-push in `attachWorld`/`bootstrap`), and add a test that sets each patch key to false and asserts the corresponding object reports the gate off.

### QA-022 — MEDIUM — generated config advertises features that do not exist (VERIFIED)

The schema's comments are written into `config/fabric-folia.yml` on first run, so they ship to every operator:

* `general.enabled` comment: "Changing this at runtime via `/folia config enabled <true|false>` …". There is **no** `config` subcommand in `FoliaCommand` (subcommands: `version`, `help`, `regions`, `workers`, `scheduler`, `patches`, `health`, `threads`, `shutdown`, `status`, `entities`, `metrics`, `gpu`, `compat`, `pin`, `unpin`), and `FoliaHelp` has no such topic.
* `threads.thread-check-mode`, `general.regionized-random-ticks`, `diagnostics.debug`, `diagnostics.debug-logging`, `diagnostics.profiling`, `diagnostics.metrics` all say "Restart required: no - applies live". No reload path exists anywhere in the tree (`grep -rn "reload"` returns only a COMPATIBILITY.md sentence); `FoliaConfig.freeze()` makes values final for the session.

**Acceptance:** either implement the command/reload or change the comments (and `FoliaHelp`'s "configuration" topic) to "restart required". A docs-only fix is acceptable for release.

### QA-023 — MEDIUM — config is rewritten on every boot and read-only config disables the mod (VERIFIED)

`FoliaConfig.load()` always calls `mergePreservingComments` then `save(merged)` (even when the file is complete and untouched), then re-parses. Consequences:

* every boot rewrites the operator's file (mtime churn, git/config-management noise, and any hand-written alignment is reflowed);
* if the file is not writable (container ConfigMap, Nix store, read-only mount, restrictive ACL), `save` throws `IOException`, which `startEngine`'s `catch (Exception)` treats as "configuration is invalid" and **disables the entire mod** — with a message that blames the operator's config rather than the filesystem.

**Acceptance:** skip the save when nothing changed; on `IOException` from save, log a distinct warning and continue with the validated in-memory config instead of disabling the mod.

### QA-024 — LOW — unknown keys are flagged only inside the file (VERIFIED)

`ConfigWriter.flagUnknownKeys` inserts a YAML comment. Nothing is logged, and `/folia status`/`metrics` never surface it. A typo such as `patches.minecraft.entity-collision-optimisation` silently does nothing except add a comment the operator may never re-read.

### QA-025 — LOW — dead config knobs (VERIFIED)

`diagnostics.metrics` (default **false**) and `diagnostics.profiling` (default **false**) are documented, generated, and never read (`grep` finds no `config.metrics()` / `config.profiling()` call). Metrics are always on, so the knob cannot reduce overhead; the docs claim it is a switch.

---

## 6. Commands and permissions

### QA-030 — MEDIUM — the real permission path is untested and its node identity is unverified against plugins (VERIFIED)

`FoliaCommandPermissionTest` tests the pure `grants(owner, atom, umbrella, sub)` OR-rule and pins node strings. It does **not** test:

* `allowed(CommandSourceStack, leaf)` — the method that actually runs, which calls `permissions.hasPermission(Permission.Atom.create(atomNode(leaf)))`;
* that `dispatcher.register(root)` wires every `requires(...)` predicate (a typo in a subcommand name would leave a node unreachable, and no test would notice);
* behaviour with a permission plugin installed.

`javap` on the named 26.2 jar confirms `Permission$Atom.create(String)` delegates to `Identifier.withDefaultNamespace(...)`, i.e. `"folia.command"` becomes `minecraft:folia.command`. Whether LuckPerms users can grant that literal string as documented (the class javadoc says "one grant unlocks the whole diagnostics tier") is `UNKNOWN` and cannot be checked here.

Also worth noting for a security review: `/folia`, `/folia version` and `/folia help` are **open to everyone** with no `requires`, and `/folia help <topic>` exposes internal architecture, metric names and config paths to any player. That may be intended ("open information tier") but should be a deliberate, documented decision.

**Acceptance:** tests for `allowed()` against a stub `CommandSourceStack` (or an integration test asserting the dispatcher contains each node and its predicate rejects a non-OP); a documented, verified LuckPerms node example; a decision recorded for the open tier.

### QA-031 — LOW — shutdown text overpromises (VERIFIED)

`/folia shutdown` sends "region work will drain and worlds will be saved" then calls `source.getServer().halt(false)`. `javap` shows `halt(boolean)` only sets `running = false` (and joins the server thread when `true`). Saving happens later in vanilla's `stopServer`, and the engine's drain depends on `SERVER_STOPPING` ordering and a 5 s pool timeout (QA-041/QA-042).

**Acceptance:** reword to the verifiable claim ("requesting a normal stop; worlds are saved by vanilla as part of the stop sequence"), or assert the drain+save outcome in the boot smoke.

### QA-032 — MEDIUM — command re-homing changes command semantics and is untested (INSPECTED)

`CommandsMixin.fabricfolia$regionAwareDispatch` cancels `performCommand` when the caller is a NETWORK thread and the source is an entity, then re-executes `source.dispatcher().execute(parseResults)` as a fire-and-forget region task. Consequences that no test covers:

* the command's return value / Brigadier result is discarded and the packet handler returns before the command runs;
* feedback ordering relative to other packets changes;
* exceptions are converted to `sendFailure("Command failed: …")`, bypassing vanilla's error formatting;
* two `HEAD` injections exist on the same method (context tagging + dispatch) whose relative order is not pinned.

**Acceptance:** either drop this re-home (the player path already stages the connection tick in REGION context, so the network-thread case may be unreachable) or add a test/live probe that a player-issued command from a network thread lands in the owning region and reports its result.

---

## 7. Runtime correctness, watchdog, fatal handling, shutdown

### QA-040 — BLOCKER (semantics) — the slow-motion gate discards gameplay ticks (VERIFIED)

`RegionStageHub.dispatch(..., allowRegionSkip=true)` (`RegionStageHub.java:651-663`) removes an entire region's batch when `queue.size() > REGION_SKIP_THRESHOLD` (3):

```java
if (queue != null && queue.size() > REGION_SKIP_THRESHOLD) {
    REGION_BEHIND_SKIPPED.addAndGet(entry.getValue().size());
    slowMotion.remove();
}
```

Those bodies are **never executed and never retried**; they are only counted, and the removal happens after the parallelism decision, so nothing re-stages them. The behavior is pinned as intended by `RegionStageHubParallelGateTest.backedUpRegionSkipsARoundExplicitlyAndResumes` (`assertEquals(0, runs.get(), "skipped bodies must not run inline either")` plus the skip counter, and the "resume" leg only after `dropAll()` discards the backlog — i.e. the skipped bodies are gone for good, and the test says so).

Why this is a release blocker:

* it contradicts `AGENTS.md` rule 2 ("fix bugs at the root — never suppress … no silent drops") and the project's own Phase-1 turn that **removed** this gate for exactly this reason (`FREEBUFF.md` §6, 2026-11-01: "ELIMINATED silent gameplay tick loss");
* it diverges from Folia, where an overloaded region ticks **later**, not less — entity AI, fire spread, redstone and scheduled work are delayed, never dropped;
* the recorded evidence is enormous: 22.1 M bodies skipped vs 1.5 M executed in the 20-TPS run (`FREEBUFF.md`, 2026-10-06 region slow-motion turn), and 69.4 M skipped in the 2026-11-09 boot;
* `THREADING.md:246-256` presents it as "Folia's overloaded-region semantics" and `docs/folia-parity.md` does not list it as a gap, so the divergence is not disclosed where it matters.

Independent corroboration: the planner report (`TEAM_REPORT_PLANNER.md` §0) reaches the same conclusion from the performance side.

**Reproduction:** with a backed-up region (block its queue with >3 tasks), stage N entity bodies; observe `skipped (region behind, slow-motion)` increase by N and the bodies' effects never occur.
**Acceptance (choose one, and record the choice):**
(a) remove the gate (Phase-1 behavior) and accept slower regions with bounded queues, or
(b) keep the gate but **re-stage** the skipped bodies for the region's next tick (delay, not loss), counting `rescheduled`, with a test that every body eventually executes exactly once, or
(c) keep the drop and declare it: parity ledger row, generated-config warning, README, and an explicit written acceptance from the owner.
Any of these must come with a test that the no-loss property (or the declared loss) holds.

### QA-041 — HIGH — shutdown abandons work without accounting (VERIFIED)

* `RegionScheduler.close()` (`:268-288`): sets `closed`, joins the coordinator, then `dropAll()` on every region queue and logs a **text** line ("dropped N queued tasks"). No metric counter, so the loss never appears in `/folia metrics`.
* `WorkerPool.shutdown(timeout, pump)` (`:232-254`): sets `running=false`, wakes workers, waits for `terminated`; when the deadline passes the loop simply ends. The `handoff` queue is never drained or reported, and no diagnostic is emitted when workers are still alive after the timeout.
* `FabricFoliaEngine.shutdown` runs `RegionStageHub.deactivateAll()`, then per-world `detachWorld` → `hub.flushStaged()` → `scheduler.close()`. Bodies flushed after deactivation are enqueued into queues that are about to be dropped, so the final tick's staged work can be lost — counted nowhere.
* Region workers are non-daemon (`worker.setDaemon(false)`), so a worker that survives the 5 s timeout can delay JVM exit indefinitely.

**Acceptance:** count abandoned tasks/bodies (`SHUTDOWN_DROPPED` counter surfaced in `/folia metrics` and the shutdown log); log an explicit ERROR when `terminated.getCount() > 0` after the timeout; do not flush staged bodies during `detachWorld` unless the queue can drain (prefer running them inline on the server thread).

### QA-042 — HIGH — drain-before-save is neither tested nor gated (INSPECTED)

`stopEngine` is registered on `ServerLifecycleEvents.SERVER_STOPPING`, which Fabric fires at the start of `MinecraftServer.stopServer`, before `saveAllChunks`. The intended sequence is: engine drains → return → vanilla saves. If `shutdown(5000)` times out (QA-041), region workers can still be mutating entities/blocks **while `saveAllChunks` runs** — a torn save, the one failure mode that outlives the server process.

No test covers the ordering, and `TESTING.md` documents no shutdown test. `FREEBUFF.md` records clean stops ("All dimensions are saved"), which is runtime evidence but not a regression net.

**Acceptance:** a boot-smoke gate that, after `/folia stop`, asserts either "no worker alive after SERVER_STOPPING" (log/counter line) or a documented degradation, and asserts the vanilla save lines; plus a unit test that `shutdown` drops nothing when all queues are drained.

### QA-043 — MEDIUM — the fail-safe quiesce loop has no bound and no test (VERIFIED)

`beginTickPhase` (Phase 1B) does not proceed on timeout: it loops `while (pool.busyCount() > 0)` calling `closeExecutionAndAwait(1s)`, logging each second. That is a deliberate correctness-over-availability choice, but:

* there is no upper bound, no escalation, and no observable metric beyond the log;
* the only exit on a permanently stuck body is the vanilla watchdog kill at 60 s, discarding the "graceful degradation" story;
* the loop itself is untested (`WorkerPoolGateTest` covers `closeExecutionAndAwait` timeouts, not `FabricFoliaEngine.beginTickPhase`'s fail-safe loop).

**Acceptance:** test the fail-safe loop with an injected never-completing task (assert it waits, logs, and is interruptible); document the watchdog-kill outcome in `THREADING.md`; consider a configurable hard cap that aborts the offending region instead of hanging.

### QA-044 — MEDIUM — a fatal error permanently shrinks the pool (VERIFIED)

`WorkerPool.workerLoop`'s catch rethrows `VirtualMachineError | ThreadDeath | LinkageError`; the `finally` decrements `terminated` and clears the context. Nothing restarts the worker, `busyCount` is restored, and no metric records the death. The pool silently loses capacity (and `workerCount()` still reports the original count). This is already an open item in `FREEBUFF.md` ("worker replacement after a fatal worker death").

**Acceptance:** either recreate the worker (with a bounded restart count) or expose `liveWorkerCount` + a `WORKER_DIED` counter and fail a health check.

### QA-045 — MEDIUM — fatal errors are still swallowed in three paths (VERIFIED)

The Phase-1 rule was "rethrow `VirtualMachineError|ThreadDeath|LinkageError` through every catch". It holds in `RegionStageHub.runBody`, `WorkerPool`, `ServerThreadDeferral`, and `RegionScheduler`'s inner drains, but **not** in:

* `RegionTransitions.guardedKeyed` (`:237`) and `guarded` (`:252`) — `catch (Throwable t)` → `engine.reportError`;
* `FabricFoliaMod.deliverApiEntrypoints` (`:273`) — `catch (Throwable t)` around a third-party `Initializer`;
* `RegionScheduler`'s outer `catch (Throwable t)` around `regionTickBody.accept(region)` (~`:712`) — logs and continues, and does **not** call `failurePolicy.reportTaskFailure`, so `ABORT_REGION` can never fire for a region whose tick body keeps throwing.

**Acceptance:** add the fatal-rethrow clause in all three; route the region-tick-body failure through `RegionFailurePolicy`; add unit tests mirroring `RegionStageHubFatalTest`.

### QA-046 — LOW — a ThreadLocal leak the javadoc says cannot happen (VERIFIED)

`ServerLevelTickMixin` sets the drain-staging flag at `tick` HEAD and clears it at `@At("RETURN")`. `javap -c` on `net.minecraft.server.level.ServerLevel.tick(BooleanSupplier)` shows a **single** `return` instruction, i.e. no exception path reaches the injection point. The javadoc claims a `finally` guarantee ("clearing in a finally guarantees no leak even if a drain throws"). On a thrown tick, the flag stays set for the server thread and subsequent unrelated `LevelTicks` drains can be captured as `SCHEDULED_TICK` staging.

**Acceptance:** move the clear into a cancel-safe path that also runs on throw (e.g. wrap at `@At("HEAD")` and observe the exception via a try/finally in a `@Redirect`/`@WrapOperation` around `tick`), or accept and document; either way fix the javadoc.

### QA-047 — LOW — pending accounting can be reconciled away (VERIFIED)

`flushStaged` zeroes `pendingBodies` when the region-queue sum is 0 and no worker is busy. Because the same branch runs on every tick, an accounting bug (a body that leaked a `+1` and will never complete) is erased rather than surfaced — the opposite of "make it observable". No metric counts reconciliations.

**Acceptance:** count the reconciliations and log/report them (`pending accounting reconciled: N`) so a leak shows as a number.

### QA-048 — LOW — unbounded stall-probe files

`StallProbe` writes `run/stall-probes/stall-*.log`, at most one per 8 s, with no retention/deletion. A server that crawls for hours fills the disk, which is precisely when an operator needs the disk.

### QA-049 — LOW — `/folia health` is blind when gameplay staging is off (VERIFIED)

`FabricFoliaEngine.levels()` builds its list from `stagingHubsByWorld`, which is populated only when `config.regionizedGameplay()` is true (`attachEntityTracking`). With staging off, `levels()` is empty → `hasLoadedChunks()` is false → `FoliaDiagnostics.healthChecks` reports "no regions and no loaded spawn chunks (server idle?)" as a **warning** on a fully loaded server, and `schedulerLines` suppresses its "chunks exist but no regions" DIAGNOSIS. Fix: track levels independently of staging.

---

## 8. Mixin injection stability

### QA-050 — MEDIUM — one required set, no tiering, no drift detection (INSPECTED)

`fabricfolia.mixins.json` sets `"required": true`, `"minVersion": "0.8"`, `"compatibilityLevel": "JAVA_25"`, `"injectors": { "defaultRequire": 1 }`. This is good for correctness (a missing target fails loudly) but poor for compatibility surface:

* a single third-party mod that overwrites the same instruction aborts boot entirely (no per-mixin priority, no optional tier);
* `compatibilityLevel: JAVA_25` must be supported by the Mixin version bundled with Loader 0.19.5 — `UNKNOWN` here, proven or disproven by the first boot gate;
* target drift after any MC/Fabric bump is only visible at boot, and CI does not boot (QA-001), so the first detector is a user.

**Acceptance:** the boot gate (G3), plus a policy decision on tiering (`required` functional mixins vs `required: 0` cosmetic ones) and documented priorities for the hot redirects (`ServerConnectionTickMixin`, `LevelEntityTickMixin`, `ServerPlayerTickMixin`, `ServerChunkCacheTickMixin`) that optimization mods compete for.

### QA-051 — LOW — GUI mixin in the required set (INSPECTED)

`MinecraftServerGuiMixin` targets `MinecraftServerGui.showFrameFor`. The class is present in the named 26.2 common jar (`unzip -l` confirms `net/minecraft/server/gui/MinecraftServerGui.class`), so the target exists; whether the redirect applies cleanly in a headless boot is exactly what G3 should prove.

### QA-052 — LOW — the three highest-risk mixins are undocumented (VERIFIED)

`MIXINS.md:8` says "26 mixins registered; 23 documented below — CommandsMixin, MinecraftServerMixin, ServerPlayerTickMixin remain to be documented". A mechanical set-difference confirms exactly those three are missing. They are, respectively, the tick-phase barrier, the server-thread deferral drain anchor, and the player-tick bounce — the hooks a reviewer most needs the mandated seven-question analysis for.

### QA-053/054 — LOW — file hygiene and brittle target forms

`MinecraftServerMixin.java` is the only file in the tree with CRLF and 4-space indentation (every other file is tabs/LF), which invites whitespace-only diffs on a file that gates the tick phase. `EntityMixin`'s collision hooks use `@ModifyArgs`/`@WrapOperation` with `ordinal = 0` on `collideWithShapes`; ordinal matchers are the least stable form and can mis-target on a signature-preserving change. Both are opt-in/low-blast-radius (the collision patch defaults off and is force-disabled with Lithium), but they should be reviewed against bytecode whenever 26.2 is bumped.

---

## 9. Test suite: what exists, what is missing

### Inventory (VERIFIED)

* 41 test files, 209 `@Test` methods (22 common files, 19 fabric files), JUnit 5, no Mockito, global `junit.jupiter.execution.timeout.default=120s`.
* `fabric` tests do boot the real engine (`FoliaConfig.at → load → freeze → FabricFoliaEngine.bootstrap`), and several stage-hub tests use package-private constructors with a `null` level.
* Only one self-skipping test: `GpuParityTest` (`Assumptions.assumeTrue(GPU_ACTIVE)`).
* Only one explicit `@Timeout` (`WorkerRandomsTest`).
* No `@Disabled` tests — good.

### QA-060 — HIGH — coverage gaps that map directly to release risk (VERIFIED)

Missing, in priority order:

1. **Patch wiring** — no test would have caught QA-020/QA-021 (a test that evaluates each `PatchRegistry` id against its config key, and one that asserts gates reach `RegionScheduler`/`WorldRegionizer`, is cheap and would have caught both).
2. **Shutdown/queue abandonment** — no test asserts what happens to queued bodies at `close()`, or that nothing is dropped when drained (QA-041).
3. **Permissions end-to-end** — only the pure rule (QA-030).
4. **Config lifecycle** — no tests for `CURRENT_VERSION` migration, read-only/IO failure, unknown-key flagging, idempotent re-load, or the save-on-load behavior (QA-023/024).
5. **Watchdog/StallProbe** — `RegionWatchdog.scan` and the stall probe have no tests at all, though the watchdog is the operator's primary stall signal.
6. **`api` module** — zero tests, though it is the published contract other mods compile against (QA-002).
7. **Artifact/metadata** — no test asserts `fabric.mod.json` validity/expansion or mixin-json ↔ source agreement.
8. **Fail-safe quiesce loop** (QA-043), **fatal rethrow in RegionTransitions** (QA-045), **`ServerLevelTickMixin` exception path** (QA-046).
9. **Docs ↔ code consistency** — no check keeps MIXINS.md, README jar name, command list, or the config comments honest (QA-052/QA-070).

### QA-061 — MEDIUM — timing dependence (VERIFIED)

22 `Thread.sleep` call sites in tests, including 1500 ms and multi-hundred-ms windows in `WorkerPoolGateTest`, `RegionSchedulerTest`, `RegionDrainBudgetTest`. With `junit.jupiter.execution.timeout.default=120s` a hang fails eventually, but on a loaded shared agent these are flake candidates, and a flaky test in a publishing pipeline is worse than no test. Add `--no-daemon`, consider a JUnit retry policy for the concurrency class only, and prefer latch/condition waits where possible.

### QA-062 — LOW — skip invisibility (VERIFIED)

`GpuParityTest` skips silently when no OpenCL device exists, and because the pipeline allows empty/partial results (QA-002), a CI machine that loses its GPU would reduce the published evidence without any signal in the build result.

---

## 10. Documentation accuracy (release-facing)

### QA-070 — HIGH — three milestone descriptions, one of them inside the artifact (VERIFIED)

* `README.md` status: "the first genuine vanilla interception is live: the per-chunk random-tick pass … All other vanilla passes still run on the server thread." The code stages entities, block entities, scheduled ticks and the player connection path by default. README also says cross-OS uses "a declarative matrix" (replaced by parallel branches) and misnames the jar (QA-006), and lists a command set that omits `version/help/workers/scheduler/patches/health/metrics/gpu/pin/unpin`.
* `TESTING.md`: "116 as of the 2026-09-24 region-pipeline turn" (actual 209) and "the remaining pipeline partitioning (scheduled ticks, block entities, entities) has not begun".
* `docs/folia-parity.md` is the only current document and explicitly warns the others lag — which means shipping readers are pointed at the wrong docs by default.

**Acceptance:** a docs pass before the next public release, plus a lightweight consistency check (G10) so counts/names cannot drift silently again.

### QA-071 — MEDIUM — `docs/mandates/` does not exist (VERIFIED)

`AGENTS.md` §5 lists `docs/mandates/` as the location of the owner mandates (binding rules), and dozens of code comments cite "mandate §8/§11/§15/§21/§26/§27/§34/§45/§47" and "spec N". `docs/` contains only `folia-parity.md`, `gpu.md`, and `compatibility/`. Every citation in the codebase therefore points at an unavailable document — an auditability problem, not just a docs problem, because reviewers cannot check the code against its stated rules.

**Acceptance:** commit the mandate document(s) under `docs/mandates/`, or remove the citations and state the rules inline where they bind.

### QA-072 — HIGH — player-path staging defaults ON while documentation says OFF (VERIFIED)

* `ConfigSchema` registers `gameplay.stage-player-path` with `Boolean.TRUE` and a comment "Default: true" (`ConfigSchema.java:404`).
* `FabricFoliaMod.resolvePatchStates` falls back to `true` (`:428-429`), and `playerPathStaging()` treats a null gate as enabled.
* `FREEBUFF.md` §2 describes the same path as "gated by config `gameplay.stage-player-path`, **default off**".
* `THREADING.md`, `README.md`, `ARCHITECTURE.md`, `TESTING.md` do not document the default at all.
* `docs/folia-parity.md` marks "Player login placement" as `NOT_IMPLEMENTED` while player path is `VALIDATED (live)`.

Consequence: every fresh install moves player physics, packet handling and connection ticks onto region workers, on a code path the handoff file believes is opt-in, with a known gap (login placement) in the same subsystem. That is the single most consequential default in the mod.

**Acceptance:** decide the intended default; align code, generated config, `FREEBUFF.md`, `THREADING.md` and the parity ledger; if it stays on, add a startup warning line stating it and the login-placement gap. A test should pin the resolved default (config absent → gate value).

---

## 11. Watchdog behavior (summary of the audit)

| Watchdog | Exists | Acts | Tested | Gap |
| --- | --- | --- | --- | --- |
| Vanilla `ServerWatchdog` | vanilla | kills at 60 s drift | n/a | Kills a *progressing* crawl; `StallProbe` now warns below 2 TPS — good, but no CI gate ever exercises it |
| `RegionWatchdog` | yes (daemon, 10 s) | logs only | **no** | Only detects `TICKING` regions overdue >5 s; blind to a dead worker, a stuck quiesce, or a region starved of dispatch; no counter in `/folia metrics` besides the log |
| `StallProbe` | yes (daemon, 500 ms) | logs + dump files | **no** | Unbounded dump files (QA-048); reflection-bound to 26.2 field names (documented, degrades) |
| Fail-safe quiesce | yes | blocks the tick indefinitely | **no** | No bound, no escalation (QA-043) |

**Acceptance:** add `RegionWatchdog.scan()` unit tests (overdue/not-overdue/READY/DYING), expose `reportedStalls()` in `/folia metrics`, and add a boot-smoke assertion that `FabricFolia-Watchdog` is alive after 30 s when `diagnostics.watchdog: true`.

---

## 12. Proposed Jenkins hard gates

These are written to be implementable as-is; each gate maps to findings above. Order matters: cheap gates first, publish last, and publish must depend on all of them.

### G1 — Real test report gate (QA-002)

```groovy
stage('Test') {
    steps {
        sh '''set -euo pipefail
              chmod +x ./gradlew
              ./gradlew --no-daemon clean :common:test :fabric:test :api:test --stacktrace'''
    }
    post { always { junit testResults: '*/build/test-results/test/*.xml', allowEmptyResults: false } }
}
```

`allowEmptyResults: false` is the whole difference between "green" and "the tests did not run".

### G2 — Test-count and skip floor (QA-002, QA-060, QA-062)

```groovy
sh '''
  set -euo pipefail
  TOTAL=$(grep -ho 'tests="[0-9]*"' */build/test-results/test/*.xml | sed 's/[^0-9]//g' | paste -sd+ | bc)
  SKIP=$(grep -ho 'skipped="[0-9]*"' */build/test-results/test/*.xml | sed 's/[^0-9]//g' | paste -sd+ | bc)
  echo "tests=$TOTAL skipped=$SKIP"
  [ "$TOTAL" -ge 209 ] || { echo "Test count fell below the committed floor (209)"; exit 1; }
  [ "$SKIP" -le 2 ] || { echo "Too many skipped tests ($SKIP); only GPU parity may skip"; exit 1; }
'''
```

Raise the floor whenever tests are added (or generate the floor from a checked-in file to avoid editing Jenkins).

### G3 — Mandatory runtime smoke / mixin-apply gate (QA-001, QA-011, QA-050, QA-051, QA-042)

A new Gradle task (mirroring the existing `verifyServerGui` `Exec` pattern) that:

1. boots the **built** mod (`:fabric:runServer`, `--nogui`, port 25901, headless) with a 180 s wall clock;
2. fails on any of `Mixin apply failed`, `InjectionError`, `MixinApplyError`, `NoClassDefFoundError`, `Error: Could not find or load main class`;
3. requires `Done (` and `SUCCESS: Fabric Folia is ready` (or the documented vanilla-mode ready line);
4. issues RCON `folia health` (password `foliapass`) and requires `Overall: OK` (or a documented warning set);
5. issues RCON `stop` and requires `All dimensions are saved` + process exit within 60 s;
6. sweeps its own PIDs and fails if any JVM it started is alive afterwards.

Run it on **every** build (it is ~1–2 minutes) and make it a required predecessor of `Publish`.

### G4 — Artifact content verification (QA-006, QA-011, QA-013)

```groovy
sh '''
  set -euo pipefail
  VERSION=$(sed -n 's/^mod_version=//p' gradle.properties | head -n1)
  JAR="fabric/build/libs/folia-${VERSION}.jar"
  [ -f "$JAR" ] || { echo "missing $JAR"; exit 1; }
  unzip -p "$JAR" fabric.mod.json > /tmp/fmj.json
  grep -q "\"version\": \"${VERSION}\"" /tmp/fmj.json
  grep -q 'fabricfolia.mixins.json' /tmp/fmj.json
  grep -q '"jars"' /tmp/fmj.json
  unzip -l "$JAR" | grep -q 'META-INF/jars/'
  unzip -l "$JAR" | grep -q 'fabricfolia.mixins.json'
  unzip -l "$JAR" | grep -q 'logo.png'
'''
```

This is the gate that would have made QA-011 `UNKNOWN` → `VERIFIED` without a human.

### G5 — Deterministic artifact selection and checksum (QA-004, QA-005)

* `clean` before the build (G1), then select **exactly** `fabric/build/libs/folia-${VERSION}.jar`; fail if `ls fabric/build/libs/*.jar | wc -l != 1`.
* `sha256sum "$JAR" > "$JAR.sha256"`, archive both, and pass the digest to the publisher.
* Reproducibility gate: build the same commit twice (or compare against the previous build's recorded digest for unchanged `gradle.properties` + Loom pin) and fail on mismatch once Loom is pinned to a release.

### G6 — Publication conditions (QA-004)

```groovy
stage('Publish to Fill') {
    when { allOf { branch 'main'; expression { params.PUBLISH == true }; expression { currentBuild.resultIsBetterOrEqualTo('SUCCESS') } } }
    // or: tag pattern 'v*' + annotated tag check
}
```

Plus: require that G1/G2/G3/G4/G5 ran in this build (track with a `stage` result env var), publish the checksum beside the jar, and verify the uploaded digest afterwards. Default `PUBLISH=false`, so a merge cannot publish by accident.

### G7 — Cross-OS without false skips (QA-003)

* Probe agent availability before `node(...)` (labels from `Jenkins.instance.getNodes()`), and emit the skip only when no node has the label.
* Put any `timeout` **around node acquisition only**; the test body gets a generous stage timeout (e.g. 30 min) and a failure there must fail the build.
* Optionally treat cross-OS as a nightly parameterised job rather than a per-merge gate.

### G8 — Config/patch wiring regression (QA-020, QA-021, QA-072)

A JUnit test in `fabric` that, for each registered patch, resolves the config key that is supposed to drive it and asserts `setRequested` matches; a test that constructs the engine and calls the gate-push path and asserts `scheduler`/`regionizer` flags changed; a test pinning `playerPathStaging()` for config absent / true / false. This is a static gate (runs in G1), not a new stage.

### G9 — Build reproducibility and toolchain (QA-005, QA-007)

* Root build: `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }`.
* `tasks.withType<AbstractArchiveTask> { isPreserveFileTimestamps = false; isReproducibleFileOrder = true }`.
* Pin Loom to a non-SNAPSHOT version in both `gradle.properties` and `libs.versions.toml`.
* Jenkins: `tools { jdk 'jdk25' }` (or an agent labelled for JDK 25) and `--no-daemon` in every Gradle invocation.

### G10 — Doc/registry consistency (QA-052, QA-070)

A test (or a tiny Gradle `check` task) that fails when:

* the mixin-class set in `fabricfolia.mixins.json` ≠ the `###` headings in `MIXINS.md`;
* a command literal in `FoliaCommand` is absent from `README.md`'s command list and `FoliaHelp`;
* the jar name in `README.md` ≠ `archivesName` + `mod_version`;
* a config key referenced by the schema has no reader (catches dead knobs like QA-025, and keys like `gameplay.stage-player-path` that bypass the typed accessor).

### G11 — Boot smoke on mixin/metadata changes (targeted, cheap)

If the boot smoke is considered too slow for every build, gate it on `changeset` containing `fabric/src/main/java/**/mixin/**`, `fabricfolia.mixins.json`, or `fabric.mod.json`. That matches AGENTS.md rule 5 and closes the "no mixin verification in CI" hole without making every docs commit boot a server.

---

## 13. Evidence log (what this audit actually executed)

Read-only commands used to make the `VERIFIED` labels defensible:

* File inventory and line counts for `api`, `common`, `fabric` (14/40/54 main sources; 41 test files; 209 `@Test`).
* `find api -type f` → no `api/src/test`, confirming `:api:test` is `NO-SOURCE`.
* `grep -rn "reload"` → no reload path; `grep -n "folia config"` → no such subcommand.
* `grep -c "@Test"` per file → 209 total.
* Set difference `mixins.json` vs `MIXINS.md` `###` headings → `CommandsMixin`, `MinecraftServerMixin`, `ServerPlayerTickMixin` undocumented (matching `MIXINS.md:8`).
* `javap -p` / `javap -c` against `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-043a8b3edf/26.2/minecraft-common-043a8b3edf-26.2.jar`:
  * `Permission$Atom.create(String)` → `Identifier.withDefaultNamespace` (node identity `minecraft:folia.command*`);
  * `MinecraftServer.halt(boolean)` → sets `running=false` only (no save, no wait when `false`);
  * `ServerChunkCache.getChunkNow` → main-thread guard (confirms why the staging probe is server-thread-only);
  * `ServerLevel.tick(BooleanSupplier)` → exactly one `return` (QA-046);
  * class presence: `MinecraftServerGui`, `TicketStorage`, `ServerChunkCache` all in the named jar.
* `git status` → only untracked team reports; no source modification by this audit.

Not executed, per the constraints of this role: any Gradle task, any test run, any server boot, any RCON command, any commit/push. Findings that depend on those are labelled `INSPECTED` or `UNKNOWN` and are assigned to a gate above.

---

## 14. Closing checklist for the manager

Must be true before the next `stable` Fill publication:

1. [ ] G1 + G2 are hard-failing (no empty test reports, no silent skips, count floor).
2. [ ] G3 boots the built jar and passes on a clean agent.
3. [ ] G4 verifies the artifact contents (version expansion, nested jars, mixin json, icon).
4. [ ] G5 selects a single named jar and publishes a checksum.
5. [ ] G6 makes publication opt-in/tag-driven and verifies the upload.
6. [ ] QA-020 and QA-021 fixed (or the two config keys removed from the shipped schema — never leave a knob that lies).
7. [ ] QA-040 decided and recorded: drop, re-stage, or declared divergence (with a parity-doc row).
8. [ ] QA-041/QA-042 fixed: shutdown loss is counted, and drain-before-save is asserted.
9. [ ] QA-010 dependency metadata tightened; `breaks` entries for documented-dangerous combos.
10. [ ] QA-072 player-path default reconciled across code, generated config, `FREEBUFF.md`, `THREADING.md`, parity ledger.
11. [ ] QA-070/QA-071/QA-052 docs are current and the mandates document exists.
12. [ ] Remaining HIGH/MEDIUM items either fixed or explicitly accepted in writing with a follow-up owner.

---

*End of report. No files other than this one were created or modified.*
