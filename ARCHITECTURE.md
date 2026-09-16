# Fabric-Folia — Architecture

**Regionized multithreaded server execution for vanilla Minecraft under Fabric Loader.**

This document is the load-bearing design record: module boundaries, state
classification, what is derived from Folia and how, feasibility constraints, and
every deliberate deviation — with reasoning. Per the project spec: constraints
are stated plainly, deviations are argued, nothing is papered over.

Status: **foundation milestone (Phases 0–3)** — engine primitives are
implemented and tested; gameplay interception is not active. See
[Roadmap position](#roadmap-position).

---

## 1. Module boundary and dependency direction

```
api  ←  common  ←  fabric        (arrow = depends on)
```

| Module | Contains | Depends on |
|---|---|---|
| `api` | The public, Fabric-native API: `RegionInfo`, `ThreadContext`, `RegionScheduler`, `EntityScheduler`, `GlobalScheduler`, `ValidationMode`, `ThreadContextViolationException`, threading annotations (`@RegionThread`, `@GlobalThread`, `@AnyThread`, `@NetworkThread`, `@IoThread`). **Zero Minecraft or loader types.** | nothing |
| `common` | The engine: regionizer (`region`), worker pool and schedulers (`scheduler`), thread-ownership core (`thread`), config engine (`config`). Pure Java + SnakeYAML Engine. | `api` |
| `fabric` | The mod: entrypoint, engine bootstrap, commands, diagnostics sink → slf4j, (later) Mixins. Produces the distributable jar. | `common`, `api` |

**Why the `common`/`fabric` split is real, not artificial** (the spec demands a
justification, and deserves one): the regionizer and scheduler are the
concurrency-safety core of the project. They must be provable in isolation —
the entire test suite runs in plain JVM without Minecraft on the classpath —
and iterated on without Loom's build cycle. Everything that genuinely requires
Minecraft or loader types lives in `fabric`. The boundary follows exactly what
differs: **engine concurrency logic vs. Minecraft integration.**

Package root: `com.palordersoftworks.fabricfolia`, with the subpackages
`api`, `region`, `scheduler`, `thread`, `config`, `engine`, `command`, plus
`mixin`, `chunk`, `entity`, `tick`, `world`, `compat`, `diagnostics` to appear
with the subsystems they serve (no empty placeholder packages).

## 2. State classification (spec 4)

Every subsystem below names its category. This table is the audit trail; code
comments repeat the classification at the point of use.

| Subsystem | Classification | Why |
|---|---|---|
| Regionizer structure (sections, region membership) | **GLOBAL** (per-world) | the regionizer itself is server-wide bookkeeping; it is explicitly not gameplay state |
| Region-owned section set while TICKING | **REGION-LOCAL** | invariant 3: a ticking region never grows; only its context may touch its state |
| Region task queue | **REGION-LOCAL** (re-homed wholesale on merge) | single-consumer rule: only the owning context drains; multi-producer enqueue is legal from any context |
| Intercept pass bookkeeping (`RegionTickInterceptor.Pass`) | **GLOBAL** (per-world, one vanilla tick's lifetime) | server-thread-side collection of vanilla's selected chunks; swapped wholesale on flush |
| Chunks offered by the intercept, once grouped | **REGION-LOCAL** | each offered chunk joins exactly one region (invariant 1); its `tickChunk` work runs only in that region's context |
| `ThreadOwnership` registry (ThreadLocal) | **GLOBAL** | describes the execution model itself |
| Worker pool | **GLOBAL** | shared infrastructure; not gameplay state |
| Global task queue | **GLOBAL** | owns game rules / border / regionizer / cross-world bookkeeping |
| Config store | **GLOBAL** (IO on save/load) | server-wide; read at boot, written on command |
| Diagnostics sinks | **GLOBAL** | append-only logging |
| (upcoming) chunks, entities, block entities, region-scoped scheduled ticks | **REGION-LOCAL** | owned by exactly one region at a time; migration = ownership transfer |
| (upcoming) chunk/entity/player IO | **IO** | owner-thread-execution or snapshot; decided in THREADING.md when implemented |
| (upcoming) packet handling | **NETWORK** → REGION-LOCAL handoff | Netty stays on Netty; gameplay mutation is scheduled into the owning region |

The global context is **not** a catch-all: no region gameplay routes through
it. `MissingRegionPolicy.RUN_ON_GLOBAL` is the one sanctioned escape hatch and
is wired in the integration phase (documented gap — see §8).

## 3. The concurrency model in one section

Ownership, not locking, is the safety mechanism (spec 3). Concretely, for the
milestone implemented here:

1. **The regionizer owns structure.** A single structure lock serializes
   regionizer operations (addChunk/removeChunk/tryBeginTick/completeTick).
   This lock is *not* the gameplay safety mechanism — it protects only the
   regionizer's bookkeeping maps and is never held while a tick runs. Lock
   hierarchy: structure lock → queue registry; nothing acquires the structure
   lock while holding a queue; no cycle exists.
2. **The state machine owns execution.** A region in state TICKING is owned by
   exactly one worker context (`tryBeginTick` is the single-owner latch,
   invariant 3/4). A region in READY/TRANSIENT is owned by no tick context;
   all state transitions happen under the structure lock.
3. **Queues are the cross-region handoff.** Region A never mutates region B's
   state directly; it enqueues into B's task queue (spec 7's diagram). The
   queue is multi-producer (ConcurrentLinkedQueue — because multiple producers
   are *legal*) and single-consumer (drained only inside the owning tick —
   ownership, not the queue structure, is the safety mechanism).
4. **volatile fields are visibility, not safety.** `Region.state`,
   `Region.tickCount`, `Region.nextTickDeadlineNanos` are volatile so
   cross-thread *advisory reads* (dispatch guards, diagnostics) see fresh
   values. All transitions still happen under the structure lock. No field is
   volatile as a substitute for ownership.

## 4. Regionizer (clean-room from Folia's documented invariants)

The four invariants are taken from PaperMC's public region-logic reference
(attribution below) and implemented against vanilla-neutral engine structures:

1. **Single ownership** — every chunk position belongs to exactly one region;
   sections move between regions only under the structure lock.
2. **Buffer invariant** — every position within the merge radius of an owned
   section is owned by the same region or pending merge. Enforced by the
   empty-section creation halo (alive buffer sections) and the merge machinery
   on addChunk.
3. **No growth while ticking** — a TICKING region's section set never expands;
   `tryMarkTicking` refuses anything not READY, and addChunk near a ticking
   region creates a TRANSIENT holder that merges at the target's tick end.
4. **Four states** — TRANSIENT, READY, TICKING, DEAD, with the documented
   tick-end protocol order: pending merges → own-pending-merge check (→
   TRANSIENT) → dead-section purge → split attempt.

Key clean-room decisions (documented in code where they live):

- **Merge direction on tick conflicts:** when a merge meets a ticking region,
  the *non-ticking* side carries the merge-later obligation into the ticking
  side and downgrades to TRANSIENT (it must not tick while carrying the
  obligation). The ticking region can never be absorbed mid-tick (it
  exclusively owns its sections until tick end).
- **Dead sections are deferred state, not garbage:** they stay owned (so
  invariant 1 keeps holding for them) until tick-end recalculation, gated on
  accumulated dead-section load — per the reference's deferred-recalculation
  design.
- **Split is flood-fill over alive sections:** connected components of the
  region's alive sections; strict subsets become new READY regions; split
  children inherit the parent's tick counter (no tick-number change, relative
  deadlines preserved).
- **Radii derive from simulation distance** (`RegionizerConfig.derive`): the
  buffer must be at least as wide as anything that reads across a boundary in
  one tick; derivation is documented on the class and is deliberately
  conservative (too wide wastes bookkeeping; too narrow is a correctness bug).
  These are not admin config options — the spec forbids exposing internal
  knobs admins cannot meaningfully judge.
- **`removeChunk` is defensively no-op on unmatched removes:** the chunk
  system's contract is balanced add/remove, but an integration bug must
  degrade to a no-op, not corrupt counters (an over-counted section is
  reclaimed by dead-section purge at tick end either way).

Folia **source code is not used, consulted, or copied** — Folia's server
patches are GPL-3.0 and this project is Apache-2.0. The derivation is from
PaperMC's public documentation (region-logic reference, architecture overview),
understood and re-implemented from first principles for a vanilla/Fabric
environment. Where Paper/Bukkit assumptions had to be discarded: no `Plugin`
model, no Bukkit `Location`, no CraftBukkit chunk internals — the equivalents
are built against the engine's own `Region`/`RegionSection` structures now, and
later against Mojang-mapped vanilla classes.

**Attribution:** the four-invariant regionizer model, the section
dead/alive/empty taxonomy, the merge/split state machine shape, the EDF-style
dispatch, and the global-region concept are derived from PaperMC/Folia's public
documentation (docs.papermc.io/folia/reference/region-logic and /overview).
This project credits that work; it copies none of it.

## 5. Scheduler and worker pool

- **Bounded worker pool** (spec 10): N dedicated worker threads; regions are
  dispatched onto them EDF (earliest-deadline-first). Workers are dumb (wake,
  run, shutdown); all policy lives in `RegionScheduler` — that separation keeps
  the policy reviewable and the pool testable.
- **Not `Executors.newFixedThreadPool`**: the intelligence the spec requires
  (which region is due, deadline awareness, per-region timing) lives in the
  scheduler's coordinator loop: 1ms scan of READY regions past their deadline,
  one tick job per due region. Duplicate dispatch is made harmless by two
  in-job guards (state re-check + deadline re-check) rather than cross-thread
  bookkeeping.
- **Independence (spec 6):** each region's next-tick deadline is its own
  (50ms cadence); a slow region delays only itself. No global tick barrier
  exists or is needed for the implemented machinery. The coordinator shares its
  time base with the regionizer (both `System::nanoTime` by default; tests
  inject matching clocks).
- **Tick pipeline (engine level):** tryBeginTick (single-owner latch) →
  counter advance (time base = ticks *started*) → drain task queue → tick body
  hook → record duration → completeTick (regionizer's tick-end protocol).
  Vanilla's real tick order is partitioned onto this pipeline in the
  integration phase — see SCHEDULING.md for the current documented plan and
  its open research items.
- **Failure handling (spec 26, engine level):** region task failures are
  reported with region + exception, never silent; a failure in the tick-end
  *protocol itself* aborts the region (fail-safe kill, queue dropped) rather
  than wedging it as eternal TICKING. Region-vs-server-halt isolation policy
  is finalized when real gameplay state flows through the pipeline.

## 6. Thread-context diagnostics (spec 8)

- `ThreadOwnership` — the registry: ThreadLocal execution context
  (REGION / GLOBAL / NETWORK / IO / UNKNOWN), entered/exited with explicit
  tokens in try/finally; at most one region context can legally exist on a
  thread at once (a nested one is itself a violation).
- `ViolationReporter` — the policy: applies STRICT (throw
  `ThreadContextViolationException`) / WARN (log, continue) / OFF (one enum
  compare) and renders the actionable diagnostic: operation, current context,
  target ownership, thread name, and the correct scheduler entry point. No
  code path swallows a violation silently.
- `ThreadContextImpl` — the public API view (`mayAccessRegion`,
  `assertRegionAccess`) so other mods can assert their own assumptions.
- Defaults: **STRICT** in dev builds (config default `WARN` in the shipped
  file, justified in the config comments: per-access STRICT has real overhead
  that must be measured before a production recommendation, per spec 8).
  Fabric API compatibility hooks that route other mods' violations into this
  same machinery arrive in the integration phase (spec 23).

## 7. Configuration (spec 11/13)

SnakeYAML Engine **3.1.1** (Apache-2.0, actively maintained, Java-25
compatible). Comment preservation is verified at the low level this project
uses: parse with `parseComments(true)` → mutate the Node tree → serialize with
`dumpComments(true)` — the convenience binding API would *not* preserve
comments (verified against the library's sources before committing, per spec 0
step 5). Round-trip and migration behavior are proven by tests, not asserted:

- every option documented in the generated default file (purpose, default,
  valid values, performance/compat/safety implications, restart-vs-live);
- admin comments survive saves (round-trip tested);
- unknown keys preserved and flagged, never silently dropped;
- `config-version` migration contract: explicit migration, fail closed on
  unknown future versions rather than guessing;
- the generated default is a complete self-documenting template.

## 8. Roadmap position and honest gaps

Implemented (Phases 0–3 + integration slice): module scaffold, config engine,
dynamic regionizer with all four invariants + merge/split + concurrency stress
tests, thread-context diagnostics, EDF worker-pool scheduler with region task
queues, merge-survival of queued tasks (listener wiring + regression test),
global context with tick-counter delays, entity scheduler with
migration-following, **and the first real vanilla interception: regionized
random-tick execution** (opt-in via `general.regionized-random-ticks`, default
OFF).

**The intercept that exists (verified end to end on a live 26.2 server):**
`ServerChunkCacheTickMixin` redirects the single
`ChunkMap.forEachBlockTickingChunk(Consumer)` call inside
`ServerChunkCache.tickChunks(ProfilerFiller,long)` (all names javap-verified
against the 26.2 jar). When intercepting, vanilla's chunk *selection* runs
unchanged but hands the selected chunks to `RegionTickInterceptor`, which
regionizes them (invariant 1), groups them per pass, and enqueues each chunk's
`ServerLevel.tickChunk(LevelChunk,int)` (precipitation + random ticks) into
the owning region's queue. Jobs execute on region workers under the EDF
scheduler, with the single-owner latch held, merge re-homing active, and the
thread-context diagnostics asserted on the real work path. When the flag is
off (or the engine disabled), the mixin forwards to vanilla unchanged. Live
evidence: `EVIDENCE: region minecraft:overworld:1 ticked on worker thread
'Fabric-Folia-Worker-N'` in the server log; `/folia pin|unpin` pins a demo
ticket area; shutdown stays clean. **World mutation is proven, not inferred:**
a covered-grass scenario driven over RCON (TESTING.md) showed worker-tick
conversions (grass 25→18 under pin, dirt control untouched), dispatch ceasing
exactly at unpin (zero passes in the following 65 s), and 7 distinct workers
executing the vanilla tick body with zero violations.

**Not implemented yet (and not faked — spec 17):**

- **The rest of the tick pipeline.** Scheduled ticks (`LevelTicks.tick`),
  block entities (`tickBlockEntities`), the entity pass, worldgen, and
  spawning still execute on the server thread — so they can mutate
  neighboring blocks while a region worker random-ticks the same area. This
  interleaving is the documented boundary of the slice (safe at the
  block-state level: each chunk's data has a single writer at a time; the
  full boundary comes when those passes regionize too). SCHEDULING.md records
  the remaining partitioning work.
- **Region ownership on chunk holders.** Chunks are regionized *per tick
  pass* via the interceptor; ownership is not yet attached to
  `ChunkHolder`/`ChunkMap` state itself (that is the next integration
  milestone).
- **The API facade's `MissingRegionPolicy.RUN_ON_GLOBAL` global fallback** is
  a documented TODO until a live GlobalScheduler is passed in — both policies
  currently degrade to a documented drop.
- **Entity scheduler is complete machinery with a stub resolver.** The real
  26.2 entity→region resolver lands with entity integration (spec 7).

Feasibility constraints (spec 27) — vanilla's structures are not
concurrency-safe; no pre-existing chunk-holder split to build on; third-party
mod compatibility cannot be guaranteed in general; static/global assumptions
must be found case by case — are accepted project constraints and are
elaborated with their consequences in COMPATIBILITY.md and THREADING.md.
Dynamic re-enable after disable (spec 16) is treated as **fail-safe-refuse**
until positively demonstrated safe under test.

## 9. Deliberate deviations (spec 29 ledger)

1. **8×8 is the default region-section size, not a fixed grid** — the original
   brief's fixed grid would violate all four invariants dynamically. Kept as
   corrected throughout (REGIONS.md explains why in operator terms).
2. **Folia is documentation-only reference material** — GPL-3.0 source is not
   read into this codebase or copied; everything above is derived from public
   docs and re-implemented against Fabric/vanilla realities.
3. **Feasibility constraints are documented, not hidden** (§8 and the
   per-doc constraint sections).
4. **Dynamic re-enable fails safe** until proven otherwise (THREADING.md).

If any of these corrections turn out to be wrong in light of new research,
the reasoning will be stated here explicitly — never silently reverted.
