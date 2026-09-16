# VMP (Very Many Players) — measured compatibility

Status: **PASS** (fully validated — see COMPATIBILITY.md for the matrix and
`compat/results/vmp.json` for the raw result).

## Test record

| | |
|---|---|
| VMP version | 0.2.0+beta.7.236+26.2 |
| Minecraft | 26.2 |
| Fabric Folia | 0.1.0 |
| Fabric API | 0.160.0+26.2 |
| Configuration | `regionized-random-ticks: true`, STRICT thread checks, production server instance |
| Date | 2026-09-15 |
| Verdict | **PASS 11/11 protocol phases** |

## What was exercised

Full runtime protocol: cold boot, engine init, intercept armed, region
formation, verified scenario build, world mutation by region-worker random
ticks, worker-thread attribution, dispatch cessation on unpin, zero
thread-context violations, graceful shutdown.

## Interaction notes

VMP optimizes player-density paths (tracking, networking). The current
protocol runs without connected players, so VMP's *main* optimizations are
not yet under load in these runs — the PASS covers boot, world, chunk,
tick, and shutdown behavior with VMP installed, not high-player-count
behavior. The harness will gain a connected-player phase as the networking
slice lands; until then treat the VMP entry as validated for everything the
harness actually measures (and see COMPATIBILITY.md's "not yet exercised"
list).

## Revalidation

```
python compat/validate.py --combo vmp
```

Update this page and the COMPATIBILITY.md matrix if the result changes.
