# Fabric-Folia

**Regionized multithreaded server execution for vanilla Minecraft under Fabric Loader.**

Fabric-Folia partitions the server's world into dynamically-sized, independently-ticking
regions executed in parallel on a bounded worker pool — the execution model Folia
pioneered, rebuilt clean-room as a normal Fabric mod. No custom server distribution: it
loads as a mod jar on a standard Fabric server.

> **Status: first interception milestone — proven end-to-end on live servers.**
> The engine primitives (dynamic regionizer with all four Folia invariants,
> EDF-dispatched worker pool, thread-ownership diagnostics, comment-preserving
> config engine) are implemented and stress-tested, and the first genuine vanilla
> interception is live: the per-chunk random-tick pass executes on region worker
> threads, with **proven world mutation**, worker-thread attribution, dispatch
> cessation on unpin, and a measured compatibility matrix against the Fabric
> optimization ecosystem. All other vanilla passes still run on the server
> thread; the remaining tick pipeline is the next milestone (see
> `ARCHITECTURE.md`).

## Requirements

| Component | Version |
|---|---|
| Minecraft | 26.2 (dedicated server) |
| Fabric Loader | ≥ 0.19.5 |
| Fabric API | 0.160.0+26.2 (or newer for 26.2) |
| Java | 25 |

## Building

```bash
./gradlew build          # builds all modules, runs the test suite
./gradlew test           # common-module concurrency/config test suite
./gradlew :fabric:runServer   # boots a dev server with the mod loaded
```

The distributable mod jar is `fabric/build/libs/fabric-0.1.0.jar` (self-contained: the
api/common modules and SnakeYAML Engine are nested inside it).

## Modules

```
api/      Public, Fabric-native API (Region/RegionScheduler/EntityScheduler/
          GlobalScheduler/ThreadContext, threading annotations). ZERO Minecraft
          or loader types — other mods compile against this alone.
common/   Loader-agnostic engine: regionizer, regions/sections, worker-pool
          scheduler, thread-context core, task queues, YAML config engine.
          Pure Java; the whole test suite runs without Minecraft on the classpath.
fabric/   The mod: entrypoints, Fabric API wiring, (future) Mixins, commands,
          diagnostics sink. Produces the distributable jar.
```

**Why the `common`/`fabric` split is real, not artificial** (spec section 12 asks for
the justification): the regionizer and scheduler are the concurrency-safety core of the
entire project. They must be provable in isolation — tested under stress WITHOUT a
Minecraft server running, iterated on without Loom's build cycle, and reusable if the
loader-facing layer changes. Everything that genuinely requires Minecraft types
(entrypoints, Mixins, commands, the diagnostics logger) lives in `fabric`. The boundary
follows the dependency direction `fabric → common → api`; `api` depends on neither.

## Configuration

`config/fabric-folia.yml`, generated on first run as a complete self-documenting
template. Every option documents purpose, default, valid values, performance,
compatibility and safety implications, and restart-vs-live behavior. **Administrator
comments survive saves** (round-trip tested); unknown keys are preserved and flagged,
never silently dropped; missing keys are backfilled from the schema automatically
(partial configs self-repair), and a duplicated key is collapsed to one on load —
the last value wins and the duplicate never round-trips through a save. `config-version`
carries an explicit migration contract — future versions fail closed rather than guessing.

## Commands

- `/folia` — overview
- `/folia status` — engine state, worker count, thread-check mode, intercept state
- `/folia shutdown` — graceful server stop (admin): region work drains, worlds save,
  then the process exits — the vanilla `/stop` lifecycle triggered from chat/console
- `/folia regions` — live per-region detail (state, ticks executed, chunks)
- `/folia threads` — Fabric Folia's worker threads
- `/folia compat` — the startup compatibility scan, on demand (measured results in
  `COMPATIBILITY.md`)

## Console

Fabric Folia logs with a `[FabricFolia]` prefix and text severity words (INFO,
SUCCESS, WARNING, ERROR) that remain readable without ANSI colors; startup walks
through configuration, schedulers, compatibility detection (once per mod, with a
count summary — never a per-mod warning storm), and a ready line that states which
mode the server is in. `diagnostics.debug-logging` enables per-pass and worker
execution evidence; normal operation stays quiet. See `TROUBLESHOOTING.md` for
every administrator-facing message explained.

## Documentation

- `docs/folia-parity.md` — the Folia parity checklist: every architectural
  subsystem with its measured status (NOT_IMPLEMENTED → VALIDATED) and the
  concrete criteria for each step up
- `ARCHITECTURE.md` — module design, state classification, Folia derivation and
  attribution, feasibility constraints, deviation rationale
- `REGIONS.md` — the dynamic region model (and why "8×8" is a section size, not a grid)
- `THREADING.md` — ownership model, thread context, disable/quiescence contract
- `SCHEDULING.md` — worker pool, EDF dispatch, tick pipeline partitioning
- `MIXINS.md` — the mixin register, the mandatory 7-question analysis, and the
  optimization-mod overlap policy
- `COMPATIBILITY.md` — the measured matrix (Lithium, C2ME, FerriteCore, Krypton,
  VMP, ScalableLux), failure-isolation protocol, and the C2ME interaction detail
- `docs/compatibility/` — per-mod measured pages; how mods declare support; what
  legacy compatibility handling means for undeclared mods
- `TROUBLESHOOTING.md` — symptom-first fixes for administrators, with the real
  console strings
- `TESTING.md` — test coverage, the world-mutation proof, and how to reproduce it
- `docs/folia-parity.md` — the parity ledger: Folia behavior vs. what is
  implemented and live-verified here

## License

Apache-2.0. Folia's server patches are GPL-3.0: **no Folia source is used or copied**;
Folia's public documentation is the architectural reference (see `ARCHITECTURE.md`).
