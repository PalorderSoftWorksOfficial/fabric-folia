Manager handoff: implement your patch queue, in this order: D4, D2, D1, D3.

Approved decisions:
- D2 is approved as designed: make the abort real (stop the drain, skip the tick body, abortTick instead of completeTick), add per-task onSuccess and onRegionRemoved. A log that claims "aborting the region" while the region keeps ticking must not survive; if you find a reason the abort is unsafe, stop and report instead of leaving the lie in place.
- D4 counter getters are approved as public accessors on RegionScheduler; do not edit metrics/, config/, patches/, docs or FREEBUFF.md — I will wire them into /folia metrics during integration.

Scope (strict): common/src/main/java/com/palordersoftworks/fabricfolia/{region,scheduler}/** and matching tests under common/src/test/**. Nothing in fabric/, api/, docs/, config, metrics, patch registry or FREEBUFF.md. Your files are untouched by the four upstream commits and by the user's dirty work, so they will replay onto the reconciled tree as a patch.

Method:
- Write each regression first and show it failing on the current code, then fix. No weakened assertions: the D2 rewrite must be stronger than the current text assertion, not softer.
- Deterministic tests only (latches plus bounded observation, no sleeps as synchronization).
- No swallowed exceptions, no unbounded waits, no new dependencies, no comment blocks (why-comments only).
- Run focused tests only, one Gradle invocation at a time, e.g. `bash ./gradlew --offline :common:test --tests '*RegionSchedulerTest'` (this host's /tmp is noexec; use `bash gradlew`). Do not run a full build; do not boot a server.
- Do not commit, push, stash, reset or checkout over anything.

Deliverable: tools/freebuff/reports/core-implementation.md with, per item: the failing-first observation (test name plus the assertion message you saw), the change, the passing run with an explicit exit code, and anything you deliberately did not do (e.g. the position-less capture sites, the drop callback). Then stop and wait for the next handoff.
