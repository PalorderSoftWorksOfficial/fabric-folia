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
- **Mixin count:** 23 registered in `fabric/src/main/resources/fabricfolia.mixins.json`
  (the respawn follow-up changes existing packet mixins; a faulty `ReportTypeMixin`
  was removed in commit `c36f8c3`).
- **Tests:** `./gradlew :common:test :api:test :fabric:test` — 116/116 green
  (2026-09-24 region-pipeline turn; no Mockito). Fabric tests boot the REAL engine
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

### Still open (priority order)
0. Uncommitted on-disk work: the 2026-09-24 CI turn (`.github/workflows/
   build.yml`, RegionTransitions UNATTACHED counter + Snapshot, tests) AND
   the 2026-09-24 region-pipeline turn (this section) — commit when the
   user asks; suggested split: `ci:` commit, then `fix(scheduler):` commit.
1. Direct `PlayerList.respawn` callers from mods/API and a destination-region
   placement hook; login placement.
2. Portal-side staging beyond the teleport funnel.
3. `FabricFoliaMod` service-locator indirection (DESIGN complaint) — explicit
   engine holder.
4. PR creation: blocked — no `gh` CLI, no PAT; work is already on `main`.
5. GitHub Actions CI workflow landed `.github/workflows/build.yml` (2026-09-24
   CI turn) — verify the first Actions run goes green once pushed.

## 7. Definition of done for any turn here
- Changes compile: `./gradlew :fabric:compileJava` (or full `:common:test :api:test :fabric:test`).
- If mixins changed: one live boot check (`/tmp/pplive.log` shows `Done (` with zero
  mixin errors), then clean shutdown + process sweep (§4).
- Docs updated (MIXINS.md per-mixin table, docs/folia-parity.md rows, this file §6).
- End with an IMPLEMENTED / CHANGED / REMOVED / REMAINING report.
- FREEBUFF.md updated.
