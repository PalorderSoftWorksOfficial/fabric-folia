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


## 2026-11-16 follow-up — API hardening and live candidate smoke boot

The follow-up commit `d3b3e164dbc15a6f69fb9a4c2161c56f017a1fd2` is pushed to `origin/main`. It aligns the minimum Fabric Loader version with the actual target runtime (0.19.3), updates the compile target to Fabric API 0.161.0, adds standalone API metadata and public-surface tests, strengthens the release verifier for standalone and nested API metadata, and makes position-targeted API scheduling retry transient region ownership changes rather than silently losing work. Void scheduling calls reject after shutdown; `send` retains its boolean enqueue result.

Final automated verification: common 144, API 10, Fabric 81; 235 tests total, 0 failures, 0 errors, 0 skipped. The clean-checkout release verifier passed and produced:

| Artifact | Size | SHA-256 |
|---|---:|---|
| fabric-folia-0.1.0.jar | 1,241,623 bytes | 41789d71cc6753fe6fc000a615ad5b24568b4e7a12c40e71120e9c384633e0fe |
| fabric-folia-api-0.1.0.jar | 13,378 bytes | 0047f6ba0a7f3de8c30d91028acef63123d7e29529b30d79eaac36d60f15903e |

### Pelican target smoke deployment

At the user's explicit request, `fabric-folia-0.1.0.jar` was uploaded to `thebrokenstarsmp-fabric:/mods/` and the Pelican restart action was accepted (HTTP 202). The restart log shows a graceful stop with players and all four dimensions saved, followed by a successful Fabric-Folia initialization. The panel reports the server running and the allocated Minecraft port is listening on the FAC LAN address.

The target runtime was Minecraft 26.2, Fabric Loader 0.19.3, Java 25.0.5, with Fabric API 0.161.0 installed. The JAR metadata and build were aligned to Loader 0.19.3 before deployment.

The live startup report showed 10 active patches of 12 registered (2 blocked), eight region workers, and eight async workers. The existing server config explicitly has regionized gameplay and player connection staging enabled. Its compatibility scan reports 248 mods undeclared, zero declaring support, and zero classified incompatible. Undeclared is not proof of incompatibility, but it is also not compatibility certification. The config was left unchanged.

The server's empty-server pause activates after 60 seconds. This was the root cause of the repeated StallProbe reports: Minecraft 26.2 increments `MinecraftServer.emptyTicks` while the player list is empty and, once it reaches `DedicatedServer.pauseWhenEmptySeconds() * 20`, logs the pause, autosaves, performs connection ticks, and returns without advancing `tickCount`. `MinecraftServer.isPaused()` is a stub returning false in the 26.2 runtime, so the corrected probe checks the actual empty-player/threshold state instead. It resets stall and crawl baselines during the intentional pause. The probe is also started at `SERVER_STARTED` rather than `SERVER_STARTING` to avoid reporting world-construction/class-loading work as a hung gameplay tick.

Startup also logged a datapack function parse error for `orbital_strike_cannon:get_zombie_shulker` (`Expected literal` while parsing a command). Its relationship to this mod deployment has not been established and it remains a separate observed startup issue.

No new full Pelican backup could be created through the available panel shell because its SQLite database connection rejected writes as read-only. Existing successful full backups are dated 2026-09-28 and 2026-09-30. The restart itself saved players and all dimensions successfully before launching the candidate.

### Remaining release blockers

The smoke boot proves that the candidate loads on this exact runtime and that the server reaches its idle-paused running state. It does not prove gameplay correctness, player movement/teleport/respawn safety, persistence after a longer run, mod compatibility, or performance under active player load. The server's explicit experimental settings and 248 undeclared mods make those checks especially important. The StallProbe idle-pause false positive is addressed by the 2026-11-16 follow-up code change and awaits live verification after redeployment. The datapack function parse error remains a separate unresolved startup diagnostic. `release/production-approval.json` remains BLOCKED; this deployment is a user-requested live candidate smoke test, not a production-readiness approval or stable Fill publication.


### 2026-11-16 StallProbe correction

The follow-up corrects the idle-pause false positive using the Minecraft 26.2 runtime's actual control flow. With an empty player list and a positive pause setting, `tickServer` advances `emptyTicks` to the configured threshold, saves, performs connection ticks, and returns without advancing `tickCount`. The 26.2 `isPaused()` method is a constant-false stub. StallProbe now reads the real threshold state, resets both timing windows while intentionally paused, and starts only after `SERVER_STARTED`.

Diagnostic storage is now bounded to 10 newest dump files, 10 MiB total, and 7 days. Each dump is capped at 250,000 characters; the unbounded temp-directory fallback was removed. Automated suite counts after the change: common 144, API 10, Fabric 85, 239 total, no failures, errors, or skips. Live verification after redeployment is still required.
