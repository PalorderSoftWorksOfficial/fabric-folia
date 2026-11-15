# TEAM_REPORT_API.md — Public API & Third-Party Developer Experience Audit

**Scope:** `api/` sources, their implementations in `common/` and `fabric/`, module
dependency direction, published artifacts/build outputs, API stability, scheduler
interfaces, task lifecycle and cancellation/rejection contracts, thread-context
methods, region lookup and semantics, entity-scheduler safety, async/IO guidance,
version/capability discovery, nullability/annotations, API docs/examples/tests, and
whether the API is actually callable from an external Fabric mod.

**Mode:** read-only. No source, build file, or shared doc was modified; nothing was
built, booted, committed or pushed. `FREEBUFF.md` was read first, as required, but is
intentionally **not** updated this turn because the task restricts writes to this file.

**Verdict (short):** the API is structurally credible (clean `api → common → fabric`
direction, real implementations behind every interface, Minecraft-free contract), but
it is **not usable by an external mod today**: there are no published coordinates, no
artifact metadata, no consumer example, and zero API tests. On top of that, several
documented contracts are contradicted by the implementation (`run` owner resolution,
`ownerOf`/`send` error behavior, `isEnabled`, `MissingRegionPolicy`, `EntityScheduler`'s
"opaque handle", `@GlobalThread`). Treat the current API as an **internal contract with
a public-looking surface**, not a shipped public API.

---

## 1. Inventory and module direction

| Module | Role | Declared dependencies | Evidence |
| --- | --- | --- | --- |
| `api` | 14 files / 968 LOC: 5 thread annotations, `FabricFoliaApi`, `RegionScheduler`, `EntityScheduler`, `GlobalScheduler`, `AsyncScheduler`, `ThreadContext`, `RegionInfo`, `ValidationMode`, `ThreadContextViolationException` | **none** (`compileOnly` jsr305 only) | `api/build.gradle.kts:12-24`, `api/src/main/java/com/palordersoftworks/fabricfolia/api/` |
| `common` | engine; implements API interfaces | `api(project(":api"))` | `common/build.gradle.kts` (`api(project(":api"))`) |
| `fabric` | mod; mixes, bootstrap, API adapters | `implementation` + `include` of `:api`/`:common` | `fabric/build.gradle.kts:40-43` |

**Dependency direction: PASS.** `api` has no Minecraft/Fabric/Mixin dependency
(verified by source scan: the only imports are `java.*` and its own packages), and the
`api(...)` configuration in `common` propagates the contract to `fabric` correctly.
`settings.gradle.kts` documents and includes the three modules in order
(`settings.gradle.kts:38-40`). No cycle.

**Implementation coverage: PASS (with defects).**

| API type | Implementation | Evidence |
| --- | --- | --- |
| `FabricFoliaApi` | `FabricFoliaApiImpl` | `fabric/.../engine/FabricFoliaApiImpl.java:35` |
| `RegionScheduler` | `ApiRegionScheduler` (common) + per-world adapter | `common/.../scheduler/ApiRegionScheduler.java:33`, `fabric/.../engine/FabricFoliaApiImpl.java:96` |
| `EntityScheduler` | `EntitySchedulerImpl` + adapter | `common/.../scheduler/EntitySchedulerImpl.java:35`, `fabric/.../engine/FabricFoliaApiImpl.java:150` |
| `GlobalScheduler` | `GlobalSchedulerImpl` | `common/.../scheduler/GlobalSchedulerImpl.java:36` |
| `AsyncScheduler` | `AsyncSchedulerImpl` | `common/.../scheduler/AsyncSchedulerImpl.java:49` |
| `ThreadContext` | `ThreadContextImpl` | `common/.../thread/ThreadContextImpl.java:24` |
| `RegionInfo` | `Region` | `common/.../region/Region.java:39` |

Entry point delivery is real, not a stub: `FabricFoliaMod.deliverApiEntrypoints`
queries the `"fabricfolia"` entrypoint key and calls `Initializer.onInitialized`
(`fabric/.../FabricFoliaMod.java:259-279`), invoked at the end of `SERVER_STARTED`
(`fabric/.../FabricFoliaMod.java:248`). **No test exercises this path** (see §3, F-19).

---

## 2. "Is the API actually callable from an external Fabric mod?"

### 2.1 Compile time — **NO**

- No `maven-publish` plugin, no `publishing` block, no repository, no `groupId/artifactId`
  declaration anywhere in the build: `grep -rn "maven-publish\|publishing\|artifactId" --include=*.kts .`
  returns nothing relevant (`api/build.gradle.kts` has only `java-library` and deps;
  `fabric/build.gradle.kts:21-57` has only dependency declarations).
- The only copy of the API ships **nested inside the mod jar** via
  `include(project(":api"))` (`fabric/build.gradle.kts:42`). Gradle cannot compile
  against classes inside a nested jar of another jar; a consumer who does
  `implementation(files("folia-0.1.0.jar"))` gets the outer mod classes, not
  `com.palordersoftworks.fabricfolia.api.*`.
- There is no versioned API artifact, no `javadoc`/`sources` jar, no
  `Automatic-Module-Name`, and no `fabric.mod.json` in `api/` at all
  (`find api -type f` → only `build.gradle.kts` and the 14 `.java` files).
- Consequence: a third-party mod can only compile against the API by vendoring or
  copying `api/` sources/jar out-of-band. That is not a public API distribution model.

### 2.2 Runtime — **UNVERIFIED, and structurally dubious**

- `api` has no `fabric.mod.json`; Loom synthesises nested-jar metadata for non-mod jars,
  so the nested jar's mod id/version are **not controlled by this repository**. That
  makes `FabricLoader.isModLoaded("fabric-folia-api")`-style capability checks
  unreliable and risks a mod-id collision with any other project whose Gradle project
  is named `api` (nested jars are matched by mod id).
- If a consumer instead bundles its own copy of the API jar (the usual way to get an
  API onto a compile classpath), the API classes exist twice — once nested in
  `folia.jar`, once in the consumer. Whether Loader collapses them or the consumer sees
  a different `Class` identity for `FabricFoliaApi` is **not tested anywhere in this
  repo**; a `ClassCastException` at `onInitialized` would be the failure mode.
- No consumer mod, no fixture mod, and no `runServer` scenario declares the
  `"fabricfolia"` entrypoint, so delivery has never been exercised end-to-end.
- What *is* verified by code reading: the entrypoint is only delivered when the engine
  started. Config-load failure and `general.enabled=false` return before bootstrap
  (`fabric/.../FabricFoliaMod.java:109-132`), and delivery is at the end of the enabled
  path (`:248`). In disabled mode a region-aware mod is **never called at all** and has
  no way to learn why (F-14).

### 2.3 Documentation/examples — **NO**

- The entrypoint key `"fabricfolia"`, the dependency coordinates, and a minimal
  consumer `fabric.mod.json` appear **nowhere** in `README.md`, `ARCHITECTURE.md`,
  `TESTING.md`, or `docs/`.
- `docs/compatibility/README.md:66-68` tells mod authors to "see the API javadoc and
  `docs/developer/`" — **`docs/developer/` does not exist** (`find docs -type f` lists
  only `docs/compatibility/*`, `docs/folia-parity.md`, `docs/gpu.md`).
- `README.md:90` and `README.md:108-109` name the artifact
  `fabric/build/libs/fabric-0.1.0.jar`, but `fabric/build.gradle.kts:24-28` sets
  `archivesName = "folia"`, so the jar is `folia-0.1.0.jar`. The one artifact path a
  consumer would use is wrong in the README.
- `README.md:112-114` claims "other mods compile against this alone" — there is no
  artifact to compile against (§2.1).

### 2.4 Tests — **NO**

- `api/src/test` does not exist (0 test files; `common` has 22, `fabric` has 19).
- `Jenkinsfile:31` runs `:api:test` with `junit allowEmptyResults: true` (`:39-41`),
  so the API lane is permanently green-by-emptiness (`NO-SOURCE`).
- No test constructs `FabricFoliaApiImpl`, `ApiRegionScheduler`, or the entity adapter;
  no test drives `deliverApiEntrypoints`. The only API-adjacent tests exercise
  `ThreadContext`/`ValidationMode` from `common` tests
  (`common/src/test/.../thread/ThreadContextTest.java`), not the public facade.

---

## 3. Findings

Severity: **Blocker** = cannot ship a public API without fixing; **High** = a documented
contract is wrong or unsafe; **Medium** = credible-API gap; **Low** = polish/ABI hygiene.

| ID | Sev | Finding | Evidence |
| --- | --- | --- | --- |
| F-01 | Blocker | API has no publishable artifact or coordinates; consumed only as an unversioned nested jar | `api/build.gradle.kts:12-24`; `fabric/build.gradle.kts:42`; no `maven-publish` anywhere |
| F-02 | Blocker | No public docs/example; dangling `docs/developer/`; entrypoint key undocumented; README names the wrong jar | `docs/compatibility/README.md:66-68`; `README.md:90,108-109,112-114` |
| F-03 | High | `FabricFoliaApi` Javadoc says accessors throw "not initialized" placeholders "until the receiving endpoint is wired (a later phase)" — the endpoint is wired and no accessor does this | `api/.../FabricFoliaApi.java:15-20` vs `fabric/.../FabricFoliaApiImpl.java:57-89` |
| F-04 | High | `RegionScheduler` promises owner resolution **at execution time**; `run` resolves once at schedule time | `api/.../RegionScheduler.java:28-31` vs `common/.../scheduler/ApiRegionScheduler.java:52-54,118-125` |
| F-05 | High | `runDelayed`/`runAtFixedRate` enqueue without the target chunk position, so a split leaves them in a region that may no longer own the position | `common/.../scheduler/ApiRegionScheduler.java:57-64,79-93`; `common/.../scheduler/RegionScheduler.java:427-431,327-356` |
| F-06 | High | `run`/`runDelayed`/`runAtFixedRate` silently drop work when the scheduler is closed or the region died: `enqueue` returns `false` and the adapter ignores it | `common/.../scheduler/ApiRegionScheduler.java:118-125`; `common/.../scheduler/RegionScheduler.java:434-441,451-455,359-367` |
| F-07 | High | `ownerOf` is documented "never throws for a missing region" and `send` "returns false if no owner", but both throw `IllegalStateException` when the world is not attached | `api/.../RegionScheduler.java:144-165` vs `fabric/.../engine/FabricFoliaApiImpl.java:98-112` |
| F-08 | High | `EntityScheduler`'s "opaque handle" is actually a Minecraft `Entity`; `Object` gives no compile-time safety, and the adapter reads `entity.level()` on the caller's thread | `api/.../EntityScheduler.java:20-23,54`; `fabric/.../engine/FabricFoliaApiImpl.java:152-161,180-187` |
| F-09 | High | Shutdown behavior is inconsistent across the four schedulers: ISE, silent acceptance, or `RejectedExecutionException` for the same lifecycle event | `fabric/.../engine/FabricFoliaApiImpl.java:50-55,87-89`; `common/.../scheduler/GlobalSchedulerImpl.java:191-195`; `common/.../scheduler/AsyncSchedulerImpl.java:210-214`; `common/.../scheduler/RegionScheduler.java:434-441` |
| F-10 | High | `ThreadContext.Kind` has no `SERVER`; the vanilla server thread reports `UNKNOWN`, so a mod cannot distinguish it from an arbitrary executor — even though most gameplay still runs there | `api/.../ThreadContext.java:51-64`; `common/.../thread/ThreadOwnership.java:38-39`; internal predicate exists but is not exposed: `fabric/.../engine/ServerThreadDeferral.java:27` |
| F-11 | Medium | `Kind.IO` is never entered and `Kind.NETWORK` has exactly one producer; `AsyncSchedulerImpl`'s own doc says it enters `IO` but it enters `ASYNC` | `api/.../ThreadContext.java:56-61`; `common/.../scheduler/AsyncSchedulerImpl.java:31-34,139-140`; `fabric/.../mixin/ConnectionPacketDispatchMixin.java:53` |
| F-12 | Medium | `MissingRegionPolicy` is public but unreachable: no API method accepts it, and the impl hardcodes `RUN_ON_GLOBAL` | `api/.../RegionScheduler.java:35-41`; `fabric/.../engine/FabricFoliaApiImpl.java:108-111` |
| F-13 | Medium | `isEnabled()` means "not shut down", not "regionized execution is active"; while the API is obtainable it is always `true`, and it cannot be `false` for a partially disabled engine | `api/.../FabricFoliaApi.java:47-51`; `fabric/.../engine/FabricFoliaApiImpl.java:87-89`; delivery gating at `fabric/.../FabricFoliaMod.java:128-132,248` |
| F-14 | Medium | When Fabric-Folia is disabled, `"fabricfolia"` entrypoints are never invoked — no callback, no reason, no capability object | `fabric/.../FabricFoliaMod.java:109-132,248,259-279` |
| F-15 | Medium | No API version constant and no capability discovery (per-feature flags); consumers cannot conditionally use regionized paths | `api/.../FabricFoliaApi.java:26-51`; `fabric/src/main/resources/fabric.mod.json` (no `custom` block) |
| F-16 | Medium | No nullability contract: 0 `@Nullable`/`@Nonnull`, no `package-info.java`, while the build claims jsr305 "communicate[s] real guarantees" | `api/build.gradle.kts:17-20`; `api/.../RegionInfo.java:71-78`; `api/.../EntityScheduler.java:35-42` |
| F-17 | Medium | `@GlobalThread` is applied to `runOrSchedule`, which is explicitly callable from any thread — the annotation says the opposite | `api/.../GlobalScheduler.java:58-68`; `api/.../annotations/GlobalThread.java:28-36` |
| F-18 | Medium | Half the public annotations are dead: `@NetworkThread` 0 uses, `@IoThread` 0 uses, `@RegionThread` 0 uses (only an unused import) | `api/.../RegionScheduler.java:15`; annotation scan (§3 note) |
| F-19 | Medium | Zero API tests; CI's `:api:test` is `NO-SOURCE`; entrypoint delivery untested | absent `api/src/test`; `Jenkinsfile:31,39-41` |
| F-20 | Low | `CancelHandleView` leaks an implementation base class into the public surface; `CancelHandle` exposes no state; adding an abstract method would break existing lambdas | `api/.../RegionScheduler.java:171-195`; `common/.../scheduler/AsyncSchedulerImpl.java:128` |
| F-21 | Low | `Position` has no `equals`/`hashCode`/`toString`, no validation; `of(null, …)` fails later with a misleading "world is not attached" | `api/.../RegionScheduler.java:43-63`; `fabric/.../engine/FabricFoliaApiImpl.java:104-106` |
| F-22 | Low | `sectionCenter()` returns a mutable `int[]` in section units that depend on the config's section shift; no accessor for the section size | `api/.../RegionInfo.java:63-78`; `common/.../region/Region.java:312-330`; `common/.../region/WorldRegionizer.java:147-150` |
| F-23 | Low | Async doc/impl drift: "threads + queue limit" (unnamed), impl Javadoc says `SynchronousQueue`, actual is `LinkedBlockingQueue(256)` | `api/.../AsyncScheduler.java:29-32`; `common/.../scheduler/AsyncSchedulerImpl.java:36-43,82-87` |
| F-24 | Low | Public Javadoc cites 8 internal "spec N" sections that are not in the repo | `api/.../{FabricFoliaApi,GlobalScheduler,EntityScheduler,RegionInfo,RegionScheduler}.java` |
| F-25 | Low | `regionScheduler()`/`entityScheduler()` allocate a new adapter (and delegate) per access — unstable identity, avoidable churn | `fabric/.../engine/FabricFoliaApiImpl.java:57-67,96-112` |
| F-26 | Low | `GlobalScheduler.runDelayed` reads non-volatile `tickCount` from the caller's thread; off-thread submissions can compute a stale deadline | `common/.../scheduler/GlobalSchedulerImpl.java:47,71` |
| F-27 | Medium | "Retired" runs synchronously on the **caller's** thread when the entity is unknown at schedule time, and untracked entities are indistinguishable from dead ones — a freshly spawned entity silently retires instead of running the task | `common/.../scheduler/EntitySchedulerImpl.java:52-58,74-79`; `common/.../scheduler/RegionEntityRegistry.java:177-186`; doc at `api/.../EntityScheduler.java:35-42` |

Annotation scan evidence: `grep -rn "@NetworkThread|@IoThread|@RegionThread" api/src/main/java`
returns only declarations and a Javadoc mention; `@AnyThread` has 14 uses, `@GlobalThread` 1.

---

## 4. Contract matrix — documented vs implemented

| Method | Documented | Implemented | Verdict |
| --- | --- | --- | --- |
| `RegionScheduler.run` | owner resolved at execution; missing region per policy; exceptions via failure policy | owner resolved at **schedule** time; `RUN_ON_GLOBAL` hardcoded; enqueue result ignored | **Wrong** (F-04, F-06, F-12) |
| `RegionScheduler.runDelayed` | delay in owner ticks; owner re-resolved; policy applies | owner resolved at schedule; no chunk position → split may strand it; policy applies | **Partly wrong** (F-05) |
| `RegionScheduler.runAtFixedRate` | re-resolves owner each period; merge keeps task; death re-resolves | self-reschedules from position each period, but each fire is queued without a chunk position and can run in a non-owner region after a split | **Partly wrong** (F-05) |
| `RegionScheduler.runOrSchedule` | inline only if caller is the owning region | compares `ThreadOwnership.current().region() == owner` by identity — matches | **OK** |
| `RegionScheduler.send` | `false` iff no owner; never throws | `false` for no owner **and** closed scheduler **and** dead region; throws `ISE` for unattached world | **Wrong** (F-06, F-07) |
| `RegionScheduler.ownerOf` | never blocks, never throws for a missing region | throws `ISE` when the world is unattached | **Wrong** (F-07) |
| `EntityScheduler.run` | handle is opaque; retired if the entity is gone; task follows migrations | requires `Entity`; retired may run inline on the caller; untracked == gone | **Wrong/misleading** (F-08, F-27) |
| `EntityScheduler.runDelayed` | delay in owner ticks; revalidates at execution | resolves owner at schedule, delay revalidated; same inline-retired issue | **Partly wrong** |
| `EntityScheduler.runAtFixedRate` | follows migrations each firing; retired once | matches, but retired inline at schedule time | **OK with caveat** |
| `GlobalScheduler.run` | runs on global context, must not block | matches; after `close()` still accepts into a queue nothing drains | **Wrong at shutdown** (F-09) |
| `GlobalScheduler.runDelayed` | deadline = `currentTick + delay` | reads a non-volatile counter off-thread | **Racy** (F-26) |
| `GlobalScheduler.runOrSchedule` | callable from any thread, inline only on global | matches; but annotated `@GlobalThread` | **Doc/annotation conflict** (F-17) |
| `AsyncScheduler.run` | rejects with `RejectedExecutionException` when full; no region/global context | matches (`LinkedBlockingQueue(256)`, `AbortPolicy`); enters `ASYNC` not `IO` | **Doc drift** (F-11, F-23) |
| `AsyncScheduler.runAtFixedRate` | no self-overlap; cancel from any thread and idempotent | `scheduleWithFixedDelay` + `future.cancel(false)` | **OK** |
| `ThreadContext.kind` | REGION/GLOBAL/NETWORK/IO/ASYNC/UNKNOWN | server thread = `UNKNOWN`; `IO` unreachable | **Gap** (F-10, F-11) |
| `ThreadContext.mayAccessRegion` | true only for that region's owning context | identity comparison against the current `RegionInfo`; a consumer wrapper always returns false | **OK, undocumented identity requirement** |
| `ThreadContext.assertRegionAccess` | respects STRICT/WARN/OFF | delegates to `ViolationReporter` (mode honored); null target renders "unknown" | **OK** |
| `FabricFoliaApi.isEnabled` | "regionized execution currently active; false while disabled" | `!closed` | **Wrong** (F-13) |
| `FabricFoliaApi.Initializer` | called once per server start, on the server thread, before the first tick | called at end of `SERVER_STARTED` on the server thread; **not called at all when disabled** | **Partly wrong** (F-14) |

---

## 5. Missing API surface (gaps, by category)

1. **Version/capability discovery:** no `apiVersion()`, no feature flags (regionized
   entities / block entities / scheduled ticks / player path), no mod-metadata marker.
   A mod cannot decide whether scheduling is necessary or whether direct access is safe.
2. **Lifecycle:** no shutdown notification; no stable post-shutdown behavior; no
   `onUnavailable` for the disabled mode; no way to re-acquire the API after a reload.
3. **Task lifecycle:** no handle for one-shot submissions (`run` returns `void`), no
   completion/failure callback, no rejection signal, no way to ask whether a task is
   queued/running, no task name/description for diagnostics attribution.
4. **Region lookup:** chunk-only `Position`; no block-coordinate factory; no section
   size/shift accessor; `RegionInfo` cannot answer "do you own this chunk?" without a
   second lookup; no world/region key type.
5. **Entity safety:** no MC-typed facade (so no compile-time safety), no `world`-scoped
   handle, no "is this entity tracked" predicate, no documented context for `retired`.
6. **Async/IO guidance:** `@IoThread` exists but there is no IO scheduler and no
   documented bridge from async to region (the internal `runOnRegion` at
   `common/.../scheduler/AsyncSchedulerImpl.java:180-188` is not on the API).
7. **Nullability:** documented in prose only; no package-level policy.
8. **Docs/examples/tests:** none of the three exist for the API module.

---

## 6. Minimum backward-compatible path to a credible public API

Ordered by value per unit of risk. Nothing here removes or re-signatures existing
public methods; everything below is additive or doc-only.

### P0 — Make the docs true (no ABI change, 1 short turn)

1. Rewrite `FabricFoliaApi` class Javadoc: remove the "later phase / not initialized"
   paragraph, state the real lifecycle and the disabled-mode behavior (F-03, F-14).
2. Either implement execution-time owner resolution for `run` (F-04, behavior fix) or
   state snapshot semantics and make `send` the explicitly-snapshot variant. Do not
   leave the class Javadoc claiming the opposite.
3. Fix `ownerOf`/`send` docs **or** the adapter: return empty/false for an unattached
   world instead of `ISE`, and reserve `ISE` for "API shut down" (F-07).
4. Redefine `isEnabled()` in docs as "API is live (engine started and not stopped)"
   **or** implement it from engine/config state; do not call it "regionized execution
   active" (F-13).
5. Remove `@GlobalThread` from `GlobalScheduler.runOrSchedule`; it is `@AnyThread`
   (F-17). Delete the unused `RegionThread`/`TimeUnit` imports in `RegionScheduler`
   (F-18).
6. Fix `AsyncSchedulerImpl`'s own Javadoc (IO → ASYNC; `SynchronousQueue` → bounded
   `LinkedBlockingQueue(256)`) (F-11, F-23).
7. Fix `README.md:90,108-109` artifact name to `folia-0.1.0.jar`; either create
   `docs/developer/api.md` or remove the dangling reference in
   `docs/compatibility/README.md:66-68` (F-02).

*ABI:* none. Removing a CLASS-retention annotation is source/binary compatible.

### P1 — Make it consumable (the actual blocker)

8. Add `maven-publish` to `api`: set a real `artifactId`
   (`fabric-folia-api`), attach `withSourcesJar()`/`withJavadocJar()`, add
   `Automatic-Module-Name`, and publish to a stated repository (GitHub Packages is the
   lowest-friction option given the existing `credential.helper` setup; Maven Central via
   Sonatype if this is meant to be a durable public API). Document coordinates in
   `README.md` and `docs/developer/api.md` (F-01, F-02).
9. Decide and document the **runtime distribution model**:
   - Recommended: a separate `fabric-folia-api` **mod jar** with its own
     `fabric.mod.json` (stable id + version), installed/declared as a dependency
     (`"depends": {"fabric-folia-api": ">=1"}`), and **not** duplicated inside
     `folia.jar`.
   - If nested-only is kept, prove that a second mod can link against the nested classes
     and that bundling a copy does not fork `Class` identity. Add a real consumer fixture
     (AC-19).
10. Add a consumer smoke module (`api-consumer-smoke`) that depends **only** on the
    published API coordinate and compiles an `Initializer`; wire it into
    `./gradlew build`/Jenkins so the public contract is compiled from outside the
    multi-project (AC-2).

*ABI:* additive build config; no API source change.

### P2 — Close the contract holes (additive API only)

11. Add an overload family that returns a handle and reports rejection, without
    changing existing signatures:
    `Submission submit(Position, Runnable)`, `submitDelayed(Position, int, Runnable)`,
    each with a `MissingRegionPolicy` parameter; `Submission` exposes `cancel()` and
    `isCancelled()`/`isDone()`. Keep `run`/`runDelayed` delegating (F-06, F-12).
12. Pass the target chunk position through `enqueueDelayed` in the adapter so delayed
    and repeating work re-homes on split like `send` already does (F-05). Behavioral
    fix, no ABI.
13. Unify shutdown: after `close()`, every scheduler method throws
    `IllegalStateException` (or a new `SchedulerClosedException extends
    IllegalStateException`); make `GlobalSchedulerImpl.close()` set a closed flag and
    reject instead of accepting forever (F-09).
14. Add default methods (binary-compatible) instead of new abstract methods:
    `ThreadContext.isServerThread()`, `CancelHandle.isCancelled()` (default `false`
    would lie — prefer a new `Cancellable`), `FabricFoliaApi.apiVersion()`.
    **Do not** add abstract methods to `CancelHandle`: the engine itself returns a
    lambda (`AsyncSchedulerImpl.java:128`), so that would break in-tree and third-party
    implementors.
15. Add `FabricFoliaApi.Initializer.onUnavailable(String reason)` as a **default**
    method and deliver it in the disabled/config-error paths, so region-aware mods get a
    callback instead of silence (F-14).
16. Add `FabricFoliaApi.capabilities()` (a small record/enum set: REGION_SCHEDULER,
    ENTITY_SCHEDULER, GLOBAL_SCHEDULER, ASYNC_SCHEDULER, ENTITY_TRACKING,
    PLAYER_PATH_STAGING) and populate from engine/config (F-15).
17. Entity safety: move the world lookup into the resolver (registry already knows the
    handle → region, and can record the world) so no `Entity` field is read on the
    caller's thread; validate the handle with a message that names the required type.
    Longer term, publish an MC-typed companion artifact (`fabric-folia-api-fabric`) with
    `Position.of(Level, BlockPos)` and `EntityScheduler.run(Entity, …)` typed overloads,
    and deprecate the `Object` handle (F-08, F-27).
18. Document `retired`'s actual execution context (may be the caller's thread,
    synchronously at schedule time) and consider guaranteeing "always on the global
    context" as a 1.0 contract (F-27).

*ABI:* new overloads and default methods are binary-compatible. Do **not** add enum
constants to `ThreadContext.Kind` in a point release: consumer `switch` statements
compiled against the old constant set can throw at runtime when a new ordinal appears.
Prefer `isServerThread()` first; add `Kind.SERVER` only at a declared major version.

### P3 — Make the ownership story honest end-to-end

19. Choose one of: (a) re-resolve the owner at execution for `run` (matches the
    documented model and the position-following rationale), or (b) keep snapshot
    semantics everywhere and document `run`, `send`, `runDelayed` as snapshot-at-schedule
    with `runOrSchedule` as the only inline path. Today the class doc sells (a) while the
    code does (b) (F-04).
20. Add metrics for API-submitted tasks (submitted/rejected/dropped, by scheduler and
    failure reason) so third-party work is observable in `/folia metrics`, per
    `AGENTS.md` §6 and the project's own mandate ("if you add behavior, make it
    observable").

### P4 — Harden

21. Add `@Nullable`/`@Nonnull` (or a single documented `@NullMarked` package policy)
    and `package-info.java`; annotate `RegionInfo.sectionCenter()`, `EntityScheduler`
    `retired`, `ThreadContext.assertRegionAccess` target, `Position.of` world (F-16).
22. Add `equals`/`hashCode`/`toString` + argument validation to `Position`; document or
    expose the section shift (F-21, F-22).
23. Deprecate `CancelHandleView` as a public base class (keep it public for binary
    compatibility until a major release) and document that it will be hidden (F-20).
24. Replace internal "spec N" Javadoc references with links to public docs (F-24).

---

## 7. Testable acceptance criteria

Each criterion names the artifact, the check, and the expected result. They are written
to be runnable later by whoever implements the fix; none were executed this turn.

### Packaging / consumer

- **AC-1** `./gradlew :api:jar` exits 0 and `api/build/libs/` contains exactly one jar;
  `jar tf` on it lists all 14 API classes (5 annotations + 9 top-level types).
- **AC-2** `./gradlew :api:publishToMavenLocal` exits 0. A scratch Gradle project with
  only `implementation("com.palordersoftworks.fabricfolia:fabric-folia-api:0.1.0")`
  and a class implementing `FabricFoliaApi.Initializer` compiles with
  `--offline` after the publish (no project dependency on `:api`).
- **AC-3** `./gradlew :fabric:jar` exits 0; `unzip -l fabric/build/libs/folia-0.1.0.jar`
  shows `META-INF/jars/`; extracting the nested API jar yields a `fabric.mod.json` whose
  `id` and `version` are documented values (not the Gradle project name `api`), **or**
  the API is no longer nested and AC-2 is the only distribution path.
- **AC-4** `./gradlew :api:javadoc` exits 0 with no "no @param" warnings for public
  methods; `api/build/libs/` contains a `-sources.jar` and a `-javadoc.jar`.

### Entrypoint lifecycle

- **AC-5** A fabric test (`ApiEntrypointDeliveryTest`) registers a fixture
  `FabricFoliaApi.Initializer`, boots the engine as `NetworkDispatchTest` does, and
  asserts: called exactly once, non-null API, `isEnabled()` true, all four schedulers
  non-null, `threadContext()` non-null.
- **AC-6** With `general.enabled=false`, the same fixture's `onUnavailable(reason)` is
  called exactly once and `onInitialized` is not called; the reason string is non-empty.
  Log contains one line naming the fixture mod id.
- **AC-7** `FabricFoliaApi.apiVersion()` is a positive int and does not change within a
  release; `capabilities()` contains `REGION_SCHEDULER`, `ENTITY_SCHEDULER`,
  `GLOBAL_SCHEDULER`, `ASYNC_SCHEDULER`, `ENTITY_TRACKING` in enabled mode and an empty
  set in disabled mode.

### Scheduler contracts

- **AC-8** `run` on a position whose chunk is owned executes the task with
  `threadContext().kind() == REGION` and
  `threadContext().currentRegion().regionId() == ownerOf(position).regionId()`.
- **AC-9** Split test: schedule `runDelayed(position, 40, task)` for a chunk that a
  forced split moves to a child region; the task must run in the region that owns the
  position at execution time (assert the executing `RegionInfo`), not the stale parent.
  This test is expected to **fail** on current `main` and is the regression proof for
  F-05.
- **AC-10** `ownerOf(Position.of("<unattached world>", 0, 0))` returns
  `Optional.empty()` and does not throw; `send(...)` for the same position returns
  `false` and does not throw (F-07).
- **AC-11** With a closed engine/scheduler, every one of
  `regionScheduler().run`, `.runDelayed`, `.runAtFixedRate`, `.send`,
  `globalScheduler().run`, `asyncScheduler().run` throws the same documented exception
  type and message pattern; none silently accepts and none executes later (F-09).
- **AC-12** When a position has no owner, `RUN_ON_GLOBAL` executes the task with
  `kind() == GLOBAL`; `DROP` does not execute it and is observable as a dropped-task
  metric (F-12).
- **AC-13** `runAtFixedRate` cancel: `cancel()` twice does not throw; after cancellation
  and 3 elapsed periods the task has not run again; cancelling from a different thread
  mid-run does not produce an extra firing.
- **AC-14** `EntityScheduler.run` with a non-`Entity` handle throws
  `IllegalArgumentException` whose message names the required type; with a tracked
  entity the task runs in the owner's context; with an untracked/low-level entity the
  retired callback runs and its executing thread is the documented one (F-08, F-27).

### Thread context / region lookup

- **AC-15** `threadContext().isServerThread()` is true on the server thread and false on
  a region worker, the global dispatcher, and an async task; `kind()` on the server
  thread is no longer `UNKNOWN` (either a new `SERVER` constant or `isServerThread()`
  covers it) (F-10).
- **AC-16** No production path returns `Kind.IO`; either remove it from the public enum
  at a major version or document it as reserved and cover it with a test asserting it is
  unreachable (F-11).
- **AC-17** `RegionInfo.sectionCenter()` returns `null` for a dead/empty region and a
  2-element array for a live one; the units are documented and a `sectionChunkShift()`
  accessor (or documented constant) exists (F-22).

### Docs / nullability / hygiene

- **AC-18** `docs/developer/api.md` exists and contains: Maven/Gradle coordinates, the
  `"fabricfolia"` entrypoint JSON snippet, the disabled-mode behavior, a thread-context
  matrix, and runnable examples for (a) edit a block by position, (b) follow an entity,
  (c) async → region handoff. Every relative link in it resolves.
- **AC-19** A fixture consumer mod (test resources) declares the `"fabricfolia"`
  entrypoint, is placed in the dev `mods/`, and a `runServer` boot logs
  `Region-aware mod initialized: <fixture>` exactly once with zero mixin errors. If the
  nested-jar distribution is kept, the same fixture must also be booted with the API jar
  bundled and must not throw `ClassCastException`.
- **AC-20** `./gradlew :api:test` executes at least one test (`--info` shows
  `executing ... test`), not `NO-SOURCE`; `Jenkinsfile` keeps
  `allowEmptyResults: false` for the API lane.
- **AC-21** `javap -v` on the compiled `RegionInfo.class` shows a `Nullable`
  RuntimeInvisibleAnnotation on `sectionCenter`, and `grep -rn "@Nullable" api/src/main/java`
  is non-empty (F-16).
- **AC-22** Doc-truth regression checks: `grep -n "later phase\|not initialized"
  api/.../FabricFoliaApi.java` is empty; `README.md` names `folia-0.1.0.jar`;
  `grep -rn "docs/developer" .` resolves to an existing file (F-02, F-03).
- **AC-23** `ARCHITECTURE.md` no longer claims the entity resolver is a stub or that
  `RUN_ON_GLOBAL` degrades to a drop (`ARCHITECTURE.md:261-265`), since both are
  implemented (`fabric/.../engine/FabricFoliaEngine.java:397-402`,
  `fabric/.../engine/FabricFoliaApiImpl.java:110`).

---

## 8. Things this audit explicitly does not claim

- Runtime class visibility of the nested API jar was **not** measured; §2.2 states the
  risk and AC-3/AC-19 define how to settle it. Do not treat the nested-jar distribution
  as proven.
- No build, test, or server boot was run (task constraint), so all implementation claims
  are from source reading, not execution.
- `common`'s internal scheduler (`common/.../scheduler/RegionScheduler.java`) is not the
  public `RegionScheduler`; findings about it are cited only where the public adapter
  delegates to it.
- Performance of the API surface was not measured; F-25/F-26 are structural
  observations, not benchmarks.

---

## 9. File reference index

| Path | Why cited |
| --- | --- |
| `api/build.gradle.kts:12-24` | no publishing; unused jsr305 claim |
| `api/src/main/java/com/palordersoftworks/fabricfolia/api/FabricFoliaApi.java:15-20,47-51,53-59` | stale lifecycle doc, `isEnabled`, entrypoint doc |
| `api/.../RegionScheduler.java:15,17,28-31,35-41,43-63,144-165,171-195` | imports, owner-resolution claim, policy, Position, send/ownerOf docs, CancelHandleView |
| `api/.../EntityScheduler.java:20-23,35-42,54,67,88` | opaque-handle claim, retired contract |
| `api/.../GlobalScheduler.java:58-68` | `@GlobalThread` misuse |
| `api/.../ThreadContext.java:51-64,90,103` | Kind enum, mayAccessRegion, assertRegionAccess |
| `api/.../AsyncScheduler.java:29-32` | queue/backpressure doc |
| `api/.../RegionInfo.java:63-78` | stateName/sectionCenter contract |
| `api/.../annotations/GlobalThread.java:28-36` | annotation meaning |
| `fabric/build.gradle.kts:24-28,40-43` | artifact name; nested include |
| `fabric/src/main/resources/fabric.mod.json` | no `custom`/capability metadata |
| `fabric/.../FabricFoliaMod.java:109-132,248,259-279` | disabled early returns, delivery point |
| `fabric/.../engine/FabricFoliaApiImpl.java:50-55,57-89,96-112,152-161,180-187` | shutdown, adapters, hardcoded policy, Entity cast |
| `fabric/.../engine/FabricFoliaEngine.java:397-402,1150` | real resolver wiring; `api.close()` |
| `fabric/.../engine/ServerThreadDeferral.java:27` | internal server-thread predicate not exposed |
| `common/.../scheduler/ApiRegionScheduler.java:52-64,79-93,118-125,133-139,142-145` | owner resolution, split gap, silent drop, policy |
| `common/.../scheduler/RegionScheduler.java:327-356,359-367,418-465,509-521` | split re-home, dead-region drop, enqueue results |
| `common/.../scheduler/EntitySchedulerImpl.java:50-69,129-157` | schedule-time resolve, inline retired, follow |
| `common/.../scheduler/RegionEntityRegistry.java:177-186` | untracked/dead → null |
| `common/.../scheduler/GlobalSchedulerImpl.java:47,53-61,191-195` | non-volatile tickCount, accept-after-close |
| `common/.../scheduler/AsyncSchedulerImpl.java:31-34,36-43,82-87,117-129,210-214` | IO doc drift, queue size, fixed rate, close |
| `common/.../thread/ThreadOwnership.java:38-39,90-99` | UNKNOWN default, side contexts |
| `common/.../thread/ThreadContextImpl.java:48-58` | identity-based access check |
| `common/.../region/Region.java:312-330` | sectionCenter units |
| `README.md:90,108-109,112-114` | wrong artifact name, unsupported "compile against this" claim |
| `docs/compatibility/README.md:66-68` | dangling `docs/developer/` |
| `Jenkinsfile:31,39-41` | `:api:test` with empty results allowed |
| `ARCHITECTURE.md:261-265` | stale stub/degrade claims |
