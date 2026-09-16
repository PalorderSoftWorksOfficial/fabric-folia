# FerriteCore — measured compatibility

Status: **PASS** (fully validated — see COMPATIBILITY.md for the matrix and
`compat/results/ferritecore.json` for the raw result).

## Test record

| | |
|---|---|
| FerriteCore version | 9.0.0-fabric |
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

FerriteCore optimizes memory layout (blockstate/model deduplication) and
touches no execution path Fabric Folia intercepts. The measured combination
is indistinguishable from baseline in every protocol phase. No
FerriteCore-specific handling exists in the core, and none is needed at the
tested versions.

## Revalidation

```
python compat/validate.py --combo ferritecore
```

Update this page and the COMPATIBILITY.md matrix if the result changes.
