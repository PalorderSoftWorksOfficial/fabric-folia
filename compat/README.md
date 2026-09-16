# compat/ — the compatibility test environment

This directory is Fabric Folia's **compatibility environment**. It is never a
dependency of production: no engine code path reads anything here, and no
optimization-mod-specific code exists anywhere in the mod's modules. Everything
below exists only to *measure* combinations and record the results.

## What it does

`validate.py --combo <name>`:

1. **Assembles** a real production server instance under `instances/<combo>/`:
   the Fabric server launcher, Fabric API, the freshly built Fabric Folia jar
   from `fabric/build/libs/`, and the combo's optimization-mod jars resolved
   from Modrinth (cached in `cache/`).
2. **Writes** a schema-valid config: `regionized-random-ticks: true`,
   `threads.thread-check-mode: STRICT`, `diagnostics.debug-logging: true`
   (per-combo overrides apply for failure-isolation runs, e.g. the intercept
   off).
3. **Boots** the server and drives an 11-phase runtime protocol over RCON:
   boot → engine active → intercept armed → regionization → scenario built
   (verified present) → baseline readable → **worker-tick world mutation**
   (deterministic covered-grass deaths, stable dirt control) → worker
   attribution → dispatch cessation on unpin → diagnostics clean → graceful
   shutdown. Isolation combos skip the phases that are inapplicable by design.
4. **Records** a machine-readable verdict in `results/<combo>.json` and
   prints the phase list. PASS exits 0; PARTIAL/FAIL do not (CI-safe).

## Combos

`baseline`, `lithium`, `c2me`, `ferritecore`, `krypton`, `vmp`, `scalablelux`,
`lithium+c2me`, `full` (all six), and the failure-isolation runs
`c2me-intercept-off`, `lithium-intercept-off`.

```bash
python compat/validate.py --combo baseline   # any combo name; one command each
```

Current measured verdicts and versions: `COMPATIBILITY.md` (matrix) and
`docs/compatibility/` (per-mod pages). A committed result JSON is the record
of a run; re-running a combo overwrites it with fresh evidence.

## Hygiene rules

- Instances are disposable: delete `instances/<name>/` freely; the harness
  regenerates it. The harness also guards against a stale server still
  holding the instance's ports.
- Nothing in this directory is referenced by production code, tests, or the
  build. Removing `compat/` entirely must leave the mod fully functional —
  that is the definition of the canonical baseline.
