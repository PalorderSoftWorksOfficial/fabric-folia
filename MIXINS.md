# Fabric Folia — Mixin Register

Every mixin Fabric Folia ships, why it exists, and the mandatory analysis
that gates any future one. Fabric Folia is deliberately mixin-minimal: the
intercept seam is a single call site, and everything else is done through
Fabric API events (lifecycle, commands).

## Current inventory (26 mixins registered; 23 documented below — CommandsMixin, MinecraftServerMixin, ServerPlayerTickMixin remain to be documented)

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

### ServerPlayerTeleportMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `ServerPlayerTeleportMixin` |
| Target | `ServerPlayer.teleport(TeleportTransition)` head (`@Inject`, cancellable, covariant-return descriptor) |
| Why this seam | `ServerPlayer` OVERRIDES `teleport(TeleportTransition)` (26.2 bytecode), so `EntityTeleportMixin` never sees player transitions: portals reach it via `Entity.handlePortal`'s virtual dispatch, mods/commands call it directly, and `ServerPlayer.teleportTo(ServerLevel,...)` delegates to it through `Player.teleportTo`. With player-path staging on, the connection body drives `handlePortal` ON a region worker, so the override's cross-dimension branch (level removal/addition, `PlayerList` broadcasts, profiler) ran from the wrong context |
| Transformation | on a REGION-context caller whose current region does not own the destination chunk: records the player-transition metric, dispatches the whole vanilla body through `RegionTransitions.dispatchKeyed` (destination region's queue, global scheduler for unowned destinations) and cancels the original call with vanilla's own return convention |
| Recursion safety | the dispatched body re-invokes `teleport`; that re-entry hits `RegionTransitions.isCurrentContextOwner` (current REGION context == destination owner) and runs inline — the recursion breaker and the same-dimension fast path are the same check |
| Accepted risk | the cross-dimension branch's global `PlayerList` mutations execute on the destination region's worker, not the server thread (vanilla itself defers cross-dimension moves inside its tick loop; a synchronous server-thread hop would violate the no-cross-context-wait mandate) |
| Effect when disarmed / engine down / non-region caller | falls through to vanilla teleport, byte-for-byte |

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

### ServerConnectionTickMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `ServerConnectionTickMixin` |
| Target | `ServerConnectionListener.tick()` — `@Redirect` of the per-connection `Connection.tick()` call |
| Transformation | when player-path staging is on, hands the per-connection body (packet drain, `SGPLI.tick` → `doTick` physics, outbound flush) to `RegionPlayerRouting.stageConnectionTick`, which stages it into the hub's PLAYER slice; otherwise forwards to the real method |
| Why this seam | the physics chain is driven from the connection tick (`doTick` → `Player.tick`), not the entity pass — staging the whole connection body keeps packets, connection state, and physics on the ONE region owning the player; vanilla still decides WHICH connections tick |
| Ordering safety | PLAYER slice is declared last in `Slice`: flush runs entity bodies before connection bodies per region, preserving vanilla's intra-tick pass order (entity pass, then `tickConnection`) |
| Effect when disarmed / gate off / fallback | vanilla's server-thread connection tick, byte-for-byte (fallbacks are whole: the packet route consults the same gate) |

### PacketProcessorMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `PacketProcessorMixin` |
| Target | `PacketProcessor.scheduleIfPossible(PacketListener, Packet)` HEAD (`@Inject`, cancellable) |
| Transformation | when a re-homing packet handler's listener belongs to a player whose chunks a region owns, routes the handler to that region's queue (in REGION context, with vanilla's error triage preserved in `RegionPlayerRouting.safelyHandle`) and cancels the vanilla server-thread queue add; `PERFORM_RESPAWN` is deliberately left in vanilla's queue so `PlayerList.respawn` runs on the server thread; all other cases fall through untouched |
| Why this seam | `scheduleIfPossible` is the single funnel of vanilla's `ensureRunningOnSameThread` re-home protocol — capturing it routes handlers to the same owner as the staged connection tick, preserving per-player ordering; respawn is the narrow exception because it constructs a replacement player and mutates global `PlayerList` state |
| Effect when disarmed / fallback | vanilla's shared server-thread packet queue |

### PacketUtilsMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `PacketUtilsMixin` |
| Target | `PacketUtils.ensureRunningOnSameThread(Packet, PacketListener, PacketProcessor)` — `@Redirect` of the `isSameThread()` call site |
| Transformation | returns true when the handler is already executing in the REGION context of the region owning the listener's player (drained from that region's queue); `PERFORM_RESPAWN` is the exception and returns false on a region so vanilla queues it for the server thread; otherwise forwards to the real `isSameThread` — server-thread, event-loop, and unowned behavior unchanged |
| Why this seam | without it, handlers drained on their owning region would re-throw `RunningOnDifferentThreadException` and ping-pong back to the server thread, defeating ownership; the respawn carve-out keeps `PlayerList.respawn`'s replacement-player/global-list body on the server thread; the covered overload is the one the `ServerLevel` convenience overload delegates to, so every game handler is handled |
| Effect when disarmed / gate off | vanilla's exact thread comparison everywhere |

### TicketStorageMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `TicketStorageMixin` |
| Target | `TicketStorage` — HEAD (`@Inject`, cancellable) of every public mutator: `addTicketWithRadius`, `addTicket(Ticket, ChunkPos)`, `addTicket(long, Ticket)`, `removeTicketWithRadius`, `removeTicket(Ticket, ChunkPos)`, `removeTicket(long, Ticket)`, `removeTicketIf`, `replaceTicketLevelOfType`, `updateChunkForced`, `purgeStaleTickets` |
| Transformation | single-writer ownership boundary: when the caller is not the recorded server thread, the mutation is cancelled and captured as a verbatim replay closure (original receiver, method, arguments) into `ServerThreadDeferral`; replayed FIFO on the server thread at `MinecraftServer.tickChildren` HEAD and `ServerChunkCache.tick` HEAD — before `runAllUpdates` and every tracker enumeration; deferred boolean mutators answer `true` (accepted; applies before the next tick's work) |
| Why this seam | `TicketStorage` is the single funnel whose listeners (`simulationChunkUpdatedListener` → `SimulationChunkTracker.setLevel`) mutate the plain fastutil tracker maps (`Long2ByteOpenHashMap`) the server thread iterates in `DistanceManager.forEachEntityTickingChunk`; vanilla never writes tickets off the server thread, so any worker write (portal tickets via `Entity.placePortalTicket`, the direct `DistanceManager.addPlayer` ticket add, `/forceload` from a region, C2ME threads) is an iterator-invalidation race — the `"this.wrapped" is null` NPE that crashed PalorderCentral on 2026-09-25 |
| Coverage | guards the whole mutation surface at the funnel rather than per caller (the superseded `ChunkTicketMixin` covered only two `ServerChunkCache` entry points; `DistanceManager.addPlayer` and `addTicketAndLoadWithRadius` bypassed it entirely — the audit that proved this is why the crash survived the first fix) |
| Effect when disarmed / gate off | off-server-thread ticket writes hit the plain maps again (the pre-fix race); the server-thread path is vanilla in all cases |

### ChunkMapTrackingMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `ChunkMapTrackingMixin` |
| Target | `ChunkMap.move(ServerPlayer)`, `ChunkMap.addEntity(Entity)`, `ChunkMap.removeEntity(Entity)` HEAD (`@Inject`, cancellable) |
| Transformation | same server-thread-identity gate as `TicketStorageMixin`: non-server-thread callers are cancelled and captured verbatim into `ServerThreadDeferral`, replayed at tick HEAD |
| Why this seam | `entityMap`/`playerMap` are plain maps iterated by `ChunkMap.tick` (entity-tracker updates) on the server thread; entity spawn/despawn inside staged bodies and player section-changes reach `addEntity`/`removeEntity`/`move` from region workers — the same iterator-invalidation disease as the ticket maps |
| Effect when disarmed / gate off | worker-side tracking writes race the server thread's entity-tracker iteration |

### DistanceManagerTrackingMixin

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `DistanceManagerTrackingMixin` |
| Target | `DistanceManager.addPlayer(SectionPos, ServerPlayer)`, `DistanceManager.removePlayer(SectionPos, ServerPlayer)`, `DistanceManager.runAllUpdates(ChunkMap)` HEAD (`@Inject`, cancellable) |
| Transformation | non-server-thread calls cancel and defer verbatim; deferred `runAllUpdates` answers `false` (no updates processed in this call) and replays at the next tick HEAD |
| Why this seam | `addPlayer`/`removePlayer` mutate `playersPerChunk` (plain map) and place player-simulation tickets directly on `TicketStorage`, bypassing `ServerChunkCache`; `runAllUpdates` is the tracker-map mutator (`setLevel`) — both must be single-writer |
| Effect when disarmed / gate off | player-ticket bookkeeping mutates off-thread again |

### ServerChunkCacheMixin (ticket-load and drain anchors)

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `ServerChunkCacheMixin` |
| Target | `blockChanged(BlockPos)` HEAD (broadcast-set deferral); `addTicketAndLoadWithRadius(TicketType, ChunkPos, int)` HEAD (`@Inject`, cancellable); `tick(BooleanSupplier, boolean)` HEAD (`@Inject`) |
| Transformation | `addTicketAndLoadWithRadius` called off the server thread is cancelled and deferred as a whole call, answering a bridge `CompletableFuture` completed when the replayed call's future completes (its inner ticket add cannot be deferred alone — the chunk-visibility check would fail); `tick` HEAD drains `ServerThreadDeferral` so every replay lands before `runAllUpdates`/`tickChunks` enumeration |
| Why this seam | `addTicketAndLoadWithRadius` additionally calls `runDistanceManagerUpdates()` (a tracker-map mutator) on the caller's thread and returns a load future callers chain on — partial deferral would break both |
| Effect when disarmed / gate off | worker ticket-loads race the trackers and their visibility check |
