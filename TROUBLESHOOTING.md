# Fabric Folia — Troubleshooting

Symptom-first fixes for server administrators. Console lines are quoted the
way they appear (the severity word is part of the text, so this file works on
colorless terminals too). Everything here reflects the current build: the
regionized-random-ticks slice is the only intercepted vanilla pass so far.

The universal fallback: `WARN` means the server continues; `ERROR` means one
operation or subsystem failed; if Fabric Folia cannot start safely it
**disables itself and leaves vanilla execution untouched** — the server still
runs. Fabric Folia never half-initializes.

## "Configuration is invalid - Fabric Folia is DISABLED and vanilla execution will be used. Problem: ..."

**What happened:** `config/fabric-folia.yml` failed validation. The `Problem:`
line names the exact issue — the two most common are:

- `Config file is missing key '...'` — the file predates an option or was
  hand-trimmed. Fabric Folia normally **backfills missing keys from its
  schema automatically** (your comments are kept); this error only appears
  when a key cannot be defaulted, e.g. it is nested in a section that itself
  is missing. Fix: restore the section or delete the file to regenerate the
  full self-documenting template.
- `Configuration file is not valid UTF-8 (...). Fabric Folia config files
  must be UTF-8 (no BOM); re-save the file as UTF-8, or delete it to
  regenerate the default template.` — the file was saved in a non-UTF-8
  encoding (Windows tools default to cp1252; an em-dash becomes an invalid
  byte). Re-save as UTF-8 or delete the file.

**Can the server continue?** Yes — pure vanilla, engine disabled, no risk.

**Also check:** the `Unknown key` warning line, which lists keys Fabric Folia
kept but did not recognize (kept, flagged, never dropped — usually a typo or
a very old file). If the same key appears twice in the file, Fabric Folia
collapses it on load: the **last** value wins and the duplicate is removed on
the next save (first occurrence's position and comments are kept).

## "Failed to load configuration - Fabric Folia is DISABLED. Problem: ..."

Same outcome as above with a different cause: the file could not be opened or
parsed at all (permissions, directory replaced by a file, malformed YAML).
The `Problem:` text is the underlying parse error. Fix the file or delete it;
a fresh template is written on next boot.

## "Fabric Folia is ready (vanilla execution mode - see the reasons logged above)."

The server booted but the engine did not start. The specific reasons are
logged immediately above this line (invalid config, `general.enabled: false`,
or a bootstrap failure — each with its own entry). Gameplay is otherwise
normal: this is a clean fallback, not a crash. `/folia status` will confirm:
`Fabric Folia: DISABLED (vanilla execution).`

If the reason was a bootstrap failure (`ERROR ... Problem: <exception>`),
the exception and stack trace are in the log right after the line; report it
with that trace if it reproduces.

## "WARNING: Mod "X" has not declared Fabric Folia support."

**What happened:** the startup compatibility scan read mod metadata; mod X
does not declare a Fabric Folia compatibility status.

**Why it matters:** little by itself. It does **not** mean X is incompatible
— most mods never declare anything and work fine. Fabric Folia never guesses:
an undeclared mod is reported once, at startup, and then watched by the
thread-ownership diagnostics on every regionized path it touches.

**What to do:** nothing, unless thread-ownership violations later name mod
X's classes (see the violation section below). Mod authors can declare
support in their own `fabric.mod.json` — see `docs/compatibility/README.md`.

## "Fabric Folia Compatibility Check" / the summary block

At startup you get one detection block and one summary (Measured /
Supported / Undeclared / Experimental / Incompatible counts). Known
optimization mods (Lithium, C2ME, FerriteCore, Krypton, VMP, ScalableLux)
appear as **Measured** — their real runtime results live in
`COMPATIBILITY.md` and are summarized in `/folia compat`.

## "C2ME detected: the regionized random-tick slice will NOT run this session."

**What happened:** you run C2ME, and Fabric Folia's startup scan detected
it. A measured interaction (C2ME's thread-ownership guard on the world
random rejects Fabric Folia's worker-thread tick body) means the
regionized-random-tick slice cannot mutate the world on workers, so Fabric
Folia suppressed **only that slice** for this session.

**Why it matters:** little. Vanilla random ticks run normally on the server
thread — grass grows, crops grow, fire spreads exactly as in vanilla. All
other Fabric Folia features (regions, schedulers, diagnostics) are active.
What you lose is only the (optional) worker-thread parallelism for random
ticks.

**Can the server continue?** Yes — fully.

**What to do:** nothing. Removing C2ME re-enables the slice; see
`docs/compatibility/c2me.md` for the root-caused evidence and re-validation
commands. `/folia status` reports the slice as `SUPPRESSED this session`.

## "Fabric-Folia Thread Context Violation" (STRICT mode)

A thread-ownership report looks like:

```
Fabric-Folia Thread Context Violation

Operation: <what was attempted>
Current execution context: Region <world>:<regionId>   (or: Global context / Network thread / IO thread)
Target ownership: Region <world>:<regionId>
Current thread: <thread name>
Expected context: Region <world>:<regionId>

This access is unsafe because the target object is owned by another region.
Schedule the operation through the appropriate RegionScheduler entry point.
```

**What happened:** code on one execution context touched mutable state owned
by another region. With `threads.thread-check-mode: STRICT` (the default)
the access is reported and blocked at the boundary; with `WARN` it is
logged and allowed; with `OFF` nothing checks.

**Why it matters:** this is exactly the class of bug regionized
multithreading exists to catch — a silent cross-region write corrupts
neighboring chunks' state. If the report names a third-party mod's classes,
the mod needs to schedule through the Fabric Folia schedulers instead of
touching state directly. If it names only vanilla paths, file it with your
report against Fabric Folia.

**What to do:** read `Operation` and `Current thread` first. Developers: use
`RegionScheduler` (by position), `EntityScheduler` (by entity), or
`GlobalScheduler` — see `THREADING.md` for the ownership model and
`docs/compatibility/README.md` for what mods must do.

## "WARNING: Compatibility issue" style messages never appear

There is nothing to look up: every Fabric Folia warning names the mod, the
option, or the file involved, says whether the server continues, and points
at documentation. If you ever see a Fabric Folia message that does not tell
you what to do, that is a bug in Fabric Folia's messages — report it as such.

## Random ticks seem not to happen in pinned areas

Check, in order:

1. `/folia status` — engine must be ACTIVE. If it says the slice is
   `SUPPRESSED this session`, random ticks are running through vanilla on
   the server thread (the C2ME policy above) — gameplay is normal; only
   worker-thread parallelism for the slice is off.
2. Otherwise the engine must say the intercept is enabled and
   `/folia regions` must show pinned chunks regionized (regions > 0). A
   pin in an unloaded chunk takes a moment to generate and regionize.
3. `general.regionized-random-ticks` must be `true` in
   `config/fabric-folia.yml`; a restart is required after changing it.

## Debugging aids

Set `diagnostics.debug-logging: true` to see per-pass dispatch, dispatch
magnitudes, worker-thread execution evidence, and compatibility scan detail.
Normal operation stays quiet by default — do not leave this on permanently.

`/folia regions` gives per-region live detail (state, ticks executed, chunk
count) on demand; nothing prints per-region information continuously.

## Where the rest of the documentation lives

| Topic | File |
|---|---|
| Architecture and module map | `ARCHITECTURE.md` |
| Region model | `REGIONS.md` |
| Thread ownership and context | `THREADING.md` |
| Scheduler, workers, dispatch | `SCHEDULING.md` |
| Measured compatibility matrix | `COMPATIBILITY.md` |
| Mod declarations, legacy mods | `docs/compatibility/README.md` |
| C2ME interaction detail | `docs/compatibility/c2me.md` |
| Mixin register and policy | `MIXINS.md` |
| What is tested and how | `TESTING.md` |
| Reproducing a compatibility run | see COMPATIBILITY.md (measured matrix and protocol) |
