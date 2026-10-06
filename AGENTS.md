# AGENTS.md — instructions for AI coding agents (all models)

> This file is the model-agnostic entry point for this repository.
> `CLAUDE.md` wraps the same rules for Claude. The **living handoff
> state** (work state, discovered defects, verified vanilla facts, host
> quirks, open items) lives in `FREEBUFF.md` — reading and updating it
> is mandatory, see rule 1.

## 1. What this repository is

- **fabric-folia** — a Fabric mod implementing Folia-style regionized,
  multithreaded execution for vanilla Minecraft **26.2** (Mojang names
  are the runtime names; no mappings layer). Apache-2.0, Palorder
  Softworks. It must stay Fabric-native (mixins, Fabric API, Loom) —
  never a Paper/Folia fork or patch-based distribution.
- Modules: `api` (public contract) → `common` (engine core: regionizer,
  schedulers, config, metrics, threading — no Minecraft classes) →
  `fabric` (mixins, engine bootstrap, entrypoints — the only module
  touching Minecraft classes).
- One-page architecture: `FREEBUFF.md` §2 · threading contract:
  `THREADING.md` · per-mixin table: `MIXINS.md`.

## 2. Standing rules (non-negotiable)

1. **Read `FREEBUFF.md` at the start of your turn; update it at the
   end** with everything you learned or changed (facts, defects,
   decisions, host quirks, work state). A stale FREEBUFF.md is worse
   than none. It carries the owner directives and the verified MC 26.2
   facts — do not rediscover them.
2. **Fix bugs at the root — never suppress them.** No swallowed
   exceptions, no update-suppression, no silent drops. Failures must
   be retried or fixed, with the outcome visible in metrics.
3. **Work lands on `main`.** Do not commit or push unless the user
   asks; when you do, conventional-commit style (`fix(engine):`,
   `perf(...)`, `docs:`, `ci:`).
4. **Baseline before you change** (`FREEBUFF.md` §4): measure the old
   behavior first, then make the change, then re-verify with the same
   measurement.
5. **Green is the definition of done:**
   `./gradlew :common:test :fabric:test :api:test build --stacktrace`
   must exit 0 — capture the exit code explicitly; the exit status of
   a pipe/filter is not the build's status. If mixins changed, also do
   one live boot check (`Done (` with zero mixin errors) and a clean
   shutdown + process sweep afterwards.

## 3. Build / test / run

- JDK 25, Gradle via the checked-in wrapper (9.5.1).
- Full check: `./gradlew :common:test :fabric:test :api:test build --stacktrace`
  — plain JUnit 5, no Mockito; `fabric` tests boot the real engine.
- Dev server: `./gradlew :fabric:runServer` — ports **25901 (MC) /
  25902 (RCON, password `foliapass`)**, never 25565/25575. Launch and
  poll in SEPARATE commands (combining kills the server when the shell
  exits). After every boot cycle: RCON `stop` (or
  `MinecraftServer.halt(false)`), `./gradlew --stop`, re-check
  `tasklist`/`netstat`, and kill ONLY processes you started.
- **Measure TPS with `time query gametime` deltas over RCON** — never
  from warning cadence. Python `mcrcon` API via heredoc
  (`with MCRcon("127.0.0.1", "foliapass", port=25902) as m:`,
  `PYTHONIOENCODING=utf-8`); Windows Python cannot see `/tmp` — edit
  such files with sed/heredoc from bash.
- Verify non-trivial vanilla facts against the named 26.2 jar with
  `javap -c -l -p -cp <jar> <class>` (exact jar path in FREEBUFF §3).

## 4. CI

- **Jenkins is the CI** — `Jenkinsfile` runs the full test protocol
  (`:common:test :fabric:test :api:test`) with junit report publishing,
  then `build`, then archives `fabric/build/libs/*.jar`.
- GitHub Actions is NOT used (workflow removed; account-level Actions
  is disabled). README documents the self-hosted Jenkins instance.

## 5. Where things are

| Need | File |
| --- | --- |
| Living handoff (READ/WRITE every turn) | `FREEBUFF.md` (§4 test protocol, §5 host quirks, §6 work state) |
| Threading / single-writer contract | `THREADING.md` |
| Architecture, regions, scheduling | `ARCHITECTURE.md`, `REGIONS.md`, `SCHEDULING.md` |
| Mixin inventory | `MIXINS.md` |
| Testing conventions | `TESTING.md` |
| Owner mandates (binding rules) | `docs/mandates/` |
| Vanilla parity status & honest gaps | `docs/folia-parity.md` |
| Optimization-mod compatibility | `COMPATIBILITY.md` |
| Troubleshooting | `TROUBLESHOOTING.md` |

## 6. Conventions

- New code ships comment-free except *why*-comments at non-obvious
  boundaries (mandate rule 47; legacy javadoc predates it).
- Config is comment-preserving YAML (`CommentedYaml`); runtime config
  `fabric/run/config/fabric-folia.yml` is gitignored.
- Metrics for every non-obvious path: if you add behavior, make it
  observable in `/folia metrics`.
