# Krypton — measured compatibility

Status: **PASS** (fully validated — see COMPATIBILITY.md for the matrix and
`compat/results/krypton.json` for the raw result).

## Test record

| | |
|---|---|
| Krypton version | 0.3.1 |
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

Krypton optimizes the network stack; the current Fabric Folia slice
intercepts nothing on the network path, and Krypton modifies no execution
path the intercept touches. One earlier run produced an apparent anomaly
(one death before the baseline read) that was **a harness race — the
protocol measured from a fixed assumed value** — not a Krypton interaction;
the protocol was corrected to measure relative change and Krypton re-ran
clean. Krypton-specific handling in the core: none, none needed at the
tested versions.

## Revalidation

```
python compat/validate.py --combo krypton
```

Update this page and the COMPATIBILITY.md matrix if the result changes.
