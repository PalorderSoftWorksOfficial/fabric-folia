# Fabric-Folia API

The standalone API module is a Java compile-time library for Fabric mods. Its public method signatures do not expose Minecraft or Fabric Loader classes. Runtime implementations are supplied by the Fabric-Folia server mod.

## Version and installation

The API version follows `mod_version` in the repository. For a local checkout, publish it into Maven Local:

```bash
./gradlew :api:publishToMavenLocal
```

Then add Maven Local and the dependency to your mod's Gradle build:

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    compileOnly("com.palordersoftworks.fabricfolia:fabric-folia-api:0.1.0")
}
```

Replace `0.1.0` with the version installed on the server. The build currently publishes to Maven Local only; no public Maven repository is configured.

The server's Fabric-Folia mod provides the API classes at runtime. Declare it as a mod dependency in the consuming mod's `fabric.mod.json`:

```json
{
  "depends": {
    "fabricfolia": "0.1.0"
  }
}
```

The API jar is nested in the Fabric-Folia mod jar. Do not shade another copy of its classes into the consuming mod.

## Entrypoint

Declare an entrypoint with key `fabricfolia`:

```json
{
  "entrypoints": {
    "fabricfolia": [
      "example.mod.FabricFoliaIntegration"
    ]
  }
}
```

Implement `FabricFoliaApi.Initializer`:

```java
package example.mod;

import com.palordersoftworks.fabricfolia.api.FabricFoliaApi;
import com.palordersoftworks.fabricfolia.api.RegionScheduler;

public final class FabricFoliaIntegration implements FabricFoliaApi.Initializer {
    @Override
    public void onInitialized(FabricFoliaApi api) {
        if (!api.isEnabled()) {
            return;
        }

        RegionScheduler.Position spawn = RegionScheduler.Position.of("minecraft:overworld", 0, 0);
        api.regionScheduler().run(spawn, () -> api.threadContext().kind());
    }
}
```

Fabric-Folia calls registered initializers during server startup, after its API facade is installed and before regular ticking begins. Check `isEnabled()` before relying on region scheduling. After shutdown, isEnabled returns false and new scheduler access/submission is rejected; read-only thread-context diagnostics remain available.

## Scheduler contracts

| API | Execution context | Time base | Typical use |
|---|---|---|---|
| `RegionScheduler` | Current owner of a world/chunk position | Region ticks | Region-owned blocks, chunks and entities |
| `EntityScheduler` | Current owner of the entity | Region ticks | Work that follows an entity between regions |
| `GlobalScheduler` | Minecraft server thread, in GLOBAL context after region workers quiesce | Server ticks | Server-wide state and cross-world bookkeeping |
| `AsyncScheduler` | Bounded async worker pool | Wall clock for delays | IO and computation independent of world state |

Global callbacks are not executed on a permanently running global-region thread. They run on the server tick thread after worker quiescence and before vanilla world mutation. Keep them short and non-blocking. Region callbacks must not mutate another region directly. Async callbacks must hand world work back to an appropriate scheduler.

## Positions and ownership

`RegionScheduler.Position.of(world, chunkX, chunkZ)` takes chunk coordinates, not block coordinates. Use `ownerOf(position)` for diagnostics; do not retain a `RegionInfo` view as a permanent routing handle because region boundaries can change.

Split-aware queue routing is under active verification. The API must not be treated as a guarantee that every Minecraft subsystem or third-party mod is safe for parallel execution.

## Supported environment and maturity

The current build targets Minecraft 26.2, Fabric Loader 0.19.5 or newer, Fabric API 0.160.0+26.2, and Java 25 or newer. The API has unit tests and a Maven Local publication task. A public Maven repository is not configured.

Fabric-Folia implements experimental staged-region execution: vanilla selects tick work on the server thread and selected bodies are dispatched to region-owned contexts. It is not a full independent Folia region tick loop. Mod compatibility and live production soak remain release gates.
