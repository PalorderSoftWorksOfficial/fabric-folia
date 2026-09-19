# Fabric Folia — Threading

The ownership model, the thread-context diagnostics, and the disable/quiescence
contract. Companion to ARCHITECTURE.md §3 (concurrency model) and §6
(diagnostics).

## The ownership model

**Safety comes from ownership and scheduling, not locking.** Every piece of
mutable gameplay state is owned by exactly one region at a time; only that
region's current execution context may mutate it directly. A region in state
TICKING is owned by exactly one worker; between ticks it is owned by no
execution context and its structure changes only under the regionizer's
structure lock.

The three rules any contributor (or diagnosing admin) needs:

1. **If code runs inside a region context, it may touch only that region's
   state.** Anything else goes through a scheduler handoff: enqueue a task
   into the target region's queue; the target runs it during its own tick.
2. **If code runs on a network/IO/global thread, it may not touch any
   region-owned state.** Schedule it. `GlobalScheduler.runOrSchedule` is the
   sanctioned entry point for global-context callers.
3. **A lock is never the answer to a cross-region access.** If a design seems
   to need `synchronized`/`volatile`/`ConcurrentHashMap` as its *primary*
   safety mechanism, that is a design defect to fix by routing through
   ownership, not a shortcut to ship. (Concurrency-safe structures still
   appear where multiple producers are *legal* — e.g. task queues — but they
   are plumbing, not the safety mechanism; see ARCHITECTURE.md §3.)

## Thread context — what code can ask

`com.palordersoftworks.fabricfolia.api.ThreadContext` (public API):

- `kind()` — REGION / GLOBAL / NETWORK / IO / UNKNOWN.
- `currentRegion()` — the owning region, if the current thread is executing a
  region's tick or a task inside one.
- `mayAccessRegion(target)` / `assertRegionAccess(operation, target)` — the
  assertion other mods use to check their own assumptions; in STRICT mode a
  failed assertion throws, in WARN it logs, in OFF it is nearly free.

Worker threads enter/exit region contexts at tick boundaries with explicit
tokens; a worker that exits without clearing would poison every subsequent
task, so the dispatch machinery enforces try/finally. At most one region
context can legally exist on a thread — a nested one is itself a violation.

## Validation modes (spec 8)

| Mode | Behavior | Cost | When |
|---|---|---|---|
| `STRICT` | throw `ThreadContextViolationException` on violation | per-access | development builds (default in dev); catches violations at the source |
| `WARN` | log the full diagnostic, continue | per-access | production default in the shipped config |
| `OFF` | no checks | one enum compare | benchmarks / trusting-ops deployments |

**Why WARN is the production default, and why that is justified rather than
assumed:** per-access STRICT checking has real overhead that must be
*measured* before being recommended to every production server (spec 8: don't
assume). WARN keeps violations operator-visible — a violation is logged with
full context, never swallowed — while OFF exists for benchmarking. If
benchmarks (spec 20) later show STRICT's cost is negligible, the default
flips with evidence. A cheaper sampling-based check is the alternative if the
full check proves expensive.

## The violation report

A violation is required to be actionable, not a bare stack trace. The rendered
report contains: the operation attempted, the current execution context, the
target's actual ownership, the current thread name, the expected context, and
the correct scheduling entry point. Shape:

```
Fabric-Folia Thread Context Violation

Operation: Entity state access
Current execution context: Region world:4
Target ownership: Region world:5
Current thread: Fabric-Folia-Worker-3
Expected context: Region world:5

This access is unsafe because the target object is owned by another region.
Schedule the operation through the appropriate RegionScheduler entry point.
```

In STRICT mode the same text is the exception message.

## Random state (per-thread sources, spec 31)

Vanilla gives every world ONE `LegacyRandomSource` (ThreadingDetector-guarded:
it throws `Accessing LegacyRandomSource from multiple threads` on concurrent
use). Region workers executing `tickChunk` race that single instance — a real
defect observed in every multi-region live run before this contract existed.

**The fix (measured, bytecode-verified):** `LevelMixin` wraps each level's
`random` field at construction with `WorkerRandoms`, a dispatching
`RandomSource`. A thread inside a REGION ownership context draws from its own
lazily-created source (fresh unique seed, same mechanism as vanilla's own
per-level seeds and its `createThreadLocalInstance` precedent); every other
thread — server thread, global, async, network, IO, unknown — gets the
original instance untouched.

The consequences an implementer must know:

* **Server-thread sequences are bit-identical to vanilla.** The original
  source is never replaced or re-seeded.
* **Dispatch keys on REGION context only.** A GLOBAL-context task touching
  `level.random` deliberately still hits the guarded original — that IS an
  ownership violation under this architecture, and masking it would hide the
  violation from STRICT diagnostics (mandate §24).
* **No cross-region stream coupling.** Each region thread's draws are
  independent; vanilla's observable seeding behavior (what a player could
  see) is unchanged — random tick *placement* within a chunk depends on
  positions and the tick speed, not on which source instance drew.
* **Lifecycle:** active while the engine is live, deactivated before the
  worker pool drains at shutdown; per-thread sources are reclaimed with
  their threads (workers die with the pool).
* The compat harness greps the ThreadingDetector signature in
  `diagnostics-clean`; a regression cannot pass a run again.

## Other mods' code (spec 23 groundwork)

A violation originating in a third-party mod's code path is reported through
this same machinery. Identifying the offending mod ID/class/method precisely
requires the integration-phase instrumentation; the diagnostics sink and API
assertion surface (`ThreadContext`) exist now so that wiring has somewhere to
land.

## What the intercept slice enforces today

The first vanilla interception (per-chunk random ticks, see REGIONS.md) runs
the enforcement machinery on a real work path, not just in tests:

- Region worker threads enter the owning region's thread context before the
  vanilla `ServerLevel.tickChunk` body and exit it in a `finally` — the
  dispatch machinery owns this, so a throwing tick body cannot leak a context
  onto a pooled thread.
- The work path itself asserts its own legality:
  `ThreadContext.assertRegionAccess("Chunk random-tick execution", region)`
  runs before every chunk tick, so the STRICT/WARN/OFF mode chosen in config
  is live exactly where world state is first mutated off the server thread.
- The server thread, meanwhile, no longer executes the per-chunk random-tick
  pass in an intercepted world — this is the first genuine split of vanilla
  work between two threads, which is why the context discipline above is
  mandatory rather than aspirational.
- On server stop, attachments are removed before workers stop: the mixin sees
  no attachment and falls through to vanilla's inline consumer, so the
  shutdown window executes no region-thread work at all.

## Staged gameplay slices — the threading contract

Three further vanilla passes now execute on region workers via the staging
hub (`RegionStageHub`): entity tick bodies, block-entity tickers, and
scheduled block/fluid tick executions. The contract, per slice:

**Capture (server thread, vanilla's own pass).** Vanilla's loop decides
*what* to tick exactly as it always has — distance gating, list management,
the `pendingBlockEntityTickers` merge, the `LevelTicks` drain order. The
capture mixin hands the already-constructed body to the hub instead of
running it. No vanilla decision logic is replicated anywhere; the disarmed
branch is byte-for-byte vanilla.

**Dispatch (server thread, end of tick).** `flushGameplay()` runs in
END_SERVER_TICK: staged bodies resolve their owning region *at dispatch
time* (post-migration truth, not capture-time truth), and each body is
enqueued into its region's task queue. Unowned positions run inline on the
server thread — zero behavioral delta for work the regionizer does not own.
A region that dies between lookup and enqueue drops the body (the next
vanilla pass re-stages; documented, bounded loss).

**Execution (region worker).** Bodies run inside the owning region's thread
context, under the same STRICT/WARN/OFF enforcement as random ticks. A
throwing body is contained by the failure policy; it cannot leak a context
or corrupt a neighbor.

**Scheduled-tick writes.** A region worker executing a staged scheduled
tick may schedule further ticks — but it must never mutate the `LevelTicks`
container's staged-tick tree concurrently with the server thread.
`LevelScheduleTickMixin` therefore defers worker-context schedules into
`ScheduledTickDeferral`, and the server thread replays them (in capture
order) at flush time. Container mutation stays single-threaded by
construction; deadline semantics are preserved because the deferred tick is
the already-constructed vanilla object.

**Transitions.** An entity teleport issued from a region worker whose
destination resolves to another region or world does not mutate either
side's state mid-flight: `RegionTransitions.dispatch` hands the vanilla
transition to the destination context and returns. Same-region teleports
stay inline vanilla.

**Network threads.** The packet boundary is the pending-action queue in
`Connection`: vanilla submits packet handling there from the Netty event
loop, and the queue drains on the server thread (`Connection.tick`) or, in
an eager window, on the loop itself. `ConnectionPacketDispatchMixin`
classifies the executing thread — NETWORK for the bounded duration of an
event-loop-drained task, restored afterwards; the server-thread path is
never touched (the earlier draft tagged the flushQueue head, which would
have mislabeled the server thread that also drains this queue). Each
drained action then flows through `NetworkDispatch.runOnOwner`
(`ConnectionFlushQueueMixin`): on the server thread it executes exactly
where vanilla would have it — zero behavioral delta — and from a network
context it hops to the global scheduler instead of mutating game state on
the event loop. Network contexts classify and route; they never mutate.
Measured evidence is routing-level (unit tests through the real engine)
plus crash-free drains on a live server; a full client session exercising
the loop-drain window live remains open.

## Entity ownership hooks (server-thread capture)

Entity add/remove/move capture (mandate §15) is the second live vanilla
integration, with its own documented boundary:

- **All hooks run on the server thread.** Vanilla adds, removes, and moves
  entities there; `EntityRegionTracker` forwards synchronously into the
  registry's atomic migrate protocol. This single-writer discipline is what
  makes the resolve→register/migrate sequence race-free — if entity ticking
  later moves onto region workers, these entry points are re-plumbed onto
  region contexts (the registry protocol itself is already thread-safe and
  storm-tested).
- **Movement re-homing is gated on chunk-boundary crossings.** Region
  ownership cannot change within a chunk, so the per-entity hook compares a
  cached packed chunk and only contacts the regionizer on a crossing — the
  hot path (same-chunk position updates) never takes the structure lock.
- **Failures are contained per entity.** A tracker exception un-caches that
  entity's hook (going inert, never throwing repeatedly) and routes to the
  error sink; vanilla's add/move/remove continues regardless.
- **Removal clears the cache before unregistering** — dimension transfers
  re-cache through the new dimension's add path, and no hook observes a
  removed entity's ownership state.

## Disable and quiescence (spec 16) — the contract

`/folia config enabled false` will be a real state machine, not a flag flip.
The **guarantee to be proven** (with tests, per spec 16/18) before the disable
command ships:

```
No region remains asynchronously active
        AND
No mutable region-owned state is being concurrently modified
        AND
Vanilla/Fabric can safely resume single-threaded global execution
```

The mechanism that plausibly satisfies these is an orderly quiescence
sequence: stop accepting new region-bound work → let in-flight ticks finish
(each region's `completeTick` is the natural per-region barrier point) →
drain or explicitly drop per-queue (documented policy: region queues DROP on
death; the global queue DRAINS at quiescence) → verify no worker is executing
region-owned mutation → return the tick loop to vanilla. **Not** a literal
"resync of region timing" — the three conditions above are the requirement;
the mechanism is whatever satisfies them.

**Status: partial.** The integration phase landed the first real piece of
this: intercept *detach* is live. Removing a world's attachment makes the
vanilla redirect fall through to its own inline consumer — the server thread
resumes the work single-threaded, with no in-flight region work in the
shutdown window because detachment precedes worker stop. What is still
missing for the full disable command: a runtime quiescence barrier (letting
in-flight region ticks finish on demand, not just at shutdown), the drain/
drop policy choice wired to that barrier, and the proof tests for the three
conditions above. The foundation's plumbing (queues with explicit drain
policies, the tick-end barrier point, scheduler `close()`) is in place and
now exercised by a real vanilla path.

## Dynamic re-enable (spec 16) — fail safe

Re-enabling after a runtime disable is **not assumed safe**. Between disable
and re-enable, other mods and vanilla may have created or cached state under
single-thread assumptions; re-establishing regionized execution over that
state is the least certain transition in the whole design.

**Decision: the architecture fails safe.** Until the re-enable path is
positively demonstrated safe under test (a deliberate, resourced effort —
not an afterthought), a runtime re-enable request will be *refused* with a
clear message requiring a restart. This is recorded as an explicit
architecture decision, not an omission; if the demonstration later succeeds,
this document will be updated with the evidence.

## Saving (spec 25) — direction recorded

World/chunk/entity/player saving under regionized execution must never race
region mutation, and global save logic needs ownership-first treatment like
everything else. The leading mechanism is **owner-context execution of save
steps** (region-owned state is serialized by the owning region's context,
into snapshot buffers that the IO path flushes), with the choice to be
justified in full here when saving is implemented — the decision is *not*
being made implicitly by whatever the code happens to do first.

## Chunk lifecycle — ownership release on unload (mandate §20)

Chunk **registration** happens in the entity-ticking pass: every chunk vanilla
selects for block ticking is regionized via `addChunk` before its work is
scheduled to a region worker. Chunk **release** happens at vanilla's
definitive departure point: `ServerLevel.unload(LevelChunk)` (called exactly
once per holder, after save, on the server thread — verified against the 26.2
jar bytecode) routes the position to the world regionizer's `removeChunk` via
`ServerLevelUnloadMixin`.

Ownership is **position-exact**: the vanilla entity-ticking pass re-offers the
same loaded chunk every tick, so the regionizer records distinct positions per
section, making `addChunk` idempotent and exactly one `removeChunk` per unload
able to empty a section. When a section empties, the halo recalculation may
mark it and its buffer dead; dead sections accumulate until a region's tick
end purges them — which is what makes the tick-end **split** path (and
region death) reachable from real chunk churn rather than only from tests.

The observable evidence of the loop closing:

```
/folia metrics
  Chunks: registered=<n> unregistered=<m>   # n-m = currently owned positions
/folia regions
  # regions appear when chunks register, and die when their last section empties
```

Both counters are wired from the regionizer's real registration events; before
this hook existed, unloading never shrank the regionizer and
registered−unregistered could only drift apart.
