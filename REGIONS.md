# Fabric-Folia — Regions

How Fabric-Folia partitions the world into independently-ticking regions, and
what "8×8" does and does not mean.

## The one thing to remember

**"8×8" is the default *region section* size — a bookkeeping cell — not a
promise about region shape or size.** Regions are dynamic: they grow by
merging, shrink by splitting, and can be any shape made of whole sections. A
region might be one section; it might be fifty. Nothing about gameplay
isolation is decided by the 8×8 number itself.

A fixed grid was rejected deliberately (and this is recorded in
ARCHITECTURE.md): two players standing near a fixed grid seam would force
either unsafe cross-seam access or a synchronization barrier at every seam —
reintroducing the single-thread bottleneck regionization exists to remove.
Dynamic regions with a merge-radius buffer are what make the four invariants
below achievable.

## The four invariants (in operator terms)

The regionizer maintains four properties at all times, derived from
PaperMC/Folia's public region-logic reference (clean-room; see ARCHITECTURE.md
§4 for attribution and licensing):

1. **Every chunk belongs to exactly one region.** No chunk is ever owned by
   two regions, even transiently, even across a merge.
2. **A region is never "close to" a foreign region.** Every position within
   the merge radius of a region's territory is owned by that same region (or
   is pending merge into it). There is always a buffer; nothing can read
   across a region boundary in one tick.
3. **A ticking region never grows while it ticks.** This is what lets a
   ticking region sync-load nearby chunks without racing a neighboring region
   that is also ticking. New activity near a ticking region lands in a
   temporary *transient* region that merges into the ticking one when its
   current tick ends.
4. **A region is always in exactly one of four states:**
   - **TRANSIENT** — exists but may not tick yet (e.g. it owes a merge, or it
     was just created as a holder near a ticking region).
   - **READY** — may be dispatched for its next tick.
   - **TICKING** — currently executing on exactly one worker. Exclusively
     owns its state for the duration of the tick.
   - **DEAD** — emptied out; inert. Its id is never reused in a run.

## Region sections

- An N×N-chunk cell (default **N = 8**, power of two, `region-section-size`
  in the config). The regionizer's bookkeeping — ownership maps, buffers,
  split recalculation — operates on sections, not chunks.
- A section has two independent properties:
  - **non-empty** — at least one loaded chunk position is registered in it; or
  - **alive** — empty but within the *empty-section creation radius* of some
    non-empty section: it is part of a region's buffer.
- **Dead sections** are empty *and* outside every buffer. They remain owned by
  their region (invariant 1 keeps holding for them) and are purged at tick
  end, when enough of them accumulate — recalculation is deliberately deferred
  so a flapping chunk load/unload cannot trigger splits constantly.

## Merging

Whenever a chunk appears (chunk holder created), the regionizer looks at all
regions within the merge radius + creation radius of that position. If there
is more than one, they merge into a single region — preferring a non-ticking
one, and preferring the larger one (less churn).

If every nearby region is **ticking**, a new TRANSIENT region holds the new
activity and merges into the ticking region at *its* tick end (invariant 3:
the ticking region never grows itself mid-tick). A non-ticking region that
carries a pending merge obligation is downgraded to TRANSIENT — it must not
tick while its buffer situation is unresolved.

Queued tasks follow their region: when regions merge, the absorbed region's
task queue is re-homed wholesale into the survivor, so nothing scheduled
against that territory is lost (regression-tested).

## Splitting

At tick end, if enough dead sections have accumulated in a region (the
recalculation gate: section count + dead load, and a dead-percentage knob),
the region's *alive* sections are flood-filled into connected components.
Any strict subset becomes a new READY region. Split children inherit the
parent's tick counter, so delayed tasks and relative deadlines keep their
meaning.

In practice: players clustered in two groups far apart who once shared a
region (because someone walked between them) end up split back into two
independent regions once the connecting territory empties out.

## Radii — where the defaults come from

`merge radius` and `empty-section creation radius` are **derived from the
server's effective simulation distance**, not fixed magic numbers:

- The buffer must be at least as wide as anything that can read across a
  region boundary within one tick — in Minecraft gameplay terms, bounded by
  the simulation distance (entities and scheduled ticks do not act beyond
  it). The derivation converts the simulation distance to sections and pads
  by one.
- The creation radius is one wider than the merge radius, so new activity
  always lands *inside* an already-buffered halo.

These are in region sections (Chebyshev distance). They are deliberately
**not** administrator-facing config options: an admin cannot meaningfully
judge a correctness-critical buffer width, and a too-narrow buffer is not a
performance trade-off — it is a corruption bug. If an operational need for
override ever appears, it will arrive with loud validation, not as a silent
knob.

`region-section-size` *is* admin-facing (`region.section-size-chunks`,
default 8): it is a coarse performance/bookkeeping knob, safe to leave at 8
for every realistic server.

## The slice that runs today

Regionization is live for one slice of vanilla behavior (opt-in:
`general.regionized-random-ticks`, default OFF). Each server tick, vanilla
still *selects* which chunks are block-ticking (its DistanceManager logic,
untouched), but the per-chunk work — `ServerLevel.tickChunk`: precipitation
plus random ticks (crop growth, grass spread, fire, ice/snow melt, leaf
decay) — executes on the owning region's worker thread:

- each selected chunk is registered with the world's regionizer (it joins or
  forms a region, exactly as the invariants above require — merge pressure
  from a pinned area builds one region, not conflicting neighbors);
- the chunk's work is enqueued into that region's queue and runs when the
  region ticks, single-owner, on a bounded worker (`Fabric-Folia-Worker-N`);
- when regions merge mid-flight, queued chunk work is re-homed into the
  survivor (regression-tested; verified live over 900+ dispatched passes with
  zero dropped tasks).

Everything else — scheduled ticks, block entities, entities, worldgen,
spawning — still runs on the server thread. That is the documented boundary
of the slice, not a hidden limitation (see ARCHITECTURE.md §8 and
THREADING.md).

`/folia pin <x> <z>` and `/folia unpin <x> <z>` are admin primitives that pin
an entity-ticking ticket area (overworld by default) so the slice is
observable on an otherwise empty server — the same effect a player standing
there would have.

## Testing

The invariants are enforced by tests, not by documentation
(`common/src/test/.../region/`):

- `RegionizerInvariantTest` — single ownership, buffer preservation, merge
  correctness, no-growth-while-ticking.
- `TickLifecycleTest` — the state machine: tryMarkTicking only from READY,
  tick counter semantics, tick-end protocol order (merges → transient check →
  purge → split).
- `RegionizerConcurrencyTest` — hammering add/remove/tick-begin/tick-end from
  many threads while observing invariants (liveness + no lost chunks).

Run with `./gradlew :common:test`.
