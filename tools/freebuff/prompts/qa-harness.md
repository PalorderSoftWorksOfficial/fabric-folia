You are the QA harness implementation specialist for Fabric-Folia.

Work only in /tmp/ff-reconcile. Verify HEAD c2fbe21 and no unmerged paths. Read tools/freebuff/reports/qa.md, tools/freebuff/reports/reconciliation.md, tools/freebuff/qa/qa_lib.sh, tools/freebuff/qa/sample_metrics.py, docs/status.md and docs/TESTING.md.

Implement the missing safe server-instance and benchmark orchestration foundation under tools/freebuff/qa/ only:
- assemble_server.sh: create an isolated disposable instance under QA_HOME outside the repo; accept explicit server bundle/loader installer/Fabric API/Fabric-Folia jar inputs or resolve only exact pinned artifacts from verified official metadata/cache; record all versions and SHA-256; generate isolated server.properties with pause-when-empty-seconds=0, RCON enabled and unique password; allocate unused ports; copy only declared mods; never use or modify fabric/run or any production world.
- run_bench.sh: default to dry-run; require explicit --execute to start anything; inventory processes/ports; start the server in its own process group; wait for Done ( and fail on mixin errors; collect logs and a manifest; invoke sample_metrics.py with RCON and jcmd/JFR if available; stop cleanly over RCON; trap cleanup and stop only the process group created by this script; preserve artifacts.
- Provide a baseline config and one workload file for a clientless idle/scheduled-task diagnostic only. Clearly state that this is not a player-load benchmark and cannot measure PvP, packet, mount, inventory or teleport performance without protocol clients.
- Do not implement a protocol bot in this pass. Document the exact next step for a protocol client driver.

Safety:
- Do not boot any server in this task. Run only dry-run/syntax checks.
- No broad process kills, no blanket process termination, no production world access.
- Never guess server bundle paths or version URLs. If required inputs are not available, fail closed with a precise error.
- Use setsid or equivalent to isolate the process group.
- Do not change production Java, Jenkinsfile, TESTING.md, COMPATIBILITY.md, FREEBUFF.md or docs/status.md.
- No code comments/docstrings. No commit/push.
- Record exact checks and exit codes in tools/freebuff/reports/qa-harness.md.
Stop after the scripts and dry-run checks are ready for manager review.
