# Core audit — loader-agnostic region / scheduler / thread correctness

**Author:** core scheduler & region-ownership specialist
**Date:** 2026-11-22
**Checkout audited:** `main` = `94976ef` (`docs: record verified idle-pause stall fix deployment`), working tree **dirty** (26 tracked modifications + 4 untracked fabric files/tests + `tools/`), local `main` **4 commits behind** `origin/main` (`a42e96a`, `e98af65`, `c850092`, `c2fbe21`).
**Scope:** `common/src/main/java/com/palordersoftworks/fabricfolia/{region,scheduler,thread}` and their tests only.
**Status: READ-ONLY under coordinator hold.** A coordination hold arrived mid-turn: *finish the audit/report first, do not modify common production files until the manager assigns the reconciled worktree or transfers the patch.* No source, test, config, metrics, patch-registry, fabric, docs or `FREEBUFF.md` file was modified. Every fix below is specified, not applied. `FREEBUFF.md` was **not** updated because the task explicitly forbade editing it (it overrides AGENTS.md rule 1 for this turn; the manager should record this turn's facts).

No production-ready claim is made anywhere in this report.

---

## 0. Why this report can be applied later without conflict

| Check | Result |
| --- | --- |
| Locally dirty paths inside my scope | **none** (`git status --porcelain common` = only `config/ConfigSchema.java`, `config/ConfigSchemaPatchTest.java`, both out of scope) |
| Upstream (`HEAD..origin/main`) paths inside my scope | **none** (`git diff --stat HEAD origin/main` touches config, metrics, patches, and `fabric/` only) |
| Dirty fabric paths I would have needed | `RegionStageHub.java`, `RegionChunkAccessGuard.java`, `ServerThreadDeferral.java`, `StallProbe.java`, `LevelEntityTickMixin.java`, `ServerChunkCacheMixin.java`, `PacketUtilsMixin.java`, `DistanceManagerTrackingMixin.java`, `FabricFoliaEngine.java`, `FabricFoliaMod.java` — **untouched by me and by this report's patch plan** |

So the patch queue in §6 is textually independent of both the user's dirty work and the four upstream commits. It still must be applied to the **reconciled** tree, not here.

Fabrolia `f_3.2.6` was studied conceptually only. No external source was read, downloaded, decompiled or derived from; every mechanism below is described from this repository's own code and the project's documented clean-room invariants.

---

## 1. Method and evidence classes

* `[CODE]` — verified by reading the current tree at the cited file and line numbers.
* `[TEST-DESIGN]` — a deterministic test specified here, not yet written or run.
* `[REC]` — recorded by an earlier session (FREEBUFF.md §6, docs/status.md, TESTING.md); not re-measured.
* `[UNVERIFIED]` — plausible from naming/docs but requires the MC 26.2 jar or a live boot.

No build, test run, server boot or Gradle invocation was performed this turn (coordination hold, and no concurrent builds). Baseline for §7 is therefore `[REC]`, not a fresh measurement. Vanilla-behaviour claims are out of scope for this report; nothing here depends on one.

---

## 2. The core's actual contract (invariant map)

| # | Invariant | Where enforced | Holds today? |
| --- | --- | --- | --- |
| I1 | A chunk position is owned by exactly one live region | `WorldRegionizer.sections` / `sectionOwners` under `structureLock`; `adopt`, `mergeInto`, `mergeNowLocked`, `attemptSplit`, `killRegion` | **yes** [CODE] |
| I2 | A region is never adjacent-without-buffer to a foreign region | `haloCreate`/`haloRelease`/`isSupported` + `regionsNear` merge in `addChunk` | **yes** for non-empty sections; see D9 for the bookkeeping-growth caveat [CODE] |
| I3 | A ticking region never grows | `Region.tryMarkTicking` (READY→TICKING only), `mergeInto` deferral branches, `addChunk` `selectNonTicking` | **yes** [CODE] |
| I4 | Region is exactly one of TRANSIENT/READY/TICKING/DEAD | `RegionState` + transitions only under `structureLock` | **yes**, with one liveness hazard examined and refuted in §4.3 [CODE] |
| I5 | One worker executes a region tick at a time | `tryBeginTick` latch + `runRegionTick` guards | **yes** [CODE] |
| I6 | A queued task runs only in a context that owns the state it targets | `partitionOnSplit` (positions), merge re-home, `EntitySchedulerImpl.executeFollowing` | **no** — D4 [CODE] |
| I7 | An entity is owned by exactly one region in both the authoritative map **and** the per-region set | `RegionEntityRegistry` | **no** — D1 [CODE] |
| I8 | No task/failure/retry is dropped silently | queue DROP policy logs counts; `StageRetry` counts exhausted; failure policy logs | **no** for D2/D3/D7 [CODE] |
| I9 | Worker gate ordering: a task observed running is waited for at quiesce | `WorkerPool.workerLoop` busy++ before gate re-read, `closeExecutionAndAwait` close-write before busy-read | **yes** [CODE], but caller-side wait is unbounded (fabric, out of scope) |
| I10 | Lock order: structure lock → queue/local-data; nothing takes the structure lock while holding a queue lock | `RegionScheduler` javadoc + all call sites; `RegionTaskQueue` lock-free | **yes** [CODE]; one documented exception is `StageRetry`'s list lock → structure lock, no reverse path (§4.4) |

---

## 3. Defect register

Severity follows the project's runtime-report convention: `P0` = corrupts ownership or hangs a tick, `P1` = real defect with contained blast radius, `P2` = cost/observability/liveness correctness, `P3` = documentation or label drift.

Summary:

| ID | Sev | One line | Fixed here? |
| --- | --- | --- | --- |
| D1 | P1 | Split duplicates entity handles across regions' local sets; retired handles linger | no (spec + test designed) |
| D2 | P1 | `ABORT_REGION` is decided but never executed; failure streak never resets | no |
| D3 | P1 | `EntityScheduler.runDelayed/runAtFixedRate` silently drops when the owner died at schedule time | no |
| D4 | P1 | Position-carrying tasks have no execution-time ownership check (split-orphan residue) | no |
| D5 | P2 | `AsyncScheduler.runOnRegion` enqueues without a position | no |
| D6 | P2 | Scheduler coordinator dies silently on rejected submit | no |
| D7 | P2 | `AsyncSchedulerImpl.close()` discards queued tasks with no count | no |
| D8 | P2 | `RegionPendingTicks.release` leaks its entry when the chunk's owner vanished | no |
| D9 | P3 | Regionizer's global section map never shrinks; halo sections accumulate | no |

---

### D1 — Split leaves an entity in two regions' local sets (P1)

**Paths:** `common/.../scheduler/RegionEntityRegistry.java`
`onRegionSplit` (lines 306–334), `retire` (353–359), `perRegionEntities` lifecycle `onSplit` (110–124).

**Invariant violated:** I7 / mandate §15 ("every entity is owned by exactly one region at any instant"), plus the class's own claim that the per-region set is the region's entity set.

**Mechanism** [CODE]: `RegionLocalData.onSplit` gives each child a *fresh empty* set (`RegionLocalData.onSplit`, 167–176) by design ("the parent keeps its own data"). `onRegionSplit` then adds the retargeted handles to the children's sets and repoints `ownerByHandle`, but never removes them from the parent's set. The parent keeps every handle it had before the split. `retire(handle)` (used for split-homeless handles, 332) also only removes the authoritative mapping — the handle stays in the old region's set as well.

Consequences: any consumer that iterates `perRegionEntities().get(parent)` (the documented way to tick a region's entities) sees entities whose single writer is now a child. The authoritative map (`ownerOfHandle`) is correct, so this is a silent divergence between the two sources of truth, not a duplicate authoritative owner.

**Reproduction (deterministic):** the existing geometry in `RegionEntityRegistryTest.splitRetargetsEntitiesByHomeChunkAndRetiresHomeless` (lines 152–190): 34 chunks in one region, register `left`/`right`/`doomed` to the parent, empty chunks 10..23, `tryBeginTick(parent)` + `completeTick(parent,1)`. Today: `left`'s set contains `left`, `right` **and** `doomed`; `right`'s set contains `right`. The test asserts only the positive half.

**Root fix:** make set membership follow the authoritative map inside the same structural critical section:
1. in `onRegionSplit`, resolve `Set<Object> parentSet = perRegionEntities.existingStructural(parent)` once and, for every handle retargeted to a child, `parentSet.remove(handle)` before/while `childSet.add(handle)`; handles whose home chunk is still parent-owned stay put;
2. add the per-region-set removal to `retire(Object)` (look up `ownerByHandle.get(handle)` before removing the mapping and remove the handle from that region's set) so death and homeless-split retirement stop leaving residue. `onRegionDead`'s explicit `set.clear()` then becomes belt-and-braces rather than the only cleanup.

**Unit test (new method in `RegionEntityRegistryTest`):**
`splitLeavesEachEntityInExactlyOneRegionSet` — same geometry; after the split assert
`entitySetInContext(left)` equals exactly `{"left"}` (or `{"left","<still-parent homed>"}`),
`entitySetInContext(right)` equals exactly `{"right"}`,
and that `doomed` appears in **no** region's set (iterate `rz.liveRegions()`, read each set under that region's context via the existing `entitySetInContext` helper).
Also assert `registry.trackedCount() == 2` and `retiredCount() == 1`.

**Runtime test:** two protocol bots 1 section apart on opposite sides of a dead gap (or one bot walking a long line while a second stands), forcing a real `splits>=1`; after the split, assert `regionSplits` advanced and that `/folia entities` per-region counts sum to the tracked total, with no entity counted twice. [TEST-DESIGN]

**Evidence:** `[CODE]` (line-by-line above). Not executed.

---

### D2 — `ABORT_REGION` is decided and logged but never performed; the streak never resets (P1)

**Paths:** `common/.../scheduler/RegionFailurePolicy.java` — `onFailure` (83–107), `onSuccess` (109–115), `onRegionRemoved` (117–119), `reportTaskFailure` (127–129); `common/.../scheduler/RegionScheduler.java` — catch sites (700–711), tick end (734–758), default policy wiring (163–167).

**Invariant violated:** I8 + mandate §34 ("a region that fails every tick must not freeze its chunks forever"): the class documents `ABORT_REGION` as "the engine routes it to the regionizer's fail-safe kill", and `RegionFailurePolicy.diagnosticsLines()` reports a live streak.

**Mechanism** [CODE]:
* `reportTaskFailure` is `void` and discards the `Action` returned by `onFailure`. `RegionScheduler` calls it at both task catch sites and never inspects a decision. `regionizer.abortTick` is only reached from the *tick-end protocol failure* catch (747–757), never from the failure policy.
* `onSuccess` has **zero callers** in the tree (`grep`), so `consecutiveFailures` only ever increments: it is a lifetime failure counter keyed by region id. With the abort wired, a region with 5 sporadic failures over a long, otherwise healthy uptime would be killed.
* `onRegionRemoved` has **zero callers**, so `consecutiveFailures` grows by one entry per region ever created — a slow unbounded map (ids are never reused).

Net today: the operator sees `"... reached 5 consecutive failures - aborting the region"` while the region keeps ticking with `state == READY`; nothing is aborted and nothing is dropped. That is a lie in the log, which is worse than either behaviour alone.

**Reproduction (deterministic):** `RegionFailurePolicyTest.consecutiveFailuresAbortOnlyTheFailingRegion` already enqueues 8 failing tasks and asserts only the log text. Add `assertFalse(rz.liveRegions().contains(bad), "region must be dead after the threshold")` — fails today (region still live). Second, deterministic defect probe: 4 failing tasks, then one successful task, then 4 failing tasks on a 1-worker scheduler; today the counter reaches 5 and "aborting" is logged although no two failures were consecutive.

**Root fix:**
1. `reportTaskFailure` returns `RegionFailurePolicy.Action` (`onFailure` already computes it).
2. In the drain loop, on `Action.ABORT_REGION`: remember the cause, stop polling (break), flush the not-due list as today, **skip the region tick body**, and in the `finally` call `regionizer.abortTick(region, cause)` **instead of** `completeTick` (the region is still `TICKING`, which is exactly the state `abortTick` requires — `WorldRegionizer.abortTick`, 901–912). Record tick duration/timing before aborting so `/folia` keeps an honest last/peak. The kill fires `onRegionDead`, which drops the queue with the existing counted diagnostic, so the remaining tasks are visible, not silent.
3. Call `failurePolicy.onSuccess(region)` after every task that returns normally (per-task streak semantics, matching the policy's own javadoc) and after a successful tick body.
4. Call `failurePolicy.onRegionRemoved(region)` from `RegionScheduler.onRegionDead` (it runs under the structure lock; the policy map is concurrent — no lock-order issue).

**Unit tests:**
* Rewrite `consecutiveFailuresAbortOnlyTheFailingRegion` to enqueue exactly `DEFAULT_MAX_CONSECUTIVE_FAILURES` failing tasks plus one counting task enqueued last; assert (a) the counting task never ran, (b) `bad` is not in `liveRegions()` and `bad.state() == RegionState.DEAD`, (c) the diagnostics contain both `"aborting the region"` and `"died: dropped 1 queued task(s)"`. This is *stronger* than the current assertion, not weaker.
* Strengthen `successResetsTheFailureStreak` to 4 fail / 1 success / 4 fail on one worker and assert no `"aborting"` line (fails today for the 5th cumulative failure once (2) is wired; with (3) it passes).
* New `failurePolicyBookkeepingIsReleasedOnRegionDeath`: after the abort, `diagnosticsLines()` no longer lists the dead region id.

**Runtime test:** enable `stage-entity-ticks`, inject one deterministic failing region task through the API (`RegionScheduler.enqueue`) in a disposable world, and verify `/folia scheduler` reports `splits/merges` unchanged, the region count drops by one, and the log shows the abort plus the drop count; verify neighbours keep 20 TPS via `time query gametime` deltas. [TEST-DESIGN]

**Evidence:** `[CODE]` plus the existing test's assertion gap (lines 82–102 of `RegionFailurePolicyTest`).

---

### D3 — `EntityScheduler.runDelayed` / `runAtFixedRate` silently drop on a schedule-time death race (P1)

**Paths:** `common/.../scheduler/EntitySchedulerImpl.java` — `runDelayed` (72–92, specifically the unchecked call at 87–89), `scheduleRepeating` (106–127), compare `run` (50–70) which *does* check.

**Invariant violated:** I8; `SCHEDULING.md` §"Entity and async scheduling" ("All task submissions must have explicit rejection, cancellation, and shutdown behavior"); the class contract that `retired` fires when the entity "no longer exists".

**Mechanism** [CODE]: `run()` checks `engine.enqueue(...)` and runs `retired` on `false`. `runDelayed` ignores the boolean from `engine.enqueueDelayed(...)`. `RegionScheduler.enqueueDelayed` → `enqueue` returns `false` exactly when the region died between the caller's `ownerOfHandle` lookup and the enqueue re-check under the structure lock (lines 432–465). The delayed task is then neither queued nor retired: it vanishes, and a fixed-rate chain stops forever while its cancel handle still reports not-cancelled.

**Reproduction (deterministic):** resolver returns a live region but kills it as a side effect of `apply` (one-shot `AtomicBoolean`), e.g. `handle -> { if (once.compareAndSet(false,true)) { rz.removeChunk(0,0); rz.tryBeginTick(r); rz.completeTick(r,1);} return r; }`. Then `runDelayed(entity, 5, taskThatMustNotRun, retiredLatch::countDown)`. Today: nothing runs, `retired` never fires (assert with a 1 s await, deterministic because the scheduler is not started).

**Root fix:** capture the boolean; on `false` run `retired` exactly once (documented as "the owner vanished before the task was accepted"). For `runAtFixedRate`, the initial `scheduleRepeating` goes through `runDelayed`, so a failed initial schedule terminates the chain through the same `retired` callback and no reschedule is attempted.

**Residual (must be documented, not hidden):** a body dropped later by the region's DROP policy (region death between ticks) still cannot fire `retired`, because `RegionTaskQueue.dropAll` has no task-level callback. Bounded fix for a later turn: an optional `onDrop` hook on queue entries, owned by the scheduler, not by fabric. Do **not** claim entity-task retirement is complete after this fix.

**Unit test:** `runDelayedFiresRetiredWhenOwnerDiedAtScheduleTime` (above), plus `fixedRateStopsWhenInitialScheduleIsRejected` asserting `retired` ran once and no subsequent task ran after a further 200 ms of a started scheduler.

**Runtime test:** kill a region (`/folia` has no such command; use a scripted world unload) while an entity task chain is armed, then assert the retirement callback counter in `/folia metrics` advanced and no task executed afterwards. [TEST-DESIGN]

**Evidence:** `[CODE]`.

---

### D4 — No execution-time ownership check for position-carrying queued work (P1)

**Paths:** `common/.../scheduler/RegionScheduler.java` — drain loop (679–721), `enqueue` (432–465), `enqueueBatch` (471–515), `onRegionSplit` (327–357); `common/.../scheduler/RegionTaskQueue.java` — `partitionOnSplit` (213–231), `Entry` (48–51).

**Invariant violated:** I6, REGIONS.md invariant 1 + THREADING.md single-writer, and the scheduler's own class doc ("on merge the donor's queue is re-homed … on death … never executed on a context that does not own the state they target"). This is the core-side half of `TEAM_REPORT_RUNTIME.md` **P0-1**.

**Mechanism** [CODE]: `partitionOnSplit` fixes the entries that are already queued *when the split happens* (and the existing test `queuedTasksRedistributeToSplitChildren` proves it). It cannot fix the race the other way round: a producer resolves the owner, the split runs, the producer then enqueues into the now-stale region with a valid position. `runRegionTick` executes every polled entry unconditionally; nothing compares `entry.chunkPos()` against `regionizer.ownerOfChunk(...)` at execution time. Result: a body carrying chunk `(x,z)` runs under the old region's `REGION` context while the child owns that chunk — precisely the cross-region single-writer violation the state machine exists to prevent. Latent in practice (`splits=0` in several recorded runs) which is why it needs a test, not a soak.

**Reproduction (deterministic):** using the existing split geometry (34 chunks, empty 10..23, tick-end split), split first, then `scheduler.enqueue(left, task, RegionScheduler.packChunkPos(29, 0))` where `right` owns chunk 29, then `scheduler.start()`. Today the task runs with `ThreadOwnership.currentRegion() == left`; expected `right`.

**Root fix:** a per-entry net at the top of the drain loop, with a one-entry memo to keep the structure-lock cost at "once per distinct position run" (staged batches are grouped per chunk, so this is normally one lock acquisition per batch):
```
long pos = entry.chunkPos();
if (pos != Entry.NO_POSITION) {
    if (pos != lastPos) { lastPos = pos; lastOwner = regionizer.ownerOfChunk(chunkPosX(pos), chunkPosZ(pos)); }
    if (lastOwner != null && lastOwner != region && lastOwner.state() != DEAD) {
        queuesByRegion.computeIfAbsent(lastOwner, r -> new RegionTaskQueue()).add(entry.task(), pos);
        rehomedAtExecution.incrementAndGet();
        continue;                       // executed by its owner on that region's next tick
    }
    if (lastOwner == null) executedWithoutOwner.incrementAndGet(); // no owner exists at all
}
```
`owner == null` (chunk unloaded mid-flight) keeps today's behaviour — run it, because there is no competing single writer and dropping it would be a new silent loss — but makes it *visible* through a scheduler counter getter (`tasksRehomedAtExecution()`, `tasksExecutedWithoutOwner()`), so operator diagnostics (fabric) can surface it. Do not touch `metrics/RegionMetrics` (out of scope this turn).

**Known limitation to keep documented:** entries with `Entry.NO_POSITION` — packet re-homes, transition bodies, and staged batches built by `RegionStageHub` for unowned positions — cannot be verified here; they need positions supplied at the fabric capture sites (that work is the coordinator's, in a dirty/upstream file). Softer alternative if ping-pong ever appears: an attempt counter on `Entry`; not needed for the first cut because the memo only rehomes to the *current* owner, and re-ownership alternating faster than a region tick is a pathological case that can only delay, not mis-execute.

**Unit test (new file `RegionSchedulerSplitRehomeTest`):** the reproduction above plus
(a) the rehomed task executes exactly once, in `right`'s context;
(b) `tasksRehomedAtExecution() == 1`;
(c) a control entry enqueued into `left` with a `left`-owned position still runs in `left` and does not increment the counter;
(d) an entry with `NO_POSITION` is unaffected (documents the limitation in code).

**Runtime test:** two clusters ~1 section apart, one forced split, then 200 API tasks scheduled against pre-split owner handles; assert zero tasks executed with a non-owner context by capturing `ThreadOwnership.currentRegion()` inside each body, and `tasksRehomedAtExecution()` matches the number of tasks whose position moved. [TEST-DESIGN]

**Evidence:** `[CODE]`; `partitionOnSplit` behaviour is already proven by `RegionSchedulerTest.queuedTasksRedistributeToSplitChildren` (passing), which is why this is the *residual* race and not the whole of P0-1.

---

### D5 — `AsyncScheduler.runOnRegion` hands off without a chunk position (P2)

**Path:** `common/.../scheduler/AsyncSchedulerImpl.java` `runOnRegion` (180–188), specifically `engine.enqueue(region, task)` at 187.

**Invariant violated:** I6 / split partitionability: the method's whole documented purpose is "schedules world-mutating work onto the region owning `(chunkX, chunkZ)`", and it already knows the exact position, yet it produces an entry that `partitionOnSplit` must skip.

**Reproduction (deterministic):** `scheduler` not started, region `r = rz.addChunk(0,0)`, `async.runOnRegion(scheduler, 0, 0, task)`; `scheduler.queueOf(r).drainEntries()` → today `chunkPos() == Entry.NO_POSITION`.

**Root fix:** `engine.enqueue(region, task, RegionScheduler.packChunkPos(chunkX, chunkZ))`.

**Unit test:** the assertion above, plus a split-then-drain variant asserting the entry moved to the child queue (reusing the split geometry).

**Runtime test:** none beyond D4's; covered transitively.

**Evidence:** `[CODE]`.

---

### D6 — Coordinator thread stops silently when the pool rejects (P2)

**Path:** `common/.../scheduler/RegionScheduler.java` — `coordinateLoop` (581–628), submit sites 606 and 617; `close()` (268–292) sets `running=false` **before** `pool.shutdown(5000)`, `WorkerPool.submit` (110–128) throws `RejectedExecutionException` once the pool is shut down.

**Invariant violated:** I8/liveness: a live scheduler must not stop dispatching without a diagnostic. Reachable when a *shared* pool is shut down by its owner (`RegionScheduler(regionizer, sharedPool, …)`, the multi-world constructor at 118–127 documents that closing a shared scheduler leaves the pool running — the converse, pool closed while the scheduler still runs, is unhandled), and in the close() race window.

**Mechanism** [CODE]: the `RejectedExecutionException` propagates out of `coordinateLoop`, the uncaught handler prints a stack trace, the coordinator thread dies, and `running` stays `true` — so `isClosed()` is false, enqueues still succeed, and tasks accumulate in region queues that nothing will ever drain.

**Reproduction (deterministic):** `RegionScheduler shared = new RegionScheduler(rz, pool, 2, OFF, r -> {}, diag::add); shared.start(); pool.shutdown(1000);` — the region created by `addChunk` is due (deadline 0), so the first scan submits and throws. Today: no diagnostic; `shared.queuedRegionTasks()` stays 0 while a task enqueued afterwards never runs.

**Root fix:** wrap both submit sites in `try { … } catch (RejectedExecutionException rejected) { if (running.get()) { running.set(false); diagnostics.accept("Region scheduler dispatch stopped: " + rejected); } return; }`. Suppressing the message when `close()` already began avoids shutdown noise while keeping the shared-pool case loud.

**Unit test:** the reproduction, awaiting the diagnostic with a 5 s latch and then asserting a task enqueued after the pool shutdown does not execute (with a bounded 200 ms observation) — deterministic because dispatch is dead.

**Runtime test:** only a multi-world shutdown path exercises this; scheduled as part of a future engine-restart test. [TEST-DESIGN]

**Evidence:** `[CODE]`.

---

### D7 — Async scheduler discards queued work at close with no count (P2)

**Path:** `common/.../scheduler/AsyncSchedulerImpl.java` `close()` (211–214).

**Invariant violated:** I8. `ThreadPoolExecutor.shutdownNow()` returns the never-commenced tasks and they are dropped on the floor; the timer's pending submissions are dropped too. The pool deliberately rejects on saturation "rather than silently accumulating" (class doc), yet shutdown discards the same work silently.

**Reproduction (deterministic):** `new AsyncSchedulerImpl(1, diag::add)`; submit one task that blocks on a `CountDownLatch` and wait until `activeTasks()==1`; submit 3 more; `close()`; today `diag` is empty.

**Root fix:** capture the `shutdownNow()` result, emit one diagnostic line with the count (and the queue depth at close, for triage), and count timer-scheduled work as discarded only if a cancelled `ScheduledFuture` reported `true`; do not fabricate a count for work that actually ran. No behaviour change beyond visibility (the tasks are already discarded — counting them is the fix, retrying them would need an owner that is shutting down).

**Unit test:** the reproduction, asserting `diag` contains `"discarded 3"`; plus a shutdown-with-empty-queue case asserting no line is emitted.

**Runtime test:** none (covered by `/folia shutdown` log inspection in a later soak). [TEST-DESIGN]

**Evidence:** `[CODE]`.

---

### D8 — `RegionPendingTicks.release` orphans its entry when the owner is gone (P2)

**Path:** `common/.../scheduler/RegionPendingTicks.java` — `release` (168–189), `record` (146–166), lifecycle `onDestroy` (dropping), `pendingCount` (214–224).

**Invariant violated:** ledger accuracy (mandate §21 semantics: "one capture → one release"), and the class's own claim that an empty ledger is a true "no".

**Mechanism** [CODE]: `release` resolves `regionizer.ownerOfChunk(...)`. If the chunk's section was purged between capture and drain (chunk unloaded while a captured tick was still buffered), `owner == null` → `return false` and the increment stays in the ledger forever. `isPending` also answers false for that position (no owner), so the ledger's ghost affects nothing but `pendingCount(region)` diagnostics and one map entry per leaked position. There is no reclaim path: entries are only removed by `release`, merge fold, or region destroy.

**Reproduction (deterministic):** region `r = rz.addChunk(0,0)`; enter `r`'s context and `ledger.record(0,0)`; `rz.removeChunk(0,0)`; `rz.tryBeginTick(r); rz.completeTick(r,1)` → region dies (zero alive sections) → `onDestroy` clears, so the leak needs the *chunk* to die but the *region* to survive: register chunks (0,0) and (40,0) so (40,0) keeps the region alive, record on (0,0), unload (0,0), tick to purge; then `release(0,0)` returns false and `pendingCount(r)` is still 1.

**Root fix:** when the structural owner is null, scan the live ledger entries for the key (rare path, `ConcurrentHashMap` iteration, no regionizer lock) and decrement there; count the fallback in a new `orphanedReleases` diagnostic counter exposed through a getter. If no ledger holds it, keep returning `false` (server-thread-originated tick, nothing owed). This needs one small read-only accessor on `RegionLocalData` (`peekAll()` / iterable of live entries) or an equivalent package-private helper in `RegionPendingTicks` — no change to `RegionLocalData`'s context enforcement.

**Unit test:** `releaseWithoutStructuralOwnerReclaimsTheLedgerEntry` (the reproduction) plus `releaseStillReturnsFalseForServerOriginatedTicks`.

**Runtime test:** a soak that unloads and reloads distance chunks under entity/BE staging, asserting `pendingBlockTicks`/`pendingFluidTicks` in `/folia metrics` return to ~0 at idle. [TEST-DESIGN]

**Evidence:** `[CODE]`.

---

### D9 — Regionizer bookkeeping only grows (P3, memory)

**Paths:** `common/.../region/WorldRegionizer.java` — `haloRelease` (385–400), `removeDeadSections` (925–939), `haloCreate` (365–383), `sections` map (85).

**What happens** [CODE]: `removeChunk` marks a section dead and `haloRelease` clears `alive`, but neither the global `sections` map nor (until recalculation) `region.sections` ever loses an entry. `removeDeadSections` removes from `region.sections` and `sectionOwners` only; the global map keeps the dead section object forever. Halo sections that never become non-empty also live in the global map forever once created.

**Why it is not a correctness bug:** lookups treat a missing entry as "no section" (`ownerOfChunk` returns null), a dead-but-owned section still resolves to its region (invariant 1 for dead sections, as documented), and `haloCreate` revives an existing entry (`alive = true`). Nothing reads a stale entry as authoritative.

**Root fix (deferred, intentionally):** purge a dead section from the global map only in `removeDeadSections`, where the region has already forgotten it, and additionally in `haloRelease` **only** if no region lists it. Removing a region-owned dead section from the global map would change `ownerOfChunk` answers for positions in dead sections (the queue partition and entity split retarget consult it) — that is why this is P3 and why I did not fold it into D4's patch. Needs an explicit test: `deadSectionsArePurgedFromTheGlobalMap` using a new diagnostics accessor (e.g. `sectionCount()`), and `ownerOfChunkOfDeadOwnedSectionStillAnswersUntilPurge`.

**Runtime test:** long walk/tour soak over previously visited geometry with heap sampled before/after; expect a bounded difference. [TEST-DESIGN]

**Evidence:** `[CODE]`.

---

## 4. Examined and held (no defect)

### 4.1 Merge/split task retention
* `onRegionMerged` (295–305) re-homes the donor's queue wholesale into the survivor's queue **before** the hub's data callbacks (listener registration order documented in `RegionDataHub`), so a data-merge handler that enqueues lands in the post-transition registry. Held.
* `onRegionSplit` creates child queues before `partitionOnSplit` runs, so an entry whose owner is a child always finds a queue; an entry whose position now belongs to a *third* region (or to a purged section) stays with the parent, which is the documented last-known-owner behaviour. Held (and made observable by D4).
* `RegionTaskQueue` counters: `add`/`pollEntry`/`removeTask`/`rehomeInto`/`partitionOnSplit`/`dropAll` all move `pendingCount` in the same direction as the queue mutation; `removeTask` decrements only when `ConcurrentLinkedQueue.remove` actually removed (the historical negative-counter bug is fixed and pinned by `queueRemoveTaskPullsBackEnqueuedWork`). Held.
* `dropAll` at death is the documented DROP policy and is counted + logged. Not a silent drop. Held.

### 4.2 Scheduler fairness / backpressure
* EDF by `Region.nextTickDeadlineNanos`; one 1 ms coordinator scan; duplicates collapse on `tryBeginTick` (READY→TICKING latch) and on the deadline re-check. Bounded at "one duplicate submission per region per scan tick". No priority inversion, no unbounded handoff growth beyond the live-region count.
* Backpressure is structural: the handoff queue has no cap, but producers are the coordinator only (≤ one per due region per ms) and the drain is capped at 35 ms / 4096 entries, so a region cannot drain-until-empty and latch TICKING (the historical 28.1 M-entry OOM wedge). Held.
* Delayed re-queue places not-due entries at the tail after the loop, so they cannot be re-polled in the same tick. Under sustained enqueue pressure a due delayed task can wait behind a full 4096-entry window — bounded, reported as latency risk only.
* Fairness note, not a defect: `scheduleNextTick` is `now + 50 ms`, so a region that misses ticks **skips** them rather than catching up (no replay/burst). Delays expressed in `Region.tickCount` therefore track ticks *started*, not server ticks. This is a deliberate staged model (documented in SCHEDULING.md), and the reason `TICK_NANOS` is a constant with no dynamic-rate path.

### 4.3 Merge obligations (TRANSIENT liveness)
`killMergedRegion` does not call `releaseObligationsOn`, so a region waiting on a **merge-donor** keeps a dead target in `mergeLater` and would be stranded TRANSIENT forever. I traced every producer of `mergeLater`:
* `mergeInto(from,to)` adds the obligation only to the **non-ticking** side and only when the other side is TICKING (481–530);
* a TICKING region's `completeTick` step 1 absorbs every waiter in `expectingMergeFrom` (775–789) **before** it can become a plain donor;
* a region that dies empty dies through `killRegion`, which does call `releaseObligationsOn` (583–600) and re-readies an obligation-free TRANSIENT.

So `expectingMergeFrom` of any non-ticking region is empty by construction, and the donor path cannot strand a waiter. Held, but it rests on that argument rather than on an assertion, and the class has no `assert` to defend it. Recommended hardening (not in this patch): assert `from.expectingMergeFrom.isEmpty()` in `mergeNowLocked`/`mergeInto`'s immediate branch, or forward waiters to the absorber defensively. If that assertion ever fires, the invariant argument above is wrong and the fix is a 4-line obligation transfer.

### 4.4 Lock ordering / deadlock
Documented order `structure lock → queue / local data` holds at every checked call site: `RegionTaskQueue` is lock-free; `RegionLocalData` uses a `ConcurrentHashMap` with `computeIfAbsent`; `RegionEntityRegistry` takes **no** stripe lock in structural handlers (documented order: structure → stripes) and stripe holders only touch their own rows. `RegionScheduler.onRegionSplit` calls `regionizer.ownerOfChunk` from inside the listener, i.e. re-enters the reentrant structure lock — legal and intentional. `StageRetry.drain` takes the per-world list lock and then dispatches (which takes the structure lock in `enqueue`); no path takes a list lock while holding the structure lock. No cycle.

The one real deadlock family (worker blocked on deferred server-thread work while the barrier waits for the worker) lives in `fabric` (`ServerThreadDeferral`/`beginTickPhase`) and is `TEAM_REPORT_RUNTIME.md` P0-3; the core's `WorkerPool.closeExecutionAndAwait` is bounded itself and reports the timeout — the unbounded fail-safe is the caller's. Out of scope, unchanged.

### 4.5 Entity / block-entity / scheduled-tick ordering
Core-side, all three slices are `RegionTaskQueue` entries and are drained in enqueue order per region; the intra-tick slice order (ENTITY → BLOCK_ENTITY → SCHEDULED_TICK → PLAYER) is a `RegionStageHub` (fabric) property, not a core one. `RegionPendingTicks.isPending` prefers the caller's own ledger then the structural owner, and is conservatively true at worst (the direction vanilla blocks depend on). Held. D4's net does not reorder entries across slices: a rehomed entry is appended to the owner's queue, so a rehomed ENTITY entry can run after another region's SCHEDULED_TICK entry **within that region's next tick** — acceptable (each region's own slices were already being drained together) but worth recording as a behaviour change if D4 lands.

### 4.6 Shutdown / replay
`RegionScheduler.close()` sets `closed` first (all later enqueues rejected), stops the coordinator, shuts its own pool with a 5 s bound, then drops remaining queues with a count. If the pool timeout expires while a worker still runs, the queue may be dropped under a running drain — bounded, loud, and only on a pool that failed to quiesce in 5 s. `close()` does not detach the regionizer listener; after close a merge can still `computeIfAbsent` an empty queue that nobody drains (enqueues are rejected, so it cannot accumulate work). Noted as residual, no fix proposed (it needs an engine-level lifecycle owner).

---

## 5. Metrics / observability gaps found (no metrics edits allowed)

| Signal | Today | Needed |
| --- | --- | --- |
| Tasks rehomed at execution (D4) | absent | scheduler-local counter + getter (this patch), then a `/folia metrics` line via fabric |
| Tasks executed with no live owner (D4) | absent | same |
| Failure policy aborts actually performed (D2) | log only, and false | count aborts; `onRegionRemoved` cleanup |
| Entity handles per region after split (D1) | count per region only at read time | sum-of-sets vs `trackedCount` reconciliation line |
| Async discard at close (D7) | absent | diagnostic line |
| Ledger orphaned releases (D8) | absent | counter |
| Regionizer section-map size (D9) | absent | `sectionCount()` diagnostics |

---

## 6. Patch queue (ready to apply when the manager assigns)

Ordered, each independent and individually revertible. All files are clean locally and untouched upstream.

| # | Files | Change | Tests |
| --- | --- | --- | --- |
| 1 | `scheduler/RegionEntityRegistry.java` | D1: remove retargeted/retired handles from the previous owner's set | `RegionEntityRegistryTest` +1, +1 strengthened |
| 2 | `scheduler/RegionFailurePolicy.java`, `scheduler/RegionScheduler.java` | D2: return `Action`, abort on `ABORT_REGION` (stop drain, skip body, `abortTick` instead of `completeTick`), per-task `onSuccess`, `onRegionRemoved` on death | `RegionFailurePolicyTest` rewritten +2 |
| 3 | `scheduler/EntitySchedulerImpl.java` | D3: honour `enqueueDelayed`'s result | `RegionSchedulerTest` (or new `EntitySchedulerLifecycleTest`) +2 |
| 4 | `scheduler/RegionScheduler.java` | D4: execution-time ownership net + memo + 2 counters/getters | new `RegionSchedulerSplitRehomeTest` (4 cases) |
| 5 | `scheduler/AsyncSchedulerImpl.java` | D5: positional enqueue; D7: discarded-count diagnostic | `AsyncSchedulerImplTest` +2, +2 |
| 6 | `scheduler/RegionScheduler.java` | D6: catch `RejectedExecutionException`, report once, stop dispatch | new `RegionSchedulerDispatchFailureTest` |
| 7 | `scheduler/RegionPendingTicks.java` (+ one read-only accessor in `RegionLocalData.java`) | D8: reclaim by scan when the structural owner is gone | `RegionPendingTicksTest` +2 |

Not in the patch: D9 (P3, needs the ownerOfChunk-for-dead-sections decision first), the position-less capture sites (fabric), obligation-transfer hardening in §4.3 (assertion only), listener detach on close (§4.6).

Rules the patch commits to: no swallowed exceptions, no weakened assertions, no unbounded waits, no new dependencies, no comment blocks (mandate rule 47 — *why*-comments only where a boundary is non-obvious), deterministic tests (latches + bounded observation, no sleeps as synchronization).

---

## 7. Verification actually performed

| Check | Status |
| --- | --- |
| Source audit of `common/{region,scheduler,thread}` (25 files, 5,874 LOC) | done, `[CODE]` |
| Dirty/upstream overlap analysis for the in-scope paths | done (§0) — no overlap |
| Focused `./gradlew :common:test` | **not run** (coordination hold; no concurrent builds) |
| New tests | designed only, not written |
| Live boot / soak | not run, not applicable (no mixin or engine change) |
| Baseline | `[REC]` TESTING.md 2026-11-16: full suite 239 tests (common 144, fabric 85, api 10), green; not re-measured here |

Because nothing was changed, nothing can regress from this turn. The defect claims in §3 are static-analysis claims with exact line numbers; each has a deterministic reproduction that fails on `94976ef` and is expected to pass only after its patch item lands. Nobody should read this report as evidence that those reproductions were executed.

---

## 8. Hand-off notes for the coordinator

1. This checkout is `94976ef` + user dirty work, 4 behind `origin/main`. My patch queue touches only `common/{region,scheduler}` files that neither the dirty set nor upstream modifies, so it can be replayed onto the reconciled tree as a patch without conflicts.
2. `FREEBUFF.md` §6 needs a line for this turn (audit-only, hold, report path, patch queue pending) — I did not write it because the task forbade it.
3. Priority if only one item can land: **D4** (ownership net) > **D2** (policy actually aborts, and the streak reset that prevents a future spurious abort) > **D1** > **D3** > D5–D8.
4. D2 changes observable behaviour (a repeatedly failing region now really dies and its queue is dropped with a count). Confirm that with the owner before landing; the alternative (log-only, no abort) is defensible but then `RegionFailurePolicy`'s ABORT wording and `diagnosticsLines()` must be corrected instead — a false "aborting" log must not survive either way.
5. Remaining core work beyond this turn: position-less capture sites (fabric), a bounded drop-callback so entity tasks can retire on region death, and the `§4.3` obligation assertion.
