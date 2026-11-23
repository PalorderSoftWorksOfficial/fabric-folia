# QA harness implementation — server-instance and benchmark foundation

Date: 2026-11-22. Tree: `/tmp/ff-reconcile`, `HEAD=c2fbe2135945e4e0ef42fc70094d3605ef03627d`,
no unmerged paths (`git diff --name-only --diff-filter=U` empty; exit 0).

**No server was booted. No RCON session was opened. No world was created.**
Every check below is a syntax, resolution, assembly or dry-run check, except the
assembly run in §4, which only writes files under a throwaway `QA_HOME` outside
the repository.

Nothing outside `tools/freebuff/qa/` and this report was created or modified.
Production Java, `Jenkinsfile`, `TESTING.md`, `COMPATIBILITY.md`, `FREEBUFF.md`,
`docs/status.md` and `fabric/run` are untouched. No commit, no push.

## 1. What was added

| File | Lines | Purpose |
|---|---|---|
| `tools/freebuff/qa/assemble_server.sh` | 467 | isolated disposable instance: bundle extraction with per-entry SHA-256 verification, pinned-artifact resolution, isolated `server.properties`, port allocation, artifact manifest |
| `tools/freebuff/qa/run_bench.sh` | 471 | run orchestration: inventory, `setsid` process group, `Done (` + mixin gate, sampler, workload, `jcmd`/JFR, RCON stop, trap cleanup, manifest |
| `tools/freebuff/qa/profiles/smoke.conf` | 13 | reduced profile for the 16 GB FAC host |
| `tools/freebuff/qa/profiles/full.conf` | 13 | full-load profile, 32 GB+ host, 40 protocol clients |
| `tools/freebuff/qa/configs/baseline.yml` | 41 | baseline `fabric-folia.yml`, `diagnostics.metrics: true` |
| `tools/freebuff/qa/workloads/idle-scheduled.txt` | 19 | clientless idle + scheduled-task diagnostic |
| `tools/freebuff/qa/README.md` | 153 | usage, evidence levels, limitations, protocol-client next step |

No comments or docstrings in the two scripts (owner preference); all prose lives
in `README.md` and the `--help` text.

## 2. Facts verified before writing (not guessed)

| Fact | How verified |
|---|---|
| Loom `minecraft-server.jar` is a Mojang bundler: `META-INF/versions.list`, `META-INF/libraries.list`, `META-INF/versions/26.2/server-26.2.jar`, 39 library jars | `zipfile` listing of the pinned cache file |
| Bundle lists carry SHA-256 digests per entry | read of `META-INF/versions.list` / `libraries.list` |
| Fabric server entry point is `net.fabricmc.loader.impl.launch.server.FabricServerLauncher`, which reads the `fabric-server-launch.properties` resource key `launch.mainClass` and the property `fabric.gameJarPath` | `javap -p -c` on `FabricServerLauncher.class` from `fabric-loader-0.19.3.jar` |
| `fabric.side`, `fabric.gameVersion`, `fabric.gameJarPath` are real loader properties | literal scan of loader classes (`SystemProperties`, `Knot`, `ModDiscoverer`, `MinecraftGameProvider`) |
| Config keys, defaults and `CURRENT_VERSION = 2` | `ConfigSchema.java` (`opt(KEY_*, Type.class, default, …)`) |
| `diagnostics.metrics` defaults to **false** — the baseline must set it true or the sampler reads empty rings | `ConfigSchema.java:600` |
| RCON-safe command names (`/folia metrics|regions verbose|workers|scheduler|health|status`) | `FoliaCommand.java`, `docs/command-reference.md` |
| `pause-when-empty-seconds` exists in 26.2 and must be 0 | recorded in `tools/freebuff/reports/qa.md` §4 |

`docs/TESTING.md` does not exist; the file is `TESTING.md` at the repository root
and was read instead.

## 3. Design decisions worth review

1. **Assembly is extraction, not installation.** The Fabric installer would
   download jars. The harness extracts the bundler's own `versions.list` and
   `libraries.list`, verifies each SHA-256 against the digest the bundle declares,
   and builds the classpath from `libraries.txt`. A repacked or tampered bundle
   fails closed instead of silently booting different code.
2. **Launch is explicit, not a generated launcher jar.** `launch.mainClass` is
   written to `launcher/fabric-server-launch.properties` on the classpath, and
   `-Dfabric.gameJarPath` is set to the verified server jar. No jar is rewritten.
3. **The watchdog stays on.** `max-tick-time` defaults to 60000 ms and is
   overridable with `--max-tick-time`; `-1` is rejected. A stuck tick must produce
   a crash report, not a silent hang.
4. **RCON is loopback-only and quiet.** `server-ip=127.0.0.1`,
   `enable-rcon=true`, `broadcast-rcon-to-ops=false`, unique random password per
   instance, unique ports.
5. **Secrets stay in the instance.** Instance dir `700`, `server.properties` and
   `instance.env` `600`. Run artifacts get redacted copies
   (`QA_RCON_PASSWORD=<redacted>`, `rcon.password=<redacted>`); the manifest
   records `rcon_password_sha256`, never the secret.
6. **Path safety is canonical.** `readlink -m` on `QA_HOME`, instance and run
   output, then a prefix check against the canonical repository root, so a
   symlink cannot smuggle writes into the tree.
7. **Profiles are an allowlist, not a path.** Only basenames present in
   `profiles/*.conf` are accepted, so `--profile ../../etc/passwd` cannot source
   arbitrary files.
8. **The process group is verified, not assumed.** After `setsid`, the script
   reads `/proc/<pid>/stat` and refuses to continue unless `pgrp == pid`; cleanup
   signals that group only, after checking `cmdline` references the instance.

## 4. Checks run, with exact exit codes

All commands run from `/tmp/ff-reconcile`. Scripts are invoked as `bash <script>`
because `/tmp` is mounted `noexec` (verified: `tmpfs /tmp rw,nosuid,nodev,noexec`).

| # | Check | Exit |
|---|---|---|
| 1 | `bash -n assemble_server.sh` | 0 |
| 2 | `bash -n run_bench.sh` | 0 |
| 3 | `bash -n qa_lib.sh` (unchanged) | 0 |
| 4 | `python3 -m py_compile sample_metrics.py` | 0 |
| 5 | `assemble --resolve-only` with no mod jar | **1** (fail closed, see below) |
| 6 | `assemble --resolve-only --mod-jar <stand-in>` | 0 (plan JSON, 4 artifacts with SHA-256) |
| 7 | Full assembly into throwaway `QA_HOME=/tmp/ff-qa-scratch` | 0 |
| 8 | Re-assemble with an existing run id | **1** ("runs are never reused") |
| 9 | `--profile ../../etc/passwd` | **1** (allowlist: `full smoke`) |
| 10 | `--max-tick-time 0` | **1** ("must be > 0 so a stalled tick is detected") |
| 11 | `QA_HOME` symlink resolving into the repo (assemble) | **1** |
| 12 | `QA_HOME` symlink resolving into the repo (run_bench) | **1** |
| 13 | `run_bench` dry-run, no arguments | 0 (plan, "started_processes": 0) |
| 14 | `run_bench` dry-run with an instance | 0 (plan, instance resolved) |
| 15 | `run_bench --execute` with no instance | **1** |
| 16 | `run_bench --execute --profile full` without `--allow-full-profile` | **1** |
| 17 | `run_bench --execute --profile full --allow-full-profile` on this 14 GB host | **1** ("needs 32 GB RAM") |
| 18 | `run_bench --execute` with port 26800 occupied | **1**, holder recorded, listener left alive |
| 19 | `start.sh` argument plumbing via a stub "java" binary | see below |
| 20 | `--jfr` without `QA_JFR_FILE` | **2** (explicit message) |
| 21 | Shellcheck | **not run**: `shellcheck` is not installed on this host |

Check 5 output (the current repository state — the mod jar has not been built):

```
ERROR: fabric-folia jar: not supplied and no exact pinned artifact in the cache.
Pass --mod-jar PATH. No download is attempted.
Expected pinned location: /tmp/ff-reconcile/fabric/build/libs/fabric-folia-0.1.0.jar
```

Check 7 (assembly) produced, from the pinned cache:

- 40 bundle entries verified against the bundle's own digests (1 server jar +
  39 libraries). Extracted `versions/26.2/server-26.2.jar` SHA-256
  `183c0499c5f855570ee487dd38e141a53f0121f83a0b07a3bac2d8b6698823e8`, identical
  to the digest in `META-INF/versions.list`.
- Input digests recorded: bundle `cdacdfb25898de5e4b4b0e5ddcc2722f77067e46605709c2d886c000ebb63ec5`,
  loader `fabric-loader-0.19.3.jar` `73eed8c34bbad0320a2a3cba5346351e822f74f82b3f3c060574068474132958`,
  Fabric API `fabric-api-0.161.0+26.2.jar` `e5b858ceb13290c274e31cb888ff5a1a40cb91067e7ffab0b5b775aec51e216a`.
- `server.properties`: `server-ip=127.0.0.1`, `server-port=26800`,
  `enable-rcon=true`, `rcon.port=26801`, random password,
  `broadcast-rcon-to-ops=false`, `pause-when-empty-seconds=0`,
  `max-tick-time=60000`, `online-mode=false`, `level-name=qa-world`,
  `level-seed=4242424242`.
- Permissions: instance `700`, `server.properties` `600`, `instance.env` `600`.
- Manifest records toolchain, ports, seed, redacted properties, all artifact
  digests and `rcon_password_sha256`. Password never appears in the manifest.

Check 18 evidence (`qa/port-conflict.txt`), written before refusing to start:

```
port=26800
LISTEN 0  1  127.0.0.1:26800 0.0.0.0:* users:(("python3",pid=246959,fd=3))
```

The listener was verified alive after the refusal, then stopped by the test
harness itself. No blanket kill was issued at any point.

Check 19 (stub binary, no JVM, no server): 42 classpath entries, all existing on
disk; `main class net.fabricmc.loader.impl.launch.server.FabricServerLauncher`;
`-Dfabric.gameJarPath` resolves to an existing file; `QA_HEAP_OVERRIDE=3G`
produced `-Xms3G -Xmx3G`; `QA_JVM_ARGS="-XX:+UseZGC -Dprobe.flag=1"` reached the
command line; `QA_JFR=1 QA_JFR_FILE=/tmp/probe.jfr` produced
`-XX:StartFlightRecording=filename=/tmp/probe.jfr,settings=profile,dumponexit=true`.

## 5. Defects found by these dry-run checks (and fixed)

| Defect | Symptom | Fix |
|---|---|---|
| Instance-reuse guard inverted | `[ -d ] \|\| [ resolve-only ] \|\| die` died even when the directory did **not** exist, so no instance could ever be assembled | rewritten as an if-block; verified by check 7 and 8 |
| Wrong game-jar path | `instance.env` recorded `26.2/server-26.2.jar`, but the jar is extracted to `versions/26.2/…`; `start.sh` classpath and `-Dfabric.gameJarPath` pointed at a non-existent file | `QA_SERVER_JAR=versions/<rel>`; verified by check 19 |
| Extra JVM args and JFR never reached `start.sh` | `run_bench` computed them but never exported them | `QA_HEAP_OVERRIDE`, `QA_JVM_ARGS`, `QA_JFR`, `QA_JFR_FILE` exported before launch; verified by check 19 |
| Port-conflict evidence was empty | `ss \| grep ":$port$"` cannot match the process-info form | pattern changed to require whitespace after the port; verified by check 18 |

## 6. Safety review items, resolved

1. `max-tick-time=-1` removed. Default 60000 ms, `--max-tick-time` overrides, `0`
   and non-numeric values rejected.
2. Plaintext credentials: `run_bench` writes redacted `qa/instance.env` and
   `qa/server.properties`; instance dir `700`, `server.properties`/`instance.env`
   `600`; manifest carries only `rcon_password_sha256`.
3. `QA_HOME` is canonicalised with `readlink -m` and rejected inside the
   repository in **both** scripts; the same applies to instance and run paths.
4. `--clientless-only` records `protocol_clients: 0` and
   `requested_client_target: 40` separately, plus `clientless_acknowledged`.
5. Profile names are validated against the `profiles/*.conf` allowlist before
   any file is sourced.
6. Every extracted bundle path is normalised, rejected if absolute or containing
   `..`, and re-checked to resolve under the instance directory.
7. No comments or docstrings remain in the scripts (scan: `grep -nE '^\s*#'` over
   both files returns no matches apart from the shebang).
8. Extra JVM args, heap override and JFR are verified to reach `start.sh` (check 19).
9. `broadcast-rcon-to-ops=false`; `server-ip` stays `127.0.0.1`.

## 7. Not done, deliberately

- **No protocol client driver.** No login handshake, no bots, no player load. The
  workload therefore contains only console-safe commands, and `sample_metrics.py`
  reports `blocked_client` for anything needing a client.
- **No boot gate executed.** `run_bench.sh --execute` has never run; `Done (`,
  mixin-error detection, RCON stop and trap cleanup are implemented and reviewed
  but unproven against a live JVM.
- **No Spark.** JFR startup plus `jcmd GC.heap_info`/`Thread.print`/
  `GC.class_histogram` are wired; frame comparison between two jars is absent.
- **No verify suite** (portals, saves, disconnect durability, advancements,
  datapacks, pistons, items), no mixin export.
- **The full profile is unusable today** by construction: 40 clients are required
  for its evidence, none can connect, and this host reports 14 GB of RAM.
- The mod jar does not exist in this tree; assembly consequently fails closed at
  check 5 until `fabric/build/libs/fabric-folia-0.1.0.jar` is built.
- `shellcheck` unavailable; static review only.

## 8. Files a reviewer should read first

1. `tools/freebuff/qa/README.md` — scope, evidence levels, next step.
2. `tools/freebuff/qa/assemble_server.sh` — extraction, verification, properties.
3. `tools/freebuff/qa/run_bench.sh` — boot gate, process-group handling, trap.

Throwaway test artifacts (`/tmp/ff-qa-scratch`, `~/.cache/ff-qa-probe`,
`build/qa-probe`, assembled instances) were removed after the checks; no instance,
run directory or stub binary is left behind.
