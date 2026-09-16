# Lithium — measured compatibility

Status: **PASS** (fully validated — see COMPATIBILITY.md for the matrix and
`compat/results/lithium.json` for the raw result).

## Test record

| | |
|---|---|
| Lithium version | mc26.2-0.25.3-fabric |
| Minecraft | 26.2 |
| Fabric Folia | 0.1.0 |
| Fabric API | 0.160.0+26.2 |
| Configuration | `regionized-random-ticks: true`, STRICT thread checks, production server instance |
| Date | 2026-09-15 |
| Verdict | **PASS 11/11 protocol phases** |

## What was exercised

Full runtime protocol: cold boot, engine init, intercept armed, region
formation, deterministic scenario build (verified present before measuring),
world **mutation by region-worker random ticks** (covered-grass deaths,
stable dirt control), worker-thread attribution, dispatch cessation on
unpin, zero thread-context violations, graceful shutdown.

## Interaction notes

Lithium optimizes logic *inside* vanilla methods; Fabric Folia changes
*where* the per-chunk random-tick body executes (one dispatch-site redirect
— see MIXINS.md for the overlap analysis). The measured combination shows
both systems working simultaneously: Lithium's optimizations apply and the
intercepted ticks mutate the world on region workers exactly as in the
baseline. No Lithium-specific handling exists in the Fabric Folia core, and
none is needed at the tested versions.

## Revalidation

```
python compat/validate.py --combo lithium
```

Update this page and the COMPATIBILITY.md matrix if the result changes.
