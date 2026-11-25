# Fabric-Folia live configuration reload verification

Date: 2026-11-25
Minecraft: 26.2
Fabric API: 0.161.0+26.2
JAR: fabric/build/libs/fabric-folia-0.1.0.jar
SHA-256: a0a0d7b6f5a5560df77f58e2e9536277c356d7573c0e669c3a90aedc16b53c7d

## Runtime behavior

The /folia reload command is administrator/OP gated and validates a fresh immutable FoliaConfig snapshot before changing live state. Failed parse, migration or schema validation returns an error and does not replace the active runtime snapshot.

Live settings:
- general.server-brand
- general.server-brand-name
- diagnostics.debug
- diagnostics.debug-logging
- diagnostics.watchdog when the engine is running

All other settings are restart-required. That includes general.enabled, worker counts, thread-check mode, region structure, GPU setup, profiling, metrics and all patch/interception settings. The values are read and validated and retained on disk, but the currently running engine retains its startup snapshot for those settings. Pending restart-required settings remain reported across repeated reloads until the file is returned to the startup values or the server restarts.

The startup master switch remains authoritative for the current process. Editing general.enabled in the file and reloading cannot start or stop the engine during a running server. Server-brand enable/name values are read from the live snapshot while the engine started enabled. If the engine started disabled, brand changes are reported as restart-required because the startup brand gate remains off; debug logging can still be changed live.

## Unit/build evidence

One coordinated test pass:

    bash ./gradlew :common:test :fabric:test :api:test --rerun-tasks --stacktrace

Result: 340 tests — common 174, Fabric 156, API 10 — zero failures, errors or skips.

Full build:

    bash ./gradlew build --stacktrace

Result: BUILD SUCCESSFUL. Non-fatal warnings remain for Java 25 ThreadDeath deprecation, unchecked operations in LevelScheduleTickMixin, OpenCL FFM restricted native access, and Gradle deprecations.

## Disposable live smoke

A disposable Minecraft 26.2 / Fabric API 0.161.0+26.2 instance booted the final JAR and reached Done (0.436s). No Mixin or class-loading errors were logged.

Initial status:
- Engine ACTIVE
- 4 worker threads
- Server brand fabricFolia
- Debug logging disabled
- Region watchdog running

The config file was then edited to:
- general.server-brand-name: ReloadedBrand
- diagnostics.debug-logging: true
- diagnostics.watchdog: false
- threads.worker-threads: 6
- general.enabled: false

First /folia reload returned:
- Applied live: debug-logging, watchdog, server-brand-name
- Restart required: general.enabled, threads.worker-threads

/folia status then reported:
- Engine ACTIVE
- 4 worker threads
- Server brand ReloadedBrand
- Debug logging enabled
- Region watchdog stopped

A second /folia reload continued to list general.enabled and threads.worker-threads as restart-required, without treating them as newly applied runtime settings.

A dedicated unit test also exercises the engine-disabled state: diagnostics.debug-logging remains live, while server-brand and watchdog changes are reported as restart-required because no engine/brand startup gate is active. This prevents the command from claiming a brand change was applied when the startup general.enabled gate keeps the vanilla brand path active.

For rejection behavior, regions.region-section-size was changed to 7. /folia reload rejected the config with the schema validation error. /folia status continued to report section size 8, the engine active, 4 workers, the reloaded brand and debug state, and watchdog stopped.

Changing watchdog back to true and running /folia reload started the watchdog. /folia status then reported it running.

The test server was stopped, the original disposable config was restored and no temporary server process remained.

## Scope and limits

This is a restricted live reload, not arbitrary hot reconfiguration. Changes to worker topology, region structure, startup master switch, GPU initialization, thread-check mode, profiling/metrics and patch/interception behavior require restart. The R4 safety interlock still forces built-in worker-side mutation paths off regardless of config. The smoke test verifies one disposable server and the supported setting subset; it is not production-world or full mod-compatibility evidence.
