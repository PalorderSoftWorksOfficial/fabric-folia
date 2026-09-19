# Fabric Folia — Mixin Register

Every mixin Fabric Folia ships, why it exists, and the mandatory analysis
that gates any future one. Fabric Folia is deliberately mixin-minimal: the
intercept seam is a single call site, and everything else is done through
Fabric API events (lifecycle, commands).

## Current inventory (13 mixins)

### LevelMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `LevelMixin` |
| Purpose | world attach hooks: regionizer registration for each loaded `ServerLevel` |
| Effect when disarmed | no regionization for the level; engine stays out of the way |

### ServerChunkCacheTickMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `ServerChunkCacheTickMixin` |
| Purpose | chunk-system tick observation feeding regionizer activity (chunk load/unload → `addChunk`/`removeChunk`) |
| Effect when disarmed | regionizer sees no chunk activity; regions never form |

### PersistentEntitySectionManagerMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `PersistentEntitySectionManagerMixin` |
| Purpose | entity section visibility drives regionizer section registration (the spatial basis of regions) |
| Effect when disarmed | regions cannot track entity sections |

### EntityMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `EntityMixin` |
| Purpose | per-entity ownership tracking: registers entities with the region entity registry and records their section so migration across region boundaries is detectable |
| Effect when disarmed | no entity ownership; entity scheduling falls back to global dispatch |

### LevelTicksQueryMixin

**Target:** `net.minecraft.world.ticks.LevelTicks#hasScheduledTick` (HEAD, cancellable)

**Why:** block behaviors that run on region workers — observers, tripwire,
targets, lightning rods — ask `level.getBlockTicks().hasScheduledTick(...)`
before re-scheduling. Vanilla's coordinator is server-thread state; a worker
read is a data race. When the caller is a region worker and the container is
a staged world's container, the question is answered from the region-owned
`RegionPendingTicks` ledger instead of the shared map. The answer is
conservative in the safe direction: a spurious "yes" only skips a duplicate
schedule vanilla would dedup (`LevelChunkTicks.schedule` is a set-add); it
can never falsely report "no" for a tick that will actually run. All other
callers (server thread, unregistered containers) run vanilla unchanged.

### LevelTicksTickMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` -> `LevelTicksTickMixin` |
| Target | `LevelTicks.runCollectedTicks` — the `BiConsumer.accept(BlockPos, T)` call site (`@Redirect` of the interface invocation) |
| Transformation | when the drain window is active for a registered container (see `ScheduledTickDeferral.isDrainStaged`), stages the drained body — vanilla's own `ServerLevel::tickBlock`/`tickFluid` consumer with the already-drained `ScheduledTick` — into the `SCHEDULED_TICK` slice; otherwise calls through |
| Why this seam | the entire drain machinery (collection, `DRAIN_ORDER`/`subTickOrder` ordering, dedup set, `alreadyRunThisTick`, cleanup) stays server-thread vanilla; only the body's execution point moves to the owning region's worker |
| Replay safety | a replayed deferral is a server-thread schedule drained by vanilla, so it flows through this redirect exactly once; anything the worker body re-schedules is new work captured by the deferral mixin — one execution per tick, one hop per worker-written tick, no loop |
| Effect when disarmed | byte-for-byte vanilla drain on the server thread |

### ServerLevelTickMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` -> `ServerLevelTickMixin` |
| Target | `ServerLevel.tick(BooleanSupplier)` head + return (`@Inject`) |
| Transformation | raises/clears the drain-staging thread-local (`ScheduledTickDeferral.beginDrainStaging`) when this level's staging is active, so the drain redirect in `LevelTicksTickMixin` stages only staged worlds' drains — other mods' LevelTicks and unattached worlds drain as vanilla |
| Effect when disarmed | no flag; the redirect never stages |

### ConnectionPacketDispatchMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` -> `ConnectionPacketDispatchMixin` |
| Target | `Connection.runOnceConnected(Consumer)` head + return (`@Inject`) |
| Transformation | wraps the execution of each submitted connection task: on a non-server thread (a Netty event loop) the thread is classified NETWORK for the task's duration and restored afterwards — a bounded enter/exit, not a sticky tag (the earlier flushQueue-based draft mislabeled the server thread, which also drains this queue via `tick`; that defect never shipped — it was caught on boot review) |
| Effect when disarmed | event-loop threads report UNKNOWN |

### ConnectionFlushQueueMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` -> `ConnectionFlushQueueMixin` |
| Target | `Connection.flushQueue` — the `Consumer.accept(Connection)` call site (`@Redirect` of the interface invocation) |
| Transformation | routes every drained pending action through `NetworkDispatch.runOnOwner`: server-thread drains (the normal path) execute inline exactly where vanilla would have them; event-loop-classified drains hop to the global context instead of mutating game state on the loop |
| Why the queue, not 61 handlers | `ensureRunningOnSameThread` already re-homes tick-sensitive handlers inside themselves; the pending-action queue is the single point where ANY submitted task can cross threads, so one redirect covers every packet path |
| Effect when disarmed | actions execute exactly as vanilla drains them |

### MinecraftServerGuiMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `MinecraftServerGuiMixin` |
| Target | `MinecraftServerGui.showFrameFor(DedicatedServer)` — `@Redirect` of the single `JFrame.setVisible(Z)` call |
| Transformation | applies the frame icon from the packaged `logo.png` (jar root) via `setIconImage`, then forwards to the real `setVisible` — the Fabric-native equivalent of Folia's upstream "use Folia logo" change (vanilla sets no icon: javap-verified zero `setIconImage`/`ImageIO` references in the class) |
| Effect when armed | the dedicated-server GUI window displays the Fabric-Folia logo before the window paints |
| Effect when disarmed / headless / missing resource | `ServerGuiIcon.applyTo` returns false and the frame keeps the platform default; the call is inside the redirect, so nothing else about frame setup changes |
| Why redirect (not inject) | the frame is a method local in `showFrameFor`; an `@Inject` callback receives only the host method's `DedicatedServer` parameter and cannot reach the frame (the live harness caught exactly this as an InvalidInjectionException on first boot) — the `setVisible` call site is the only point where the frame reference exists |
| Failure isolation | `applyTo` catches its own runtime failures and logs them; the GUI is a convenience surface, never a startup dependency |

### LevelEntityTickMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `LevelEntityTickMixin` |
| Target | `ServerLevel` entity tick loop — `@Redirect` of the `guardEntityTick` invocation for non-player entities |
| Transformation | when gameplay staging is active for this level, hands the vanilla body (the exact lambda, unmodified) to `RegionStageHub.stage(ENTITY, ...)` instead of running it inline; otherwise forwards to the real method |
| Why this seam | the whole `tickNonPassenger` tree flows through `guardEntityTick` — tick-count increment, tick body, passenger recursion — so zero vanilla logic is replicated; the `inEntityTickingRange`/vehicle-crumble gating runs before the seam and stays vanilla |
| Effect when disarmed | byte-for-byte vanilla entity ticking on the server thread |

### LevelBlockEntityTickMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `LevelBlockEntityTickMixin` |
| Target | `Level.tickBlockEntities` — the `TickingBlockEntity.tick()` call site (`@Redirect` of the interface invocation) |
| Transformation | when staging is active, stages the ticker body with the block entity's chunk position; otherwise calls through |
| Why this seam | cancelling `tickBlockEntities` wholesale would strand the `pendingBlockEntityTickers` merge/prune discipline; the per-tick call site leaves all list management vanilla |
| Effect when disarmed | vanilla block-entity ticking on the server thread |

### LevelScheduleTickMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `LevelScheduleTickMixin` |
| Target | `LevelTicks.schedule(...)` head (`@Inject`, cancellable) — the funnel for `scheduleTick`/`scheduleInMidTick` |
| Transformation | when staging is active, records the already-constructed `ScheduledTick` into `ScheduledTickDeferral` (keyed by container identity) and cancels the vanilla insert; the drain's own server-thread re-schedules pass through untouched |
| Why | region workers executing staged scheduled ticks must not mutate the container's staged-tick tree concurrently with the server thread; deferral keeps every container mutation on the server thread |
| Effect when disarmed | vanilla tick scheduling, zero deferral |

### EntityTeleportMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `EntityTeleportMixin` |
| Target | `Entity.teleport(TeleportTransition)` head (`@Inject`, cancellable) |
| Transformation | when the caller is a region worker and the destination resolves to another region/world, routes the transition through `RegionTransitions.dispatch` (destination-context execution) instead of mutating state from the wrong context |
| Effect when disarmed / engine down | falls through to vanilla teleport |

### ServerChunkCacheMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `ServerChunkCacheMixin` |
| Target | `ServerChunkCache.blockChanged(ChunkHolder)` head (`@Inject`, cancellable) |
| Transformation | when the caller is a region worker, queues the holder into a per-level concurrent pending set (ChunkBroadcastDeferral) and cancels the direct `chunkHoldersToBroadcast.add`; the server thread drains the set at `broadcastChangedChunks` HEAD, before vanilla iterates the non-concurrent set |
| Why | vanilla assumes block changes happen only on the server thread; our worker-context staged bodies call `setBlock`, and a raw add during the server thread's iteration corrupts the fastutil set (`wrapped is null` crash, reproduced live) |
| Effect when disarmed / engine down | vanilla's direct add — correct on a single-threaded server, racy only under staged worker execution |

### ServerLevelUnloadMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `ServerLevelUnloadMixin` |
| Target | `ServerLevel.unload(LevelChunk)` TAIL (`@Inject`) |
| Transformation | routes the departing chunk position to the world regionizer's `removeChunk`, so sections can empty, dead sections accumulate, and the tick-end split path is reachable from real chunk churn |
| Why this seam | `ServerLevel.unload` is vanilla's definitive per-chunk departure point: called exactly once per holder from `ChunkMap`'s unload lambda, after save, on the server thread (verified against the 26.2 jar bytecode) |
| Ordering safety | TAIL inject: vanilla has already unregistered the chunk's tick containers and block entities — region ownership release is the last step |
| Effect when disarmed / engine down | no-op; sections stay owned forever (the defect this hook fixed: merges/splits were unreachable live) |
