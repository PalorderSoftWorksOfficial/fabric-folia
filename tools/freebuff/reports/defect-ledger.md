# Defect ledger — P0 / P1 / P2

Owner: manager. Last rebuilt: 2026-11-23 (integration review) against `HEAD`
`c2fbe21` + the local dirty work in `/tmp/ff-reconcile`, status hash
`ae97c1adabf5448a`. No commit, push, deploy or sync was performed. The matrix below
is the authoritative status for this snapshot; older per-entry lines are history.

Rule: every entry names a root cause, the regression that pins it and the runtime
evidence that exists. "Fixed" never means "verified live" unless the runtime column
says so.

Evidence levels:

- **E0** — claim from documentation, metadata or a release note; not reproduced.
- **E1** — source read at the cited file/method.
- **E2** — vanilla fact verified with `javap` against the named MC 26.2 jar.
- **E3** — a deterministic automated regression exists and has been observed red on
  the defect and green after the fix.
- **E4** — dedicated-server, gameplay, soak or benchmark evidence at the exact commit.

An entry's evidence level is the highest level actually achieved for that entry, not
the level its author believes.

## 2026-11-23 integration matrix (authoritative for this snapshot)

All agents were idle when this snapshot was taken; two earlier full-suite runs hit
mid-edit states while core and runtime were writing the same tree, so only the run at
status hash `ae97c1adabf5448a` is evidence. No manager Java edit, default change,
merge, commit or sync.

| Item | Status at this snapshot | Evidence | Remaining |
| --- | --- | --- | --- |
| Full suite, one tree | **GREEN** | `bash ./gradlew :common:test :fabric:test :api:test --rerun-tasks` -> exit 0; api 10 + common 167 + fabric 141 = **318**, 0 fail/err/skip | - |
| `build` tail | **GREEN** | `bash ./gradlew build --stacktrace` -> exit 0 at the same status hash | - |
| R1 non-player teleport | **ACCEPTED E3 + final-jar boot; NOT closed** | `EntityTeleportDispatchTest` 14/14 inside fabric 141/0; final-tree jar booted `Done (4.920s)`, 0 mixin errors | disposable-world `/tp` + portal check (E4) |
| R1 R10 residual (`noteServerThread`) | **CLOSED** | production call sites verified: `FabricFoliaMod.startEngine` (SERVER_STARTED), `MinecraftServerMixin` HEAD of `processPacketsAndTick` and `tickChildren` | - |
| R2 disconnect/removal | **OPEN P0** | no implementation | execution-time liveness + reclaim |
| R3 structure lock on Netty | **OPEN P0** | unchanged | non-blocking snapshot/BUSY lookup |
| R4 ownership vs loadedness | **OPEN P0, audit only** | runtime audit: mutation reach is chunk-unbounded (`setBlockAndUpdate` flag 3, `CollectingNeighborUpdater`, plain `blockEvents` set, POI, explosions) | cross-region handoff or measured halo policy |
| R5 deferred-failure swallowing | **FIXED E3** | `ServerThreadDeferral` records and rethrows; both lanes poisoned until taken; `beginTickPhase` surfaces; `ServerThreadDeferralFailureTest` 5/5 | live crash-report injection (E4) |
| R6 positionless split migration | **PARTIAL** | accepted-task drop/retirement fixed (core); player ENTITY/PLAYER bodies pinned to one chunk position; producers still positionless | packet/re-home/command/random-tick positions |
| R10 admission/ordering/pause | **OPEN** | unchanged; only the `noteServerThread` residual is closed | null-owner admission, bounded queues, cross-lane FIFO, pause drain |
| R11 register/retire race | **FIXED E3** | `register` put + set-add under the same per-entity stripe; 8x500 hammer green | hammer is an invariant test, not a fixed schedule |
| D2 abort performed | **PATCHED, signed** | `RegionFailurePolicyTest` 5/5; F3 semantics recorded | - |
| D3 delayed-schedule rejection | **PATCHED** | `EntitySchedulerLifecycleTest` 6/6 | accepted-then-retired loss is the R6 half |
| D4 execution-time ownership net | **PATCHED** | `RegionSchedulerSplitRehomeTest` 9/9 + `RegionSchedulerTest` 11/11 | F1 window not deterministically schedulable |
| D4-F1 merge-window stranding | **FIXED E3** | two-phase `enqueue` + bounded re-resolve (`rehomeToOwner`); no queue for a dead region | exact interleaving not driven |
| F2 merge bookkeeping leak | **FIXED E3** | `onRegionMerged` releases the donor's failure-policy state | - |
| D5-D9 | **OPEN** | unchanged | - |
| L1 `/folia` command surface | **OPEN P1 (release blocker)** | current jar nests only `adventure-api` + `adventure-text-minimessage`; zero `examination` classes in any nested jar; prior boot fails every `/folia` command | nest transitives + boot where `/folia health` answers |
| L2 launcher idle check | **FIXED** | `=session:0.0`, verified | - |
| M1 retired chunk guard | **OPEN (hygiene)** | both files still present; `RegionChunkAccessGuardTest` runs inside fabric 141 | delete pair, expect 138 |
| T1 abort-drop test race | **OPEN (test determinism)** | `RegionFailurePolicyTest.consecutiveFailuresAbortOnlyTheFailingRegion` enqueues after `start()`; one full-suite run failed the `died: dropped` latch (10 s) because `enqueue` removes the survivor when the region dies mid-enqueue; focused runs green | enqueue before `start()` (explicitly legal) |
| ConfigSchema default flip | **UNAPPROVED - recommend REVERT** | see below | owner decision |
| Fabrolia comparison | **BLOCKED (no comparison)** | 3.2.6/3.2.1/3.2.0 all declare `minecraft ~26.3`; project is 26.2. QA additionally booted Fabrolia 3.2.6 **standalone on 26.3** (loader 0.20.1, API 0.166.2+26.3, `Done (6.090s)`, 0 clients, clean stop, 6 cancelled mixins) | a `~26.2` Fabrolia asset or a 26.3 re-baseline |
| 40-client benchmark | **DEFERRED** | 14 GiB host; one scripted 26.2 probe bot now logs in and spawns, but its walk is pinned (position never changes) | >=32 GiB host + benchmark-grade client |

### ConfigSchema default flip - reconciliation (recommend REVERT)

The diff flips `patches.minecraft.entity-tick-optimization`,
`patches.minecraft.chunk-tick-optimization` and
`patches.minecraft.random-tick-optimization` from `TRUE` to `FALSE`, rewrites their
help text, and adds `ConfigSchemaPatchTest.gameplayMutationStagingIsDisabledByDefault`.

Observed facts:

1. `FoliaConfig.load()` calls `ConfigWriter.mergePreservingComments(parsed, schema)`
   and saves. A config **missing** these keys is repaired to the new default, so this
   is not only a first-run default: an upgrade can silently change behavior.
2. Patch state is `regionizedGameplay() && patchEnabled(KEY, false)`
   (`FabricFoliaMod.resolvePatchStates`). With the flip, setting
   `general.regionized-gameplay=true` **alone** enables no entity, block-entity,
   scheduled-tick or random-tick staging; three more keys are required.
3. Live boot of the current jar (`5905e13...`, 26.2) prints
   `Gameplay tick execution: entities=server thread, block entities=server thread,
   scheduled block/fluid ticks=server thread, random ticks=server thread, player
   physics and connection=server thread`.
4. The test that pins the flip was added in the same unapproved diff, so its green is
   not independent approval.
5. The change does not fix R4: a config that already has `true` remains exposed.

Recommendation: **REVERT** the three default flips and their test expectation for this
integration wave, keeping the unrelated correct doc fixes (`diagnostics.metrics` was
already `FALSE` in `ConfigSchema` at HEAD; README toolchain minimums). The safe default
is already `general.regionized-gameplay=false`; the master switch should continue to
mean "enable the staged gameplay set" rather than silently enable nothing. Approving
the flip is defensible only as an explicit owner product decision, and then it needs a
config-version bump / migration note so an upgrade does not flip behavior silently.

### Shared-file conflicts in this wave

| File | Writers | Assessment |
| --- | --- | --- |
| `FabricFoliaEngine.java` | core (`WorkerChunkProbe.vanillaJoins` metric) + runtime (R5 `throwPendingReplayFailure`, barrier pump, metrics) | distinct hunks, no textual conflict; review together because `beginTickPhase` is the R5 surface and the pump is core's quiesce path |
| `RegionStageHub.java`, `LevelEntityTickMixin.java` | core (player position pin, `stagePlayerEntity`/`stagePlayerConnection`) + runtime (`RegionPlayerRouting` calls `stagePlayerConnection`) | interface coupling across scopes; a signature change needs both owners |
| `RegionTransitions.java` | runtime (R1 rewrite) + QA (public transition key, frozen after) | QA edit is necessary and reviewed; no further QA edits |
| `ConfigSchema.java`, `ConfigSchemaPatchTest.java` | config change outside core's scheduler scope | unapproved; kept isolated above |
| `FREEBUFF.md` | manager | mtime changed during the wave; no agent-authored section remains; agents must not append |
| `docs/configuration.md`, `README.md` | manager docs embedding the default flip | revert or approve the narrative with the code |

## 0. Priority decision (binding until evidence changes it)

The five open P0s below are the release-blocking set. **Green unit tests do not
downgrade them**: the existing suite does not boot a server, does not apply mixins
against a real runtime and does not execute a staged body under contention. Each P0
is a case where the pipeline either never runs the vanilla body, runs it on a thread
that does not own the state, blocks network I/O on a topology lock, mutates state
outside its region, or suppresses a failed mutation.

| Priority | ID | One line | Owner | Status 2026-11-22 |
| --- | --- | --- | --- | --- |
| P0 | R1 | Non-player `Entity.teleport` is cancelled twice and never executes | `fabric-folia-runtime` | **ACCEPTED at E3 + final-jar boot, not closed** — 14/14 R1 tests; R10 residual closed; E4 `/tp`/portal check still missing (see 2026-11-23 matrix) |
| P0 | R2 | Player disconnect/removal work runs on a region worker, or a removed player's staged body still ticks | `fabric-folia-runtime` | open, no session active |
| P0 | R3 | Region ownership lookup takes `structureLock` on Netty event loops (plus a second acquisition in `enqueue`) | `core` + `runtime` | open |
| P0 | R4 | Staged bodies mutate state outside their region: the gate proves *loaded*, not *owned*; no block-event/POI/explosion handoff | manager design + `runtime` | open; audit completed this wave (mutation reach is chunk-unbounded), **no mitigation**; blocks any "region ownership safe" claim |
| P0 | R5 | Deferred vanilla-mutation failures are swallowed and the tick continues on partial state | `fabric-folia-runtime` | **FIXED at E3** (5/5 new tests); live crash-report injection (E4) not run; chunk-task absorption in the pump remains |

P1 is the correctness/observability set that must be scheduled behind the P0s but
must not be forgotten when a P0 fix narrows the surface. R6 is called out separately:
**task migration is not complete while positionless work can strand after a split.**

---

## P0 — can hang the server, lose simulation or break single writer

### R1 — Non-player `Entity.teleport` is cancelled twice and never executes

- **Severity:** P0. **Evidence:** E2 + E1; independently re-checked statically by the
  owner in `/tmp` (2026-11-22). **No E3/E4:** `EntityTeleportDispatchTest` exists in
  the worktree (8 tests) but has never run — it is absent from
  `fabric/build/test-results/test/`, whose 122-test XML set predates it. **This header
  is historical: see the R1 implementation outcome at the end of this section.**
- **Files/methods:** `fabric/src/main/java/com/palordersoftworks/fabricfolia/mixin/EntityTeleportMixin.java` (HEAD inject, ~43),
  `fabric/src/main/java/com/palordersoftworks/fabricfolia/engine/RegionTransitions.java` (`dispatch` ~112, `isSameRegion` ~278),
  `common/src/main/java/com/palordersoftworks/fabricfolia/scheduler/RegionEntityRegistry.java` (`ownerOfHandle` ~185).
- **Root cause:** the re-entry gate is registry ownership, which still reports the
  **source** region because the entity has not moved yet. The dispatched body re-enters
  the mixin, `isSameRegion` answers false again, and the `IN_FLIGHT` stamp written by
  the first dispatch is read as "already dispatched", so the second call cancels too.
  Vanilla's teleport body is never executed. Compounding it, `dispatch` writes the
  stamp **before** scheduler/world validation and before `enqueue`; if enqueue returns
  false the stamp survives, so a retry inside the 50 ms window is misclassified as a
  duplicate even though nothing was queued. Above 50 ms the opposite happens and the
  body may re-dispatch or ping-pong. **Third case (owner review, 2026-11-22):**
  `RegionTransitions.enqueueTransition` sends an `owner == null` destination to
  `globalScheduler().run(body)`, but `isCurrentContextOwner` answers false for every
  non-REGION `ThreadContext`. The global-lane body re-enters, sees the live token with
  `target == null`, returns `DUPLICATE_IN_FLIGHT` and cancels again — the same
  never-executes defect on the global lane. **Fourth case (owner review, 2026-11-22):**
  `DispatchKey` is source world + entity id + destination world + chunk X/Z, so two
  intentional teleports to different positions or rotations **inside the same
  destination chunk** collapse into one key and the second transition is lost as a
  duplicate. Identity must be the exact destination (position and rotation) or a unique
  transition token, not the destination chunk. **Fifth case (R1 implementation review,
  2026-11-22):** the in-progress mixin removed the former same-region early return and
  now calls the resolver for every context. The ordinary server thread is
  `ThreadOwnership.UNKNOWN`, so `isCurrentContextOwner` is false even when
  `isSameRegion` is true; a same-region server-thread teleport is then dispatched to a
  worker. That contradicts the class comment that server-thread behaviour is unchanged
  and reorders vanilla work. A same-region server-thread call must run inline. The new
  diagnostic `isSameRegion` call is also an expensive registry + regionizer lookup
  whose result is ignored; do not pay it on the hot path without a counted use.
  **Sixth case (patch review, 2026-11-22):** the attempted fix that returns inline for
  **every** non-REGION context is also unsafe. It runs a cross-region teleport from the
  physical server thread inline (the previous code dispatched when `!sameRegion`) and
  lets NETWORK/ASYNC threads mutate world state inline. Context handling must be a
  per-context policy, not one branch. Baseline note: `isCurrentContextOwner` existed in
  `RegionTransitions` but `dispatch()` never called it; the mixin's only recursion gate
  was `isSameRegion`.
- **Impact:** portals, `/tp`, datapack teleports and mod teleports of non-player
  entities silently do nothing in staged mode. Timing-dependent.
- **Deterministic regression:** extract `RegionTransitions.resolveEntityTransition(...)`
  returning `DISPATCHED | INLINE | DUPLICATE_IN_FLIGHT | DROPPED`, then test:
  (a) source context A, destination B → `DISPATCHED`; (b) call again inside destination
  REGION context → `INLINE` (registry still says A); (c) force enqueue failure →
  `DROPPED`, and a second call inside the window is still `DROPPED`, never
  `DUPLICATE_IN_FLIGHT`; (d) destination has no owner and the current context is the
  global/server lane → `INLINE` on the live token, not a duplicate; (e) two intentional   transitions to different positions/rotations in the **same destination chunk** are
  both dispatched and both run the vanilla body; (f) a same-region teleport called from
  the server thread (`UNKNOWN` context) runs inline in vanilla order and is not
  dispatched to a worker. Global-lane, same-chunk and server-thread fixtures are
  required. `EntityTeleportReentryTest` asserts one vanilla body per transition.
- **Runtime verification:** cow, dropped item and boat through a lit portal plus `/tp`
  of a non-player entity across regions, both directions and cross-dimension; assert
  the position/dimension changes, the transition counter increments exactly once and
  no `IN_FLIGHT` entry persists. **None — not run.**
- **Fix (required shape):** an explicit per-context matrix, not a single branch:
  - **physical server thread:** same-region → **inline**; cross-region → **dispatch**
    to the destination region (never inline a cross-region move); no-owner destination
    goes to the global lane rather than executing inline on the server thread;
  - **REGION:** current context owns the destination → **inline**; otherwise dispatch;
  - **GLOBAL / global lane:** no owner → **inline** on the live token; otherwise
    dispatch;
  - **NETWORK / ASYNC:** always **dispatch** (or hand off to the server thread); never
    mutate world state inline;
  - **UNKNOWN:** treat as non-owner; do not dispatch blindly and do not assume the
    server thread.
  Plus outcome-aware idempotence that never treats a dropped dispatch as in-flight,
  an exact-destination identity, a destination position on the enqueue (R6), a
  liveness re-check instead of a stale target `Region`, and no `Throwable` swallowing
  (R8). `isSameRegion` stays only as a counted diagnostic, not an ignored hot-path
  lookup. **Do not accept the current runtime-impl patch as-is.**
- **Current patch state (final review, 2026-11-22):** the worktree diff replaces the
  50 ms stamp with a `DispatchToken` and a `Decision` enum, and removes the
  `IN_FLIGHT`-before-validation stamp. That fixes the *false duplicate on rejection*
  and adds token retirement on completion, failure and enqueue rejection; the
  dispatch key is still **destination chunk**, not exact position/rotation. Five
  requirements are still unmet, so the patch stays unaccepted:
  1. **Context semantics** — `isCurrentContextOwner` is still REGION-only. A body
     dispatched to the global lane (`owner == null` → `globalScheduler().run`) re-enters
     with a non-REGION context, fails the owner check, finds its own live token and
     returns `DUPLICATE_IN_FLIGHT`; the mixin cancels. The global-lane body still never
     executes. The ordinary server thread (`UNKNOWN`) likewise dispatches instead of
     running a same-region teleport inline.
  2. **Position-aware queueing** — `RegionTransitions` still calls the positionless
     `scheduler.enqueue(owner, body)` (line ~276), so the new entry is invisible to
     `RegionTaskQueue.partitionOnSplit` and to D4's execution-time net (R6).
  3. **Token lifecycle** — a token whose target is `null` (global lane) reports live for
     as long as the scheduler is open, and `prior.isLive` cannot distinguish "body is
     about to run" from "body was stranded"; there is no lease/timeout, and no test
     covers the global-lane token end to end.
  4. **Identity** — two intentional teleports to different positions or rotations in the
     same destination chunk still collapse (`DispatchKey` = entity + destination world
     + chunk X/Z).
  5. **Tests** — `EntityTeleportDispatchTest` covers inline re-entry (same chunk, cross
     region, cross dimension), a different destination, rejection-then-retry, a
     concurrent duplicate and token clearing on completion/failure. It does **not**
     cover same-region server-thread inline order, NETWORK/ASYNC non-inline handoff,
     the global-lane inline rule, exact-position identity, or position-aware queueing.
     The file has never been compiled or run in this tree.
  The mixin also still performs a hot-path `isSameRegion` lookup whose result is
  discarded, and `guardedBody` still catches `Throwable` (R8).
- **Owner:** `fabric-folia-runtime` role (`fabric-folia-runtime-review-r1` was the last
  session; **no runtime session is active now**). Scope: only `EntityTeleportMixin`,
  `RegionTransitions` and related Fabric tests; no overlap with core. Status: **not
  tested, not accepted**; the per-context matrix, global-lane rule, exact-destination
  identity, positioned enqueue and all regression branches remain the assignment.

### R2 — Player disconnect/removal executes on a region worker, or a staged player ticks after removal

- **Severity:** P0. **Evidence:** E2 + E1. No E3/E4.
- **Files/methods:** `ServerConnectionTickMixin` (~61),
  `RegionPlayerRouting.stageConnectionTick` (~180),
  `RegionStageHub.stagePlayerTick` / `reclaimPinnedPlayerBody` (~529–578),
  player capture in `LevelEntityTickMixin`.
- **Root cause:** liveness is checked when the body is *selected*, but the PLAYER body
  executes later. `Connection.tick()` re-checks `isConnected()` at execution and calls
  `handleDisconnection()` → `ServerGamePacketListenerImpl.onDisconnect` →
  `PlayerList.remove` + save + broadcast **on the worker** (E2 for the call chain).
  The mirror case: the connection stage refuses to stage a disconnected player but the
  ENTITY body staged earlier still runs later against a player `PlayerList.remove`
  already removed and saved. Nothing cancels a staged `Runnable` on removal.
- **Impact:** global player-list mutation and player save off the server thread;
  entity/AI code ticked against a retired player. Same class as the 2026-09-25 ticket
  crash, one layer up.
- **Deterministic regression:** hub-level `PlayerConnectionLivenessTest`: stage ENTITY,
  then refuse the PLAYER stage for a liveness reason; assert the entity body is
  reclaimed and runs inline exactly once in non-REGION context, the PLAYER body is not
  enqueued, `pendingBodies == 0`. Second test drives a body whose liveness flips after
  dispatch and asserts the execution-time wrapper defers to `ServerThreadDeferral`
  instead of running `Connection.tick()` on the worker.
- **Runtime verification:** bot join, `/kick`, proxy drop, 10-minute reconnect loop
  with player staging on and STRICT checks; assert no disconnect-path work off the
  server thread and no ghost entity tick after removal (`log entity id + isRemoved`).
  **None — not run.**
- **Fix (required shape):** execution-time liveness re-check that defers disconnect
  handling to the server thread (counted, never silently dropped); reclaim the pinned
  entity body whenever the PLAYER stage refuses for a liveness reason.
- **Owner:** `runtime-r1`. Status: **open**.

### R3 — Region ownership lookup blocks Netty event loops on `structureLock`

- **Severity:** P0 by the owner's priority decision; the audit records it as a
  P1 latency/liveness risk with the blocking path directly visible and a deadlock
  **not** proven. **Evidence:** E1 (source call chain) + E1 for hold-time amplification
  (R11b); no E3/E4. This entry absorbs `tools/freebuff/reports/netty-owner-lookup.md`.
- **Files/methods:** `common/.../region/WorldRegionizer.ownerOfChunk` (~619: `structureLock.lock()` /
  `sectionOwners.get` / `unlock`, plain `HashMap` at ~97);
  packet-path callers `RegionPlayerRouting.isOwnerRegion` (~345–376) and
  `schedulePacketToRegion` (~283–327); `PacketUtilsMixin` (43–55);
  `PacketProcessorMixin` (45–50); `ConnectionFlushQueueMixin` → `NetworkDispatch.runForConnection` /
  `runOnOwner`; `CommandsMixin` entity re-dispatch; **second acquisition** in
  `RegionScheduler.enqueue` phase 2 (`regionizer.underStructureLock(region::isDead)`).
  Hold time is extended by `RegionEntityRegistry.onRegionSplit` calling
  `ownerOfChunk` per entity (R11b) while `structureLock` is held.
- **Root cause:** every ownership read, including reads from Netty event loops, takes
  the global structure lock. Structural transitions (add/remove/split/merge/kill/
  section reclamation) hold it across long scans. The `PacketUtilsMixin` /
  `RegionPlayerRouting` comment claiming a lock-free lookup is false.
- **Impact:** a packet re-home or owner check can block an event loop behind a merge
  or split, stopping read/write for **every** connection assigned to that loop
  (keepalive, latency, read timeouts). Priority inversion: game-structure work blocks
  network I/O.
- **Deterministic regression:** `StructureLockContentionTest` (common): thread A holds
  the structure lock; thread B invokes the packet-path lookup and must receive an
  explicit `BUSY`/fallback result **before** A releases, never park and never guess an
  owner; counter increments once. Companion uncontended test returns the correct
  world-qualified owner. Plus a reader-latency stress test with a baseline p99 taken
  first (`AGENTS.md` §4).
- **Runtime verification:** JFR `jdk.JavaMonitorEnter`/lock events for 60 s while
  forcing add/remove/split/merge at region edges under packet traffic; success metric
  is zero Netty threads blocked in `ownerOfChunk` for > 1 ms with unchanged TPS.
  **None — not run.**
- **Fix (required shape):** publish ownership as a volatile/immutable snapshot (or
  `ConcurrentHashMap`) updated inside the same structural critical sections, with
  `isDead()` filtered at read; make the packet path use a non-blocking lookup that
  answers `BUSY` → leave the packet on vanilla's server-thread queue. Remove the
  second global lock from `RegionScheduler.enqueue` with a per-region add→check→remove
  protocol verified by a deterministic interleaving test. Do **not** `tryLock` and
  guess an owner.
- **Owner:** `core-r1` for `ownerOfChunk` snapshot + `enqueue` ordering;
  `runtime-r1` for caller-side `BUSY` handling. Status: **open**.

### R4 — Staged bodies mutate state outside their region (ownership gate, not loadedness gate)

- **Severity:** P0. **Evidence:** E2 (vanilla structures below) + E1 (gate and absence
  of handoffs). No E3/E4. **Owner directive 2026-11-22:** treat this as a
  foundational ownership blocker; it is **not** fixed by another loaded-neighbour
  check. Do not call region ownership safe until the boundary tests pass.
- **Files/methods:** staging gate in `RegionStageHub` (3×3 "loaded" predicate);
  `LevelEntityTickMixin`, `LevelBlockEntityTickMixin`, `LevelScheduleTickMixin` /
  `LevelTicksTickMixin` bodies; `Level.setBlock` / `setBlockAndUpdate` /
  `updateNeighborsAt`; vanilla `ServerLevel.blockEvent` (plain
  `ObjectLinkedOpenHashSet`, **no mixin or deferral**); `CollectingNeighborUpdater`
  (server-thread-owned depth queue); `updatePOIOnBlockStateChange` → `PoiManager`
  (plain storage map read without `loadLock`); explosion paths (not intercepted).
- **Root cause:** the predicate proves the body's 3×3 halo is **loaded**, never that
  the executing region **owns** every chunk/block the body may mutate. There is no
  execution-time ownership comparison and no cross-region handoff for block writes,
  block events, neighbour updates, POI writes or explosions.
- **Impact:** concurrent mutation of non-concurrent vanilla structures from a worker
  (same shape as the 2026-09-25 fastutil crash), cross-region block/entity mutation
  and lost/duplicated side effects when the adjacent cell ticks concurrently. Affects
  redstone, chest/bell/note-block/piston/sensor paths, villager/piglin POI work and
  any mod that assumes `level.setBlock` is server-thread-only.
- **Deterministic regression (all required):**
  1. `RegionStageHubOwnershipGateTest` — a body whose halo is loaded but whose target
     block/chunk is owned by a different live region must not execute on the staged
     worker; it is bounced or handed off with a counter, and runs exactly once.
  2. `BlockEventOwnershipBoundaryTest` — an entity at an 8×8-cell edge writes a
     neighbour block, emits a block event and triggers POI work while the adjacent
     cell ticks concurrently; assert ordering, single-writer and no
     concurrent-modification, with the event replayed on the server thread.
  3. `ExplosionOwnershipBoundaryTest` — an explosion started in a staged body at a
     cell edge is either handed off or counted as a STRICT violation, never silently
     applied cross-region.
- **Runtime verification:** STRICT mode, disposable world: chest/bell/note block/
  piston/observer clock exactly on a region border with a second workload in the
  adjacent cell; villager POI workload; explosion at the boundary; zero cross-region
  writes, zero concurrent-modification reports, counters match the injected cases.
  **None — not run.**
- **Fix (required shape, one of two):**
  (a) prove and enforce a **single-owner halo** before staging (region merging already
  unifies adjacent sections, so the gate can require ownership of every section the
  body can touch), **or** (b) introduce explicit cross-region write/event/POI/
  explosion handoff with defined ordering, failure semantics and metrics. A loaded-only
  re-check is explicitly rejected. Until one exists, block-mutating staged bodies
  remain an ownership violation, not a parity gap.
- **Owner:** design decision by manager; implementation `runtime-r1` with `core-r1`
  for the ownership query/epoch. Status: **open; blocks any "region ownership safe"
  claim.**

### R5 — Deferred vanilla-mutation failures are swallowed and the tick continues

- **Status 2026-11-23: FIXED at E3** — `runDeferred` records the failure and rethrows it;
  both lanes stop until `takeReplayFailure()`; `beginTickPhase` surfaces a failure the
  pump swallowed; `ServerThreadDeferralFailureTest` 5/5. A live crash-report injection
  (E4) is still not run, and `WorkerPool` / `pumpChunkExecutors` still absorb chunk-task
  failures (counted, not propagated).
- **Severity:** P0 by the owner's no-suppression rule (previously recorded as P1-3).
  **Evidence:** E1 (history) + E3 (fix).
- **Files/methods:** `fabric/.../engine/ServerThreadDeferral.java` (`runDeferred`,
  `drainAll`, `drainBarrierSafe`), plus the per-task catch in
  `FabricFoliaEngine.pumpChunkExecutors` (`drainChunkExecutorQueue`).
- **Root cause:** `runDeferred` catches every non-fatal `Throwable`, increments
  `FAILED`, logs and returns false; the drain continues. A deferred ticket mutation,
  `ServerChunkCache.move` or player move that throws after partially mutating state
  leaves partial world state and the tick proceeds. Vanilla would have raised a
  `ReportedException` and produced a crash report. The pump's per-task catch has the
  same shape.
- **Impact:** silent divergence from vanilla with only an aggregate counter; the
  server keeps ticking on torn state.
- **Deterministic regression:** `ServerThreadDeferralFailureTest`: a deferred mutation
  throws → the tick fails with the original throwable (or a wrapped error naming the
  operation and context), `FAILED` is counted, and later deferred work from the same
  tick does not run. A partial-mutation fixture proves no silent continue. Isolation
  stays only for a specifically classified transient (the CME shape `StageRetry`
  already handles).
- **Runtime verification:** inject a failing deferred mutation on a disposable server
  with staging on; assert the server stops with a crash report naming the operation
  and no partial state is saved. **None — not run.**
- **Owner:** `runtime-r1`; `ServerThreadDeferral.java` is locally dirty and needs an
  explicit written handoff. Status: **open**.

**R1 implementation outcome (2026-11-22 live-validation turn).** Runtime rewrote the
candidate (`EntityTeleportMixin`, `RegionTransitions`, `EntityTeleportDispatchTest`) and
filed `tools/freebuff/reports/runtime-implementation.md`. Manager re-verified the
hashes and the XML: `EntityTeleportDispatchTest` **14/14**, full fabric **136/136**, 0
failures/errors/skips. The design is now an exact-match invocation token (entity +
world + destination chunk, thread-confined for the body), a per-context matrix, and
`scheduleToChunk` (position-aware) with a `ServerThreadDeferral` safe continuation when
enqueue is refused. A first revision short-circuited on any nested transition; manager
rejected it, runtime added `TransitionToken.matches` and
`nestedTransitionToAnotherEntityAndDestinationIsNotInline`. **Verdict: ACCEPT at E3**,
not closed: run 6 booted the final-tree jar (`06e8520…`, built 02:52:26) with `Done
(4.920s)` and zero mixin errors, so mixin application is proven, but there is still no
`/tp`/portal functional check and R10's `noteServerThread` wiring is absent, so a
foreign `UNKNOWN` thread can take the server-thread same-region inline fast path. The
three benchmark runs used the older 02:28:55 jar and do not validate R1. The new code also carries javadoc/comments
despite the assignment's no-comments rule; recorded, not a correctness blocker.

---

## P1 — real defects with contained blast radius

### R6 — Positionless queued work is stranded by a split (task migration is incomplete)

- **Severity:** P1. **Evidence:** E1; `RegionTaskQueue.partitionOnSplit` skips
  `Entry.NO_POSITION`. No E3 for the producers, no E4.
- **Files/methods:** `RegionTaskQueue.partitionOnSplit` (~213–231); producers using the
  positionless `RegionScheduler.enqueue(Region, Runnable)`:
  `RegionPlayerRouting.schedulePacketToRegion` (~326),
  `RegionTransitions.dispatch`/`dispatchKeyed` (~154/211) and
  `enqueueTransition` (the null-owner global-lane path), `CommandsMixin` (~124),
  `RegionTickInterceptor.flushPass` (~236), `NetworkDispatch.runOnOwner`/`runForConnection`.
  `enqueueTransition` is specifically **not** re-homed by D4 after a split because it
  carries no position; destination chunk metadata or an explicit retirement rule is
  required before this producer can be considered safe.
- **Root cause:** these producers have (or can derive) a chunk position but enqueue
  without one, so the split partition cannot move them; they execute later under the
  parent's REGION context although the chunk belongs to a child. `StagedBatch` is the
  one path that is correct because it carries a position and re-resolves the owner.
- **Impact:** packet handlers, teleport bodies, command re-dispatch and random-tick
  batches can run in the wrong region after a split — a single-writer violation.
- **Deterministic regression:** extend `RegionSplitRehomeTest` to each producer shape:
  enqueue before a split that moves the chunk, assert the body executes with the child
  as current region and exactly once. Today only the position-bearing D4 case passes.
- **Runtime verification:** forceload add/remove at region edges under packet traffic;
  assert zero bodies execute with a non-owner context and the re-home counter matches.
  **None — not run.**
- **Fix (required shape):** supply the position at every producer that has one, and add
  an execution-time owner comparison for those entries. Positionless-by-nature work
  (e.g. a body with no chunk binding) needs a **separate explicit policy**
  (pin-to-parent with justification, or a lease/epoch), not silent last-known-owner.
- **Owner:** `runtime-r1` for capture sites; `core-r1` for entry/queue policy. Status:
  **open. D4 alone does not close this, and task migration must not be called
  complete.**

### R7 — Context restore leaks on exceptional exit and nested calls corrupt the token stack

- **Severity:** P1. **Evidence:** E1.
- **Files/methods:** `ServerLevelTickMixin` (HEAD sets drain flag, RETURN clears),
  `ConnectionPacketDispatchMixin` (HEAD push NETWORK, RETURN restore), `CommandsMixin`
  (conditional GLOBAL push, unconditional `restoreConsoleContext` `pollLast`).
- **Root cause:** context is restored only at `RETURN`; a throw leaks the context
  (Netty loop stays NETWORK, server thread keeps the staging drain flag). Nested
  `runOnceConnected` / nested `performCommand` pops the outer token because the inner
  call did not push an identity token.
- **Impact:** wrong context for later work on the same thread; a nested command can
  exit GLOBAL early and the outer body runs as UNKNOWN.
- **Deterministic regression:** `ContextTokenRestoreTest` on `ScheduledTickDeferral`
  and `ThreadOwnership`: push/push/pop/pop and push/throw/pop restore the original
  context; `nestedCommandRestoresOuterToken`.
- **Runtime verification:** STRICT mode with a throwing packet handler and a nested
  command; assert the event loop returns to its prior context (thread dump/diagnostic).
  **None — not run.**
- **Owner:** `runtime-r1`. Status: **open** (mixins listed as handoff-safe).

### R8 — `RegionTransitions.guarded*` swallows fatal errors and does not count failures

- **Severity:** P1. **Evidence:** E1.
- **Files/methods:** `RegionTransitions.guarded(...)` / `guardedKeyed(...)`.
- **Root cause:** catches `Throwable`, including `VirtualMachineError` and
  `LinkageError`, logs and returns; failed transitions are not counted and fatal errors
  do not propagate.
- **Impact:** a fatal JVM/linkage error inside a transition is converted into a lost
  transition; operators see a log line, not a metric or a stop.
- **Deterministic regression:** `TransitionFailureTest`: `OutOfMemoryError` /
  `NoClassDefFoundError` thrown inside the guarded body propagates; a normal failure
  increments a transition-failure counter and the dispatch outcome is `DROPPED`.
- **Runtime verification:** inject a failing transition in a disposable run; assert
  fail-stop and a counter, not a silent continue. **None — not run.**
- **Owner:** `runtime-r1` (same file as R1, so land together or sequence them).
  Status: **open**.

### R9 — Worker `hasScheduledTick` falls through to a non-concurrent vanilla map

- **Severity:** P1. **Evidence:** E1 + E2 for the container types.
- **Files/methods:** `LevelTicksQueryMixin.hasScheduledTick` → vanilla
  `LevelTicks.hasScheduledTick` reading `allContainers` (non-concurrent fastutil map).
- **Root cause:** when the ledger says "no", the mixin falls through to vanilla, which
  reads a map the server thread inserts/removes concurrently. This is a data race, not
  merely a conservative answer.
- **Impact:** corruption/undefined read in redstone/observer-heavy workloads; the
  class comment's "safe over-approximation" claim is wrong for this fall-through.
- **Deterministic regression:** `TickQueryLedgerTest`: with a ledger present, a worker
  answer comes from the ledger only (record → true, else false) and never enters the
  vanilla body (strict-mode assertion/fixture); a concurrent insert may cause a
  duplicate schedule that vanilla dedups.
- **Runtime verification:** scheduled-tick farm + observer clock with staging on and
  STRICT; assert no worker reads vanilla `allContainers` and no duplicate execution.
  **None — not run.**
- **Owner:** `runtime-r1`. Status: **open**.

### R10 — `ServerThreadDeferral` admission, ordering and pause-window defects

- **Status 2026-11-23:** the `noteServerThread` residual is **CLOSED** — production
  call sites exist in `FabricFoliaMod.startEngine` (SERVER_STARTED) and at the HEAD of
  both `MinecraftServerMixin` handlers. Items (a) null-owner admission, (b) bounded
  queues, (c) pause drain and (d) cross-lane FIFO remain open.
- **Severity:** P1. **Evidence:** E1.
- **Files/methods:** `ServerThreadDeferral.isServerThread`, `defer`, `drainAll`,
  `drainBarrierSafe`, plus the `tickChildren` HEAD and `beginTickPhase` anchors.
- **Root cause:** (a) `isServerThread()` answers true when no owner is recorded, so
  any thread before the first tick executes ticket/chunk mutators inline; (b) `PENDING`
  and `BARRIER_SAFE` are unbounded with no admission or overflow policy; (c) the
  empty-server pause branch returns before `tickChildren`, so `PENDING` never drains
  while paused; (d) `drainAll` drains `PENDING` before `BARRIER_SAFE`, so cross-queue
  FIFO is not preserved (a later `removeTicket` can replay before an earlier
  barrier-safe `addTicketAndLoadWithRadius`).
- **Impact:** startup-time off-thread mutation, unbounded queue growth under a worker
  loop, lost ordering across queues, work accumulating during idle pause.
- **Deterministic regression:** `ServerThreadDeferralAdmissionTest`: null owner is
  "not the server thread"; a bounded admission policy returns a rejection the caller
  must handle (never a silent drop) with a counter; one logical FIFO across the
  barrier-safe lane; a pause-path drain is exercised through `beginTickPhase`.
- **Runtime verification:** boot with a foreign-thread ticket mutation probe; paused
  empty server with a deferred producer; assert the queue does not grow and the drain
  order is FIFO. **None — not run.**
- **Owner:** `runtime-r1`; explicit handoff required (locally dirty file). Status:
  **open**.

### R11 — `RegionEntityRegistry.register` is not stripe-locked; structural scans hold the lock

- **Status 2026-11-23: FIXED at E3** — `register` now performs put + set-add in one
  per-entity stripe critical section; `RegionEntityRegistryTest` 8x500 hammer green.
  The hammer is an invariant test, not a fixed schedule. R11b/R3 (structure-lock hold
  time in `onRegionSplit`) remains open.
- **Severity:** P1. **Evidence:** E1 (history) + E3 (fix).
- **Files/methods:** `RegionEntityRegistry.register` (~171) vs `unregister` (~228) /
  `migrate` (~196); `onRegionMerged` / `onRegionSplit` / `onRegionDead` (~280-345).
- **Root cause:** `register` does `putIfAbsent` and then adds to the per-region set
  outside the stripe lock. An unregister between the two leaves the handle in a
  per-region set with no authoritative mapping (strong reference, never retired,
  never migrated). `migrate` can also lose the add against a concurrent `register`.
  **Review note (2026-11-22):** the current D1 patch removes the handle from the
  parent set on split and from the previous set in `retire`, but it does **not** put
  `register` under the same per-entity stripe/atomic protocol. `register` versus
  `retire` can still leave an orphan set entry, so **R11 stays open** until a separate
  race fix and deterministic test exist.
  Separately, merge/split/death iterate the whole world map under `structureLock`, and
  `onRegionSplit` calls `ownerOfChunk` per entity — the dominant structure-lock hold
  time feeding R3 (61,425 entities recorded in one world).
- **Impact:** leaked entity handles and a per-region set that diverges from the
  authoritative map; long structure-lock stalls for every reader.
- **Deterministic regression:** concurrent add/remove hammer (8 threads × 5k entities,
  half removed during add) asserting `trackedCount == Σ per-region set sizes` and zero
  leaked handles after `close()`; a merge visits only the donor's handles; a split of
  two regions asserts `ownerOfChunk` calls == 0 (or ≤ children) and an exact map.
- **Runtime verification:** `/folia entities` versus a vanilla selector census over
  10 minutes with spawning/killing; abort on drift. JFR lock events during a forced
  split/merge on a 10k-entity world (p99 hold time). **None — not run.**
- **Owner:** `core-r1`. Status: **open** (not in the first patch).

### R12 — Deferred ticket/player-tracking mixins change vanilla return contracts optimistically

- **Severity:** P1. **Evidence:** E1 + E2 (targets present).
- **Files/methods:** `TicketStorageMixin` (`addTicket(long,Ticket)`,
  `removeTicket(long,Ticket)`, `updateChunkForced`), `DistanceManagerTrackingMixin.removePlayer`.
- **Root cause:** deferred ticket mutators answer `true` (as if applied) before the
  server thread replays them; `removePlayer` cancels when `playersPerChunk` has no
  entry. Return values and side-effect timing therefore differ from vanilla with no
  regression pinning the contract.
- **Impact:** callers that branch on the return value observe a state that does not
  exist yet; a legitimate removal can be skipped.
- **Deterministic regression:** `TicketDeferralSemanticsTest`: deferred add/remove
  return values match the documented deferred policy and a later replay produces
  exactly vanilla's final state; `removePlayer` on an absent player set is a no-op
  without cancelling unrelated work.
- **Runtime verification:** chunk force-load/unload cycles with tickets created from a
  staged body; compare final ticket state and forced-chunk set to a vanilla baseline.
  **None — not run.**
- **Owner:** `runtime-r1`. Status: **open**.

### R13 — Random-tick pass has uncounted drop/bounce paths

- **Severity:** P1. **Evidence:** E1 (upstream `RegionTickInterceptor` + mixin).
- **Files/methods:** `RegionTickInterceptor.flushPass` (~236),
  `ServerChunkCacheTickMixin`; `getServer().execute(...)` stale-edge bounce.
- **Root cause:** the edge-not-loaded branch re-executes on the server thread via
  `getServer().execute(...)` (a **bounce**, mislabelled a drop), the back-pressure skip
  uses a local counter, and a dead-region early return is uncounted. The new
  `RANDOM_TICK_CHUNKS_EXECUTED` counter only counts executions. The bounce also runs in
  the inter-tick window, which contradicts the stated rule that staged bounces go
  through `ServerThreadDeferral`.
- **Impact:** operators cannot reconcile offered vs executed chunks; the bounce can
  run concurrently with other workers.
- **Deterministic regression:** `RegionTickInterceptorAccountingTest` covering the
  three paths with named counters (`randomTickBouncedEdge`,
  `randomTickSkippedBackpressure`, `randomTickDroppedDeadRegion`) and asserting the
  bounce uses the deferral ledger.
- **Runtime verification:** random-tick workload at chunk edges with staging on;
  assert offered = executed + bounced + skipped + dropped and zero inter-tick
  concurrent server-thread chunk ticks. **None — not run.**
- **Owner:** `runtime-r1` (layer on upstream `RegionTickInterceptor`). Status: **open**.

---

## P2 — observability, documentation and bounded cost

### R14 — Uncounted/unbalanced diagnostics

- **Severity:** P2. **Evidence:** E1.
- **Files/methods:** `FabricFoliaEngine.pumpChunkExecutors` (`barrierDrained` not bumped
  by `drainAll`), `ScheduledTickDeferral.deactivate()` (clears pending with no counter),
  `RegionStageHub` `PLAYER_TICK_TARGETS` (cleanup only at flush/deactivate), staged-body
  totals over-count (recorded).
- **Root cause:** several paths mutate or discard state without a matching counter, so
  `/folia metrics` cannot be reconciled.
- **Regression:** `MetricsAccountingTest` asserting each path's counter balances the
  work it consumed, including `droppedAtShutdown` for scheduled ticks.
- **Runtime verification:** `/folia metrics` reconciliation across a restart.
  **None.**
- **Owner:** `runtime-r1`. Status: **open**.

### R15 — Stale runtime documentation and leftovers

- **Severity:** P2. **Evidence:** E1 (doc/code mismatch).
- **Files/methods:** "lock-free `ownerOfChunk`" claim in `RegionPlayerRouting` /
  `PacketUtilsMixin` (false — see R3); dedicated `FabricFolia-Global` thread references
  (thread removed); mixin counts in docs (register is 29 declared classes, **not**
  proof any applied); `LevelEntityTickMixin` formatting. The stray
  `System.out.println` in `PlayerSliceAtomicityTest` was removed during reconciliation.
- **Root cause:** documentation drifted from the code; no doc check pins counts.
- **Regression:** none (documentation). A boot-time mixin audit remains the only proof
  of application.
- **Runtime verification:** mixin export/apply audit on the exact jar. **None.**
- **Owner:** manager (docs) + `runtime-r1` (comments). Status: **partially fixed in the
  reconciliation tree; R15 remains open until a mixin audit runs.**

---

## Previously reported P0s — fixed in the reconciled tree, unit evidence only

These were the three pre-existing P0s. They are **not** live-verified, and the new P0s
above are not downgraded because these pass.

| ID | Item | Fix | Regression | Runtime |
| --- | --- | --- | --- | --- |
| P0-H1 | Deferred ticket-load bridge deadlocked the tick barrier | `deferBarrierSafe` + pump-side `drainBarrierSafe` | `DeferredBarrierDeadlockTest` (7) | none on merged tree |
| P0-H2 | Player ENTITY and PLAYER slices could tick in different regions | per-player per-tick world-qualified pin + reclaim on world change | `PlayerSliceAtomicityTest` (5) | none |
| P0-H3 | Worker `getChunk` on an absent non-creating chunk parked forever | `WorkerChunkProbe`: absent + `create=false` → vanilla `null` inline; `create=true`/below-status keep the join, counted | `WorkerChunkProbeTest` (5) | upstream reports TPS 20.00; not reproduced here |

`P0-H3`'s residual is P1-M2 below: a creating/upgrading join is still a worker blocking
inside the barrier.

## P1/P2 manager and QA findings carried forward

- **P1-M1 — Backlog gauge can be zeroed while bodies are in flight.** `RegionStageHub.flushStaged`
  resyncs `pendingBodies` from two sampled reads (queue sum + `busyCount`), and per-batch
  `finally` clamps at 0. The gate can under-read, so staging is not suspended when it
  should be. Evidence: E1; `PlayerSliceAtomicityTest` observation repaired (bounded
  await, invariant unchanged). Fix direction: per-batch completion accounting. No runtime.
- **P1-M2 — A creating/status-upgrading `getChunk` still blocks a worker inside the barrier.**
  `WorkerChunkProbe.decide` keeps vanilla's join for `create=true` and below-status; the
  pump drains `pendingRunnables` but a chunk that must generate needs later ticks the
  barrier holds. Observable as `worker getChunk creating/below-status vanilla joins`.
  Fix direction: bounce the body or make the pump satisfy the specific future. No
  deterministic test yet.
- **P1-M3 — Cross-dimension player teleport mutates global `PlayerList` on a destination worker.**
  `ServerPlayerTeleportMixin` cross-world branch calls `sendPlayerPermissionLevel`,
  `removePlayerImmediately`, `sendLevelInfo`, `addDuringTeleport` in the destination
  context. Documented design risk, not a proof. No runtime.
- **P1-M4 — Packet processor self-requeue for container mutations.** Fixed in the local
  work (`canHandlePacketOnCurrentThread` accepts the real processor thread);
  `PlayerRespawnRoutingTest` green. No client-side inventory test after deployment.
- **P1-M5 — Packet latency re-home has no fairness/latency metric** (`rehomes region=N`
  only) and can execute a tick later than vanilla's server-thread queue. E1.
- **M1 — The retired chunk guard is still compiled and tested.**
  `fabric/src/main/java/.../engine/RegionChunkAccessGuard.java` and
  `fabric/src/test/java/.../engine/RegionChunkAccessGuardTest.java` are untracked but
  present. `reconciliation.md` says they were deleted; they were not. Nothing in
  production references the class (grep), yet its test asserts the rejected
  `create=true` behaviour that `WorkerChunkProbe` deliberately replaced — two
  contradictory chunk-lookup policies in one tree, and a green test for the retired
  one. Its JUnit XML is from 01:33, before the reconciliation report was written, so a
  fresh `:fabric:test` would compile and run the obsolete assertions. **Fix:** delete
  both files, then rerun `:fabric:test` and confirm the suite count drops by 3. This
  is handoff-recorded, not applied (no production edits this turn).
- **L1 (P1, found by the live boot) — the entire `/folia` command surface fails at
  runtime.** Boot evidence: `Error executing: folia metrics
  (java.lang.NoClassDefFoundError: net/kyori/examination/Examinable)` and
  `Error executing: folia health/workers/regions/scheduler (... Could not initialize
  class com.palordersoftworks.fabricfolia.command.FoliaMessages)` — 134 failing command
  responses in one run, identical in three runs. **Root cause verified:**
  `fabric/build.gradle.kts` nests only `adventure-api` and
  `adventure-text-minimessage`; `adventure-api-4.23.0.pom` declares compile/runtime
  deps on `adventure-key`, `examination-api` and `examination-string`, none of which is
  nested (`unzip -l` finds zero `examination` entries), so `FoliaMessages` static init
  fails. Effect: MSPT, region, worker, queue and health metrics are all unavailable;
  no performance claim can use them. **Regression required:** jar-content assertion
  for the Adventure transitive set plus a boot test where `/folia health` answers over
  RCON. Evidence paths in `tools/freebuff/reports/live-runtime-and-benchmark.md` §6.
  **Owner:** runtime/core (build + command surface). Status: **open**.
- **L2 (tooling, fixed this turn) — launcher idle detection was dead.**
  `launch-team.sh:session_idle` used `capture-pane -t "=session"`, which tmux 3.6
  rejects for a pane target, so every `--send`/`--resume` refused without `--force`.
  Fixed to `"=session:0.0"` and verified: idle core accepted, busy qa refused, no tmux
  action in dry-run.
- **T1 (test determinism, found 2026-11-23) — `RegionFailurePolicyTest.consecutiveFailuresAbortOnlyTheFailingRegion`
  is racy.** The test calls `scheduler.start()` and then enqueues five failing tasks plus
  a survivor. Under load the worker can abort the region during the enqueue loop; the
  survivor's `enqueue` then hits the post-add dead check and removes it, so
  `onRegionDead` legitimately reports `dropped=0` and the `died: dropped` latch times
  out (observed: 1 full-suite failure at `RegionFailurePolicyTest.java:114`, `time
  10.014`; focused and later full runs green). Not a product bug — rejecting a task
  enqueued into an already-dead region is correct. Fix: enqueue all tasks **before**
  `scheduler.start()` (the enqueue-before-start path is explicitly legal,
  `RegionScheduler` ~449) so the drain owns the abort. Not applied by the manager.
- **QA-101 — No boot or mixin-application gate in CI; `:fabric:verifyServerGui` points at
  a deleted `compat/` script.** QA-102 — no runtime QA harness (now partially built
  under `tools/freebuff/qa/`, see the live evidence section). QA-103 — the last
  full-suite evidence predates the tree. Full list in `tools/freebuff/reports/qa.md`.

## Core audit defects (D1–D9) — status at 2026-11-23

| ID | Sev | Item | Regression | Owner | Status |
| --- | --- | --- | --- | --- | --- |
| D1 | P1 | Split leaves entity handles in the parent's per-region set; retired handles linger | `RegionEntityRegistryTest` (11) | core | **patched**: split removes retargeted handles from the parent set; `retire` removes from the previous set; `register` is now under the stripe (R11 fixed at E3). Unit evidence only. |
| D2 | P1 | `ABORT_REGION` decided/logged but never performed; failure streak never resets | `RegionFailurePolicyTest` (5) | core | **patched and manager-signed-off**: returns the action, breaks the drain, `abortTick` replaces `completeTick`, streak resets. Semantics are F3 above; F2 is fixed at E3. |
| D3 | P1 | `EntityScheduler.runDelayed`/`runAtFixedRate` drop silently when the owner died at schedule time | `EntitySchedulerLifecycleTest` (6) | core | **patched**: rejected `enqueueDelayed` runs `retired`; the accepted-then-dropped case now has an `onDropped` hook + entity re-proof (R6 drop/retirement half fixed at E3). |
| D4 | P1 | Position-bearing tasks had no execution-time ownership check | `RegionSchedulerSplitRehomeTest` (9) | core | **patched; D4-F1 and F2 fixed at E3**; R6 producer half still open |
| D5 | P2 | `AsyncScheduler.runOnRegion` enqueues without a position | entry-position assertion | core | open |
| D6 | P2 | Scheduler coordinator dies silently on rejected submit | `RegionSchedulerDispatchFailureTest` | core | open |
| D7 | P2 | `AsyncSchedulerImpl.close()` discards queued work with no count | discarded-count assertion | core | open |
| D8 | P2 | `RegionPendingTicks.release` orphans its entry when the owner vanished | `releaseWithoutStructuralOwnerReclaimsTheLedgerEntry` | core | open |
| D9 | P3 | Regionizer global section map never shrinks | `deadSectionsArePurgedFromTheGlobalMap` | core | open (deferred) |

**D4 review note (core review report, 2026-11-22):** the execution-time net is
correct for position-bearing entries and its seven tests cover re-home, own-region,
no-position, no-owner, ordering and no-double-run. It does **not** cover
`Entry.NO_POSITION` producers (R6), so **task migration is not complete**. The core
review adds one new defect and three decisions:

- **D4-F1 (P1, new): a re-home can strand a task in a merge window.** The drain
  resolves the owner under the structure lock, then `queuesByRegion.get` /
  `computeIfAbsent` + `add`. If that owner merges in between, `onRegionMerged` has
  already removed and re-homed the donor queue, so the add creates a fresh queue for
  a dead donor that nothing drains. Silent loss until close. Required fix shape:
  route the re-home through `enqueue(owner, task, pos)` (whose phase 2 pulls a stale
  copy back out), and on `false` re-resolve once and retry before the counted local
  fallback. **Fixed 2026-11-23 at E3** via `rehomeToOwner` (two-phase `enqueue`, bounded
  re-resolve `REHOME_ATTEMPTS = 4`, no queue for a dead region). The exact merge
  interleaving is still not deterministically schedulable; the regression drives the
  extracted decision with a stale owner.
- **F2 (P1 residual): `onRegionRemoved` is wired for death only, not merges**, so the
  failure-policy map grows by one entry per merged donor (ids are never reused).
  One-line addition in `onRegionMerged`; required, not optional.
- **F3 (accepted semantics):** with `onSuccess` after every successful task and tick
  body, a region that fails exactly one queued task per tick never aborts; the
  threshold now only accumulates within one drain or through a repeatedly failing
  tick body. Accepted as the intended meaning. Do not describe it as "a region that
  fails every tick always dies".
- **F4 (accepted):** the 8-arg `RegionScheduler` constructor is deliberately
  package-private for the new test's policy injection; no production caller changes.
  The javadoc line-sharing (`*/` + constructor on one line) is **pre-existing at
  HEAD**, not introduced by the patch. Accepted; cosmetic only.
- **F5 (accepted limitation):** a re-homed entry does not mark the target region due,
  so it waits for that region's next scheduled tick. Latency only.
- **F6 (pre-existing):** `DelayedRegionTask.targetTick()` is a per-region counter, so
  a delayed entry re-homed across a split is interpreted against the child's counter.

## Focused common test evidence — 25 tests (2026-11-22)

This is the only test evidence produced by the core patch turn. It is **separate from
the 280-test reconciliation baseline** and does not replace it.

| Class | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| `RegionSchedulerSplitRehomeTest` | 7 | 0 | 0 | 0 |
| `RegionFailurePolicyTest` | 4 | 0 | 0 | 0 |
| `RegionEntityRegistryTest` | 10 | 0 | 0 | 0 |
| `EntitySchedulerLifecycleTest` | 4 | 0 | 0 | 0 |
| **Total** | **25** | **0** | **0** | **0** |

- **Command (operator-run, reported in `core-implementation.md` §2):**
  `bash ./gradlew :common:test --tests '*RegionSchedulerSplitRehomeTest'
  --tests '*RegionFailurePolicyTest' --tests '*RegionEntityRegistryTest'
  --tests '*EntitySchedulerLifecycleTest' --stacktrace` — **exit 0**, `BUILD
  SUCCESSFUL in 3.37s`. The worktree `gradlew` lacks the executable bit and `/tmp` is
  `noexec`, so a bare `./gradlew` cannot be used.
- **Provenance:** the four JUnit XML files in
  `/tmp/ff-reconcile/common/build/test-results/test/` (timestamps 01:56:59), parsed
  for the counts above. Only these four suites remain in that directory, so it is a
  focused run, not a full module run. `ConfigSchemaPatchTest` was deliberately
  excluded (config default change, outside the scheduler patch).
- **Limits:** the core patch is `common`-only, but no full `:common:test`,
  `:fabric:test`, `:api:test` or `build` has been recorded since the patch landed.
  Nothing here is runtime, mixin-application or gameplay evidence.

## Live clientless evidence — 2026-11-22 (QA, reviewed)

Source: `tools/freebuff/reports/live-runtime-and-benchmark.md` plus the raw runs under
`/home/administrator/.cache/ff-qa-evidence/runs/` (outside the repository).

- **Build:** the 02:28 tree failed to compile until QA made
  `RegionTransitions.transitionKey` / `EntityTransitionKey` public (required across the
  package boundary) and corrected two test fixtures from chunk `(50,50)` to `(400,400)`
  because simulation distance 10 makes `(50,50)` the same region. Assertions were not
  weakened. Green build log `build-full-4.log`, exit 0.
- **Test snapshots:** QA's 02:30 snapshot = api 10 + common 161 + fabric 129 = **300**
  all green. After runtime's 02:40 rewrite the current XML = api 10 + common 161 +
  fabric 136 = **307** all green (0 failures/errors/skips). Fabric's 136 includes the
  14 R1 tests. `ConfigSchemaPatchTest` 4/4 passed in the common 161, closing the config
  condition.
- **Boots:** three isolated, loopback-only 26.2 runs reached `Done (5.241s / 5.038s /
  4.946s)`, zero mixin warnings/errors, `protocol_clients=0`, 26 TPS samples each at
  ~20.00 TPS from `time query gametime` deltas, clean RCON stop and saved worlds, no
  crash reports. JFR (9.5–9.7 MB), `jcmd` heap/thread dumps and per-5s raw command
  outputs are preserved per run.
- **Critical caveat:** the three benchmark runs booted a jar built **02:28:55**, sha256
  `0ac65238959e237e8036a35b91a192330cd772a579f22ae17905e595ab7b048e`, which predates the
  R1 rewrite (02:34–02:40). Run 6 then rebuilt the current tree
  (`06e852018bd19858683362deb0ad54341b83e830d5e9c1921eaacd6ba3b8d76a`, 02:52:26) and
  booted `Done (4.920s)` with zero mixin errors, 60 s sampling at 20.000 TPS and a
  clean stop. That closes the **mixin-application** gap for final R1; it is not a
  teleport/gameplay check and not benchmark data.
- **Fabrolia: BLOCKED, no comparison.** Pinned `f_3.2.6`, asset
  `fabrolia-alpha_3.2.6.jar`, sha256
  `42678345eca6bc5b99ed9f3e405f98bc4d40f39ead15bcc1a55d5815a90dd3ca`, declares
  `minecraft ~26.3`, `java >=25`, GPL-3.0. 3.2.1 and 3.2.0 also declare `~26.3`. This
  project is 26.2, so no same-version run was attempted, no 26.3 toolchain installed,
  no GPL source fetched and **no winner or speedup claimed**.
- **Scope boundary:** binaries and all evidence live outside the repository; no
  Fabrolia jar or source and no GPL file was added to `/tmp/ff-reconcile` (searched).
  40-client/full profile deferred: 14 GiB host and no verified native 26.2 client.
- **Core re-verification this turn (code, not report):** D4-F1 still real — the drain
  resolves the owner then does `queuesByRegion.get` / `computeIfAbsent` + `add` without
  the structure lock (RegionScheduler ~708–716); F2 still real — `onRegionMerged` does
  not call `failurePolicy.onRegionRemoved(donor)`, only `onRegionDead` does (~375);
  R11 still real — `RegionEntityRegistry.register` mutates map and per-region set
  outside the stripe (~173–181); R6 unchanged.

## Manager decisions on the core review (2026-11-22)

| Item | Decision |
| --- | --- |
| ConfigSchema staged-gameplay defaults `true` -> `false` | **NOT approved; recommend REVERT** — see the 2026-11-23 matrix. The test is green (`ConfigSchemaPatchTest`), but it was added in the same unapproved diff; the flip breaks the master-gate contract and silently changes repaired configs. |
| 8-arg `RegionScheduler` constructor `private` → package-private | **Accepted** as test-only access; javadoc line-sharing is pre-existing at HEAD. |
| D2 real abort behaviour change | **Signed off**, with the F3 semantics recorded above. |
| D4-F1 merge-window stranding | **Accepted at E3** — fixed via `rehomeToOwner` (two-phase `enqueue` + bounded re-resolve); exact interleaving still not schedulable in a test. |
| F2 merge-side policy cleanup | **Fixed at E3** — `onRegionMerged` releases the donor's failure-policy state. |

**The 280-test baseline is a different run.** It is the reconciliation run
(`:common:test :fabric:test :api:test --rerun-tasks`, API 10 + common 148 + Fabric
122, zero failures/errors/skips) recorded in
`tools/freebuff/reports/reconciliation.md` §3. Its XML set was overwritten by later
focused runs; it must not be quoted as covering the core D1–D4 patch.

## Rules for adding entries

1. One root cause per entry, named at file/method level.
2. A fix without a regression test is not a fix; a test without the failing-first
   observation is a guess.
3. Runtime evidence must name the commit, the world/workload and the measurement method
   (gametime deltas, Spark/JFR); otherwise write "none".
4. Nothing moves to "fixed" while its regression is red in the same tree.
5. A green unit suite does not downgrade a P0 whose failure mode requires a live
   runtime (mixin application, thread ownership, Netty event loop, server-thread state).

## Current integration status — 2026-11-23

The dated implementation reports and final integration pass supersede earlier open/untested notes where explicitly stated:

| Item | Current status | Remaining evidence |
| --- | --- | --- |
| R1 teleport dispatch | Accepted at E3; 14 focused tests and final-tree boot evidence | Live /tp, portal, player teleport and persistence checks |
| R2 connection/player liveness | Implemented at E3; 7 deterministic tests | Live /kick, proxy-drop, reconnect and STRICT-mode test; live PlayerList.remove thread/count |
| R3 owner lookup | ownerOfChunk read path is lock-free via published ConcurrentHashMap; 4 deterministic publication tests | RegionScheduler.enqueue phase-2 lock, transient false-null window, dead-queue leak, R11b lock hold time, JFR/latency measurement |
| R4 cross-region mutation | Built-in staged paths fail-closed at E3; protocol remains open | Worker-side entity/block-entity/scheduled/random/player/packet mutation paths are forcibly disabled and redstone smoke passes; true cross-region mutation bridge and third-party task ownership are not proven |
| R5 deferred mutation failure | Fixed at E3; 5 focused tests | Live crash-report injection; chunk-task failure propagation |
| R6 positionless queues | Accepted-task drop/retirement half fixed at E3 | Position-bearing producer updates for packets, transitions, commands and tick interceptors |
| R10 server-thread registration | noteServerThread residual closed | Remaining admission, ordering and pause-window defects |
| R11 entity registry registration | Stripe-lock fix accepted at E3 | R11b structure-lock hold-time reduction/measurement |
| L1 diagnostics dependency | Fixed at E3; final JAR bundles Adventure key and Examination transitives; 26.2 boot and /folia diagnostics verified | Live metrics under loaded regions and gameplay |

Final coordinated evidence: :common:test :fabric:test :api:test --rerun-tasks passed 329 tests (171 common, 148 Fabric, 10 API), zero failures/errors/skips; build --stacktrace exited 0. The final JAR booted in a disposable Minecraft 26.2 instance (Done 0.481s), no Mixin/class-loading errors, and /folia health, /folia workers, /folia metrics responded. This remains a dirty, uncommitted tree. No release approval or deployment.

R4 is not mitigated by the default-off configuration or by the chunk loadedness guard. Any explicit enablement of parallel staged world mutation remains outside the proven safety envelope. See tools/freebuff/reports/r4-design-review.md.


R4 mitigation evidence and scope are detailed in tools/freebuff/reports/engine-off-and-redstone-smoke.md. The interlock disables the built-in mutation-bearing worker paths regardless of config and has a disposable 26.2 redstone smoke in both master-off and engine-on configurations. This is a fail-closed fallback, not a cross-region write bridge; retain the release blocker for re-enabling parallel gameplay or claiming general cross-region mutation support.
