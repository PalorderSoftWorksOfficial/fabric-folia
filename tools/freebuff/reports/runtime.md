# tools/freebuff/reports/runtime.md — Fabric runtime / mixin ownership audit

**Role:** Fabric/Minecraft runtime integration specialist
**Date:** 2026-11-22
**Scope:** read-only audit of the shared checkout at `/home/administrator/CDN-bucket/fabric-folia`
(`main`, local HEAD `94976ef`, four commits behind `origin/main`; 26 modified tracked files,
5 untracked paths). No source, test, docs or shared-state edits this turn. No Gradle build,
no server boot, no commit/push/stash/reset. The only artifact is this report.

**Bytecode source of truth (used for every descriptor below):**

```
JAR=.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-043a8b3edf/26.2/minecraft-common-043a8b3edf-26.2.jar
javap -p [-c] -cp "$JAR" <class>
```

Evidence tags: `[BYTECODE]` = verified this turn with the jar above; `[CODE]` = read from the
current working tree; `[REC]` = recorded in `FREEBUFF.md` by an earlier session and not
re-measured here; `[UNVERIFIED]` = needs a live probe or a boot to become fact.

**Hard constraints honoured:** no code changes (no file has been handed to this role yet); no
claim of production readiness; no blanket compatibility claim; no experimental default changed;
no exception suppression proposed as a fix; nothing outside this report written.

---

## 0. Executive summary

The staged-execution model is coherent and the capture points are real: every mixin below was
re-checked against the 26.2 jar and every `@Redirect`/`@Inject` target exists with the exact
descriptor used in the source. The architecture's claim — *vanilla selects, workers execute,
the server thread never overlaps workers* — is implemented, but this audit found **five
defects that can silently lose or mis-context gameplay work**, plus a set of narrower
ownership, liveness and observability gaps. The most serious are not performance issues; they
are cases where the pipeline either never runs the vanilla body or runs it on a thread that
does not own the state it mutates.

| # | Severity | One-line |
|---|---|---|
| R1 | **P0** | Non-player `Entity.teleport` is cancelled twice: the dispatched body re-enters the mixin, `isSameRegion` is registry-based (still the source region), the duplicate window answers "already dispatched", and vanilla's teleport body **never executes**. |
| R2 | **P0** | A staged `Connection.tick()` can discover `!isConnected()` at execution and run `handleDisconnection → ServerGamePacketListenerImpl.onDisconnect → PlayerList.remove` **on a region worker**; the mirror case (disconnect between the entity stage and the connection stage) leaves a staged `ServerPlayer` entity body to tick a removed player. |
| R3 | **P0** | `WorldRegionizer.ownerOfChunk` takes `structureLock` on **every** call, including from Netty event loops (`PacketUtils` redirect, `PacketProcessor` re-home, `NetworkDispatch`, commands). Structural transitions hold that lock across O(all entities) scans. An event loop can block behind a merge; `RegionScheduler.enqueue` takes the same lock a second time on the same path. |
| R4 | **P0** | Staged bodies can mutate state the region does not own: the stage/execute gate is "3×3 chunks are *loaded*", never "3×3 chunks are *owned by this region*". Border block writes, neighbour updates, `ServerLevel.blockEvent` (plain `ObjectLinkedOpenHashSet`), POI and explosion work all execute on a worker without an ownership or handoff check. |
| R5 | **P0** | `ServerThreadDeferral.runDeferred` catches every non-fatal `Throwable`, increments `FAILED`, logs and continues. A deferred vanilla mutation that fails half-way leaves partial world state and the tick proceeds — vanilla would have crashed with a crash report. This is the standing "never suppress" rule applied to the server-thread replay path. |
| R6 | **P1** | Split-orphaned *positionless* queued tasks: packet re-homes, teleport bodies, command re-dispatch and random-tick batches are enqueued with `NO_POSITION`, so `partitionOnSplit` leaves them on the parent and they execute in the parent's REGION context after the chunk moved to a child. |
| R7 | **P1** | Context-restore injects at `RETURN` only (`ServerLevel.tick`, `Connection.runOnceConnected`, `Commands.performCommand`) leak or corrupt thread context on exceptional exit, and the nested-call restore pops an outer token (Netty loop can stay `NETWORK`; a nested command can exit `GLOBAL` early). |
| R8 | **P1** | `RegionTransitions.guarded*` swallows `Throwable` including `VirtualMachineError`/`LinkageError`; failed transitions are logged but not counted, and fatal errors do not propagate. |
| R9 | **P1** | `LevelTicks.hasScheduledTick` falls through to vanilla's `allContainers` when the ledger says "no". That is a **concurrent read of a non-concurrent fastutil map** from a worker while the server thread inserts/removes — not merely a conservative answer. |
| R10 | **P1** | `ServerThreadDeferral`: `isServerThread()` returns `true` when no owner is recorded (any thread before the first tick); both queues are unbounded with no admission; the empty-server pause path (`tickServer` returns before `tickChildren`) never drains `PENDING`; `drainAll` drains `PENDING` before `BARRIER_SAFE`, so cross-queue ordering is not FIFO. |
| R11 | **P1** | `RegionEntityRegistry.register` is not stripe-locked (add/remove race leaks a handle in a per-region set); structural merge/split/death do full-map scans under the structure lock and `onRegionSplit` calls `ownerOfChunk` per entity — the dominant structure-lock hold time feeding R3. |
| R12 | **P1** | `TicketStorageMixin` answers `true` optimistically for deferred `addTicket(long,Ticket)`, `removeTicket(long,Ticket)` and `updateChunkForced`; `DistanceManagerTrackingMixin.removePlayer` cancels on the server thread when `playersPerChunk` has no entry. Both change vanilla return/side-effect contracts without a test. |
| R13 | **P1** | Random-tick pass still has uncounted drop paths (edge-not-loaded `continue`, back-pressure local counter, dead-region early return); the new upstream work counter counts only executions. |
| R14 | **P2** | Uncounted/unbalanced diagnostics: `barrierDrained` not bumped by `drainAll`, `ScheduledTickDeferral.deactivate()` clears pending without a counter, `PLAYER_TICK_TARGETS` cleanup only at flush/deactivate, `staged bodies total` over-count [REC]. |
| R15 | **P2** | Stale documentation in the runtime surface: "lock-free ownerOfChunk" (false), dedicated `FabricFolia-Global` thread (removed), mixin counts in docs, `LevelEntityTickMixin` formatting, an `System.out.println` left in `PlayerSliceAtomicityTest`. |

Upstream overlap (must be reconciled by the coordinator before any edit): `EntityMixin`,
`ServerChunkCacheMixin`, `FabricFoliaEngine`, `RegionTickInterceptor`, `FabricFoliaMod`,
`ConfigSchema`, `PatchRegistry`, `fabricfolia.mixins.json` (three new mixins). Details in §11.

---

## 1. Verified 26.2 injection map

Every row was re-derived from the jar this turn. Method descriptors are the ones Mixin must
match; the "handler" column is the current source.

| Mixin | Target descriptor | Sites verified | Handler |
|---|---|---|---|
| `MinecraftServerMixin` | `processPacketsAndTick(Z)V` | 1 | `HEAD` → engine `beginTickPhase` |
| | `tickChildren(Ljava/util/function/BooleanSupplier;)V` | 1 | `HEAD` → `ServerThreadDeferral.drainAll` |
| | `getServerModName()Ljava/lang/String;` | 1 | `HEAD` cancellable brand |
| `ServerConnectionTickMixin` | `ServerConnectionListener.tick()V` → `Connection.tick()V` | exactly 1 call site (offset 54, inside the `synchronized (connections)` loop) | `@Redirect` stage connection body |
| `ServerPlayerTickMixin` | `ServerPlayer.tick()V` → `ServerChunkCache.move(ServerPlayer)V` | exactly 1 (offset 117) | `@Redirect` defer |
| | `ServerPlayer.tick()V` → `ServerPlayerGameMode.tick()V` | exactly 1 (offset 11) | `@Redirect` defer |
| `PacketUtilsMixin` | `ensureRunningOnSameThread(Packet,PacketListener,PacketProcessor)V` → `PacketProcessor.isSameThread()Z` | 1 | `@Redirect` owner check |
| `PacketProcessorMixin` | `scheduleIfPossible(PacketListener,Packet)V` | 1 public funnel | `HEAD` cancellable |
| `ConnectionFlushQueueMixin` | `Connection.flushQueue()V` → `Consumer.accept(Object)V` | exactly 1 (offset 46) | `@Redirect` dispatch |
| `ConnectionPacketDispatchMixin` | `Connection.runOnceConnected(Consumer)V` | 1 | `HEAD`/`RETURN` NETWORK context |
| `LevelEntityTickMixin` | `Level.guardEntityTick(Consumer,T)V` → `Consumer.accept(Object)V` | exactly 1 (offset 2) | `@Redirect` stage entity |
| `LevelBlockEntityTickMixin` | `Level.tickBlockEntities()V` → `TickingBlockEntity.tick()V` | exactly 1 (offset 113; removal prune is *before* the call) | `@Redirect` stage BE |
| `LevelTicksTickMixin` | `LevelTicks.runCollectedTicks(BiConsumer)V` → `BiConsumer.accept(Object,Object)V` | exactly 1 (offset 68) | `@Redirect` stage drain body |
| `LevelScheduleTickMixin` | `LevelTicks.schedule(ScheduledTick)V` | 1 | `HEAD` cancellable |
| `LevelTicksQueryMixin` | `LevelTicks.hasScheduledTick(BlockPos,T)Z` | 1 | `HEAD` cancellable |
| `ServerLevelTickMixin` | `ServerLevel.tick(BooleanSupplier)V` | 1 | `HEAD`/`RETURN` drain flag |
| `ServerChunkCacheTickMixin` | `ServerChunkCache.tickChunks(ProfilerFiller,J)V` → `ChunkMap.forEachBlockTickingChunk(Consumer)V` | 1 | `@Redirect` random ticks |
| `ServerChunkCacheMixin` | `getChunk(II ChunkStatus Z)ChunkAccess`, `blockChanged(BlockPos)V`, `broadcastChangedChunks(ProfilerFiller)V`, `tick(BooleanSupplier,Z)V`, `addTicketAndLoadWithRadius(...)CompletableFuture` | all present | see §7 |
| `TicketStorageMixin` | 10 mutators | all present | `HEAD` cancellable |
| `ChunkMapTrackingMixin` | `move(ServerPlayer)V`, `addEntity(Entity)V`, `removeEntity(Entity)V` | all present | `HEAD` cancellable |
| `DistanceManagerTrackingMixin` | `addPlayer(SectionPos,ServerPlayer)V`, `removePlayer(...)`, `runAllUpdates(ChunkMap)Z` | all present | `HEAD` cancellable |
| `PersistentEntitySectionManagerMixin` | `addEntity(EntityAccess,Z)Z` (private) | 1 funnel | `RETURN` observer |
| `EntityMixin` | `setPosRaw(DDD)V` (final), `setRemoved(RemovalReason)V` (final) | present | `TAIL` observer |
| `EntityTeleportMixin` | `Entity.teleport(TeleportTransition)LEntity;` | present | `HEAD` cancellable |
| `ServerPlayerTeleportMixin` | `ServerPlayer.teleport(TeleportTransition)LServerPlayer;` | present; `ServerPlayer` also has the `Entity`-return bridge | `HEAD` cancellable |
| `LevelMixin` | `Level.<init>` (exactly one constructor) | 1 | `TAIL` random wrap |
| `ServerLevelUnloadMixin` | `ServerLevel.unload(LevelChunk)V` | present | `TAIL` observer |
| `CommandsMixin` | `Commands.performCommand(ParseResults,String)V` | present | 2× `HEAD`, 1× `RETURN` |
| `MinecraftServerGuiMixin` | `showFrameFor(DedicatedServer)LMinecraftServerGui;` → `JFrame.setVisible(Z)V` | present | `@Redirect` + constants |

The register (`fabricfolia.mixins.json`) lists exactly these 26 classes and the source tree
contains exactly these 26 files. `required: true` / `defaultRequire: 1` is consistent with the
`require = 1` markers that are written explicitly. Three mixins added upstream
(`EntityGetterMixin`, `EntitySectionMixin`, `LevelChunkLightEngineMixin`) will make it 29.

### 1.1 Exact tick timeline (verified call order)

`MinecraftServer.runServer` → `processPacketsAndTick(Z)`:

1. `packetProcessor.processQueuedPackets()` **first** (`[BYTECODE]` offsets 29–33).
2. `tickServer(BooleanSupplier)`.
3. `tickServer` has an **empty-pause early branch**: when `emptyTicks >= pauseWhenEmptySeconds*20`
   it calls `autoSave()`, then `tickConnection()`, then `return` **without calling `tickChildren`**
   and without incrementing `tickCount` (`[BYTECODE]` offsets 56–100).
4. Normal branch: `tickCount++`, `tickRateManager.tick()`, `tickChildren(hasTimeLeft)`.
5. `tickChildren`, in order (`[BYTECODE]`): player-list `forEach`, `ServerFunctionManager.tick`,
   clock manager, `forceGameTimeSynchronization`, `updateEffectiveRespawnData`, then for each
   level `ServerLevel.tick`, then **`tickConnection()`** (`ServerConnectionListener.tick`),
   then **`PlayerList.tick()`**, debug subscribers, game-test ticker, registered tickables,
   then per-player `PlayerChunkSender.sendNextChunks` + `resumeFlushing`.
6. Fabric `ServerTickEvents.END_SERVER_TICK` (registered by `FabricFoliaMod`) runs at the end of
   `tickServer`: this is `flushGameplayStaging` → `RegionStageHub.flushStaged` →
   `ScheduledTickDeferral.replayOnServerThread` → `endTickPhase` → `awaitStagedDrain` →
   `recordTickWork`.

Consequences that matter and are easy to get wrong:

- The server-thread **packet processor drain happens before the world passes**, so the respawn
  carve-out (`PERFORM_RESPAWN` left in the processor queue) executes *before* `ServerLevel.tick`
  and before `tickConnection`. That ordering is correct for a replacement player.
- `tickConnection` runs **after every level tick**, so vanilla's entity pass and connection pass
  are already sequential on one thread; the pipeline reproduces that by declaring `PLAYER` last
  in `Slice` and flushing in declaration order (`[CODE]` `RegionStageHub.Slice`).
- The staged `PLAYER` body is dispatched at `END_SERVER_TICK`, i.e. **after** `PlayerList.tick`
  and after `PlayerChunkSender.sendNextChunks`. Vanilla's connection tick is **before** those.
  This is a real intra-tick reordering (see R21 in §5), not covered by the "PLAYER is declared
  last" claim, which only orders slices relative to each other.
- In the empty-pause branch `tickChildren` is skipped, so the `ServerThreadDeferral.drainAll()`
  anchor at `tickChildren` HEAD does not run. Only `beginTickPhase` (processPacketsAndTick HEAD)
  runs. Any deferred work produced during the pause accumulates (R10).

---

## 2. Entity ownership and migration

### 2.1 What is wired

`[CODE]` `PersistentEntitySectionManagerMixin` observes the single add funnel
(`PESM.addEntity(EntityAccess,boolean)`, private, `[BYTECODE]` present) at `RETURN`; it caches
the world tracker plus the packed chunk on the entity (`FabricFoliaEntityHolder`) and calls
`EntityRegionTracker.onEntityAdded`. `[CODE]` `EntityMixin` observes `setPosRaw(DDD)V` and
`setRemoved(RemovalReason)V` at `TAIL`; `setPosRaw` is the innermost position primitive and is
`final` (`[BYTECODE]`), so every server-side position change funnels through the hook.
`RegionEntityRegistry` keeps the authoritative `ConcurrentHashMap<Object,Region>` plus a
16-stripe lock array; `migrate` removes from the old set, adds to the new set and repoints in one
stripe-locked section. This part of the design is sound and the per-chunk cache keeps the common
path to one long compare.

### 2.2 R11 — `register` is not stripe-locked; add/remove can leak

**Location:** `common/.../scheduler/RegionEntityRegistry.register` (line ~171) versus
`unregister` (line ~228) and `migrate` (line ~196).

**Race:** `register` does `ownerByHandle.putIfAbsent(handle, region)` and then
`perRegionEntities.getOrCreateStructural(region).add(handle)` **outside** the stripe lock.
`unregister` takes the stripe lock, removes the map entry and removes from the set. If a
worker's staged body removes the entity (`setRemoved` → `unregister`) between the `putIfAbsent`
and the `set.add` (e.g. a mob spawned by a worker and killed in the same window, or a server
thread spawn racing a worker removal), the handle ends up in a per-region set with **no**
authoritative mapping. It is never retired, never migrated and the set retains a strong
reference. `migrate` can also lose the add: it sees `from == null` and returns false while
`register` completes, pinning the entity to the stale region until its next chunk crossing.

**Root fix:** move `register` under the same stripe lock, re-read `ownerByHandle` inside the
lock, and add only when the mapping is still `region`. Add a `retired`/`registered` counter
balance and a `trackedCount()` vs per-region-set-size invariant exposed in `/folia entities`.

**Regression:** concurrent add/remove hammer (8 threads × 5k entities, half removed while
added) asserting `trackedCount == Σ per-region set sizes` and zero leaked handles after
`close()`. Mirrors the existing queue hammer style; fails deterministically on today's code.

**Runtime:** `/folia entities` tracked count versus a vanilla selector census over 10 minutes
with mob spawning/killing; abort on monotonic drift (already an abort criterion in
`docs/status.md`).

### 2.3 R11b — full-map structural scans under the structure lock

**Location:** `RegionEntityRegistry.onRegionMerged` / `onRegionSplit` / `onRegionDead`
(`[CODE]` lines ~280–345). Each iterates the entire `ownerByHandle`; `onRegionSplit`
additionally calls `regionizer.ownerOfChunk` **per entity** (reentrant on the structure lock)
and `perRegionEntities` per child.

**Evidence:** the record says 61,425 stands in one world (`[REC]` 2026-11-11). A merge/split
therefore performs a 61k-entry scan while `structureLock` is held, and the split path performs
61k reentrant lock acquisitions. Every R3 reader (Netty packet re-home, `PacketUtils`, region
worker `StagedBatch`, chunk registration) waits behind it.

**Root fix:** iterate the donor's own `perRegionEntities` set (which is exactly the donor's
handles) instead of the world map; on split, partition that set by home chunk using a
precomputed child lookup rather than `ownerOfChunk` per entity.

**Regression:** a counting set wrapper proves the merge visits only the donor's handles; a split
of two regions with N registered handles asserts `ownerOfChunk` calls == 0 (or ≤ children) and
the authoritative map is exact.

**Runtime:** JFR lock events during a forced split/merge on a 10k-entity world; success metric
is structure-lock hold time at p99.

### 2.4 Block entities

Block entities cannot migrate; their chunk owns them for life. `[CODE]`
`LevelBlockEntityTickMixin` stages `ticker.tick()` and pins the block position. The redirect is
exact (one call site, offset 113), and vanilla's list maintenance (`pendingBlockEntityTickers`
merge, iterator removal) still runs on the server thread. Two narrow issues:

- A ticker removed **between** staging and execution is still ticked on the worker (the
  `isRemoved()` prune is before the call site). Vanilla would skip it next pass. The class
  comment calls this "a no-op tick"; it is not one — `ticker.tick()` still runs against a
  removed block entity, which vanilla's ordering avoids.
- `TickingBlockEntity.tick()` can call `ServerLevel.blockEvent` (chests, bells, note blocks,
  sensors, pistons) or `Level.setBlock` at a border. That is R4, not a block-entity-specific
  bug.

---

## 3. R1 (P0) — non-player teleports are cancelled twice and never execute

**Files:** `fabric/.../mixin/EntityTeleportMixin.java` (line 43),
`fabric/.../engine/RegionTransitions.java` (`dispatch` ~112, `isSameRegion` ~278),
`common/.../scheduler/RegionEntityRegistry.ownerOfHandle` (~185).

**Vanilla evidence:** `[BYTECODE]` `Entity.teleport(TeleportTransition)Lnet/minecraft/world/entity/Entity;`
exists and is the funnel for `teleportTo(ServerLevel,...)` and `handlePortal()`; the virtual call
dispatches to the `ServerPlayer` override for players (`[REC]`, re-checked: `ServerPlayer` has
both the `ServerPlayer`-returning override and the `Entity` bridge). `Entity.teleportSetPosition`
ends in `setPosRaw`.

**Race/failure scenario (deterministic, not a race):**

1. A non-player entity is ticked on region A (staged entity body). Its body calls
   `teleport(transition)`.
2. `EntityTeleportMixin` asks `isSameRegion(engine, newLevel, cx, cz, self)`. That compares
   `registry.ownerOfHandle(entity)` (still **A**, because the entity has not moved yet) with
   `regionizer.ownerOfChunk(destination)` (**B**) → false.
3. `RegionTransitions.dispatch` writes `IN_FLIGHT[(sourceWorld,id)]`, enqueues the body on B and
   returns true. The mixin cancels the original and returns `self`.
4. The enqueued body runs on B and calls `self.teleport(transition)` → the mixin runs again.
   `isSameRegion` **still** answers false (the registry owner is still A and the body has not
   moved the entity). `dispatch` does `IN_FLIGHT.put(key, now)` and sees the prior stamp from
   step 3, within the 50 ms window → it returns **true as a "duplicate"**.
5. The mixin cancels again and returns `self`. **The vanilla teleport body has now been
   cancelled twice and has never run.** The entity never moves; the transition counter says one
   dispatch happened.

If the queue delay exceeds 50 ms the opposite happens: the second call re-dispatches and the
teleport body eventually runs on B (or, for a cross-dimension transition, can ping-pong). So
behaviour is timing-dependent — the worst kind of defect.

The player mixin does **not** have this bug because it gates re-entry on
`RegionTransitions.isCurrentContextOwner` (thread context), not on the registry.

**Root fix (two parts):**

1. Use context ownership as the re-entry gate in `EntityTeleportMixin`, exactly like
   `ServerPlayerTeleportMixin`: `isCurrentContextOwner(worldKey, destChunkX, destChunkZ)` first;
   keep `isSameRegion` only as a diagnostic counter. This also makes the same-region fast path
   correct when the entity registry is stale.
2. Make `RegionTransitions.dispatch*` distinguish *duplicate of a live dispatch* from
   *duplicate of a dropped dispatch*. `IN_FLIGHT` must store the outcome (ENQUEUED / DROPPED /
   COMPLETED) and the window must not refresh a stamp for a dropped transition. Return an enum
   (`DISPATCHED`, `DUPLICATE_IN_FLIGHT`, `DROPPED`) and have the mixin cancel only on
   `DISPATCHED`/`DUPLICATE_IN_FLIGHT`; on `DROPPED` fall through to the vanilla body.

**Regression test (unit, no MC bootstrap needed if the decision is extracted):** add
`RegionTransitions.resolveEntityTransition(engine, worldKey, chunkX, chunkZ, entity, contextRegion)`
returning the enum, then test: (a) source context A, destination B → DISPATCHED; (b) enter
destination REGION context (B) and call again → INLINE (not dispatched); (c) force an enqueue
failure (closed scheduler) → DROPPED, and a second call within the window is **still** DROPPED,
not DUPLICATE. This fails on today's `isSameRegion` gate.

**Runtime test:** portal transit with a dropped item / cow / boat through a lit portal, and
`/tp` a non-player entity across regions; assert the entity's dimension/position changes, the
transition counter increments exactly once, and no `IN_FLIGHT` entry persists. Repeat both
directions, and cross-dimension.

---

## 4. R2 (P0) — player connection tick can run disconnect/global removal on a worker

**Files:** `ServerConnectionTickMixin` (line 61), `RegionPlayerRouting.stageConnectionTick`
(~180), `RegionStageHub.stagePlayerTick` / `reclaimPinnedPlayerBody` (~529–578),
`ServerLevelTickMixin`/`LevelEntityTickMixin` player capture.

**Vanilla evidence:** `[BYTECODE]` `Connection.tick()V` is:

```
flushQueue()
listener = packetListener
if (listener instanceof TickablePacketListener) listener.tick()
if (!isConnected() && !disconnectionHandled) handleDisconnection()
channel.flush(); tickCount++; ...
```

`Connection.handleDisconnection()` calls `Objects.requireNonNullElseGet(getDisconnectionDetails(), …)`
and then `getPacketListener().onDisconnect(details)` (`[BYTECODE]`). `ServerGamePacketListenerImpl`
has `onDisconnect(DisconnectionDetails)` (`[BYTECODE]`), whose body removes the player from
`PlayerList`, saves it and broadcasts. `ServerConnectionListener.tick()` checks `isConnected()`
**before** calling `Connection.tick()`, but the staged body runs later (at `END_SERVER_TICK`), so
the check is stale.

**Failure scenario A (disconnect lands inside the staged body):** the connection is closed during
the tick (kick command, proxy close, channel inactive, another handler calling `disconnect`).
The body executes on region B, sees `!isConnected()`, calls `handleDisconnection()` →
`PlayerList.remove` and player save **on the worker**, concurrently with the server thread's
`tickChildren` tail (`PlayerList.tick`, `PlayerChunkSender`) and with other regions. This is the
same class of violation as the 2026-09-25 ticket crash, one layer up.

**Failure scenario B (disconnect between the entity stage and the connection stage):** the
entity pass stages the player's `StagedEntityBody` (target pinned, `staged=true`). During
`tickConnection`, `stageConnectionTick` sees `!isConnected()` / `isRemoved()` and returns false,
so the connection body runs vanilla on the server thread — correctly. But the **entity body is
still staged** and executes later in the inter-tick window against a player that
`PlayerList.remove` already removed and saved. `EntityMixin` clears the tracker on `setRemoved`,
but the staged `Runnable` already holds the consumer and entity; nothing cancels it.

**Root fix:**

1. In the PLAYER body wrapper, re-check connection liveness **at execution time** on the worker.
   If the connection is no longer connected or the player is removed, do not run
   `Connection.tick()` on the worker: hand the body to `ServerThreadDeferral.defer(...)` (the
   server thread owns disconnect handling) and count it. Never silently drop it.
2. In `stageConnectionTick`'s false path, reclaim the pinned player target for this tick
   (`reclaimPinnedPlayerBody`) whenever the reason is liveness (not "staging disabled"), so the
   entity body runs inline on the server thread in vanilla order. The existing cross-world
   reclaim is the right pattern; extend it to "connection cannot be staged for this player this
   tick".
3. Add `player connection bodies deferred to server thread (disconnect)` to metrics.

**Regression:** hub-level test using the package-private `stagePlayerTick`: stage ENTITY, then
call the PLAYER stage with a "connection no longer live" predicate; assert the entity body is
reclaimed (`runStandalone` on the calling thread, exactly once, non-REGION context), the PLAYER
body is not enqueued, and `pendingBodies` returns to 0. A second test drives a fake body whose
"liveness" flips after dispatch and asserts the execution-time wrapper defers instead of running.

**Runtime:** bot join, `/kick`, proxy drop, and a 10-minute reconnect loop with player staging
on; watch for `disconnect`-path work executing off the server thread (STRICT thread mode) and for
ghost entity ticks after removal (log entity id + `isRemoved` at execution).

---

## 5. Player connection atomicity, packet routing, respawn/reconnect

### 5.1 What is correct

- `stagePlayerTick` has one world-qualified pin per player per tick, created by the ENTITY
  capture and reused by the PLAYER capture, so both bodies resolve to the same region at flush.
  The new `PlayerSliceAtomicityTest` pins this (5 tests). Cross-world pins are reclaimed and
  counted (`playerTickWorldChanges`).
- `PacketUtilsMixin` now accepts the real processor thread unconditionally and only accepts a
  region worker when it owns the player's chunk and the packet is not a server-thread-only type
  or respawn (`RegionPlayerRouting.canHandlePacketOnCurrentThread`). The 2026-11-17 self-requeue
  fix is present.
- `PERFORM_RESPAWN` is excluded from both the `PacketUtils` owner fast path and the
  `PacketProcessor` region route; it stays in vanilla's queue and is drained by
  `processPacketsAndTick` on the server thread before the world passes. Re-checked the call
  chain: `processPacketsAndTick` → `processQueuedPackets` → ... → `ServerLevel.tick`; correct.
- Container packets (`ContainerClick/ButtonClick/Close/SlotStateChanged`, creative slot,
  `PlayerAction`, `UseItem`, `UseItemOn`) are forced to the server thread. Conservative, and it
  removes the whole class of inventory mutations from workers.

### 5.2 R21 — staged PLAYER bodies run after `PlayerList.tick` and chunk sending

**Evidence:** `[BYTECODE]` `tickChildren` calls `ServerLevel.tick` per level, then
`tickConnection`, then `PlayerList.tick`, then `PlayerChunkSender.sendNextChunks` +
`resumeFlushing`. The PLAYER slice is flushed at `END_SERVER_TICK`, i.e. after all of them.
`ServerChunkCache.move` and `ServerPlayerGameMode.tick` are deferred to the next tick's
`tickChildren` HEAD via `ServerThreadDeferral`, so the chunk view and break progress are one tick
behind vanilla even before the PLAYER body runs.

**Scenario:** a player crosses a chunk border during `doTick` on the worker. `PlayerList.tick`
(server thread, earlier in the same vanilla ordering) may broadcast or sample player state that
vanilla would have observed *after* physics; `PlayerChunkSender` sends chunks based on the old
view. Vanilla also has a one-tick lag for chunk sends, so the visible effect is bounded — but
the claim "vanilla intra-tick order is preserved" is only true slice-to-slice, not relative to
the rest of `tickChildren`.

**Root fix (bounded):** either (a) flush the PLAYER slice at `PlayerList.tick` HEAD instead of
`END_SERVER_TICK` (a new flush anchor; then only PLAYER is staged there, and the entity slice
stays at END), or (b) document the reordering explicitly in `THREADING.md` and
`docs/folia-parity.md` and add a counter for "player body executed after PlayerList.tick". Do not
claim parity until one of these exists. (a) is the cleaner root fix but is a coordinated
change with the core scheduler and needs a dedicated test.

### 5.3 R22 — respawn while a player body is staged

Respawn is drained **before** the entity pass (processor queue first), and
`PlayerList.respawn` builds a new `ServerPlayer` and swaps `connection.player`. The old player is
removed before `ServerLevel.tick`, so the entity pass should no longer select it. The residual
risk is a **direct `PlayerList.respawn` call from a mod** during `ServerLevel.tick` or during the
inter-tick window: the old player's staged entity body then runs after the swap (same shape as
R2-B), and the new player's placement is not region-aware. Keep this documented as a gap (it is
in `THREADING.md`), and make R2's execution-time liveness check the generic net: any staged body
whose entity `isRemoved()` should be skipped/redirected rather than executed.

### 5.4 Packet routing ordering

`PacketProcessorMixin` enqueues re-homed handlers onto the owning region's queue **immediately**
(from the Netty thread), while the connection body is staged and enqueued only at flush. Region
queues are FIFO per region, so a packet re-homed during phase A (`scheduleIfPossible` from the
event loop) lands ahead of the PLAYER batch — i.e. before the connection tick, matching vanilla's
"packets drain at the head of `Connection.tick`". Two caveats:

- The re-home task carries **no position** (`scheduler.enqueue(region, task)`), so it is
  split-orphaned (R6).
- If the owning region is behind, the task can execute a whole tick later than vanilla's
  server-thread queue would; the region queue has no per-player fairness. Counted only as
  `rehomes region=N`. Add a latency metric (enqueue→execute) if this path is enabled in
  production.

---

## 6. Teleport / cross-dimension / mount / passenger / removal

- `ServerPlayerTeleportMixin` is correct in shape: context-owner re-entry gate, destination
  dispatch, `setReturnValue(self)` on success, fall-through to vanilla on refusal. Its documented
  accepted risk remains: the cross-dimension branch mutates global `PlayerList` and the
  destination `ServerLevel` entity manager **on the destination region's worker**. The class
  comment says the alternative (a synchronous hop to the server thread) is forbidden by mandate
  §32; that is a policy decision, not a proof. It should stay documented as a known ownership
  violation until a destination-side server-thread handoff exists.
- `EntityTeleportMixin` is broken by R1.
- Mount/passenger: `[BYTECODE]` `Entity.addPassenger/removePassenger/startRiding/stopRiding/
  ejectPassengers` are ordinary methods; `tickPassenger` recursion is inside the staged
  `guardEntityTick` body. A player riding a boat: the boat is a non-player entity body; the
  player's entity body calls `tickPassenger` for the vehicle? `ServerLevel.tickNonPassenger` /
  `tickPassenger` are separate bodies staged by position. If a vehicle and its passenger are in
  **different regions** (possible at a region border), two workers tick two halves of one
  passenger relationship in the same window. Vanilla ticks a vehicle then its passengers in one
  call chain. The pipeline's position grouping does not keep a vehicle/passenger pair together.
  **This is a real gap, not covered by any test.** Root fix: when staging a body whose entity
  has a vehicle or passengers, pin the whole root-vehicle tree to the root's region for the tick
  (or run the pair inline). Regression: region-border boat with a passenger, assert both bodies
  execute in the same region and in vehicle→passenger order.
- Removal: `setRemoved` at TAIL clears the tracker and unregisters. Because the staged body
  holds the entity, a removal mid-tick does not cancel the body (R2-B). `RegionStageHub.runBody`
  does no `isRemoved()` check.

---

## 7. Chunk lifecycle, tickets, load/unload/save

### 7.1 Ticket deferral and the barrier (what works)

`[CODE]` `TicketStorageMixin` defers all ten mutators from non-server threads; `ChunkMap` and
`DistanceManager` mutators are deferred too. `ServerThreadDeferral.drainAll()` runs at
`tickChildren` HEAD and again at `ServerChunkCache.tick` HEAD. `addTicketAndLoadWithRadius`
returns a **barrier-safe bridge** whose `BarrierSafeEntry` is drained by
`FabricFoliaEngine.pumpChunkExecutors()` *inside* `closeExecutionAndAwait`, which is exactly
what the new `DeferredBarrierDeadlockTest` pins (7 tests). This closes the earlier P0-3 shape for
that one method. Residuals are R10 (admission, ordering, failure), R12 (return semantics) and the
fact that `pumpChunkExecutors` also drains the **whole chunk executor queue** and runs arbitrary
chunk-system callbacks on the server thread while other workers may still be in flight (§9).

### 7.2 `getChunk` policy — local vs upstream conflict (must reconcile)

Local dirty `ServerChunkCacheMixin` routes REGION-context `getChunk` through
`RegionChunkAccessGuard.resolveRegionLookup`, which **throws `IllegalStateException`** for an
absent chunk with `create=true`. Upstream `c850092` replaces the same block with
`WorkerChunkProbe.decide(create, holderPresent, chunkPresent)`:

- `RETURN_CACHED` when present at status,
- `RETURN_NULL` when absent and `!create` (vanilla's own main-path answer),
- `FALL_THROUGH` when `create=true` or present-below-status (vanilla join).

These are incompatible policies. Local's throw **loses the body tick** (`runBody` catches the
`IllegalStateException` as a generic failure, counts `EXCEPTIONS_ISOLATED`, logs, and does not
retry); upstream's join can park the worker on the main-thread executor during quiesce and relies
on the pump. The correct root policy is neither: a REGION worker that needs a **creating** lookup
must not throw away gameplay work and must not block the barrier. Bounce the body:
`RegionChunkAccessGuard` should signal `BOUNCE_TO_SERVER` (a checked result, not an exception),
and `RegionStageHub.runBody` should `ServerThreadDeferral.defer(() -> runStandalone(...))` plus a
counter (`staged bodies bounced for chunk creation`). That preserves the no-loss rule and the
single-writer rule. **Do not apply either side of the conflict until the coordinator decides.**

### 7.3 Load/unload/save

- `ServerLevel.unload(LevelChunk)V` exists (`[BYTECODE]`) and the hook runs at TAIL; chunk
  departure releases ownership after vanilla unregisters tickers/block entities. Correct order.
- `ChunkResidency` is fed by `ChunkRegionization` (load) and `engine.onChunkUnloaded`; it uses a
  **copy-on-write `LongOpenHashSet` cloned on every load/unload** (`[CODE]`). At a 1k-chunk
  working set that is a 1k-element clone per event on the server thread inside chunk
  registration. Never benchmarked. Candidate: striped per-section bitset or a
  `ConcurrentHashMap<Long,Boolean>`; measure first (experiment E5 in the earlier runtime report).
- **Save-time quiescence:** `MinecraftServer.stopServer`/`saveAllChunks` are not hooked. The
  engine's `shutdown(timeout)` is invoked from the mod's shutdown path, but nothing guarantees
  that `PENDING`, `BARRIER_SAFE`, `ScheduledTickDeferral.PENDING`, staged batches and stage
  retries are drained/flushed **before** vanilla writes chunks. `docs/status.md` already lists
  this as PARTIAL. Root fix: a `saveAllChunks` HEAD hook that (a) closes the gate, (b)
  quiesces with the pump, (c) flushes staged + replays scheduled ticks + drains deferrals, and
  (d) records the outcome; fail closed (refuse to save) if quiescence cannot be reached rather
  than saving a torn state. This is a mixin + engine change and needs its own test.
- `ScheduledTickDeferral.deactivate()` clears pending ticks with no counter and no drain; it is
  documented as bounded loss, but the standing rule says the outcome must be visible. Add
  `droppedAtShutdown`.

---

## 8. Scheduled ticks, random ticks, block events, redstone, spawning, POI, raids

### 8.1 Scheduled ticks

`[CODE]` `LevelTicks.schedule` is deferred only when the caller is in REGION context and the
container is a registered world container; the replay happens on the server thread at end of
tick. The drain body is staged by `LevelTicksTickMixin` (exact one call site). Correct in shape.
Two residuals:

- `LevelTicksQueryMixin` (R9): when the ledger says "no", the code **falls through to vanilla**,
  which reads `allContainers` (`Long2ObjectMap`, non-concurrent) from a worker. That is a data
  race, not merely an over/under-approximation. Fix: when a ledger exists, answer from the ledger
  only (return `true` on a record, `false` otherwise) and never touch the vanilla coordinator
  from a worker; a false with a concurrent insert causes a duplicate schedule that vanilla
  dedups, which is the safe direction the class already documents. Add a strict-mode assertion
  that a worker never enters vanilla's `hasScheduledTick` body.
- `RegionPendingTicks.release`/`isPending` call `ownerOfChunk` (structure lock) per drained tick
  and per worker query (`[REC]` earlier report). Store the owning region in the ledger entry and
  resolve from the ledger; fall back only on a miss.

### 8.2 Random ticks

`ServerChunkCacheTickMixin` redirects the single `forEachBlockTickingChunk` call and gives the
collected chunks to `RegionTickInterceptor.flushPass`. Correct seam. Issues:

- The batch is enqueued with **no position** (`scheduler.enqueue(region, batch)`), so a split
  leaves it on the parent (R6).
- Three drop paths remain uncounted: the stale-edge `continue` (the body is re-executed on the
  server thread via `getServer().execute`, which is actually a **bounce**, not a drop — name it
  so), the back-pressure local counter, and the dead-region early return. Upstream `c850092`
  adds `RANDOM_TICK_CHUNKS_EXECUTED` but only counts executions. Add
  `randomTickBouncedEdge`, `randomTickSkippedBackpressure`, `randomTickDroppedDeadRegion`.
- `getServer().execute(...)` from a worker uses the vanilla main-thread executor, which runs in
  the **inter-tick window** (after the flush, gate open) — i.e. the bounced random-tick body runs
  on the server thread concurrently with other workers. `RegionStageHub.runBody` deliberately
  uses `ServerThreadDeferral` for the same situation "because a raw server.execute would pump
  during the inter-tick window". The interceptor violates that stated rule. Fix: use
  `ServerThreadDeferral.defer(...)` (phase-A drain) for the stale-edge bounce, or pin the chunk
  inline.

### 8.3 Block events, redstone, explosions, POI (R4)

`[BYTECODE]` `ServerLevel.blockEvent(BlockPos,Block,int,int)` does
`blockEvents.add(new BlockEventData(...))`; `blockEvents` is a plain
`ObjectLinkedOpenHashSet`, consumed by `runBlockEvents()` on the server thread (which removes
from it, reschedules into `blockEventsToReschedule`, and calls `doBlockEvent`). There is **no
mixin or deferral** for it. A staged body that triggers a block event — chest open/close, bell,
note block, piston extension, sensor/shrieker paths, end-portal frame — mutates that set from a
worker while the server thread may be iterating it. Same shape as the 2026-09-25
`Long2ByteOpenHashMap` crash.

`Level.setBlock`/`setBlockAndUpdate`/`updateNeighborsAt` from a worker also:
- mutate a chunk that may belong to a **different region** (the 3×3 gate checks *loaded*, not
  *owned*);
- mutate `CollectingNeighborUpdater` (a server-thread-owned depth queue) for deep redstone;
- call `updatePOIOnBlockStateChange` → `PoiManager.add/remove`; `PoiManager extends
  SectionStorage` whose `storage` is a plain `Long2ObjectMap` mutated under `loadLock` but read
  without it (`[BYTECODE]` fields/methods) — villager/piglin AI on a worker and a server-thread
  POI write can race;
- can call `WorldGenLevel`/`getChunk(create=true)` (see §7.2).

**Root fix (staged, bounded):**

1. **Ownership gate, not loadedness gate:** before executing a body on a worker, compare
   `regionizer.ownerOfChunk(bodyChunk)` with `ThreadOwnership.current().region()`; if they
   differ (split race) or the body's *target block* is outside the region, bounce. For block
   writes there is no position in the body; the only correct general answer is to keep
   block-mutating bodies on the server thread until a cross-region write router exists. The
   narrower, shippable step is to make the 3×3 gate **owned-by-this-region** (sections, not just
   loaded chunks) so a body cannot legally touch a neighbour region; region merging already
   unifies adjacent sections when needed.
2. **Defer block events:** add a `ServerLevel.blockEvent` HEAD inject that, from a non-server
   thread, captures `(pos, block, type, data)` into a `ServerThreadDeferral` queue replayed on
   the server thread (verbatim, preserving order). Count it. This is the same pattern as
   `TicketStorageMixin` and is the minimal fix for the concrete data race.
3. **POI:** treat `PoiManager` as server-thread-only. Either defer the write
   (`updatePOIOnBlockStateChange` is already a `Level` method — hook it) or prove and document
   the specific lock. Do not assume `SectionStorage` is thread-safe.
4. **Explosions:** already documented as unsupported; keep the documentation and add a
   STRICT-mode violation counter when an explosion starts in REGION context, so it is visible
   rather than silent.

Redstone overall stays on the server thread by default (block-entity/scheduled-tick/random-tick
staging is opt-in), but **scheduled-tick staging alone** is enough to run `tickBlock` bodies on
workers, and those call `setBlock`/`blockEvent`. The gate and the block-event deferral are the
two changes that make that gate honest.

### 8.4 Spawning, raids, global systems

- `NaturalSpawner`/`tickSpawningChunk`, `Raids.tick`, `EnderDragonFight`, weather, time, world
  border, game rules and `ServerLevel.blockEvents` all remain on the server thread inside
  `ServerLevel.tick`/`tickChunks` (`[BYTECODE]` `Raids.tick` at offset 322, `tickBlockEntities`
  at 483). Because workers are quiesced during phase A, there is no concurrent write from
  staged entities to these structures **except** through R4 (a staged body calling into them
  during the inter-tick window). The mobile-entity count used by spawning can be stale by one
  inter-tick window; that is a behavioural drift, not corruption. Document it as a parity gap
  (mob caps can over-spawn briefly after an entity-heavy worker round).

---

## 9. Main-thread handoffs, the barrier, and deadlocks

### 9.1 The quiesce path (current)

`beginTickPhase` (processPacketsAndTick HEAD) → `pool.closeExecutionAndAwait(1000ms,
pumpChunkExecutors)`; on timeout it enters the **unbounded** fail-safe loop (1 s slices,
`reportError` each) until `busyCount()==0`. `pumpChunkExecutors`:

1. drains `ServerThreadDeferral.drainBarrierSafe(128)` — the bridge futures;
2. for every attached level: `source.pollTask()` and `drainChunkExecutorQueue(source)` (reflective
   `BlockableEventLoop.pendingRunnables`, budget 128), catching per-task failures.

### 9.2 R23 — the pump runs arbitrary chunk-system callbacks while other workers are in flight

`closeExecutionAndAwait` closes the gate (no new tasks start) but **does not stop in-flight
bodies**. Running the whole chunk-executor queue and a distance-manager `pollTask()` on the server
thread during that window executes chunk-load completion callbacks (`PESM` entity add, block
entity creation, POI load, ticket updates) concurrently with other workers' body execution. The
worker that is *blocked* on its own future is safe; a **different** worker mid-body is not. This
is exactly the c2me "Async entity load" CME shape that `StageRetry` exists to absorb, so the pump
may be manufacturing the race it retries. It may also be the only way to release a worker parked
on a join, so it cannot simply be removed.

**Root fix:** make the pump **target the parked future**, not the whole queue. Track
`(world, chunkPos, workerThread)` for each barrier-safe/`getChunk` join; the pump then runs only
tasks that complete one of those records (or, at minimum, only `Runnable`s submitted by a
barrier-safe caller). Keep the broad drain as a last resort with a warning counter
(`pumpBroadDrains`). This is an engine change and needs an in-flight attribution metric (the
record notes this as the next lever).

**Regression:** `WorkerPoolGateTest`-style test where worker A is mid-body mutating a fake
structure and worker B is parked on a bridged load; assert the pump runs only the bridge and
does not execute unrelated queued tasks. Today's `drainChunkExecutorQueue` would run the
unrelated task.

### 9.3 R10 — `ServerThreadDeferral` admission, ordering, failure (user-flagged)

`[CODE]` `ServerThreadDeferral`:

- `isServerThread()` is `owner == null || current == owner`. **Before the first
  `noteServerThread`** (called only at `processPacketsAndTick`/`tickChildren` HEAD), every thread
  is treated as the server thread. All the ticket/chunk/distance mixins then execute directly
  from a foreign thread during startup (worldgen worker, an early mod async task). Root fix:
  `return owner != null && Thread.currentThread() == owner;` and note the server thread in
  `FabricFoliaMod`'s engine bootstrap (before any world attaches) in addition to the tick
  anchors.
- `defer` has no admission control; `PENDING` and `BARRIER_SAFE` are unbounded
  `ConcurrentLinkedQueue`s. Producers: 10 ticket mutators, 3 `ChunkMap` mutators, 3
  `DistanceManager` mutators, 2 per staged player per tick (`move`, `gameMode.tick`), one per
  bounced staged body (`RegionStageHub.runBody`), plus `RegionTransitions`/`NetworkDispatch`
  fallbacks. A busy worker loop can outrun the server-thread drain; there is no cap, no
  `RejectedExecution` policy and no metric for queue depth (only `pendingCount`). Root fix:
  bounded admission with an explicit overflow policy that is **not** a silent drop — e.g. when
  the cap is hit, refuse the deferral and make the caller run/retry its own path
  (`defer` returns boolean; the mixin falls back to a conservative synchronous action or
  re-queues through the region scheduler), plus a counter and a one-line warning.
- **Ordering across queues:** `drainAll()` drains `PENDING` then `BARRIER_SAFE`. A
  `removeTicket` deferred after a barrier-safe `addTicketAndLoadWithRadius` can be replayed
  before the add if the bridge survived to `drainAll` (pump didn't run). One logical queue with a
  barrier-safe priority lane that preserves global FIFO is the fix.
- **Pause window:** the `tickServer` empty-pause branch skips `tickChildren`, so `PENDING` is
  never drained while paused (`ServerChunkCache.tick` also does not run). With no players the
  producers are mostly quiet, but a queue that only grows is not acceptable. Drain `PENDING` in
  `beginTickPhase` after quiescence as well (server thread, gate closed — safe), or add a pause
  anchor.
- **Failure semantics (R5):** `runDeferred` catches `VirtualMachineError|ThreadDeath|LinkageError`
  and rethrows; every other `Throwable` is logged, counted and swallowed. A deferred
  `TicketStorage.addTicket` that throws after partially mutating a list, or a
  `ServerChunkCache.move` that throws mid-view-update, leaves partial state and the tick
  proceeds. Vanilla would have propagated a `ReportedException` and produced a crash report. The
  rule is "fix at the root, never suppress": rethrow non-transient failures (after naming the
  deferred operation and context) so vanilla's crash semantics apply, and keep isolation only
  for a specifically classified transient (the CME shape already handled by `StageRetry`). At
  minimum, aggregate the failure into an ERROR with the operation name and fail the tick.

### 9.4 R7 — context restore leaks (RETURN-only) and nested-token corruption

`[CODE]`:

- `ServerLevelTickMixin` sets the drain flag at HEAD and clears at `RETURN`. A throwing
  `ServerLevel.tick` leaves `DRAIN_STAGING=TRUE` on the server thread; every later `LevelTicks`
  drain for a registered container is then treated as staged. (Vanilla usually crash-stops on a
  level-tick throw, so the blast radius is small, but the same shape matters for any mod that
  catches and continues.)
- `ConnectionPacketDispatchMixin` pushes NETWORK at HEAD, restores at `RETURN`. A handler that
  throws leaves the Netty event loop permanently `NETWORK`; and a **nested** `runOnceConnected`
  (handler calls it again) has an inner RETURN that sees `NETWORK` and exits to `UNKNOWN`,
  stripping the outer call's context.
- `CommandsMixin` pushes a GLOBAL token only when the thread is not already GLOBAL and not named
  `Server thread`, but `restoreConsoleContext` always `pollLast()`s. On a thread that was
  `UNKNOWN` and runs a nested `performCommand`, the outer pushes, the inner sees GLOBAL and does
  not push, and the inner RETURN **pops the outer token**; the rest of the outer body runs as
  UNKNOWN and the outer RETURN pops nothing.

**Root fix:** a per-invocation token stack: every HEAD pushes a token (even the identity token)
and every exit (RETURN **and** THROW) pops exactly one. Mixin supports `@At("THROW")` alongside
`@At("RETURN")`; alternatively wrap with `@WrapOperation`/`@WrapWithCondition` or use a
single `@Inject(at=HEAD, cancellable)` plus a try/finally inside the handler around a
`@Redirect`. For the two state-holder classes (`ScheduledTickDeferral`,
`ThreadOwnership`), make the token an explicit value the handler stores in a ThreadLocal stack.

**Regression (state-holder level, no MC needed):** simulate push/push/pop/pop and push/throw/pop
on `ScheduledTickDeferral` and `ThreadOwnership` and assert the original context is restored;
add a `nestedCommandRestoresOuterToken` test against the extracted token logic.

### 9.5 R6 — split-orphaned positionless tasks

`[CODE]` `RegionTaskQueue.partitionOnSplit` skips `Entry.NO_POSITION`;
`RegionScheduler.enqueue(Region,Runnable)` is the positionless overload. Positionless producers:

| Producer | Current call |
|---|---|
| `RegionPlayerRouting.schedulePacketToRegion` (~326) | `scheduler.enqueue(region, task)` |
| `RegionTransitions.dispatch` / `dispatchKeyed` (~154/211) | `scheduler.enqueue(owner, guarded(...))` |
| `CommandsMixin` region re-dispatch (~124) | `scheduler.enqueue(region, ...)` |
| `RegionTickInterceptor.flushPass` (~236) | `scheduler.enqueue(region, batch)` |
| `NetworkDispatch.runOnOwner` / `runForConnection` | `scheduler.enqueue(region, task)` |

After a split, these stay on the parent and execute under the parent's REGION context even
though the body's chunk now belongs to a child. `StagedBatch` carries a position and
re-resolves the owner at execution (`[CODE]` `StagedBatch.run`), so the staged gameplay slices
are the one path that is correct. Fix: pass `packChunkPos(...)` at every producer that has a
position, and add an execution-time check in `RegionStageHub.runBody`/`StagedBatch` that compares
the current `ThreadOwnership.current().region()` with `regionizer.ownerOfChunk(bodyChunk)`; on
mismatch re-dispatch instead of running, and count `bodies re-owned at execution`.

**Regression:** `RegionSplitRehomeTest` — stage a body in a chunk, force a split that moves the
chunk to a child, assert the body executes with the child as current region; today's positionless
packet/teleport/random-tick tasks stay on the parent. Extend
`RegionTaskQueueConcurrencyTest` to assert a StagedBatch-shaped entry with a position moves.

---

## 10. R3 (P0, user-flagged) — `ownerOfChunk` acquires the structure lock on the network path

**Location:** `common/.../region/WorldRegionizer.ownerOfChunk` (line 619) —
`structureLock.lock()` / `sectionOwners.get(...)` / `unlock()`. `sectionOwners` is a plain
`HashMap` (`[CODE]` line 97). `[CODE]` `RegionPlayerRouting.isOwnerRegion` line 369 calls it and
the comment at line 342 says "the same lock-free `ownerOfChunk` read the schedulers use — the
regionizer's structure lock is not taken". **That comment is false.**

**Call sites that can be on a network thread (Netty event loop):**

| Path | Call |
|---|---|
| `PacketUtils.ensureRunningOnSameThread` → `PacketUtilsMixin` → `RegionPlayerRouting.isOwnerRegion` | `ownerOfChunk` |
| `PacketProcessor.scheduleIfPossible` → `PacketProcessorMixin` → `RegionPlayerRouting.schedulePacketToRegion` | `ownerOfChunk` |
| `Connection.flushQueue` → `ConnectionFlushQueueMixin` → `NetworkDispatch.runForConnection` → `RegionPlayerRouting.routeFor` + `ownerOfChunk` | `ownerOfChunk` |
| `NetworkDispatch.runOnOwner` | `ownerOfChunk` |
| `CommandsMixin` entity-command re-dispatch | `ownerOfChunk` |
| `RegionScheduler.enqueue` phase-2 | `regionizer.underStructureLock(region::isDead)` — **a second lock acquisition on the same network path** |

Region-worker call sites (blocking a worker feeds the quiesce tail): `StagedBatch.run`,
`EntityRegionTracker.onEntityMoved`, `RegionTransitions`, `RegionPendingTicks`.
Server-thread call sites: flush `regionFor` (tick-cached), chunk registration.

**Hold time:** `addChunk`, `completeTick` (merge), split, kill and `removeDeadSections` all hold
`structureLock`. With R11b the hold includes an O(all entities) scan; the record is 61k entities.
A Netty event loop that blocks there stops reading/writing **every connection on that loop** —
keepalive and packet latency, and under a long merge, read timeouts. It is also a priority
inversion: a game-structure operation blocks network IO.

**Root fix (lock-free ownership snapshot):**

1. Keep `sectionOwners` as the authoritative write-side index under `structureLock`, but publish
   it as a **`ConcurrentHashMap<Long, Region>`** (or an immutable copy-on-write map, or a striped
   array) updated in the same critical sections that mutate ownership. `ownerOfChunk` becomes a
   volatile read + map get, no lock. A reader may observe the pre- or post-transition owner; both
   are valid Region objects and every caller already tolerates a stale/dead owner (enqueue
   phase 2, `RegionTransitions` drop, `isOwnerRegion` false). Filter `region.isDead()` (a
   `volatile RegionState`, `[CODE]` `Region.java:52/302`) at read to avoid handing out dead
   regions.
2. Remove the second lock from `RegionScheduler.enqueue`: the phase-2 death re-check needs an
   ordering guarantee against queue re-home/drop. Give each region a small lock (or a
   `StampedLock`/atomic state + pending counter) so enqueue and death serialize **per region**
   instead of on the global structure lock. If a volatile `isDead()` read is used instead, the
   death path must set `DEAD` **before** dropping/re-homing the queue and the enqueue must
   re-check *after* adding (the classic add→check→remove protocol), with the order verified in a
   test. Do not guess this ordering; pin it with a deterministic interleaving test.
3. The fallback linear scan (`ownerLookupIndex == false`) still needs the lock; either keep it
   (and document that turning off the index restores the lock) or scan a volatile immutable
   `Region[]` snapshot.

**Deterministic contention test (common module, core owner):**

```
thread A: regionizer.underStructureLock(() -> { latch.countDown(); release.await(); })
thread B: latch.await(); long t0 = nanoTime();
          regionizer.ownerOfChunk(0,0);
          assert nanoTime()-t0 < 5ms   // fails today: blocks until release
```
Plus a stress test: 1 writer doing addChunk/removeChunk/completeTick for 2 s while 4 readers call
`ownerOfChunk`; report p50/p99/max and assert the reader never parks (thread state RUNNABLE, no
`ReentrantLock` frame) and that the returned owner is always either null or a live region.
Measure the **baseline first** (that is the `AGENTS.md` §4 protocol) and record p99 before/after.

**Runtime test:** JFR 60 s with `jdk.JavaMonitorEnter`/lock events on a multi-region world while
forcing merges/splits (forceload add/remove at region edges); success metric = zero Netty
threads blocked in `WorldRegionizer.ownerOfChunk` for > 1 ms, and unchanged TPS.

---

## 11. Upstream overlap and reconciliation (read-only inspection)

`git log origin/main` shows four commits ahead of local `94976ef`:

| Commit | Touches (runtime-relevant) |
|---|---|
| `a42e96a` | `EntityMixin`, `OfemCollision`, `FabricFoliaMod`, `ConfigSchema`, `FoliaConfig`, `PatchRegistry`, `fabricfolia.mixins.json` (+`EntityGetterMixin`, `EntitySectionMixin`, `LevelChunkLightEngineMixin`), `LightEngineDeferral`, `ConcurrentClassInstanceMultiMap`, `HardHitBoxEntityIndex` |
| `e98af65` | FREEBUFF only |
| `c850092` | `ServerChunkCacheMixin`, `RegionTickInterceptor`, `FabricFoliaEngine` (one metrics line), `WorkerChunkProbe`, metrics + tests |
| `c2fbe21` | FREEBUFF only |

Local dirty files that overlap: `FabricFoliaEngine`, `ServerChunkCacheMixin`,
`RegionPlayerRouting`, `RegionStageHub`, `ServerThreadDeferral`, `StallProbe`,
`DistanceManagerTrackingMixin`, `LevelEntityTickMixin`, `PacketUtilsMixin`, `FabricFoliaMod`,
`ConfigSchema`, plus untracked `RegionChunkAccessGuard` (+test),
`DeferredBarrierDeadlockTest`, `PlayerSliceAtomicityTest`.

Concrete semantic conflicts the coordinator must resolve:

1. **`getChunk` on a REGION worker** — local throws for `create=true` absent via
   `RegionChunkAccessGuard`; upstream returns null for `!create` and falls through to vanilla
   join for `create=true` via `WorkerChunkProbe`. §7.2 proposes the third option (bounce). Do not
   merge either side blindly.
2. **Collision layer** — upstream changes the OFEM trigger from "small movement" to "fast
   diagonal", adds `patches.override-vanilla`, and adds three mixins. The local tree's
   `EntityMixin` is upstream-owned in practice; my R1 fix is in `EntityTeleportMixin`, which
   upstream does not touch, so it will apply cleanly after reconciliation.
3. **`FabricFoliaEngine` metrics line** — upstream adds `WorkerChunkProbe.absentNulls()`; local
   dirty has the same area. Reconcile by feature, not by hunk.
4. **`RegionTickInterceptor`** — upstream adds `RANDOM_TICK_CHUNKS_EXECUTED` and changes the
   bounce/executed accounting; local dirty may not have it. My §8.2 counters should be layered
   on the upstream version.
5. **`ConfigSchema`/`PatchRegistry`** — upstream adds `patches.override-vanilla` and the new
   patch ids; local dirty has other keys. Any new gate I propose must be added after this merge.

**Files I can own after handoff (no overlap with upstream or local dirty):**
`fabric/.../mixin/EntityTeleportMixin.java`, `ServerLevelTickMixin.java`,
`ConnectionPacketDispatchMixin.java`, `CommandsMixin.java`, `LevelTicksQueryMixin.java`,
`RegionTransitions.java` (not dirty, not upstream — but its API changes affect tests), plus new
`fabric/src/test/...` classes. `ServerThreadDeferral.java` is dirty locally and must be handed
explicitly. I will not touch `FabricFoliaEngine`, `ServerChunkCacheMixin`, `EntityMixin`,
`LevelEntityTickMixin`, `RegionStageHub`, `RegionPlayerRouting`, docs, `FREEBUFF.md`, common
code, or QA files without a written handoff.

---

## 12. Mixin composition and runtime verification

**Composition**

- `LevelEntityTickMixin`, `LevelBlockEntityTickMixin` and `LevelMixin` all target `Level`; no two
  redirect the same instruction. `EntityMixin` and `EntityTeleportMixin` target `Entity`; no
  overlap (`setPosRaw`/`setRemoved` vs `teleport`). `ServerChunkCacheMixin` and
  `ServerChunkCacheTickMixin` target different methods of the same class; the former's `tick`
  HEAD and the latter's `tickChunks` redirect coexist.
- Exclusive redirects are the compatibility cliff: `ServerChunkCacheTickMixin`'s
  `forEachBlockTickingChunk`, `LevelEntityTickMixin`'s `Consumer.accept`, `LevelTicksTickMixin`'s
  `BiConsumer.accept`, `ConnectionFlushQueueMixin`'s `Consumer.accept`, `ServerConnectionTickMixin`'s
  `Connection.tick`, `ServerPlayerTickMixin`'s two calls, `PacketUtilsMixin`'s `isSameThread`.
  Any other mod redirecting the same call site will conflict at apply time (loud, not silent),
  which is the intended failure mode. This must be stated per-mixin in `MIXINS.md` (the
  compatibility specialist owns that doc).
- `MinecraftServerGuiMixin` is client-GUI only but the mod is `"environment": "server"`; the class
  still loads on a dedicated server (Swing is present), so it applies. No runtime effect on a
  headless server.

**Runtime verification checklist (a boot is still required; none was run this turn):**

1. `Done (` in the log and `SUCCESS: Fabric Folia is ready`, with **zero** mixin apply errors.
2. `[Mixin]` audit: every one of the 26 (29 after upstream) classes appears in the transformer
   log at least once, and `EntityMixin`'s new/upstream handlers apply with the expected count.
3. `/folia metrics` renders every line added by this report's fixes (no NPE in the command).
4. RCON `stop` → `All dimensions are saved`, `Engine shut down cleanly`; ports 25901/25902
   released; `./gradlew --stop`; process sweep leaves only foreign PIDs.
5. STRICT thread mode in a disposable world for the R1/R2/R4 paths (portal with a cow, bot
   disconnect, chest open at a region border) — violations must appear as counted
   `ViolationReporter` lines, not crashes.

**Verification I could not do:** no build and no boot (explicit instruction not to run a full
Gradle build while other sessions build; the checkout is dirty and behind). Descriptor checks
were done with `javap` only. Every "does not execute / executes on the wrong thread" claim is
from source + bytecode reasoning, not from a live trace. R1 and R2 should be proven with a
temporary live counter/log before any fix is called verified.

---

## 13. Mod-compatibility consequences of the runtime findings

- R3 (structure lock on Netty threads) affects **every** mod: any packet re-home can stall a
  netty loop behind a region merge. It is not an optimization-mod interaction.
- R1 (teleport) affects any mod that teleports non-player entities: portals, ender pearls for
  non-players, shulker/teleport AI, `/tp`, datapack `teleport`. This is a gameplay-breaking
  defect in staged mode, not a compatibility edge.
- R2 (disconnect/respawn on workers) affects any mod with a `PlayerEvent`/disconnect listener:
  the listener runs on a region worker instead of the server thread.
- R4 (block events/POI/neighbour writes) affects redstone/chest/farm mods and any mod that
  assumes `level.setBlock` is server-thread-only. The default profile (all gameplay staging off)
  is unaffected; `general.regionized-gameplay` + entity/BE/scheduled-tick patches are affected.
- R9 (worker `hasScheduledTick` fallthrough) affects redstone/observer-heavy mods; the read is
  also a data race, so "it only over-reports" is not a safe claim.
- `ServerChunkCacheTickMixin`'s exclusive redirect conflicts structurally with any mod that also
  redirects `forEachBlockTickingChunk` (C2ME's random-tick path is suppressed by config today;
  Lithium and ScalableLux do not touch that call site, per `COMPATIBILITY.md`).
- No blanket compatibility claim is made. `docs/compatibility/` notes remain scoped to their
  exact versions and were not re-run.

---

## 14. Test plan (what I would run, in order, once work is handed off)

**Unit/integration (fabric module, no live server):**

1. `EntityTeleportReentryTest` — R1 enum semantics, inline on destination context, dropped
   transitions do not become "duplicates".
2. `PlayerConnectionLivenessTest` — R2 reclaim/defer/execute-once; `pendingBodies==0`.
3. `ServerThreadDeferralAdmissionTest` — R10: null owner is "not server", bounded admission
   policy, FIFO across PENDING/BARRIER_SAFE, failure propagation (non-transient rethrows),
   pause-path drain is exercised via `beginTickPhase`.
4. `ContextTokenRestoreTest` — R7 nested push/pop and throw-path restore for
   `ScheduledTickDeferral` and `ThreadOwnership`.
5. `RegionSplitRehomeTest` — R6 positionless producers (packet/teleport/random-tick/command).
6. `TransitionFailureTest` — R8 fatal propagation and failure counter.
7. `StructureLockContentionTest` (common, core owner) — R3 deterministic blocked-reader test and
   reader-latency stress.
8. `BlockEventDeferralTest` — R4 deferral ordering, no drop on shutdown.
9. `TickQueryLedgerTest` — R9 ledger-only answer, never enters vanilla from a worker.
10. `ChunkCreationBounceTest` — §7.2 policy: creating lookup from a worker bounces, body runs
    exactly once, counter increments.

**Live (dev server, ports 25901/25902, one bot):**

1. Boot + mixin audit + `/folia metrics` render.
2. Portal transit with a cow, a dropped item and a boat (R1); `/tp` a cow across regions.
3. Bot `/kick` and proxy close (R2); reconnect loop; STRICT mode violation scan.
4. Chest open/close, note block, bell, piston, observer/redstone clock at a region border (R4).
5. Forceload add/remove at region edges to force merge/split while a packet flood runs (R3/R6);
   JFR lock events; Netty thread block detector.
6. Scheduled-tick farm (sugar cane/water) and a villager/PoiManager workload with entity + BE
   staging on (R9/POI).
7. Save/restart: RCON `stop`, restart, verify entities/blocks/tickets survive and no deferred
   work is lost (save-time quiescence).

Each fix should land with its unit test, and the live step for that fix should be rerun on the
exact tree. Exit status must be captured explicitly (`set -o pipefail` or `$?`), and no full
Gradle build should run concurrently with another session's build.

---

## 15. What this audit did not do

- No source, test, doc, config, Jenkins, or `FREEBUFF.md` change. No build, no boot, no RCON,
  no JFR. No commit/push/stash/reset; the dirty tree and the four upstream commits were left
  untouched.
- No claim that any finding is fixed. R1/R2/R4 are reasoning from source + verified bytecode;
  they need a temporary live counter or a unit-level extraction before they can be closed.
- No re-derivation of the historical performance numbers in `FREEBUFF.md` §6; they are quoted
  as `[REC]` only where they inform a risk.
- No review of the GPU subsystem, command formatting, config parser, Jenkins, or docs beyond what
  the runtime claims required.
- No compatibility matrix run; `docs/compatibility/` remains scoped evidence, not certification.
- This checkout is four commits behind `origin/main`; all findings are against local `94976ef`
  plus the dirty tree. The upstream `getChunk` and collision changes may alter §7.2 and §11's
  collision note after reconciliation.
