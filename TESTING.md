# Testing and release validation

## Automated tests

The project is divided into three Gradle modules:

| Module | What its tests cover |
|---|---|
| api | Public API shape, annotations, context kinds and Java-only signatures |
| common | Regionizer invariants, split/merge, queue accounting, schedulers, worker lifecycle, ownership and configuration |
| fabric | Runtime adapters, staging, commands, mixin-adjacent routing, configuration-to-patch wiring |

Run the module suites with:

    ./gradlew --no-daemon :common:test :api:test :fabric:test

Run the full build and API publication task with:

    ./gradlew --no-daemon clean build :api:publishToMavenLocal

The repository's previous test inventory was 209 tests before the latest API, configuration, server-thread-global, and split-routing regressions were added. Use the JUnit XML reports from the current build for the exact count; do not treat the historical count as a current test total.

## Jenkins gates

The Jenkins pipeline cleans the workspace build outputs, requires non-empty JUnit reports from all three modules, builds the artifacts, validates mod metadata and mixin classes, checks the standalone API jar, records SHA-256 hashes, and archives a release manifest.

A normal build is a release candidate only. Stable publishing requires a separate PRODUCTION_RELEASE request and a checked-in approval record for the exact commit/version with runtime soak, compatibility matrix, and independent review evidence.

## Required tests before production

Automated unit tests are not sufficient. A production approval must also include:

- Dedicated-server startup on the exact Minecraft/Loader/API/Java versions, with every required mixin applying.
- Disposable-world gameplay tests for entity, block-entity, player, scheduled and random tick paths that are enabled.
- Chunk load/unload, region split/merge, teleport, respawn, packet routing and entity migration tests.
- Save, orderly shutdown, process restart, and world reopen validation.
- Long-running high-load soak with queue depth, TPS/MSPT, p95/p99 quiesce latency, memory, worker health, and no simulation loss recorded.
- Exact third-party modpack compatibility checks.
- Independent review of concurrency and release evidence.
