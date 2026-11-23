# Folia Parity Checklist

This ledger compares Fabric-Folia with the execution model documented by Folia. It is not a claim of Folia parity, and it is not a release certificate. The current release is experimental and not production-ready.

## Status vocabulary

- **NOT_IMPLEMENTED** — no code path exists.
- **PARTIAL** — a code path exists but does not cover the subsystem's contract, or a required cross-region case is missing.
- **IMPLEMENTED** — the described contract is implemented, but the strength of automated coverage does not justify a stronger status.
- **TESTED** — implemented and covered by a named automated test in this repository.

No row uses a "validated" status. Live development sessions are recorded in the project handoff, but their logs, worlds and benchmark artifacts are not committed, so this repository cannot reproduce them. A status here means "code present" or "covered by a named test", not "proven on a production server".

Two evidence classes are kept separate throughout:

- **Code-path evidence** is source inspection plus the named automated tests.
- **Live evidence for the current candidate** is limited to a 2026-11-16 smoke boot of the deployed artifact: the server reached `Done (` and `Fabric Folia is ready` with regionized gameplay and player staging enabled in the existing config, but no players were connected. That boot proves startup and mixin application only. It does not exercise entity, block-entity, scheduled-tick or player behavior.

## Regionizer core

| Subsystem | Status | Evidence and notes |
|---|---|---|
| Regionizer per world (create, maintain, destroy) | TESTED | `WorldRegionizer`; `RegionizerInvariantTest`, `RegionizerConcurrencyTest`, `RegionizerChunkLifecycleTest` |
| Region sections (NxN spatial organization) | TESTED | `RegionSection`; invariant tests |
| Unique chunk ownership | TESTED | `RegionizerInvariantTest` |
| Merge buffer around regions | TESTED | Empty-section radius; invariant tests |
| Ticking regions cannot grow | TESTED | TRANSIENT gating; `TickLifecycleTest` |
| Region state machine (TRANSIENT, READY, TICKING, DEAD) | TESTED | `RegionState`; `TickLifecycleTest` |
| Merge logic | TESTED | `mergeInto` and deferred merge at tick end; `RegionizerChunkLifecycleTest`, `RegionPipelineTest` |
| Split logic and region-local data redistribution | TESTED | Flood-fill split, queue partitioning, `RegionLocalData` redistribution; `TickLifecycleTest`, `RegionizerChunkLifecycleTest`, `RegionLocalDataTest` |
| Merge/split redstone-time reconciliation | NOT_IMPLEMENTED | Requires per-region redstone time |
| Region tick state and lifecycle | TESTED | `tryBeginTick`, `completeTick`, `abortTick`; `TickLifecycleTest` |
| Ticking eligibility | TESTED | READY-only and neighbour-free rules; invariant tests |
| Region deadlines | TESTED | `RegionSchedulerTest` fairness cases |
| Region-local queues | TESTED | `RegionTaskQueue`; `RegionTaskQueueConcurrencyTest`, `RegionSchedulerTest` |

## Schedulers

| Subsystem | Status | Evidence and notes |
|---|---|---|
| Region scheduler | TESTED | `RegionScheduler`; `RegionSchedulerTest`, `RegionDrainBudgetTest` |
| Global scheduler | TESTED | `GlobalSchedulerImpl` is an ordered queue, not a dedicated dispatch thread. It is drained on the Minecraft server thread during the tick coordination phase. `GlobalSchedulerImplTest` |
| Async scheduler | TESTED | Own bounded daemon pool and timer; wall-clock delays; `AsyncSchedulerImplTest` |
| Entity scheduler | IMPLEMENTED | Follow and retire semantics with a per-world region resolver; `RegionEntityRegistryTest` covers registry ownership, not a dedicated entity-scheduler test |
| Create-region-if-absent task submission | TESTED | `scheduleToChunkOrCreate`; `RegionSchedulerTest.scheduleToChunkOrCreateRegionifiesUnownedPosition` |
| Bounded worker pool | TESTED | `WorkerPoolGateTest`, `RegionSchedulerTest` |
| Scheduler fairness | TESTED | A slow region does not delay others; `RegionSchedulerTest` |
| Malformed task isolation | TESTED | Per-task catch and region abort; `RegionFailurePolicyTest` |
| Staged-body retry and exhaustion | TESTED | `StageRetryTest` |

## Region data

| Subsystem | Status | Evidence and notes |
|---|---|---|
| Region-local data with lifecycle callbacks | TESTED | `RegionLocalData`; `RegionLocalDataTest` |
| Per-region entity lists | TESTED | `RegionEntityRegistry`; `RegionEntityRegistryTest` |
| Queue redistribution on split | TESTED | Parent queue partitioned by child ownership; `RegionSchedulerTest`, `RegionStageHubParallelGateTest` |
| Pending scheduled-tick ledger | TESTED | `RegionPendingTicks`; `RegionPendingTicksTest` |
| Chunk registration on load and release on unload | TESTED | `ChunkRegionization` plus `ServerLevelUnloadMixin`; `RegionPipelineTest`, `RegionizerChunkLifecycleTest` |
| Per-region chunk state object | NOT_IMPLEMENTED | Ownership is a position-to-region map, not `ChunkHolder`/`ChunkMap` state |
| Per-region redstone time | NOT_IMPLEMENTED | Tick counters are per region; redstone time is global |

## Inter-region operations

| Subsystem | Status | Evidence and notes |
|---|---|---|
| Explicit region, global and async handoffs | TESTED | `ThreadContextTest`, `GlobalSchedulerImplTest` |
| Entity migration between regions | TESTED | `RegionEntityRegistry` ownership, split retargeting and retirement; `RegionEntityRegistryTest` |
| Teleportation | PARTIAL | `ServerPlayerTeleportMixin` routes worker-context player teleports to the destination context. The non-player `EntityTeleportMixin` has an open P0 double-cancel (R1 in `tools/freebuff/reports/defect-ledger.md`): a dispatched body re-enters, is read as a duplicate and the vanilla teleport never runs. `PlayerTeleportRoutingTest` covers routing decisions, not a live transition |
| Player login placement | NOT_IMPLEMENTED | Login still uses vanilla's server-thread placement |
| Player respawn | PARTIAL | `PERFORM_RESPAWN` is excluded from owner-region packet routing and drained on the server thread by vanilla. Direct `PlayerList.respawn` callers and destination-region placement are not covered. `PlayerRespawnRoutingTest` |
| Dimension transfer | PARTIAL | The cross-world branch hands the transition to the destination scheduler. Global `PlayerList` mutations in that branch run on the destination region's worker. `PlayerTeleportRoutingTest` covers region hop, global fallback and unattached-world refusal, not a successful cross-dimension move |
| Portals | PARTIAL | `Entity.handlePortal` virtual-dispatches `teleport(TeleportTransition)`, which is captured for both `Entity` and the `ServerPlayer` override. Portal search and creation on the origin side are not staged |
| Networking dispatch (Netty to region or global) | IMPLEMENTED | `NetworkDispatch` plus `ConnectionPacketDispatchMixin` and `ConnectionFlushQueueMixin`; `NetworkDispatchTest` |
| Player connection tick and packet routing | IMPLEMENTED | `ServerConnectionTickMixin`, `PacketProcessorMixin`, `PacketUtilsMixin`, `ServerPlayerTickMixin`; component routing tests only. Gated by `gameplay.stage-player-path` |
| Explosion damage across regions | NOT_IMPLEMENTED | Not intercepted. An explosion triggered from a staged entity body executes in that region context and can mutate neighbouring regions |

## Vanilla tick pipeline integration

| Subsystem | Status | Evidence and notes |
|---|---|---|
| Entity ticking | IMPLEMENTED | `LevelEntityTickMixin` redirects the `guardEntityTick` consumer; the whole `tickNonPassenger` tree is staged. No direct automated test of entity-body world effects |
| Block entities | IMPLEMENTED | `LevelBlockEntityTickMixin` redirects `TickingBlockEntity.tick()`; list management stays vanilla. No direct automated test |
| Scheduled block ticks | IMPLEMENTED | `LevelScheduleTickMixin` defers worker schedules to the server thread; `LevelTicksTickMixin` stages drain bodies. `StageRetryTest`, `RegionPendingTicksTest` cover retry and ledger accounting, not live block behavior |
| Scheduled fluid ticks | IMPLEMENTED | Same `LevelTicks` machinery as scheduled block ticks |
| Random ticks | IMPLEMENTED | `ServerChunkCacheTickMixin` redirects `forEachBlockTickingChunk`; `RegionTickInterceptorPolicyTest` covers the queue-limit policy. A region above 512 queued tasks skips that pass; a later pass re-offers the chunks, and the skip can repeat while the queue stays above the limit |
| Precipitation | IMPLEMENTED | Same intercepted pass as random ticks |
| Staged backlog backpressure | TESTED | `RegionStageHubBacklogTest` shows staging is suspended, refused bodies are counted rather than dropped (callers run them vanilla-inline), and staging resumes after drain. Random ticks use a separate pass-level skip, not this gate |
| Retained staged rounds | TESTED | `RegionStageHubParallelGateTest.backedUpRegionRetainsEveryStagedRound` |
| Redstone and current-tick state | NOT_IMPLEMENTED | Global tick semantics |
| Chunk load and generation on region threads | NOT_IMPLEMENTED | Chunk loading remains server/async-cache driven |
| World border, daylight, weather and game rules | IMPLEMENTED | `GlobalStateRegistry` classification, surfaced in diagnostics; enforcement lands with each consumer |
| Save-time quiescence | PARTIAL | Shutdown drains through the engine, but no test asserts drain completion before vanilla saves |

## Platform

| Subsystem | Status | Evidence and notes |
|---|---|---|
| Thread ownership enforcement (STRICT, WARN, OFF) | TESTED | `ThreadContextTest` |
| Per-thread level random state | TESTED | `WorkerRandoms`; `WorkerRandomsTest` |
| Violation diagnostics | TESTED | `ViolationReporter`; `ThreadContextTest` |
| Exception isolation per region | TESTED | `RegionFailurePolicy`; `RegionFailurePolicyTest` |
| Fatal-error propagation from staged bodies | TESTED | `RegionStageHubFatalTest.fatalBodyErrorPropagatesThroughInlineFlush` |
| Performance metrics (MSPT, queue depth, utilization) | PARTIAL | Per-region duration and queue sizes; no machine-readable export, no per-region TPS |
| Multi-world support | IMPLEMENTED | Shared pool with per-world regionizers and world-qualified routing keys; tests attach a single overworld plus isolated regionizers, not two live dimensions |
| Optimization-mod compatibility | PARTIAL | `docs/compatibility/` contains scoped investigations for six mods. There is no current matrix for the staged profile or a full modpack |
| Legacy Fabric-mod handling | PARTIAL | Declaration scan, diagnostics and `LegacyDispatchPolicy` (`LegacyDispatchPolicyTest`); automatic translation of legacy thread assumptions is not implemented |
| Shutdown and quiescence | PARTIAL | Drain-and-drop close protocol is tested for the pool (`WorkerPoolGateTest`); abandoned work accounting and drain-before-save are not covered end to end |
| Stress coverage | PARTIAL | Regionizer and worker-pool concurrency storms; no committed entity, teleport, redstone or save stress suite |

## Reading the gaps

What exists is a tested regionization and scheduling core plus a staged execution layer for selected vanilla tick bodies. Entity, block-entity, scheduled-tick and player bodies are captured at their vanilla call sites and executed on the owning region's worker when the corresponding gate is enabled. Worker-context tick schedules are deferred and replayed on the server thread so the tick containers remain single-writer. Teleport transitions from region workers route through an explicit destination-context handoff, and the server thread closes a worker gate at the start of each tick.

What does not exist is Folia's full gameplay integration layer: region-independent world ticks, player login placement, first-class portal and respawn flows, region-aware explosion and multi-block damage, per-region redstone time, per-region chunk state, and saved-evidence compatibility and soak matrices. Those gaps are why the project is described as staged-region execution rather than Folia parity, and why the staged paths are opt-in.

Two earlier statements in this repository are deliberately corrected here: the global scheduler is not a dedicated dispatch thread, and random ticks are not the only intercepted vanilla pass. Both were true at an earlier milestone and are preserved only in history.
