# Fabrolia production stand reference

Source: https://github.com/DUzzL/fabrolia/blob/prod/current/docs/STAND.md

The production branch documents a test stand with 40 real protocol clients over loopback. Unit tests are explicitly not considered proof of a threading change.

## Runtime verification

The documented verify run checks:
- Datapack function fences.
- Advancement grants and rewards.
- Disconnect durability.
- Nether portal transfers in both directions.
- World saves and player files.
- Log and crash-report scanning.
- Mixin export against the exact mod set.

## Performance runs

The documented stand provides:
- Baseline multi-client load.
- Item entities in flowing water.
- Piston clocks.
- Heap/class-histogram captures.
- Spark profiles for old/new jar comparison.
- Before/after per-island TPS, MSPT, lag and missed-deadline snapshots.

The comparison method is changed-frame profiling under the same scenario, not raw server TPS alone. A light Main thread can make total TPS misleading.

## Operational controls

- Unique run directory per test; never reuse or overwrite a previous run.
- A lock prevents overlapping stand runs.
- Server and clients run on separate CPU sets when configured.
- Loopback-only fake clients and disposable worlds.
- Isolated server process and explicit cleanup.
- A manifest and structured JSON report per run.
- Any crash report is a failure.
- A mixin-export command is separate from server startup and gameplay verification.

## Fabric-Folia adaptation

Do not copy Fabrolia stand scripts. Build an independent harness with equivalent evidence:
1. Disposable server instance and immutable artifact manifest.
2. Exact Fabric Loader/API/mod versions and checksums.
3. 40-client protocol driver for the full benchmark profile.
4. Functional tests for portals, saves, disconnect durability, advancements, datapacks, redstone/farms, player packets and chunk boundaries.
5. Workload scenarios for distributed players, one hot island, items, pistons and chunk churn.
6. Spark/JFR profile capture and old/new frame comparison.
7. Unique run IDs, process/port inventory, lock, cleanup and artifact retention.

Hardware constraint: FAC is a 16 GB host. Do not run a 16 GB server heap plus 40 protocol clients there. Provide a reduced FAC smoke profile and a full-load profile intended for a 32 GB or larger benchmark host. The full profile must not run unless that host is explicitly selected.

The first QA harness pass must remain dry-run only. No server is to be booted until the manager approves the reconciled code and artifact inputs.
