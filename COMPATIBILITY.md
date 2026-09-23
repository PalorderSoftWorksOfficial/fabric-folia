# Fabric Folia — Compatibility

Compatibility is **measured, never assumed**. Every status in this file comes
from a recorded run of the compatibility harness, which
assembles real production server instances (Fabric server launcher + mod
jars — not the dev environment), boots them cold, and drives a runtime
protocol: world mutation by region-worker ticks, worker-thread attribution,
dispatch cessation on unpin, diagnostics cleanliness, and graceful shutdown.
Recorded results are summarized in the per-mod pages under `docs/compatibility/`.

## Definitions (no false claims)

- **Loads successfully** — the server boots and the engine initializes. Never
  sufficient for a compatibility claim on its own.
- **Runtime tested** — the harness protocol ran to completion against a real
  server and observed the intercept's runtime behavior.
- **Fully validated (PASS)** — every protocol phase passed at the recorded
  versions on a fresh world, including world mutation by worker ticks and a
  clean shutdown.

Statuses: **PASS** (all phases green), **PARTIAL** (ran, but a phase failed),
**FAIL** (could not complete), **EXPERIMENTAL** (works with documented
caveats), **UNKNOWN** (not tested), **BLOCKED** (cannot be tested).

## Compatibility matrix

Minecraft 26.2, Fabric Loader 0.19.5, Fabric API 0.160.0+26.2, Fabric Folia
0.1.0, measured 2026-09-15 (protocol: 11 phases; `regionized-random-ticks:
true`, STRICT thread checks):

| Configuration                        | Startup | Runtime | Region Safety | Worldgen | Chunk IO | Status |
|--------------------------------------|---------|---------|---------------|----------|----------|--------|
| Fabric + Fabric Folia (baseline)     | PASS    | PASS    | PASS          | PASS     | PASS     | **PASS** |
| Fabric Folia + Lithium               | PASS    | PASS    | PASS          | PASS     | PASS     | **PASS** |
| Fabric Folia + C2ME                  | PASS    | PASS*   | PASS          | PASS     | PASS     | **PASS*** (slice auto-suppressed) |
| Fabric Folia + FerriteCore           | PASS    | PASS    | PASS          | PASS     | PASS     | **PASS** |
| Fabric Folia + Krypton               | PASS    | PASS    | PASS          | PASS     | PASS     | **PASS** |
| Fabric Folia + VMP                   | PASS    | PASS    | PASS          | PASS     | PASS     | **PASS** |
| Fabric Folia + ScalableLux           | PASS    | PASS    | PASS          | PASS     | PASS     | **PASS** |
| Fabric Folia + Lithium + C2ME        | PASS    | PASS*   | PASS          | PASS     | PASS     | **PASS*** (slice auto-suppressed) |
| Fabric Folia + full optimization stack | PASS  | PASS*   | PASS          | PASS     | PASS     | **PASS*** (slice auto-suppressed) |
| C2ME with Fabric Folia intercept OFF | PASS    | PASS    | n/a           | PASS     | PASS     | isolation run: **vanilla ticking healthy under C2ME** |
| Lithium with Fabric Folia intercept OFF | PASS | PASS     | n/a           | PASS     | PASS     | isolation run: **PASS** |

\* For C2ME-containing configurations, "PASS" means the runtime protocol
passed **with the regionized-random-tick slice automatically suppressed**
(measured startup policy; see below) — the protocol then exercises the
vanilla server-thread tick path instead of worker ticks, and asserts the
suppression warning is present. This is an honest PASS of what the
configuration actually does, not a claim that the slice runs there.

Column semantics: *Startup* = boot + engine init; *Runtime* = the mutation /
attribution / cessation protocol; *Region Safety* = zero thread-ownership
violations with STRICT checks armed; *Worldgen* / *Chunk IO* = world
generation and chunk load/save completed in every run (all three dimensions
generated, saved, and reloaded without error in each measured combo).

## Versions under test

| Mod | Version | Result |
|---|---|---|
| Lithium | mc26.2-0.25.3-fabric | PASS |
| C2ME | 0.4.2-alpha.0.52+26.2 | PASS (slice auto-suppressed — see below) |
| FerriteCore | 9.0.0-fabric | PASS |
| Krypton | 0.3.1 | PASS |
| VMP | 0.2.0+beta.7.236+26.2 | PASS |
| ScalableLux | 0.3.0-alpha.0.3+26.2 | PASS |

## The C2ME interaction (root-caused; handled by automatic suppression)

**Observed (before the fix):** with C2ME installed and the intercept ON,
region workers executed vanilla `tickChunk` (dispatch, execution counts,
attribution all identical to baseline) but the executed random ticks did not
mutate the world. The intercept-off isolation runs proved vanilla random
ticking under C2ME healthy — the defect was specific to the worker-thread
write path. Lithium+C2ME and the full stack reproduced the identical
signature — C2ME is the trigger.

**Root cause (identified from C2ME's own bytecode, not inferred):**
C2ME's `fixes-worldgen-threading-issues` module redirects `Level`'s random
initialization to `CheckedThreadLocalRandom`, which records its creating
thread (the server thread) as owner. Vanilla's `ServerLevel.tickChunk` reads
that random three times; on a region worker every read throws
`ThreadLocalRandom accessed from a different thread`. Fabric Folia's
per-task failure policy reported each failure (`Region task failed`) and
dropped the work — correctly, never silently. The guard is *correct*: the
defect was in the intercepted slice, which consumed the level's shared
random from multiple workers — a latent data race with or without C2ME.
(A harness blind spot initially hid this: the cleanliness check grepped for
`Region tick failed` but not `Region task failed`; fixed, and the earlier
"zero errors" claim retracted.)

**Resolution (this release):** automatic, warned slice suppression. When
C2ME is detected at startup and the slice is enabled, Fabric Folia
suppresses only the regionized-random-ticks intercept, prints the actionable
warning, and runs everything else normally; `/folia status` reports
`SUPPRESSED this session`. The correct real fix — a region-confined random
source on the worker path — is fully designed but deferred: **Lithium
compiles its own optimized random-tick loop directly into `ServerLevel`, so
a `@Redirect` inside `tickChunk` would not apply under Lithium** and (with
`require=1`) would hard-crash boot in exactly the combination that currently
works. That collision-safe work is recorded as the design path forward.

**Measured after the fix:** `c2me`, `lithium+c2me`, and `full` all PASS
(8/8 phases) with the slice suppressed — the pinned scenario mutates via
vanilla server-thread ticks at the baseline rate, the suppression warning is
asserted present in both log and status, diagnostics are clean, shutdown is
clean. Baseline is unchanged (PASS 11/11). Full mechanism, isolation
table, and administrator guidance: `docs/compatibility/c2me.md`.

| Run | Intercept | Mutation lands? |
|---|---|---|
| Fabric Folia only | ON | yes (worker ticks) |
| Fabric Folia + Lithium | ON | yes (worker ticks) |
| Fabric Folia + C2ME (pre-fallback) | ON | no (guard rejects worker random reads) |
| Fabric Folia + C2ME (this release) | auto-suppressed | yes (vanilla server-thread ticks) |
| Fabric Folia OFF-intercept + C2ME | OFF | yes (vanilla ticking healthy) |

## What every combo was actually exercised for (and not)

Exercised in every PASS row: cold boot, all three dimensions attached and
saved, region formation, pin-driven entity-ticking ticket area, region-worker
random-tick **world mutation** (deterministic covered-grass deaths, stable
dirt control), worker-thread attribution, dispatch cessation on unpin, zero
thread-context violations under STRICT, graceful shutdown.

**Not yet exercised by the harness** (the addendum's full 32-point list;
each becomes a harness phase as the relevant interception lands): entity
ticking, block entities, scheduled ticks, redstone, portals, explosions,
player interaction, networking under load, dimension change mechanics,
high player/entity counts, concurrent saves under load. The current slice
moves only random ticks; the harness measures what the build actually does.

## Developer declaration

A mod author declares Fabric Folia support in their own `fabric.mod.json`:

```json
{
  "custom": {
    "fabricfolia": { "compatibility": "supported" }
  }
}
```

Values: `supported`, `experimental`, `incompatible`. This declaration is what
the startup compatibility scan reports; absence of a declaration means
**not declared**, never incompatible. See docs/compatibility/README.md.

## Mixin inventory (target overlap analysis)

Fabric Folia currently ships exactly **one** mixin:

| Target | What it does | Who else hits it | Analysis |
|---|---|---|---|
| `ServerChunkCache.tickChunks(ProfilerFiller,long)` → redirect of the single `ChunkMap.forEachBlockTickingChunk(Consumer)` call site | routes the per-chunk random-tick body to region workers | C2ME: does not target this dispatch site (its measured conflict is one level below — the random-source guard; root-caused, see the C2ME section); Lithium: optimizes `tickChunk` internals but not the dispatch site — **and this is the binding constraint on the planned region-confined-random fix, which must not `@Redirect` inside `tickChunk`** | Before adding any mixin, check Lithium's and C2ME's published mixin configs for the same class; prefer event/extension points; document every target here (MIXINS.md holds the full register and the mandatory 7-question analysis) |

## Production vs compatibility environments

- **Production reference**: Fabric Loader + Fabric API + Fabric Folia. No
  optimization mod is a dependency; the engine has zero code paths that
  require one (verified: the baseline combo is the canonical PASS).
- **Compatibility environment**: the external harness used for the measured
  runs (not part of this repository). The engine contains
  no optimization-mod-specific branches (the CompatScanner only *reads
  metadata* and reports).

## Failure-isolation protocol

When a combo fails: (1) run the mod alone; (2) run it with the Fabric Folia
intercept OFF (`*-intercept-off` combos); (3) pair it with each other mod in
the failure; (4) compare signatures. This distinguishes Fabric-Folia bug /
optimization-mod bug / vanilla behavior / mixin conflict / threading
assumption. The C2ME entry above is the worked example. Never disable a
Fabric Folia feature to make a test pass before running this protocol.

## Regression testing

Every combo is re-runnable from the harness and its result is recorded. A
compatibility regression is a harness phase that
flips from PASS to FAIL at unchanged versions — re-run, bisect with the
isolation combos, and fix the cause (never mask it in the core).
