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

The last recorded full test run (2026-11-16) passed 239 tests: common 144, fabric 85, api 10. This is a point-in-time result, not a guaranteed total. Test sources and counts can change between commits, and parameterized cases or assumptions can change the executed count. Use the JUnit XML reports from the build you are validating as the authoritative result.

Several fabric tests construct the real engine and exercise staging, region queues, command formatting and routing. They do not boot a Minecraft server, so they cannot prove that a mixin injection applies on a live runtime.

## Jenkins gates

The Jenkins pipeline cleans the workspace build outputs, runs all three module test tasks, publishes JUnit results with empty results disallowed, builds the artifacts, then runs `scripts/verify_release.py`. That verifier requires JUnit XML for every module, enforces per-module test floors (currently common 144, api 10, fabric 81), rejects failures, errors and unexpected skips, validates mod metadata and mixin classes, checks the standalone and nested API jars, records SHA-256 hashes, and archives a release manifest. The floors and checks move with the script, so read `scripts/verify_release.py` for the values in force at a given commit.

A normal build is a release candidate only. Stable publishing requires a separate PRODUCTION_RELEASE request and a checked-in approval record for the exact commit/version with runtime soak, compatibility matrix, and independent review evidence. The approval record for the current candidate is blocked.

## Evidence classes

- **Automated:** the unit and integration suites plus the Jenkins report and artifact checks. This is code-path evidence.
- **Smoke:** a 2026-11-16 deployment on a Pelican-hosted Fabric 26.2 server reached `Done (` and `Fabric Folia is ready` with regionized gameplay and player staging enabled in the existing server config, with no reported mixin errors. This proves startup and mixin application for that artifact; it does not exercise active players or gameplay.
- **Not present:** active-player gameplay, long-running soak, save/restart-under-load, and a committed modpack compatibility matrix. A compatibility scan on the deployed server reported 248 undeclared mods and zero declared supported mods; that is a scan result, not a matrix.

Do not upgrade a smoke result to a soak result, and do not treat an undeclared mod scan as compatibility evidence.

## Required tests before production

Automated unit tests are not sufficient. A production approval must also include:

- Dedicated-server startup on the exact Minecraft/Loader/API/Java versions, with every required mixin applying.
- Disposable-world gameplay tests for entity, block-entity, player, scheduled and random tick paths that are enabled.
- Chunk load/unload, region split/merge, teleport, respawn, packet routing and entity migration tests.
- Save, orderly shutdown, process restart, and world reopen validation.
- Long-running high-load soak with queue depth, TPS/MSPT, p95/p99 quiesce latency, memory, worker health, and every skipped random-tick pass or abandoned staged body accounted for.
- Exact third-party modpack compatibility checks.
- Independent review of concurrency and release evidence.
