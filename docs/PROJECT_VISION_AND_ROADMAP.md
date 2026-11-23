# Project vision and roadmap

<sup>Last reviewed: 2026-11-22 (final manager handoff) · owner: engineering lead · status: target, not a claim</sup>

This document states where Fabric-Folia is going, how progress is measured, and what is still missing. It is deliberately written so that any claim in it can be falsified by a named test, a named commit or a named measurement. When this document and the code disagree, the code wins and this document is wrong.

Related: `docs/status.md` (release decision), `docs/folia-parity.md` (per-subsystem status), `tools/freebuff/reports/defect-ledger.md` (open defects), `FREEBUFF.md` (living handoff).

## 1. Vision

Fabric-Folia should be a **Fabric-native, genuinely region-threaded Minecraft 26.x server mod** that a real server owner can run with their existing modpack: separate areas of a world tick concurrently on different cores under one explicit ownership contract, while everything global stays single-writer and vanilla-observable behaviour — tick order, farms, redstone, datapacks, mob caps, persistence — is preserved or the deviation is documented and measurable.

Today the project is a **staged-region execution layer**: vanilla's own server-thread passes still decide *what* ticks; captured bodies run on region-owned workers behind a server-thread quiescence barrier; all gameplay staging is opt-in and off by default. That is a real parallel execution mechanism, but it is not yet an independent region tick loop, and it is not production-ready.

The ambition — measured correctness, performance, compatibility and maintainability that exceed comparable work (currently Fabrolia) — is a **target**. It is reached only by evidence, one acceptance gate at a time.

## 2. Non-negotiables

1. **Fabric-native.** Mixins, Fabric API, Loom. Never a Paper/Folia fork, patch distribution, Bukkit runtime or Paper dependency.
2. **Single writer per world / chunk / entity / player / block entity** at every instant, with ownership transitions that are observable and testable.
3. **Root causes only.** No swallowed exceptions, no dropped tasks, no disabled feature as a "fix", no weakened assertions, no unbounded waits, no silent timeouts. Failures are retried or fail-stop, and visible in metrics.
4. **The server thread never waits on a worker that needs the server thread.** Every cross-context wait needs a proof that the other side can progress, or it is a deadlock.
5. **Vanilla parity by default.** Experimental execution is opt-in; safe defaults ship.
6. **Evidence or silence.** No performance claim without an identical-condition measurement; no compatibility claim from metadata; no stability claim from a single boot.
7. **License hygiene.** Apache-2.0 only. Fabrolia is GPL-3.0: its public behaviour, documentation and architecture concepts may be studied; its source is never copied, ported, translated or used to derive an implementation. Equivalents are designed from vanilla bytecode and our own invariants.

## 3. Current state, honestly

**Implemented and unit-tested** (reconciled tree, 2026-11-22): 280 tests recorded from the reconciled run's JUnit XML (API 10, common 148, Fabric 122; zero failures/errors/skips) — `common` regionizer/scheduler/pool/deferral core, `api` contract, `fabric` staging hub, mixins and engine. A later focused core run adds **25 passing common tests** (7 `RegionSchedulerSplitRehomeTest`, 4 `RegionFailurePolicyTest`, 10 `RegionEntityRegistryTest`, 4 `EntitySchedulerLifecycleTest`; 0 failures/errors/skips), executed with `bash ./gradlew` because the worktree `gradlew` lacks the executable bit. The 280 and 25 numbers are **different runs**: the 280 baseline predates the core D1–D4 patch and its XML has been overwritten. Both are unit evidence only: no mixin export, server boot, gameplay, soak or performance run has been performed on this tree.

**Implemented and worth trusting:** region ownership/split/merge mechanics, bounded worker pool with quiescence, per-region MSPT and queue metrics, staged entity / block-entity / scheduled-tick / player bodies behind gates, server-thread deferral ledger, barrier-safe ticket-load bridge, per-tick player owner pin, packet routing carve-outs, `/folia` diagnostics.

**Not verified:** the merged tree has had no dedicated-server boot, no live gameplay, no persistence, no soak and no benchmark. The mixin register declares 29 classes; that is a count in `fabricfolia.mixins.json`, **not** proof that any of them applied. The only live evidence in the repository's history belongs to earlier commits and does not transfer to this tree.

**Known structural limits:** worldgen, spawning, world border, daylight and weather remain server-thread; explosions are not region-aware; redstone time is global; portals are covered only through the teleport funnel; login placement is vanilla; direct `PlayerList.respawn` callers are uncovered; a routed body that removes the player whose tick is executing is an open stop case.

**Release decision:** blocked (`docs/status.md`, `release/production-approval.json`).

## 4. Comparison baseline: Fabrolia

Facts verified on 2026-11-22 against GitHub API, tag `f_3.2.6` metadata and public documentation:

| Fact | Value | Source |
| --- | --- | --- |
| Repository | `DUzzL/fabrolia`, created 2026-10-10, 19 stars, default branch `main` | GitHub API |
| License | GPL-3.0 | GitHub API |
| Releases | **3 total**: `f_3.2.0` (2026-10-25), `f_3.2.1` (2026-10-26), `f_3.2.6` (2026-11-21) | GitHub API |
| Tags | 4: `prod/current`, `f_3.2.6`, `f_3.2.1`, `f_3.2.0` | GitHub API |
| Version string | `version=3.2.6-alpha`; assets named `fabrolia-alpha_3.2.6.jar` | `gradle.properties`, release assets |
| Minecraft target | 26.3, range `[26.3,26.4)`; Fabric API `0.161.0+26.3`, Loader `0.19.5`, Java 25 | `gradle.properties` |
| Documented model | 8×8-chunk regions; touching loaded regions form an *island*; islands tick concurrently with independent clocks; main thread keeps commands, joins/leaves and cross-island work | public README |
| Documented incompatibilities | Moonrise, Open Parties and Claims | public README |

**Release-history reading:** there is **no mature release history** — a six-week-old repository with three releases, all alpha-named, the newest published the day before this review. GitHub not marking them `prerelease` is a metadata choice, not a stability statement. The `f_3.2.6` notes themselves list a remaining server stop: a routed body that teleports or removes the very player whose tick it runs in. Comparing against Fabrolia therefore means comparing against an alpha with documented stop cases — useful as a functional target, not as a correctness certificate.

### Operational reference: `prod/current`

The `prod/current` ref is the better **operational** reference, and a different point in the release timeline from `f_3.2.6`. Its README states it runs one approximately 35-player production server with a 20 TPS vanilla-behaviour goal; its AGENTS.md states around 1,400 tests (including source/bytecode wiring tests), a 40-real-client server stand with verify and performance modes, Spark/JFR diagnostics and modpack mixin-export verification. `f_3.2.6` is newer development at around 1,600 tests, but its own notes still carry the routed-player self-teleport/removal stop case. **All of these are claims attributed to those repositories' README/AGENTS/release notes; this project has not reproduced any of them.** The surface-by-surface comparison, including player turn ownership, async lifecycle/packet continuations, island scheduled ticks, chunk delivery and the explicit Main fallback, is recorded in `tools/freebuff/reports/fabrolia-comparison.md`. Missing equivalent surfaces are design requirements for independent implementation, not code to port.

**Claims not reproduced here:** the repository's own documentation claims roughly 1,600 tests, ~60 source/bytecode wiring tests, a 40-protocol-client stand harness, teleport/island regression suites and Spark profile comparison. Those are repository documentation, not independently verified evidence.

**What to match (behaviour, independently designed):** region cells distinct from larger connected tick units; explicit semantics for per-unit clocks (logical time, deadlines, catch-up, timer migration); player connection work revalidated at the phase boundary; chunk delivery treated as a transactional lifecycle; owner-local scheduled ticks with vanilla ordering and restoration; explicit, operationally bounded main-thread fences; split/merge claim epochs so no task is stranded; failure paths and diagnostics that name the fallback.

**What not to do:** copy, port, translate or structurally imitate their code; adopt their configuration surface or class layout; claim parity from their README.

## 5. Architecture direction

Clean-room, from our own invariants:

1. **Ownership epochs.** Every region/island carries a monotonically increasing epoch. Bodies capture the epoch they were staged under; a body whose epoch is stale is re-resolved or replayed, never executed against a changed topology. Split/merge becomes a claim barrier rather than a lock dance. *(Absent today; the player pin is a per-player, per-tick approximation.)*
2. **Heterogeneous single writer, not hope.** Every vanilla structure that is global (ticket maps, entity tracking, player list, light sections, chunk broadcast sets) gets exactly one declared owner and one funnel; anything else is a bug. *(Partially there: `ServerThreadDeferral`, `ChunkBroadcastDeferral`, `ScheduledTickDeferral`, `LightEngineDeferral`.)*
3. **Bounded, provable cross-context waits.** A wait must name the producer that can satisfy it and the mechanism that runs that producer. The barrier pump is that mechanism; every blocking worker path must be classified as pump-satisfiable or bounced. *(Open: P1-2.)*
4. **Independent tick units, when earned.** Per-unit clocks are only meaningful once ownership, tickets, scheduled ticks and saves are owner-local. Until then, "region TPS" is a diagnostic of drain progress, not a simulation clock.
5. **Saves as snapshot, then parallel IO.** Snapshot under the owner's turn; serialize per file; parallelize across files; bounded queue; fatal errors propagate. *(Today: shutdown drain only, no test proving drain-before-save.)*
6. **Diagnostics as a product surface.** Every fallback, retry, wait, drop and replay is counted and readable: `/folia metrics`, `/folia health`, `/folia patches`, `/folia threads`.

## 6. Roadmap

Each phase ends with an exit gate. A phase is not "done" because code merged; it is done when the gate's evidence exists at a named commit.

### R0 — Reconcile and re-baseline *(partial; 2026-11-22)*
- **Done:** the four upstream commits are integrated with the local dirty work in `/tmp/ff-reconcile`; `WorkerChunkProbe` is the one authoritative chunk-lookup decision and `RegionChunkAccessGuard` is retired by design; the defect ledger is rebuilt against the merged tree; planning docs are consistent with it.
- **Open hygiene item (M1):** the retired `RegionChunkAccessGuard.java` and its test are still present untracked and would still run. `reconciliation.md` says they were deleted; they were not. Delete both when production edits are allowed, then rerun `:fabric:test`.
- **Done (unit):** **318 tests green in one run** at the 2026-11-23 snapshot (api 10, common 167, fabric 141; status hash `ae97c1adabf5448a`) and `build` exit 0 at the same tree. A live 26.2 boot of that tree's jar reached `Done (5.331s)` with zero mixin errors.
- **Remaining for exit:** a `/folia metrics` capture (blocked by L1 — the jar still nests no `examination` classes, so every `/folia` command throws `NoClassDefFoundError`); a `/tp`/portal gameplay check; apply the reconciliation manifest to the shared checkout (blocked on explicit approval).
- **Exit:** green suite at the final commit, clean boot, ledger and docs matching that commit.

### R1 — Correctness spine *(in progress; R1/R5/R11 accepted at E3, R2/R3/R4 still block)*
- **Blockers carried in:** P0 R2 (disconnect/removal on a worker), R3 (structure lock on Netty loops), R4 (loadedness gate, not ownership) remain open; P0 R1 (non-player teleport) is accepted at E3, P0 R5 (deferred failures) and P1 R11 (register/retire race) are fixed at E3; P1 R6 is partial (drop/retirement half fixed, positionless producers open). See `tools/freebuff/reports/defect-ledger.md`.
- **Status (updated 2026-11-23):** R1 is **accepted at E3 + final-jar boot, not closed** — `EntityTeleportDispatchTest` 14/14 inside a 141-test green fabric suite, and the **R10 `noteServerThread` residual is closed** (production call sites in `FabricFoliaMod.startEngine` and both `MinecraftServerMixin` heads). Only the disposable-world `/tp`/portal check (E4) remains. R5 is fixed at E3 with 5 new tests (live crash-report injection still unrun). R4 was audited only: mutation reach is chunk-unbounded, so no halo mitigation was shipped and R4 stays open P0. Reports: `runtime-implementation.md`, `runtime-hardening-r4-r5.md`, `core-implementation.md` §9-12.
- Player lifecycle: login placement, respawn, disconnect, reconnect, mount/dismount, cross-dimension transfer, and the routed-body-removes-executing-player stop case.
- Chunk lifecycle and tickets: load/unload/save, ticket funnels, create-vs-read classification, transactional chunk delivery.
- Scheduled ticks: owner-local due times, vanilla ordering, restoration of unentered work.
- Save/shutdown: prove drain-before-save; persistence round-trip on a disposable world.
- **Exit:** named regression for each item + disposable-world functional run + restart persistence evidence.

### R2 — Measurement
- Identical-condition harness: fixed seed/pregenerated world, fixed JVM flags/heap/affinity, fixed mod set, identical client behaviour, Spark + JFR capture.
- Report p50/p95/p99 MSPT, per-region tick rate, server-thread stall time, worker utilization, queue depths, memory/GC.
- Vanilla vs candidate at the same workload, repeated runs, variance reported.
- **Exit:** committed scripts + a results document with raw artifacts, not a summary sentence.

### R3 — Overload and failure behaviour
- One hot island, many small islands, split/merge churn, chunk churn, worker death, interrupted handoff, shutdown under load.
- Soak: hours, no deadlock, no lost work, no worker leak, no unbounded queue growth, no corruption, no unexpected stop.
- **Exit:** soak logs + metric time series + explicit "no finding" statements where nothing happened.

### R4 — Compatibility matrix
- Per exact artifact, separate stable releases from alpha/dev builds (`tools/freebuff/reports/optimization-mods.md`).
- Fabric baseline; Lithium `0.26.3`; C2ME `0.4.2-alpha…`; FerriteCore; Krypton `0.3.2` (best-effort per author); VMP (early development per author); ScalableLux `0.3.0-alpha…`; then selected combinations (Lithium+C2ME+VMP, C2ME+ScalableLux); Carpet/TIS-Addition where applicable.
- Evidence levels per artifact: source inspection → mixin export → startup → gameplay → soak. Metadata never counts.
- **Exit:** matrix with per-cell evidence level and a tested boundary for each integration point.

### R5 — Release engineering
- Jenkins-only pipeline, module report validation, artifact/metadata/checksum verification, independent review of the exact commit.
- `release/production-approval.json` filled only with checked-in evidence paths.
- **Exit:** gates 1–8 of `docs/status.md` satisfied; only then any stable publication.

### Cross-cutting: 26.2 → 26.3 port *(dedicated change, after R0)*
- Official current values for 26.3: Loader `0.20.1`, Loom `1.19-SNAPSHOT`, Fabric API `0.166.0+26.3` (Fabric developer page, checked 2026-11-22); Loom plugin id for non-obfuscated 26.1+ is `net.fabricmc.fabric-loom`.
- **Two pinned 26.3 matrices, not one silent upgrade.** The `prod/current` Fabrolia baseline is deliberately older (Loader `0.19.5`, Fabric API `0.161.0+26.3`), and it is **not equivalent** to the latest official pair (`0.20.1` / `0.166.0+26.3`). Test the optimization-mod set against **(A)** the conservative `0.19.5` / `0.161.0+26.3` baseline and **(B)** the latest official `0.20.1` / `0.166.0+26.3` pair, record the exact artifacts and checksums for each, and only then choose the release toolchain. Do not present one matrix as evidence for the other.
- Not a version-string bump: it is a vanilla API/mixin port (documented removals include `CompostingChanceRegistry`, `FuelRegistry`, `FabricPotionBrewingBuilder`, `StrippableBlockRegistry`, `TillableBlockRegistry`, `FlattenableBlockRegistry`), plus a re-verification of every mixin descriptor with `javap`.
- Constraint: do not start while the tree is dirty; R0 first.

## 7. Measurement protocol (binding for performance claims)

1. Same commit-built candidate and same vanilla jar; same world seed or pregenerated world; same JVM flags, heap, CPU affinity; same mod set; same client count and behaviour.
2. TPS from `time query gametime` deltas over RCON — never from warning cadence.
3. MSPT from the engine's own work-based rings *and* an external sampler (Spark/JFR); both reported.
4. Repeat each condition at least three times; report p50/p95/p99 and max, plus run-to-run variance.
5. Report what the workload is: entity count, distribution, chunk count, region count, player count. A single-region clump is an Amdahl demonstration, not a scaling result.
6. Any claim that cannot be reproduced from the committed scripts and artifacts is not made.

## 8. Workstreams and ownership

| Stream | Scope | Current owner |
| --- | --- | --- |
| Core | regionizer, scheduler, pool, deferral ledgers, clocks, split/merge, shutdown | `fabric-folia-core` (active: reviewing/testing D1–D4) |
| Runtime | mixins, tick lifecycle, player/packet/entity integration, tickets, chunks | `fabric-folia-runtime` — **no active session**; R1 diff uncommitted and unaccepted |
| Compatibility | per-artifact matrix, mixin composition, integration boundaries | `fabric-folia-compatibility` (active: writing `compatibility.md`) |
| Protocol driver | native 26.3 protocol client research and the 40-client stand driver | `fabric-folia-protocol` (active: research report delivered) |
| QA | harnesses, benchmarks, soak, release evidence | `fabric-folia-qa` — **no active session**; harness is dry-run only |
| Integration | reconciliation, docs, ledger, vision, test coordination | manager |

Shared checkout, strict file ownership, no concurrent full builds, no commit/push/deploy without explicit approval. Handoffs go through `tools/freebuff/reports/`. The launcher's `--send` mode pastes a role prompt into an **existing** session and never creates, replaces or kills one; `--resume` sends to every existing team session; both refuse a session whose input is not confirmed idle unless `--force` is explicit.

## 9. Risks

- **Barrier-liveness class.** Any worker wait that the pump cannot satisfy becomes a watchdog kill; the create-chunk case (P1-2) is the open one.
- **Scope drift toward a Folia reimplementation.** The staged model is the current product; independent tick units are a later, gated step.
- **Third-party mod assumptions.** Region workers break main-thread-only mods; the compatibility matrix is the control, and it is expensive.
- **Evidence debt.** Historic live results do not transfer across commits; each release gate needs fresh evidence at an exact commit.
- **Toolchain lag.** We target 26.2 while the reference mod targets 26.3; every skipped vanilla version increases port risk.
