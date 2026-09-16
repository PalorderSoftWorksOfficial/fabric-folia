# Fabric Folia — Mod Compatibility Guide

For server administrators and mod developers.

## The one rule

**"Not declared" does not mean "incompatible."** The vast majority of mods
never declare anything, and most of them work fine under Fabric Folia. The
startup scan reports declarations and measured facts only — it never guesses.

## What the startup scan tells you

At startup Fabric Folia scans the installed mods and reports four things:

1. **Measured** — mods whose interaction with Fabric Folia has been
   runtime-tested by the compatibility harness at specific versions
   (results in COMPATIBILITY.md). Today: Lithium, C2ME, FerriteCore,
   Krypton, VMP, ScalableLux.
2. **Supported** — the mod's own metadata declares Fabric Folia support
   (see below for the declaration format).
3. **Experimental** — the mod declares experimental/partial support. One
   WARNING is logged naming the mod; the thread-ownership diagnostics will
   identify the mod if it actually touches region-owned state unsafely.
4. **Undeclared** — everything else, covered by a single informational
   line (never a per-mod warning storm). These mods run under what we call
   **legacy compatibility handling**: they run wherever Fabric Loader runs
   them, and the thread-context diagnostics watch every regionized path
   they touch.

## How to declare support (mod authors)

In your `fabric.mod.json`:

```json
{
  "custom": {
    "fabricfolia": { "compatibility": "supported" }
  }
}
```

Values:

| Value | Meaning |
|---|---|
| `supported` | Your mod is region-safe: it never touches region-owned game state from a foreign thread (or it uses the Fabric Folia schedulers for cross-region work). |
| `experimental` | Partial: some paths are region-safe, others are known-risky. A warning is shown to admins. |
| `incompatible` | You determined your mod cannot work under regionized execution. A warning is shown; admins can remove the mod. |

Declare only what you have verified. The declaration is your mod's statement,
not Fabric Folia's endorsement.

## Legacy compatibility handling — what it means

A mod that does not declare anything still runs normally. What changes under
regionized execution:

- Code running inside vanilla's regionized paths (today: the per-chunk
  random-tick pass) executes on a **region worker thread**, not the server
  thread. If that code touches state owned by another region, the
  `threads.thread-check-mode` diagnostics name the operation, both regions,
  the thread, and the correct scheduler entry point.
- Code running on the server thread (still most of the game) is unaffected
  today — those passes are not regionized yet.
- If your mod needs to mutate world state from its own timing, use the
  public API: `RegionScheduler` (schedule by position), `EntityScheduler`
  (schedule by entity; follows migrations), `GlobalScheduler` (global
  context). These are safe by construction — see the API javadoc and
  docs/developer/.

## Per-mod pages (one per measured mod, with revalidation commands)

- [C2ME](c2me.md) — interaction root-caused (C2ME's random-owner guard vs.
  the worker tick body); Fabric Folia now auto-suppresses the random-tick
  slice with a startup warning — measured **PASS** with suppression.
- [Lithium](lithium.md) — measured **PASS**; coexists with the intercept.
- [FerriteCore](ferritecore.md) — measured **PASS**.
- [Krypton](krypton.md) — measured **PASS**.
- [VMP](vmp.md) — measured **PASS** (player-load paths not yet exercised).
- [ScalableLux](scalablelux.md) — measured **PASS**; lighting engine
  receives worker-produced light updates correctly.

## Questions this file answers, and where the rest lives

- Which combos were actually tested, at which versions → COMPATIBILITY.md
- How to reproduce a test → `python compat/validate.py --combo <name>`
- What a thread-ownership violation report means → THREADING.md and
  TROUBLESHOOTING.md
- Why a mixin exists and which targets are taken → MIXINS.md
