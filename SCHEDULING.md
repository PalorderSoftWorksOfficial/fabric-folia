# Fabric-Folia — Scheduling

How work is dispatched: the worker pool, the EDF coordinator, the region tick
pipeline, and the plan for partitioning vanilla's tick order.

## The shape

```
Region Scheduler (coordinator, 1ms scan)
       |
       +-- worker 1  ─┐
       +-- worker 2   ├─ bounded pool; regions are NOT pinned to workers
       +-- worker 3   │
       +-- worker N  ─┘
       |
Global context ("global region"): one queue, one tick() per 50ms
```

- **Bounded pool, not a thread per region.** Regions are dispatched onto
  available workers as they come due; a region may run on different workers
  across ticks. `WorkerPool` is deliberately dumb (wake, run, shutdown); all
  policy lives in `RegionScheduler`.
- **EDF dispatch.** The coordinator scans READY regions whose
  `nextTickDeadlineNanos` has passed and submits one tick job per due region,
  earliest-deadline-first. Duplicate dispatch is made harmless by two in-job
  guards (state re-check + deadline re-check) instead of cross-thread
  bookkeeping; correctness never depends on scan timing.
- **Not `Executors.newFixedThreadPool`.** A raw pool gives threads, not
  scheduling: deadline awareness, fairness, and per-region timing live in the
  scheduler, which is what the spec demands and what makes the policy
  reviewable.

## Independence (spec 6)

Each region's next-tick deadline is its own, written at its tick end (50ms
cadence). A slow region — even one taking 140ms per tick — only delays
itself: it occupies a worker longer, but no other region's deadline moves.
With fewer due regions than workers, every region holds 20 TPS independent of
every other. There is no global tick barrier; if one ever becomes necessary
for a specific subsystem, the correctness reason will be documented inline at
that point (none has been needed so far).

Implementation note: the coordinator and the regionizer share a time base
(`System::nanoTime` by default); tests inject matching clocks.

## The region tick pipeline (engine level)

```
tryBeginTick(region)          single-owner latch; fails if not READY
  → advanceTickCounter()      time base = ticks STARTED (delayed tasks)
  → drain task queue          tasks scheduled INTO this region from any
                              context; delayed tasks not yet due re-queue
  → region tick body          engine hook; vanilla steps land here
  → recordTickDuration()
  → completeTick(region)      regionizer tick-end protocol:
                              merges → own-merge check (→TRANSIENT)
                              → dead-section purge → split attempt
```

Inside a region, the tick is logically synchronous — exactly one context
executes everything above. Parallelism exists *between* regions.

### Delayed tasks

Delays are measured in the target's own tick counter ("ticks started"): a
task scheduled with delay d executes during the d-th future tick's drain. The
counter survives merges (a merged region's queue re-homes with its deadlines
intact) and dies with the region (DROP policy). The same mechanism powers
`RegionScheduler.runDelayed`, `EntityScheduler.runDelayed`, and the global
context's `runDelayed`.

## Partitioning vanilla's tick order (spec 5) — verified pipeline and slice 1

The spec forbids assuming vanilla's ordering from memory, so 26.2's real
pipeline was read from the jar (javap on the Loom-produced named artifacts;
all names verified 2026-09-14):

```
MinecraftServer.tickServer
  └─ ServerLevel.tick(BooleanSupplier)          per dimension, on the server thread
       ├─ global passes: world border, weather cycle, sleep/time, tickTime
       ├─ tickPending: blockTicks.tick(gameTime, 65536, …), fluidTicks.tick(…)   ← SERVER THREAD (not yet regionized)
       ├─ raids, blockEvents
       ├─ ServerChunkCache.tick(BooleanSupplier, boolean)
       │    ├─ executor/pump: worldgen + light tasks (chunk worker threads, vanilla's own pool)
       │    ├─ tickChunks():
       │    │    ├─ spawning pass: tickSpawningChunk per selected chunk
       │    │    ├─ forEachBlockTickingChunk(Consumer)  ← THE SEAM (Mixin @Redirect)
       │    │    │      per chunk: ServerLevel.tickChunk(chunk, randomTickSpeed)
       │    │    │      = tickPrecipitation + section random ticks
       │    │    └─ tickCustomSpawners
       ├─ tickBlockEntities()                        ← SERVER THREAD (not yet regionized)
       └─ entity pass: EntityTickList.forEach → tickNonPassenger …   ← SERVER THREAD (not yet regionized)
```

**Slice 1 (implemented, opt-in):** the `forEachBlockTickingChunk` call is
redirected; vanilla keeps selection, Fabric-Folia executes each selected
chunk's `tickChunk` on the owning region's worker (see ARCHITECTURE.md §8 for
the end-to-end description and live evidence). Vanilla invariants preserved:
chunk *selection* is byte-identical vanilla logic; each chunk's work executes
exactly once per pass, now in the owning region's context instead of inline;
per-chunk RNG reads `ServerLevel.random`, which a dedicated server
provisions as the thread-safe `ThreadSafeLegacyRandomSource` (verified —
AtomicLong-seeded) so multi-worker reads are sound.

**Known interleaving (documented boundary of slice 1):** scheduled ticks and
block entities (server thread) can mutate neighboring blocks while a region
worker random-ticks an area. Single-writer-per-chunk-data holds; full
closure requires regionizing those passes too.

**Slice 1's effects are proven on the real surface (TESTING.md):** worker-
executed `tickChunk` demonstrably mutates world state (deterministic
covered-grass deaths under pin, dirt control stable, one-pass in-flight
residue after unpin, then zero new mutations — dispatch audit shows the last
pass at the unpin second), with execution attributed to named
`Fabric-Folia-Worker-N` threads and zero diagnostics violations.

**Remaining partitioning work (in dependency order):** scheduled ticks →
block entities → entity pass → spawning, then chunk-holder-attached
ownership (so regions own holders, not per-pass registrations), then the
cross-region handoffs (§7) for each newly-regionized pass.

Open research items recorded now:

- Whether `LevelTicks.tick`'s 65536-budget drain is cleanly partitionable
  per region without reordering vanilla's drain semantics.
- The entity pass's real dependency on the chunk system (spec 26's
  chunk-ownership research feeds this).
- Whether any vanilla step reads across the full world in one tick (world
  border interactions, weather, time) — those route global, and their
  frequency bounds the global context's load (spec 4's anti-bottleneck rule).

Failure handling at the engine level is documented in ARCHITECTURE.md §5:
task failures are reported with region + exception, never silent; a failing
tick-end protocol aborts the region (fail-safe kill). The full region-vs-
server-halt isolation policy (spec 26) is finalized when real gameplay state
flows through the pipeline, and will be documented here with its reasoning.
