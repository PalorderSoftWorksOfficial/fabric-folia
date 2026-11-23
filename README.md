![Fabric-Folia logo](fabric/src/main/resources/logo.png)

# Fabric-Folia

**A Fabric-native, experimental staged-region execution project by Palorder Softworks.**

| Project status | Compatibility target | License |
|---|---|---|
| Experimental — not production-ready | Minecraft 26.2 · Fabric Loader 0.19.3+ · Fabric API 0.160.0+26.2 minimum (0.161.0+26.2 built and deployed) · Java 25+ | Apache-2.0 |

Fabric-Folia explores running selected vanilla tick bodies in region-owned worker contexts while keeping vanilla's server-thread selection and ordering logic. It is a clean-room implementation; it is not Paper, Folia, or a drop-in replacement for either.

> **Production warning:** this project is not currently certified for production servers. Automated compilation and unit tests do not replace a live server boot, mixin verification, gameplay soak, world-save/restart validation, and third-party mod compatibility testing. Regionized gameplay is off by default. Do not enable it on a production world until your exact modpack has been tested and the release status says otherwise.

## What it does

Vanilla still decides which chunks, entities, and block entities are selected for ticking. For code paths that are enabled and intercepted, Fabric-Folia captures selected bodies, groups them by owning region and chunk, and schedules bounded work on a worker pool. Workers execute under a region ownership context. At the next server tick, the server thread closes the worker gate and waits for those bodies to quiesce before vanilla world mutation resumes.

The staged intake does not discard entity, block-entity or scheduled-tick rounds to hold a target tick rate. When a world's staged backlog exceeds its retention limit, staging is suspended for that world and further bodies run vanilla-inline on the server thread until the queue drains. This is deliberate server-thread backpressure: tick rate can fall, but a staged round is not dropped by the intake gate.

The separate random-tick slice has pass-level backpressure. If a region's queue is already saturated, that region skips the current random-tick pass; a later pass re-offers the same chunks. Random ticks are probabilistic/advisory, but a skipped pass is still a delay, and the skip can repeat while the queue stays above the limit. This is not exact simulation parity.

The global scheduler is deliberately different from an independent Folia global region: global callbacks run on the Minecraft server thread after region workers quiesce and before vanilla world mutation.

## What it is not

Fabric-Folia does not implement a fully independent Folia world-tick loop. Vanilla still performs tick selection, and the staged design has a server-thread coordination barrier. World generation, chunk loading, spawning, lighting, saving, scheduled-tick containers, and code paths without a verified interception remain on vanilla execution. The player path is separately gated and off by default.

Several Folia integration flows are also absent:

- Player login placement is not region-aware.
- Portal search and creation on the origin side are not staged; only the resulting teleport transition is routed.
- Explosion block and entity damage is not a separately regionized path. An explosion triggered from a staged entity body executes in that body's region context and can mutate blocks outside that region without a handoff.
- Direct `PlayerList.respawn` callers from other mods and destination-region placement remain uncovered.
- Per-region redstone time, per-region chunk state ownership, and per-region entity tick lists beyond the staged slices are not implemented.

This distinction is important for performance expectations: a body moved to a region worker is not proof that an entire world subsystem is independently ticking or that every mod is thread-safe. Mod compatibility is scoped evidence, not a blanket guarantee; see [COMPATIBILITY.md](COMPATIBILITY.md).

## First run

Install the built fabric-folia JAR on a dedicated Fabric server matching the pinned environment. Fabric Loader 0.19.3 is the declared minimum; Fabric API 0.160.0+26.2 is the declared minimum, and the build and 2026-11-16 smoke deployment used 0.161.0+26.2. The normal configuration is generated at config/fabric-folia.yml.

The safe defaults keep entity, block-entity, scheduled-tick, random-tick, and player-path execution on the vanilla server thread. The engine, API facade, diagnostics, region ownership services, and safe optimization patches remain available.

After testing in a disposable world, an operator can opt into the experimental paths in config/fabric-folia.yml:

| Setting | Default | Effect |
|---|---:|---|
| general.enabled | true | Loads the Fabric-Folia engine and its integration layer |
| general.regionized-gameplay | false | Master gate for experimental staged gameplay |
| patches.minecraft.entity-tick-optimization | false | Keeps all entity ticks, including item entities, on the server thread |
| patches.minecraft.chunk-tick-optimization | false | Keeps block entities and scheduled block/fluid ticks on the server thread |
| patches.minecraft.random-tick-optimization | false | Keeps random ticks on the server thread |
| gameplay.stage-player-path | false | Keeps player physics and interaction processing on the server thread |
| general.regionized-random-ticks | false | Master gate for the separate random-tick interception |

Restart the server after changing these settings. There is no /folia config or /folia reload command. The generated file contains the typed options and their defaults; see the [configuration reference](docs/configuration.md).

## Operator commands

/folia and /folia version are open. Diagnostic and administration commands default to the server owner/OP permission and can also be granted through folia.command, folia.command.<subcommand>, or the legacy fabricfolia.command.folia.<subcommand> permission nodes.

Start with /folia health, /folia status, /folia workers, /folia scheduler, and /folia metrics. See the [command reference](docs/command-reference.md). Beware that diagnostics describe the running engine; they are not a compatibility certificate.

## Developer API

The public Java API is built as a standalone fabric-folia-api artifact. The current build publishes it to Maven Local with ./gradlew :api:publishToMavenLocal; there is no public Maven repository yet. Fabric-Folia supplies the runtime classes and the fabricfolia entrypoint. See the [developer API guide](docs/developer/api.md).

## Architecture and release status

- [Project status and release gates](docs/status.md)
- [Architecture](ARCHITECTURE.md)
- [Configuration reference](docs/configuration.md)
- [Threading model](THREADING.md)
- [Testing and validation](TESTING.md)
- [Compatibility notes](COMPATIBILITY.md)
- [Folia parity ledger](docs/folia-parity.md)
- [All documentation](docs/index.md)

The Jenkins pipeline produces a hashed release candidate. Stable publication requires a separate approval record tied to the exact commit and backed by a live runtime soak, compatibility evidence, and independent review. Automated tests alone do not satisfy that gate.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Reproducible bug reports and feature discussion belong in [GitHub Issues](https://github.com/PalorderSoftWorksOfficial/fabric-folia/issues). Report security-sensitive defects through [GitHub Security Advisories](https://github.com/PalorderSoftWorksOfficial/fabric-folia/security/advisories/new).

## Attribution and non-affiliation

Fabric-Folia is maintained by Palorder Softworks and distributed under Apache-2.0. It is an independent clean-room project informed by publicly documented region-threading concepts. It is not affiliated with, endorsed by, or maintained by FabricMC, PaperMC, the Folia project, Mojang Studios, or Microsoft. Minecraft is a trademark of Microsoft/Mojang; Fabric and Folia names and trademarks belong to their respective owners.
