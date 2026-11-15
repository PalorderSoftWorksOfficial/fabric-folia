# Mixin inventory

Fabric-Folia uses mixins only at interception points not exposed by the Fabric lifecycle/command APIs. The configuration is `fabric/src/main/resources/fabricfolia.mixins.json`; it declares `required: true` and `defaultRequire: 1`. The candidate verifier also checks that every declared mixin class is present in the built JAR.

This inventory describes source intent. It does not prove that an injection applies on a live 26.2 server. A dedicated-server boot that reports successful application of every required mixin remains a production release gate.

| Mixin | Target / responsibility | Feature boundary |
|---|---|---|
| `ChunkMapTrackingMixin` | `ChunkMap.move`, `addEntity`, `removeEntity`; defers chunk/entity tracking mutations to the server thread when necessary | Player/entity ownership handoff |
| `CommandsMixin` | `Commands.performCommand`; command execution context and timing instrumentation | Diagnostics |
| `ConnectionFlushQueueMixin` | `Connection.flushQueue`; preserves packet flush ordering around staged connection work | Player path |
| `ConnectionPacketDispatchMixin` | `Connection.runOnceConnected`; connection packet dispatch accounting | Player path |
| `DistanceManagerTrackingMixin` | `DistanceManager.addPlayer`, `removePlayer`, `runAllUpdates`; serializes ticket/tracking operations | Chunk ownership safety |
| `EntityMixin` | Entity position/removal lifecycle hooks and optional collision optimization | Entity tracking / optional optimization |
| `EntityTeleportMixin` | `Entity.teleport(TeleportTransition)` capture and destination routing | Teleport path |
| `LevelBlockEntityTickMixin` | `Level.tickBlockEntities`; captures supported block-entity tick bodies | `general.regionized-gameplay` |
| `LevelEntityTickMixin` | `Level.guardEntityTick`; captures supported entity tick bodies | `general.regionized-gameplay` |
| `LevelMixin` | `Level` initialization and world identity hooks | Regionizer wiring |
| `LevelScheduleTickMixin` | `LevelTicks.schedule`; scheduled tick queue interception/ownership bookkeeping | Scheduled tick integration |
| `LevelTicksQueryMixin` | `LevelTicks.hasScheduledTick`; query-side ownership/cache support | Scheduled tick integration |
| `LevelTicksTickMixin` | `LevelTicks.runCollectedTicks`; preserves selected scheduled-tick dispatch semantics | Scheduled tick integration |
| `MinecraftServerGuiMixin` | Dedicated server GUI frame and icon behavior | Branding / GUI only |
| `MinecraftServerMixin` | `MinecraftServer.processPacketsAndTick`, `tickChildren`, server brand; establishes the server-thread tick barrier and drains deferred server mutations | Core lifecycle / coordination |
| `PacketProcessorMixin` | `PacketProcessor.scheduleIfPossible`; routes eligible re-homed packets to the player's current region | Player path |
| `PacketUtilsMixin` | `PacketUtils.ensureRunningOnSameThread`; checks whether a packet handler is already in its owner's context | Player path |
| `PersistentEntitySectionManagerMixin` | Entity section manager add/remove lifecycle | Entity ownership tracking |
| `ServerChunkCacheMixin` | Chunk lookup/ticket paths, block-change broadcast deferral, and server-thread deferral | Chunk safety / network broadcasts |
| `ServerChunkCacheTickMixin` | `ServerChunkCache.tickChunks`; captures the vanilla chunk-ticking iteration seam | Staged chunk work |
| `ServerConnectionTickMixin` | `ServerConnectionListener.tick`; stages eligible connection ticks when the player-path gate is enabled | `gameplay.stage-player-path` |
| `ServerLevelTickMixin` | Server-level tick begin/end hooks | Region scheduler lifecycle |
| `ServerLevelUnloadMixin` | `ServerLevel.unload`; detaches world regionizer state | World lifecycle |
| `ServerPlayerTeleportMixin` | `ServerPlayer.teleport(TeleportTransition)` override | Player teleport path |
| `ServerPlayerTickMixin` | Server-player tick couplings such as chunk-view movement and game-mode tick | Player path / server-thread deferral |
| `TicketStorageMixin` | TicketStorage ticket mutation methods; defers cross-context ticket changes | Chunk ticket safety |

## Validation requirements

- Keep the JSON list synchronized with actual source files and the JAR contents.
- Do not lower `defaultRequire` or mark a required injection optional just to make a build pass.
- For each target version change, verify target descriptors and injection counts against the matching Minecraft runtime.
- A passing compilation and archive inspection are not a substitute for starting a dedicated server and verifying mixin application logs.
- Gameplay-related mixins must be exercised with the corresponding feature gate disabled and enabled in a disposable world.
