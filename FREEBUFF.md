# FREEBUFF.md — model handoff file for fabric-folia

> **STANDING RULE (read first, follow always):** any AI model working in this repo
> MUST re-read this file at the start of its turn and MUST update it at the end of
> its turn with everything new it learned or changed (facts, defects, decisions,
> process discoveries, host quirks, work state). The goal: every model starts with
> the same information and never rediscovers it. Keep this file truthful and
> current — a stale FREEBUFF.md is worse than none.

---

## 1. Project identity

- **Repo:** `fabric-folia` at `D:\fabric-folia` (MSYS path `/d/fabric-folia`).
- **What it is:** a real Fabric mod implementing Folia-style regionized
  multithreaded execution for vanilla Minecraft **26.2** (Mojang names ARE the
  runtime names — no mappings layer). Must stay Fabric-native: mixins, Fabric API,
  Loom. NEVER a Paper/Folia fork or patch-based distribution.
- **License:** Apache-2.0, Palorder Softworks, 2026.
- **Git:** `main` branch; remote `origin` = `https://github.com/PalorderSoftWorksOfficial/fabric-folia.git`.
  Push works via `credential.helper=manager`; REST/PR needs a PAT we do not have.
- **Modules:** `api` (public contract, ~968 LOC) → `common` (engine core: regionizer,
  schedulers, config, metrics, threading) → `fabric` (mixins, engine bootstrap,
  entrypoints, the only module touching Minecraft classes).
- **Mixin count:** 24 registered in `fabric/src/main/resources/fabricfolia.mixins.json`
  (`ChunkTicketMixin` added in the 2026-09-25 ticket-deferral turn; a faulty
  `ReportTypeMixin` was removed in commit `c36f8c3`).
- **Tests:** `./gradlew :common:test :api:test :fabric:test` — 133/133 green
  (2026-09-25 ticket-deferral turn; no Mockito). Fabric tests boot the REAL engine
  (`FoliaConfig.at(...)` → `load()` → `freeze()` → `FabricFoliaEngine.bootstrap(config, info, error)`),
  see `fabric/src/test/java/com/palordersoftworks/fabricfolia/engine/NetworkDispatchTest.java`.
- **CI:** `.github/workflows/build.yml` (GitHub Actions) runs
  `./gradlew build --stacktrace` on push/PR to `main` — Java 25 (temurin),
  Gradle via the checked-in wrapper (9.5.1), test reports on failure, fabric
  jar archived. Jenkins (`Jenkinsfile`) still exists alongside; README
  documents Jenkins as primary.

## 2. Architecture in one page

- **Staging model (the load-bearing idea):** vanilla's own server-thread tick passes
  decide WHAT to tick; capture mixins stage the *bodies* into `RegionStageHub`
  (`fabric/.../engine/RegionStageHub.java`); at end of server tick the engine flushes
  each staged batch, grouped by owning region, onto that region's task queue
  (`RegionScheduler`, shared worker pool). Slices: ENTITY, BLOCK_ENTITY,
  SCHEDULED_TICK, PLAYER (declared LAST to preserve vanilla intra-tick order:
  entity pass before connection tick, per region).
- **Player path (gated by config `gameplay.stage-player-path`, default off):**
  `ServerConnectionTickMixin` stages the whole `Connection.tick()` body (packet drain,
  `SGPLI.tick` → `doTick` physics, flush) into the PLAYER slice;
  `PacketProcessorMixin` routes re-homed packets to the owning region;
  `PacketUtilsMixin` makes the re-home check pass on the owning region;
  `ServerPlayerTickMixin` bounces the two verified server-thread couplings inside
  `ServerPlayer.tick()` (`ServerChunkCache.move`, `ServerPlayerGameMode.tick`) to the
  server thread via `level().getServer().execute(...)`.
  **Respawn carve-out:** `ServerboundClientCommandPacket.Action.PERFORM_RESPAWN`
  is deliberately excluded from the owner-region pass; vanilla's packet queue
  rehomes it to `MinecraftServer.processPacketsAndTick` on the server thread,
  where `PlayerList.respawn` can safely build its replacement player and mutate
  global state.
- **Transitions:** `RegionTransitions` (`fabric/.../engine/RegionTransitions.java`)
  routes worker-context teleports to the destination context (region queue, or the
  global scheduler for unowned chunks), with a CAS idempotence window
  (entity id → stamp, 50 ms tick window). `EntityTeleportMixin` captures
  `Entity.teleport(TeleportTransition)` HEAD, cancellable.
- **Entity ownership:** `EntityRegionTracker` + `EntityMixin` (add/remove/move hooks,
  per-entity cached tracker + packed chunk); `RegionEntityRegistry` does atomic
  stripe-locked register/migrate/unregister.
- **Threading classification:** `ThreadOwnership` — one context slot per thread;
  kinds UNKNOWN/NETWORK/GLOBAL/REGION/...; region workers run inside REGION context
  (set by `RegionScheduler` line ~501 `enterRegion`). Check facade: `RegionChecks`,
  violations: `ViolationReporter`. Mandates §8/§11/§15/§32 live in docs/mandates.
- **Metrics:** `RegionMetrics` (`common/.../metrics/RegionMetrics.java`) — enum
  `Counter` + `snapshotLines()`. `/folia` command: status|shutdown|regions|entities|
  threads|metrics|global|watchdog|compat|pin|unpin.
- **Config:** `common/.../config/` — `CommentedYaml` (has `collapseDuplicates` for
  last-wins duplicate keys), `ConfigWriter` (groups sections by name),
  `FoliaConfig`. Runtime file `fabric/run/config/fabric-folia.yml` is gitignored.

## 3. Verified MC 26.2 facts (javap against the named jar)

Named jar for inspection (project loom cache):
`/d/fabric-folia/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-043a8b3edf/26.2/minecraft-common-043a8b3edf-26.2.jar`
(inspect: `javap -c -p -cp "$JAR" net.minecraft.world.entity.Entity > /tmp/entity.txt`)

- `ServerPlayer` **overrides** `teleport(TeleportTransition)` with covariant return
  `ServerPlayer` — descriptor
  `(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;`.
  An `Entity`-level mixin does NOT capture the override.
- `ServerPlayer.teleport` body: same-dimension branch = connection-local packet sends;
  cross-dimension branch mutates GLOBAL state (`PlayerList.sendPlayerPermissionLevel`,
  `removePlayerImmediately`, `sendLevelInfo`, `sendAllPlayerInfo`,
  `ServerLevel.addDuringTeleport`, `Profiler.get()`, advancements).
- `Entity.handlePortal()` → `PortalProcessor.getPortalDestination` → **virtual call
  `teleport(TeleportTransition)`** → dynamic dispatch to the ServerPlayer override
  for players. `ServerPlayer` does NOT override `handlePortal`.
- `PlayerList.respawn` builds a NEW `ServerPlayer` via
  `findRespawnPositionAndUseSpawnBlock` — does NOT funnel through `teleport`.
- `Entity.teleportSetPosition(PMR, PMR, Set<Relative>)` → `setPosRaw(DDD)` (the
  move hook fires; the old "bypasses first movement hook" defect was fixed).
- `Entity.teleportTo(ServerLevel,ddd,Set,float,float,boolean)` → constructs a
  TeleportTransition → `teleport(...)` (covered transitively).
- `Entity.teleportTo(DDD)` → `snapTo` + passengers (no TeleportTransition).
- `ServerPlayer.teleportTo(DDD)` / `teleportRelative(DDD)` → only send a client
  teleport packet via `ServerGamePacketListenerImpl.teleport` (position applies on
  client resync; no cross-thread world mutation server-side).
- `ServerPlayer.tick()` contains `ServerChunkCache.move` + `ServerPlayerGameMode.tick`
  couplings (bounced by ServerPlayerTickMixin); physics chain runs in `doTick()`
  inside `SGPLI.tickPlayer()` (connection tick), NOT the entity pass.
- `PacketProcessor.scheduleIfPossible(PacketListener,Packet)` is the single re-home
  funnel; `PacketUtils` 3-arg `ensureRunningOnSameThread` has one `isSameThread()`
  call site; `MinecraftServer.processPacketsAndTick` drains the processor queue on
  the server thread; `MinecraftServer.halt(boolean)` is public.
- `ServerboundClientCommandPacket.Action.PERFORM_RESPAWN` reaches
  `ServerGamePacketListenerImpl.handleClientCommand`, which calls
  `PlayerList.respawn`; that method constructs a fresh player and mutates the
  global player list plus `ServerLevel` entity manager, so it is not a normal
  destination-region teleport. Direct `PlayerList.respawn` callers from mods are
  not covered by the packet-level carve-out.
- 26.2 permissions: `source.permissions().hasPermission(Permissions.COMMANDS_OWNER)`
  (no `hasPermission(int)`).
- SGPLI anti-float guard: `dy > -0.03125 && noBlocksAround` → "kicked for floating too long!".

## 4. Dev-server / testing protocol — READ BEFORE ANY TEST

**The user's #1 pain: leftover server/Gradle processes overload the machine. Absolute rules:**

1. **Before** starting any build/test/boot: `tasklist.exe /FI "IMAGENAME eq java.exe"`
   and `netstat.exe -ano | grep -E ":(25901|25902)\s"` to inventory what exists.
   Never touch processes you cannot prove belong to this work (e.g. a bare
   `java.exe` on :8080 that predates the session = LEAVE ALONE).
2. The dev server uses **ports 25901 (MC) / 25902 (RCON)** — NOT 25565/25575.
   RCON password `foliapass`.
3. Launch pattern that works (launch and poll in SEPARATE commands — combining
   them kills the server when the shell exits):
   ```bash
   cd /d/fabric-folia && rm -f /tmp/pplive.log && (nohup ./gradlew :fabric:runServer > /tmp/pplive.log 2>&1 & echo "pid=$!")
   # then poll:  grep -E "Done \(|SUCCESS: Fabric Folia is ready|ERROR" /tmp/pplive.log
   ```
4. **After** every boot/test cycle: verify exit (log shows "Stopping server"/process
   gone from netstat), then `./gradlew --stop` (kills daemons started this session),
   and re-run the tasklist/netstat check. Kill ONLY processes started for this work.
5. `MinecraftServer.halt(false)` and RCON `stop` both produce clean exits (proven).

## 5. Host quirks / tooling traps (this Windows machine)

- **The bash-spawn tool intermittently fails with `ENOENT ... 'C:\Program Files\Git\bin\bash.exe'`**
  right after a restart; retrying later works. `cwd` "." resolves to the project root.
- **`read_files` is broken in some sessions** (its tool layer serializes arguments as
  `{"$text": ...}` → schema rejection, loops forever). WORKAROUND: read files via
  `run_terminal_command` with `cat`/`sed -n`/`grep`.
- Write-file tools need BOTH `path` + `instructions` + `content`.
  `str_replace` schema: `{"path", "replacements":[{"oldString","newString","allowMultiple"}]}`.
- Codex `code_search` may be broken (missing vendored rg.exe) — use `grep -rn`.
- Windows Python cannot `open('/tmp/...')`; edit /tmp files with `sed -i`/heredoc.
- `tasklist.exe /FI "PID eq 1234"` from MSYS: MSYS mangles `/FI` into a path —
  quote it or use PowerShell `Get-Process -Id`.
- `git ls-remote origin` may time out; use the explicit https URL with
  `GIT_TERMINAL_PROMPT=0`.
- Historical orphan pattern to watch for: gradle wrapper PID, `runServer` PID,
  Gradle daemon PID left behind by previous sessions (e.g. 1898 / 32936 / 43648).
- `handleDisconnection() called twice` WARN on every protocol-bot disconnect:
  unexplained, pre-existing, harmless so far.
- Bot walks pin against terrain with teleport-resync loops (vanilla collision
  guard, gate-independent). `compat/probe_bot.py` is NOT in the tree; recover via
  `git show 1325fdd:compat/probe_bot.py > /tmp/pb.py` if needed.

## 6. Work state (update this section every turn!)

### Done & pushed
- `8a7dd96` player path (connection tick staging + packet re-homes) — live-verified,
  100/100 tests, metrics balanced, pushed to origin/main.
- `96f76a8`, `c485874`, `c36f8c3` — user commits (README, fixes, removed ReportTypeMixin).
- Config fixes: `CommentedYaml.collapseDuplicates`, `ConfigWriter` section grouping.
- `/folia shutdown` (owner perm → `server.halt(false)`); RCON `stop` clean exit proven.
- Compat: 11 optimization-mod combos measured PASS (COMPATIBILITY.md).

### DONE in the 2026-09-24 coding turns (verified; included in the final commit)
- **ServerPlayer.teleport capture shipped + live-verified:**
  - NEW `fabric/.../mixin/ServerPlayerTeleportMixin.java` — captures
    `teleport(TeleportTransition)→ServerPlayer` HEAD (cancellable, covariant
    descriptor). REGION callers not owning the destination chunk dispatch the
    whole vanilla body via `RegionTransitions.dispatchKeyed` and cancel with
    `cir.setReturnValue(self)`. Recursion breaker + same-dimension fast path
    = `RegionTransitions.isCurrentContextOwner`. Registered in
    fabricfolia.mixins.json (23 mixins).
  - `RegionTransitions` additions: `dispatchKeyed(engine, worldKey, x, z,
    entity, body)` (world-key core, null-entity skips idempotence window),
    `isCurrentContextOwner(worldKey, x, z)`, `recordPlayerTransition()`,
    metrics line `player transitions dispatched: N`.
- **2026-09-24 CI turn (second turn, after the commit+push of the teleport/
  respawn work as `4809f73`):**
  - `RegionTransitions`: unattached-world refusals (engine live but the
    destination world never attached) now counted under a NEW `UNATTACHED`
    counter, separate from the transient unload-race `DROPPED` counter —
    operators can distinguish "fix the world attachment" from "expected
    race" in `/folia metrics`. Metrics line now reads `... dropped: N,
    unattached-world: M`. Public `Snapshot` record + `snapshot()` accessor
    for tests/diagnostics (plain volatile reads, mandate §35 semantics).
    BOTH `dispatch` and `dispatchKeyed` route through the new counter, and
    `dispatch` now also checks the regionizer, not just the scheduler.
  - NEW test `unattachedWorldRefusalIsCountedSeparatelyFromDrops` in
    `PlayerTeleportRoutingTest` (live engine, attached overworld dispatches,
    unattached nether refused, exactly +1 on the counter).
  - **Suite: 107 tests, 0 failures** (`:fabric:test --rerun-tasks`) and
    full `./gradlew build --stacktrace` green.
  - NEW `.github/workflows/build.yml` — GitHub Actions CI mirroring the
    Jenkins pipeline exactly (`./gradlew build --stacktrace`), Java 25
    temurin + Gradle wrapper, on push/PR to main + workflow_dispatch,
    least-privilege `contents: read`, test reports on failure, fabric jar
    archived (error if missing). No mixin changes → no live boot needed
    this turn (per §7 definition of done).
  - NEW test `fabric/src/test/.../engine/PlayerTeleportRoutingTest.java`
    (region hop / global-hop-to-FabricFolia-Global-thread / engine-down
    refusal / exactly-once execution).
  - Docs: MIXINS.md `### ServerPlayerTeleportMixin` table;
    docs/folia-parity.md Teleportation/Dimension-transfer/Portals rows →
    PARTIAL with the override story; honest-gaps paragraph updated.
- **Player respawn packet routing follow-up shipped + live-verified:**
  - `RegionPlayerRouting.isRespawnCommand` recognizes only
    `ServerboundClientCommandPacket.Action.PERFORM_RESPAWN`.
  - `PacketUtilsMixin` rejects the owner-region fast path for that packet;
    `PacketProcessorMixin` deliberately leaves it in vanilla's processor queue,
    which `MinecraftServer.processPacketsAndTick` drains on the server thread.
    This protects `PlayerList.respawn`'s replacement-player construction, global
    `PlayerList` mutations, and `ServerLevel` entity-manager placement without a
    synchronous cross-context wait.
  - Metrics now report `respawns server-thread=N`; the new
    `PlayerRespawnRoutingTest` covers all three client-command actions and the
    engine-down predicate. The seam is packet-level only: direct mod/API calls to
    `PlayerList.respawn` remain open, as does destination-region placement.
  - **Suite: 106 tests, 0 failures** (`:common:test :api:test :fabric:test`).
  - Latest live boot: `Done (1.312s)!`, `SUCCESS: Fabric Folia is ready.`, zero
    mixin errors; RCON `folia metrics` rendered the new respawn metric line;
    RCON `stop` shut down cleanly, ports 25901/25902 released, `./gradlew --stop`
    completed, and the final sweep left only pre-existing unrelated PID 12220
    (never kill it).
- Design note: the cross-dimension branch's global PlayerList mutations run
  on the DESTINATION region's worker (accepted risk, documented in the mixin
  javadoc; global scheduler drains on the `FabricFolia-Global` daemon thread,
  NOT the server thread — do not assume otherwise). Respawn takes the separate
  server-thread packet-queue path because `PlayerList.respawn` returns a fresh
  player synchronously and its destination is computed inside the method.

### DONE in the 2026-09-24 region-pipeline turn (verified live; NOT yet committed)
- **User request this turn:** fix the "critical region-threading failure" —
  `Regions: 0`, workers TIMED_WAITING, bad MSPT — plus a toggleable patch
  system (spec 25). Debug-first protocol (§26 of the request) was followed.
- **ROOT CAUSE (traced, not guessed):** the regionizer/scheduler/coordinator
  chain was fully real and healthy — the missing link was that **no
  production code path registered loaded chunks with the regionizer**. The
  ONLY `addChunk` caller in production was `RegionTickInterceptor.flushPass`
  (the random-tick slice, opt-in via `general.regionized-random-ticks`,
  default off; the dev run-dir had it on, which is why earlier boots showed
  regions). The unload side was wired (`ServerLevelUnloadMixin` →
  `removeChunk`) but the load side was not — an asymmetry. Consequence under
  default config: zero regions form, `RegionStageHub.dispatch` runs every
  staged entity/block-entity body INLINE on the server thread (`region ==
  null` path), so regionization added pure overhead → the observed lag.
- **Fix — `ChunkRegionization` (NEW, fabric/engine):** three registration
  sources, all idempotent, all gated on the patch flag:
  1. Fabric `ServerChunkEvents.CHUNK_LOAD` (registered in onInitialize;
     note Fabric API 0.160's Load callback is `(level, chunk, newChunk)`);
  2. attach-time backfill in `attachWorlds` (spawn chunk + player chunks,
     `getChunkNow`-gated);
  3. first-START_SERVER_TICK catch-up (armed by `markWorldsAttached()`):
     bounded spiral (radius 8) around `getRespawnData().pos()` per world —
     closes the pre-attach window (vanilla loads spawn during world init,
     BEFORE SERVER_STARTED; those events count as `unattached refusals`).
  Plus the runtime self-check (`healthCheckLine`, END_SERVER_TICK): live
  server + loaded spawn chunk + regions=0 → concise warning, ≥10s spacing.
- **Toggleable patch layer (spec 25):** NEW `common/.../patches/PatchRegistry`
  (name+layer registry; hot-path gate = layer AND patch, one volatile read;
  invocation/fallback counters; no runtime flip API — stable per session).
  Config: `patches.enabled` (default true) via ConfigSchema/FoliaConfig.
  Registered patches: regionize-chunk-load, stage-entity-ticks,
  stage-block-entity-ticks, stage-scheduled-ticks, stage-player-path,
  regionized-random-ticks, regionized-gameplay-dispatch. Gates live in:
  `RegionStageHub.isStaging` (slice→patch), `FabricFoliaMod.playerPathStaging`,
  `ChunkRegionization` (chunk-load + backfill), `ServerChunkCacheTickMixin`
  (random-tick redirect). Disabling any patch falls back to the original
  path; correctness layer (regionizer/ownership/thread checks) is untouched.
- **MSPT instrumentation (spec 16):** `Region` gains EWMA avg (α=1/100),
  peak watermark, and current-tick-start (pairing `markTickStart` →
  `completeTickTiming`, zero when idle); wired into `RegionScheduler`
  runRegionTick. `/folia regions` detail now shows `mspt=`, `peak=`;
  `regionCountsByWorld` shows `due` + `queue=`; `WorkerPool` gains an
  atomic `busyCount` (busy workers / total in `/folia threads`);
  `RegionScheduler.queuedRegionTasks()` + `dueRegionCount()` expose the
  dispatch picture; engine exposes `workerPool()`.
- **Diagnostics:** NEW `/folia patches` (patch name, layer, enabled,
  description, global-switch note). `Chunks regionized (chunk-load / attach
  backfill / unattached-refusals)` lines in `/folia metrics`.
- **Tests:** NEW `RegionPipelineTest` (fabric/engine, 3 tests — real engine:
  chunk registration → regions → repeated ticks w/ reschedule + MSPT;
  queued task executes with owning region as current REGION context;
  separate far-apart regions, adjacent activity merges at tick end — the
  test itself documents invariant 3's deferred merge). NEW
  `PatchRegistryTest` (common, 6 tests). Fixed `CommentedYamlTest`
  duplicate-key assertion: its substring counter now scopes to the
  `general:` section (my new `patches.enabled` option legitimately repeats
  `enabled:` at top level — test bug, not product bug). Suite 116/116.
- **LIVE VERIFICATION (two boots, RCON):** boot 1 (pre-fix build): forceload
  400 400 → 25/25 chunks regionized via CHUNK_LOAD, regions #1–#9, staged
  bodies 350,252/350,252 on workers (0 server-thread), region ticks 9,820+
  with MSPT avg 0.01–0.02ms — pipeline proven; found backfill=0 + 211
  pre-attach refusals → led to the catch-up fix. Boot 2 (fixed build):
  backfill=122 at first tick, regions form immediately (7 regions incl.
  nether+end, ticks advancing, MSPT ≤0.02ms), flag-gate proven (backfill=0,
  fallback=0 under `patches.enabled: false` config edit + forceload →
  regions still form via interceptor path), staging 39,828/39,845 on
  workers with 17 honest unowned-position fallbacks. Zero ERROR lines,
  clean RCON stop both boots, ports released, `./gradlew --stop` done,
  final sweep = only unrelated PID 12220.
- **Trap discovered:** Fabric API 0.160 `ServerChunkEvents$Load` is a
  3-arg functional method — a lambda compiles confusingly wrong; use a
  method reference so javac reports the mismatch.

### DONE in the 2026-09-24 delivery turn (PR #1: commit → push → PR → merge)
- Delivered on branch `fix/region-pipeline-patch-layer` off main (never
  committed to main directly), split exactly as planned:
  - `44a30df` `ci: add GitHub Actions workflow; count unattached-world
    transition refusals` (workflow + RegionTransitions + teleport test);
  - `657d034` `fix(scheduler): regionize loaded chunks; add toggleable
    patch layer` (everything else incl. FREEBUFF.md merge-only updates);
  - empty `ci:` trigger commits pushed during the CI troubleshooting
    (1e03809 on the branch; cf933e9 on main).
- Push via credential.helper worked. **No `gh` CLI — PR #1 was created via
  the REST API with the git credential-manager token**:
  `TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential
  fill | sed -n 's/^password=//p')` then `POST /repos/.../pulls` (201).
  Token handled in-shell only, never echoed. **PR #1 merged (rebase, 200
  "merged": true)**: main = f2caa06 (both conventional commits on top of
  4809f73); local main synced; feature branch may be deleted.
- **GitHub Actions is BLOCKED at the account level** (2026-09-24 delivery
  turn): workflow dispatch and every push/pull_request event return
  `422 "Actions has been disabled for this user."` — zero runs exist, and
  the PR-branch and main-merge check-suites stuck as empty `queued` suites
  (4 suites, 0 check-runs) were the symptom. Workflow IS registered and
  active (id 366291118, state active) and repo-level permissions were PUT
  to enabled/all (204) — none of that helps while the USER-level flag is
  off. Only the account owner can flip it (user Settings → Actions, or
  enterprise policy). Until then: CI cannot go green and the PR checks
  show "There are no checks"; the merged state rests on the verified
  local + live evidence (116/116, build, RCON boots) — do not claim CI
  green. When the owner enables Actions, re-fire once (empty `ci:` commit
  or workflow_dispatch) and the first real run should execute.
  Re-confirmed on the 2026-09-24 continuation turn: another dispatch probe
  returned the same 422 (third confirmation); main remained synced at
  1958ef4 with a clean tree — no other pending delivery items.

### DONE in the 2026-09-24 patch-layer/`/folia` turn (verified live; NOT committed)
- **PatchRegistry v2** (full metadata): id/name/layer/description/target/dependencies/
  conflicts/lifecycle(STARTUP_ONLY|RUNTIME)/status; one-time resolution pass
  (`resolveAndApply`, order-independent fixpoint) with statuses
  ACTIVE/DISABLED/BLOCKED_DEPENDENCY/BLOCKED_CONFLICT/FAILED + reasons;
  hot gate `isEnabled` = one volatile read; `requested` defaults true.
  **11 real patches registered** (each gated at a real call site): chunk-load
  regionization, entity/BE/scheduled-tick staging (deps: chunk-load;
  scheduled deps: BE), player-path staging, packet-dispatch,
  allocation-reduction, region-lookup (O(1) index), scheduler-dispatch,
  task-queue. Per-patch config keys `patches.<layer>.<patch>`; global
  `patches.enabled`; resolution BEFORE gates are consulted, gates pushed
  into engine objects (schedulerDispatchPatch, ownerLookupIndex,
  GLOBAL_TASK_QUEUE_PATCH).
- **Real optimizations (all behind patch gates, fallback = original path):**
  - O(1) `sectionOwners` reverse index in WorldRegionizer (ownerOfChunk was
    a linear scan of liveRegions per call) — maintained at adopt/both
    immediate-merge loops (addChunk's AND completeTick's mergeNowLocked!)/
    killRegion/killMergedRegion/removeDeadSections/split; gate
    `setOwnerLookupIndex` (falling back to the linear scan keeps identical
    answers).
  - Zero-alloc `forEachLiveRegion` + gated coordinateLoop scan path (the
    old loop copied the live set every 1ms).
  - RegionTaskQueue: O(1) `pendingCount` AtomicInteger (size() was O(n))
    maintained at add/drain/rehome/dropAll/partition/removeTask + exact-size
    drain buffers; gate `setGlobalTaskQueuePatch`.
  - RegionStageHub flush: empty-batch short-circuit (per-world, before any
    dispatch work).
- **Config writer N-segment fix**: buildDefaultTree/appendMissing now walk
  nested keys (`patches.minecraft.entity-tick-optimization` renders as real
  nested YAML; the old 2-segment assumption emitted dotted keys snakeyaml
  cannot parse → ConfigException on load). CommentedYaml.get already
  traversed nesting.
- **`/folia` overhaul** (FoliaCommand rewritten): MiniMessage layer
  (`FoliaMessages` + `ComponentConverter` adventure→vanilla, no
  adventure-platform; MiniMessage 4.23.0 added to catalog + bundled);
  `FoliaVersion` (loader-sourced rows, "Unknown" when absent);
  `FoliaHelp` (9 in-game topics, self-contained per spec 34);
  `FoliaDiagnostics` (single source of truth: regionSummary/
  regionDetail/workerLines/schedulerLines/healthChecks — all reading the
  real engine). Subcommands: version/help(+topic)/regions(+verbose)/
  workers/scheduler/patches/health/threads/status/metrics/entities/compat/
  pin/unpin/shutdown. Permissions: atom
  `fabricfolia.command.folia.<sub>` **OR COMMANDS_OWNER** (OP default —
  atom-only gating made diagnostics invisible to OP; level-based sets
  don't carry custom atoms; found live via RCON).
- **Runtime verified (RCON, 3 boots)**: version/help/regions+verbose/
  workers/scheduler/patches/threads/health/status/metrics/help topics all
  render real state; 11/11 patches active at boot; health all-OK after
  fixing a sampling-artifact false positive (due>0 && busy==0 needs one
  50ms re-probe — the coordinator dispatches within 1ms, a mid-cycle sample
  reads stale). MSPT 0.01–0.03ms; staged 53k bodies → 53,238 workers /
  17 server-thread (unowned); merges=2 splits=0. 127/127 tests
  (+11 PatchRegistry v2, +7 FoliaMessages/version/help). Jar: command
  classes + adventure jars nested verified. Clean shutdowns; sweep = only
  PID 12220. mcrcon needs PYTHONIOENCODING=utf-8 now (UnicodeEncodeError
  on MiniMessage output — host quirk, §5).
- **Not measured (honest, spec 41)**: no allocation/GC microbenchmarks;
  the optimizations are structural (removed scans/copies), verified by
  behavior + live MSPT, not by invented numbers.

### Still open (priority order)
1. Enable GitHub Actions at the ACCOUNT level (owner action, outside the
   repo) — then re-fire a run and verify it goes green.
2. Commit the 2026-09-24 patch-layer //folia turn (on-disk, verified).
3. Direct `PlayerList.respawn` callers from mods/API and a destination-region
   placement hook; login placement.
4. Portal-side staging beyond the teleport funnel.
5. `FabricFoliaMod` service-locator indirection (DESIGN complaint) — explicit
   engine holder.
6. Delete the merged feature branch `fix/region-pipeline-patch-layer`
   (optional housekeeping).

## 7. Definition of done for any turn here
- Changes compile: `./gradlew :fabric:compileJava` (or full `:common:test :api:test :fabric:test`).
- If mixins changed: one live boot check (`/tmp/pplive.log` shows `Done (` with zero
  mixin errors), then clean shutdown + process sweep (§4).
- Docs updated (MIXINS.md per-mixin table, docs/folia-parity.md rows, this file §6).
- End with an IMPLEMENTED / CHANGED / REMOVED / REMAINING report.
- FREEBUFF.md updated.

### DELIVERED in the 2026-09-25 commit/push turn
- Re-read this handoff before delivery, as required.
- Created and pushed feature branch `feat/patch-layer-folia-command` from `main`; no direct commit to `main` and no pull request was opened.
- Delivered the verified patch-layer and `/folia` command work in two commits:
  - `6195768` `perf(patches): add gated region and scheduler optimizations` — PatchRegistry v2, nested patch configuration, O(1) ownership lookup, zero-allocation scheduler scanning, task-queue accounting, empty-batch short-circuit, bootstrap wiring, and registry regression tests.
  - `7b90e61` `feat(command): expand folia diagnostics and formatting` — MiniMessage formatting and bundled Adventure libraries, version/help/diagnostics/health command surfaces, permission handling, FoliaMessages tests, and this handoff update.
- Remote verification: local and `origin/feat/patch-layer-folia-command` both resolve to `7b90e61c5a94f9f2eee5c0bd64ecc9beff32c9f7`; the working tree is clean.
- This turn did not call GitHub Actions APIs, create CI-trigger commits, poll checks, or attempt to enable CI. Actions remains account-level disabled and owner-only; the prior verified local results (127/127 tests, successful build, three clean RCON boots) are the available evidence.
- The prior Still-open item to commit the patch-layer turn is now complete. The account-level Actions blocker and the remaining respawn/login/portal/service-locator work remain open as listed above.

### DONE in the 2026-09-25 crash-fix turn (verified; committed on the feature branch)
- **Trigger:** production crash on PalorderCentral (WSL2 dedicated, 26.2, 2 players
  incl. spectator bot at extreme coords): `Exception ticking world` — NPE
  `"this.wrapped" is null` in fastutil `Long2ByteOpenHashMap$MapIterator.nextEntry`
  inside `DistanceManager.forEachEntityTickingChunk` → `ChunkMap.forEachBlockTickingChunk`
  → `ServerChunkCache.redirect$elf000$fabricfolia$folia$interceptChunkEnumeration`
  (our `ServerChunkCacheTickMixin` frame). Concurrent ticket-map mutation during
  server-thread enumeration.
- **Root cause (bytecode-verified on the named 26.2 jar):** worker-context ticket
  placement mutates `TicketStorage` → `DistanceManager` trackers (same tick via
  `simulationChunkUpdatedListener`; `SimulationChunkTracker.chunks` IS the crashed
  `Long2ByteOpenHashMap`). Off-thread writers reachable from staged entity bodies:
  `Entity.placePortalTicket(BlockPos)` (PORTAL ticket radius 3 — callers verified:
  ONLY the private static helper in `TeleportTransition.postTeleport`, i.e. after a
  teleport actually executes; NOT from standing in a portal — `Entity.handlePortal`
  does not call it; `PortalForcer` has ZERO addTicket calls in 26.2) and
  `static ServerPlayer.placeEnderPearlTicket(ServerLevel, ChunkPos)` (ENDER_PEARL
  radius 2; called from player pearl-restore/teleport code, NOT `ThrownEnderpearl`).
  Removal side stays server-thread-owned.
- **Fix shipped:** NEW `fabric/.../engine/TicketDeferral.java` (per-world
  `ConcurrentHashMap` pending map keyed by record Key(world, packedChunk, TicketType
  record ref); `Placement(world, packedChunk, type, radius, ticketLevel)`;
  `defer`/`drain(worldKey, apply, suppressionActive)` per-world scoped with
  iterator-remove; suppression ⇒ `PENDING.clear()`; `clearWorld`; metrics
  deferred/drained/drainedBatches/pendingCount; freshness-over-FIFO duplicate
  collapse). NEW `fabric/.../mixin/ChunkTicketMixin.java`: HEAD cancellable injects
  on `ServerChunkCache.addTicketWithRadius(TicketType,ChunkPos,I)V` and
  `addTicket(Ticket,ChunkPos)V` — REGION-context callers cancel + defer; HEAD inject
  on `tick(BooleanSupplier,Z)V` — non-REGION callers drain their own world's
  placements verbatim before `runAllUpdates`/enumeration, re-checking
  `randomTickInterceptSuppressed()` at replay. `fabricfolia.mixins.json` now 24
  mixins. `RegionStageHub.fabricfolia$bodyNeighborhoodLoaded` gained a
  zero-loaded-chunks pre-flight bounce to the server thread (transition-window
  guard; chunk source can be down between worlds). `FabricFoliaEngine`: new metrics
  line `chunk tickets deferred from workers: N (replayed server-thread: M, pending
  now: P)`; `detachWorld` calls `TicketDeferral.clearWorld`.
- **Tests:** NEW `TicketDeferralTest` (5: per-world drain scoping, duplicate→LATEST,
  suppression drops all, clearWorld scoping, real-engine drain) and
  `TransitionBodyServerThreadTest` (1: unowned-destination body runs on the
  `FabricFolia-Global` thread in NON-REGION context — pins that teleport-borne
  tickets there are NOT deferred). Tests pass **null** TicketType (see trap below)
  and use delta assertions on the cumulative statics. Suite 133/133; `./gradlew
  build` green; jar contains TicketDeferral + ChunkTicketMixin.
- **Live verification:** boot `Done (2.079s)`, 11/11 patches, zero ERROR/Exception
  lines, new metrics line renders (baseline 0). **Live capture probe NOT achieved —
  honest record:** entity-ticking requires a player online (EggLayTime frozen with
  nobody on; protocol bot recovered from `git show 1325fdd:compat/probe_bot.py` and
  relaunched with a `stay` phase proves ticking). Portal probes all failed at the
  vanilla destination lookup: standing chicken (built overworld+nether portals,
  POIs verified via `locate poi`, gamerule true, bot player teleported into portal)
  never teleports — `transitions dispatched: 0` throughout — cooldown cycles
  (300→0→300) prove the flow runs to `getPortalDestination`, which returns null
  (environment-specific; NOT our mixin — same failure mode as the known open item
  "portal-side staging beyond the teleport funnel"). Ender-pearl probe: the bot
  has permission=none, so its `give` command is rejected and no pearl exists —
  command-based pearl supply is impossible for an offline-mode bot. In PRODUCTION
  the destination lookup demonstrably succeeds (the crash itself is the proof a
  worker ran `placePortalTicket`); dev-world evidence = unit tests + clean boot +
  zero mixin errors. The two new tests pin the drain/replay mechanics.
- **26.2 API traps discovered this turn:** `TicketType` has NO name/identifier/byName
  (it's a `Record(long timeout, int flags)`; static instances only) — identity IS the
  record reference; do not invent names. `ChunkPos.unpack(long)` exists;
  `new ChunkPos(long)` does NOT. `ServerChunkCache` has `addTicketWithRadius`,
  `addTicket`, `getLoadedChunksCount()`, `getChunkNow(int,int)` (the latter two NOT
  on `ServerLevel`). Touching `TicketType` in a unit-test JVM throws
  `ExceptionInInitializerError: Not bootstrapped (called from registry
  minecraft:game_event)` — tests must pass null types (deferral carries them
  opaquely). `GameRules` ids are snake_case in 26.2
  (`allow_entering_nether_using_portals` — the old camelCase name errors).
- **Tooling quirks added:** RCON output is one concatenated MiniMessage line —
  use `grep -oE`; `say` output goes to chat only, use `data get`/`execute if … run
  data get` as boolean probes. `@e` selects ACROSS dimensions from RCON (marker
  summoned in the nether was visible from the overworld context) — dimension
  scoping of selectors is unreliable for census. Windows python cannot see `/tmp`
  (MSYS-only path) — patch bot scripts with sed/heredoc, not python.
  Bot phases added in /tmp: `stay` (150s), `pearl` (give+select+throw; useless
  without permissions). Bot relaunch loop: `while true; do python pb.py stay;
  sleep 1; done`.
- **Process notes:** live server (gradle runServer JVM 56848) exits leaving its
  gradle launcher JVM; `./gradlew --stop` may report "no daemons" while a
  different-version daemon (9.7.1, PID 51504) lingers — verify by command line
  before killing only our own. Final sweep: only protected PID 12220 remains.
- **Still open:** suppressed `ConcurrentModificationException: Async entity load`
  on `use_item` (production, ×4, unfixed, separate issue); portal-side staging
  beyond the teleport funnel; the javadoc inaccuracy in `RegionTransitions`
  ("server-thread context" for the unowned hop) was FIXED this turn (now says
  `FabricFolia-Global` MPSC dispatch thread, non-REGION, ticket capture does not
  fire there). MIXINS.md gained the ChunkTicketMixin row (24 mixins).
