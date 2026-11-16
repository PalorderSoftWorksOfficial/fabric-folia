# Fabric-Folia API

The API lets a Fabric mod become region-aware: instead of assuming one gameplay
thread, the mod submits work to the scheduler that owns the world state it wants
to touch. It is a plain Java library — no Minecraft, Fabric Loader or Mixin types
appear in its signatures — so it compiles in any JVM project. The implementations
are supplied at runtime by the Fabric-Folia server mod.

This guide targets API **0.1.0** for **Minecraft 26.2**.

## Coordinates and hosting status

| Field | Value |
| --- | --- |
| Group | `com.palordersoftworks.fabricfolia` |
| Artifact | `fabric-folia-api` |
| Version | `0.1.0` (matches the Fabric-Folia mod version) |
| Packaging | `jar`, with `-sources.jar` and `-javadoc.jar` |
| POM dependencies | none |

The artifact is produced by `api/` and is **published to Maven Local only**. The
build configures no repository, so `fabric-folia-api` is **not hosted on Maven
Central, GitHub Packages or any other public repository**. Treat the coordinates
above as valid for local installs until a repository is configured and announced.

The server mod jar is `fabric-folia-0.1.0.jar` (Loom remaps it; it is *not* named
`fabric-0.1.0.jar`). The API jar is nested inside it under `META-INF/jars/` and
declares the Fabric mod id `fabric-folia-api`.

## Compile-time setup

Publish the API from a checkout of this repository:

```bash
./gradlew :api:publishToMavenLocal
```

Then resolve it from Maven Local. Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    compileOnly("com.palordersoftworks.fabricfolia:fabric-folia-api:0.1.0")
}
```

Maven:

```xml
<dependency>
  <groupId>com.palordersoftworks.fabricfolia</groupId>
  <artifactId>fabric-folia-api</artifactId>
  <version>0.1.0</version>
  <scope>provided</scope>
</dependency>
```

`compileOnly` / `provided` is deliberate: the classes are already on the server's
classpath because they ship inside the Fabric-Folia mod jar.

**Do not shade, `include` or `jar-in-jar` this artifact into your mod.** A second
copy of the API classes in your jar would give `FabricFoliaApi` a different class
identity than the instance Fabric-Folia delivers, and installing the standalone
API jar alongside Fabric-Folia duplicates the `fabric-folia-api` mod id. Either
way the server fails to start or the entrypoint cast fails. Declare a dependency
instead.

## Registering the entrypoint

Fabric-Folia delivers the API through the entrypoint key **`fabricfolia`**. Add it
to your `fabric.mod.json` (spelling and case matter):

```json
{
  "schemaVersion": 1,
  "id": "example-mod",
  "version": "1.0.0",
  "environment": "*",
  "entrypoints": {
    "fabricfolia": [
      "example.mod.FabricFoliaIntegration"
    ]
  },
  "depends": {
    "fabricfolia": ">=0.1.0"
  }
}
```

`fabricfolia` is the server mod that must be installed for the entrypoint to be
called. The nested API mod id `fabric-folia-api` exists for presence checks
(`FabricLoader.getInstance().isModLoaded("fabric-folia-api")`); depending on
`fabricfolia` already implies it is present.

The entrypoint class implements `FabricFoliaApi.Initializer`:

```java
package example.mod;

import com.palordersoftworks.fabricfolia.api.FabricFoliaApi;
import com.palordersoftworks.fabricfolia.api.RegionScheduler;

public final class FabricFoliaIntegration implements FabricFoliaApi.Initializer {
    private FabricFoliaApi api;

    @Override
    public void onInitialized(FabricFoliaApi api) {
        this.api = api;

        RegionScheduler.Position spawnChunk = RegionScheduler.Position.of("minecraft:overworld", 0, 0);
        api.regionScheduler().run(spawnChunk, () -> {
            // Runs on the region that owns chunk (0, 0) of the overworld.
            // Read ThreadContext here to confirm, or use it for diagnostics.
        });
    }
}
```

The initializer is called **once per server start**, on the server thread, after
worlds attach and before the first tick. If it throws, Fabric-Folia logs the
failure naming your mod id and continues booting; other mods still receive the
API.

### Availability and disabled mode

- **Fabric-Folia not installed:** the entrypoint is never called. Your mod must
  tolerate `onInitialized` never running.
- **Fabric-Folia installed but regionized execution disabled**
  (`general.enabled: false`) or its config is invalid: the server runs vanilla
  single-threaded execution and the `fabricfolia` entrypoint is **never called**.
  There is no "unavailable" callback in 0.1.0.
- **After server shutdown:** `isEnabled()` returns `false`, and
  `regionScheduler()`, `entityScheduler()`, `globalScheduler()` and
  `asyncScheduler()` throw `IllegalStateException`. `threadContext()` remains
  readable.
- `isEnabled()` is a liveness check for the delivered API instance, not a
  capability report for individual regionized features.

## Scheduler and thread matrix

All scheduler methods are callable from any thread. "Region ticks" means the tick
counter of the region that owns the target position/entity, not a server-wide
clock.

| API | Executes in | Time base | Blocking allowed | Use for |
| --- | --- | --- | --- | --- |
| `RegionScheduler.run` / `runDelayed` | The owner selected when scheduled; pending tasks carry the target chunk so a split can move them to its owning child | Region ticks | No | Blocks, block entities, chunks and other state owned by that region |
| `RegionScheduler.runAtFixedRate` | The current owner selected for each new firing; pending tasks carry the target chunk through splits | Region ticks | No | Repeated work against a position |
| `RegionScheduler.runOrSchedule` | Inline if the caller already owns the position, otherwise as `run` | Region ticks | No | Mod code written against a single-thread assumption |
| `EntityScheduler` | The region owning the entity when the task comes due | Region ticks | No | Work that must follow an entity between regions |
| `GlobalScheduler` | The Minecraft server thread, in GLOBAL context, after region workers quiesce and before vanilla world mutation | Server ticks | No | Server-wide state and cross-world bookkeeping |
| `AsyncScheduler` | A bounded daemon pool with `ThreadContext.Kind.ASYNC` and no region/global ownership | Wall-clock milliseconds | Yes, until the pool saturates | IO, hashing, disk/database work, preparing data to hand back |

`ThreadContext` reports where the current code is running: `kind()`,
`currentRegion()`, `threadName()`, `mayAccessRegion(region)` and
`assertRegionAccess(operation, region)`. Use it to verify assumptions instead of
comparing thread names; two ticks of the same region may run on different
workers. The validation mode is server-configured (`threads.thread-check-mode`,
default `WARN`; `STRICT` throws `ThreadContextViolationException`, `OFF` skips
checks). `Kind.IO` is reserved and no production path currently enters it.

## Positions and ownership

`RegionScheduler.Position.of(world, chunkX, chunkZ)` takes **chunk** coordinates
and a dimension identifier string such as `minecraft:overworld`, not a `Level`
object and not block coordinates. Divide block coordinates by 16 (floor
division) before constructing a position.

Ownership moves as regions merge and split, so a position — not a `RegionInfo` —
is the routing handle. `ownerOf(position)` is a diagnostic snapshot; do not cache
its `RegionInfo` and route with it later. Queued position tasks carry their target
chunk so a split can redistribute pending work to the child that owns it. Delayed
and immediate tasks are initially queued to the owner selected when they are
scheduled; fixed-rate tasks resolve the owner again after each firing. A split-aware
queue transfer protects pending tasks when boundaries change before execution.

Current implementation notes for 0.1.0:

- `ownerOf` and `send` throw `IllegalStateException` for a world that is not
  attached to Fabric-Folia. For an attached world with no owner, `ownerOf` returns
  an empty `Optional` and `send` returns `false`.
- `MissingRegionPolicy` is not selectable through the API: unowned positions run
  on the global context (`RUN_ON_GLOBAL`). Check `ownerOf` first if your work
  must not run globally.

## Entity handles

The API module carries no Minecraft types, so entity methods take an `Object`
handle. At runtime the handle must be the live
`net.minecraft.world.entity.Entity` instance:

```java
import net.minecraft.world.entity.Entity;
import com.palordersoftworks.fabricfolia.api.FabricFoliaApi;

public void boostEntity(FabricFoliaApi api, Entity entity) {
    api.entityScheduler().run(entity,
        () -> entity.setDeltaMovement(0.0, 0.5, 0.0),
        null);
}
```

- Passing anything that is not an `Entity` throws `IllegalArgumentException`
  whose message names the required type.
- The entity should still be valid when you call the scheduler; the implementation
  reads its world at that point.
- `retired` is the fallback when the entity no longer exists. If the entity cannot
  be resolved at schedule time, `retired` runs synchronously **on the calling
  thread**; if the entity disappears later, it runs on the region worker that was
  about to execute the task. Pass `null` to drop the task silently instead.
- Repeating entity tasks follow the entity across region migrations and stop when
  it retires.

## Async to region handoffs

Async work must not touch world state. Prepare data off-thread, then submit the
mutation to the owning region:

```java
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import com.palordersoftworks.fabricfolia.api.FabricFoliaApi;
import com.palordersoftworks.fabricfolia.api.RegionScheduler;

public void loadData(FabricFoliaApi api, Path file, Consumer<byte[]> applyToWorld) {
    RegionScheduler.Position target = RegionScheduler.Position.of("minecraft:overworld", 12, -4);
    api.asyncScheduler().run(() -> {
        try {
            byte[] data = Files.readAllBytes(file);
            api.regionScheduler().run(target, () -> applyToWorld.accept(data));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    });
}
```

For server-wide state, hand off to the global scheduler instead:

```java
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import com.palordersoftworks.fabricfolia.api.FabricFoliaApi;

public void loadGlobalConfig(FabricFoliaApi api, Path file, Consumer<String> applyGlobally) {
    api.asyncScheduler().run(() -> {
        try {
            String config = Files.readString(file);
            api.globalScheduler().run(() -> applyGlobally.accept(config));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    });
}
```

Async submissions are rejected with `RejectedExecutionException` when every worker
and queue slot is busy; a faulted async task is reported to server diagnostics and
does not stop the pool. Region and global tasks must be short and non-blocking —
they run inside tick work.

## Supported environment and maturity

- Minecraft 26.2 (server side; Fabric-Folia declares `environment: server`)
- Fabric Loader 0.19.5 or newer
- Fabric API 0.160.0+26.2 or newer
- Java 25 or newer
- API artifact 0.1.0, unit-tested and published to Maven Local only

Fabric-Folia implements **experimental staged-region execution**: vanilla selects
tick work on the server thread, and supported bodies are dispatched to
region-owned worker contexts behind server-thread quiescence barriers. It is not
a full independent Folia region tick loop, and the project does not claim
production readiness. Do not assume arbitrary Minecraft subsystems or third-party
mods are parallel-safe; see [COMPATIBILITY.md](../../COMPATIBILITY.md) and
[docs/folia-parity.md](../folia-parity.md).

## Further reading

- [Threading contract](../../THREADING.md) — ownership, quiescence and the
  single-writer rules the schedulers enforce.
- [Architecture](../../ARCHITECTURE.md) — regions, staging and scheduler wiring.
- [Configuration](../configuration.md) — `threads.thread-check-mode`,
  `general.enabled` and the rest of `fabric-folia.yml`.
- [Troubleshooting](../../TROUBLESHOOTING.md) — disabled-engine and
  configuration failures.
- [Project README](../../README.md) — build, test and server-run instructions.
- Source, issues and releases:
  <https://github.com/palordersoftworks/fabric-folia>
