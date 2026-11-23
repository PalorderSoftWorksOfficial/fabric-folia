# Fabrolia reference research

Research snapshot: 2026-11-22. This is an architecture and release reference for clean-room engineering, not permission to copy implementation code.

## Release status and scope

- Latest GitHub release observed: tag f_3.2.6, published 2026-11-21.
- The Fabric artifact is named fabrolia-alpha_3.2.6.jar. Its published SHA-256 is 42678345eca6bc5b99ed9f3e405f98bc4d40f39ead15bcc1a55d5815a90dd3ca.
- GitHub marks this release as not a prerelease, but the artifact name and repository version identify it as alpha. Do not call it a stable production release without separate evidence.
- The f_3.2.6 release notes explicitly document a fix for a player moved, mounted or dismounted between the two halves of an island tick, and a fence preventing unrelated server-thread work from running inside a parked island tick.
- The same release notes still document a server-stop case when a routed body teleports or removes the very player whose own tick is executing. This is a concrete example of why a published release is not proof of complete stability.
- README describes Fabric and NeoForge support, Minecraft 26.3, Java 25, 8x8 chunk regions, connected populated regions grouped into islands, independently paced island clocks, player ticks on the player's island, and compatibility with Lithium/C2ME. It documents conflicts with Moonrise and Open Parties and Claims.
- AGENTS.md documents around 1,600 tests, source/bytecode wiring tests, a 40-protocol-client server verification harness, performance tests, teleport/island regression tests, Spark profile comparison, and full modpack mixin-export verification. This is repository documentation, not independently reproduced evidence in this session.

## Architectural concepts worth evaluating independently

1. Region cells and connected islands are distinct concepts. Fixed grid cells provide bounded ownership units; connected populated cells form a larger island that can share one logical tick and clock.
2. Independent island clocks can isolate a hot base from unrelated islands, but require explicit semantics for world time, logical time, deadlines, timer migration, cross-dimension coupling and catch-up after pauses.
3. Player connection work has two halves and cannot be treated as a simple entity tick. Ownership can change between phases through teleport, mount/dismount or lifecycle transitions. The owner must be revalidated at the phase boundary, and server-thread work must not execute inside a parked region turn.
4. Chunk delivery is a transactional lifecycle, not just a packet send. Ownership, snapshot generation, event-loop FIFO order, acknowledgements, retries and disconnect retirement must be coordinated.
5. Scheduled ticks need owner-local due-time semantics while preserving vanilla ordering, budgets, deduplication, and restoration of unentered work on failure.
6. Main-thread fences must be explicit and bounded in operational effect. A region waiting for Main must not permit unrelated Main operations to enter a half-completed turn, and Main must not wait for a worker that is itself waiting for Main.
7. Save jobs can be serialized per file and parallelized across files only after a consistent snapshot has been captured by the owning thread. Backpressure and fatal propagation need a documented policy.
8. Region split/merge and island topology changes require claim/epoch barriers so no two owners mutate the same world state and no accepted task is stranded during rehoming.

## Source references inspected

- Repository README: https://github.com/DUzzL/fabrolia/blob/f_3.2.6/README.md
- Agent/build/runtime guide: https://github.com/DUzzL/fabrolia/blob/f_3.2.6/AGENTS.md
- Region scheduler: https://github.com/DUzzL/fabrolia/blob/f_3.2.6/common/src/main/java/com/axalotl/fabrolia/common/regions/RegionScheduler.java
- Region worker pool: https://github.com/DUzzL/fabrolia/blob/f_3.2.6/common/src/main/java/com/axalotl/fabrolia/common/regions/RegionWorkerPool.java
- Island tick unit: https://github.com/DUzzL/fabrolia/blob/f_3.2.6/common/src/main/java/com/axalotl/fabrolia/common/regions/IslandTickUnit.java
- Island registry: https://github.com/DUzzL/fabrolia/blob/f_3.2.6/common/src/main/java/com/axalotl/fabrolia/common/regions/IslandRegistry.java
- Player ownership/tick classes: https://github.com/DUzzL/fabrolia/tree/f_3.2.6/common/src/main/java/com/axalotl/fabrolia/common/regions
- Chunk delivery: https://github.com/DUzzL/fabrolia/blob/f_3.2.6/common/src/main/java/com/axalotl/fabrolia/common/regions/ChunkDelivery.java
- Scheduled ticks: https://github.com/DUzzL/fabrolia/blob/f_3.2.6/common/src/main/java/com/axalotl/fabrolia/common/regions/IslandScheduledTicks.java
- Async save writer: https://github.com/DUzzL/fabrolia/blob/f_3.2.6/common/src/main/java/com/axalotl/fabrolia/common/save/AsyncSaveWriter.java
- Release notes: https://github.com/DUzzL/fabrolia/releases/tag/f_3.2.6

## Licensing boundary

Fabrolia is GPL-3.0. Fabric-Folia is Apache-2.0. Use this note only to compare high-level behavior and engineering requirements. Do not copy source, port algorithms line-by-line, translate implementation, or reproduce distinctive code structure. Independently specify invariants and implement from Fabric/Minecraft APIs and verified vanilla behavior.
