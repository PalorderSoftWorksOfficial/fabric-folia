# Core scheduler patch — reconciliation review

**Reviewer:** core scheduler patch reviewer / test runner
**Date:** 2026-11-22
**Worktree:** `/tmp/ff-reconcile` (original checkout untouched)
**HEAD:** `c2fbe2135945e4e0ef42fc70094d3605ef03627d` ("docs: record c850092 delivery and dead-flag correction")
**Unmerged paths:** `git ls-files -u` = 0
**Status:** reviewed, tested by the operator, **stopped for manager approval**. Nothing was committed, pushed, or changed in `common/` by this session.

---

## 1. Preconditions checked

```
$ git -C /tmp/ff-reconcile rev-parse HEAD
c2fbe2135945e4e0ef42fc70094d3605ef03627d
$ git -C /tmp/ff-reconcile ls-files -u | wc -l
0
```

The worktree also carries unrelated dirty paths (docs, `fabric/` engine + mixins, `config/ConfigSchema.java`). This review touched none of them.

Scope of the patch under review (`git diff --stat`, plus two untracked tests):

```
common/.../scheduler/RegionScheduler.java          | 78 ++++++++--
common/.../scheduler/RegionFailurePolicy.java      |  5 +-
common/.../scheduler/RegionEntityRegistry.java     | 11 +-
common/.../scheduler/EntitySchedulerImpl.java      |  5 +-
common/.../scheduler/RegionEntityRegistryTest.java | 37 +++
common/.../scheduler/RegionFailurePolicyTest.java  | 116 +++++++---
common/.../scheduler/RegionSchedulerSplitRehomeTest.java   (new, 7 tests)
common/.../scheduler/EntitySchedulerLifecycleTest.java     (new, 4 tests)
common/.../config/ConfigSchema.java                | 31 +++---   (isolated, unapproved)
common/.../config/ConfigSchemaPatchTest.java       |  7 ++      (isolated, unapproved)
```

---

## 2. Focused test run

Run in this worktree by the **operator** (the manager's instruction to this session was not to re-run it; this session verified the artifacts instead):

```
cd /tmp/ff-reconcile
bash ./gradlew :common:test \
  --tests '*RegionSchedulerSplitRehomeTest' \
  --tests '*RegionFailurePolicyTest' \
  --tests '*RegionEntityRegistryTest' \
  --tests '*EntitySchedulerLifecycleTest' \
  --stacktrace
```

**Exit code: 0. `BUILD SUCCESSFUL in 3.37s`.**

`gradlew` has no executable bit in this worktree, so `bash ./gradlew` is required (host quirk; same JDK 25 / wrapper 9.5.1).

Artifacts inspected at `common/build/test-results/test/` (timestamps `01:56:59`):

| Class | tests | failures | errors | skipped | `@Test` in source |
| --- | --- | --- | --- | --- | --- |
| `RegionSchedulerSplitRehomeTest` | 7 | 0 | 0 | 0 | 7 |
| `RegionFailurePolicyTest` | 4 | 0 | 0 | 0 | 4 |
| `RegionEntityRegistryTest` | 10 | 0 | 0 | 0 | 10 |
| `EntitySchedulerLifecycleTest` | 4 | 0 | 0 | 0 | 4 |
| **total** | **25** | **0** | **0** | **0** | **25** |

`ConfigSchemaPatchTest` was **excluded** because the config default change is outside the original core scope and has not been approved by the manager. No full build was run.

Baseline caveat: no pre-patch baseline run was made in this session (the focused run is patched-tree only, and the manager asked for no re-run). The failing-first observations for D2/D4 therefore remain `[TEST-DESIGN]` from `core.md`, not re-observed here. `core.md` §7 records that the pre-patch tree was never executed either.

---

## 3. Patch summary and verdict

### D4 — execution-time ownership net (`RegionScheduler.java`)

- New per-entry net at the top of the drain (`RegionScheduler.java:704–727`): a position-bearing entry whose chunk is owned by a *different live* region is appended to that region's queue, counted in `rehomedAtExecution`, and skipped (`continue`) so it cannot run under the stale region.
- One-entry memo (`lastPos` / `lastOwner`) reuses the resolved owner for consecutive entries carrying the same position.
- `owner == null` or a dead owner keeps the previous behavior (execute locally) but is counted in `executedWithoutOwner`, with one once-only diagnostic (`unownedTaskReported`) instead of a per-entry log storm.
- Public counters `tasksRehomedAtExecution()` / `tasksExecutedWithoutOwner()`; private `ownerOfPackedChunk` helper.

Verdict: **correct and in scope for the position-bearing case.** It closes the post-`partitionOnSplit` race and, per the tests, does not double-execute, does not reorder re-homed entries, and leaves `Entry.NO_POSITION` untouched. It does **not** cover positionless entries (see §5, R6).

### D2 — `ABORT_REGION` is now performed (`RegionFailurePolicy.java`, `RegionScheduler.java`)

- `reportTaskFailure` returns `RegionFailurePolicy.Action` (`RegionFailurePolicy.java:126–128`).
- On `ABORT_REGION` the drain stops (`break`), the tick body is skipped, and the tick-end protocol calls `regionizer.abortTick(region, cause)` instead of `completeTick` (`RegionScheduler.java:750–760, 768–772, 793–799`). `abortTick` fires `onRegionDead`, which drops the queue with the existing counted diagnostic, so the remaining work is visible, not silent.
- `failurePolicy.onSuccess(region)` after every normally-returning task and after a successful tick body (streak reset).
- `failurePolicy.onRegionRemoved(region)` from `onRegionDead` (`RegionScheduler.java:375`).

Verdict: **the logged-but-never-performed lie is gone and the streak resets.** Two residuals are recorded in §4 (F2, F3). This is a real behavior change (a repeatedly failing region now actually dies); the owner must sign it off.

### D1 — per-region entity sets follow the authoritative map (`RegionEntityRegistry.java`)

- `onRegionSplit` removes each retargeted handle from the parent's set while adding it to the child's (`:324–334`).
- `retire` removes the handle from the previous owner's set before firing retirement listeners (`:357–366`).

Verdict: **correct for split and retire.** The `register`/`unregister` race is untouched, so R11 remains open (see §5).

### D3 — `runDelayed` / `runAtFixedRate` honor a rejected schedule (`EntitySchedulerImpl.java`)

- `boolean enqueued = engine.enqueueDelayed(...)`; on `false`, `retired` runs once on the calling thread (`:89–93`).

Verdict: **correct and matches the documented API contract**, which already promises "when the handle cannot be resolved at schedule time, `retired` runs synchronously on the calling thread" (`api/.../EntityScheduler.java:42–44`). Scope is exactly the schedule-time rejection; accepted-then-retired loss is still open (§5).

---

## 4. Review findings

None of these failed a test; all are recorded for the manager's decision.

**F1 (correctness, narrow race, not covered by any test) — the re-home can strand a task if the target region merges or dies between resolution and the add.**
`RegionScheduler.java:708` resolves the owner under the structure lock, then `:714` does `queuesByRegion.get` / `computeIfAbsent` and `:716` adds. If a merge of that owner completes in between, `onRegionMerged` has already removed the donor queue and re-homed its entries; the add then lands in a **newly created queue for the dead donor**, which nothing drains and which `onRegionDead` has already passed. The entry is stranded until scheduler close (where it is only counted as dropped). This is the same class of silent loss D4 exists to prevent, and it is exactly the case `enqueue`'s own two-phase protocol handles by pulling the stale copy back out (`RegionScheduler.java:427–469`, phase 2 re-check + `queue.removeTask`).
Minimal fix shape: route the re-home through `enqueue(owner, task, pos)` (whose phase 2 removes the stale copy when the owner died), and on `false` re-resolve the position owner once and retry; only then fall through to the counted local execution. Not applied here: no test covers the interleaving and the assignment is review + manager gate. **Recommend fixing before D4 is called done.**

**F2 (residual) — `onRegionRemoved` is wired for death only, not for merges.**
`RegionFailurePolicy.onRegionRemoved` documents "merge/death cleanup" (`:115–119`) but the only caller is `RegionScheduler.onRegionDead`. A merged donor's id keeps its counter entry forever, so the map still grows by one entry per merge (ids are never reused). One-line addition in `onRegionMerged`; deliberately not added here (new behavior, manager gate).

**F3 (design consequence, should be accepted explicitly) — the abort threshold is now reachable only within a single drain or through a failing tick body.**
Because a successful tick body calls `onSuccess`, a region that fails exactly one queued task per tick, every tick, now never aborts. Failures that accumulate *within* one drain still abort, and a failing tick body (the common "broken region" case) still accumulates across ticks. This follows the wiring `core.md` prescribed; it is not a defect against the spec, but it changes what the threshold means and must not be mistaken for "a region that fails every tick always dies".

**F4 (scope, accept or revert) — the 8-arg `RegionScheduler` constructor went `private` → package-private** (`RegionScheduler.java:145`) so the new `failurePolicyBookkeepingIsReleasedOnRegionDeath` test can inject a policy. No production caller changes. Accepted as test-only package access; the manager should sign it off. The ledger note's second item (`*/` sharing a line with the constructor) is **pre-existing at HEAD** (`git show HEAD:...` line 118) and is not introduced by this patch.

**F5 (known limitation, already in the ledger) — a re-homed entry does not mark the target region due.** It waits for the owner's next scheduled tick (≤ one tick re-arm). Latency only, never a mis-execution.

**F6 (known limitation) — `DelayedRegionTask.targetTick()` is a per-region tick counter.** A delayed entry re-homed to a child (by `partitionOnSplit` before, by D4 now) has its deadline interpreted against the child's own counter. Pre-existing behavior of queue re-homing, unchanged here; noted because D4 widens when it can happen.

---

## 5. Unresolved limitations (do not claim fixed)

| Item | State | Evidence |
| --- | --- | --- |
| **R6, positionless tasks** | **open** | D4 skips `Entry.NO_POSITION`; packet re-homes (`RegionPlayerRouting.schedulePacketToRegion`), `RegionTransitions.dispatch`/`dispatchKeyed`/`enqueueTransition`, `CommandsMixin`, `RegionTickInterceptor.flushPass`, `NetworkDispatch.runOnOwner` still enqueue without a position. `RegionSchedulerSplitRehomeTest.entryWithoutPositionKeepsLastKnownOwnerBehaviour` only documents the omission. Task migration is **not** complete. |
| **R6, accepted-then-retired entity tasks** | **open** | D3 covers schedule-time rejection only. A task accepted into a region queue and dropped later by the DROP policy at region death still cannot run `retired`, because `RegionTaskQueue.dropAll` has no per-entry callback. Entity-task retirement is not complete. |
| **R11** | **open** | `RegionEntityRegistry.register` is still not stripe-locked; add/remove can leave an orphan set entry. D1 fixed only the split/retire half. |
| **R11b / R3** | **open, untouched** | `onRegionSplit` still calls `ownerOfChunk` per entity under the structure lock; no R3 work was done in this patch. |
| D5, D6, D7, D8, D9 | open, untouched | async positional enqueue, coordinator dispatch failure, async discard count, ledger orphan reclaim, section-map growth. |
| D2 merge bookkeeping | open | F2 above. |
| D4 merge-window loss | open | F1 above. |

---

## 6. Comment / Javadoc audit (assignment item 2)

Result: **no newly introduced code comments or Javadocs exist in this patch, so nothing was removed.**

Verified by grepping every added line in the tracked diff for `//`, `/*`, `*/` and Javadoc continuation lines (`git diff -U0 -- common/src | grep '^+' | grep -E '//|/\*|\*/|^\+\s*\*'`) — zero matches. Both new test files were read in full: the only comment block is the repo's Apache-2.0 file header, which 10 of the 15 existing scheduler tests also carry; it is a license notice, not explanatory code prose, and was retained.

The patch **deletes** three pre-existing comments rather than adding any:

- `RegionFailurePolicy.java`: `/** Internal helper kept for the scheduler's existing per-task report call. */` above `reportTaskFailure`.
- `RegionFailurePolicyTest.java`: the `// Defaults: maxConsecutiveFailures …` block and `// fail, succeed, fail, succeed … never two consecutive fails.`, plus `// No abort diagnostic: …`.

They were left deleted: restoring them would re-introduce comment prose into rewritten blocks (mandate rule 47) and this session's instruction is to remove newly introduced comments, not to re-add old ones. The manager may ask for the production Javadoc to be restored; that is a one-line comment-only change.

No source, test, config or doc file was modified by this session, so the 25-test run above applies to the exact tree being reviewed.

---

## 7. Config change, isolated (assignment item 3)

`common/src/main/java/.../config/ConfigSchema.java` flips `patches.entity-tick`, `patches.chunk-tick` and `patches.random-tick` from `Boolean.TRUE` to `Boolean.FALSE`, and rewrites their help text; `ConfigSchemaPatchTest` gains `gameplayMutationStagingIsDisabledByDefault` asserting the three new defaults.

Not touched by this session, not tested, not approved. It is outside the original core scheduler scope and changes gameplay-visible defaults. **Manager decision required: approve or reject.** If approved, `ConfigSchemaPatchTest` should be added to the focused run (or the full suite run) before it can be considered green; note that no other test in `common` or `fabric` reads these three keys, so the focused run could not have caught a default-related regression either way.

---

## 8. What the manager needs to decide

1. Approve or reject the `ConfigSchema` default flip (isolated diff, currently unapproved and untested).
2. Accept or revert the `private` → package-private `RegionScheduler` constructor (F4, test-only access).
3. Sign off on the D2 behavior change: a repeatedly failing region now really dies and its queue is dropped with a count.
4. Fix F1 before calling D4 done, or accept the merge-window loss explicitly.
5. Approve the one-line `onRegionMerged` bookkeeping release (F2), or record the leak as accepted.
6. Acknowledge that R6 (both halves), R11, R11b/R3 and D5–D9 remain open; this patch does not close them.

`FREEBUFF.md` §6 was not edited by this session: the shared worktree already carries a dirty `FREEBUFF.md` owned by other sessions, and the assignment scoped the deliverable to this report. The manager should record this turn.

No commit, no push, no R3 work, no new features.

---

# Core scheduler hardening pass — F1, F2, R6 (drop/retirement), R11

**Date:** 2026-11-23
**Worktree:** `/tmp/ff-reconcile`, **HEAD** `c2fbe2135945e4e0ef42fc70094d3605ef03627d`, `git ls-files -u` = 0
**Scope:** `common/` only. No gameplay default, no config schema change, no commit/push.

## 9. Defects fixed

| # | Defect | Fix | Where |
| --- | --- | --- | --- |
| **F1** | D4's execution-time re-home resolved the owner, then `get`/`computeIfAbsent` + `add` outside any ordered check: a merge of that owner in the window stranded the entry in a fresh queue for the dead donor, which never ticks again | The drain now re-homes through the scheduler's own two-phase `enqueue` (phase 1 add, phase 2 dead-check under the structure lock + `removeTask` rollback). On rejection the owner is re-resolved and the attempt retried (bounded, `REHOME_ATTEMPTS = 4`); only a position with no live foreign owner falls back to terminal local execution and the `executedWithoutOwner` counter. Empty queues are never created for dead regions. | `RegionScheduler.java:714` (drain), `:823–846` (`rehomeToOwner`), `:462` (enqueue overload carrying the drop hook) |
| **F2** | `RegionFailurePolicy.onRegionRemoved` ("merge/death cleanup") was wired only to `onRegionDead`, so every merged donor leaked one per-region failure counter | `onRegionMerged` now releases the donor's bookkeeping before re-homing its queue | `RegionScheduler.java:308` |
| **R6 (provable half)** | An entry accepted into a region queue was silently discarded by `dropAll` when the region died or the scheduler closed — the entity scheduler's `retired` contract was never honoured, and a task whose entity had already moved to a live region was lost. `RegionTaskQueue.dropAll` had no per-entry callback | `RegionTaskQueue.Entry` carries an optional `onDropped` hook that `dropAll` runs once per dropped entry (merges/splits/re-queues keep the entry, so the hook survives them). `EntitySchedulerImpl` supplies `recoverDropped`: it re-proves the entity's owner via the resolver — a live owner re-enqueues the task into that region's context; an absent owner runs `retired` exactly once. A throwing `retired` is reported through `RegionScheduler.reportDroppedTaskFailure` (diagnostics), never swallowed | `RegionTaskQueue.java:48,77,203–210`; `EntitySchedulerImpl.java:62,92,153,165–178`; `RegionScheduler.java:763,848` |
| **R11** | `RegionEntityRegistry.register` did `putIfAbsent` and then added to the per-region set outside the stripe lock; `unregister`/`migrate` between the two left the handle in a region set with no authoritative mapping (strong reference, never retired) | `register` performs put + set-add in one critical section under the same per-entity stripe used by `migrate`/`unregister`. Lock order is documented as structure → stripes; no stripe holder ever waits for the structure lock (migration/unregister/`RegionLocalData` touch only `ConcurrentHashMap`), so the order cannot cycle | `RegionEntityRegistry.java:169–177`, lock-order sentence updated in the class doc |

## 10. Focused run

```
$ bash ./gradlew :common:test --tests '*RegionSchedulerSplitRehomeTest' \
    --tests '*RegionFailurePolicyTest' --tests '*RegionEntityRegistryTest' \
    --tests '*EntitySchedulerLifecycleTest' --tests '*RegionSchedulerTest' --stacktrace
BUILD SUCCESSFUL in 4s      (exit 0)
```

JUnit XML at `common/build/test-results/test/`:

| Class | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| `RegionSchedulerSplitRehomeTest` | 9 | 0 | 0 | 0 |
| `RegionSchedulerTest` (regression guard for the drain change) | 11 | 0 | 0 | 0 |
| `RegionFailurePolicyTest` | 5 | 0 | 0 | 0 |
| `RegionEntityRegistryTest` | 11 | 0 | 0 | 0 |
| `EntitySchedulerLifecycleTest` | 6 | 0 | 0 | 0 |
| **Total** | **42** | **0** | **0** | **0** |

`bash ./gradlew :fabric:compileJava --rerun-tasks` → exit 0 (the `fabric` module still compiles against the changed `common` API). No full build, no boot.

New tests:
- `RegionSchedulerSplitRehomeTest.staleOwnerAtRehomeIsReResolvedInsteadOfStrandingTheEntry` — a stale (dead) owner is re-resolved to the live owner, the entry lands there once, and no queue is registered for the dead region.
- `RegionSchedulerSplitRehomeTest.rehomeWithNoLiveOwnerNeverRegistersAQueueForTheDeadRegion`.
- `RegionFailurePolicyTest.failurePolicyBookkeepingIsReleasedOnRegionMerge` — real regionizer merge (bridge recipe from `RegionizerChunkLifecycleTest`); dead regions' counters gone, survivor's kept.
- `EntitySchedulerLifecycleTest.acceptedTaskRetiresOnceWhenItsRegionDiesBeforeExecution` (fails on the pre-fix code: `retired` never ran).
- `EntitySchedulerLifecycleTest.droppedTaskIsRecoveredIntoTheEntitysProvenLiveOwner` (entity migrated, its old region died; task runs in the new owner's context, `retired` not fired).
- `RegionEntityRegistryTest.concurrentRegisterAndUnregisterLeaveNoOrphanSetEntries` — 8 threads × 500 handles, half unregistered during registration; `trackedCount == set.size()` and every set member maps back.

## 11. Honest limits of this pass

1. **F1's exact window is not schedulable deterministically.** `WorldRegionizer` is `final` and exposes no hook between `ownerOfChunk` and the queue add, so the regression drives the extracted decision (`rehomeToOwner`) with a stale owner rather than racing a real merge inside the drain. The `enqueue` phase-2 branch it depends on is the pre-existing, separately tested protocol.
2. **R11's test is an invariant hammer, not a fixed schedule.** The assertion is deterministic, the interleaving is not; it may not fail reproducibly on the pre-fix code. The fix itself is provable by lock inspection (put + set-add are now one stripe critical section), and `migrationsCompleted`/`trackedCount` invariants held across the run.
3. **R6's producer half is still open and is not in `common/`.** `RegionPlayerRouting.schedulePacketToRegion`, `RegionTransitions.dispatch`/`dispatchKeyed`/`enqueueTransition`, `CommandsMixin`, `RegionTickInterceptor.flushPass` and `NetworkDispatch` still enqueue without a chunk position, so a split cannot migrate them; that needs the destination position (or an explicit lease/pin policy) at each fabric call site. This pass does not claim positionless split migration is fixed — it fixes the other half: accepted work no longer vanishes when its region dies, and ownership is re-proven via the entity resolver before a dropped entity task is retired.
4. **Drop hooks run on the structural thread while the structure lock is held** (region death and `close()` both drop queues inside listener/close paths). The API contract says `retired` must not block, so this is acceptable, but a blocking `retired` would extend structure-lock hold time — a known R3-adjacent trade-off, recorded rather than hidden.
5. **The `rehomeToOwner` fallback** for an exhausted retry with a still-live foreign owner executes the entry locally without inventing a counter; it requires four consecutive structural invalidations in one drain and is not covered by a test.
6. `ConfigSchema.java` / `ConfigSchemaPatchTest.java` remain untouched and unapproved. R11b/R3 (structure-lock hold time, `onRegionSplit` calling `ownerOfChunk` per entity) and D5–D9 are untouched. `FREEBUFF.md` was not edited (dirty and shared); the manager should record this pass.

## 12. Changed files

```
common/src/main/java/com/palordersoftworks/fabricfolia/scheduler/RegionScheduler.java
common/src/main/java/com/palordersoftworks/fabricfolia/scheduler/RegionTaskQueue.java
common/src/main/java/com/palordersoftworks/fabricfolia/scheduler/EntitySchedulerImpl.java
common/src/main/java/com/palordersoftworks/fabricfolia/scheduler/RegionEntityRegistry.java
common/src/test/java/com/palordersoftworks/fabricfolia/scheduler/RegionSchedulerSplitRehomeTest.java
common/src/test/java/com/palordersoftworks/fabricfolia/scheduler/RegionFailurePolicyTest.java
common/src/test/java/com/palordersoftworks/fabricfolia/scheduler/RegionEntityRegistryTest.java
common/src/test/java/com/palordersoftworks/fabricfolia/scheduler/EntitySchedulerLifecycleTest.java
tools/freebuff/reports/core-implementation.md
```

No comment or Javadoc was added to the new code (mandate rule 47); the only doc text changed is the now-false lock-order sentence in `RegionEntityRegistry`'s class doc, which the R11 fix invalidates.

---

# R3 — lock-free ownership reads (ownerOfChunk)

**Date:** 2026-11-23
**Worktree:** `/tmp/ff-reconcile`, HEAD `c2fbe2135945e4e0ef42fc70094d3605ef03627d`, `git ls-files -u` = 0
**Scope:** R3 only. Runtime R2/R4 untouched, docs untouched apart from this report, no commit/push/deploy, dirty work preserved.

## 13. What changed

`WorldRegionizer.ownerOfChunk` was the last query on the hottest read path (every staged body, packet re-home, ownership check, pending-tick ledger query, entity split re-home) that acquired the structure lock — a blocking acquisition ordered behind every chunk load, merge, split and death in the world. It now answers from a published view:

| File | Change |
| --- | --- |
| `common/.../region/WorldRegionizer.java:98` | `sectionOwners` is now a `ConcurrentHashMap` (was `HashMap`). Writes stay under the structure lock, exactly as before — the map type changes only so reads are race-free; the field doc records that the map *is* the published view. |
| `common/.../region/WorldRegionizer.java:634–647` | `ownerOfChunk` computes the section key and, when the ownership-index patch is on (default), reads `sectionOwners` with **no lock**, filters a region whose `state` is already `DEAD`, and returns. The patch-gated no-index fallback keeps the previous locked linear scan verbatim. |
| `common/src/test/.../region/RegionizerPublicationTest.java` (new, 4 tests) | Deterministic publication/lock-freedom tests (see §15). |

No signature changed, no new API, no `Region`/`RegionState` change (`state` was already `volatile`, which the DEAD filter relies on).

**Why a published concurrent map instead of an immutable snapshot swap:** every structural mutation already maintains `sectionOwners` at the exact point ownership moves (`adopt`, `mergeInto`, `mergeNowLocked`, `attemptSplit`, `killRegion`, `killMergedRegion`, `removeDeadSections`) — seven call sites, all under the structure lock. A copy-on-write snapshot would rebuild a map of every owned section on each of those operations: chunk loads happen continuously at 20 TPS (`addChunk` is called for the spawn halo, player chunks and every chunk-load event), so a full-map copy per structural op is O(owned sections) per chunk load. The concurrent map publishes per key with the same ordering, at no copy cost. What it does **not** provide is a multi-key point-in-time picture; the only caller that needs one, `ownedSectionsSnapshot` (a region's tick boundary), still takes the structure lock and is unchanged.

**Publication ordering, per transition (each is what the tests assert):**

| Transition | Writes before any reader can see the new state | Ordering argument |
| --- | --- | --- |
| adopt (`addChunk`) | `sectionOwners.put(key, region)` after `region.sections.put` | a key is either absent or points at the region that owns it; never at a region that does not own it |
| immediate merge (`mergeInto`, `mergeNowLocked`) | every `from` key is re-pointed to `to` **before** `from` is marked DEAD and before `onRegionMerged` fires | a reader during the merge sees `from` (still live) or `to`; never a dead donor, never a gap |
| split (`attemptSplit`) | every moved key re-pointed to the child before `onRegionSplit` fires | the split partition in `RegionScheduler.onRegionSplit` therefore resolves the post-split owner; parents keep their remaining keys |
| death (`killRegion`) | `state = DEAD` first, then the keys are removed, then `onRegionDead` fires | the DEAD filter closes the window between the flag and the removals, so a lock-free read can never hand out a dying region |
| dead-section purge (`removeDeadSections`) | map entry removed with the section | a purged section reads as unowned, which is what it now is |

## 14. Audit: stale/dead owner semantics and the enqueue second acquisition

**Owner liveness.** A lock-free answer is point-in-time by construction. Every consumer was checked for the two cases that can now occur without a lock in between: (a) the returned region transitions immediately afterwards, (b) a position reads as unowned in a transient publication window.

| Consumer | Behaviour on a stale/dead or transient-null answer | Verdict |
| --- | --- | --- |
| `RegionScheduler.enqueue` / `enqueueBatch` | phase 2 re-checks `region::isDead` **under the structure lock** and pulls the task back out on death | safe (this is the ordered accept-vs-death chokepoint; unchanged) |
| `RegionScheduler.scheduleToChunk` | null → `false` (documented drop policy) | safe |
| drain re-home / `rehomeToOwner` (F1) | null → terminal local execution + `executedWithoutOwner` counter; stale owner → re-resolve and retry through `enqueue` | safe |
| `RegionScheduler.onRegionSplit` partition | runs under the lock after the split's publications; resolves the child | safe, and no longer depends on lock reentrancy |
| `RegionPendingTicks.release` / `isPending` | null → "nothing owed" / `false` | safe (ledger entries exist only while a capture window is open) |
| `RegionEntityRegistry` split re-home | null → `homeless` bucket, released through the registry's normal teardown | safe |
| `AsyncSchedulerImpl`, `ApiRegionScheduler.runOrSchedule` / `runOrScheduleDelayed` | null → API contract path (inline/global/refusal) | safe |
| `RegionChecks.ownsChunk` | `actual == currentOwner`; a DEAD region now yields `null` → **deny** instead of allow | safer direction (a dead region must not vouch for itself) |
| fabric `RegionTransitions` / `RegionStageHub` re-home | null → unowned/server-thread continuation; any region obtained is re-validated by enqueue phase 2 | safe |

**Transient false-null.** During `addChunk`, the target section exists (with its chunk) from `sections.put`/`haloCreate` until `adopt` publishes the owner — microseconds, under the lock, while the caller is inside the same operation. A concurrent lookup that lands in that window returns `null` ("unowned") rather than the region that is about to own it. That is the documented semantic for a position without an owner, and every consumer above degrades to its unowned path; no ownership invariant depends on a lookup succeeding. Fixing the ordering (publish the owner before registering the section) would trade this false-null for a false-positive answer, so the current order is kept and the window is recorded rather than papered over. It cannot be scheduled deterministically (`WorldRegionizer` is `final`, with no seam between `haloCreate` and `adopt`).

**`RegionScheduler.enqueue`'s second structure-lock acquisition: audited, deliberately kept.** It is the only place where "accept into a queue" is ordered against "region dies and drops that queue": phase 1 adds, phase 2 (under the lock) either confirms or extracts, so a task can never be accepted into a queue that was already dropped and left stranded. Making that check lock-free would need the queue itself to carry an admission state published atomically with the region's death — a different protocol with its own race surface, and out of R3's scope ("remove the blocking acquisition from `ownerOfChunk`"). Cost today: one uncontended `ReentrantLock` acquisition on the *enqueueing* thread (not on the worker drain path, which already runs inside the region). Note it is the same lock domain as `ownerOfChunk` used to take, so R3 does not move enqueue's cost, it removes the cost from the *read* path that never needed it.

**Observation (pre-existing, not introduced here, not fixed — R3 only):** when `enqueue` loses the phase-2 death race, `computeIfAbsent` may have just re-created an empty queue for a region whose `onRegionDead` already removed its queue; that empty `RegionTaskQueue` stays in `queuesByRegion`, keyed by a dead `Region`, until scheduler close. Bounded by the number of regions that die while an enqueue is in flight. Candidate fix for a later pass: on the dead branch, `queuesByRegion.remove(region, queue)` when the queue is empty.

## 15. Tests and evidence

New `RegionizerPublicationTest` (4 tests, all deterministic, no sleeps):

- `ownerOfChunkDoesNotTakeTheStructureLock` — a structural thread holds the structure lock (`underStructureLock` + latch) while a separate reader thread calls `ownerOfChunk`; the reader must answer within 5 s, and an unowned position must still be `null`.
- `mergePublishesTheSurvivorsSectionsBeforeTheMergeIsAnnounced` — inside `onRegionMerged` (fired under the lock, after the section moves), the reader is handed every section of the survivor and must see the survivor for each; then both clusters must resolve to one region.
- `splitPublishesChildrensSectionsBeforeTheSplitIsAnnounced` — inside `onRegionSplit`, every child's section must already resolve to that child and the parent's remaining sections to the parent; the post-split left/right regions must be distinct.
- `deathUnpublishesOwnershipBeforeTheDeathIsAnnounced` — inside `onRegionDead`, the keys the region owned must already read `null`; after the tick, the position must never resolve to the dead region.

**Red/green proof (not just "green"):** with `ownerOfChunk` temporarily restored to the locking implementation (same tree, same test file), `RegionizerPublicationTest` fails **4/4** with `ownerOfChunk blocked on the structure lock while a structural thread held it`; restored to the published-view implementation it passes **4/4**. The test uses a separate reader thread precisely so a lock-taking implementation cannot re-enter the reentrant lock and hide the blocking.

Commands actually run (all with `bash ./gradlew`, exact):

```
# focused (region + scheduler ownership consumers)
bash ./gradlew :common:test --tests '*Regionizer*' --tests '*TickLifecycleTest' \
  --tests '*RegionSchedulerTest' --tests '*RegionSchedulerSplitRehomeTest' \
  --tests '*RegionPendingTicksTest' --tests '*RegionEntityRegistryTest' \
  --tests '*RegionLocalDataTest' --stacktrace          -> exit 0

# full common module suite
bash ./gradlew :common:test --stacktrace               -> exit 0
```

| Run | Result |
| --- | --- |
| focused set | `BUILD SUCCESSFUL`, 58 tests, 0 failures/errors/skips (includes the 4 new ones) |
| `:common:test` (whole module) | `BUILD SUCCESSFUL` in 18 s, **171 tests, 0 failures, 0 errors, 0 skipped** across 29 classes (167 pre-existing + 4 new) |

No `build`, no `:fabric:test`, no boot: the change is `common`-internal, `ownerOfChunk`'s signature and the `fabric` compile surface are unchanged, and the assignment forbids a full build while other sessions work. One earlier `:common:test` invocation exited 1 with no output because the shared Gradle daemon was being replaced mid-run ("2 stopped Daemons could not be reused"); the rerun above is the recorded evidence.

## 16. Limits / open

1. No point-in-time *multi-key* guarantee from `ownerOfChunk` (single-key publication only); `ownedSectionsSnapshot` remains the locked, multi-key API for tick boundaries.
2. The transient false-null during `addChunk`'s adopt window cannot be scheduled deterministically (final class, no seam) and is documented rather than tested.
3. R3 measures no latency win: a per-call measurement would need the dev server, and the assignment allows focused unit tests only. The claim made here is structural (no lock acquisition on the read path), not a TPS or MSPT number.
4. `enqueue`'s second acquisition and the empty-queue-after-death observation are audited, not changed.
5. `FREEBUFF.md` was not updated (assignment: no docs except this report; the file is also dirty and shared) — the manager should record R3 as implemented-at-E3, with the transient-null window and the enqueue audit as the open notes.

Changed files (this pass):

```
common/src/main/java/com/palordersoftworks/fabricfolia/region/WorldRegionizer.java
common/src/test/java/com/palordersoftworks/fabricfolia/region/RegionizerPublicationTest.java   (new)
tools/freebuff/reports/core-implementation.md
```
