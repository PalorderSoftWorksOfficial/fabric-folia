# C2ME — measured compatibility

Status: **PASS with automatic slice suppression** (measured, not assumed —
see COMPATIBILITY.md for the matrix and `compat/results/c2me.json` for the
raw result).

## The interaction, root-caused

With C2ME installed and the regionized random-tick slice enabled, region
workers executed the vanilla `tickChunk` body but **no world mutation ever
landed** — reproduced across the `c2me`, `lithium+c2me`, and `full` combos,
while the intercept-off isolation runs proved vanilla random ticking under
C2ME is healthy. The mechanism is now fully identified:

1. C2ME's `fixes-worldgen-threading-issues` module redirects the `random`
   field initialization in `Level`'s constructor to
   `CheckedThreadLocalRandom` (`threading_detections.random_instances.MixinWorld`).
2. `CheckedThreadLocalRandom` records its **creating thread** as owner — on
   a dedicated server, the server thread.
3. Vanilla's `ServerLevel.tickChunk` reads `Level.random` three times. On
   Fabric Folia workers, every read hits the guard and (per C2ME's
   `fixes.enforceSafeWorldRandomAccess` policy) throws
   `ConcurrentModificationException: ThreadLocalRandom accessed from a
   different thread (owner: Server thread, current: FabricFolia-Worker-N)`.
4. Fabric Folia's per-task failure policy reported each failure
   (`Region task failed in Region[...]`) and dropped the work — correctly,
   never silently. **The "zero errors" in the earlier harness runs was a
   measurement blind spot**: the harness's cleanliness check grepped for
   `Region tick failed` but not `Region task failed`. That gap is fixed;
   the harness now treats task failures as diagnostics failures.

The guard is *correct*: a random instance owned by one thread genuinely
must not be consumed from another. The defect was in the intercepted slice's
design — it consumed the level's shared random from worker threads, which
is also a latent data race (two workers can concurrently mutate the shared
`RandomSource`'s internal state) **with or without C2ME**.

## Why the fix is not in this release

The correct fix is a region-confined random source: workers draw from a
per-region (or thread-local) `RandomSource` instead of the level's shared
field. The injection points were fully surveyed (the `random` field reads
inside `tickChunk`, `Level.getRandom()`, and `getBlockRandomPos`) — but
**Lithium compiles its own optimized random-tick loop directly into
`ServerLevel`** (`alloc.chunk_random` + `world.chunk_ticking.random_block_ticking`
mixins), so any `@Redirect` inside `tickChunk` silently does not apply under
Lithium, and with `require=1` that is a hard boot crash in exactly the
combination that currently works. A redirect-based fix needs a
collision-safe strategy (Lithium-aware targeting or an overwrite-free
approach) and is recorded as the design path forward, not attempted
halfway here.

## What Fabric Folia does instead (the sanctioned fallback)

At startup, when C2ME is detected **and** the slice is enabled by config,
Fabric Folia suppresses only the regionized-random-ticks intercept and
prints an actionable warning (what happened, why it matters, what to do,
where it is documented). Everything else — regions, schedulers, worker
pool, diagnostics, commands — runs normally, and vanilla random ticks run
on the server thread exactly as without Fabric Folia. `/folia status`
reports `Regionized random ticks: SUPPRESSED this session`.

Measured result after the fallback: the `c2me` combo moved **PARTIAL →
PASS (8/8)** — the pinned scenario mutates normally via vanilla ticks
(grass 25→18, same rate as baseline), with the suppression warning
verified present in both the status output and the startup log, zero
diagnostics failures, and a clean shutdown. `lithium+c2me` and `full`
reproduce the same PASS. The baseline combo is unchanged (PASS 11/11,
worker mutation and attribution intact).

## Test record

| | |
|---|---|
| C2ME version | 0.4.2-alpha.0.52+26.2 (Modrinth devbuild) |
| Minecraft | 26.2 |
| Fabric Folia | 0.1.0 |
| Fabric API | 0.160.0+26.2 |
| Configuration | `regionized-random-ticks: true`, STRICT thread checks, production server instance |
| Date | 2026-09-15 (root cause + fallback measured same day) |

## Recommendations for administrators

- **Nothing to do.** With C2ME installed, Fabric Folia suppresses the
  random-tick slice automatically and tells you at startup. C2ME's chunk
  parallelism and every other Fabric Folia feature work.
- **Want the slice?** Remove C2ME (all other measured optimization mods
  are green with the slice ON) — or watch this document: when the
  region-confined random lands, the suppression is removed and re-measured.
- Do **not** set C2ME's `fixes.enforceSafeWorldRandomAccess` to false to
  "get the slice back": that disables C2ME's safety guard, not the
  interaction, and would trade a clean fallback for silent cross-thread
  random-state races.

## Revalidation

When a new C2ME build ships, or after the region-confined random fix lands:

```
python compat/validate.py --combo c2me
python compat/validate.py --combo lithium+c2me
python compat/validate.py --combo full
```

Update this page and the COMPATIBILITY.md matrix with the new result.
The harness combos are the permanent regression tests for this entry.
