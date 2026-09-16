# ScalableLux — measured compatibility

Status: **PASS** (fully validated — see COMPATIBILITY.md for the matrix and
`compat/results/scalablelux.json` for the raw result).

## Test record

| | |
|---|---|
| ScalableLux version | 0.3.0-alpha.0.3+26.2 |
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

ScalableLux replaces the lighting engine; the intercepted random-tick body
performs light updates as part of block changes, so this combination is a
real exercise of the lighting engine receiving updates produced by
region-worker threads. The measured combination shows the expected mutation
behavior with no lighting errors and clean diagnostics. ScalableLux-specific
handling in the core: none, none needed at the tested versions.

## Revalidation

```
python compat/validate.py --combo scalablelux
```

Update this page and the COMPATIBILITY.md matrix if the result changes.
