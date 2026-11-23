# QA harness (clientless foundation)

Status: **dry-run only, never executed against a server.** Nothing in this
directory has booted a Minecraft server, opened RCON, or touched a world.

This is the foundation for the runtime evidence that JUnit cannot produce. It
assembles a disposable server instance and orchestrates a repeatable run with a
manifest. It is not a player-load benchmark.

## Files

| File | Purpose |
|---|---|
| `qa_lib.sh` | shared helpers: port/process inventory, port-pair selection, SHA-256, `QA_HOME`, own-process-group stop |
| `sample_metrics.py` | dependency-free RCON sampler: `--mode sample`, `--mode exec`, `--mode stop` |
| `assemble_server.sh` | build an isolated instance under `QA_HOME`: extract the Mojang bundle, verify every entry, place loader/API/mod, write `server.properties`, allocate ports, record versions and SHA-256 |
| `run_bench.sh` | orchestrate one run: inventory, boot gate, sampling, workload, `jcmd` captures, RCON stop, trap cleanup, manifest |
| `profiles/smoke.conf` | reduced profile for the 16 GB host |
| `profiles/full.conf` | full-load profile for a 32 GB or larger benchmark host |
| `configs/baseline.yml` | baseline `fabric-folia.yml` with `diagnostics.metrics: true` |
| `workloads/idle-scheduled.txt` | the only workload shipped in this pass: clientless idle and scheduled-task diagnostic |

## What this harness can and cannot measure

**Can:** server-side engine behaviour with no players connected — MSPT rings
(p50/p95/p99/max), worker utilization, queue depth, region count and per-region
TPS derived from `ticks=` deltas, heap and GC via `jcmd`, TPS from
`time query gametime` deltas, boot and mixin-application success, clean shutdown.

**Cannot, without protocol clients:** PvP, packet dispatch latency under real
connections, mount and dismount, inventory and container transactions, teleport
and portal transfers, disconnect durability, or any player-driven load shape.
`sample_metrics.py` reports `blocked_client` for a workload that asks for a
client rather than pretending to run it. Do not describe a hostile-MSPT number
from this harness as a player-load result.

Two traps this harness removes: `pause-when-empty-seconds=0` is always written
(an empty 26.2 server otherwise pauses and the numbers measure the pause), and
instances live under `QA_HOME`, never `fabric/run`.

## Usage

```bash
export QA_HOME=~/.cache/fabric-folia-qa

# 1. plan only: resolves inputs, verifies the bundle is a 26.2 bundler, writes nothing
tools/freebuff/qa/assemble_server.sh --resolve-only

# 2. assemble (needs the built mod jar, loader jar and Fabric API jar)
tools/freebuff/qa/assemble_server.sh --run-id smoke-001 \
  --mod-jar fabric/build/libs/fabric-folia-0.1.0.jar \
  --loader  <path>/fabric-loader-0.19.3.jar \
  --fabric-api <path>/fabric-api-0.161.0+26.2.jar

# 3. plan the run (default: dry run, starts nothing, writes nothing)
tools/freebuff/qa/run_bench.sh --instance "$QA_HOME/instances/smoke-001"

# 4. execute only after manager approval of the reconciled code and artifacts
tools/freebuff/qa/run_bench.sh --instance "$QA_HOME/instances/smoke-001" --execute
```

`--execute` is the only way anything starts. There is no implicit download: an
input either comes from `--server-bundle/--loader/--fabric-api/--mod-jar` or from
an exactly pinned artifact in the Gradle/Loom cache (`minecraft_version`,
`loader_version`, `fabric_api_version` in `gradle.properties`). If it is absent
the script fails closed and names the expected path.

## Profiles

| | smoke | full |
|---|---|---|
| host RAM floor | 8 GB | 32 GB |
| heap | 2 GB | 12 GB |
| view / simulation distance | 6 / 4 | 10 / 8 |
| sample window | 180 s | 1800 s |
| protocol clients | 0 | 40 |
| `--execute` gate | none | `--allow-full-profile` **and** a host reporting ≥ 32 GB |

The FAC host reports 14 GB of usable RAM, so the full profile cannot start there
by construction. Even on a 32 GB host the full profile is currently a server-side
baseline only: `--clientless-only` is required, and the manifest records
`protocol_clients: 0` with the requested target, never a claimed client count.

## Safety invariants

- Instances live under `QA_HOME`, and `QA_HOME`, the instance and the run output
  are canonicalised with `readlink -m` and rejected if they resolve inside the
  repository. `fabric/run` is refused explicitly.
- A run directory is never reused; the run id is unique per invocation.
- Ports are chosen from `QA_PORT_BASE` (default 26800) after an `ss`/`netstat`
  inventory; if a port is taken the run refuses and records the holder. It never
  kills or takes over a foreign process.
- The server starts under `setsid` in its own process group; the script verifies
  `pid == pgid` and refuses to manage an unisolated process. Cleanup signals that
  group only, and only after `/proc/<pid>/cmdline` references the instance.
- `flock` on `QA_HOME/locks/bench.lock` prevents overlapping runs.
- `server-ip=127.0.0.1`, `broadcast-rcon-to-ops=false`, a random per-instance
  RCON password, unique ports, `pause-when-empty-seconds=0`, and a finite
  `max-tick-time` (60000 ms, overridable) so a stuck tick trips the vanilla
  watchdog instead of hanging silently.
- The instance directory is `700`; `server.properties` and `instance.env` are
  `600`. Run artifacts never contain the plaintext password: `qa/instance.env`
  and `qa/server.properties` are written redacted.
- Bundle entry paths are normalised and rejected if they escape the instance
  directory; every extracted entry is checked against the SHA-256 in the
  bundler's own `META-INF/{versions,libraries}.list`.

## Artifacts per run

`inventory-before.txt`, `inventory-after.txt`, `server-console.log`,
`boot-gate.txt`, `sample/samples.csv`, `sample/metrics.json`, `sample/raw/`,
`workload.json`, `jcmd/*.txt`, `recording.jfr`, `server-logs/`,
`crash-reports/` (presence is a failure), `qa/steps.tsv` (per-step exit codes),
`qa/instance.env` and `qa/server.properties` (redacted), `manifest.json`.

## Evidence levels (adapted, implemented independently)

Fabrolia's stand reference is used as an evidence checklist only; no Fabrolia
code is copied, ported or derived (it is GPL-3.0, this project is Apache-2.0).

| Evidence level | State here |
|---|---|
| Immutable artifact manifest with versions and checksums | implemented in `assemble_server.sh` |
| Disposable instance, loopback only, unique run directory, lock | implemented |
| Boot gate with mixin errors as failure, structured manifest per run | implemented in `run_bench.sh` |
| Spark/JFR capture and old/new frame comparison | JFR start + `jcmd` captures implemented; Spark absent; frame comparison not implemented |
| 40 protocol clients | **not implemented** |
| Verify suite: portals, saves, disconnect durability, advancements, datapacks, pistons, items | **not implemented** |
| Mixin export against the exact mod set | **not implemented** |

## Exact next step: protocol client driver

The only in-tree prior art is `compat/probe_bot.py` (593 lines), deleted in
`8a7dd96` and recoverable read-only with
`git show 1325fdd:compat/probe_bot.py`. It is a reference for the packet
handshake, not a component to depend on.

1. Add `tools/freebuff/qa/client_driver.py` implementing, without external
   libraries, the 26.2 offline-mode login sequence (`online-mode=false` is already
   set): handshake with the protocol number read from the pinned 26.2 jar, login
   start, login success, configuration acknowledgement, play join.
2. Add a keep-alive responder, position sync, and command dispatch, so one client
   can be told to `tp`, open a container, mount a boat, and disconnect on cue.
3. Read every packet id from the 26.2 sources in the verified jar rather than
   hardcoding them, and record the protocol number in the manifest.
4. Add `--clients N --scenario distributed|hot-island|items|pistons`, bound to
   loopback, with a per-client scheduler and a bounded connect rate.
5. Wire `REQUIRES_CLIENT` workloads to the driver: `sample_metrics.py` already
   reports `blocked_client`; it should call the driver when present, and only then
   may `run_bench.sh` drop `--clientless-only` and set `protocol_clients` to the
   real count.
6. Only after 1–5 does the full profile mean anything, and only on a host with at
   least 32 GB of RAM.
