# Fabric-Folia — independent engineering review

**Review date:** 2026-11-15  
**Candidate version:** 0.1.0  
**Project:** Palorder SoftWorks / Fabric-Folia  
**Decision:** Automated candidate validated; production release blocked

## Team process

Five Freebuff read-only specialist sessions completed and wrote:

| Role | Report |
|---|---|
| Product / architecture planner | [TEAM_REPORT_PLANNER.md](TEAM_REPORT_PLANNER.md) |
| Runtime / performance / concurrency | [TEAM_REPORT_RUNTIME.md](TEAM_REPORT_RUNTIME.md) |
| Public API / developer experience | [TEAM_REPORT_API.md](TEAM_REPORT_API.md) |
| Independent QA / security / release | [TEAM_REPORT_QA.md](TEAM_REPORT_QA.md) |
| OSS branding / maintainer experience | [TEAM_REPORT_BRANDING.md](TEAM_REPORT_BRANDING.md) |

A second wave of five Freebuff coding sessions could not be started because the concurrent-session limit was reached. No implementation work is attributed to sessions that did not run. The primary manager carried out the code and documentation changes after synthesizing the audit findings. The Freebuff UI did not preserve model identifiers in the captured terminal output, so this report does not invent model names.

## Architecture conclusion

Fabric-Folia has an actual regionizer, scheduler, bounded worker pool, staged tick capture, mixin integrations, diagnostics and a Java API. It is not an empty scaffold. However, the project was materially over-described: vanilla still selects the tick work on the server thread, and selected bodies are staged across a server-thread/worker barrier. This is not a complete independently ticking Folia world-tick implementation.

That distinction is now stated throughout the README, architecture, threading, scheduling, compatibility, API and release documentation. Experimental regionized gameplay, random-tick staging and player connection staging are separate gates; the main gameplay and player-path gates now default to false.

## Correctness work

| Area | Change | Regression evidence |
|---|---|---|
| Global scheduler | Removed the independent 50 ms daemon. Global tasks now run on the Minecraft server thread after worker quiescence, in GLOBAL context. Delays/repeats use server ticks; new work after close rejects. | Six GlobalSchedulerImpl tests and updated network/teleport/transition context tests |
| Worker shutdown | Made task start mutually exclusive with shutdown; queued work is abandoned and counted; post-shutdown submission rejects. | WorkerPoolGateTest shutdown assertion |
| Region splits | Staged batches are keyed by region and target chunk; each batch contains one chunk and carries a chunk position into RegionTaskQueue. | Forced split test asserts queued tasks reside with the post-split owners |
| Backpressure | Removed the region-round skip algorithm that discarded simulation bodies. Existing bounded global staging backpressure suspends new staging and uses vanilla-inline execution while backlog drains. | Retained ENTITY and PLAYER round tests |
| World identity | Region player ownership now compares world key before numeric region id. Teleport de-duplication keys include source world and entity id. | Same-region-id across two worlds and duplicate entity-id identity tests |
| Patch/config | Separated chunk-regionization from owner lookup; applied scheduler/lookup optimization flags when each world scheduler is created; config schema is version 2. | Config schema and Fabric config resolution tests |
| Safe defaults | Regionized gameplay and player connection staging are off by default pending modpack-specific validation. | Config defaults test |
| Operator experience | Updated /folia help, status and shutdown messages; documentation describes actual commands and permissions. | Existing command/help test suite |
| API distribution | Added standalone fabric-folia-api Maven Local publication with sources/Javadoc, contract tests and consumer instructions. | Four API tests, publishToMavenLocal, external javac consumer compile |

## Automated verification

The clean Gradle test/build sequence completed successfully after the help-topic regression was corrected.

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| common | 142 | 0 | 0 | 0 |
| api | 4 | 0 | 0 | 0 |
| fabric | 81 | 0 | 0 | 0 |
| **Total** | **227** | **0** | **0** | **0** |

Additional checks passed:
- API Maven Local publication, Javadoc JAR and sources JAR.
- A scratch Java 25 consumer compiled against the published Maven Local API artifact using the documented API coordinate.
- fabric.mod.json and the release approval JSON parse as valid JSON.
- Candidate JAR archive integrity, metadata constraints, logo, declared mixin classes and nested common/API dependencies.
- git diff --check and Python syntax validation for scripts/verify_release.py.

## Candidate artifacts

| Artifact | Size | SHA-256 |
|---|---:|---|
| fabric-folia-0.1.0.jar | 1,241,011 bytes | e8c82cc8694594b1bffea7bfb9be53cd7c95e5c518919c3e5892eb7d3905fe19 |
| fabric-folia-api-0.1.0.jar | 12,926 bytes | 93343ce1c98a285eb43063218f06f197718f8f35551ef2959190170edb0e5d38 |

The mod JAR contains the matching fabric-folia-api-0.1.0.jar and common-0.1.0.jar as nested dependencies. The standalone API is currently published to Maven Local only; no public Maven repository has been configured.

The local release verifier generated build/release-candidate/release-manifest.json and SHA256SUMS. A production-release invocation was also tested and correctly failed because release/production-approval.json is BLOCKED.

## Production blockers

1. **No live runtime verification.** No dedicated server was booted on the candidate build during this work. Required mixin application, server start/stop, world save/reopen, gameplay behavior, and a long-running soak remain unverified.
2. **Quiesce recovery policy.** The server-thread deferral API is asynchronous; a synchronous deadlock was not established. However, the engine waits beyond its first quiesce timeout while workers remain in flight. A stuck worker can stall the server indefinitely. Recovery must preserve the single-writer invariant rather than resume vanilla mutation concurrently.
3. **Player execution atomicity.** Entity tick, connection processing, packet routing, teleport, respawn and migration paths need an integrated runtime test. Player connection staging remains disabled by default.
4. **Compatibility.** There is no complete version-pinned modpack compatibility matrix. Mods assuming server-thread execution require review.
5. **Performance.** Removing round skipping avoids intentional simulation loss, but sustained single-region overload may trigger staging suspension and vanilla-inline work. The new path needs repeatable one-hot-region and multi-region load tests, queue-depth monitoring, and p95/p99 quiesce measurements.
6. **Persistence and lifecycle.** Chunk unload, region split/merge, server shutdown, restart, and world reopening must be exercised on disposable worlds.
7. **Public API distribution.** The Maven Local artifact works, but developers outside the build host still need a published repository or a documented artifact distribution channel.

## Final decision

The 0.1.0 output is a **validated automated release candidate**, not a verified production JAR. The Jenkins pipeline now archives only exact candidate JARs and refuses stable publication unless a separate approval file matches the exact source commit and contains runtime-soak, compatibility and independent-review evidence. That approval remains blocked. No Fill stable publication or production-server deployment was performed.
