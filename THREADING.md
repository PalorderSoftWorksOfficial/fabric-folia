# Threading model

## Contexts

| Context | Owner | Allowed work |
|---|---|---|
| REGION | The region currently owning a world/chunk position | Region-local state for that region only |
| GLOBAL | Minecraft server thread during the pre-world-mutation phase | Short server-wide operations after region workers quiesce |
| ASYNC | Bounded async worker pool | Computation and IO independent of live world state |
| NETWORK | Fabric/Netty network event-loop thread | Network handling and scheduling handoffs |
| IO | IO worker | Serialization and file/network IO |
| UNKNOWN | Any unregistered executor or ordinary thread | No assumed ownership |

REGION is a logical execution context, not a dedicated thread. A region may execute on different workers over time.

## Coordination barrier

Vanilla selects work on the Minecraft server thread. Selected bodies are staged and executed by region workers between server-thread mutation phases. At the start of the next server tick, the server thread closes the worker gate and waits for in-flight work to quiesce. Global callbacks run on the server thread after this barrier.

This is a staged single-writer design with explicit handoff points. It is not the same as independent Folia region tick loops.

## Rules for mod developers

- Do not access a different region's blocks, entities, or block entities from a REGION task.
- Use RegionScheduler for position-owned work and EntityScheduler for work that follows an entity.
- Keep GLOBAL callbacks short. Do not block the server thread.
- Keep async tasks independent of live world and server-owned state; hand back through the relevant scheduler.
- Do not infer safety from thread names. Use FabricFoliaApi.threadContext() where the API is available.
- Audit mods that use static mutable state, global caches, event callbacks, or assumptions that all gameplay runs on the main server thread before enabling regionized gameplay.

## Validation

Set threads.thread-check-mode to STRICT in a disposable test environment to expose ownership violations. WARN records violations and continues; OFF disables checks. Diagnostics cannot prove that a third-party mod is thread-safe.

The server-thread deferral path and all cross-context handoffs remain production release review items. This document does not claim that every fallback path has been proven deadlock-free under arbitrary load.
