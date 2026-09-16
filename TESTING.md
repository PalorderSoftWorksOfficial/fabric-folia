# Fabric-Folia — Testing

What is tested, how to run it, and what is deliberately *not* tested yet.
Tests are load-bearing: every invariant the docs claim is enforced by a test
that would fail if the claim became false.

## Running

```bash
./gradlew test              # full suite (all tests live in common/)
./gradlew :common:test      # equivalent today
./gradlew build             # compile + tests + jar packaging
```

The whole suite runs in a plain JVM — **no Minecraft classes on the
classpath** (see ARCHITECTURE.md §1 for why that is a feature, not a gap).

## Current suite (76 tests, 13 classes)

### Server GUI icon (`gui/`, fabric module)

- `ServerGuiIconTest` — `logo.png` packaged at the jar root and decodable;
  `loadFrameIcon` follows the environment (null headless, real image on a
  desktop JVM); **`applyTo` on a real `JFrame`** sets the icon and fully
  decodes it (the exact code the server window runs); null-frame safety.
  Plus the live protocol: `compat/gui_icon_check.py` (or
  `./gradlew :fabric:verifyServerGui`) boots the assembled baseline
  production server **without `nogui`** and asserts the "Server GUI icon set
  from logo.png (512x512)" line, zero GUI-scope errors, and clean shutdown.

### Per-thread random state (`thread/`, fabric module)

- `WorkerRandomsTest` — the `LegacyRandomSource` cross-thread defect
  (mandate §31/§37): inert-by-default passthrough (bit-identical server
  streams even inside a REGION context); region threads draw from their own
  persistent source and never advance the original (stream-position
  assertions, not value comparisons — both sources are the same algorithm);
  non-region threads keep the original while active; deactivation restores
  passthrough on the same thread mid-stream; concurrent region threads are
  isolated; and the live defect itself reproduced at unit scale — three
  region threads + a non-region thread hammering a real ThreadingDetector-
  guarded `LegacyRandomSource` through the wrapper with zero throws.

### Config engine (`config/`)

- `CommentedYamlTest` — the load-bearing property: **admin comments survive
  read-modify-write round trips**; unknown keys preserved; typed scalar
  handling. Uses the low-level Node API (parse-comments → mutate →
  dump-comments), not the comment-stripping convenience binding.
- `FoliaConfigTest` — defaults, validation failures (fail-closed on bad
  values), `config-version` migration contract, unknown-version refusal.

### Regionizer (`region/`)

- `RegionizerInvariantTest` — the four invariants directly: single
  ownership, buffer/merge-radius preservation, no-growth-while-ticking,
  four-state machine. Includes merge-under-tick deferral semantics.
- `TickLifecycleTest` — `tryMarkTicking` only from READY (the single-owner
  latch), tick counter = ticks *started*, tick-end protocol order (pending
  merges → own-merge check → dead-section purge → split), TRANSIENT refusal,
  dead-section-driven split geometry.
- `RegionizerConcurrencyTest` — a multi-threaded storm over
  add/remove/tryBeginTick/completeTick while an observer verifies no chunk
  position is ever double-owned and no region wedges (liveness under
  contention; unmatched removes degrade to no-ops).

### Scheduler (`scheduler/`)

- `RegionEntityRegistryTest` — entity migration as a first-class system
  (mandate §15): unique ownership, atomic stripe-locked migrate, per-region
  entity sets enforced under owner context, retire on region death,
  split retargets entities by home chunk (and retires the homeless), the
  entity scheduler FOLLOWING a migration, and a concurrency storm proving
  no entity is lost, duplicated, or double-owned.
- `AsyncSchedulerImplTest` — the distinct async scheduler (mandate §12):
  dedicated pool threads with IO context tagging, wall-clock delays,
  fixed-rate never overlaps itself, cancellation, exception isolation (one
  malformed task does not kill the pool), and bounded-queue backpressure
  (saturation rejects; timer-path rejections reach diagnostics).
- `RegionSchedulerTest`:
  - tasks execute inside the owning region's context (`ThreadOwnership`
    verifies the entered region);
  - **independence**: a region burning 6× its tick budget does not delay an
    unrelated region — the fast region completes multiple ticks while the
    slow one grinds (spec 6's scenario, tested not asserted);
  - task queue semantics: drain-then-run in context, DROP on death (never
    executed elsewhere), death-race pull-back (`removeTask`), wholesale
    re-home on merge;
  - **merge survival (regression)**: a task queued before its region is
    absorbed executes in the merged survivor's context — nothing scheduled
    against a territory is lost by a merge;
  - **split redistribution (mandate §8)**: tasks queued into a region that
    then splits are partitioned by their target chunk — the right-cluster
    task re-homes to the new child and executes in the child's context, the
    left-cluster task stays with the parent (both driven through the real
    regionizer tick-end protocol; child ownership asserted non-null so the
    split is real, not a purged-section artifact);
  - **create-if-absent scheduling**: scheduling to an unowned chunk position
    creates the region and executes the task in its context (Folia's
    `RegionizedTaskQueue` contract, mandate §13);
  - delayed tasks: re-queued until the region's tick counter reaches the
    deadline, then executed in context;
  - entity scheduler: follows migration (runs in the *new* owner's context),
    runs `retired` when the entity is gone (at schedule time and at
    execution time), never runs the task for a dead entity;
  - failure policy: a throwing tick body is reported with full region
    context, never silent; the worker survives and keeps serving.

### Thread context (`thread/`)

- `ThreadContextTest` — enter/exit token discipline, nested-region-context
  is impossible by construction, STRICT throws with the full actionable
  report (operation / current context / target ownership / thread / expected
  context / correct entry point), WARN logs and continues, OFF is a no-op,
  `mayAccessRegion`/`assertRegionAccess` semantics.

### Region-local data (`scheduler/`)

- `RegionLocalDataTest` — the `RegionizedData` equivalent (mandate §7/§26):
  access outside the owning region's context is a `ThreadContextViolationException`
  (never a silent hand-out); lazy per-region creation under the owner's
  context; **merge reconciles the donor's data into the survivor** through
  the real deferred-merge path; **split redistributes per-child data**
  through the real tick-end split (halo margins and the dead-section gate
  documented on the test); **destroy releases** when the region empties and
  dies (the emptied-region kill, not a zombie).

## Dev-server smoke test (live interception evidence)

```bash
./gradlew :fabric:runServer
# then, over RCON or console:
/folia pin 0 0     # pin an entity-ticking ticket area (admin primitive)
/folia regions     # watch live region state
/folia unpin 0 0
stop
```

Verified in this milestone (with `regionized-random-ticks: true`): the mixin
applies cleanly on a real 26.2 dedicated server, all three dimensions attach,
and after pinning, the server log shows per-tick dispatch lines plus the
thread evidence the slice exists to prove:

```
[Fabric-Folia] pass: dispatched 9 chunk-tick job(s) across minecraft:overworld regions
[EVIDENCE] region minecraft:overworld:1 ticked on worker thread 'Fabric-Folia-Worker-2'
```

Across a 900+-pass soak: zero dropped tasks, zero region-task failures, zero
thread-context violations, clean shutdown (`Engine shut down cleanly.`). With
the flag OFF (default), the same server runs pure vanilla — the redirect
forwards unchanged.

The definitive end-to-end proof — that those worker ticks **mutate world
state** and that interception **ceases** on unpin — is the next section.

## Multi-region live proof and the per-thread random-state fix

The compat protocol (`compat/validate.py`) pins TWO regions 3000 chunks apart
(chunks 12000,0 and 15000,0), asserts both form and both mutate on worker
threads over one shared 75s window, and greps the full log for the
ThreadingDetector signature as part of `diagnostics-clean` — a
`Accessing LegacyRandomSource from multiple threads` line fails the run.
This is the configuration under which the shared-`Level.random` defect first
surfaced; the run is therefore both the regression gate and the evidence.

Last baseline run after the `WorkerRandoms` fix:
**PASS 13/13** (`compat/results/baseline.json`) — multi-region PASS, both
regions' grass dying under worker attribution (8 distinct workers),
**0 offending log lines**, graceful shutdown.

## Entity-ownership live proof (mandate §15 hooks on a real server)

`python compat/entity_live_check.py` boots the dev server and drives the full
entity lifecycle over RCON. Last run (fresh world, recorded in
`compat/results/entity-live.json`): **PASS 10/10** —

- add funnel: 5 tagged summons → tracked rises by exactly 5 (with
  `doMobSpawning false` the delta is exact; every vanilla add path funnels
  through `PersistentEntitySectionManager.addEntity`, which is the hooked
  site);
- canary + teleport: a canary summoned at the destination proves the chunk is
  loaded, then a cow teleported 4000 blocks across the region boundary shows
  `migrations 0 → 1` — the registry's atomic migrate on a real crossing;
- removal: killing everything (cows, their item drops, xp orbs, naturals)
  returns tracked to the exact boot baseline — `unregister is not retire`
  (retired stays flat);
- clean engine shutdown.

Two real defects this live protocol caught that fake-based unit tests could
not, both fixed:

1. **The removal hook silently ignored every removal.** It gated on
   `!entity.isRemoved()`, but fires at `setRemoved` TAIL — where the entity
   is by definition already removed. The registry only ever grew (tracked
   diverged from vanilla's own `@e` count after a kill and never returned).
   Discriminating experiment: spawn 5, kill 5 → tracked 11 vs vanilla 5.
2. **The movement hook missed teleports.** It hooked `setPos(DDD)`, but the
   teleport path (`Entity.teleportTo` → `teleport(TeleportTransition)` →
   `teleportSetPosition`) calls `setPosRaw(DDD)` directly — verified in the
   26.2 bytecode. The hook now sits on `setPosRaw`, the innermost primitive.

Protocol design notes for re-runs: vanilla 26.2 dedicated servers keep only
spawn chunks loaded without a connected player — chunks beyond it stay
marked-but-never-loaded (verified against vanilla `forceload`: chunks are
marked, `setblock` says "not loaded"), so probe groups live at x=0 and
x=4000, both inside the spawn-chunk area; a canary summon at the destination
must register before the teleport, else the entity parks as
UNLOADED_TO_CHUNK. The empty-world control (tracked=0=vanilla `@e` at rest)
guards against counting drift.

Known-separate issue (recorded, not fixed here): with TWO regions ticking
random ticks concurrently, the level's shared `LegacyRandomSource` throws
`ReportedException: Accessing LegacyRandomSource from multiple threads`
(worker-task failure, isolated to that task — regions keep ticking). Single-
region operation is unaffected; this is the next engine task (region-local
world random), pre-existing since the intercept slice, exposed by the second
pinned region.

## World-mutation proof (region-worker ticks mutate the world)

The dispatch/worker evidence above proves *where* ticks run; this scenario
proves they *change the world*, through the real surface (RCON + /folia on a
live 26.2 dedicated server, fresh world):

**Scenario.** With `regionized-random-ticks: true` and
`pause-when-empty-seconds=0`: a 5×5 `grass_block` layer at y=-58, covered by
stone at y=-57 — covered grass dies on the next random tick it receives
(100%-deterministic conversion; it never grows back under cover) — plus a
5×5 `dirt` control layer 20 blocks away at the same height. Both are built
far from spawn (chunk 12000,0) so spawn chunks cannot interfere.

**Protocol and results (run 9):**

1. `/folia pin 12000 0` → the pinned chunks regionize (`folia regions`: 1
   region).
2. Baseline via RCON block-query scoreboard readback: `grass_A=25`,
   `dirt_B=25`.
3. 90 s pinned: `grass_A 25 → 18` (7 deaths — a covered-grass death can only
   happen inside vanilla's random-tick pass, which is exactly what the
   intercept moved to workers); `dirt_B=25` throughout (control: no false
   conversions).
4. `/folia unpin 12000 0` → the **last interception dispatch logs at the
   same second as the unpin**, and the following 65 seconds contain **zero**
   `pass: dispatched` lines.
5. Residue: exactly **one** further death (18→17 on the next loaded read) —
   the final pre-unpin pass's in-flight job executing on a worker after the
   ticket removal. This is the documented dispatch-cessation semantics,
   bounded at one pass's worth of work.
6. Attribution: `WORKER-EVIDENCE` lines name the executing thread — 7
   distinct `Fabric-Folia-Worker-N` threads executed the vanilla tick body
   this run. **Zero** thread-context violations, zero dropped tasks, zero
   region-task failures.
7. `stop` → all chunks saved, ports released, `BUILD SUCCESSFUL`.

A measurement pitfall worth recording: block queries on **unloaded** chunks
return failure (not "false"), which silently under-counts — every count in
the protocol above is taken with the area re-pinned (loaded-read control:
unforced read reported 0, re-pinned read reported the true 17).

## Compatibility validation (production instances, measured)

`compat/validate.py` assembles real production server instances (Fabric
launcher + mod jars from Modrinth), boots them cold, and drives the same
protocol the dev-server proof uses: scenario build (verified present),
baseline read, worker-tick mutation, worker attribution, dispatch cessation,
diagnostics cleanliness, graceful shutdown — 11 phases. Results live in
`compat/results/*.json`; the matrix and per-mod pages live in
`COMPATIBILITY.md` and `docs/compatibility/`.

All 11 combos (baseline, 6 single mods, lithium+c2me, full stack, 2
intercept-off isolation runs) were re-measured 2026-09-15 with
`threads.thread-check-mode: STRICT` after a harness defect (mis-sectioned
config keys silently downgraded the mode to WARN) was found and fixed —
the harness template now emits schema-valid configs. Verdicts: baseline and
all non-C2ME combos PASS (11/11); the three C2ME-containing combos PASS
(8/8) **with the random-tick slice auto-suppressed** — the harness asserts
the suppression warning in both log and status and exercises the vanilla
server-thread tick path instead. The combo definitions are permanent
regression tests: re-run any of them with
`python compat/validate.py --combo <name>`.

The harness caught real engine defects that are now regression-tested in
`FoliaConfigTest`: the non-UTF-8 config error message (encoding
exceptions surfaced as a generic parse error) and partial-config
self-repair (validation ran before default backfill). It also root-caused
the C2ME interaction — C2ME's `CheckedThreadLocalRandom` guard rejects the
worker-thread tick body's reads of the level's shared random; a second
harness blind spot (its cleanliness check missed `Region task failed`
lines) initially hid the 1,608 per-task failure reports and is fixed. The
full mechanism and the deferred region-confined-random fix (blocked by
Lithium compiling into `tickChunk`): docs/compatibility/c2me.md.

## Defects this playtest caught and fixed

- **`/folia unpin` was a silent no-op.** Pin created tickets at a literal
  level (30) but unpin removed at the radius helpers' derived level
  (`ChunkLevel.byStatus(FULL) − radius` = 29) — nothing matched, and pinned
  areas stayed ticked forever. Found by the cessation protocol (dispatches
  continuing after unpin); fixed by using vanilla's own symmetric
  `addTicketWithRadius`/`removeTicketWithRadius` pair for both. The
  cessation protocol above is the regression test for this.
- **Earlier "post-unpin death" observations were measurement artifacts** —
  the first unit sat inside spawn-chunk territory (vanilla kept ticking it
  independently of tickets), and unloaded-chunk reads under-counted. Both
  eliminated by the far-unit + loaded-read design; run 9 is the corrected
  protocol.

## What is deliberately NOT tested yet (spec 17 honesty)

- **No automated Mixin tests** — the Mixins' behavior is verified by the
  live-server evidence above (mixin apply, vanilla fall-through when
  disabled, worker-thread execution when enabled, and the entity-lifecycle
  protocol in `compat/entity_live_check.py`), by the GUI-icon protocol in
  `compat/gui_icon_check.py` (which caught a real invalid-descriptor mixin
  apply failure that compile+package checks cannot see), and by unit tests
  of the machinery they call (the entity registry protocol is storm-tested
  in `common`), not yet by automated gametests; automating the gametest
  harness is integration-phase work.
- **No vanilla-behavior parity tests** — the remaining pipeline partitioning
  (scheduled ticks, block entities, entities) has not begun; parity tests
  become the acceptance tests of that work.
- **No benchmark suite** — spec 20 arrives after stress testing (spec 19);
  the strict-mode overhead measurement that decides the production default
  in THREADING.md lives there.
- **Concurrency tests are probabilistic by nature** — the storm tests bound
  contention; they cannot prove the absence of every race. They are sized to
  catch realistic interleavings, and they have already caught real bugs
  (merge-deferral direction, split-gate ordering) during this milestone.

## Adding tests

New engine behavior lands in `common` with its tests in the same change —
the spec's rule is tests after each phase, not deferred to the end. Test
config knobs live on `RegionizerConfig.forTests()`; new knobs need the same
treatment (deterministic, small, documented) before use.
