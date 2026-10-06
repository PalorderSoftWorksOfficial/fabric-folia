# CLAUDE.md — instructions for Claude

Read this file first, then **`FREEBUFF.md`** — the mandatory living
handoff (read it at turn start, append what you learned and changed at
turn end) — then `AGENTS.md` for the full model-agnostic rules and doc
index.

## The rules that matter most

1. **FREEBUFF.md is the handoff.** §4 = testing protocol, §5 = host
   quirks (they will bite you), §6 = work state and open items, §3 =
   verified MC 26.2 bytecode facts. Never rediscover recorded facts;
   never let the file go stale.
2. **Fix at the root; never suppress.** No swallowed exceptions, no
   silent drops, no update-suppression. Failures get retried or fixed,
   and the outcome is visible in `/folia metrics`.
3. **Definition of done:**
   `./gradlew :common:test :fabric:test :api:test build --stacktrace`
   exits **0** (capture the real exit code, not a pipe's). Mixins
   changed → one live boot (`Done (` with zero mixin errors) + clean
   RCON `stop` + `./gradlew --stop` + process sweep.
4. **Measure before and after** with the same instrument: TPS via
   `time query gametime` deltas over RCON, counters via
   `/folia metrics` (Python `mcrcon` heredoc, `PYTHONIOENCODING=utf-8`).
5. **Land on `main`, commit/push only when asked**, conventional
   commits (`fix(engine):`, `perf(...)`, `docs:`, `ci:`).

## Quick reference

- Dev server: `./gradlew :fabric:runServer` — ports **25901 / 25902**
  (RCON password `foliapass`); launch and poll in separate commands;
  stop with RCON `stop`; kill only what you started.
- **Jenkins is the CI** (`Jenkinsfile`); GitHub Actions is not used.
- Threading contract: `THREADING.md` (tick-phase single-writer gate,
  chunk fast path, drain window, region slow-motion intake gate).
- Vanilla behavior checks: `javap -c -l -p -cp <named-26.2-jar>
  <class>` — jar path recorded in FREEBUFF §3.
- Key measurement pitfall: run-to-run TPS comparisons are only valid
  at equal entity activation — record `entityTicks=` with every
  measurement.
