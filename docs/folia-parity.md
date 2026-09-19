# Fabric Folia — Folia Parity Checklist

Statuses against Folia's documented architecture (PaperMC Folia docs:
overview + region-logic references; this project's clean-room derivation in
ARCHITECTURE.md). Status vocabulary is fixed and honest:

- **NOT_IMPLEMENTED** — no code path exists.
- **PARTIAL** — real code exists but does not cover the subsystem's contract.
- **IMPLEMENTED** — the contract is implemented; not yet exercised by tests
  at that claim's strength.
- **TESTED** — implemented AND covered by automated tests at that strength.
- **VALIDATED** — TESTED and additionally proven on live production servers
  (compat harness / dev-server evidence).

This file is the completion standard (mandate §43): nothing is called
complete here that is only implemented, and nothing is TESTED without a
named test. Update it in the same change that moves a status.

## Regionizer core

| Subsystem | Status | Evidence / notes |
|---|---|---|
| Regionizer per world (create/maintain/destroy) | TESTED | `WorldRegionizer`; RegionizerInvariantTest, RegionizerConcurrencyTest; emptied-region death regardless of the recalculation gate (zombie-region fix) |
| Region sections (NxN spatial organization) | TESTED | `RegionSection`, section-keyed maps; invariant tests |
| Invariant 1 — unique ownership per chunk | TESTED | RegionizerInvariantTest |
| Invariant 2 — merge-radius buffer around regions | TESTED | empty-section creation radius; invariant tests |
| Invariant 3 — ticking regions cannot grow | TESTED | TRANSIENT gating; TickLifecycleTest |
| Invariant 4 — state machine (TRANSIENT/READY/TICKING/DEAD) | TESTED | `RegionState`, TickLifecycleTest |
| Merge logic (incl. merge-later deferral, transient downgrade) | TESTED | `mergeInto` + deferred-merge protocol; invariant tests |
| **Split logic (redistribute owned data)** | TESTED | flood-fill split + tick-counter inheritance + queue partitioning + region-local data redistribution — all driven through the real tick-end protocol (see Scheduler/Region data rows) |
| Merge/split tick-deadline reconciliation (redstone/current-tick offset) | NOT_IMPLEMENTED | requires per-region redstone time (below); queue deadlines use tick counters which already re-home/inherit |
| Region tick state + lifecycle | TESTED | tryBeginTick/completeTick/abortTick; TickLifecycleTest |
| Ticking eligibility (READY only, neighbor-free) | TESTED | invariant tests |
| Region deadlines (independent pacing) | TESTED | RegionSchedulerTest (fairness: slow region does not delay others) |
| Region-local queues | TESTED | `RegionTaskQueue` (multi-producer/single-consumer, merge re-home, death drop) |

## Schedulers (the four-scheduler model)

| Subsystem | Status | Evidence / notes |
|---|---|---|
| Region scheduler | TESTED | `RegionScheduler` (EDF coordinator, shared pool); RegionSchedulerTest |
| Global scheduler | TESTED | `GlobalSchedulerImpl` (dedicated dispatch thread, ordered queue) |
| Async scheduler (distinct from global) | TESTED | `AsyncSchedulerImpl` (own bounded daemon pool + timer thread, wall-clock time base, IO context tagging, fixed-rate non-overlap, exception isolation, bounded-queue backpressure); AsyncSchedulerImplTest — never shares the global dispatch thread or region workers |
| Entity scheduler (follows migration) | TESTED | `EntitySchedulerImpl` — follow/retire semantics tested; now wired per world to the live `RegionEntityRegistry` resolver, so entity tasks resolve the owning region at execution time |
| RegionizedTaskQueue (create-region-if-absent) | TESTED | `scheduleToChunkOrCreate` creates the region for an unowned position and executes the task in its context |
| Worker pool (bounded, world-agnostic) | TESTED | `WorkerPool` storm tests; shared across worlds |
| Scheduler fairness (§37: one lagging region never delays others) | TESTED | RegionSchedulerTest deadline-independence case |
| Scheduler robustness (malformed task ≠ region death) | TESTED | per-task catch + region abort protocol; RegionSchedulerTest |

## Region data

| Subsystem | Status | Evidence / notes |
|---|---|---|
| RegionizedData (region-local data provider with lifecycle callbacks) | TESTED | `RegionLocalData` (common): context-enforced get/create, merge handler, split redistribution, destroy release; violation/lifecycle tests driven through the real regionizer |
| Per-region entity lists | TESTED (engine) | the FIRST real consumer: `RegionEntityRegistry` keeps per-region entity sets through `RegionLocalData` — context-enforced access, merge adopt, split redistribution by home chunk, destroy release |
| Queue redistribution on split | TESTED | split listener partitions the parent queue by child ownership; re-homed task executes in the child's context |

| Chunk lifecycle: register on activity, release on unload | TESTED (engine) / TESTED (live server) | regionizer position-exact ownership (idempotent re-offers); `ServerLevelUnloadMixin` routes `ServerLevel.unload` → `removeChunk`; live proof: forceload far chunk → region forms (`registered` +1), ticket removal → `unregistered` +1 and the region dies. Per-region chunk-*state* object still open (position lifecycle is owned, state is not) |
| Block/fluid tick lists per region | NOT_IMPLEMENTED | vanilla's LevelTicks still server-thread-global |
| Per-region redstone time / current tick semantics | NOT_IMPLEMENTED | current tick counters are per-region (split children inherit; merge re-homes) — redstone-time split from game time not yet done |

## Inter-region operations

| Subsystem | Status | Evidence / notes |
|---|---|---|
| Cross-region task scheduling (explicit boundaries) | TESTED | region↔region/global enqueue via queues; ThreadContextTest |
| Entity migration between regions | TESTED (engine) / TESTED (live server) | `RegionEntityRegistry`: authoritative ownership map, atomic stripe-locked migrate/unregister, per-region entity sets as real `RegionLocalData`, split retarget by home chunk, retire on death/purged home, merge adopt; storm test proves unique ownership under concurrency. LIVE vanilla hooks validated on a real 26.2 server (`compat/entity_live_check.py`, PASS 10/10): single add funnel capture, removal release on every reason, and cross-region migration measured `0 → 1` on a real 4000-block teleport. Two live-found defects fixed: removal gate fought the hook's own timing; teleport path bypassed the first movement hook |
| Teleportation (multi-phase, region-safe) | PARTIAL | `RegionTransitions.dispatch` + `EntityTeleportMixin`: worker-context teleports resolving to another region/world are routed to the destination context; same-region teleports stay inline vanilla |
| Player login placement | NOT_IMPLEMENTED | |
| Player respawn | NOT_IMPLEMENTED | |
| Dimension transfer (region-to-region across worlds) | PARTIAL | the cross-world branch of `RegionTransitions.dispatch` hands the transition to the destination world's scheduler; full multi-phase login/respawn placement still open |
| Portals | NOT_IMPLEMENTED | |
| Networking dispatch (netty → region/global) | IMPLEMENTED | the pending-action queue in `Connection` is the wired seam: `ConnectionPacketDispatchMixin` classifies event-loop-drained tasks (bounded window), `ConnectionFlushQueueMixin` routes every drained action through `NetworkDispatch.runOnOwner` (inline on the server thread, global hop from network context); NetworkDispatchTest proves region/global/engine-down routing through the real engine. Live client-session evidence still open (no GUI client in CI) |
| Global→Region / Region→Global task flows | TESTED | global queue + region queues; ThreadContextTest |

## Vanilla tick pipeline integration

| Subsystem | Status | Evidence / notes |
|---|---|---|
| Random ticks on region workers | VALIDATED | the one intercepted slice; world-mutation + attribution + cessation proven on production servers (TESTING.md, all 11 compat combos) |
| Precipitation (same pass as random ticks) | VALIDATED | same intercepted body |
| Scheduled block ticks | TESTED (live) | two seams: `LevelScheduleTickMixin` defers worker-context schedules into `ScheduledTickDeferral` (server-thread replay keeps the container single-writer), and `LevelTicksTickMixin`+`ServerLevelTickMixin` stage the drain bodies at `runCollectedTicks`' accept site onto region workers. LIVE: water/fire on a dev server drove 6,132 staged scheduled-tick bodies executed on workers; 6,053 worker-originated re-schedules deferred and replayed in balance, zero backlog, zero failures |
| Scheduled fluid ticks | TESTED (live) | same staged slice as block ticks (same `LevelTicks` machinery); fluid ticks from placed water are part of the live counts above |
| Block entities | IMPLEMENTED | staged slice: `LevelBlockEntityTickMixin` redirects the `TickingBlockEntity.tick()` call site into the `BLOCK_ENTITY` slice; list management stays vanilla |
| Entity ticking | IMPLEMENTED | staged slice: `LevelEntityTickMixin` redirects the `guardEntityTick` call site — the whole `tickNonPassenger` tree (tick-count increment, body, passenger recursion) flows through it, zero vanilla logic replicated; bodies execute on the owning region's worker |
| Redstone/current-tick state | NOT_IMPLEMENTED | |
| Chunk load/generation on region threads | NOT_IMPLEMENTED | chunk loads still server/async-cache driven |
| Worldborder/daylight/weather/game rules classification | IMPLEMENTED | `GlobalStateRegistry`: machine-checked Domain→Ownership classification (global/region/unclassified), surfaced in `/folia` diagnostics; enforcement hooks land with each consumer |

## Platform

| Subsystem | Status | Evidence / notes |
|---|---|---|
| Thread-ownership enforcement (STRICT/WARN/OFF) | TESTED | ThreadContextTest; live evidence in compat runs |
| Per-thread random state (level RNG) | TESTED (engine) / VALIDATED (live) | `WorkerRandoms` dispatches per-thread sources to region threads; original instance untouched for all other threads (bit-identical server streams). Unit: `WorkerRandomsTest` incl. the guarded-source contention reproduction. Live: multi-region compat protocol greps the ThreadingDetector signature and the post-fix baseline run is PASS 13/13 with 0 offending lines |
| Violation diagnostics (actionable, region/thread/operation named) | TESTED | ViolationReporter format |
| Exception isolation per region | TESTED | `RegionFailurePolicy`: consecutive-failure accounting, abort at threshold (regionizer fail-safe kill), success resets; exercised through the scheduler's real catch sites (RegionFailurePolicyTest). Fixed en route: the default-policy construction previously left null in place, which would NPE inside the tick catch and wedge the region as eternal TICKING |
| Performance metrics (MSPT per region, queue depths, utilization) | PARTIAL | per-region last-tick duration + queue sizes via commands; no metrics export, no TPS-per-region |
| Multi-world support (shared pool, per-world regionizers) | TESTED | RegionScheduler shared-pool constructor; engine attaches all dimensions |
| Optimization-mod compatibility (Lithium/C2ME/FerriteCore/Krypton/VMP/ScalableLux) | VALIDATED | 11 measured combos, all PASS; C2ME interaction root-caused with auto-suppression (COMPATIBILITY.md) |
| Legacy Fabric-mod handling | PARTIAL | declaration scan + diagnostics + `LegacyDispatchPolicy` (per-mod destination declarations from mod metadata, configurable default, network/region direct-run gate; tested); entrypoint delivery of the public API implemented — automatic translation of legacy patterns still open |
| Shutdown/quiescence | TESTED | drain-and-drop close protocol; graceful-shutdown phase in every compat combo |
| Stress coverage (mandate §45) | PARTIAL | regionizer concurrency storm, worker-pool storms; no entity/teleport/redstone stress (nothing to stress yet) |

## Reading the gaps (the honest summary)

What exists is a **tested regionization and scheduling core** plus the
**staged gameplay execution layer**: entity ticking, block entities, and
scheduled block/fluid ticks are captured at their exact vanilla call sites
and executed on the owning region's worker (zero replicated vanilla logic;
disarmed state is byte-for-byte vanilla), with worker-context tick
schedules deferred and replayed on the server thread so container mutations
stay single-threaded. Teleport/dimension transitions from region workers
route through an explicit destination-context handoff, Netty event loops
carry a machine-checked NETWORK context, and the global-state domain
classification is enforced rather than documented. The failure policy and
legacy dispatch policy are real, wired, and tested.

What does **not** exist yet is Folia's gameplay integration layer: entity
migration, teleport/login/respawn/dimension transfer, chunk-lifecycle
ownership, block/fluid/redstone tick state per region, the network dispatch
boundary, and the per-region tick pipeline beyond random ticks. Those are
the mandate's remaining bulk, and this checklist will say so until each
survives tests at its claimed strength.
