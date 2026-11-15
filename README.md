![Fabric-Folia logo](fabric/src/main/resources/logo.png)

# Fabric-Folia

**A Fabric-native, experimental staged-region execution project by Palorder Softworks.**

| Project status | Compatibility target | License |
|---|---|---|
| Experimental — not production-ready | Minecraft 26.2 · Fabric Loader 0.19.5+ · Java 25+ | Apache-2.0 |

Fabric-Folia explores running selected vanilla tick bodies in region-owned worker contexts while keeping vanilla's server-thread selection and ordering logic. It is a clean-room implementation; it is not Paper, Folia, or a drop-in replacement for either.

> **Production warning:** this project is not currently certified for production servers. Automated compilation and unit tests do not replace a live server boot, mixin verification, gameplay soak, world-save/restart validation, and third-party mod compatibility testing. Regionized gameplay is off by default. Do not enable it on a production world until your exact modpack has been tested and the release status says otherwise.

## What it does

Vanilla still decides which chunks, entities, and block entities are selected for ticking. For code paths that are enabled and intercepted, Fabric-Folia captures selected bodies, groups them by owning region and chunk, and schedules bounded work on a worker pool. Workers execute under a region ownership context. At the next server tick, the server thread closes the worker gate and waits for those bodies to quiesce before vanilla mutates world state again.

The global scheduler is deliberately different from an independent Folia global region: global callbacks run on the Minecraft server thread after region workers quiesce and before vanilla world mutation.

## What it is not

Fabric-Folia does not currently implement a fully independent Folia world-tick loop. Vanilla still performs tick selection, and the staged design has a server-thread coordination barrier. Not every vanilla subsystem is regionized. World generation, spawning, some scheduled-tick paths, and code paths without a verified interception remain on vanilla execution. The player path is separately gated and off by default.

This distinction is important for performance expectations: a body moved to a region worker is not proof that an entire world subsystem is independently ticking or that every mod is thread-safe.

## First run

Install the built fabric-folia JAR on a dedicated Fabric server matching the pinned environment. The normal configuration is generated at config/fabric-folia.yml.

The safe defaults leave regionized gameplay, player connection staging, and regionized random ticks disabled. This keeps normal startup on vanilla gameplay execution while the engine, API facade, diagnostics, and region ownership services are initialized.

After testing in a disposable world, an operator can opt into the experimental paths in config/fabric-folia.yml:

| Setting | Default | Effect |
|---|---:|---|
| general.enabled | true | Loads the Fabric-Folia engine and its integration layer |
| general.regionized-gameplay | false | Stages supported entity and block-entity tick bodies |
| gameplay.stage-player-path | false | Stages the player connection path; experimental |
| general.regionized-random-ticks | false | Enables the separate random-tick interception |

Restart the server after changing these settings. There is no /folia config or /folia reload command.

## Operator commands

/folia and /folia version are open. Diagnostic and administration commands default to the server owner/OP permission and can also be granted through folia.command, folia.command.<subcommand>, or the legacy fabricfolia.command.folia.<subcommand> permission nodes.

Start with /folia health, /folia status, /folia workers, /folia scheduler, and /folia metrics. See the [command reference](docs/command-reference.md).

## Developer API

The public Java API is built as a standalone fabric-folia-api artifact. The current build publishes it to Maven Local with ./gradlew :api:publishToMavenLocal; there is no public Maven repository yet. Fabric-Folia supplies the runtime classes and the fabricfolia entrypoint. See the [developer API guide](docs/developer/api.md).

## Architecture and release status

- [Project status and release gates](docs/status.md)
- [Architecture](ARCHITECTURE.md)
- [Configuration reference](docs/configuration.md)
- [Threading model](THREADING.md)
- [Testing and validation](TESTING.md)
- [Compatibility notes](COMPATIBILITY.md)
- [All documentation](docs/index.md)

The Jenkins pipeline produces a hashed release candidate. Stable publication requires a separate approval record tied to the exact commit and backed by a live runtime soak, compatibility evidence, and independent review. Automated tests alone do not satisfy that gate.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Report security-sensitive defects through [GitHub Security Advisories](https://github.com/PalorderSoftWorksOfficial/fabric-folia/security/advisories/new).

## Attribution and non-affiliation

Fabric-Folia is maintained by Palorder Softworks and distributed under Apache-2.0. It is an independent clean-room project informed by publicly documented region-threading concepts. It is not affiliated with, endorsed by, or maintained by FabricMC, PaperMC, the Folia project, Mojang Studios, or Microsoft. Minecraft is a trademark of Microsoft/Mojang; Fabric and Folia names and trademarks belong to their respective owners.
