Manager handoff: runtime evidence tooling and one dead build task.

1. QA-101 (HIGH, approved): `fabric/build.gradle.kts` registers `verifyServerGui`, which executes `compat/gui_icon_check.py`; `compat/` was deleted in `8a7dd96`, so the task is dead. Restore a real check rather than deleting the gate: add `tools/freebuff/qa/gui_icon_check.py` (read-only, asserts the icon-apply log line and a non-empty icon source as the old check did) and repoint the Gradle task at it. Touch only `fabric/build.gradle.kts` and `tools/freebuff/qa/**`. If restoring the check is not feasible, remove the dead task and record exactly why in your report; do not leave a task that cannot run.

2. Build the missing harness pieces from your report §7, in this order:
   - `tools/freebuff/qa/assemble_server.sh`: extract the pinned 26.2 bundler into a disposable instance under `QA_HOME` (never `fabric/run/`), install Fabric Loader and Fabric API pinned to `gradle.properties`, install the built mod jar, write an isolated `server.properties` with `pause-when-empty-seconds=0`, RCON enabled and a fixed seed, and record every artifact checksum.
   - `tools/freebuff/qa/run_bench.sh`: fixed JVM flags/heap/affinity, `-XX:StartFlightRecording` to an artifact directory, wait for `Done (`, fail on any mixin error line, RCON stop, trap-based cleanup that signals only its own PID/process group, and a manifest of run parameters.
   - Pinned workload files for your three modes, including the `REQUIRES_CLIENT` cases so a clientless run reports `blocked_client` instead of faking coverage.

Rules: no server boot and no Gradle full build in this shared checkout while other agents are active; you may run `bash -n`, `python3 -m py_compile` and `--help` style checks. No production Java, no Jenkinsfile, no docs outside `tools/freebuff/**`. Never touch `fabric/run/`, never use a production world, never kill processes you did not start.

3. Update tools/freebuff/reports/qa.md (append a short "follow-up" section) with: the exact commands a manager must run to produce a benchmark result, the parameters that must be recorded for the result to count, and your test designs for drain-before-save and `abandonedTaskCount` visibility (designs only — those need production changes that I will route separately).

Do not commit, push or stash. Report the files you changed and every check you actually ran, with exit codes. Then stop and wait for the next handoff.
