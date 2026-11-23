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

## Whose code runs in REGION context

When regionized gameplay is enabled, the staged body is the vanilla call site, not only vanilla code. A staged entity or block-entity body can contain another mod's tick implementation, and a staged scheduled-tick body can contain another mod's block or fluid behavior. Those mods inherit the REGION context whether or not they were written for it.

This is why regionized gameplay is opt-in and why the default profile leaves entity, block-entity, scheduled-tick, random-tick and player-physics staging disabled. Item entity movement, merging and despawn therefore remain on the server thread by default. Redstone and interactive block updates stay on the main thread when the chunk/player staging gates remain off. There is no per-mod or per-entity-type staging policy.

## Partial and unsupported cross-region paths

The following flows are not first-class regionized operations:

| Flow | Current behavior |
|---|---|
| Player login placement | Not region-aware; the player is created and placed through vanilla's server-thread login path |
| Portal search and creation | Runs on the origin context; only the resulting teleport transition is routed to the destination context |
| Explosions | Not intercepted. An explosion triggered from a staged entity body runs in that body's region context and can mutate blocks and entities outside the region without a handoff |
| Direct `PlayerList.respawn` calls | Not covered by the packet-level respawn carve-out; mods calling it directly stay on the caller's context |
| Redstone and current-tick state | Global tick semantics; no per-region redstone time. Default block-entity, scheduled-tick and player-interaction paths remain on the server thread |
| Chunk state ownership | Ownership is a position-to-region map, not a property of `ChunkHolder`/`ChunkMap` state |

Do not treat these as implemented because a related path is routed. For example, teleport routing does not make portal creation or explosion damage region-safe.

## Rules for mod developers

- Do not access a different region's blocks, entities, or block entities from a REGION task.
- Use RegionScheduler for position-owned work and EntityScheduler for work that follows an entity.
- Keep GLOBAL callbacks short. Do not block the server thread.
- Keep async tasks independent of live world and server-owned state; hand back through the relevant scheduler.
- Do not infer safety from thread names. Use FabricFoliaApi.threadContext() where the API is available.
- Audit mods that use static mutable state, global caches, event callbacks, or assumptions that all gameplay runs on the main server thread before enabling regionized gameplay.

## Validation

Set threads.thread-check-mode to STRICT in a disposable test environment to expose ownership violations. WARN records violations and continues; OFF disables checks. Diagnostics cannot prove that a third-party mod is thread-safe.

A REGION worker asking for an absent chunk with `create=false` answers vanilla's `null` instead of joining the server-thread chunk executor; a request that must create or upgrade the chunk keeps vanilla's blocking join and depends on the quiesce pump draining the executor. Only the absent non-creating case is non-blocking. Async chunk APIs remain asynchronous and must not be joined from a region task. The tick barrier still preserves exclusive ownership and waits for a worker that is executing arbitrary blocking code; Java cannot safely terminate such a worker. The server-thread deferral path and all cross-context handoffs remain production release review items.
