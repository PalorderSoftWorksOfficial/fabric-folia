# Runtime hardening — R1 re-verification, R5 fail-stop, R4 ownership audit

**Tree:** `/tmp/ff-reconcile`, HEAD `c2fbe2135945e4e0ef42fc70094d3605ef03627d`, dirty worktree
(inherited changes untouched). No commit, no push, no Java sync, no config change.

**Scope:** `fabric/` runtime + mixins only. Three source files changed
(`ServerThreadDeferral.java`, `FabricFoliaEngine.java`) plus two test files changed/added. No
common-module edit; one common-module limitation is named in §4.3 and left untouched.

**Verdicts:** R1 — unchanged and still green (14/14), plus the R10 residual is resolved (§2).
R5 — **fixed at E3**, boot-level crash-report parity still unproven (§3). R4 — **audited, not
implemented**: the required bound is not provable from vanilla source, so it stays open (§4).

---

## 1. Evidence summary

```
$ bash ./gradlew :fabric:test --console=plain
BUILD SUCCESSFUL   exit 0
JUnit XML (fabric/build/test-results/test/): 141 tests, 0 failures, 0 errors, 0 skipped

per class:
  EntityTeleportDispatchTest            14 / 0
  ServerThreadDeferralTest              13 / 0
  ServerThreadDeferralFailureTest        5 / 0   (new)
  DeferredBarrierDeadlockTest            7 / 0
  RegionStageHubFatalTest                2 / 0
  StagedDrainWindowTest                  5 / 0
```

Exit status was captured explicitly (`out=$(...); rc=$?; echo EXIT=$rc`), never from a pipe.
Focused runs before the full run: the two deferral classes (18/0), then the barrier class.

**Full AGENTS §2.5 check** (`:common:test :fabric:test :api:test build --stacktrace`) exits **1**,
from two pre-existing failures in the inherited dirty `common/` work — **not** from this change:

```
common 167 tests, 2 failures   (fabric 141/0, api 10/0)
  RegionFailurePolicyTest.failurePolicyBookkeepingIsReleasedOnRegionMerge
  RegionSchedulerSplitRehomeTest.staleOwnerAtRehomeIsReResolvedInsteadOfStrandingTheEntry
  both: AssertionFailedError: the bridge must merge one region away ==> expected true, was false
```

Non-causality: `common` does not depend on `fabric` (`fabric/build.gradle.kts` declares
`implementation(project(":common"))`; `common/build.gradle.kts` declares no project dependency), and
no file under `common/` was touched this turn. Both failures sit on the same fixture expectation
("the bridge must merge one region away") inside the uncommitted `RegionScheduler` /
`RegionFailurePolicy` changes that were already in the tree. They are reported as a limitation, not
fixed (common is out of scope).

SHA-256 of the five changed files:

```
7dd5453ff9620fd66ed4da9b453d47cccd11307301cdc2e80a24b50071af9d6f  ServerThreadDeferral.java
534434f6dfaf0fce952354726394e8730d8a71f91865eb931d8aa6169f9228c8  FabricFoliaEngine.java
fb707458869cb4bac62be8f8f0793dbec50ed798a294af7bf004a29c58adb667  ServerThreadDeferralTest.java
f4c3809c63b119bd3e5e74a7435a01fa37fde1ace5b8d8117f52e3c4f70a134e  ServerThreadDeferralFailureTest.java (new)
a124b84283fe1ce9f4b36d10513dceb504c573deb836ee1872a5806d5137d9ec  DeferredBarrierDeadlockTest.java
```

## 2. R1 — re-verified, and the R10 residual is closed

The accepted R1 implementation (exact-match `TransitionToken`, per-context matrix, positioned
enqueue, `DEFERRED_TO_SERVER` fallback) is unchanged in this turn and still passes 14/14 inside
the 141-test suite.

**R10 correction.** `runtime-implementation.md` §6.1 recorded that
`ServerThreadDeferral.noteServerThread(...)` "is called only from tests", so the server-thread row
of the matrix degenerated to "every `UNKNOWN` context". That is **not true of this tree**:

- `FabricFoliaMod.startEngine` (`SERVER_STARTED` handler) calls `noteServerThread(Thread.currentThread())`;
- `MinecraftServerMixin` calls it at the HEAD of both `processPacketsAndTick` and `tickChildren`;
- `ServerThreadDeferral.installDiagnostics(Console::error)` is installed in the same place.

So `isServerThread()` is exact from server start onward, the `sameRegion` hint is only consulted
for `UNKNOWN` contexts, and the R1 matrix row is exact in production. `runtime-implementation.md`
§6.1 is superseded by this section; the ledger's "R10 open" line should be closed on that basis.

## 3. R5 — deferred-mutation failures now fail the tick (implemented)

### 3.1 Root cause, re-proven against the 26.2 jar

`ServerThreadDeferral.runDeferred` caught every non-fatal `Throwable`, incremented `FAILED`, logged
and returned `false`, so the drain continued. `FabricFoliaEngine.drainChunkExecutorQueue` and the
pump's outer catch had the same shape. **Vanilla does not do this**, and this was re-checked
rather than assumed:

```
javap -c -p 'net.minecraft.server.level.ServerChunkCache$MainThreadExecutor'
  protected void doRunTask(java.lang.Runnable);
     0: invokestatic  Profiler.get
     5: invokeinterface ProfilerFiller.incrementCounter("runTask")
    11: invokespecial BlockableEventLoop.doRunTask(Runnable)     // no try/catch
    15: return
```

No catch block exists, so a throwing chunk task propagates to `pollTask()`'s caller and fails the
tick with a crash report. The old engine behaviour was therefore a silent divergence, not a
conservative choice.

### 3.2 Fix

`ServerThreadDeferral` (fabric, no common change):

- `runDeferred` counts `FAILED`, reports the failure through the diagnostics sink, stores it in an
  `AtomicReference` (`REPLAY_FAILURE`, first failure wins) and **rethrows** it: `RuntimeException`
  and `Error` are rethrown unchanged, a checked throwable is wrapped in `RuntimeException(cause)`.
  Fatal errors (`VirtualMachineError | ThreadDeath | LinkageError`) keep their existing
  count-and-propagate path and also record the reference.
- `REPLAYED` is now incremented per successful mutation inside `runDeferred` (previously summed
  after the loop), so accounting is exact when a pass throws. `BARRIER_DRAINED` moved to the same
  place, which also fixes it under a mid-pass throw.
- Both lanes check `replayIsHealthy()` before polling, so **after a failure no further deferred
  mutation is applied** from either lane until the failure is taken. Entries behind the failure
  stay queued (`pendingCount()` is preserved) rather than being replayed on torn state.
- `clearAll()` clears the pending failure too (shutdown/startup reset).
- New accessors: `replayFailure()` (peek) and `takeReplayFailure()` (take-and-clear).

`FabricFoliaEngine`:

- `beginTickPhase` calls `throwPendingReplayFailure()` at entry and again after the quiesce, before
  `global.tick()`. The second call is the one that matters: `WorkerPool.closeExecutionAndAwait`
  (common) swallows anything its pump throws, so a failure raised by `drainBarrierSafe` inside
  `pumpChunkExecutors` would otherwise vanish. The reference is the reliable channel.
- `pumpChunkExecutors` wraps `drainBarrierSafe` in a `catch (Throwable)` that only increments
  `quiescePumpErrors` and returns; it does not stop the pump, because the barrier must still reach
  quiescence so the stored failure can be raised by the caller. The failure is not lost: it is in
  `REPLAY_FAILURE` and is rethrown two frames up in the same method sequence.
- `global.tick()` is no longer reached when a replay failed.

Behaviour is fail-closed: the tick aborts with the original throwable, later deferred work does not
run, and `FAILED` / `pendingCount()` / the diagnostics line make the outcome visible.

### 3.3 Deterministic tests

New `ServerThreadDeferralFailureTest` (5 tests):

| Test | Asserts |
| --- | --- |
| `failingMutationThrowsTheOriginalAndStopsLaterWork` | exact instance propagates; earlier work ran; later work did not; `FAILED+1`; trailing entry stays queued; one diagnostic |
| `partialMutationIsNotFollowedByLaterWork` | a mutation that writes then throws leaves the partial write visible and the following mutation never runs |
| `failedReplayPoisonsBothLanesUntilTheFailureIsTaken` | after the throw, `drainAll()`/`drainBarrierSafe()` return 0 and apply nothing; `takeReplayFailure()` returns the exact instance and re-opens the lane |
| `barrierSafeFailurePropagatesAndPoisonsTheOrdinaryLane` | a failing barrier-safe supplier propagates with its own message, the ordinary lane stays poisoned, then resumes |
| `tickPhaseSurfacesAFailureTheQuiescePumpSwallowed` | a bootstrapped engine's `beginTickPhase()` throws the same instance the pump had swallowed, and consumes it |

Updated tests (semantics changed deliberately, assertions strengthened not weakened):

- `ServerThreadDeferralTest.failingReplayIsIsolatedCountedAndReported` →
  `failingReplayFailsThePassAndStopsLaterWork` (was: isolation and continue; now: throw, later work
  blocked, trailing entry queued, diagnostic emitted).
- `ServerThreadDeferralTest.fatalErrorPropagatesAndAccountingStaysExact` — unchanged except that it
  now also asserts `takeReplayFailure()` returns the exact fatal before the lane resumes.
- `DeferredBarrierDeadlockTest.failingTicketLoadCompletesTheBridgeExceptionallyAndIsCounted` →
  `failingTicketLoadFailsTheTickAndStillReleasesTheWorker`: additionally asserts the barrier/tick
  thread fails with `IllegalStateException("load refused")` while the parked worker still receives
  the failure and the bridge still completes. Deadlock coverage is unchanged.

### 3.4 What is still not proven (E4)

- No live injection of a failing deferred mutation, so "the server stops with a crash report naming
  the operation" is **reasoned, not observed**. The fork path is `throw` from `beginTickPhase` /
  `drainAll` inside `processPacketsAndTick` / `tickChildren`, which is vanilla's own exception path,
  but only a boot with a poisoned deferral can confirm `ReportedException` and the crash report.
- The `WorkerPool` pump catch is in `common` and remains a silent absorber; this work routes around
  it instead of changing it (see §4.3).

## 4. R4 — staged-world-mutation ownership: audit only, no code change

### 4.1 The gap, bytecode-verified in this turn

1. **The gate proves loadedness.** `RegionStageHub.vanillaNeighborhoodLoaded` requires the body's
   3×3 chunks to be resolvable via `ServerChunkCache.getChunkNow`. Nothing compares them to the
   executing region.
2. **Dispatch picks the body's own chunk owner.** `dispatch` → `regionFor(body)` →
   `regionizer.ownerOfChunk(body.chunk)`. The halo chunks may belong to another region or to none.
3. **Vanilla mutation reach is not chunk-bounded.**
   - `Level.setBlockAndUpdate(pos,state)` → `setBlock(pos,state,3)` (verified: `iconst_3`,
     `invokevirtual setBlock`) — flag 3 = neighbour + client updates, so the block's own
     `onPlace`/`neighborChanged` run inside the same call.
   - Neighbour cascades go through `CollectingNeighborUpdater`, whose only bound is the
     configurable `maxChainedNeighborUpdates` (vanilla default 512) and a per-layer work queue —
     i.e. the cascade can cross any number of chunk boundaries.
   - `ServerLevel.blockEvent(pos,block,id,param)` is literally
     `blockEvents.add(new BlockEventData(...))` on a **plain `ObjectLinkedOpenHashSet`** field, with
     `blockEventsToReschedule` a plain `List`. No lock, no mixin, drained later by
     `ServerLevel.runBlockEvents()` on the server thread. A staged worker mutating this set is a
     non-concurrent-structure write from a worker.
   - Explosions: `ServerExplosion` invokes `ServerLevel.setBlockAndUpdate` directly on the calling
     thread; no mixin intercepts it.
   - POI: `setBlock` reaches `onBlockStateChange` → `PoiManager`, a plain storage map.

### 4.2 Why no fix was implemented

The ledger's accepted fix shapes are (a) proven single-owner halo enforcement, or (b) explicit
cross-region handoff. Neither bound is provable today:

- The 3×3 halo bounds **loadedness**, not **reach**. Point 4.1(3) shows vanilla propagation is
  chunk-unbounded, so "the region owns the 3×3" does not prove "the body cannot mutate outside the
  region". Shipping a halo check and calling R4 mitigated would be exactly the "another
  loaded-neighbour check" the owner directive rejects.
- A halo *ownership* check would also be a large behaviour change: any body whose chunk is within
  1 chunk of its region's border (a function of section size, a sizeable fraction of all chunks)
  could no longer be staged at all and would run inline on the server thread, collapsing staged
  parallelism at the borders without a measured cost/benefit. That trade needs a manager decision
  and a benchmark, not a unilateral edit.
- The real fix is (b): a cross-region write/event/POI handoff with defined ordering — which needs a
  core-side ownership query and an epoch (the ledger already assigns that to `core-r1`), plus a
  decision on block events and explosions. Outside "fabric runtime and mixins only".

So R4 stays **open P0**, unchanged: staged bodies can still mutate state their region does not own.
This turn adds no claim and no mitigation.

### 4.3 Related common-module limitation (not changed)

`WorkerPool.closeExecutionAndAwait` and `shutdown(timeout, pump)` swallow `Throwable` from their
pump callback with the comment "Vanilla's executor reports task failures itself". §3.1 shows
`MainThreadExecutor.doRunTask` has no catch, so that comment's premise is wrong for chunk tasks
too. Changing it needs a common-module edit (and a pump contract that distinguishes "chunk task
failed" from "pump failed"); out of scope here. `pumpChunkExecutors` and `drainChunkExecutorQueue`
therefore remain the only place a chunk-task failure is absorbed, and they record it in
`quiescePumpErrors` / `quiescePumpLastError` for the quiesce report. That absorption is still a
divergence from vanilla and is listed as an open item, not as fixed.

## 5. Code hygiene

The new and changed code carries **no comments and no javadoc**: `runDeferred`, `replayIsHealthy`,
`drain`, `drainBarrierSafeQueue`, `drainBarrierSafe`, `clearAll`, `replayFailure`,
`takeReplayFailure`, `throwPendingReplayFailure` and the `pumpChunkExecutors` guard are
comment-free; the new test class has no comments. Pre-existing comments in those files were left
untouched (removing them is churn outside this fix).

## 6. Changed paths (working tree, uncommitted)

```
fabric/src/main/java/com/palordersoftworks/fabricfolia/engine/ServerThreadDeferral.java
fabric/src/main/java/com/palordersoftworks/fabricfolia/engine/FabricFoliaEngine.java
fabric/src/test/java/com/palordersoftworks/fabricfolia/engine/ServerThreadDeferralTest.java
fabric/src/test/java/com/palordersoftworks/fabricfolia/engine/ServerThreadDeferralFailureTest.java   (new)
fabric/src/test/java/com/palordersoftworks/fabricfolia/engine/DeferredBarrierDeadlockTest.java
```

`EntityTeleportMixin.java`, `RegionTransitions.java`, `EntityTeleportDispatchTest.java` are
untouched by this turn (R1 remains as accepted).

## 7. Open items

1. **R5 E4:** boot the dev server with a deliberately failing deferred mutation and confirm the
   crash report names the operation and no partial state is saved. Not run (no live check in this
   assignment).
2. **R4:** unchanged P0. Needs the manager's choice between proven cross-region handoff (preferred)
   and single-owner halo enforcement with a measured inline-rate budget, plus core's ownership
   query/epoch.
3. **Chunk-task error absorption** in `pumpChunkExecutors` / `drainChunkExecutorQueue` and in
   `WorkerPool`'s pump catch (§4.3).
4. **`docs/status.md` still lists R1–R5 as open P0.** R1 is accepted at E3 and R5 is fixed at E3;
   the doc needs a docs-scope pass (not edited here).
5. **Inherited `common/` red:** `RegionFailurePolicyTest` and `RegionSchedulerSplitRehomeTest` both
   fail on "the bridge must merge one region away". Pre-existing in the dirty tree, outside this
   assignment's module scope, and the reason the full AGENTS check is not green (§1).
