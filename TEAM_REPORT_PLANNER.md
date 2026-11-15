# Fabric-Folia — Product & Systems Architecture Audit

**Author:** Product Planner / Systems Architect (read-only audit)
**Date:** 2026-11-15
**Scope:** repository state at `main` = `6db343d` (working tree clean)
**Constraint honored:** no source or shared-doc edits, no build, no server boot, no commit/push.
**Deliverable:** this file only.

---

## 0. Executive summary

Fabric-Folia is a real, working Fabric mod that moves a meaningful share of vanilla's
tick work off the server thread and onto a bounded worker pool, with a genuine
clean-room regionizer and genuine single-writer discipline. It is **not**, in its
current form, a Folia-equivalent regionized tick loop, and it should not be marketed
or shipped as production-ready.

The load-bearing finding: **the engine is a two-phase "vanilla decides, region
executes" scheduler with a global per-tick barrier** — the server thread closes a
worker gate at `processPacketsAndTick` HEAD, runs *all* vanilla world simulation
serially, then flushes staged bodies to region queues and reopens the gate for the
inter-tick window. Regions are execution lanes for staged bodies, not independent
world ticks. `RegionScheduler`'s region-tick body is a no-op in the default
configuration (`FabricFoliaEngine:272-275`), so a "region tick" is a queue drain.

Two consequences drive the whole assessment:

1. **A hard wall-clock ceiling.** Region work only executes in the inter-tick window
   (≤ `DRAIN_WINDOW_MILLIS = 20` ms plus whatever vanilla sleeps). At 20 TPS that caps
   achievable parallel work at roughly half of wall time, no matter how many workers
   exist. The measured 61,425-entity spread test (12.5 TPS, workers saturated) is that
   ceiling, not a tuning miss.
2. **The headline 20-TPS stress result is bought by discarding ticks.** The
   "region slow-motion intake gate" (`RegionStageHub:187`, `:651-663`) *drops* an
   overloaded region's entire staged round — permanently, not deferred. The recorded
   run shows 22.1 M bodies skipped against 1.5 M executed (~93 % discarded) while TPS
   held at 20. That is a game-semantics divergence, and it contradicts the project's
   own non-negotiable rule ("never suppress, never silently drop") and its own
   Phase-1 no-loss fix.

Neither of those is fatal to the project — both are decisions that should be made
explicitly rather than discovered by an operator — but together they mean the current
positioning ("Folia-style regionized multithreaded execution", README:13-21) overstates
what the code does, and the default configuration (`general.regionized-gameplay: true`,
`gameplay.stage-player-path: true`) enables the least-validated path in the tree.

Take the default aggressive path off by default, tell the truth in `README.md` /
`ARCHITECTURE.md` / `SCHEDULING.md` / `COMPATIBILITY.md`, make tick discards an explicit
operator choice, re-run compatibility against the shipped default, and the project has a
credible, differentiated product: **a Fabric-native, mod-compatible, staged-parallel
execution layer** — something Folia cannot offer, because Folia is not a mod.

**Recommendation summary:** do not declare production readiness. Ship the next release
as *experimental*, with a conservative default profile, after Phase 0 and Phase 1 below.

---

## 1. Method and evidence base

| Item | Value |
| --- | --- |
| Commit audited | `6db343d` (`docs: record Fill publisher permission fix`), 55 commits, first 2026-09-16 |
| Source read | `api` 968 LOC / 14 files, `common` 9,270 LOC (main), `fabric` 10,252 LOC (main) |
| Mixins registered | **26** (`fabric/src/main/resources/fabricfolia.mixins.json`), `defaultRequire: 1` |
| Tests (annotation count, verified by `grep`) | **209** total: `common` 133, `fabric` 76, **`api` 0** |
| CI | `Jenkinsfile` (Linux agent; `:common:test :fabric:test :api:test`, junit publish, `build`, archive, Fill publish) |
| Measurement instrument in-tree | `/folia metrics`, `RegionMetrics`, `StallProbe`, region watchdog |
| Live evidence | Only in `FREEBUFF.md` §6 (session logs, RCON transcripts, JFR summaries). No raw logs, JFR dumps, or benchmark worlds are committed |

### 1.1 Verified vs. assumed (this audit)

**VERIFIED in this audit (code read, not executed):**

- Module boundary is real: `api` and `common` contain **zero** `net.minecraft` /
  `net.fabricmc` imports (grep count 0). `fabric` is the only Minecraft-coupled module.
- The 26 registered mixins match the files present; all 26 target classes exist in the
  mixin package.
- The tick-phase barrier exists exactly as described: `MinecraftServerMixin:31` →
  `FabricFoliaEngine.beginTickPhase():609` → `WorkerPool.closeExecutionAndAwait():193`
  → `executionOpen = false`; reopen at `FabricFoliaMod:306-307`
  (`endTickPhase` → `awaitStagedDrain`).
- The region tick body is empty unless random-tick interception is armed
  (`FabricFoliaEngine:273`: `interceptActive ? interceptor.regionTickBody(...) : region -> { }`).
- The staged-body drop path exists and is unconditional for non-PLAYER slices
  (`RegionStageHub:651-663`); `PLAYER` and retry replays are exempt, player **entity**
  bodies are not.
- The API entrypoint delivery is implemented (`FabricFoliaMod:259-284`), contradicting
  its own javadoc (`FabricFoliaApi:19`).
- Documentation drift at the cited lines (README:13-21, ARCHITECTURE:10-11,
  SCHEDULING:82-93, COMPATIBILITY:127/152, THREADING:247, `FabricFoliaApi:19`).
- `docs/mandates/` does not exist, while 96 `mandate §N` references across code and docs
  (130 distinct section numbers) point at it. `AGENTS.md:5` advertises the directory.

**ASSUMED (claimed in `FREEBUFF.md` / `docs/folia-parity.md`, not reproducible here):**

- "209/209 green, `build` rc=0" — plausible (209 `@Test` exist) but this audit ran no build.
- Every live TPS/MSPT figure, the 11-combo compatibility matrix, the 61k-entity stress,
  the C2ME interaction, the production crash root-causes, and the `pushEntities` stall
  analysis. These are detailed and internally consistent, and several come with
  bytecode/JFR evidence — but they are handoff assertions, not artifacts in the repo.
- That mixin injection succeeds on 26.2 (only a live boot proves it; no CI boot exists).

This distinction matters for planning: the team has unusually high-quality *session*
evidence and almost no *committed* evidence.

---

## 2. What the engine actually is

### 2.1 The execution model (verified)

```
MinecraftServer.processPacketsAndTick HEAD      (MinecraftServerMixin:31)
  └─ beginTickPhase()                            (FabricFoliaEngine:609)
       ├─ WorkerPool.closeExecutionAndAwait(1000ms, pump)   (WorkerPool:193)
       ├─ on timeout: fail-safe loop `while (busyCount > 0)` — unbounded
       └─ GATE CLOSED → workers parked, take no tasks

  ... ALL vanilla world simulation, serially, on the server thread ...
      (packet drain, ServerLevel.tick, chunk source, spawning, worldgen,
       block/fluid drain machinery, entity-list iteration and gating,
       block-entity list maintenance, weather/time/border)

  capture mixins replace the *execution* of selected bodies with a stage() call
  (entity bodies, block-entity tickers, scheduled-tick drain bodies,
   connection-tick bodies when the player path is on)

ServerTickEvents.END_SERVER_TICK                 (FabricFoliaMod:97)
  └─ flushGameplay()                             (FabricFoliaEngine:447)
       ├─ RegionStageHub.flushStaged()            (:540) group by owning region
       │    ├─ slow-motion gate: drop a backed-up region's round  (C1)
       │    ├─ headroom gate: inline if Σ−largest < 32 && Σ < 64
       │    └─ else enqueueBatch: ceil(n/32) tasks per region
       ├─ ScheduledTickDeferral.replayOnServerThread()
       └─ endTickPhase() → awaitStagedDrain(20ms) → recordTickWork()
```

Region workers therefore run **only** while the server thread is either sleeping
between ticks or parked in the 20 ms drain window. There is no window in which the
server thread simulates the world concurrently with a region worker — by design, and
it is what makes the approach safe against arbitrary vanilla code.

### 2.2 Genuine regionization, or a task pool with region labels?

Both, in different senses — and the distinction is the core product question.

**Genuine:**
- The regionizer (`WorldRegionizer`, 1,037 LOC; `Region`, `RegionSection`,
  `RegionState`) implements all four Folia invariants with merge/split, buffer radii
  derived from simulation distance, and a structure lock that is never held across a
  tick. Tests: `RegionizerInvariantTest`, `RegionizerConcurrencyTest`,
  `TickLifecycleTest`, `RegionizerChunkLifecycleTest`.
- Serialization *within* a region and parallelism *across* regions is real for staged
  bodies (run-length grouping, per-region queues, `enqueueBatch`).
- Region queue lifecycle follows merge/split/death, and region-local data
  (`RegionLocalData`, `RegionEntityRegistry`) redistributes on split.
- Ownership checks (`ThreadOwnership`, `RegionChecks`, `ViolationReporter`) are wired
  into the real execution path, not just tests.

**Not genuine Folia:**
- No independently ticking world. A region never runs vanilla's world tick; it drains a
  queue. `SCHEDULING.md:82-93` still draws the entity/block-entity/scheduled passes as
  "SERVER THREAD (not yet regionized)" — and that diagram is *more accurate about the
  world tick* than the newer docs, even though it is stale about where the bodies run.
- One global barrier per server tick. Folia has no such barrier; regions genuinely
  diverge in time, which is why Folia regions can fall behind independently. Here the
  server thread is the metronome and regions have no clock of their own in the default
  configuration.
- Chunk ownership is not attached to `ChunkHolder`/`ChunkMap` state (ARCHITECTURE:251-253
  says so explicitly and remains true). Ownership is a position→region map maintained by
  `ChunkRegionization`, not a property of the chunk system.
- Worldgen, chunk loading, spawning, lighting, saving, and the block/fluid tick
  *containers* remain single-threaded. That is arguably the correct trade for a mod, but
  it bounds speedup to the subset of work that is stageable.

**Planning verdict:** describe this as **"staged region execution"**, not "regionized
ticking". The former is accurate, defensible, and still novel on Fabric. The latter
invites direct comparison with Folia and with Folia's plugin-contract expectations.

### 2.3 What is intercepted, where, and by default

| Slice | Capture site | Runs on | Default | Evidence class |
| --- | --- | --- | --- | --- |
| Entity bodies | `Level.guardEntityTick` consumer accept (`LevelEntityTickMixin:58`) | Region worker | **ON** (`general.regionized-gameplay: true`, `ConfigSchema:190`) | IMPLEMENTED (parity doc); live validated only in handoff |
| Block entities | `Level.tickBlockEntities` → `TickingBlockEntity.tick()` (`LevelBlockEntityTickMixin:44`) | Region worker | **ON** | IMPLEMENTED |
| Scheduled block/fluid ticks | `LevelTicks.runCollectedTicks` consumer accept (`LevelTicksTickMixin:55`) | Region worker (drain machinery stays vanilla) | **ON** | TESTED (live per parity doc) |
| Player connection tick | `ServerConnectionTickMixin` → `Connection.tick()` body, `Slice.PLAYER` | Region worker | **ON** (`gameplay.stage-player-path: true`, `ConfigSchema:404`) | VALIDATED (live, per parity doc) |
| Random ticks + precipitation | `ServerChunkCache.tickChunks` → `forEachBlockTickingChunk` (`ServerChunkCacheTickMixin`) | Region worker | **OFF** (`general.regionized-random-ticks: false`, `ConfigSchema:213`) | VALIDATED, the only slice the compat harness actually measured |
| Packet re-home | `PacketProcessor` / `PacketUtils` / `Connection` mixins | Owner region or global | ON with player path | IMPLEMENTED |
| Teleports / dimension change | `Entity.teleport`, `ServerPlayer.teleport` overrides | Destination context | ON | PARTIAL (parity doc) |
| Ticket / tracker mutation funnels | `TicketStorage`, `ChunkMap`, `DistanceManager` mixins → `ServerThreadDeferral` | Deferred to server thread | ON | TESTED (regression test for the exact crash) |
| Everything else | — | Server thread | — | — |

Note the inversion that matters commercially: **the best-evidenced slice (random ticks)
is off by default, and the least-evidenced slices (entity, block entity, player) are on
by default.**

### 2.4 Feature gates and fallbacks (verified defaults)

| Gate | Default | Effect when false |
| --- | --- | --- |
| `general.enabled` | true | No engine at all; brand hook still works (regression-tested `ServerBrandTest`) |
| `general.regionized-gameplay` | true | No staging hubs; regions still form, nothing staged |
| `general.regionized-random-ticks` | false | Vanilla chunk tick pass |
| `gameplay.stage-player-path` | true | Player connection body stays server-thread |
| `patches.enabled` | true | All patch-layer optimizations fall back |
| `patches.minecraft.entity-collision-optimization` | false | Vanilla collision |
| `gpu.enabled` | false | CPU backend (GPU measured slower in all cases anyway) |
| `C2ME detected` | runtime | Random-tick slice suppressed for the session (`FabricFoliaMod:141-193`) |

Inline fallbacks are well designed: unloaded 3×3 neighborhood, backlog suppression,
no-parallel-headroom, unowned positions, engine-down, and unknown worlds all fall through
to vanilla at the original call site, preserving vanilla exception semantics.

---

## 3. Findings

Severity: **Critical** (data/gameplay loss or availability), **High** (production
blocker, identity/contract risk), **Medium** (correctness/diagnostic debt), **Low**
(hygiene). IDs are stable for tracking.

### C1 — Staged ticks are permanently discarded for backed-up regions — **Critical**

`RegionStageHub:187` (`REGION_SKIP_THRESHOLD = 3`), enforced at `:651-663`: if a region's
queue holds more than 3 tasks at flush, the entire round for that region is removed from
`byRegion` and counted in `REGION_BEHIND_SKIPPED`. It is never enqueued, never retried,
never replayed. `pendingBodies` was not yet incremented, so accounting stays consistent —
the loss is invisible except as a counter labelled *"skipped (region behind, slow-motion)"*.

Evidence of magnitude (handoff, 2026-11-09 run E, 3,987-armor-stand clump):
`staged 23,810,063 → executed on workers 1,513,900, skipped 22,142,693`. Roughly 93 % of
the hot region's entity ticks were dropped to hold 20 TPS.

Why this is Critical:
- It contradicts `AGENTS.md` rule 2 ("never suppress… no silent drops") — the counter
  makes it non-silent but the behaviour is still a drop, not a retry.
- The project already reached this conclusion once: the 2026-11-01 Phase-1 turn removed
  the gate precisely because it lost gameplay ticks, then it was restored on 2026-11-09
  to recover MS PT. The code now encodes a value trade that the docs describe as
  "Folia's overloaded-region contract". It is not: Folia's overloaded region ticks
  *late*, it does not skip ticks. Tick-skipping changes mob AI, crop growth, redstone,
  invulnerability frames, despawn timers, and every mod's tick assumptions.
- **Player entity bodies are in the ENTITY slice and are not exempt.** A player in a
  backed-up region can have their own `ServerPlayer.tick` body discarded while their
  connection body (PLAYER slice, exempt) still runs — an inconsistent player state.

**Acceptance criteria**
1. No staged body is discarded without an explicit, documented, operator-selected policy;
   default must be *defer* (queue) or *inline-fallback*, not *drop*.
2. If a drop policy ships, it is off by default, per-slice configurable, hard-capped
   (e.g. ≤ N per region per second), and never applies to `ServerPlayer` entity bodies.
3. `/folia metrics` exposes a `DISCARDED` class distinct from `DEFERRED`, with per-slice
   and per-region breakdowns.
4. A test asserts a staged body is either executed, retried, or explicitly discarded with
   the counter incremented — never silently absent.
5. Any published TPS/MSPT figure on an overloaded world states the discard rate next to it.

### C2 — Fail-safe quiesce can wedge the server indefinitely — **High**

`FabricFoliaEngine:609-671`: on a 1 s quiesce timeout the engine logs loudly, then enters
`while (pool.busyCount() > 0) { closeExecutionAndAwait(1000ms, pump); }` with no upper
bound. That is a deliberate "availability yields to correctness" decision, and it is
correct as far as it goes — but it means a single worker body blocked on something the
pump cannot satisfy (a foreign mod's `synchronized` wait, a deadlock involving a mod's
own executor, a native call) halts the entire server forever, and `StallProbe` will
record stacks but nothing recovers.

**Acceptance criteria**
1. Bounded escalation ladder: after *T* seconds, capture a full diagnosis bundle
   (worker stacks, pending bodies, per-region state, held locks where cheap), still keep
   waiting, but surface a single high-severity alert entry (not one log line per second).
2. Documented operator escape (e.g. `/folia panic` → cancel in-flight region work, convert
   remaining regions to vanilla-inline for the session, keep the server alive).
3. A test that drives `closeExecutionAndAwait` with a permanently-blocked task and asserts
   the escalation path fires (not that the server hangs).

### C3 — Third-party mod tick code is off-threaded with no opt-in — **High**

Staging replaces the execution point of *every* entity, block entity, and scheduled tick
in the world, including code from every installed mod and datapack. There is no
per-mod or per-entity-type policy; the only gate is global config. Vanilla-thread
assumptions common in the ecosystem (calling `server.execute`, touching `PlayerList`,
`level.getChunk` synchronously, static caches, `ThreadLocal` state) are not detected
outside the engine's own `RegionChecks` authority (which only knows regions the engine
created).

The C2ME case is the canary: a real, popular mod is handled by *detecting it and turning
the feature off* (`FabricFoliaMod:182-193`). That is honest, but it is also proof that the
"arbitrary mods just work" hypothesis is untested at scale. `COMPATIBILITY.md:127` says
plainly that entity ticking, block entities, scheduled ticks, portals, high entity counts,
and networking under load were **not** exercised by the harness — and those are now the
default path.

**Acceptance criteria**
1. A documented support policy: declared (`custom.fabricfolia.compatibility`), undeclared,
   and known-incompatible tiers, with a startup report naming any mod whose entity types
   are being off-threaded without a declaration.
2. A conservative default: entity/BE staging **off** unless the operator opts in (or an
   "auto" profile that enables only for worlds/mods that declare support).
3. Compatibility matrix re-run with `regionized-gameplay: true` across the same 11 combos,
   plus at least three real modpacks (tech, magic, performance) — results committed.
4. `COMPATIBILITY.md` and `MIXINS.md` updated to the 26-mixin reality.

### C4 — Player state is split across two slices and two possible regions — **High**

`ServerPlayer` participates twice per tick: its entity body is staged into `ENTITY`
(`LevelEntityTickMixin:68`) and its connection body into `PLAYER`
(`ServerConnectionTickMixin`). Both resolve their owning region independently at flush,
by current position, in different dispatch passes. A player crossing a region boundary
during the tick can therefore have the entity body and the connection body executed by
different region workers in the same server tick — with `ServerPlayerTickMixin` bouncing
only two known couplings (`ServerChunkCache.move`, `ServerPlayerGameMode.tick`).

There is no invariant test asserting "a player's ENTITY and PLAYER bodies always execute
in the same region context in the same tick". This is the kind of defect that surfaces as
rare desyncs under load, not as a crash.

**Acceptance criteria**
1. An explicit invariant: one owning region per player per tick, with the resolver
   captured once and reused by both slices; or a documented, tested carve-out that makes
   the split safe.
2. A regression test (real engine, two regions, a player straddling the boundary) that
   asserts single-context execution.
3. `THREADING.md` states the rule where the split is described.

### C5 — Documented structural gaps that remain real — **Medium**

These are already honestly recorded; they are listed because each is a product promise,
not just a TODO:
- Direct `PlayerList.respawn` callers from mods/API and destination-region placement
  (`docs/folia-parity.md`, Player respawn row).
- Login placement: `NOT_IMPLEMENTED`.
- Per-region redstone time / current-tick semantics: `NOT_IMPLEMENTED`; merge/split
  deadline reconciliation likewise.
- Chunk *state* ownership (ownership is position-level only).
- Portal-side staging beyond the teleport funnel.

**Acceptance criteria:** each row gains an owner, a target phase (see §6), and either an
implementation or an explicit "will not ship in 1.0" decision in `docs/folia-parity.md`.

### C6 — Diagnostics can mask real pending work — **Medium**

`RegionStageHub:563-572` zeroes `pendingBodies` whenever the region queues are empty and
no worker is busy. This is a leak-reconciliation heuristic; it is *probably* safe because
`busyCount` increments before a task body runs (`WorkerPool:297`), but it means a genuine
lost-pending bug would be silently normalized instead of observed. Combined with C1, the
operator cannot distinguish "work deferred", "work dropped by region death", and "work
dropped by the intake gate".

**Acceptance criteria:** replace the heuristic with exact per-batch completion accounting
(futures or a completion token per `StagedBatch`), and remove the reset. Keep a separate
`RECONCILED` counter if the heuristic must stay for safety.

### C7 — A load-bearing comment describes a synchronization contract that no longer exists — **Medium**

`RegionStageHub:445-463` documents the `staged` lists as "mutated by the merge listener
under the regionizer structure lock". The current `StagingListener` (`:959-990`) touches
none of them. The lists are plain `ArrayList`s used only on the server thread today, so
this is not a live bug — but it is exactly the kind of stale invariant that turns into a
race when the next contributor follows the comment.

**Acceptance criteria:** comment corrected to the actual ownership; or the listener's
merge re-homing reinstated with a lock discipline that is tested.

### C8 — Private-field reflection into `BlockableEventLoop.pendingRunnables` — **Medium**

`FabricFoliaEngine:497-516` resolves a private JDK-unnamed-module field and drains it
directly with a 128-task budget. Required by a real vanilla behaviour (`pollTask`'s
distance-manager short circuit — well documented at `:490-496`). Risks: silent degradation
to `pollTask`-only pumping if the field is renamed (`resolveChunkExecutorQueueField`
returns `null`), and running chunk tasks from a code path vanilla never intended.

**Acceptance criteria:** boot-time assertion that the field resolved on a supported MC
version (loud failure, not silent fallback); `/folia status` shows pump mode
(`direct` vs `pollTask-only`); TROUBLESHOOTING entry for a wedged pump.

### C9 — Hard-coded, statically tuned thresholds validated on one workload — **Medium**

`MIN_PARALLEL_BODIES = 32`, `MIN_DISPATCH_TOTAL = 64`, `BODIES_PER_TASK = 32`,
`REGION_SKIP_THRESHOLD = 3`, `BACKLOG_FLOOR = 8192`, `DRAIN_WINDOW_MILLIS = 20`
(`RegionStageHub:126-187`, `FabricFoliaEngine:705`). All are `static volatile` for tests,
none are config, all were tuned on synthetic armor-stand worlds. The handoff itself
records a case where these defaults *reversed* a regression direction (2026-10-06).

**Acceptance criteria:** either expose as documented config with sane defaults, or derive
from measured per-tick load; validate across at least three workload shapes (spread
entities, dense clump, redstone/farm churn) and publish the matrix.

### P1 — Architectural throughput ceiling — **High (informational for roadmap)**

Because workers only run outside the server's simulation window, maximum speedup is
bounded by the inter-tick window. At a healthy 20 TPS the window is `50 ms − work`; the
engine then adds up to 20 ms of drain (charged to the server's tick budget) plus a
10-15 ms quiesce at the next tick head. The 61k-spread test (TPS 10.7 → 12.5, workers
saturated, MSPT p50 ≈ 80-100 ms) is the empirical ceiling for the current design.

Implication: work on `pushEntities`, collision, or per-body cost moves *worker* CPU and
region tick rate, not server MSPT. The remaining server-side cost is vanilla iteration,
tracking, and staging overhead. Do not promise Folia-class scaling from this architecture;
promise *server-thread relief*, which is real and measurable.

**Acceptance criteria:** a published model ("what this architecture can and cannot
accelerate") with the measured ceiling, and a decision in Phase 3 on whether to pursue a
true per-region world tick.

### X1 — Compatibility evidence does not cover the shipped default — **High**

`COMPATIBILITY.md:127` lists entity ticking, block entities, scheduled ticks, redstone,
portals, explosions, player interaction, networking under load, dimension change, high
entity counts, and saves-under-load as **not exercised**. All of those except redstone
and the last are now on by default. `COMPATIBILITY.md:152` also claims "exactly one
mixin", while 26 are registered.

**Acceptance criteria:** matrix regenerated against the default profile; the mixin
inventory table replaced with the real 26-row analysis; a dated "last validated" field
per row.

### X2 — Feature suppression on mod detection is policy, not engineering — **Medium**

C2ME suppresses the random-tick slice for the whole session (`FabricFoliaMod:182-193`),
with console warnings and a doc page. Under the standing rule ("fix, don't suppress")
this is a documented exception, and the alternative (crash or silent corruption) is worse.
But the operator-facing surface is thin: `/folia status` should make a suppressed slice
unmissable, and the suppression should be re-evaluated (C2ME moves fast).

**Acceptance criteria:** suppression appears in `/folia status` and `/folia metrics` as a
first-class state with the measured reason, plus a documented re-validation procedure tied
to a C2ME version range.

### E1 — Documentation contradicts the shipped behaviour in six places — **High**

This is the single biggest contributor-experience and credibility defect, and it is cheap
to fix:

| File | Line | Claim | Reality |
| --- | --- | --- | --- |
| `README.md` | 13-21 | "first interception milestone… all other vanilla passes still run on the server thread" | Entity/BE/scheduled/player staging are implemented and default-on |
| `ARCHITECTURE.md` | 10-11 | "gameplay interception is not active" | It is active and default-on |
| `SCHEDULING.md` | 82-93, 118 | entity/BE/scheduled "not yet regionized" | Bodies are regionized (containers are not) |
| `COMPATIBILITY.md` | 127, 152 | harness excludes the staged passes; "exactly one mixin" | 26 mixins; staged passes are the default |
| `THREADING.md` | 247 | `BODIES_PER_TASK = 256` | 32 |
| `api/.../FabricFoliaApi.java` | 19 | accessors "throw IllegalStateException… not initialized" | Entrypoints are delivered (`FabricFoliaMod:259-284`) |

**Acceptance criteria:** one authoritative "current status" table (single-sourced in
`docs/folia-parity.md`), referenced by README/ARCHITECTURE rather than duplicated; a CI
docs check that asserts mixin count, config defaults, and key constants match the code.

### E2 — The handoff file is an append-only 103 KB log with superseded statements inline — **High**

`FREEBUFF.md` is 1,448 lines. It contains, in order: "slow-motion gate REMOVED (Phase 1)",
then "restored", then a 2026-11-10 entry, then 2026-11-13 entries — plus several entries
that say "NOT committed" for work that is in fact committed. `AGENTS.md` rule 1 makes
reading it mandatory every session, which means every contributor pays that cost and must
independently determine which statements are current.

**Acceptance criteria:** `FREEBUFF.md` reduced to (a) current work state, (b) open items
with owners, (c) verified vanilla facts, (d) host quirks — with dated history moved to
`docs/history/YYYY-MM.md`. Target < 300 lines for the live file.

### E3 — Two instruction files disagree about the git workflow — **Medium**

`AGENTS.md` rule 3: "Do not commit or push unless the user asks."
`FREEBUFF.md` §1: "OWNER DIRECTIVE… commit and push to main directly."
`CLAUDE.md` rule 5 softens it again ("commit/push only when asked").

**Acceptance criteria:** one rule, stated once, referenced everywhere.

### E4 — `docs/mandates/` is missing while being cited 96 times — **Medium**

`AGENTS.md:5` advertises it; `FabricFoliaMod`, `RegionStageHub`, `THREADING.md`,
`ARCHITECTURE.md` and others cite `mandate §N` / spec numbers as binding rules. A new
contributor (or a new model) cannot resolve any of them. This is broken provenance for
the project's most normative statements.

**Acceptance criteria:** either restore `docs/mandates/` (a single indexed file is enough)
or strip the references and state the rules inline where they matter.

### E5 — The public API has zero tests and no consume-side example — **Medium**

`api` has 14 public types and 0 test methods. The `fabricfolia` entrypoint contract, the
`MissingRegionPolicy` behaviour, and post-shutdown semantics are the product's extension
surface for other mods, and none of it is pinned. There are no published coordinates and
no example mod.

**Acceptance criteria:** API unit tests for each scheduler adapter's documented contract;
a 20-30 line example mod under `examples/` compiled in CI; documented coordinates or a
`mavenLocal`/JitPack recipe.

### E6 — CI cannot detect a broken mixin — **Medium**

`Jenkinsfile` runs tests and `build`. Tests bootstrap the engine without a Minecraft
server (`FabricFoliaEngine.bootstrap`), and mixins are only proven by
`./gradlew :fabric:runServer` (`AGENTS.md` §3, manual). With `defaultRequire: 1`, a single
target rename breaks every boot — and CI will stay green.

**Acceptance criteria:** a headless `runServer` smoke stage in Jenkins (fresh world, RCON
`stop`, fail on mixin error or missing `Done (`), with a bounded timeout and no leftover
processes. This is the highest-value CI improvement available.

### O1 — Operator observability stops at `/folia metrics` — **Medium**

Metrics are rich and well-labelled internally, but: no machine-readable export
(Prometheus/JSON), no per-slice discard/defer breakdown (C1), historically one counter
over-counted by ~12× (handoff open item 1, still listed), and the command surface is large
(15 subcommands) without a single "is this healthy?" summary line suitable for alerting.

**Acceptance criteria:** a `status` JSON document; a documented alerting recipe (which
numbers mean trouble); the counter-overcount open item closed or formally retracted.

### I1 — Naming and positioning overpromise — **Medium (product)**

"Fabric-Folia" plus README's "the execution model Folia pioneered, rebuilt clean-room" is
a parity claim. Users will infer Folia semantics (independent region TPS, plugin
compatibility, `/tps` per region) and Folia has strict plugin opt-in rules; this mod has
none. The clean-room, Apache-2.0 stance is genuinely well documented
(`ARCHITECTURE.md:§4` attribution, no GPL code, no patch distribution) and should stay.

**Acceptance criteria:** README opens with what the mod *does* (stages vanilla tick bodies
onto region workers, preserving vanilla decision logic) and a one-line "what this is not"
(not a Folia fork, no plugin API parity, worldgen/spawning stay single-threaded).

---

## 4. Architecture boundaries — assessment

**Verdict: the three-module boundary is real and should be defended.**

- `api` (968 LOC, 14 types) and `common` (9,270 LOC) have **zero** Minecraft/loader
  imports — verified. `fabric` (10,252 LOC) holds all 26 mixins, the engine wiring, and
  commands. Dependency direction `api ← common ← fabric` matches `settings.gradle.kts`.
- The boundary is not cosmetic: the regionizer and scheduler are testable in a plain JVM
  (133 `common` tests), which is why the concurrency core has credible coverage.

**Boundary erosion to watch:**

1. `RegionStageHub` (997 LOC) lives in `fabric` and knows regionizer, scheduler, metrics,
   residency, retries, and config — it is the de facto second engine. It is the right
   place for staging (it wraps MC bodies), but its decision logic (gates, grouping,
   counters) is not unit-testable without Minecraft types. Consider extracting a
   `StagingPolicy` into `common`.
2. `FabricFoliaEngine` (1,174 LOC) is a god object: bootstrap, lifecycle, per-world
   registries, tick-phase gate, chunk pump, metrics pairing, API delivery, diagnostics.
   The handoff already flags the service-locator complaint (`FabricFoliaMod.engine()`).
   Phase 2 should split tick-phase ownership into its own class.
3. Static global state (`RegionStageHub.ACTIVE`/`HUBS_BY_LEVEL`, `ChunkResidency`,
   `ServerThreadDeferral`, `ScheduledTickDeferral`, `PatchRegistry`) is process-global and
   must be reset by tests (`@AfterEach` in `RegionStageHubParallelGateTest:38-50`). This
   works, but it makes test isolation fragile and forbids multi-engine embedding. A
   known open item (`deactivate()` clear-vs-capture race) is a symptom.
4. Pairing: `MinecraftServerMixin:31` (gate close) and `FabricFoliaMod:306-307` (gate
   open + drain) are in different modules (`fabric` both, but different files/mechanisms:
   mixin vs Fabric event). If Fabric's `END_SERVER_TICK` ordering ever changes relative to
   `processPacketsAndTick`, the gate stays closed for a tick (server stalls) or opens early
   (concurrency corruption). **This invariant deserves an explicit assertion**, not an
   ordering assumption.
5. `docs/mandates/` absent (E4) means the "binding rules" layer of the architecture has no
   artifact — the architecture is documented by the code's comments, which is how the
   stale-comment class of defect (C7) arises.

---

## 5. Project identity and contributor experience

**Identity.** Fabric-native, Apache-2.0, clean-room, server-side-only, no custom
distribution, 26 mixins, JDK 25, MC 26.2, Loom. That is a coherent and defensible niche:
*Folia-style parallelism without forking the server or breaking Fabric's mod ecosystem.*
The differentiator against Folia is real (mod jar, no plugin rewrite); the differentiator
against Lithium/C2ME is much less clear today, because the engine's wins are
server-thread offload on entity-heavy worlds while the ceiling (§P1) limits how far that
scales.

**Strengths to preserve:**
- Exceptionally honest engineering culture: `docs/folia-parity.md` uses a fixed
  NOT_IMPLEMENTED → VALIDATED vocabulary with named tests; the handoff records failures,
  regressions, and dead theories.
- Real measurement discipline: TPS via gametime deltas, JFR attribution, phase-level MSPT
  rings, watchdog-root-cause work, process-hygiene protocol.
- Root-cause fixes with regression tests (the tracker-enumeration crash, the
  worker-chunk-residency MSPT bug, the counter race) — these are genuinely good.
- 209 tests, plain JUnit 5, no mocking framework, several concurrency hammer tests.
- Clear ownership/threading doctrine (`THREADING.md`) that matches the code.

**Costs that will hurt a second contributor:**
- Six stale top-level docs (E1) and a 103 KB mandatory log (E2): a newcomer cannot form a
  model of the system from the docs without reading 20 k LOC.
- Windows-specific handoff protocol (`AGENTS.md` §3-4: `tasklist.exe`, MSYS bash quirks,
  `/d/fabric-folia`) while CI runs Linux. There is no Linux/macOS dev guide.
- No `CONTRIBUTING.md`, issue templates, release process, or versioning policy;
  `mod_version=0.1.0`, artifact `fabric-0.1.0.jar`, no tags/releases.
- Normative rules live in unreachable mandate references (E4) and contradict each other
  (E3).
- No `runServer` in CI (E6), so the only proof that 26 mixins inject is a human boot.

---

## 6. Phased production-readiness roadmap

Exit criteria are deliberately binary and verifiable. Do not advance a phase without them.

### Phase 0 — Truth and triage (1–2 weeks) — *the only phase that is mandatory before any public release*

| Work | Owner | Exit criterion |
| --- | --- | --- |
| Decide C1: drop the intake gate (defer/inline instead), or ship it off-by-default, player-exempt, capped | Engine | Decision recorded in `THREADING.md` + `docs/folia-parity.md`; no body discarded in the default profile; test proves it |
| Docs truth pass (E1): README, ARCHITECTURE, SCHEDULING, COMPATIBILITY, THREADING, API javadoc | Docs/eng | Every claim in §3/E1 resolved; `docs/folia-parity.md` is the single status source |
| Restore or remove `docs/mandates/` (E4) | Docs | Zero dangling `mandate §N` references, or an indexed file exists |
| Split `FREEBUFF.md` (E2) | Eng | Live file < 300 lines; history under `docs/history/` |
| Resolve the git-workflow contradiction (E3) | Eng | One rule in `AGENTS.md`, `CLAUDE.md`, `FREEBUFF.md` |
| Fix C7 stale invariant, C6 pending heuristic | Engine | Comment matches code; exact completion accounting; tests green |
| Publish the "what this architecture can/cannot accelerate" model (P1) | Planner | One page, with the measured ceiling and no Folia-parity implication |

**Phase 0 gate:** CI green (209+ tests, `build` rc=0), docs check passes, headless boot
still reaches `Done (` with zero mixin errors. Ship *nothing* before this.

### Phase 1 — Safe by default (2–4 weeks)

1. **Conservative default profile.** `general.regionized-gameplay` and
   `gameplay.stage-player-path` default **off** in the shipped config; an explicit
   `profile: conservative | entity | full` selector replaces scattered booleans.
   Rationale: the default path is the least-validated path (C3, X1).
2. **Player single-context invariant** (C4) with a regression test.
3. **Quiesce escalation + `/folia panic`** (C2) with a test that exercises the escalation.
4. **Per-mod staging policy** (C3): declarations, startup report, and an
   entity-type/mod opt-out.
5. **Headless boot stage in Jenkins** (E6), plus `mixin.audit` verification.
6. **Compatibility re-run** (X1) on the real default profile, results committed.

**Phase 1 gate:** default profile boots and plays 1 h on a real modpack with zero
`ERROR`/`Exception` lines; the full matrix is re-measured at the default profile; the
player invariant test exists and passes; a wedged worker produces an alert and a recovery
path rather than a silent hang.

### Phase 2 — Representative evidence and operability (4–8 weeks)

1. **Benchmark suite** (P2, C9): three committed worlds (spread entities, dense clump,
   redstone/farm churn) with a scripted runner that records TPS via gametime deltas,
   MSPT p50/p95/p99, per-slice executed/deferred/discarded, `entityTicks`, and worker
   utilisation. Vanilla A/B on the same worlds (the handoff notes an A/B was skipped at
   61k because no engine kill-switch was found — restore a proper kill-switch).
2. **Threshold policy** (C9): config-exposed or load-derived, validated on all three worlds.
3. **Observability** (O1): `status` JSON, per-slice/per-region counters, alerting recipe;
   close the counter-overcount open item.
4. **API maturity** (E5): tests + example mod + coordinates.
5. **`FabricFoliaEngine` split** and `StagingPolicy` extraction into `common` (§4.1-4.2).

**Phase 2 gate:** a published, reproducible performance report that states the ceiling and
the discard/defer rates; operators can alert from a JSON document; thresholds justified by
the matrix, not by one world.

### Phase 3 — The architectural decision (8–16 weeks, needs a written decision record)

Choose one, explicitly:

**(a) Harden the staged model (recommended near-term).** Keep the global barrier, own the
identity "staged region execution", and push on: per-region block/fluid tick containers,
chunk-state ownership, spawn pipeline, per-region redstone time, save-time quiescence.
Deliverable: a 1.0 with a documented support matrix and no parity claims.
Cost: bounded. Risk: ceiling stays (§P1); Folia comparisons remain unfavourable on
scaling.

**(b) Build a true per-region world tick.** Per-region `ServerLevel` tick slicing, chunk
holder ownership, region-local entity lists and tick containers, removal of the global
quiesce, region clocks. Deliverable: actual Folia-class scaling.
Cost: very high (this is the bulk of Folia's patch set); risk of a permanent fork-like
maintenance burden inside a mod, exactly what the project's identity forbids.

**Phase 3 gate:** a written ADR with measured evidence for the chosen path, a staffing
estimate, and an explicit "will not ship in 1.0" list for the other.

### Phase 4 — Release engineering (2 weeks, overlaps Phase 2)

Versioning + release notes, upgrade/migration notes for config, supported-MC-version
policy, `TROUBLESHOOTING.md` refresh, support matrix published in README, a deprecation
policy for config keys, and a 1.0 go/no-go review against Phases 0-2.

---

## 7. Ranked recommendations

1. **Stop discarding ticks by default (C1).** It is the one behaviour that would be
   unacceptable to a player-facing server owner and it contradicts the project's own rule.
   Everything else is a trade-off; this is a correctness promise.
2. **Make the shipped default the validated path (Phase 1.1, C3, X1).** Right now the
   best-measured slice is off and the least-measured is on. Invert that.
3. **Fix the docs before the code grows further (E1, E2, E4).** Six stale documents and a
   103 KB append-only log are the project's biggest scaling risk — not the concurrency.
4. **Add a headless boot to CI (E6).** 26 mixins with `require = 1` and only manual
   proof of injection is the cheapest high-severity gap to close.
5. **Decide the product identity now (I1, Phase 3).** Market "staged region execution,
   Fabric-native" and publish the ceiling, or fund the true regionized tick. Do not do
   both by implication.

---

## 8. Severity roll-up

| ID | Finding | Severity | Phase |
| --- | --- | --- | --- |
| C1 | Backed-up region rounds permanently discarded; players not exempt | Critical | 0 |
| C2 | Unbounded fail-safe quiesce can wedge the server | High | 1 |
| C3 | All mods' entity/BE tick code off-threaded with no opt-in | High | 1 |
| C4 | Player entity vs. connection bodies may run in different regions | High | 1 |
| P1 | Inter-tick-window architecture ceiling | High | 2–3 |
| X1 | Compatibility matrix does not cover the default path | High | 1 |
| E1 | Six docs contradict shipped behaviour | High | 0 |
| E2 | 103 KB append-only handoff with superseded claims | High | 0 |
| C5 | Respawn/login/redstone/chunk-state gaps | Medium | 2–3 |
| C6 | `pendingBodies` heuristic can mask loss | Medium | 0 |
| C7 | Stale synchronization invariant in a load-bearing comment | Medium | 0 |
| C8 | Private-field chunk-executor drain, silent-degradation risk | Medium | 2 |
| C9 | Hard-coded thresholds tuned on one workload | Medium | 2 |
| X2 | C2ME feature suppression is policy, weakly surfaced | Medium | 1 |
| E3 | Git-workflow instruction contradiction | Medium | 0 |
| E4 | `docs/mandates/` missing, 96 dangling references | Medium | 0 |
| E5 | Public API has zero tests, no example, no coordinates | Medium | 2 |
| E6 | CI cannot detect a broken mixin | Medium | 1 |
| O1 | No machine-readable/alertable observability | Medium | 2 |
| I1 | Naming/positioning implies Folia parity | Medium | 0–3 |

---

## 9. Closing assessment

The engineering here is better than the product story. The regionizer, the single-writer
gate, the ticket/ownership funnels, the residency fix, and the retry/replay machinery are
serious work, each with a root cause, a written rationale, and a regression test. The
project has something genuinely differentiated: a Fabric-native way to take vanilla's
entity/block-entity/scheduled-tick load off the main thread without forking the server or
breaking the mod loader.

What it does not yet have is a *contract* it can keep: the default path is the unvalidated
path, the headline performance result depends on discarding ticks, the documentation
describes a different product than the one in the jar, and CI cannot see whether the mod
even boots. All four are fixable in weeks, not quarters — and none of them require
abandoning the architecture.

The strategic question to settle before 1.0 is not "can we make it faster" but "are we
selling staged execution or Folia parity". The evidence in this repository supports the
first honestly. The second requires a different engine.
