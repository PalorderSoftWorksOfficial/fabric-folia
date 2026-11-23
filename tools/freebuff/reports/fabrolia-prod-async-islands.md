# Fabrolia prod/current architecture concepts

Source inspected: https://github.com/DUzzL/fabrolia/blob/prod/current/docs/ASYNC-ISLANDS.md

This is a paraphrased architecture reference, not copied implementation code.

## Topology and ownership

- A region cell is an 8x8 chunk grid cell.
- Populated cells form connected islands; distinct islands are separated by at least three unloaded cells (at least 24 chunks / 384 blocks).
- The gap is a locality/topology constraint, not permission for arbitrary callbacks or cross-island access.
- Each island has stable identity across split/merge, with deterministic survivor rules and explicit lifecycle events.
- A worker may touch a chunk/entity/block entity only while it holds the corresponding island/region ownership claim.
- Main owns global-lane chunks and may touch island-owned chunks only while holding the exact island claim or a global fence.
- Main-only work is bucketed per island and processed in queue order under one claim; async island execution must not let Main block on a running island when that island can require Main.

## Time model

Fabrolia documents three distinct clocks:
- W: global, real-time anchored world clock for day/night, weather and global time commands.
- L: monotonic logical tick per island for relative timers and island-local scheduled deadlines.
- G: server-wide heartbeat for infrastructure only.

Absolute deadlines must be explicitly assigned to a clock and translated when ownership changes. Scheduled ticks and absolute block-entity counters require re-keying across island merge/handoff. Never let a lagging island freeze global day/night or accidentally advance another island's local timers.

## Player and cross-island work

- Player connection ticking is not a simple entity tick; player lifecycle and packet work can be split across phases.
- Player ownership must be checked at phase boundaries, especially after teleport, mount/dismount, removal and respawn.
- Work the owner cannot execute is routed through a single ordered Main FIFO with explicit per-player ordering and a defined drain point.
- Main fallback retains source ownership while it runs; a worker must not release its claim and then let Main access the same world state without a transaction.
- A Main fallback request should stop the island at a safe unit boundary, wake Main and complete at a safe point rather than blocking the island worker on a Main operation that cannot run.
- Cross-dimension transfers require ownership of both endpoints and serialized preparation.

## Validation and tradeoffs

- The production branch documents a 40-client stand, functional verification and timed performance scenarios.
- It requires vanilla parity for farms, redstone and datapacks; parallelism that changes entity order is treated as a semantic deviation, not a free optimization.
- The docs explicitly note that independent clocks and concurrent island roots do not themselves guarantee throughput. Main-only fallbacks and cross-island dependencies remain bottlenecks.
- A single large island/base cannot be split just because multiple cores are available; improvements there require profiling and safe subpass optimization.

## Implications for Fabric-Folia

Use these as invariants and acceptance questions, not implementation templates:
1. Is ownership world-qualified and single-writer across all state?
2. Are split/merge identity and task migration atomic?
3. Are W/L/G semantics explicit for every absolute timer?
4. Can Main make progress without waiting for a worker that needs Main?
5. Are player connection phases and packet queues linearizable through teleport/respawn/disconnect?
6. Are cross-dimension transfers and saves fenced consistently?
7. Are vanilla-order-sensitive paths kept serial unless equivalence is proven?
8. Are multi-island and one-hot-island benchmarks reported separately?

Do not copy or derive source implementation from this GPL-3.0 repository. Fabric-Folia remains Apache-2.0 and must implement its own system.
