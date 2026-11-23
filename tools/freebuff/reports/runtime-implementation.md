# tools/freebuff/reports/runtime-implementation.md — R1 teleport patch, finished and reviewed

**Role:** R1 teleport patch reviewer and implementation finisher
**Date:** 2026-11-22
**Tree:** `/tmp/ff-reconcile`, HEAD `c2fbe2135945e4e0ef42fc70094d3605ef03627d`, **no unmerged paths**
(`git diff --name-only --diff-filter=U` empty). The pre-existing uncommitted R1 diff was preserved:
no stash, reset, checkout, commit, push or Java sync was performed.

**Scope honoured:** only `EntityTeleportMixin.java`, `RegionTransitions.java` and the Fabric
regression `EntityTeleportDispatchTest.java` were edited. No common-module, config, mixin-register
or docs change (this file is the only documentation artifact). **Verdict: revised after manager
review — ACCEPT (E3 only, see §7 for the untested boot half).** The first revision was rejected by
manager review for a thread-flag over-match; §3.1 records the defect and the fix.

Edited-file hashes (SHA-256, working tree — **revised tree**, supersedes the hashes in the first
revision of this report):

```
2c205f0e9a821ed5287f9ca0f48a7ae27a3c8447c07c72559da6279bfddfc5fc  engine/RegionTransitions.java
08a09b1aee1c646927539eabf9f4c8e692f8f440589883b061a0bbaf279d1290  mixin/EntityTeleportMixin.java
f4bd094680d2881dca20ce88bd3dbc7e0ff419a1954e21b1eb93ce97bcd0bbb8  engine/EntityTeleportDispatchTest.java
```

Tree: HEAD `c2fbe21`, dirty worktree, three intended paths (`EntityTeleportDispatchTest.java` is
untracked, as it was before this session). No commit, push or Java sync.

---

## 1. Inheritance: what I received and what I own

Two files were edited by another session before this assignment landed. Both were treated as
unverified.

| File | Inherited edit | Verdict |
| --- | --- | --- |
| `engine/RegionTransitions.java` (mtime 02:28:50) | `EntityTransitionKey` and `transitionKey(Entity)` changed from package-private to `public`, plus a public canonical constructor | **Correct and required — kept.** `EntityTeleportMixin` lives in `...fabricfolia.mixin`, a different package, and calls `RegionTransitions.transitionKey(self)`. Without the visibility change the mixin does not compile. |
| `engine/EntityTeleportDispatchTest.java` (mtime 02:30:37) | 8-test suite built around `DispatchToken`, `DUPLICATE_IN_FLIGHT` and `inFlightCount()` | **Not kept as-is.** It encoded the chunk-keyed duplicate semantics the assignment forbids (`DispatchKey = entity + destination world + chunk X/Z`). Its *coverage intent* is preserved and extended in the rewritten 14-test suite; no assertion was weakened (see §5). |

Everything else in both files is mine. `EntityTeleportMixin.java` (mtime 01:44, the prior R1
agent) was rewritten because its body called the resolver with only a REGION gate and paid the
`isSameRegion` lookup on every worker call.

## 2. What was wrong with the inherited patch

1. **Context matrix was absent.** `isCurrentContextOwner` is REGION-only, so:
   a global-lane body re-entered with `GLOBAL`, failed the owner check, found its own live token
   and answered `DUPLICATE_IN_FLIGHT` → cancelled again → vanilla never ran; and the ordinary
   server thread (`UNKNOWN`) had every teleport dispatched, including same-region ones that
   baseline ran inline.
2. **Positionless enqueue.** `scheduler.enqueue(owner, body)` carried no `targetChunkPos`, so
   `RegionTaskQueue.partitionOnSplit` could not re-home the body and the core D4 execution-time
   net had nothing to compare (R6).
3. **Chunk-level identity.** Two intentional teleports to different positions/rotations inside
   one destination chunk collapsed into one dispatch and the second body was dropped as a
   duplicate.
4. **Token lifecycle.** A token whose `target` was `null` (global lane) reported live as long as
   the scheduler was open — it could not distinguish "about to run" from "stranded".
5. **Rejection fall-through.** `DROPPED` fell through to the vanilla body in *every* context, so a
   REGION/NETWORK/ASYNC caller could still execute the vanilla teleport on a thread that does not
   own the destination.
6. **Hot-path diagnostic.** `isSameRegion` was called on every REGION teleport and its result
   discarded.
7. **`guardedBody` caught `Throwable`** (R8 shape).

## 3. Implemented semantics (`RegionTransitions`)

`Decision` is now `INLINE | DISPATCHED | DEFERRED_TO_SERVER | DROPPED`. The dispatch-token map,
`DUPLICATE_IN_FLIGHT`, `inFlightCount()` and the reservation loop are gone.

**Identity is a per-invocation token, not a key.** `resolveEntityTransition` creates a
`TransitionToken` (world + destination chunk + entity) for each call and wraps the body so the
token is installed in a `ThreadLocal` for exactly the duration of the body. There is no stored
token, so there is no lease, no TTL, no retirement callback and nothing that can leak or strand;
liveness is "the body is running". No common-module scheduler callback was needed, so no
dependency blocks this work.

### 3.1 Token match is exact (manager-review fix)

The first revision short-circuited on `RUNNING.get() != null`, i.e. **any** nested teleport on a
thread running a transition body. A body dispatching entity A to region R that also teleported
entity B (or a passenger/vehicle, or another entity's portal) to region S would have run B's
vanilla body inline on R's worker — the same wrong-owner class R1 exists to remove, moved from
the key map into a thread flag.

The marker now answers only for an exact match of the invocation: same
`EntityTransitionKey` (source world + entity id), same destination world key, same destination
chunk X/Z. `TransitionToken.matches(...)` compares all four with `Objects.equals`, so a null
entity key only matches a null entity key. **A non-matching nested call falls through to the
normal context matrix** and is dispatched to its own owner (or deferred on rejection).

No vanilla path justifies a coarser match: `Entity.teleport` moves exactly one entity to exactly
one `ServerLevel`/position, `TeleportTransition` carries both, and the passenger/vehicle paths
call `teleport` per entity — so entity identity plus destination is precisely the unit of work.
A nested call for the *same* entity and destination inside its own body is the same logical
transition re-entered and correctly runs inline.

**Context matrix** (evaluated only after the token re-entry check):

| Context | Destination owned by current region | Destination owned elsewhere | Destination unowned |
| --- | --- | --- | --- |
| `REGION` | `INLINE` | `DISPATCHED` | `DISPATCHED` (global lane) |
| `GLOBAL` | `DISPATCHED` | `DISPATCHED` | `INLINE` |
| `NETWORK` / `ASYNC` / `IO` | `DISPATCHED` | `DISPATCHED` | `DISPATCHED` (global lane) |
| `UNKNOWN` on the physical server thread, `sameRegion` hint true | `INLINE` | `DISPATCHED` | `DISPATCHED` |
| `UNKNOWN` on the physical server thread, hint false | `DISPATCHED` | `DISPATCHED` | `DISPATCHED` |
| `UNKNOWN` not the server thread | `DISPATCHED` | `DISPATCHED` | `DISPATCHED` |

No context blanket-returns; NETWORK/ASYNC/IO never reach the vanilla body inline.

**Position-aware scheduling.** A destination region is reached through
`RegionScheduler.scheduleToChunk(chunkX, chunkZ, body)`, which enqueues with
`packChunkPos(chunkX, chunkZ)`; the uowned path uses `globalScheduler().run`. The test asserts the
queued `RegionTaskQueue.Entry` carries the destination position and that a split re-homes it to
the owning child.

**Rejection.** A refused enqueue (closed scheduler, dead region, lost owner) does **not** fall
through to vanilla on a foreign thread: the token-wrapped body goes to
`ServerThreadDeferral.defer(...)` and the decision is `DEFERRED_TO_SERVER` (counted). The mixin
cancels on `DISPATCHED` and `DEFERRED_TO_SERVER` only. `DROPPED` is returned only when running
inline is safe — engine null (mod disabled, no region workers exist) or an unattached destination
from `GLOBAL`/server-thread-`UNKNOWN` — which is exactly the baseline behaviour those callers had.

**An unattached destination world** (engine has no scheduler/regionizer) is counted as
`UNATTACHED` and deferred for REGION/NETWORK/ASYNC/IO callers instead of running inline.

**No new swallowing.** `guardedBody`'s `catch (Throwable)` was removed; a failing body now
propagates to the region queue's `RegionFailurePolicy` (or the global lane's per-task handler).
The one inherited swallow that remains on this path is `ServerThreadDeferral.runDeferred`
(R5/R8, out of R1 scope, listed open in §6).

**Mixin.** `EntityTeleportMixin` now: bails on a null/client-side destination or a null engine;
computes the server-thread same-region hint only when the context is `UNKNOWN`, the thread is the
physical server thread and the destination is the same `ServerLevel`; calls the resolver for every
context; cancels only on `DISPATCHED`/`DEFERRED_TO_SERVER`. No `isSameRegion` lookup is paid on a
region-worker path.

## 4. Test run (exact commands, exact statuses)

```
$ sh gradlew :fabric:compileJava --console=plain
BUILD SUCCESSFUL   (exit 0)

$ sh gradlew :fabric:test --tests "com.palordersoftworks.fabricfolia.engine.EntityTeleportDispatchTest" --console=plain
BUILD SUCCESSFUL   (exit 0)   — 14 tests, 0 failed

$ sh gradlew :fabric:test --console=plain
BUILD SUCCESSFUL   (exit 0)
```

Status captured explicitly (`out=$(...); rc=$?; echo EXIT=$rc`), not from a pipe. JUnit XML totals
from `fabric/build/test-results/test/` after the revised tree: **136 tests, 0 failures, 0 errors,
0 skipped** (R1 class: **14/14 green**, `tests="14"` in its XML). No common-module test was run or
changed. The nested-transition test is red against the first revision (the blanket flag answered
`INLINE` and its body never ran), which is the evidence that the fix is pinned.

## 5. Regression coverage (`EntityTeleportDispatchTest`, 14 tests)

| Requirement | Test |
| --- | --- |
| REGION owner re-entry | `regionOwnerDestinationRunsInline`, `regionForeignDestinationDispatchesAndReentersInline` |
| Same-token re-entry still inline | `regionForeignDestinationDispatchesAndReentersInline` (same entity + world + chunk inside the body → `INLINE`, body runs once) |
| Nested different entity / destination is NOT inline | `nestedTransitionToAnotherEntityAndDestinationIsNotInline` (another entity + another chunk → `DISPATCHED` and runs in its own region; same entity + different chunk → `DISPATCHED`; outer body runs exactly once) |
| GLOBAL no-owner re-entry | `globalContextWithNoOwnerRunsInline`, `unownedDestinationBodyReentersInlineOnTheGlobalLane` |
| Server thread same-region inline | `serverThreadSameRegionRunsInline` |
| Server thread cross-region dispatch | `serverThreadCrossRegionDispatches` (asserts a different thread and the destination region) |
| NETWORK / ASYNC never inline | `networkContextNeverRunsInline`, `rejectedAsyncDispatchDefersAndNeverRunsInline` |
| Distinct exact destinations, same chunk | `distinctDestinationsInTheSameChunkBothRun` |
| Two separate identical invocations both execute | `twoIdenticalInvocationsBothExecute` |
| Enqueue rejection safe fallback | `rejectedRegionDispatchDefersToServerThread`, `rejectedAsyncDispatchDefersAndNeverRunsInline`, `unattachedWorldNeverRunsInlineFromNetwork` |
| Accepted task follows destination chunk after split | `acceptedTaskFollowsDestinationChunkAfterSplit` |

The split test is end-to-end against the real scheduler: it closes the execution gate
(`engine.beginTickPhase()`), builds a 19-section region, dispatches through the resolver, reads the
queued entry's `chunkPos()` back, drives a real split (`tryBeginTick` + `completeTick`), and
asserts the entry moved to the child queue and then executed in the child region after
`endTickPhase()` — i.e. the position-aware enqueue plus D4 re-home, not a simulation.

Two of these tests are red against the inherited patch by construction: same-chunk distinct
destinations hit its chunk-keyed `DUPLICATE_IN_FLIGHT`, and the global-lane re-entry test hits its
`target == null` live token and never runs the body.

## 5.1 Comment / docstring policy (recorded per manager request)

Mandate 47 says new code ships comment-free, and the assignment repeated it. The revised code
still carries **contract javadoc on changed members**. Why each one is kept:

- `Decision` — defines a four-value return contract the caller's cancel decision depends on;
  without it `DROPPED` vs `DEFERRED_TO_SERVER` is not inferable and that distinction is exactly
  the R1 failure mode.
- `resolveEntityTransition` overload — records the non-obvious `sameRegion` hint rule (server
  thread only, ignored elsewhere) and the exact-match re-entry rule from §3.1.
- Class javadoc of `RegionTransitions` / `EntityTeleportMixin` — pre-existing legacy javadoc that
  predates mandate 47; the re-entrance and context paragraphs were **corrected**, not extended,
  because the old text described the token/window scheme that no longer exists. Leaving it would
  document a behaviour the code does not have.
- `TransitionToken.matches` and the helpers have no comments; the new counter declarations carry
  only the pre-existing why-notes that were already there.

No other new comments were added. If the manager prefers mandate 47 enforced literally before
merge, the three items above are the complete list to delete (the class-level paragraphs would
have to be replaced by a doc update instead, not simply dropped).

## 6. Unresolved issues / dependencies (explicitly open)

1. **R10 — `noteServerThread` wiring: STILL OPEN (blocks an exact matrix in production).**
   `ServerThreadDeferral.isServerThread()` returns `true` while no owner is recorded, and grep
   shows `noteServerThread(...)` is called **only from tests** in this tree. In production the
   "physical server thread" row therefore degenerates to "every `UNKNOWN` context". The matrix is
   exact for `REGION`/`GLOBAL`/`NETWORK`/`ASYNC`/`IO` (checked first, never affected); only a
   foreign `UNKNOWN` thread could run a same-region teleport inline, which is R10's existing
   misclassification, not new. **Manager decision needed on where to call `noteServerThread`**
   (engine bootstrap before any world attaches). This is why the fix does not touch common code.
2. **R8 / R5 — the deferred path.** A body deferred to `ServerThreadDeferral` inherits
   `runDeferred`'s non-fatal `Throwable` swallow and the queue's unbounded admission. R1 does not
   add any swallowing, but does use that queue as its safe continuation. Open.
3. **`sameRegion` hint is not unit-tested through a live entity.** The resolver row is tested with
   the explicit hint; the mixin's computation uses the existing registry-based `isSameRegion`,
   which needs a real `Entity`. Registry staleness during a body is irrelevant here (a body
   re-entry exits on the token first).
4. **No live boot / mixin-apply check (E4 absent) — still absent and a merge condition.** The
   assignment limited verification to focused Fabric tests, so no `:fabric:runServer` boot, no
   `Done (` assertion, no STRICT-mode portal/`/tp` gameplay check was run. The mixin target
   descriptor is unchanged from the audited patch and `javap`-verified in `runtime.md` §1, and the
   module compiles, but **mixin application is not proven by this session.** AGENTS.md §2.5
   requires one boot check before merge; this report does not satisfy it.
5. **Extra structure-lock acquisition.** `resolve` looks up `ownerOfChunk` and
   `scheduleToChunk` looks it up again, so a dispatch costs two structure-lock reads (R3 is
   already open on this lock). Kept deliberately: the second lookup is what makes a split between
   decision and enqueue land the body on the new owner instead of a dead region.
6. **`RegionTransitions.dispatch(ServerLevel, ...)`** is retained for API compatibility; the
   entity mixin uses the keyed/context variant. No production caller needs the `ServerLevel`
   overload today.
7. **Metrics surface changed**: `Snapshot` now reports `inline` and `deferredToServer` instead of
   `duplicates`, and the `/folia metrics` line text changed accordingly. `inFlightCount()` was
   removed with the token map. Nothing outside the R1 files referenced them.

## 7. Verdict

**ACCEPT** for the R1 code path as reviewed: the per-context matrix, exact-match invocation-token
identity (entity + world + chunk; non-matching nested calls go through the matrix), position-aware
enqueue, safe rejection continuation and the 14 required regressions are implemented and green
(14/14 R1, 136/136 Fabric, exit 0). **E3 only** — the live dedicated-server boot/portal check
(§6.4) is still absent and remains a merge condition, and R10's `noteServerThread` wiring (§6.1)
remains open as the one dependency that keeps the server-thread row exact rather than
conservative.
