# Optimization-mod compatibility report — Minecraft 26.2

Status: **manager review**. Static inventory and target-overlap analysis only.
No production code was edited, no source file was changed, the scanner was not
rerun, and nothing was committed or pushed.

## 0. Provenance and inputs

| Item | Value |
| --- | --- |
| Scan | `tools/freebuff/compatibility/scan-26.2.json` (`"scan": "minecraft-26.2"`) |
| Minecraft | 26.2 |
| Loader | Fabric |
| Scan commit | `c2fbe2135945e4e0ef42fc70094d3605ef03627d` |
| Artifact root | `/tmp/fabric-folia-compat-jars` |
| Metadata manifest | `/tmp/compat-manifest.json` (Modrinth metadata only) |
| Fabric-Folia side | `fabricfolia.mixins.json` (29 declared mixins), `MIXINS.md`, `COMPATIBILITY.md` |

Every count in this report was recomputed from the scan JSON. Nothing was copied
from an earlier report. The manifest's `sha512` for every top-level artifact was
recomputed by the scanner and matched (`sha512_match: true` for all eight).

## 1. Top-level 26.2 artifacts (8)

All eight are declared `environment: "*"` (both sides) and were selected as the
top-level 26.2 optimization-mod set.

### 1.1 Identity and Modrinth metadata

| Mod | Artifact file | Modrinth version | Version ID | Project ID | Published |
| --- | --- | --- | --- | --- | --- |
| lithium | `lithium-fabric-0.25.3+mc26.2.jar` | `mc26.2-0.25.3-fabric` | `f7vZ0VWU` | `gvQqBUqZ` | 2026-07-29T12:19:55Z |
| c2me-fabric | `c2me-fabric-mc26.2-0.4.2-alpha.0.71.jar` | `0.4.2-alpha.0.71+26.2` | `qwS7dYKj` | `VSNURh3q` | 2026-11-19T12:40:43Z |
| ferrite-core | `ferritecore-9.0.0-fabric.jar` | `9.0.0-fabric` | `d5ddUdiB` | `uXXizFIs` | 2026-03-24T18:31:50Z |
| krypton | `krypton-0.3.1.jar` | `0.3.1` | `5WeL0Nkz` | `fQEb0iXm` | 2026-07-14T22:10:35Z |
| vmp-fabric | `vmp-fabric-mc26.2-0.2.0+beta.7.236-all.jar` | `0.2.0+beta.7.236+26.2` | `d6FfpWFI` | `wnEe9KBa` | 2026-07-02T09:28:54Z |
| scalablelux | `ScalableLux-fabric-0.3.0-alpha.0.3-all.jar` | `0.3.0-alpha.0.3+26.2` | `EKLUURiy` | `Ps1zyz6x` | 2026-07-09T12:25:09Z |
| carpet | `fabric-carpet-26.2+v260616.jar` | `26.2` | `bGrLxJ8v` | `TQTTVgYE` | 2026-06-17T00:08:13Z |
| carpet-tis-addition | `carpet-tis-addition-v1.83.3-mc26.2.jar` | `v1.83.3-mc26.2` | `jtyqAhAd` | `jE0SjGuf` | 2026-11-14T10:22:20Z |

The jar-internal `fabric.mod.json` versions differ from the Modrinth version
strings for three mods and are listed in the container table in §2:
Lithium `0.25.3+mc26.2`, Carpet `26.2+v260616`, Carpet TIS Addition `1.83.3`.

### 1.2 SHA-512 (verified) and source URL

| Mod | SHA-512 | Source URL |
| --- | --- | --- |
| lithium | `148b638f3c6229fbaf487120a2344a0af5e411a5aa6533d5db9d75da0a8c0d8304f63eb4cca13f4d03b2c9b4c23d559dd74c1d832422ef8a3087bd005e62a8bd` | `https://cdn.modrinth.com/data/gvQqBUqZ/versions/f7vZ0VWU/lithium-fabric-0.25.3%2Bmc26.2.jar` |
| c2me-fabric | `a45125e3eed782aebde7e2d434bd8a5821ac053f5eec3c6f16f75bd7b2fe9f4a4e0e87ee7b403192cba4481a56cc9458e10c682f9013c47eac6bcd3c648fd90e` | `https://cdn.modrinth.com/data/VSNURh3q/versions/qwS7dYKj/c2me-fabric-mc26.2-0.4.2-alpha.0.71.jar` |
| ferrite-core | `d81fa97e11784c19d42f89c2f433831d007603dd7193cee45fa177e4a6a9c52b384b198586e04a0f7f63cd996fed713322578bde9a8db57e1188854ae5cbe584` | `https://cdn.modrinth.com/data/uXXizFIs/versions/d5ddUdiB/ferritecore-9.0.0-fabric.jar` |
| krypton | `b8d9af34cd0050493afb8a6232cb8f785daa9d8887b7045f6e6a53c6bb9b5ffc4318fd9b0347a940eacfeba4773f10cb80ae0be1e79ce4c1888f96eda21e564e` | `https://cdn.modrinth.com/data/fQEb0iXm/versions/5WeL0Nkz/krypton-0.3.1.jar` |
| vmp-fabric | `2e0fd87e66f35f00f634176d4072a6c6d1eee965d1817833709ad3ea73e4f2719fa152d5e95b5ab8216b064c7db124b7f13838ebea87d92f7a72df82839e9bd5` | `https://cdn.modrinth.com/data/wnEe9KBa/versions/d6FfpWFI/vmp-fabric-mc26.2-0.2.0%2Bbeta.7.236-all.jar` |
| scalablelux | `ea1551c8728a726f6ee82fed3040af52791943ef99f6b63428ed770797f60677e0160975ad59ad0f51bcd4a4ec2b5da39c6913ab2379f2e25d2397132d469a94` | `https://cdn.modrinth.com/data/Ps1zyz6x/versions/EKLUURiy/ScalableLux-fabric-0.3.0-alpha.0.3-all.jar` |
| carpet | `8b8fac6979bd3153f5cfb4faa6bab52e1357eab814492a6658f3c0e1ac2856ad37a626c0a03a0839c39abb7bf56661f77b09d05d10ac01173bcdd373a33c6265` | `https://cdn.modrinth.com/data/TQTTVgYE/versions/bGrLxJ8v/fabric-carpet-26.2%2Bv260616.jar` |
| carpet-tis-addition | `5675ad02fb57d9b1b7ed83857f10d6fd36f4a7904587463e87f67440b83e4fce07af677bbf3e056136ea420c12145d5869c458c3c9d3f3bb7e30e0eb2b3417a5` | `https://cdn.modrinth.com/data/jE0SjGuf/versions/jtyqAhAd/carpet-tis-addition-v1.83.3-mc26.2.jar` |

### 1.3 Manifest gaps and declared dependencies

- `dependencies` is an empty list for seven mods. That is a manifest value, not a
  claim that they have no runtime requirements.
- Carpet TIS Addition lists one required dependency: project `TQTTVgYE`
  (Carpet), with `version_id`, `file_name` and `dependency_type: required` but no
  pinned version ID.
- No other manifest field is missing for these eight artifacts.
- No Modrinth project IDs, version IDs, URLs or hashes are inferred here; every
  value above comes from `/tmp/compat-manifest.json`.

## 2. Container and mixin-config inventory (40 containers)

`container_total` in the scan is 40: 8 top-level JARs plus 32 nested JARs
(C2ME 29, Krypton 1, VMP 1, TIS 1). Config counts are
`mixin classes / server-applicable classes`.

| # | Top-level mod | Depth | Container JAR | Module ID | Module version | Mixin configs (classes/server) |
| --- | --- | --- | --- | --- | --- | --- |
| 01 | lithium | 0 | `lithium-fabric-0.25.3+mc26.2.jar` | `lithium` | `0.25.3+mc26.2` | `lithium-fabric.mixins.json` 6/6<br>`lithium.mixins.json` 286/271 |
| 02 | c2me-fabric | 0 | `c2me-fabric-mc26.2-0.4.2-alpha.0.71.jar` | `c2me` | `0.4.2-alpha.0.71+26.2` | — |
| 03 | c2me-fabric | 1 | `asyncutil-0.1.0.jar` | `com_ibm_async_asyncutil` | `0.1.0` | — |
| 04 | c2me-fabric | 1 | `c2me-fabric-base-mc26.2-0.4.2-alpha.0.71-all.jar` | `c2me-base` | `0.4.2-alpha.0.71+26.2` | `c2me-base.mixins.json` 75/73<br>`c2me.mixins.json` (extra, undeclared) 0/0 |
| 05 | c2me-fabric | 1 | `c2me-fabric-client-uncapvd-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-client-uncapvd` | `0.4.2-alpha.0.71+26.2` | `c2me-client-uncapvd.mixins.json` 5/5 |
| 06 | c2me-fabric | 1 | `c2me-fabric-fixes-chunkio-threading-issues-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-fixes-chunkio-threading-issues` | `0.4.2-alpha.0.71+26.2` | `c2me-fixes-chunkio-threading-issues.mixins.json` 1/1 |
| 07 | c2me-fabric | 1 | `c2me-fabric-fixes-general-threading-issues-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-fixes-general-threading-issues` | `0.4.2-alpha.0.71+26.2` | `c2me-fixes-general-threading-issues.mixins.json` 4/4 |
| 08 | c2me-fabric | 1 | `c2me-fabric-fixes-worldgen-threading-issues-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-fixes-worldgen-threading-issues` | `0.4.2-alpha.0.71+26.2` | `c2me-fixes-worldgen-threading-issues.mixins.json` 37/37 |
| 09 | c2me-fabric | 1 | `c2me-fabric-fixes-worldgen-vanilla-bugs-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-fixes-worldgen-vanilla-bugs` | `0.4.2-alpha.0.71+26.2` | `c2me-fixes-worldgen-vanilla-bugs.mixins.json` 1/1 |
| 10 | c2me-fabric | 1 | `c2me-fabric-notickvd-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-notickvd` | `0.4.2-alpha.0.71+26.2` | `c2me-notickvd.mixins.json` 16/15 |
| 11 | c2me-fabric | 1 | `c2me-fabric-opts-allocs-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-opts-allocs` | `0.4.2-alpha.0.71+26.2` | `c2me-opts-allocs.mixins.json` 12/12 |
| 12 | c2me-fabric | 1 | `c2me-fabric-opts-chunkio-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-opts-chunkio` | `0.4.2-alpha.0.71+26.2` | `c2me-opts-chunkio.mixins.json` 2/2 |
| 13 | c2me-fabric | 1 | `c2me-fabric-opts-dfc-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-opts-dfc` | `0.4.2-alpha.0.71+26.2` | `c2me-opts-dfc.mixins.json` 20/20 |
| 14 | c2me-fabric | 1 | `c2me-fabric-opts-math-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-opts-math` | `0.4.2-alpha.0.71+26.2` | `c2me-opts-math.mixins.json` 2/2 |
| 15 | c2me-fabric | 1 | `c2me-fabric-opts-natives-math-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-opts-natives-math` | `0.4.2-alpha.0.71+26.2` | `c2me-opts-natives-math.mixins.json` 4/4 |
| 16 | c2me-fabric | 1 | `c2me-fabric-opts-scheduling-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-opts-scheduling` | `0.4.2-alpha.0.71+26.2` | `c2me-opts-scheduling.mixins.json` 15/15 |
| 17 | c2me-fabric | 1 | `c2me-fabric-opts-worldgen-general-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-opts-worldgen-general` | `0.4.2-alpha.0.71+26.2` | `c2me-opts-worldgen-general.mixins.json` 3/3 |
| 18 | c2me-fabric | 1 | `c2me-fabric-opts-worldgen-vanilla-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-opts-worldgen-vanilla` | `0.4.2-alpha.0.71+26.2` | `c2me-opts-worldgen-vanilla.mixins.json` 4/4 |
| 19 | c2me-fabric | 1 | `c2me-fabric-rewrites-chunk-serializer-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-rewrites-chunk-serializer` | `0.4.2-alpha.0.71+26.2` | `c2me-rewrites-chunk-serializer.mixins.json` 5/5 |
| 20 | c2me-fabric | 1 | `c2me-fabric-rewrites-chunk-system-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-rewrites-chunk-system` | `0.4.2-alpha.0.71+26.2` | `c2me-rewrites-chunk-system.mixins.json` 20/20 |
| 21 | c2me-fabric | 1 | `c2me-fabric-rewrites-chunkio-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-rewrites-chunkio` | `0.4.2-alpha.0.71+26.2` | `c2me-rewrites-chunkio.mixins.json` 3/3 |
| 22 | c2me-fabric | 1 | `c2me-fabric-server-utils-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-server-utils` | `0.4.2-alpha.0.71+26.2` | `c2me-server-utils.mixins.json` 1/1 |
| 23 | c2me-fabric | 1 | `c2me-fabric-threading-lighting-mc26.2-0.4.2-alpha.0.71.jar` | `c2me-threading-lighting` | `0.4.2-alpha.0.71+26.2` | `c2me-threading-lighting.mixins.json` 3/3 |
| 24 | c2me-fabric | 1 | `caffeine-3.2.1.jar` | `com_github_ben-manes_caffeine_caffeine` | `3.2.1` | — |
| 25 | c2me-fabric | 1 | `core-3.6.5.jar` | `com_electronwill_night-config_core` | `3.6.5` | — |
| 26 | c2me-fabric | 1 | `exp4j-0.4.8.jar` | `net_objecthunter_exp4j` | `0.4.8` | — |
| 27 | c2me-fabric | 1 | `jctools-core-4.0.5.jar` | `org_jctools_jctools-core` | `4.0.5` | — |
| 28 | c2me-fabric | 1 | `mixinsquared-fabric-0.3.7-beta.1.jar` | `mixinsquared` | `0.3.7-beta.1` | `mixinsquared.fabric.init.mixins.json` 0/0 |
| 29 | c2me-fabric | 1 | `reactive-streams-1.0.4.jar` | `org_reactivestreams_reactive-streams` | `1.0.4` | — |
| 30 | c2me-fabric | 1 | `rxjava-3.1.12.jar` | `io_reactivex_rxjava3_rxjava` | `3.1.12` | — |
| 31 | c2me-fabric | 1 | `toml-3.6.5.jar` | `com_electronwill_night-config_toml` | `3.6.5` | — |
| 32 | ferrite-core | 0 | `ferritecore-9.0.0-fabric.jar` | `ferritecore` | `9.0.0` | `ferritecore.accessors.mixin.json` 7/7<br>`ferritecore.blockstatecache.mixin.json` 2/2<br>`ferritecore.datacomponents.mixin.json` 1/1<br>`ferritecore.fastmap.mixin.json` 2/2<br>`ferritecore.threaddetec.mixin.json` 1/1 |
| 33 | krypton | 0 | `krypton-0.3.1.jar` | `krypton` | `0.3.1` | `krypton.mixins.json` 10/10 |
| 34 | krypton | 1 | `velocity-native-3.4.0-SNAPSHOT.jar` | `com_velocitypowered_velocity-native` | `3.4.0-SNAPSHOT` | — |
| 35 | vmp-fabric | 0 | `vmp-fabric-mc26.2-0.2.0+beta.7.236-all.jar` | `vmp` | `0.2.0+beta.7.236+26.2` | `vmp.mixins.json` 45/45 |
| 36 | vmp-fabric | 1 | `asyncutil-0.1.0.jar` | `com_ibm_async_asyncutil` | `0.1.0` | — |
| 37 | scalablelux | 0 | `ScalableLux-fabric-0.3.0-alpha.0.3-all.jar` | `scalablelux` | `0.3.0-alpha.0.3+26.2` | `scalablelux.mixins.json` 16/13 |
| 38 | carpet | 0 | `fabric-carpet-26.2+v260616.jar` | `carpet` | `26.2+v260616` | `carpet.mixins.json` 187/172 |
| 39 | carpet-tis-addition | 0 | `carpet-tis-addition-v1.83.3-mc26.2.jar` | `carpet-tis-addition` | `1.83.3` | `carpet-tis-addition.mixins.json` 615/599 |
| 40 | carpet-tis-addition | 1 | `conditional-mixin-fabric-0.6.5.jar` | `conditional-mixin` | `0.6.5` | — |

Container-level notes:

- `c2me-fabric` is a bundle: its top-level JAR carries a `fabric.mod.json` and
  two classes, and all 233 mixin classes live in 20 nested modules. The other
  nine nested C2ME JARs are libraries (`asyncutil`, Caffeine, night-config,
  exp4j, JCTools, MixinSquared, reactive-streams, RxJava).
- `c2me-base` also contains an **undeclared** `c2me.mixins.json` (not listed by
  its `fabric.mod.json`, `mixin classes: 0`). It contributes no classes but is
  visible in the container inventory.
- `mixinsquared.fabric.init.mixins.json` declares 0 classes; it is an
  infrastructure config for C2ME's `@MixinSquared` handlers.
- `c2me-client-uncapvd` is the only nested container with
  `environment: "client"`; its 5 classes are excluded from the server-applicable
  aggregate (see §3).
- No container had `undeclared_nested_jars` or `declared_nested_jars_missing`
  entries.

## 3. Verified totals

Recomputed directly from the scan JSON (not taken as given):

| Metric | Value |
| --- | --- |
| Top-level artifacts | 8 |
| Total JAR containers | 40 (8 top-level + 32 nested) |
| Mixin configs | 34 (32 with at least one class; 2 empty) |
| Mixin classes (total) | **1,411** |
| Server-applicable mixin classes | **1,354** |
| Client-only mixin classes | 57 |
| Missing mixin classes | **0** |
| Static scan errors | **0** |
| Injection handler records | 1,862 |
| Distinct vanilla target classes | 606 |

Per-mod aggregate, from the scan's per-top-level aggregate fields:

| Mod | Mixin classes | Server-applicable | Client-only | Targets | Handlers | Missing | Errors |
| --- | --- | --- | --- | --- | --- | --- | --- |
| lithium | 292 | 277 | 15 | 172 | 384 | 0 | 0 |
| c2me-fabric | 233 | 225 | 8 | 152 | 340 | 0 | 0 |
| ferrite-core | 13 | 13 | 0 | 12 | 15 | 0 | 0 |
| krypton | 10 | 10 | 0 | 8 | 6 | 0 | 0 |
| vmp-fabric | 45 | 45 | 0 | 32 | 76 | 0 | 0 |
| scalablelux | 16 | 13 | 3 | 16 | 19 | 0 | 0 |
| carpet | 187 | 172 | 15 | 140 | 294 | 0 | 0 |
| carpet-tis-addition | 615 | 599 | 16 | 263 | 728 | 0 | 0 |
| **Sum** | **1,411** | **1,354** | **57** | 606 distinct | **1,862** | **0** | **0** |

Verification notes:

- 1,411 is the sum of the eight `aggregate_mixin_count` values and also the sum
  of every config's `class_count` (34 configs). Both agree.
- The naive sum of config-level `server_class_count` is **1,359**, not 1,354.
  The 5-class difference is exactly `c2me-client-uncapvd`'s config (5/5): that
  nested module is `environment: "client"`, so the top-level aggregate correctly
  drops its 5 classes from the server count. **1,354 is the correct
  server-applicable figure** and is the one used throughout this report.
- "0 missing mixin classes" means every class named by every config was present
  in the container (`missing_classes: []` everywhere). It does **not** mean the
  vanilla target methods exist; that is checked separately by the scanner's
  `has_target` field and is not a runtime application test.
- "0 static scan errors" means the scanner recorded no errors
  (`aggregate_errors: 0`, every `errors: []`). It says nothing about runtime
  errors.

## 4. Fabric-Folia side and target overlap

### 4.1 Fabric-Folia's declared mixins

`fabric/src/main/resources/fabricfolia.mixins.json` is the only mixin config
(`fabric.mod.json` declares exactly that one). It is `required: true`,
`defaultRequire: 1`, package `com.palordersoftworks.fabricfolia.mixin`, and
lists **29** mixin classes. The source tree contains the same 29 classes and
no `@Mixin` class outside that package (checked in `fabric`, `common`, `api`).

There is **no separate integration mixin config and no mixin that targets a mod
class**. All 29 target vanilla 26.2 classes. C2ME and Lithium handling is
runtime code (`CompatScanner`, engine fail-safes), not mixins, so it cannot
collide at the bytecode level.

| # | Fabric-Folia mixin | Vanilla target | Injection point(s) |
| --- | --- | --- | --- |
| 1 | `ChunkMapTrackingMixin` | `ChunkMap` | `move(ServerPlayer)`, `addEntity(Entity)`, `removeEntity(Entity)` — `@Inject` HEAD, cancellable |
| 2 | `CommandsMixin` | `Commands` | `performCommand(...)` — 2× `@Inject` HEAD (one cancellable), 1× RETURN |
| 3 | `ConnectionFlushQueueMixin` | `Connection` | `flushQueue` — `@Redirect` `Consumer.accept` |
| 4 | `ConnectionPacketDispatchMixin` | `Connection` | `runOnceConnected(Consumer)` — `@Inject` HEAD and RETURN |
| 5 | `DistanceManagerTrackingMixin` | `DistanceManager` | `addPlayer`, `removePlayer`, `runAllUpdates` — `@Inject` HEAD, cancellable |
| 6 | `EntityMixin` | `Entity` | `setPosRaw` TAIL; `setRemoved` `@Inject`; `collectCollidersIgnoringWorldBorder` `@WrapOperation` `Level.getBlockCollisions`; `collideWithShapes` `@ModifyExpressionValue` `List.isEmpty#0` + `@ModifyArgs` `Shapes.collide`; `collideBoundingBox` `@ModifyVariable` HEAD (argsOnly) + `@Inject` RETURN |
| 7 | `EntityGetterMixin` | `EntityGetter` | `getEntityCollisions` — `@WrapOperation` `EntityGetter.getEntities` |
| 8 | `EntitySectionMixin` | `EntitySection` | `<init>` TAIL, `add` TAIL, `remove` TAIL; `getEntities` both overloads — `@ModifyExpressionValue` FIELD `storage` |
| 9 | `EntityTeleportMixin` | `Entity` | `teleport(TeleportTransition)` — `@Inject` HEAD, cancellable |
| 10 | `LevelBlockEntityTickMixin` | `Level` | `tickBlockEntities` — `@Redirect` `TickingBlockEntity.tick()` |
| 11 | `LevelEntityTickMixin` | `Level` | `guardEntityTick` — `@Redirect` `Consumer.accept` |
| 12 | `LevelChunkLightEngineMixin` | `LevelChunk` | `setBlockState` — `@Redirect` `LevelLightEngine.updateSectionStatus` and `checkBlock` |
| 13 | `LevelMixin` | `Level` | `<init>` TAIL |
| 14 | `LevelScheduleTickMixin` | `LevelTicks` | `schedule` — `@Inject` HEAD, cancellable |
| 15 | `LevelTicksQueryMixin` | `LevelTicks` | `hasScheduledTick` — `@Inject` HEAD, cancellable |
| 16 | `LevelTicksTickMixin` | `LevelTicks` | `runCollectedTicks` — `@Redirect` `BiConsumer.accept` |
| 17 | `MinecraftServerGuiMixin` | `MinecraftServerGui` | `showFrameFor` — `@Redirect` `JFrame.setVisible` + 2× `@ModifyConstant` |
| 18 | `MinecraftServerMixin` | `MinecraftServer` | `getServerModName` HEAD; `processPacketsAndTick` HEAD; `tickChildren` HEAD |
| 19 | `PacketProcessorMixin` | `PacketProcessor` | `scheduleIfPossible` — `@Inject` HEAD, cancellable |
| 20 | `PacketUtilsMixin` | `PacketUtils` | `ensureRunningOnSameThread` — `@Redirect` `isSameThread` |
| 21 | `PersistentEntitySectionManagerMixin` | `PersistentEntitySectionManager` | `addEntity` — `@Inject` RETURN |
| 22 | `ServerChunkCacheMixin` | `ServerChunkCache` | `getChunk` HEAD (cancellable), `broadcastChangedChunks` HEAD, `blockChanged` HEAD (cancellable), `tick` HEAD, `addTicketAndLoadWithRadius` HEAD (cancellable) |
| 23 | `ServerChunkCacheTickMixin` | `ServerChunkCache` | `tickChunks` — `@Redirect` `ChunkMap.forEachBlockTickingChunk` |
| 24 | `ServerConnectionTickMixin` | `ServerConnectionListener` | `tick()` — `@Redirect` `Connection.tick()` |
| 25 | `ServerLevelTickMixin` | `ServerLevel` | `tick` HEAD and RETURN |
| 26 | `ServerLevelUnloadMixin` | `ServerLevel` | `unload(LevelChunk)` TAIL |
| 27 | `ServerPlayerTeleportMixin` | `ServerPlayer` | `teleport(TeleportTransition)` HEAD, cancellable |
| 28 | `ServerPlayerTickMixin` | `ServerPlayer` | `tick()` — 2× `@Redirect` |
| 29 | `TicketStorageMixin` | `TicketStorage` | 10 public mutators — `@Inject` HEAD, cancellable |

Those 29 classes collapse to **20 distinct vanilla target classes**. Note that
many mixins are always applied and gate their behavior at runtime (config
flags), so a bytecode-level overlap exists even when the corresponding
Fabric-Folia feature is disabled.

### 4.2 Risk categories used below

- **N** — no scanned mod targets this vanilla class.
- **A** — same target class only; no shared target method.
- **B** — at least one shared target method, but different `@At` injection point.
- **C** — at least one identical target method **and** identical `@At` selector
  (same invocation/field/constant). Two handlers at one point; composition or
  ordering is not settled by a static scan.
- **D** — proven conflict. **None exist in this evidence set.**

A/B are same-target overlap. C is a same-injection-point candidate. Neither C
nor anything else here is a proven conflict: no Mixin export, boot, gameplay or
soak test has run (§7).

### 4.3 Class- and method-level overlap

18 of Fabric-Folia's 20 target classes are also targeted by at least one
scanned mod. `MinecraftServerGui` and `PacketProcessor` are untouched.

| Vanilla class | Folia mixin (methods) | Scanned mods sharing the class (shared methods) | Risk |
| --- | --- | --- | --- |
| `ChunkMap` | `ChunkMapTrackingMixin` (move, addEntity, removeEntity) | c2me-fabric (addEntity, removeEntity); vmp-fabric (addEntity, move, removeEntity); carpet-tis-addition (addEntity); carpet, scalablelux (class only) | **C** |
| `Commands` | `CommandsMixin` (performCommand) | carpet (performCommand) | **C** |
| `Connection` | `ConnectionFlushQueueMixin`, `ConnectionPacketDispatchMixin` (flushQueue, runOnceConnected) | krypton, vmp-fabric, carpet, carpet-tis-addition (no shared method) | A |
| `DistanceManager` | `DistanceManagerTrackingMixin` (addPlayer, removePlayer, runAllUpdates) | c2me-fabric (addPlayer, removePlayer, runAllUpdates); vmp-fabric (runAllUpdates); carpet (class only) | **C** |
| `Entity` | `EntityMixin`, `EntityTeleportMixin` (setPosRaw, setRemoved, collision seams, teleport) | carpet-tis-addition (collectCollidersIgnoringWorldBorder, collideBoundingBox, collideWithShapes); lithium, carpet (setPosRaw); vmp-fabric (class only) | **C** |
| `EntityGetter` | `EntityGetterMixin` (getEntityCollisions) | lithium, carpet-tis-addition (getEntityCollisions) | **C** |
| `EntitySection` | `EntitySectionMixin` (add, remove, getEntities) | carpet-tis-addition (add, remove, getEntities, `<init>`); lithium (add, remove, getEntities) | **C** |
| `Level` | `LevelMixin`, `LevelBlockEntityTickMixin`, `LevelEntityTickMixin` (`<init>`, tickBlockEntities, guardEntityTick) | carpet-tis-addition, carpet (tickBlockEntities, guardEntityTick); lithium (`<init>`, tickBlockEntities); c2me-fabric (`<init>`, guardEntityTick) | B |
| `LevelChunk` | `LevelChunkLightEngineMixin` (setBlockState) | lithium, scalablelux, carpet (setBlockState) | B |
| `LevelTicks` | `LevelScheduleTickMixin`, `LevelTicksQueryMixin`, `LevelTicksTickMixin` (schedule, hasScheduledTick, runCollectedTicks) | carpet-tis-addition (schedule, runCollectedTicks) | **C** |
| `MinecraftServer` | `MinecraftServerMixin` (getServerModName, processPacketsAndTick, tickChildren) | carpet-tis-addition (processPacketsAndTick, tickChildren); carpet (class only) | B |
| `MinecraftServerGui` | `MinecraftServerGuiMixin` (showFrameFor) | — | N |
| `PacketProcessor` | `PacketProcessorMixin` (scheduleIfPossible) | — | N |
| `PacketUtils` | `PacketUtilsMixin` (ensureRunningOnSameThread) | carpet-tis-addition (ensureRunningOnSameThread) | B |
| `PersistentEntitySectionManager` | `PersistentEntitySectionManagerMixin` (addEntity) | carpet (addEntity); lithium, carpet-tis-addition (class only) | B |
| `ServerChunkCache` | `ServerChunkCacheMixin`, `ServerChunkCacheTickMixin` (getChunk, tick, blockChanged, broadcastChangedChunks, addTicketAndLoadWithRadius, tickChunks) | c2me-fabric (getChunk, tick, tickChunks, broadcastChangedChunks); carpet-tis-addition (tick, tickChunks); lithium (tick, tickChunks); carpet (tickChunks); vmp-fabric (class only) | **C** |
| `ServerConnectionListener` | `ServerConnectionTickMixin` (tick) | carpet-tis-addition (tick) | B |
| `ServerLevel` | `ServerLevelTickMixin`, `ServerLevelUnloadMixin` (tick, unload) | carpet (tick, unload); carpet-tis-addition (tick); lithium, vmp-fabric, scalablelux (class only) | B |
| `ServerPlayer` | `ServerPlayerTeleportMixin`, `ServerPlayerTickMixin` (teleport, tick) | carpet (teleport, tick); carpet-tis-addition (tick); vmp-fabric (class only) | **C** |
| `TicketStorage` | `TicketStorageMixin` (10 mutators) | lithium (addTicket, removeTicket, removeTicketIf); carpet-tis-addition (addTicket, removeTicket); carpet (class only) | **C** |

Per-mod overlap summary (classes among the 20):

| Mod | Overlapping Folia classes | Highest observed risk |
| --- | --- | --- |
| carpet-tis-addition | 15 | C (Entity collision, EntitySection, LevelTicks, TicketStorage, ChunkMap) |
| carpet | 13 | C (Commands, Entity setPosRaw, ServerPlayer teleport) |
| lithium | 9 | C (EntityGetter, EntitySection, TicketStorage) |
| c2me-fabric | 9 | C (ChunkMap, DistanceManager, ServerChunkCache) |
| vmp-fabric | 7 | B on shared methods (ChunkMap/DistanceManager redirects sit inside bodies Folia may cancel) |
| scalablelux | 4 | B (setBlockState light path) |
| krypton | 1 (`Connection`) | A |
| ferrite-core | 0 | none |

### 4.4 Same-injection-point candidates (risk C)

These pairs have the same target method and the same `@At` value/target, as
recorded in the scan and the Fabric-Folia source. They are the first things a
boot/export test should inspect. None is proven to fail.

| Fabric-Folia point | Colliding handler(s) | Why it matters |
| --- | --- | --- |
| `Commands.performCommand` HEAD | carpet `@Inject` HEAD (plus RETURN, `@Redirect isDebugEnabled`) | Folia's cancellable HEAD dispatch can skip the body, including Carpet's RETURN hook, while both HEAD handlers still run |
| `ChunkMap.addEntity(Entity)` HEAD, `removeEntity(Entity)` HEAD | c2me `@Inject` HEAD (`c2me-fixes-general-threading-issues`) | Folia cancels off-server-thread calls and replays them; C2ME's HEAD bookkeeping can still run on the worker, and ordering between the two HEAD handlers is priority-driven |
| `DistanceManager.runAllUpdates(ChunkMap)` HEAD | c2me-notickvd `@Inject` HEAD (plus RETURN); c2me-rewrites-chunk-system `@Inject` at `runDistanceUpdates` | Same HEAD point; a Folia cancel returns `false` and skips C2ME's inner handler |
| `ServerChunkCache.getChunk(...)` HEAD | c2me-rewrites-chunk-system `@Inject` HEAD, plus `@WrapMethod` | Folia's cancellable HEAD short-circuit (`null` for `create=false`) bypasses C2ME's wrapper and tracker bookkeeping |
| `ServerChunkCache.tick(...)` HEAD | c2me `@Inject` HEAD (`c2me-fixes-general-threading-issues`) | Two HEAD handlers; Folia's drain order relative to C2ME's thread assertions is unverified |
| `Entity.setPosRaw(DDD)` TAIL | carpet `@Inject` TAIL | Both observe the final position; ordering decides which bookkeeping is last |
| `Entity.collectCollidersIgnoringWorldBorder` `@WrapOperation Level.getBlockCollisions` | carpet-tis-addition `@WrapOperation` on the same invocation | Two OFEM-style rewrites of the same block-collision source; Fabric-Folia's version is disabled only when **Lithium** is detected, not when TIS is present |
| `Entity.collideWithShapes` `@ModifyArgs Shapes.collide` | carpet-tis-addition `@ModifyArgs` on the same invocation | Both append/modify shapes on the same call; composition of the two rewrites is undefined by a static scan |
| `Entity.collideBoundingBox` HEAD `@ModifyVariable` | carpet-tis-addition `@ModifyVariable` HEAD | Same method entry, same argument list |
| `EntityGetter.getEntityCollisions` `@WrapOperation EntityGetter.getEntities` | lithium `@Redirect` on the same invocation; carpet-tis-addition `@ModifyArg` on the same invocation | Three handlers on one call site, including two call-transform kinds; this is the most structurally fragile overlap in the set |
| `EntitySection.getEntities` FIELD `storage` `@ModifyExpressionValue` | carpet-tis-addition `@ModifyExpressionValue` on the same field read | Both replace the storage map read; last writer wins for that expression |
| `EntitySection.<init>`, `add`, `remove` TAIL | carpet-tis-addition `@Inject` TAIL on all three | Duplicate index maintenance at the same tail points |
| `LevelTicks.runCollectedTicks` `@Redirect BiConsumer.accept` | carpet-tis-addition `@Inject` at the same invocation (2 handlers) and `@ModifyVariable` at the same invocation (2 handlers) | Fabric-Folia replaces the call while TIS observes and rewrites its arguments |
| `ServerPlayer.teleport(TeleportTransition)` HEAD | carpet `@Inject` HEAD (plus RETURN) | Folia cancels for cross-region callers; Carpet's RETURN hook then never runs, and the two HEAD handlers order by priority |
| `TicketStorage.addTicket(J,Ticket)` / `removeTicket(J,Ticket)` HEAD | carpet-tis-addition `@ModifyVariable` HEAD | Folia's cancellable HEAD deferral and TIS's local rewrite share method entry |

B-level findings worth naming:

- **VMP** redirects inside `ChunkMap.addEntity/removeEntity/move` and
  `DistanceManager.runAllUpdates`; Folia cancels at HEAD for non-server callers,
  so those inner redirects are skipped on exactly the deferred path.
- **C2ME** `tickChunks` wraps `ServerLevel.getAllEntities`; Folia redirects
  `ChunkMap.forEachBlockTickingChunk` — different invocation, same method.
- **C2ME** `broadcastChangedChunks` redirects `ChunkHolder.getTickingChunk`;
  Folia injects at HEAD to drain the broadcast set. Folia's drain is intended
  to land before vanilla's iteration, and C2ME's redirect does not move that
  boundary, but content of the drained set is then handed to C2ME's path.
- **ScalableLux** redirects `ChunkSkyLightSources.update` inside
  `LevelChunk.setBlockState`; Folia redirects `LevelLightEngine` calls in the
  same method. Different call sites, same light-engine path and same
  block-change traffic.
- **Lithium** `EntitySection.getEntities` redirects `ClassInstanceMultiMap.iterator`;
  Folia replaces the `storage` field read. Both can be active at once.
- **Carpet TIS** also touches `PacketUtils.ensureRunningOnSameThread` at HEAD
  while Folia redirects the `isSameThread()` call inside it; a TIS HEAD cancel
  would bypass Folia's redirect.

### 4.5 What is *not* a proven conflict

- No pair above has been shown to fail mixin application. "Same injection
  point" is a static observation about method plus selector, nothing more.
- No behavioral conflict has been demonstrated. A `@Redirect` and an `@Inject`
  at the same invoke can coexist; two `@ModifyArgs` or two `@WrapOperation`
  handlers may compose in priority order. Whether the composition is correct
  for either mod is a runtime question.
- The C2ME cases are moot for a support claim regardless of overlap, because the
  scanned C2ME build is below Fabrolia's declared minimum (§5).
- Fabric-Folia's own docs already classify every mod here as having no current
  release matrix. `docs/compatibility/` has notes for C2ME, FerriteCore,
  Krypton, Lithium, ScalableLux and VMP; **Carpet and Carpet TIS Addition have
  no note at all**, and TIS is the mod with the most same-point candidates.

## 5. C2ME version gate

| Item | Value |
| --- | --- |
| Scanned C2ME artifact | `c2me-fabric-mc26.2-0.4.2-alpha.0.71.jar` |
| Modrinth version / ID | `0.4.2-alpha.0.71+26.2` / `qwS7dYKj` |
| Fabrolia prod/current declared minimum | `0.4.2-alpha.0.88` (README declares incompatibility with C2ME versions **older than** this) |
| Relation | **0.71 < 0.88 — below the declared minimum** |

Consequences:

- **C2ME 0.4.2-alpha.0.71+26.2 must not be described as supported**, by
  Fabric-Folia or by comparison with Fabrolia. It is below the only minimum
  stated in the reference.
- The manifest's `all_26_2_versions` list tops out at
  `0.4.2-alpha.0.71+26.2`; it contains **no 26.2 build at or above
  `0.4.2-alpha.0.88`**. So within the observed 26.2 artifact set there is no
  C2ME build that satisfies the Fabrolia minimum. A future C2ME 26.2 build above
  the gate would need its own artifact record and scan.
- Fabric-Folia's runtime behavior is a fail-safe, not support: when C2ME is
  detected and `general.regionized-random-ticks` is on, the random-tick slice is
  suppressed and vanilla random ticks run (COMPATIBILITY.md). That does not
  cover C2ME's chunk-system, ticket, tracking or scheduling hooks.
- The 9-class target overlap in §4.3 is recorded for completeness. It does not
  upgrade the version verdict.

## 6. Minecraft 26.3 baselines (kept separate)

The matrix in §§1–5 is a **26.2** matrix. It does not describe any 26.3 build.
Two 26.3 toolchain baselines are recorded, and neither has been inventoried:

| Baseline | Minecraft | Fabric Loader | Fabric API | Loom | Exact mod artifacts / scan |
| --- | --- | --- | --- | --- | --- |
| Fabrolia prod/current | 26.3 (`0.2.38+alpha-26.3`) | 0.19.5 | 0.161.0+26.3 | not recorded in the reference | **missing** — no Modrinth version IDs, files, URLs or hashes; no scan |
| Official Fabric (current developer page) | 26.3 | 0.20.1 | 0.166.0+26.3 | 1.19-SNAPSHOT | **missing** — toolchain values only; no mod set, IDs, hashes or scan |

Notes:

- Fabrolia prod/current is itself a 26.3 line; it also lists Lithium
  `mc26.3-0.26.2` and VMP `0.2.0+beta.7.238+26.3`, but those are its own
  reference versions, not artifacts in this scan. No IDs or hashes for them are
  present in the inputs, so none are stated here.
- The official page is a toolchain recommendation, not a mod set. The 26.2
  optimization-mod artifacts cannot be assumed to run on 26.3.
- Both 26.3 baselines still need: exact artifact list, Modrinth version IDs,
  SHA-512 values, a 26.3 container/mixin scan, and the same overlap analysis
  against a 26.3 Fabric-Folia mixin set. Until then, no 26.3 compatibility
  statement can be made.
- The repository still documents Minecraft 26.2 / Loader 0.19.3; the 26.3 move
  is a toolchain and vanilla-API port, not a version-string change.

## 7. Evidence limits

This report is **metadata plus static mixin inventory only**. Specifically:

- Artifact identity rests on Modrinth metadata and SHA-512 recomputation of the
  downloaded files. All eight top-level hashes matched the manifest.
- Mixin class counts and handler selectors come from static inspection of the
  JARs' configs, class files and annotations. They are declarations, not proof
  of successful application.
- `missing_classes: 0` means the declared mixin class files exist in their
  containers. It does not verify that target methods, descriptors, fields or
  ordinals resolve against the 26.2 runtime.
- **No Mixin export has been produced.** No `-Dmixin.debug.export=true` run, no
  merged-class inspection, no application log.
- **No server boot has run** with any of these mods, alone or combined. There is
  no `Done (` result, no mixin-application audit, no startup-error count.
- **No gameplay test has run** — no players, entities, chunk churn, teleport,
  redstone, hopper or save/restart exercise.
- **No soak test and no performance measurement have run.** No TPS, MSPT, memory
  or thread-contention data exists for any combination.
- Client-side behavior was not tested; the 57 client-only classes are counted
  only.
- The Fabric-Folia side is the repository's declared 29 mixins and source
  selectors. `MIXINS.md` itself states that the inventory is source intent and
  does not prove live application.
- Nothing here establishes a supported modpack, a supported version pair, or a
  compatibility certification.

## 8. Open items for manager review

1. Decide whether Carpet and Carpet TIS Addition get declared compatibility
   notes; TIS currently has the largest same-point overlap and no note.
2. Decide whether the OFEM collision patch should also be force-disabled when
   Carpet TIS Addition is detected, as it already is for Lithium.
3. Prioritize a Mixin export + dedicated-server boot with the default profile,
   then one gate at a time, starting with the C-level rows in §4.4.
4. Treat C2ME separately: either exclude 26.2 C2ME from any support claim or
   wait for a 26.2 build at or above `0.4.2-alpha.0.88` and scan it.
5. Commission the 26.3 artifact inventory and scan before any 26.3 statement.
