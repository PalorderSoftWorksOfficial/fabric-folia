# TEAM_REPORT_RUNTIME.md — runtime / concurrency audit (read-only)

**Author:** runtime performance & concurrency specialist (Buffy)
**Date:** 2026-11-15
**Scope:** read-only audit of `fabric-folia` at `main`. No source, test or shared-doc
changes; no builds, no boots, no commits. The only file created is this report.
`FREEBUFF.md` was read in full (including §6 runtime/benchmark history) and was
**not** modified, because the request explicitly restricted writes to this file.

**Evidence classes used below**

| Tag | Meaning |
| --- | --- |
| `[CODE]` | verified by reading the current tree (file + line cited) |
| `[REC]` | recorded in `FREEBUFF.md` §6 by an earlier session; **not re-measured here** |
| `[UNVERIFIED]` | plausible from the javadoc/record, but needs `javap` on the 26.2 jar or a live probe before it is treated as fact |

No MC jar is present on this host (`find` for `minecraft-common-*.jar` → none), so
nothing in this report was re-derived from bytecode. Every vanilla-behaviour claim
below is either `[REC]` (a bytecode fact already recorded) or flagged
`[UNVERIFIED]`.

---

## 0. Executive summary

The pipeline is real, lossless **in the steady state**, and the measured wins are
substantial (`[REC]`: MSPT p50 101.8 → 80.2 ms on 61,425 stands; 20 ms p50 on the
12k clump; TPS 3.75 → 20.0 after the slow-motion gate returned). The design's
central bet — *vanilla decides, workers execute* — is implemented faithfully: every
capture sits at a vanilla funnel and the vanilla body runs verbatim.

The audit nonetheless found **four issues that can produce wrong-context mutation
or a hard hang**, and a set of hot-path costs that are unmeasured rather than small:

1. **Split-orphaned queued work (`P0`)** — every region enqueue that carries no packed
   chunk position (all staged batches, packet re-homes, teleports, random-tick
   batches) cannot be redistributed when a region splits. The task stays queued on the
   parent and executes its body in the *parent's* REGION context even though the body's
   chunk now belongs to a child. This violates the class's own "no staged body is ever
   executed by a region that no longer owns it" contract.
2. **Ungated global scheduler (`P0`)** — `FabricFolia-Global` drains every 50 ms on its
   own thread, *concurrently with the server thread's tick phase*, and is used as the
   fallback for unowned teleports and network work. Vanilla-state mutation there is
   exactly what the phase gate exists to prevent.
3. **Worker-blocks-on-deferred-work deadlock (`P0`, conditional)** — deferral queues
   are drained at `tickChildren` HEAD, i.e. *after* the tick-start quiesce; a worker
   that waits on deferred work (the `addTicketAndLoadWithRadius` bridged future is the
   concrete shape) can never be released while the barrier waits for it. Combined with
   Phase 1B's *unbounded* fail-safe wait, that converts a transient into a watchdog kill.
4. **Config-gate wiring is dead on the startup path (`P1`)** — `resolvePatchStates()`
   runs before `engine` is assigned, so its `if (engine != null)` branch never executes:
   `region-lookup` and `scheduler-dispatch` toggles are reported in `/folia patches`
   but never reach the scheduler/regionizer; `regionize-chunk-load` is bound to the
   *region-lookup* config key instead of its own.

Everything else is prioritised below. Each finding names a bounded root fix and a
regression test that fails before it and passes after.

---

## 1. Exact tick path (what actually happens, and on which thread)

### 1.1 Phase timeline for one server tick

```
 MinecraftServer.run loop
 │  (server thread = "Server thread")
 ├─ processPacketsAndTick HEAD ────────────────────── [beginTickPhase]
 │    • WorkerPool.closeExecutionAndAwait(1000 ms, pumpChunkExecutors)
 │    • = QUIESCE: gate closed; wait busyCount()==0; on timeout log + unbounded
 │      fail-safe wait loop (1 s slices) until quiesced         `[CODE]`
 ├─ vanilla phase A (gate closed: workers start nothing new)
 │    • packet processor queue drain (respawn path lives here)
 │    • per-level ServerLevel.tick:
 │        – weather/time/global
 │        – blockTicks.tick / fluidTicks.tick  → runCollectedTicks
 │             ├─ [SCHEDULED_TICK capture] StageRetry of every drained body
 │             └─ inside: tickBlock/tickFluid bodies
 │        – guardEntityTick(consumer, entity)     ← entity pass (entityTickList)
 │             └─ [ENTITY capture] stage the consumer body
 │        – tickBlockEntities() list maintenance + per-ticker
 │             └─ [BLOCK_ENTITY capture] stage ticker.tick()
 │        – ServerChunkCache.tick → tickChunks (random ticks, opt-in)
 │             └─ [random-tick intercept] collect chunks, then queue one batch/region
 │    • tickChildren HEAD → ServerThreadDeferral.drainAll()  ← the ONLY reliable
 │      drain point for deferred vanilla mutations (after quiesce)
 │    • ServerConnectionListener.tick() → per-connection Connection.tick()
 │             └─ [PLAYER capture] stage the whole connection body
 ├─ Fabric ServerTickEvents.END_SERVER_TICK ───────── [flushGameplayStaging]
 │    • per world: RegionStageHub.flushStaged()
 │        – StageRetry.drain (retries first, tick order)
 │        – per slice ENTITY, BLOCK_ENTITY, SCHEDULED_TICK, PLAYER:
 │            group by owner → skip-if-behind → inline-or-enqueue
 │        – pending gauge reconcile, backlog hysteresis, tick-cache clear
 │    • ScheduledTickDeferral.replayOnServerThread()  (worker-scheduled ticks)
 │    • endTickPhase()  → gate OPEN
 │    • awaitStagedDrain()  → up to 20 ms pumping/parks while workers drain
 │    • recordTickWork()   → MSPT rings
 └─ vanilla sleep / catch-up; workers execute in this window (gate open)
```

`[CODE]` Mixin anchors: `MinecraftServerMixin:31` (`processPacketsAndTick` HEAD),
`MinecraftServerMixin:47` (`tickChildren` HEAD drain), `ServerChunkCacheMixin:159`
(`ServerChunkCache.tick` HEAD drain, redundant second drain point),
`FabricFoliaMod.flushGameplayStaging` (END_SERVER_TICK).

Consequence worth stating plainly: **the inter-tick window is the only window in which
workers run.** Gate open → drained by `awaitStagedDrain` + vanilla sleep. Gate closed →
workers park. That is the single-writer rule, and it is why every "how much parallelism
do we get" question reduces to "how long is the inter-tick window, and is enough
cross-region headroom batched into it" (`[REC]` §6 2026-10-06 analysis).

### 1.2 What vanilla selects, and what is captured

| Slice | Vanilla decides (server thread, untouched) | Captured body | Position key |
| --- | --- | --- | --- |
| `ENTITY` | `entityTickList` active/passive iteration, `checkDespawn`, tick-rate gating in the ServerLevel lambda | `consumer.accept(entity)` inside `Level.guardEntityTick` — the whole `tickNonPassenger`/`tickPassenger` recursion, **players included** | `entity.chunkPosition()` at flush |
| `BLOCK_ENTITY` | `tickBlockEntities()` merge of pending tickers + removal prune | `TickingBlockEntity.tick()` | BE block pos |
| `SCHEDULED_TICK` | `LevelTicks.runCollectedTicks`: collection, DRAIN_ORDER/subTickOrder, dedup set, `cleanupAfterTick` | the drained `BiConsumer` (`ServerLevel::tickBlock` / `tickFluid`) | tick pos |
| `PLAYER` | `ServerConnectionListener.tick()` synchronized iteration, `isConnecting` skip | whole `Connection.tick()` (packet drain, `SGPLI.tick` → `doTick` physics, outbound flush) | `player.chunkPosition()` at flush |
| random ticks (separate, opt-in) | `ChunkMap.forEachBlockTickingChunk` enumeration | `ServerLevel.tickChunk(chunk, randomTickSpeed)` batched per region | batch region (no position on the task) |
| packet re-home | `PacketProcessor.scheduleIfPossible` funnel | `packet.handle(listener)` | region chosen *now*, **no position on the task** |
| teleports | `Entity.teleport` / `ServerPlayer.teleport` HEAD | the whole vanilla teleport body | region chosen *now*, **no position on the task** |

Capture sites: `LevelEntityTickMixin` (guardEntityTick redirect),
`LevelBlockEntityTickMixin` (ticker redirect), `LevelTicksTickMixin` +
`ServerLevelTickMixin` (drain flag), `ServerConnectionTickMixin` (Connection.tick),
`ServerChunkCacheTickMixin` (chunk enumeration), `PacketProcessorMixin`,
`PacketUtilsMixin`, `EntityTeleportMixin`, `ServerPlayerTeleportMixin`. 26 mixins
registered `[CODE]` `fabricfolia.mixins.json` (FREEBUFF §1 still says 24 — stale).

### 1.3 What stays on the server thread / elsewhere

- Worldgen, chunk load/unload, light, spawning, weather, time, world border.
- Entity/block-entity **selection**, list maintenance, tick-rate gating.
- Scheduled-tick **collection and ordering** — only body execution moves.
- Ticket map mutations (`TicketStorage`, `ChunkMap` tracking, `DistanceManager`) —
  verbatim replay via `ServerThreadDeferral` at `tickChildren`/`ServerChunkCache.tick`
  HEAD (`TicketStorageMixin`, `ChunkMapTrackingMixin`, `DistanceManagerTrackingMixin`).
- `ServerChunkCache.move` and `ServerPlayerGameMode.tick` — bounced to the server
  thread through `ServerThreadDeferral` when the player body runs on a worker
  (`ServerPlayerTickMixin`).
- Chunk broadcast set — worker enqueues deferred into a per-level concurrent set,
  drained at `broadcastChangedChunks` HEAD (`ServerChunkCacheMixin`).
- `PERFORM_RESPAWN` — deliberately left in vanilla's packet queue (server thread).
- **Not server thread:** the `FabricFolia-Global` daemon thread (`GlobalSchedulerImpl`)
  executes fallback work on its own 50 ms cadence; see P0-2.

### 1.4 Feature gates and effective defaults (code-verified)

| Gate | Key | Default | Effect |
| --- | --- | --- | --- |
| master | `general.enabled` | true | engine on |
| gameplay staging | `general.regionized-gameplay` | true | installs hubs + `ScheduledTickDeferral` |
| player path | `gameplay.stage-player-path` | **true** | PLAYER slice + packet re-home + teleport routing |
| random ticks | `general.regionized-random-ticks` | false | chunk-tick interception |
| patches | `patches.enabled` + per-patch keys | true | optimization layer |
| collision opt | `patches.minecraft.entity-collision-optimization` | false | OFEM axis-only collision |
| thread checks | `threads.thread-check-mode` | WARN | diagnostics only; **not** on the staged-body hot path |
| worker threads | `threads.worker-threads` | -1 (auto) | pool size |
| section size | `regions.region-section-size` | 8 | region granularity |

Note the documentation drift: several docs describe the player path as default-off;
the schema default is `true` (`ConfigSchema:404`). FREEBUFF §2 also says "default off".

Hard-coded constants that govern behaviour (`RegionStageHub`, `FabricFoliaEngine`,
`RegionScheduler`, `RegionTickInterceptor`, `StageRetry`):

`BACKLOG_FLOOR=8192`, `BACKLOG_MULTIPLE=2`, `MIN_PARALLEL_BODIES=32`,
`MIN_DISPATCH_TOTAL=64`, `BODIES_PER_TASK=32`, `REGION_SKIP_THRESHOLD=3`,
`DRAIN_WINDOW_MILLIS=20`, `DRAIN_SLEEP_FLOOR_NANOS=5 ms`,
`QUIESCE_TIMEOUT_MILLIS=1000` (then unbounded), `DRAIN_BUDGET_NANOS=35 ms`,
`DRAIN_MAX_ENTRIES=4096`, `PASS_BACKPRESSURE_QUEUE_TASKS=512`,
`PUMP_DRAIN_BUDGET=128`, `StageRetry.MAX_ATTEMPTS=4` / `RETRY_SPACING_TICKS=2`,
`RegionTransitions.TICK_WINDOW_NANOS=50 ms`, watchdog interval 10 s / overdue 5 s.

---

## 2. Risk register

Severity is about production damage: `P0` = can corrupt ownership or hang the tick;
`P1` = real defect with contained blast radius, or a scaling cliff; `P2` = cost,
observability or config-correctness defect; `P3` = doc/label drift.

### P0-1 — Split-orphaned queued tasks execute in a non-owner context

**Where:** `RegionScheduler.enqueueBatch` adds tasks with **no** chunk position
(`RegionScheduler`, `queue.add(task)`); `RegionStageHub.dispatch` at
`RegionStageHub:698-705` builds `StagedBatch` tasks and calls `enqueueBatch`.
`RegionPlayerRouting.schedulePacketToRegion:323` and
`RegionTransitions.dispatch*/dispatchKeyed` call `enqueue(region, task)` (no position).
`RegionTickInterceptor.flushPass` enqueues chunk batches with no position.

**Mechanism:** `onRegionSplit` calls `parentQueue.partitionOnSplit(...)`, which skips
every entry whose `chunkPos == Entry.NO_POSITION`
(`RegionTaskQueue.partitionOnSplit`). Entries without positions stay on the parent.
After the split the parent still ticks and drains them; `RegionStageHub.runBody`
executes the body because the thread is in *a* REGION context — it never verifies that
the context's region still owns the body's chunk. The body then mutates state whose
single-writer is now a child region.

**Why it has not been seen:** splits require the dead-section recalculation gate and
have been observed as `splits=0` in several recorded runs (`[REC]` §6). It is a latent
invariant break, not a frequent crash — which is exactly why it needs a test rather
than a soak.

**Bounded fix (root):**
1. Make every region task carry its target when it has one: `enqueueBatch` gains a
   per-task position (or a single representative position when the whole batch shares
   one chunk — true for the run-length path at `RegionStageHub:640-650`); packet
   re-home and teleport paths pass `packChunkPos(player/entity chunk)`; the
   random-tick batch passes the batch's owning chunk or splits per chunk.
2. Add the execution-time net in `runBody`: one `regionizer.ownerOfChunk(pos)`
   comparison **per task** (not per body), and if the captured region is no longer the
   owner, re-dispatch the task through the normal flush path instead of running it
   under the wrong context. Count it (`bodies re-owned at execution`).
3. Keep `partitionOnSplit` as is; it becomes effective because positions exist.

**Regression test:** new `RegionSplitRehomeTest` in `fabric` — build a hub + real
scheduler, stage bodies in chunk (0,0) owned by region A, force A to split (drive
`WorldRegionizer` chunks so a child owns (0,0)), assert every body executes with
`ThreadOwnership.current().region()` equal to the post-split owner. Today this fails
(child involvement is 0). Also extend `RegionTaskQueueConcurrencyTest` to assert a
`StagedBatch`-shaped task with a position moves on split.

### P0-2 — `FabricFolia-Global` mutates vanilla state off the phase gate

**Where:** `FabricFoliaEngine.bootstrap` starts a daemon
`while (!interrupted) { sleep(50); global.tick(); }`; `GlobalSchedulerImpl.tick()`
enters the GLOBAL context and runs tasks. Fallbacks that land here:
`RegionTransitions.dispatch:134` and `dispatchKeyed:191` (unowned destination — a
portal/teleport into an unregionized chunk), `NetworkDispatch.runOnOwner` /
`runForConnection` (unowned region), `RegionPlayerRouting` fallbacks.

**Mechanism:** the global thread has no relationship to `beginTickPhase` /
`endTickPhase`. It can therefore execute a full cross-dimension teleport body —
`ServerLevel.addDuringTeleport`, `PESM` entity-map mutation, `PlayerList` broadcasts —
while the server thread is inside vanilla's entity pass. `ServerThreadDeferral` covers
tickets/tracking/broadcast but **not** entity-list or `PlayerList` mutation. This is
the same class of race that produced the 2026-09-25 production crash, one layer up.

**Bounded fix (root):** stop treating the global scheduler as a *mutation* context.
Route transition/network fallbacks to `ServerThreadDeferral.defer(...)` (server thread,
drained inside phase A) instead of `globalScheduler().run(...)`; keep the global
scheduler for genuinely global bookkeeping (world border, game rules, regionizer
maintenance) that has no vanilla-world writer. If a task must run off-thread, it must
be provably region-local. Count both destinations separately so operators can see how
often the fallback fires.

**Regression test:** `TransitionBodyServerThreadTest` currently *pins* the global
thread as the unowned-destination context (it asserts the body runs in non-REGION
context on `FabricFolia-Global`). Once routing changes, invert it: assert the body runs
on the recorded server thread and that no global-queue task mutates a `ServerLevel`.
Additionally a unit test that `GlobalSchedulerImpl.tick()` is never entered while
`WorkerPool.isExecutionOpen()` is false, once that becomes the contract.

### P0-3 — Worker blocking on deferred work cannot be released by the barrier

**Where:** `ServerThreadDeferral` queue is drained at `MinecraftServerMixin:47`
(`tickChildren` HEAD) and `ServerChunkCacheMixin:159`. `beginTickPhase`
(`MinecraftServerMixin:31`) runs **earlier in the same tick** and waits for
`busyCount()==0`. `ServerChunkCacheMixin:166` returns a **bridged
`CompletableFuture`** for `addTicketAndLoadWithRadius` from any non-server thread.

**Mechanism:** if a staged body (worker) calls that method and joins/awaits the
bridge, completion requires `drainAll()` on the server thread, which happens *after*
the quiesce. The quiesce waits for the worker; the worker waits for a drain that the
quiesce blocks. `pumpChunkExecutors` does not help — it pumps the chunk executor, not
`ServerThreadDeferral`. Phase 1B deliberately removed the timeout escape, so the tick
holds forever → 60 s drift → watchdog kill with a misleading "single tick took…"
message (`[REC]` watchdog bytecode facts).

**Reachability is unproven.** No worker-path caller of `addTicketAndLoadWithRadius`
was identified in mod code, and its vanilla callers were not enumerated this turn
(`[UNVERIFIED]` — needs `javap`/caller scan on the named 26.2 jar). The ticket
mutators that *are* known-reachable from workers (`addTicketWithRadius`,
`removeTicket*`, `purgeStaleTickets`) are fire-and-forget and safe.

**Bounded fix:** make the deferral drainable from inside the barrier —
`pumpChunkExecutors` (or a sibling pump passed to `closeExecutionAndAwait`) should
also drain `ServerThreadDeferral` **while phase-A exclusive ownership is already
held**. Inside the barrier the server thread *is* the only gameplay writer, so
replaying inside it is exactly as safe as replaying at `tickChildren` HEAD. Second
layer: count a `blockingDeferralWait` whenever a non-server thread waits on a bridged
future, and alarm on non-zero.

**Regression test:** `WorkerPoolGateTest`-style test with a task that defers a
mutation and blocks until it has run; assert quiesce completes within the 1 s budget
and the mutation ran on the server thread. Today it times out and enters the unbounded
wait (the test must run with a hard timeout so it fails instead of hanging CI).

### P0-4 (same family, worth separate tracking) — Unbounded quiesce fail-safe with no in-flight attribution

`beginTickPhase` after the 1 s timeout loops `while (pool.busyCount() > 0)` logging
each second, forever. That is the documented correctness-over-availability decision
and is defensible — **but** it removes the only automatic recovery from any worker
stall (including P0-3). FREEBUFF lists "in-flight attribution at quiesce" as the open
lever; this audit agrees it is now a *safety* prerequisite, not just a perf knob.
Minimum: when the timeout fires, capture each busy worker's stack into the error line
(or a dump file) once per second; add `pool.inFlightTaskLabels()` fed by
`StagedBatch` slice/region so the log names what is stuck.

### P1-1 — Patch-gate wiring is dead on the startup path

`FabricFoliaMod.startEngine` calls `resolvePatchStates(config)` at line 137 and
assigns `engine` at line 190. Inside `resolvePatchStates` the scheduler/regionizer
gates are applied only `if (engine != null)` (line 471) — always false at that moment.

Effects `[CODE]`:
- `region-lookup` and `scheduler-dispatch` are reported ACTIVE/DISABLED by
  `/folia patches` but the runtime keeps the class defaults (`true`), so *disabling*
  them changes nothing.
- `regionize-chunk-load` is requested from `KEY_PATCH_REGION_LOOKUP`
  (`patches.fabricfolia.region-lookup`) instead of its own key — turning off the
  ownership index silently stops chunk registration and therefore region formation.

**Fix:** apply the gates after `attachWorld`/`bootstrap` (e.g. in `attachWorlds`, or
make `attachWorld` consult `PatchRegistry` at construction), and give
`regionize-chunk-load` its own config key. **Test:** assert
`regionizer.ownerLookupIndex == false` and `scheduler.schedulerDispatchPatch == false`
after a real `bootstrap` with both patches disabled — fails today.

### P1-2 — `RegionPlayerRouting.isOwnerRegion` compares region **ids** across worlds (and is not lock-free)

`RegionPlayerRouting:373-374` resolves the owner and compares
`owner.regionId() == current.regionId()`. Region ids are per-world counters starting
at 1 (`WorldRegionizer.nextRegionId`), so two different regions in two different
worlds routinely share an id. The correct comparison is object identity (`owner ==
current.region()`), which is exact because both are the same `Region` instance. Impact
is a false "same owner" answer for a packet handler running on another world's region
with a colliding id — a handler would be allowed to run in the wrong context. Low
frequency, wrong answer.

Same method's javadoc (`RegionPlayerRouting:346` and `PacketUtilsMixin`) claims
"the lookup is the same lock-free `ownerOfChunk` read ... the structure lock is not
taken" — **false**: `WorldRegionizer.ownerOfChunk` takes `structureLock` on every
call. Consequence: one structure-lock acquisition per tick-sensitive packet handler on
a worker, contending with chunk registration. Not a deadlock (no reverse order —
nothing takes the structure lock while holding the pool lock), but a hot-path cost and
a documentation trap.

**Fix:** identity compare + a `ownerRegionChunk` variant that reads a volatile
section-owner snapshot if the lock cost matters. **Test:** two regionizers, ids forced
equal, assert `isOwnerRegion` false for the foreign one.

### P1-3 — Full-world scans under the structure lock on every merge/split/death

`RegionEntityRegistry.onRegionMerged:298`, `onRegionSplit:310`, `onRegionDead:339`
iterate the **entire** `ownerByHandle` map (all entities in the world) while the
regionizer structure lock is held. On the recorded 61,425-entity world that is a
~61k-entry scan per structural event, and `onRegionSplit` additionally calls
`regionizer.ownerOfChunk` per entity. Every `ownerOfChunk` caller in the process
(chunk registration, packet handler check, enqueue death re-check, flush resolve on
cache miss) blocks behind it. The per-region entity set already exists
(`perRegionEntities`) and contains exactly the donor's handles.

**Fix:** iterate `perRegionEntities.existingStructural(donor)` (merge/death) and
partition by child using that set (split), falling back to the full map only when the
set is absent. **Test:** register N entities across two regions, merge, assert the scan
visits only the donor's set (instrument with a counting set wrapper) and the
authoritative map is correct.

### P1-4 — Structure lock per drained scheduled tick

`LevelTicksTickMixin` calls `RegionPendingTicks.release(x,z)` for **every** drained
scheduled tick; `release` calls `regionizer.ownerOfChunk` → `structureLock`
(`RegionPendingTicks:170`). `isPending` (`:205`) also falls back to `ownerOfChunk`
from workers whenever the caller's own ledger has no entry — i.e. the *common* "no"
answer pays a world structure lock.

This is a redstone/farm-scale cost (thousands of drains/tick possible) on the same lock
chunk registration uses, and it is currently unmeasured. It is not a correctness bug —
the ledger is conservative in the safe direction.

**Fix (bounded):** store the owner region reference in the ledger entry at capture time
and resolve release from the ledger's own chunk→owner map (rebuilt on structural
events, which already partition it); only fall back to `ownerOfChunk` when the entry is
absent. **Test:** assert `release` performs zero `ownerOfChunk` calls for a captured
tick (wrap the regionizer in a counting fake), and that the balance still closes.

### P1-5 — Slow-motion intake drops ENTITY bodies, including players

`RegionStageHub.dispatch:651-661` removes a region's whole round when its queue holds
more than `REGION_SKIP_THRESHOLD=3` tasks — for every slice except `PLAYER`. The
`PLAYER` *connection* body is exempt, but a **`ServerPlayer`'s entity-pass body lives
in the `ENTITY` slice** and is therefore skippable while the connection body still runs.
Result under overload: physics/packets advance, `ServerPlayer.tick()` (food, health,
advancements, item use cooldowns) silently loses rounds, at a rate invisible outside
the aggregate skip counter.

This is a deliberate, counted overload policy (Folia's "slow region" contract) and was
re-enabled on purpose after the no-loss variant caused a deterministic watchdog kill
(`[REC]` 2026-11-09). The audit's point is narrower: the *player* case is not obviously
covered by that policy, and the decision is not documented as such.

**Fix/decision:** either exempt `ServerPlayer` bodies from the skip (cheap: one
`instanceof` at dispatch, keeps the player state machine whole), or document the
degradation explicitly and add a per-kind skip split (`skipped player bodies`) so the
behaviour is observable. **Test:** backed-up region + one player body → assert either
the body is enqueued (exemption) or the player-skip counter increments (documented
policy).

### P1-6 — Deferred-flag restores use `@At("RETURN")`, not `finally`

`ServerLevelTickMixin:36/43`: `beginDrainStaging()` at HEAD, `endDrainStaging()` at
RETURN. The javadoc claims "clearing in a `finally` guarantees no leak", but a RETURN
inject does not run on exceptional exit. If `ServerLevel.tick` throws, the thread-local
`DRAIN_STAGING` stays `TRUE`, and every later `LevelTicks` drain on that thread for any
staged world is treated as staged. In practice a throwing level tick crash-stops the
server, so blast radius is small — but the same shape exists in
`ConnectionPacketDispatchMixin` (`runOnceConnected` HEAD/RETURN), where a throwing
handler leaves a Netty event loop permanently classified `NETWORK`, which *does* change
routing for later work on that loop.

**Fix:** mark the injections `@At("RETURN")` **plus** a `@At("THROW")` restore, or
restore in the existing outer `try/finally` sites. **Test:** `ServerLevelTickMixin` is
mixin-level (needs MC bootstrap) — cover the *state holder* instead: assert
`ScheduledTickDeferral` has an idempotent `abandonDrainStaging()` and that a simulated
throw path restores `false`.

### P1-7 — Teleport idempotence window is keyed by `int` entity id across worlds

`RegionTransitions:112/168` `IN_FLIGHT.put(entity.getId(), now)`. Entity ids are
per-level counters, so two entities in different dimensions can share one id. If both
teleport within 50 ms, the second is reported as a duplicate: `dispatch*` returns
`true`, the mixin cancels the vanilla body, and **the teleport never executes** (not
merely delayed). Also `IN_FLIGHT.values().removeIf(...)` is an O(n) scan of all
in-flight transitions on every dispatch.

**Fix:** key by `(worldKey, entity id)` or by the entity object identity (a small
`IdentityHashMap` under a lock, or a `ConcurrentHashMap<Entity, Long>`), and sweep
lazily instead of per dispatch. **Test:** two fake entities with equal ids in different
worlds dispatch within the window; assert both bodies run exactly once.

### P1-8 — Worker death silently shrinks the pool

`WorkerPool.workerLoop` rethrows fatal errors after `terminated.countDown()`; nothing
replaces the thread, and `workerCount()`/`keepaliveSnapshot().length` still report the
original size. A repeated fatal (e.g. `OutOfMemoryError` in a body) degrades the pool
permanently and invisibly. `[CODE]`; FREEBUFF already lists "worker replacement after a
fatal worker death" as open.

**Fix:** catch fatal in `workerLoop`, count it in metrics, start a replacement thread
(same index) unless shutting down; if the pool cannot be maintained, escalate through
the error sink. **Test:** inject a task that throws `LinkageError`, assert
`busyCount` returns to 0, a replacement worker exists, and the fatal counter is 1.

### P2-1 — Random-tick slice drops are invisible

`RegionTickInterceptor.flushPass`: not-yet-loaded neighborhoods `continue` (silent),
back-pressured regions increment a **local** `backpressured` which is only logged when
`debugLogging()` is on, and `runChunkWorkBatch` returns early on `region.isDead()`
(silent). The random-tick slice is opt-in (default off), but the repo rule is "failures
must be retried or fixed, with the outcome visible in metrics". This is the only slice
that violates it. **Fix:** three counters (`randomTickSkippedEdge`,
`randomTickSkippedBackpressure`, `randomTickDroppedDeadRegion`) in `RegionMetrics`.

### P2-2 — `StageRetry` is gated on an unrelated flag

`RegionStageHub.flushStaged` passes `engine.randomTickInterceptSuppressed()` as the
retry drain's `suppressionActive`. When random ticks are suppressed (c2me present), entity
retries stop draining — but their bodies are entity work, unrelated to random ticks.
Entries then accumulate until the world detaches. **Fix:** pass the real condition (a
dedicated `retriesSuppressed` when the retry's *cause* is active, otherwise `false`), or
drop the gate. **Test:** suppression on + a scheduled retry due → today it stays
pending forever; assert it runs.

### P2-3 — `ChunkResidency` copy-on-write clones per chunk event

`ChunkResidency.Resident.add/remove` clone the whole `LongOpenHashSet` on every chunk
load/unload (`:64`, `:73`). For a 1k-chunk working set that is a ~1k-long copy plus
allocation per event, on the server thread, inside chunk registration. Readers are
lock-free (good). Never benchmarked. Candidate fixes: striped per-section bitset, or a
mutable set guarded by a `StampedLock` read, keeping the volatile snapshot idea only
for the reader fast path. **Measure before changing** (experiment E5).

### P2-4 — Coordinator duplicate submissions while the gate is open

`RegionScheduler.coordinateLoop` scans every 1 ms and submits a tick job for every
READY due region. Between submit and the worker's `tryBeginTick`, the region is still
READY, so duplicates accumulate in `WorkerPool.handoff` (bounded by the inter-tick
window ≈ 20 ms → ≤ ~20 per region per window). Each duplicate costs a state/deadline
check. Not harmful, but it pollutes `queuedTaskCount` and the dispatch picture. A
`submittedForTick` flag (region tick counter stamp) would remove it.

Also note `pool.submit(...)` runs **inside** `forEachLiveRegion`, i.e. under the
structure lock. The lock order structure → pool is safe today (no path takes the pool
lock then the structure lock while holding it), but it must be documented, because
`requeueFront`/`queuedTaskCount` are pool-lock users and a future worker-side caller
would create the cycle.

### P2-5 — `pendingBodies` reconcile can zero a live gauge

`RegionStageHub:571` sets `pendingBodies` to 0 when region queues are empty and
`busyCount()==0`. A worker can be between `takeTask` and `busyCount.incrementAndGet`,
in which case the batch's release later clamps at 0 (harmless) but the backlog gate
sees a low gauge and may admit more work than intended. Gauge-only; fix by making the
reconcile subtract a *sampled* in-flight estimate, or move `busy++` before `handoff.poll`.

### P3 — Documentation drift that will mislead the next agent

- `<synchronized>` staged list wrapper described in `RegionStageHub` javadoc; the code
  uses plain `ArrayList` with single-writer discipline (accurate behaviour, wrong doc).
- `ownerOfChunk` described as lock-free in `RegionPlayerRouting`/`PacketUtilsMixin`
  (it locks).
- MIXINS.md / FREEBUFF §1 mixin counts (24 vs 26 registered).
- Player-path default described as off in FREEBUFF §2; schema default is true.
- `RegionTickInterceptor` javadoc says structural work is never subject to the
  backpressure gate — true for that class, misleading next to the hub's skip gate.

---

## 3. Body-loss / duplication / backlog inventory

| Path | Kind | Counted? |
| --- | --- | --- |
| Region queue backed up (`> 3` tasks) → round not enqueued | **loss** (this round) | yes, `regionBehindSkipped` |
| Region died before `enqueueBatch` | loss | yes, `droppedDeadRegion` |
| Body throws CME (c2me async load) | retried, ≤4 attempts | yes, `StageRetry.*` |
| Body throws other `Throwable` | **loss** (isolated) | yes, `EXCEPTIONS_ISOLATED` |
| Body retried after partial application | **possible duplicate side effects** | no |
| Backlog gate refuses staging | not loss (inline) | yes, `suppressedBacklog` |
| Below dispatch thresholds | not loss (inline at flush) | yes, `inlineNoParallel` |
| No owning region at flush | not loss (inline) | no dedicated counter |
| Execution-time neighborhood stale | not loss (deferred to server thread) | yes, `bouncedToServer` |
| World detach: staged batch enqueued then `scheduler.close()` drops | loss | only the scheduler's "dropped N queued tasks" log |
| Random-tick edge/backpressure/dead region | loss | **no** (P2-1) |
| Teleport duplicate window | loss (silently claims success) | counted as "dispatched" |
| Split-orphaned task | wrong-context execution | no |

The reconciliation identity that a soak must be able to close:
`stagedTotal ≈ executedOnWorkers + executedOnServer + bouncedToServer + droppedDeadRegion
+ regionBehindSkipped + suppressedBacklog(inline, if counted at stage) + pending`.
FREEBUFF still lists `staged bodies total` over-counting (~12–25×) as open item 1; the
identity is therefore **not** currently checkable, and this audit did not find the
cause in the paths read (every `stage()` increments once; `StageRetry` replays do not
re-stage). The most likely candidate is `guardEntityTick` being reached more than once
per entity per tick (passenger recursion + `tickNonPassenger` re-entry), which would
mean the *staged* count counts deliveries, not entities — consistent with the recorded
"Work counters = exact entity rate, staged ≈ 12×". Confirm by tagging each staged body
with the entity id and counting distinct ids per tick (experiment E7). Any soak gate
must wait for this.

---

## 4. Hot-path cost inventory (unmeasured unless noted)

| Site | Cost | Notes |
| --- | --- | --- |
| `RegionStageHub.dispatch` grouping + run-length cache | O(bodies) hash/identity probes; run-length hit path is 1 long compare | `[REC]` 2026-11-11: grouping was ~30 % of server runnable at 61k; run-length raised TPS 10.1→12.5, flush p50 9.5→1.9 ms |
| `vanillaNeighborhoodLoaded` 3×3 `getChunkNow` | 9 chunk lookups per *chunk*, not per body (tick cache) | `[REC]` 2026-11-10: ~30 % of server runnable before the cache; cache hits 103.6 M |
| `ownerOfChunk` at flush | structure lock (reentrant) per chunk, cached per tick | `[REC]` JFR 2026-11-10: 7 % of server runnable pre-cache |
| `RegionPendingTicks.release/isPending` | **structure lock per drained scheduled tick / per worker query** | P1-4; unmeasured |
| `RegionEntityRegistry` structural scans | O(all entities) per merge/split/death under the structure lock | P1-3; unmeasured |
| `ChunkResidency` COW clone | O(chunks) copy per chunk load/unload | P2-3; unmeasured |
| `RegionTransitions.IN_FLIGHT` sweep | O(in-flight) per transition | P1-7 |
| `RegionTickInterceptor.flushPass` | `addChunk` (structure lock) per offered chunk, ~250/pass | random ticks only |
| Quiesce poll | `Thread.sleep(1)` loop, hence ~1 ms latency floor + in-flight task tail | `[REC]` 2026-11-09: p50 10.2 ms / p95 31.3 ms at 32 bodies/task |
| `MsptRing.record` | `synchronized` per sample, 5 rings/tick + regions | audited `[REC]` 2026-10-28, negligible |
| Metrics counters | per batch, not per body (`Counters` batching) | `[CODE]` |

---

## 5. Performance evidence actually on record

All figures below are from `FREEBUFF.md` §6 and are **not** re-measured in this turn.
Same-dev-world, protocol-bot-driven, regionized unless stated. TPS is always
`time query gametime` deltas over RCON; MSPT is work-based (`beginTickPhase` →
flush+drain), which excludes vanilla's sleep.

| When | Workload | Result |
| --- | --- | --- |
| 2026-10-06 (run E) | 3,987-stand single clump | TPS 23.7 / 30.1 / 22.0 (before: 4.8–6.4, run D); 0 "Can't keep up" |
| 2026-11-09 (Phase 1 "no-loss") | same clump | **watchdog kill ~75 s**, TPS 3.75, MSPT p50 311 ms, kill stack `ArmorStand.pushEntities → EntitySection.getEntities` |
| 2026-11-09 (slow-motion restored) | same clump, 3 boots | TPS 20.06/19.99/20.00 (64/task) and 20.10/20.00/20.03 (32/task); MSPT p50 20.69 / 20.68; quiesce p50 15.00 → 10.20, p95 46.19 → 31.26 at 32/task |
| 2026-11-10 | 11,987 stands (2 regions) | MSPT p50 50.74 → 20.17, p95 55.60 → 40.85, p99 46.90, recent misses 64/100 → 1/100, flush p50 1.99 → 0.51, body p50 10.69 → 4.94, pending 6,456 → 2,785 |
| 2026-11-11 | 61,425 stands, 720 forceloaded chunks | TPS 10.7/10.0/9.6 → 12.5 with run-length grouping; MSPT p50 101.8 → 80.2; flush p50 9.5 → 1.9 |

**Limitations that must travel with those numbers**

- One dev world, one protocol bot; no vanilla A/B at 61k (no engine kill-switch
  found at that time).
- Run-to-run variance is real (the same clump world ground at 4 TPS in one run and
  0.7–1.4 TPS in another).
- `Work` counters only increment through `runBody`; the vanilla-equivalent run counted
  nothing, so some historical "TPS" figures were drift-derived, not gametime-direct
  (`[REC]` 2026-10-06 caveat).
- The 20 ms MSPT p50 floor on the clump world is **wait**, not CPU: 20 ms drain budget
  + quiesce on in-flight tails. Lowering the drain budget would improve the number while
  slowing the region's tick rate — a metric-gaming path, explicitly rejected.
- Nothing here measures the P1/P2 costs (structure lock, entity scans, COW residency),
  because they are dwarfed by `pushEntities` on clump worlds and simply not visible in
  aggregate MSPT. They need targeted worlds/tests (below).

---

## 6. Next decisive experiments (bounded, falsifiable)

Each states the measurement, the world, and what result would change the plan.

**E1 — Split-ownership proof (P0-1).** Unit: stage N bodies in a chunk, force a split
that moves that chunk to a child, assert every body runs with the child as the current
region; count `re-owned at execution`. Expected today: 0/ N on child (test fails).
No live boot needed.

**E2 — Structure-lock attribution (P1-3/P1-4).** On the 12k clump world and on a
redstone/dripstone world: JFR 120 s with lock events + a temporary nanoTime histogram
around `structureLock` acquisitions keyed by call site (`addChunk`, `ownerOfChunk`,
`underStructureLock`, `completeTick`). Decide whether the lock or `pushEntities`
dominates the server thread in a **non-clump** workload. Success metric: the share of
server-runnable samples in `ReentrantLock.lock` before/after moving `release`/
entity-scan off the lock.

**E3 — Deferred-work barrier deadlock probe (P0-3).** Add a temporary metric that
counts non-server-thread callers of `addTicketAndLoadWithRadius` and any
`CompletableFuture.join/await` executed in REGION context. Run: forceload add/remove,
portal transit, ender pearl, respawn, `/execute in` teleports, with the player path on.
Non-zero worker joins ⇒ P0-3 is live; zero ⇒ record it as a guarded invariant (assert
in the barrier that no worker is parked on a deferral).

**E4 — Global-scheduler mutation audit (P0-2).** Instrument `GlobalSchedulerImpl.tick`
to record (a) whether `WorkerPool.isExecutionOpen()` was false at any point during the
batch, (b) task class site. Then reroute unowned transitions through
`ServerThreadDeferral` and A/B a portal/teleport soak: expect identical TPS, zero
global-thread `ServerLevel` mutations, and a small transition-latency increase
(one tick) that must be measured, not assumed.

**E5 — ChunkResidency cost (P2-3).** Bot walk/elytra loop crossing ~10k chunk
boundaries: JFR allocation profile + `ChunkResidency` clone time as a share of
server-runnable. Only if > ~2 % does a striped redesign pay.

**E6 — Player body skip parity (P1-5).** Overloaded single-region world with a player:
measure `ServerPlayer` entity-tick rate (region counter / wall time) vs 20/s, and
compare with the exemption patch. Recommend the exemption if the rate drops below ~18/s,
because the player state machine is split otherwise.

**E7 — Staged-count reconciliation (open item 1).** Tag staged bodies with entity id
(or `Ticker` identity for BEs) per tick and print `deliveries / distinctIds` in
`/folia metrics`. This either finds the over-count or closes the item, and it is a
hard prerequisite for any soak acceptance gate.

**E8 — Gate-toggles actually off (P1-1).** Boot once with `region-lookup: false` and
`patches.fabricfolia.scheduler-dispatch: false`; assert `/folia patches` says DISABLED
**and** the runtime path is the linear scan / list copy (log once at attach). Today the
second half is false.

---

## 7. Production soak plan

Preconditions (all must hold before the soak counts as evidence):

1. `./gradlew :common:test :fabric:test :api:test build --stacktrace` exit 0 captured
   explicitly (pipefail or explicit `$?`), with the new regression tests from §2.
2. P0-1/P0-2/P0-3 fixed, or explicitly accepted in writing with a monitoring rule.
3. E7 reconciliation closed, or the soak reports the identity gap as a number instead
   of pretending it balances.

**Stage 1 — correctness soak (2 h, dev world).** 12k clump + player bot; script:
join, walk, build, break, kill, respawn, ender-pearl, portal transit both directions,
forceload add/remove, dimension change, chunk load/unload loop to force
merge/split, autosave. Sample `time query gametime` every 30 s (3 windows ≥ 30 s per
report), `/folia metrics` every 5 min, JFR 60 s at minutes 20/60/110.

Acceptance: TPS mean ≥ 19.5; 0 watchdog kills; 0 quiesce timeouts; 0 ERROR lines;
0 `staged-body retries exhausted`; `dropped/skipped` explained by the scripted
overload; pending bounded (< backlog limit); heap plateau with < 10 % residual growth
between minute 60 and 120.

**Stage 2 — multi-region spread soak (6 h, 20–60k entities, 4+ bots in different
directions).** Purpose: exercise real cross-region parallelism, merges, splits, and
the structure-lock paths. Same instrumentation plus per-region MSPT distribution
(expect the hot region's own tick rate to lag; that is Folia semantics, record it).

**Stage 3 — production-like (24 h, PalorderCentral workload).** Watchdog + StallProbe
armed. Dashboards: TPS (gametime), MSPT p50/p95/p99, quiesce p50/p95 + timeouts,
drain budgetExhausted, backlog pending + suppressed, per-slice executed/skipped,
deferral pending/deferred/replayed/failed, StageRetry scheduled/retried/exhausted,
region merges/splits/aborts, `split-reowned` and `blockingDeferralWait` (new).

**Abort criteria (any one stops the soak and produces a report, not a retry):**
watchdog kill; quiesce fail-safe wait > 5 s; `ServerThreadDeferral.failed` increases;
`staged bodies executed` stops advancing while the server ticks; entity count drift
between `/folia entities tracked` and a vanilla selector census; any crash report.

**Post-soak artefacts:** the metric snapshots, the JFR clips, the reconciliation line,
and an explicit list of counters that were non-zero and *not* explained. Per repo rule
5, a mixin-affecting fix also requires one live boot check (`Done (` + zero mixin
errors) and a clean shutdown + process sweep.

---

## 8. What this audit did not do

- No build, test run, server boot, RCON query, JFR capture or `javap`. Every numeric
  figure is quoted from FREEBUFF §6 with its date, not produced here.
- No bytecode verification of the vanilla callers of `addTicketAndLoadWithRadius`
  (P0-3 reachability) or of the exact position of `tickConnection` relative to
  `END_SERVER_TICK` (relevant to whether any staged PLAYER body can run against a
  post-flush entity state). Both are listed as `[UNVERIFIED]` and as experiments.
- No review of the GPU subsystem, command layer, config parser, or the test bodies
  themselves beyond what is needed to name regression tests.
- `FREEBUFF.md` was not updated, per the read-only constraint of this task; its §6
  work-state section therefore does not contain this audit. The next writable turn
  should fold §2's P0/P1 list into it.

---

## 9. File map for the fixes

| Finding | Primary file(s) |
| --- | --- |
| P0-1 split orphan | `common/.../scheduler/RegionScheduler.java`, `RegionTaskQueue.java`, `fabric/.../engine/RegionStageHub.java` (`dispatch`, `runBody`), `RegionPlayerRouting.java`, `RegionTransitions.java`, `RegionTickInterceptor.java` |
| P0-2 global thread | `fabric/.../engine/RegionTransitions.java`, `NetworkDispatch.java`, `FabricFoliaEngine.java` (global thread), `common/.../scheduler/GlobalSchedulerImpl.java` |
| P0-3 deferred barrier | `fabric/.../engine/FabricFoliaEngine.java` (`beginTickPhase`, `pumpChunkExecutors`), `ServerThreadDeferral.java`, `mixin/ServerChunkCacheMixin.java` |
| P1-1 gate wiring | `fabric/.../FabricFoliaMod.java` (`resolvePatchStates`, `attachWorlds`), `config/ConfigSchema.java` |
| P1-2 owner identity | `fabric/.../engine/RegionPlayerRouting.java` (`isOwnerRegion`), `region/WorldRegionizer.java` |
| P1-3 scans | `common/.../scheduler/RegionEntityRegistry.java` |
| P1-4 ledger lock | `common/.../scheduler/RegionPendingTicks.java`, `mixin/LevelTicksTickMixin.java` |
| P1-5 player skip | `fabric/.../engine/RegionStageHub.java` (`dispatch` skip block) |
| P1-6 flag restore | `mixin/ServerLevelTickMixin.java`, `mixin/ConnectionPacketDispatchMixin.java`, `engine/ScheduledTickDeferral.java` |
| P1-7 transition key | `fabric/.../engine/RegionTransitions.java` |
| P1-8 worker replacement | `common/.../scheduler/WorkerPool.java` |
| P2-1 random-tick counters | `fabric/.../engine/RegionTickInterceptor.java`, `common/.../metrics/RegionMetrics.java` |
| P2-2 retry gate | `fabric/.../engine/RegionStageHub.java` (`flushStaged`) |
| P2-3 residency COW | `fabric/.../engine/ChunkResidency.java` |
| P2-4 coordinator | `common/.../scheduler/RegionScheduler.java` (`coordinateLoop`) |
