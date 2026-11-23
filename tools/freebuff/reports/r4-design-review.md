# R4 — cross-region world-mutation boundary

**Original design status: OPEN. A 2026-11-23 fail-closed interlock now blocks built-in worker-side gameplay mutation (see the addendum below); the synchronous cross-region mutation protocol itself remains open and parallel built-in gameplay stays disabled.**

## Finding

The staged block-entity and scheduled-tick bodies can reach vanilla mutation chains whose read/write footprint is not bounded by a fixed radius around the source chunk. Neighbor updates can cascade; POI state and block-event collections have shared per-level state; explosions and callbacks can touch multiple chunks. A loaded 3×3 neighborhood is a chunk-residency precondition, not proof that a region exclusively owns every object reached by the call graph.

## Rejected shortcuts

- A fixed-radius loaded-chunk or ownership halo. It cannot bound neighbor cascades or mod callbacks.
- Deferring `Level.setBlock`, POI changes, block events, explosion writes or entity-health changes while returning an optimistic synchronous result. Callers branch on the actual vanilla return value and ordering.
- Using the existing server-thread deferral queue for a synchronous world write without a pump and exclusive-writer protocol. A worker that waits for the server thread can deadlock if the server thread is waiting for that worker at the tick barrier; processing a write while other region workers continue also leaves concurrent writers.
- Calling R4 mitigated merely because synchronous chunk loads are rejected by `RegionChunkAccessGuard`. That guard prevents a worker from blocking on a chunk load; it does not serialize arbitrary world mutation.

## Safe design direction

A correct cross-region mutation bridge must preserve the synchronous vanilla call contract and ensure a single writer for the whole mutation cascade, not just its initial target.

The practical designs are:

1. **Global single-writer mutation window.** Pause/quiesce all region workers at a coordinated barrier, service mutation requests on the server thread, complete the full vanilla cascade there, then release the workers. The barrier must pump requests while waiting and must be re-entrant for nested writes; no worker may block waiting for a server-thread pump while the server thread is blocked in an unconditional worker join.
2. **Conservative serialized execution.** Keep mutation-heavy staged bodies on the server thread while the world has multiple active regions. This preserves vanilla semantics but gives up parallelism for those bodies. A one-writer-per-world mode is only safe if region topology and all writers are held stable for the entire staged phase.

Neither protocol is implemented by this pass. The existing defaults for entity, block-entity/scheduled-tick and random-tick staging remain disabled. Enabling them is experimental and must not be represented as production-safe until R4 is closed.

## Required validation before closure

- Deterministic tests for cross-boundary `setBlock` return values, neighbor chains and nested callbacks.
- POI add/remove and block-event ordering tests across region boundaries.
- Explosion and entity/block-entity callbacks that touch multiple chunks.
- A barrier stress test proving no worker/server-thread deadlock and no overlapping world writers.
- Live server testing with multiple players and regions, STRICT ownership enabled, and instrumentation proving one writer across the complete cascade.

Until those pass, R4 remains a P0 release blocker for parallel regionized world mutation.


## 2026-11-23 addendum — built-in worker mutation interlock

The runtime now fails closed instead of allowing the audited staged bodies to execute cross-region mutation chains without an ownership protocol.

FabricFoliaMod.resolvePatchStates unconditionally resolves these built-in requests inactive, regardless of configuration:
- entity tick staging
- block-entity tick staging
- scheduled block/fluid tick staging
- player connection/physics staging
- random-tick staging
- packet-to-region mutation dispatch

If the config requests any of them, startup emits an R4 safety warning. The region ownership/scheduler infrastructure may still start, but these mutation-bearing vanilla paths stay on the server thread. This is a conservative server-thread fallback, not an implementation of the synchronous handoff protocol.

Additional engine-off fixes restore vanilla fall-through: all patches resolve inactive before the general.enabled=false return; chunk registration is not attached and the stall probe is not started; the connection queue executes its original vanilla action when no engine/packet dispatcher is active; ticket, chunk-tracking and light-engine deferrals bypass when the engine is absent.

Live evidence: the same final 26.2 artifact boots with general.enabled=false (0 active patches, no region scheduler/workers) and with the engine enabled but every worker mutation option requested (R4 warning, worker staging disabled). A disposable redstone-lamp circuit powered and unpowered correctly in both modes. Full details and exact RCON steps are in engine-off-and-redstone-smoke.md.

What this does not close: there is still no synchronous cross-region mutation bridge. The interlock removes the built-in staged mutation path from the worker, and therefore closes that built-in execution hazard by disabling the feature. Third-party tasks submitted through the region scheduler must still obey the API's owner-confinement contract; arbitrary cross-region writes from such tasks are not made safe by this interlock. Do not claim general Folia-equivalent cross-region mutation support or performance.
