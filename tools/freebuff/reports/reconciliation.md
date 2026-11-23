# Upstream reconciliation record (2026-11-22)

Owner: manager session. Status: **staged and verified in an isolated worktree; not applied to the shared checkout yet.**

## 1. What is being reconciled

| Item | Value |
| --- | --- |
| Checkout | `/home/administrator/CDN-bucket/fabric-folia`, branch `main` |
| Local HEAD | `94976ef` (docs: record verified idle-pause stall fix deployment) |
| Upstream | `origin/main` = `c2fbe21`, four commits ahead |
| Local dirty | 26 modified tracked files, 4 untracked Java/test files, untracked `tools/` |
| Backup | `/tmp/ff-backup/local-tracked.patch` (sha256 `31e76dcd…48aba35`), `/tmp/ff-backup/untracked.tgz` |
| Reconciliation tree | `/tmp/ff-reconcile` (detached worktree at `c2fbe21`) |

The shared checkout was never reset, cleaned, stashed or checked out over. Its existing dirty implementation files were preserved, not overwritten. New planning and agent-tooling paths (`docs/PROJECT_VISION_AND_ROADMAP.md`, `tools/freebuff/**`) were added there; the reconciled implementation remains in `/tmp/ff-reconcile` and nothing was copied back.

### Upstream commits

1. `a42e96a` `feat(vanilla): override-vanilla layer …` — new `patches.override-vanilla` + `VANILLA_OVERRIDE` patch layer; OFEM directional collision rewrite; per-section hard-hitbox index (`ConcurrentClassInstanceMultiMap`, `HardHitBoxEntityIndex`, `EntityGetterMixin`, `EntitySectionMixin`); server-thread light-engine deferral (`LevelChunkLightEngineMixin`); `/folia vanilla status|kill` kill switch.
2. `e98af65` — same subject; FREEBUFF.md correction only (post-rebase note). Duplicate message, distinct content.
3. `c850092` `fix(engine): worker getChunk no-create miss answers null, add random-tick work counter` — `WorkerChunkProbe` decision table; `ServerChunkCacheMixin.getChunk` returns vanilla's null for absent + `create=false` instead of parking on `supplyAsync(mainThreadExecutor).join`; random-tick executed-chunk counter.
4. `c2fbe21` `docs: record c850092 delivery and dead-flag correction` — FREEBUFF.md only.

### Local dirty work (what it is for)

- **Deferred-work barrier deadlock (P0-3):** `ServerThreadDeferral.deferBarrierSafe` + a second drain queue drained inside `FabricFoliaEngine.pumpChunkExecutors`; `ServerChunkCacheMixin.addTicketAndLoadWithRadius` uses it instead of `defer(Runnable)`.
- **Player slice atomicity (planner C4):** `RegionStageHub.stagePlayerEntity/stagePlayerConnection` pin one owner region and one chunk position per player per tick; `RegionPlayerRouting`/`LevelEntityTickMixin` route through the pin; `reclaimPinnedPlayerBody` for a mid-tick world change.
- **Packet routing:** `canHandlePacketOnCurrentThread(processorThread | ownerRegion && !serverThreadOnly && !respawn)` — fixes the processor-thread self-requeue.
- **Safe defaults:** entity/block-entity/scheduled/random tick staging default `false`; boot banner reports the real per-slice routing.
- **StallProbe:** suppress the pre-first-tick window.
- **DistanceManagerTrackingMixin:** tolerate a `removePlayer` for a chunk with no player set.
- **Tests:** `DeferredBarrierDeadlockTest` (7), `PlayerSliceAtomicityTest` (5), `ServerThreadDeferralTest` (+), `PlayerRespawnRoutingTest` (+), `StallProbeTest` (+).

## 2. Conflict resolution decisions

Only three files conflicted; everything else applied cleanly (including `ConfigSchema`, `FabricFoliaMod` and `FabricFoliaEngine`, whose hunks are disjoint).

| File | Decision | Why |
| --- | --- | --- |
| `mixin/ServerChunkCacheMixin.java` | **Upstream `WorkerChunkProbe` decision table wins**; local `RegionChunkAccessGuard` retired. Local `deferBarrierSafe` ticket path kept (no conflict). | Upstream's table is vanilla-exact: absent + `create=false` → `null` (vanilla's own answer), `create=true` / below-status → vanilla join. The local guard threw `IllegalStateException` for `create=true`, converting a pump-completable wait into a lost entity tick. One authoritative implementation (mandate 46). |
| `FREEBUFF.md` | Upstream 2026-11-21 sections kept, then the local 2026-11-16 / 2026-11-17 sections (upstream does not carry them). | Both are evidence records; neither supersedes the other. |
| `MIXINS.md` | Local rewritten inventory kept; count corrected 26 → **29** from `fabricfolia.mixins.json`; three upstream rows added (`EntityGetterMixin`, `EntitySectionMixin`, `LevelChunkLightEngineMixin`); `ServerChunkCacheMixin` row notes the worker present-chunk path. | Upstream's header was stale (26/23 documented) against a 29-mixin register. Table now diffs clean against the JSON. |

### Additional reconciliation edits

- `WorkerChunkProbe`: added `recordVanillaJoin()`/`vanillaJoins()` — the fall-through case is a worker taking vanilla's **blocking** join, which the quiesce pump must complete. Counted, rendered as `worker getChunk creating/below-status vanilla joins: N`. The local intent (make the worker's chunk-access risk observable) is preserved without the throw.
- `RegionChunkAccessGuard.java` and `RegionChunkAccessGuardTest.java` deleted; their metric line replaced by the `vanillaJoins` line.
- `PlayerSliceAtomicityTest`: one assertion repaired. `done.await(...)` fires inside the body, but `pendingBodies` is decremented in `StagedBatch.finally` **after** the body returns, so `assertEquals(0, hub.pendingBodies())` was order-dependent (failed once in the full suite, passed focused). It now awaits the decrement with a 10 s deadline and still requires exactly 0 — the invariant is unchanged, only the observation point.

## 3. Verification evidence

Run in `/tmp/ff-reconcile` (`bash gradlew`, `/tmp` is `noexec`):

| Check | Result |
| --- | --- |
| Conflict-marker scan (`<<<<<<<`, `>>>>>>>`, `=======`) | none, whole tree |
| `:common:compileJava :fabric:compileJava` | exit 0 |
| Focused: `WorkerChunkProbeTest`, `DeferredBarrierDeadlockTest`, `PlayerSliceAtomicityTest`, `ServerThreadDeferralTest` | 30 tests, 0 failures |
| Full `:common:test :fabric:test :api:test --rerun-tasks` ×2 | **280 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESSFUL twice |

Not done (deliberately): no dedicated-server boot, no live workload, no mixin-application check. The merged mixin set changed (`+3` new mixins, one injection body rewritten), so a live boot is mandatory before this is called done.

## 4. Application procedure (do not shortcut)

Specialists work in the **original checkout**; `/tmp/ff-reconcile` is a snapshot from 01:22. Never treat it as up to date for agent work.

1. Wait for `tools/freebuff/reports/{core,runtime,compatibility,qa}.md` and the follow-up reports (`core-implementation.md`, `qa` harness section).
2. Diff the original checkout against the snapshot to isolate specialist edits: `git diff` for tracked files plus the untracked file list.
3. Review each specialist diff. Core's queue touches only `common/{region,scheduler}` — a set the reconciliation does not modify, so its files are copied **from the original checkout into the worktree**, not the other way round.
4. Re-run the full suite in the worktree with an explicit exit code.
5. Apply the reconciled delta to the shared checkout **file-scoped** (never a tree-wide overwrite), then delete the two retired files.

### Reconciled delta (verified 2026-11-22)

Copy from `/tmp/ff-reconcile` to the shared checkout — 30 files:

```
FREEBUFF.md  MIXINS.md  THREADING.md  TROUBLESHOOTING.md  docs/status.md
common/src/main/java/.../config/ConfigSchema.java
common/src/main/java/.../config/FoliaConfig.java
common/src/main/java/.../metrics/RegionMetrics.java
common/src/main/java/.../patches/PatchRegistry.java
common/src/test/java/.../metrics/RegionMetricsWorkCountersTest.java
common/src/test/java/.../patches/PatchRegistryTest.java
fabric/src/main/java/.../FabricFoliaMod.java
fabric/src/main/java/.../command/FoliaCommand.java
fabric/src/main/java/.../command/FoliaHelp.java
fabric/src/main/java/.../engine/FabricFoliaEngine.java
fabric/src/main/java/.../engine/LightEngineDeferral.java            (new)
fabric/src/main/java/.../engine/RegionTickInterceptor.java
fabric/src/main/java/.../engine/WorkerChunkProbe.java              (new)
fabric/src/main/java/.../mixin/EntityGetterMixin.java              (new)
fabric/src/main/java/.../mixin/EntityMixin.java
fabric/src/main/java/.../mixin/EntitySectionMixin.java             (new)
fabric/src/main/java/.../mixin/LevelChunkLightEngineMixin.java     (new)
fabric/src/main/java/.../mixin/ServerChunkCacheMixin.java
fabric/src/main/java/.../util/ConcurrentClassInstanceMultiMap.java (new)
fabric/src/main/java/.../util/HardHitBoxEntityIndex.java           (new)
fabric/src/main/java/.../util/OfemCollision.java
fabric/src/main/resources/fabricfolia.mixins.json
fabric/src/test/java/.../engine/LightEngineDeferralTest.java       (new)
fabric/src/test/java/.../engine/PlayerSliceAtomicityTest.java
fabric/src/test/java/.../engine/WorkerChunkProbeTest.java          (new)
fabric/src/test/java/.../util/ConcurrentClassInstanceMultiMapTest.java (new)
fabric/src/test/java/.../util/OfemCollisionTest.java               (new)
```

Delete (retired by the chunk-lookup decision):
```
fabric/src/main/java/.../engine/RegionChunkAccessGuard.java
fabric/src/test/java/.../engine/RegionChunkAccessGuardTest.java
```

Leave untouched: `common/src/main/java/.../{region,scheduler}/**` (core agent), `fabric/build.gradle.kts` and `tools/freebuff/qa/**` (QA agent), all `tools/**`, `fabric/run/**`, `fabric/logs/**`, `build/**`, `.gradle/**`.

6. After applying, the working tree must equal `/tmp/ff-reconcile` for every file in the manifest — verify by hash, not by eye. Then run the full suite in the **shared checkout** with an explicit exit code.
7. If mixins changed (they did: +3), do one live boot: `Done (`, zero mixin errors, `/folia metrics` renders `worker getChunk absent no-create served null`, `worker getChunk creating/below-status vanilla joins`, `barrier-drained`, `bridge-waiters`; then RCON `stop` and a process sweep.

## 5. Open risks carried into the ledger

- `WorkerChunkProbe` fall-through is still a blocking join on a worker; the quiesce pump is the only thing that completes it. Counter added, runtime proof outstanding.
- `deferBarrierSafe` bridge completes only when the ticket-load future completes; a future that needs promotion in a later tick must be shown to progress under the barrier (unit test covers an already-completed future).
- Player pin clearing is per-flush; a player removed by a routed body mid-tick (the exact case Fabrolia f_3.2.6 still lists as a server stop) has no reclaim path here.
- Upstream's `override-vanilla` layer ships default-off but its collision/light paths are unverified by this session.
