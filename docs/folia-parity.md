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

| Per-region chunk lists | PARTIAL | regionizer owns section/chunk membership; no per-region chunk-state object yet |
| Block/fluid tick lists per region | NOT_IMPLEMENTED | vanilla's LevelTicks still server-thread-global |
| Per-region redstone time / current tick semantics | NOT_IMPLEMENTED | current tick counters are per-region (split children inherit; merge re-homes) — redstone-time split from game time not yet done |

## Inter-region operations

| Subsystem | Status | Evidence / notes |
|---|---|---|
| Cross-region task scheduling (explicit boundaries) | TESTED | region↔region/global enqueue via queues; ThreadContextTest |
| Entity migration between regions | TESTED (engine) / IMPLEMENTED (vanilla hooks) | `RegionEntityRegistry`: authoritative ownership map, atomic stripe-locked migrate/unregister, per-region entity sets as real `RegionLocalData`, split retarget by home chunk, retire on death/purged home, merge adopt; storm test proves unique ownership under concurrency. LIVE vanilla hooks this release: `ServerLevel.addEntity` funnel capture (all spawn paths), `Entity.setRemoved` release (every removal reason), and server-thread movement re-homing gated on chunk-boundary crossings — fed into the registry protocol by `EntityRegionTracker` |
| Teleportation (multi-phase, region-safe) | NOT_IMPLEMENTED | |
| Player login placement | NOT_IMPLEMENTED | |
| Player respawn | NOT_IMPLEMENTED | |
| Dimension transfer (region-to-region across worlds) | NOT_IMPLEMENTED | |
| Portals | NOT_IMPLEMENTED | |
| Networking dispatch (netty → region/global) | NOT_IMPLEMENTED | packets still processed on server threads |
| Global→Region / Region→Global task flows | TESTED | global queue + region queues; ThreadContextTest |

## Vanilla tick pipeline integration

| Subsystem | Status | Evidence / notes |
|---|---|---|
| Random ticks on region workers | VALIDATED | the one intercepted slice; world-mutation + attribution + cessation proven on production servers (TESTING.md, all 11 compat combos) |
| Precipitation (same pass as random ticks) | VALIDATED | same intercepted body |
| Scheduled block ticks | NOT_IMPLEMENTED | |
| Scheduled fluid ticks | NOT_IMPLEMENTED | |
| Block entities | NOT_IMPLEMENTED | |
| Entity ticking | NOT_IMPLEMENTED | ticking remains on the server thread this phase; ownership capture (add/remove/move) is live via `EntityRegionTracker` — the substrate region ticking will consume |
| Redstone/current-tick state | NOT_IMPLEMENTED | |
| Chunk load/generation on region threads | NOT_IMPLEMENTED | chunk loads still server/async-cache driven |
| Worldborder/daylight/weather/game rules on a global region task | NOT_IMPLEMENTED | vanilla still owns these on the server thread; the global scheduler exists to receive them |

## Platform

| Subsystem | Status | Evidence / notes |
|---|---|---|
| Thread-ownership enforcement (STRICT/WARN/OFF) | TESTED | ThreadContextTest; live evidence in compat runs |
| Violation diagnostics (actionable, region/thread/operation named) | TESTED | ViolationReporter format |
| Exception isolation per region | PARTIAL | per-task + per-tick catches tested; region-halt policy is drop-and-diagnose, no configurable failure policy yet |
| Performance metrics (MSPT per region, queue depths, utilization) | PARTIAL | per-region last-tick duration + queue sizes via commands; no metrics export, no TPS-per-region |
| Multi-world support (shared pool, per-world regionizers) | TESTED | RegionScheduler shared-pool constructor; engine attaches all dimensions |
| Optimization-mod compatibility (Lithium/C2ME/FerriteCore/Krypton/VMP/ScalableLux) | VALIDATED | 11 measured combos, all PASS; C2ME interaction root-caused with auto-suppression (COMPATIBILITY.md) |
| Legacy Fabric-mod handling | PARTIAL | declaration scan + diagnostics; no automatic translation of legacy execution patterns into schedulers yet |
| Shutdown/quiescence | TESTED | drain-and-drop close protocol; graceful-shutdown phase in every compat combo |
| Stress coverage (mandate §45) | PARTIAL | regionizer concurrency storm, worker-pool storms; no entity/teleport/redstone stress (nothing to stress yet) |

## Reading the gaps (the honest summary)

What exists is a **tested regionization and scheduling core**: the
regionizer with its four invariants, independent EDF-paced region ticks over
a bounded shared pool, region-local queues with merge/death semantics, a
global scheduler, a distinct async scheduler, an entity scheduler with
follow semantics wired to a real entity-migration registry, and STRICT
thread-ownership enforcement — plus one gameplay slice (random ticks)
validated end-to-end on live servers, including against the optimization
ecosystem. The entity registry is the first real `RegionLocalData` consumer;
its vanilla-side hooks (feeding real entity movement in) remain the next
integration step.

What does **not** exist yet is Folia's gameplay integration layer: entity
migration, teleport/login/respawn/dimension transfer, chunk-lifecycle
ownership, block/fluid/redstone tick state per region, the network dispatch
boundary, and the per-region tick pipeline beyond random ticks. Those are
the mandate's remaining bulk, and this checklist will say so until each
survives tests at its claimed strength.
