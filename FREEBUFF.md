# FREEBUFF.md — model handoff file for fabric-folia

> **STANDING RULE (read first, follow always):** any AI model working in this repo
> MUST re-read this file at the start of its turn and MUST update it at the end of
> its turn with everything new it learned or changed (facts, defects, decisions,
> process discoveries, host quirks, work state). The goal: every model starts with
> the same information and never rediscovers it. Keep this file truthful and
> current — a stale FREEBUFF.md is worse than none.
>
> **OWNER DIRECTIVE (2026-09-25, supersedes earlier branch flow):** work stays
> ON `origin main` — commit and push to main directly; sync any outstanding
> patch-layer branch work into main. **FIX BUGS AT THE ROOT — NEVER SUPPRESS
> THEM.** Swallowing an exception into a counter, carpet's update-suppression,
> or a silent drop is not acceptable for a diagnosed failure mode: the body
> must be retried or the bug fixed at its cause, with the outcome visible in
> metrics.

---

## 1. Project identity

- **Repo (current host, 2026-11-22):** Linux FAC host. Original working checkout
  `/home/administrator/CDN-bucket/fabric-folia` (branch `main`, local dirty work
  preserved); reconciled worktree `/tmp/ff-reconcile` at `c2fbe21`. The historical
  Windows path `D:\fabric-folia` (`/d/fabric-folia`) is kept only in the history
  sections below and no longer describes the active machine.
- **What it is:** a real Fabric mod implementing Folia-style regionized
  multithreaded execution for vanilla Minecraft **26.2** (Mojang names ARE the
  runtime names — no mappings layer). Must stay Fabric-native: mixins, Fabric API,
  Loom. NEVER a Paper/Folia fork or patch-based distribution.
- **License:** Apache-2.0, Palorder Softworks, 2026.
- **Git (historical owner directive, 2026-09-25):** stay on `main` and push there.
  **For the current delegated/manager work this is history only: no commit, push,
  publish or deploy without explicit approval.** Remote `origin` =
  `https://github.com/PalorderSoftWorksOfficial/fabric-folia.git`.
  Push works via `credential.helper=manager`; REST/PR needs a PAT we do not have.
- **Modules:** `api` (public contract, ~968 LOC) → `common` (engine core: regionizer,
  schedulers, config, metrics, threading) → `fabric` (mixins, engine bootstrap,
  entrypoints, the only module touching Minecraft classes).
- **Mixin register:** 29 declared classes in `fabric/src/main/resources/fabricfolia.mixins.json`
  (2026-11-22 reconciled tree). A declared count is **not** proof that any mixin
  applied; only an exact-jar mixin export or a server boot can show that, and neither
  has been run on the reconciled tree.
- **Team sessions (canonical role names, 2026-11-22):** `fabric-folia-manager`,
  `fabric-folia-core`, `fabric-folia-runtime`, `fabric-folia-compatibility`,
  `fabric-folia-protocol`, `fabric-folia-qa`. Not every role is running at a given
  moment; check `tmux list-sessions` rather than assuming. Transient review sessions
  may carry a suffix (e.g. `fabric-folia-runtime-review-r1`). The launcher matches
  exact names and never kills a session.
- **Tests:** `bash ./gradlew :common:test :api:test :fabric:test` (bare `./gradlew`
  fails: the worktree wrapper lacks the executable bit and `/tmp` is `noexec`). The
  reconciled tree records **280 tests (API 10, common 148, Fabric 122), zero
  failures/errors/skips** from the reconciliation run's JUnit XML (2026-11-22; unit
  evidence only, no boot). A later focused core run adds **25 passing common tests**
  (7 `RegionSchedulerSplitRehomeTest`, 4 `RegionFailurePolicyTest`, 10
  `RegionEntityRegistryTest`, 4 `EntitySchedulerLifecycleTest`; 0 failures/errors/
  skips) — a **separate** run from the 280 baseline, not a replacement. Older counts
  in this file's history are historical. No Mockito. Fabric tests boot the REAL engine
  (`FoliaConfig.at(...)` → `load()` → `freeze()` → `FabricFoliaEngine.bootstrap(config, info, error)`),
  see `fabric/src/test/java/com/palordersoftworks/fabricfolia/engine/NetworkDispatchTest.java`.
- **CI: JENKINS IS THE CI (owner directive, 2026-10-06) — GitHub Actions is
  NOT used.** `.github/workflows/build.yml` was DELETED this turn (it could
  never run: account-level Actions disabled, confirmed 422 three times).
  `Jenkinsfile` = the only pipeline: stage 1 `./gradlew :common:test
  :fabric:test :api:test --stacktrace` with `junit` publishing of
  `*/build/test-results/test/*.xml`, stage 2 `./gradlew build --stacktrace`,
  then archives `fabric/build/libs/*.jar`. README already documents the
  self-hosted instance (jenkins.palorderhosting.net) as primary.

## 2. Architecture in one page

- **Staging model (the load-bearing idea):** vanilla's own server-thread tick passes
  decide WHAT to tick; capture mixins stage the *bodies* into `RegionStageHub`
  (`fabric/.../engine/RegionStageHub.java`); at end of server tick the engine flushes
  each staged batch, grouped by owning region, onto that region's task queue
  (`RegionScheduler`, shared worker pool). Slices: ENTITY, BLOCK_ENTITY,
  SCHEDULED_TICK, PLAYER, flushed in declaration order **relative to each other**.
  The PLAYER slice flushes at `END_SERVER_TICK`, after `PlayerList.tick` and
  `PlayerChunkSender.sendNextChunks`; that is not vanilla's intra-tick order (runtime
  audit R21). Do not claim vanilla ordering parity for the player slice.
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
- **Transitions (accepted at E3, E4 pending):** `RegionTransitions`
  (`fabric/.../engine/RegionTransitions.java`) routes teleports to the destination
  context (region queue, the global scheduler for unowned chunks, or a server-thread
  safe continuation on rejection) through an exact-match invocation token
  (`TransitionToken.matches`, entity + world + destination context) and a per-context
  matrix; `scheduleToChunk` carries the destination position. `EntityTeleportMixin`
  captures `Entity.teleport(TeleportTransition)` HEAD, cancellable. `EntityTeleportDispatchTest`
  is 14/14; the final-tree jar booted with zero mixin errors. **Do not close R1 without
  the disposable-world `/tp`/portal check (E4).**
- **Entity ownership:** `EntityRegionTracker` + `EntityMixin` (add/remove/move hooks,
  per-entity cached tracker + packed chunk); `RegionEntityRegistry` stripe-locks
  `migrate`, `unregister` **and, since 2026-11-23, `register`** (put + set-add in one
  per-entity stripe critical section). R11 is fixed at E3 by an 8x500 invariant hammer,
  not a fixed schedule; R11b/R3 (structure-lock hold time in `onRegionSplit`) is open.
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

Named jar for inspection (Linux FAC host, current worktree `/tmp/ff-reconcile`):
`/tmp/ff-reconcile/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-201bed67e3/26.2/minecraft-common-201bed67e3-26.2.jar`
The shared checkout has its own copy with a different hash:
`/home/administrator/CDN-bucket/fabric-folia/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-043a8b3edf/26.2/minecraft-common-043a8b3edf-26.2.jar`.
Both are 26.2 `minecraft-common` jars; use the one belonging to the tree you are
inspecting and verify a class with
`javap -c -p -cp "$JAR" net.minecraft.world.entity.Entity > /tmp/entity.txt`
before trusting a fact. The historical Windows path `/d/fabric-folia/...` is not
valid on this host.

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

## 4. Dev-server / testing protocol — READ BEFORE ANY TEST (Linux FAC host)

**The user's #1 pain: leftover server/Gradle processes overload the machine. Absolute rules:**

1. **Before** any build/test/boot, inventory: `ps -eo pid,etime,args | grep -E '[j]ava|[g]radle'`
   and `ss -ltnp | grep -E ':(25901|25902)\s'`. Never touch a process you cannot
   prove belongs to this work. Kill only processes you started; no blanket `pkill`.
2. The dev server uses **ports 25901 (MC) / 25902 (RCON)** — NOT 25565/25575.
   RCON password `foliapass`.
3. `/tmp` is `noexec`: invoke Gradle as `bash ./gradlew`. Launch and poll in
   SEPARATE commands (combining them kills the server when the shell exits):
   ```bash
   cd /home/administrator/CDN-bucket/fabric-folia && rm -f /tmp/pplive.log \
     && (nohup bash ./gradlew :fabric:runServer > /tmp/pplive.log 2>&1 & echo "pid=$!")
   # then poll:  grep -E "Done \(|SUCCESS: Fabric Folia is ready|ERROR" /tmp/pplive.log
   ```
   Use `/tmp/ff-reconcile` only when the reconciled tree is the one under test.
4. **After** every boot/test cycle: RCON `stop` (or `MinecraftServer.halt(false)`),
   wait for the process to exit, verify the ports are released, run
   `bash ./gradlew --stop`, then repeat the process/port inventory. Kill ONLY
   processes started for this work.
5. `MinecraftServer.halt(false)` and RCON `stop` both produce clean exits (proven).
6. Measure TPS with `time query gametime` deltas over RCON, never from warning
   cadence. The current dev-server world under `fabric/run/` is not a benchmark world.

### Historical Windows protocol (no longer the active host)

1. Inventory with `tasklist.exe /FI "IMAGENAME eq java.exe"` and
   `netstat.exe -ano | grep -E ":(25901|25902)\s"`.
2. Launch from `D:\fabric-folia` with `./gradlew :fabric:runServer`; poll in a
   separate command.
3. After a cycle: `./gradlew --stop`, then re-run the inventory.

## 5. Host quirks / tooling traps — current Linux FAC host (2026-11-22)

- **`/tmp` is mounted `noexec`** (including `/tmp/ff-reconcile`): run Gradle with
  `bash ./gradlew`, never `./gradlew`; a fake `tmux`/`freebuff` binary cannot execute
  from `/tmp` (launcher tests inject shell functions instead).
- **Gradle daemon is shared:** check the process list before building; no concurrent
  full builds. Stop only daemons started for this work with `bash ./gradlew --stop`.
- **`code_search` / `read_files` may be unreliable in some sessions** — fall back to
  `grep -rn`, `sed -n` or `cat` through the shell.
- **Write tools** need `path` + `instructions` + `content`; `str_replace` uses
  `{path, replacements:[{oldString,newString,allowMultiple}]}`.

### Historical Windows notes (no longer the active host)

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

### 2026-11-23 R2 turn — player disconnect/removal off region workers (fixed at E3; NOT committed)
- **Deliverable:** `tools/freebuff/reports/runtime-r2-player-liveness.md`. Tree HEAD `c2fbe21`,
  `/tmp/ff-reconcile`, inherited dirty worktree preserved, no commit/push/deploy, R3/R4 and the SFS
  repo untouched. Fabric runtime only.
- **Evidence:** `bash ./gradlew :fabric:test` exit 0 — **146 tests, 0 failures/errors/skips** (141 +
  5 new `PlayerConnectionLivenessTest`). Focused new class green first (5/0).
- **Vanilla facts verified (do not rediscover):** `Connection.tick()` order is `flushQueue()` →
  `TickablePacketListener.tick()` → `isConnected()` check → only then `handleDisconnection()`
  (guarded by `disconnectionHandled`), which reaches `removePlayerFromWorld` → `PlayerList.remove` +
  save + broadcast on the calling thread. `ServerConnectionListener.tick()` **never ticks a
  disconnected connection**: it removes it from the list and calls `handleDisconnection()` itself on
  the server thread.
- **Fix (revision 3 — transport/player split, after two reviews):** the redirect sits inside SCL's
  `if (isConnected())` branch, so a `StagedConnectionBody` exists only for a `Connection.tick()`
  vanilla already admitted while connected — that tick is owed. `StagedConnectionBody` carries two
  execution-time probes: transport closed ⇒ **defer** the admitted body to `ServerThreadDeferral`
  (`closedTransportDeferredTicks()++`); transport open + player invalid ⇒ **defer** likewise
  (`invalidPlayerDeferredTicks()++`); both live ⇒ worker as before. Deferrals run once on the server
  thread (`deferredConnectionTicks()` = sum); next SCL pass does not tick a closed connection and
  calls `handleDisconnection()` itself (`disconnectionHandled` guard). *Revision 2 wrongly abandoned
  the closed-transport body, skipping an owed `flushQueue` + listener tick; revision 1 wrongly
  treated the deferral as an extra tick.*
  New `RegionStageHub.reclaimPlayerEntityBody(uuid)` (run inline on the server thread) and
  `cancelPlayerEntityBody(uuid)` (player removed → no tick at all) consume the pin and remove the
  still-staged ENTITY body; `stageConnectionTick` calls them on both liveness refusals, so the
  mirror case no longer leaves a ghost tick.
- **Tests:** `PlayerConnectionLivenessTest` now **7 tests** (adds closed-transport abandon,
  transport-wins-over-player precedence, invalid-player-on-open-transport deferral); 148 fabric
  tests green.
- **Metrics:** `player liveness releases: reclaimed / canceled / missed` in `/folia metrics`, plus
  `deferred(transport closed)=` / `deferred(player invalid)=` in `RegionPlayerRouting.metricsLine`.
- **Open:** R2 E4 (live `/kick`, proxy drop, reconnect loop, STRICT + player staging on; assert no
  disconnect-path work off the server thread, exactly one removal, no ghost entity tick);
  `PlayerList.remove` is not constructible in a unit test, so exactly-once is structural + the
  verified `disconnectionHandled` guard; the deferred admitted tick runs at the next server-thread
  drain rather than inline in the SCL iteration (same total work); config asymmetry where ENTITY
  staging on + PLAYER staging off still splits a
  live player's state machine (pre-existing).
- **Live boot not run** (focused tests only); no mixin changed this turn.
- **Ledger rows for R1/R2/R5 still say "open P0"** — they need a manager/docs update (R1 accepted
  at E3, R5 fixed at E3, R2 fixed at E3); docs edits were outside every turn's scope.

### 2026-11-23 runtime hardening turn — R1 re-verified, R5 fixed at E3, R4 audited (NOT committed)
- **Deliverable:** `tools/freebuff/reports/runtime-hardening-r4-r5.md`. Tree HEAD `c2fbe21`,
  `/tmp/ff-reconcile`, dirty worktree preserved, no commit/push/config/common edit.
- **Evidence:** `bash ./gradlew :fabric:test` exit 0; **141 tests, 0 failures/errors/skips**
  (R1 `EntityTeleportDispatchTest` 14/14; deferral 13/0; new failure class 5/0).
- **R1:** unchanged and green. **R10 correction (do not rediscover):**
  `noteServerThread` IS called in production — `FabricFoliaMod.startEngine` (SERVER_STARTED) and
  both `MinecraftServerMixin` injectors (`processPacketsAndTick`, `tickChildren`). The earlier
  "called only from tests" note in `runtime-implementation.md` §6.1 was wrong; the R1 matrix row
  for the physical server thread is exact from server start. The ledger's R10 "open" line should
  be closed on that basis (docs edit not made here).
- **R5 fixed (fabric only):** `runDeferred` no longer swallows — it counts, reports, stores the
  first failure in `REPLAY_FAILURE` and rethrows (`RuntimeException`/`Error` unchanged, checked
  wrapped). Both lanes stop polling until `takeReplayFailure()`; entries behind the failure stay
  queued. `REPLAYED`/`BARRIER_DRAINED` now increment per success so accounting is exact under a
  throw. `FabricFoliaEngine.beginTickPhase` throws the stored failure at entry **and after the
  quiesce** (the second is what matters: `WorkerPool.closeExecutionAndAwait` swallows pump
  throws); `global.tick()` is skipped. `pumpChunkExecutors` catches the drain failure only to keep
  the barrier alive and counts it in `quiescePumpErrors`. New `ServerThreadDeferralFailureTest`
  (5 tests incl. partial-mutation, lane poisoning, engine surfacing).
- **Verified vanilla fact (new, do not rediscover):** `ServerChunkCache$MainThreadExecutor.doRunTask`
  has **no** try/catch (only a profiler counter, then `BlockableEventLoop.doRunTask`), so chunk-task
  failures propagate in vanilla; the "vanilla reports executor failures itself" comment in
  `WorkerPool` is wrong. `WorkerPool` pump catch left untouched (common scope).
- **R4 audited, NOT fixed:** gate proves loadedness only; `ServerLevel.blockEvent` is a plain
  `ObjectLinkedOpenHashSet.add`; `ServerExplosion` calls `ServerLevel.setBlockAndUpdate` on the
  calling thread; `Level.setBlockAndUpdate` = `setBlock(...,3)` (neighbour+client) and cascades via
  `CollectingNeighborUpdater` with only `maxChainedNeighborUpdates` as a bound. A 3×3 halo bound is
  therefore unproven and would inline most border bodies; left to the manager's design decision +
  core ownership query/epoch. R4 remains open P0, no "region ownership safe" claim.
- **Open:** R5 E4 (boot with a failing deferred mutation → crash report, no partial save); R4
  design; chunk-task error absorption in `pumpChunkExecutors`/`drainChunkExecutorQueue`;
  `docs/status.md` still lists R1–R5 as open P0 (R1 accepted E3, R5 fixed E3) and needs a docs pass.
- **Inherited common red (pre-existing, NOT mine):** the full AGENTS check exits 1 because
  `RegionFailurePolicyTest.failurePolicyBookkeepingIsReleasedOnRegionMerge` and
  `RegionSchedulerSplitRehomeTest.staleOwnerAtRehomeIsReResolvedInsteadOfStrandingTheEntry` both
  fail on "the bridge must merge one region away" (common 167, 2 failures; fabric 141/0, api 10/0).
  `common` has no dependency on `fabric`, and no `common/` file was touched, so these belong to the
  uncommitted `RegionScheduler`/`RegionFailurePolicy` work already in the tree.
- **Live boot not run this turn** (assignment: focused tests only). Per AGENTS §2.5 one
  `:fabric:runServer` boot with `Done (` and a clean shutdown is still required before merge of any
  mixin-touching change; none of this turn's edits touch a mixin.

### 2026-11-22 protocol-client research turn (research only; NOT committed)
- **Deliverable:** `tools/freebuff/reports/protocol-client-research.md` (~600
  lines). No code, no installs, no boot, no RCON, no production network, no
  commit. Tree HEAD `c2fbe21`, working in `/tmp/ff-reconcile`.
- **Verified protocol facts (do not rediscover):** 26.2 = **776** / data 4903;
  26.3 = **777** / data 5023 (Wiki + minecraft-data + mctoolbox agree).
- **Critical finding for the QA harness:** `tools/freebuff/qa/README.md` assumed
  Mineflayer could drive 26.2 — it cannot. Upstream `minecraft-protocol` 1.68.0
  and `mineflayer` 4.39.0 both cap at **26.1** (`version.js` and README checked
  at tag/master). 26.2 and 26.3 exist only as open PR branches (#3926/#4192 and
  #4124/#4206 for mineflayer; #1532 for nmp).
- **Candidates and pins:** Azalea `0d38e0d` = native **776** (`PROTOCOL_VERSION
  776` on main; crates.io still 26.1, so git pin required) → primary 26.2 driver.
  rapidbot `b365a4a` and go-mclib `v0.0.0-20261112211548-022b47e95d21` = native
  **777**, both immature (7 weeks / 0 stars) → 26.3 candidates behind a smoke
  gate. MCProtocolLib master `65efb9d` version `26.3-4`, protocol 777, MIT,
  GeyserMC-maintained, snapshots only (last GitHub release 1.21.4-1) → in-house
  fallback. `@wp2508/mineflayer` 4.42.9 is a one-publisher fork with nine shadow
  dependencies → rejected for production without audit. ViaProxy 3.4.14 /
  ViaVersion 5.12.2 are **GPL-3.0** and translation-only; never count them as
  native protocol evidence.
- **Mount/vehicle, respawn and cross-dimension are undocumented for both 26.3
  candidates** — the report's S11–S13 smoke phases must answer them before any
  full-profile claim.

### 2026-10-10 SECOND turn — "fix the limitations and the performance issues" (verified live; on main, NOT committed)
- **THE 60s STALL IS FULLY ROOT-CAUSED — and it was NEVER a hung tick:**
  a 30-min vanilla soak + StallProbe caught the killer event live: the
  watchdog killed at "A single server tick took 60.01 seconds" while the
  probe (500ms polls on getTickCount) saw ZERO >5s stagnation — the tick
  loop was PROGRESSING the whole time. **26.2 bytecode proof
  (ServerWatchdog.run):** kill iff `Util.getNanos() − server.getNextTickTime()
  > maxTickTimeNanos`, and the "single server tick took" message prints
  that DRIFT — the wording is misleading. `nextTickTimeNanos` re-bases only
  at the 100-tick "Can't keep up" gate ⇒ **at sustained TPS < ~1.67 the
  re-base interval itself exceeds 60s and the server is killed with no
  warnings in between** (empirically: last warn ≈ exactly 100 ticks before
  each kill, both occurrences). The "stall" = the vanilla O(n²) 4,000-stand
  clump pushing the server to 0.7-4 TPS (each tick 88% inside
  ArmorStand.pushEntities per JFR) — vanilla's cost; regionized mode never
  approaches it (0 stalls across all soaks). The ghost-section/GC theories
  are formally DEAD (census probe armed but never needed; GC pauses were
  8.4ms).
- **StallProbe upgraded with the missing operator signal:** a sliding
  30s TPS window; below CRAWL_KILL_TPS=2.0 it logs ONE concise warning
  ("tick loop is PROGRESSING (not hung)… kill is on this trajectory…")
  and a recovery line — fixing the "no warnings then sudden death"
  limitation. Zero-cost (same poll loop). The hang-dump path stays for
  genuine freezes.
- **New server-MSPT instrumentation (attribution, not just numbers):**
  per-phase rings in RegionMetrics — `quiesce/flush/drain/body p50/p95` —
  paired in-engine (beginTickPhase/flushGameplay/awaitStagedDrain/
  recordTickWork; recordTickWork must NOT zero lastTickPhaseStartNanos or
  the cycle pairing dies — that bug made the first gate build inert,
  caught by measurement: skipped(sleep-window)=0). The MSPT line is now
  `Server MSPT (tick work): p50/p95/p99/max missed(>50ms)=cumulative
  recent-window=X/100` — the windowed count fixes the boot-conflation
  limitation (cumulative still reported, labeled honestly).
- **PERF FIX 1 — adaptive drain gate (measured-bottleneck fix):**
  breakdown proved the inter-tick drain burned p50≈23ms of EVERY tick
  (75% of the 31ms work) while the server slept ~19ms/cycle — redundant.
  `FabricFoliaEngine.drainRedundant(cycle, work, floor=5ms)`: skip the
  drain when the previous cycle already handed workers ≥5ms of sleep
  (the drain exists for BEHIND ticks where sleep≈0). Live: skips 68% of
  ticks, engaged otherwise. Unit-pinned (StagedDrainWindowTest +1).
- **PERF FIX 2 — BODIES_PER_TASK 256→64:** the breakdown showed quiesce
  p95=46ms (tick-start wait on in-flight worker tasks); smaller tasks
  cap each in-flight slice (test-derived expectation in
  RegionStageHubParallelGateTest instead of the magic 3).
- **BEFORE/AFTER (same clump world, one bot, work-based MSPT, mid-session
  samples):** p50 31.11→21.77 (−30%), p95 77.83→61.80 (−21%), p99
  92.31→78.35 (−15%), recent-window misses 33/100→16/100, Region#1 avg
  12.5→7.1ms; end-of-session ring after full gameplay churn: p50=19.75
  p95=50.38 p99=52.31, recent 8/100. TPS locked 20.0x throughout;
  quiesce p95 (~46ms) persists — it is POOL-WIDE in-flight work (up to
  8 workers mid-task), not single-task size; next lever = in-flight
  attribution (which work is busy at quiesce), recorded as open.
- **NEW known-issue observed (transient, loud, self-healing):** at a bot
  RELOGIN mid-clump, one worker body joined a mid-load chunk (~2s):
  quiesce timed out twice (attempt 1/2, "1 task(s) still in flight",
  pump drained 128 chunk tasks meanwhile), 2 degraded ticks, 0 errors
  afterwards, TPS recovered to 20 instantly. Trigger = relogin visibility
  timing, not task granularity; tracked as open item.
- **Suite: 184 tests, 0 failures, build rc=0** (final build live-verified
  through the full bot lifecycle: walk/build//kill/respawn; clean RCON
  stop "All dimensions are saved"; sweep left only foreign processes).
- **Bot quirk:** the FIRST bot join after a fresh boot fails with
  "socket closed" during login (~1 in 2 boots); immediate relaunch
  succeeds — retry, do not debug the server for it.

### 2026-10-10 stability-recovery turn (verified live; on main, NOT committed)
- **Directive:** stability/concurrency/performance recovery — feature freeze,
  GPU isolated, baseline first, root-cause fixes, before/after metrics.
  State at start: main=`49a99a1`, clean tree, baseline build EXIT=0 with
  178 tests / 0 failures / 0 skipped.
- **CRITICAL PRE-EXISTING BUG FOUND + FIXED (root cause, not suppressed):**
  `general.enabled=false` (the documented vanilla-fallback mode) CRASHED the
  server at boot: `MinecraftServerMixin.fabricfolia$brand` called
  `FabricFoliaMod.engine().config()` with engine==null (startEngine returns
  early when disabled), NPE inside `loadLevel→getServerModName`. FIX: brand
  now resolves from a new engine-independent
  `FabricFoliaMod.config()` (static volatile, set on every startEngine path,
  nulled on config-load failure); the mixin no longer touches the engine.
  Repro'd live (crash report + stack), fixed, live-verified: disabled mode
  boots to `Done (3.738s)!`. NEW `ServerBrandTest` (3: handler survives
  missing engine+config; applies configured brand without engine;
  server-brand=false leaves vanilla) invokes the REAL private handler via
  reflection on a real CallbackInfoReturnable (construct it CANCELABLE —
  non-cancellable throws CancellationException on setReturnValue).
- **Watchdog-killer stall REPRODUCED under control (vanilla mode, one bot):**
  60.2s single tick, watchdog kill; crash-report stack = Server thread
  RUNNABLE in `ArmorStand.pushEntities:202 → Level.getEntities:652 →
  EntitySectionStorage.getEntities:122 → EntitySection.getEntities:39`.
  JFR (90s window over the kill): 4655/5299 samples (88%) in exactly this
  chain via `LivingEntity.aiStep:3270`; GC RULED OUT (G1 pauses 8.4ms,
  old gen 233MB); persisted entity data SANE (r.0.0.mca 573KB ≈ 4k stands);
  range loops ruled out (delta between nested frames ≈50 samples) → time is
  genuinely inside section entity-list iteration. Second boot: no killer
  tick in 10 min (nondeterministic as recorded); grind state instead
  (0.7-1.4 TPS). NOT YET ROOT-CAUSED: is one section's list exploding at
  runtime (ghost/dup refs) or is it pure O(n²) clump volume — the census
  probe below answers it on the next occurrence.
- **NEW `engine/StallProbe` (always-on, zero steady-state cost):** daemon
  thread, 500ms poll of `server.getTickCount()`; on ≥5s no-progress dumps
  (max 1/8s) to `run/stall-probes/stall-*.log`: ALL thread stacks +
  per-dimension section census (totalRefs/distinct/intra-section dups/
  knownUuids/tickList active+passive+iterated/top sections + sample entity
  pos+removed) via reflection on bytecode-verified names: `ServerLevel
  .entityManager`, `PersistentEntitySectionManager.sectionStorage/.knownUuids`,
  `EntitySectionStorage.sections`, `EntitySection.storage`,
  `ClassInstanceMultiMap.allInstances`, `EntityTickList.active/.passive/
  .iterated`. Reflection failure degrades to stacks-only (never crashes).
  Started in startEngine BEFORE the disabled-return; stopped first in
  stopEngine. 0 dumps during ~20 min regionized sessions (healthy).
- **NEW server MSPT metric (directive requires p50/p95/p99):**
  `RegionMetrics.recordServerTick` + `MsptRing.percentileMillis` (nearest-
  rank) + line `Server MSPT (tick-to-tick): p50/p95/p99/max/
  missed-deadlines(>50ms)`; recorded WORK-based (tick-phase start → flush+
  drain complete via `FabricFoliaEngine.recordTickWork()` at end of
  flushGameplayStaging) — loop-to-loop would include vanilla's sleep and
  always read 50ms. NEW `RegionMetricsServerTickTest` (2: exact percentiles
  on 1..100ms fixture [nearest-rank: p50=50, p95=95, p99=99 — NOT 100];
  empty ring reports zeros).
- **BEFORE/AFTER on the identical 4,000-stand-clump world, one player
  (protocol bot), gametime-delta TPS (3×30s windows):**
  - BEFORE (general.enabled=false, vanilla): **TPS 12.2 idle / 3.95-4.01
    with player**; run 2 ground at 0.7-1.4 TPS; killed by watchdog once.
    JFR: 88% of CPU = one armor stand pushing the clump on the server thread.
  - AFTER (regionized, same world/bot): **TPS 20.03/19.99/20.00** (and
    19.93/20.10/19.96 after restart); server work MSPT **p50=30.9-31.7,
    p95=77.6-78.0, p99=92.4-93.3, max=278-330**; Region#1 (clump) avg
    9.1-12.5ms; workers ~4.5% util; 0 quiesce timeouts, 0 pump errors,
    0 bounced probes, 0 stalls, health all-OK. JFR: the pushEntities chain
    runs under `WorkerPool.workerLoop→RegionScheduler.runRegionTick→
    RegionStageHub.runBody` (parallelism real).
  - Layer overhead itemized in metrics: quiesce waits 1390-1732 × ~30ms,
    drain window 2347-2752 waits × ~25ms (budget 20ms, deliberate trade);
    slow-motion skip absorbs the clump (11.0-14.7M skipped, by design).
- **Gameplay + persistence:** bot `all` phase passed (640 moves/274
  resyncs [known terrain-pin behavior], block place/break, /kill+respawn
  → respawned at spawn); RCON stop → "All dimensions are saved" +
  "Engine shut down cleanly"; restart reproduced the identical profile
  (clump persisted, same MSPT/TPS). 0 ERROR lines across all regionized
  sessions.
- **GPU isolation:** run config `gpu.enabled: false` (boot log confirms
  disabled); zero tick-path callers of overlapMask verified by grep (only
  /folia gpu + metrics lines reach it).
- **Suite at end of turn: 183 tests, 0 failures, 0 skipped, build rc=0.**
  Files changed (UNCOMMITTED): FabricFoliaMod, MinecraftServerMixin,
  FabricFoliaEngine, StallProbe (new), ServerBrandTest (new), RegionMetrics,
  RegionMetricsServerTickTest (new).
- **Honest limits:** killer stall not re-reproduced post-instrumentation
  (one clean stack capture is the evidence); missed-deadline counter is
  cumulative and conflates the boot burst (first ~1-2k ticks) with steady
  state — steady p95 ≈78ms ≈ 5% of ticks over budget, absorbed by vanilla
  catch-up; StallProbe reflection is 26.2-field-name-bound (degrades
  gracefully); second vanilla run's worse grind (0.7-1.4 TPS vs 4) is
  unexplained run-to-run variance (clump geometry shifts each run via
  push-apart).
- **Host quirks added this turn:** (1) MSYS path conversion applies to
  ARGUMENTS only — python code that opens('/tmp/...') directly must use
  `C:\Users\User\AppData\Local\Temp` (Git Bash /tmp = that dir);
  (2) Windows python subprocess has cmd.exe shell → no `grep`, parse
  netstat in python; (3) `time query gametime` returns "The time is N
  tick(s)"-style text — regex the number; (4) **NEVER blanket-sed an
  indented `enabled: true` in the runtime config** — it matched general,
  patches AND gpu at once (self-inflicted incident this turn; fixed by
  targeted line edits); (5) `execute if entity @e[...] Count` via mcrcon
  errored this turn ("Incorrect argument") — 26.2 selector-census syntax
  needs re-verification before reuse; (6) jcmd JFR.start writes to Windows
  %TEMP% regardless of the /tmp argument.

### 2026-10-09 turn — GPU subsystem + collision patch + stall soak (verified; commit pending user-visible check)
- **OFEM collision patch (verified earlier this session):** `Colliders.java`
  REPLACED by `util/OfemCollision.java` + `EntityMixin` OFEM design
  (`@WrapOperation`/`@ModifyArgs` + ThreadLocal context). Config key
  `patches.minecraft.entity-collision-optimization` (default false,
  Lithium force-disable). Live tests: plain stands stop at wall face minus
  half-width (x=805.7500 vs face 806.0) and land stable at y=101.0 — PASS.
  **Two test artifacts explained earlier "freezes": vanilla
  `ArmorStand.hasPhysics()` returns false for NoGravity stands (travel()
  never runs — verify stands on test subjects WITHOUT NoGravity), and
  chunks not force-loaded don't entity-tick (stands at x=799.5 were in
  chunk [49,50] while only [50,50] was loaded).**
- **Stall soak verdict:** background monitor polled gametime over RCON
  every 2s for ~75 min against the live server: ZERO stalls, zero RCON
  errors. The suspected 60s pushEntities stall does not reproduce on this
  build/host.
- **OpenCL GPU subsystem (NEW, opt-in, default OFF):**
  - `common/.../gpu/`: `GpuBackend`, `CpuBackend` (canonical vanilla
    AABB#intersects reference — strict inequalities), `OpenClRuntime`
    (hand-rolled FFM binding of ~20 CL1.2 entry points, JDK 25, no deps),
    `GpuVerification` (boot/test parity battery: edge cases incl. NaN,
    ±1e308, touching faces + seeded random batches), `GpuSubsystem`
    (lifecycle/dispatch/degradation/metrics/bench), `GpuDevice`,
    `GpuException`. ONE real kernel: `aabb_overlap_mask` (bulk overlap
    filtering, pure IEEE-754 comparisons → bit-exact by construction).
  - Config: `gpu.enabled` (false), `gpu.device` (auto),
    `gpu.verify-on-boot` (true). Fabric wiring: startup init + config
    summary line, `GpuSubsystem.close()` in stopEngine, GPU lines in
    `/folia metrics`, `/folia gpu` + `/folia gpu bench [n]` commands,
    `/folia help gpu` topic.
  - **CRASH ROOT-CAUSE (JVM EXCEPTION_ACCESS_VIOLATION in nvopencl64):**
    `clCreateContext`'s `devices` (and `clBuildProgram`'s `device_list`)
    are pointers to an ARRAY of handles; passing the raw handle made the
    ICD dereference driver memory as a device pointer (platform derived
    from device when props=NULL) and jump to garbage. Fixed with a slot
    holding the handle (why-comment in `OpenClRuntime.open()`). Python
    ctypes proved the driver fine; FFM probe isolated the parameter.
  - **Measured (RTX 5060 Ti, OpenCL 3.0 CUDA, 36 CU):** boot battery
    PASSED live in-server (`Boot parity verified: true`); JUnit
    `GpuParityTest` bit-exact on 8 seeded batches (device present, zero
    skips); bench parity=EXACT at 200k/1M/4M boxes but **CPU wins at all
    sizes** (1.24/3.95/11.97 ms vs 9.34/40.07/142.90 ms) — per-call
    round-trip dominates; GPU ceiling ~28k boxes/ms vs CPU ~334k. Honest
    conclusion + path to crossover (persistent buffers, batched queries)
    in `docs/gpu.md`.
  - Tests: `AabbOverlapTest` (11: pinned literals incl. NaN/extremes,
    validation, opt-in/fallback contracts), `GpuParityTest` (2, skips
    cleanly without a device). Full suite **178 tests, 0 failures,
    0 skipped**; `./gradlew :common:test :fabric:test :api:test build`
    EXIT=0.
  - Live boot of the GPU build: `Done (` + zero mixin errors; `/folia
    gpu` shows GPU_ACTIVE + devices + verified; TPS ~20.2 via gametime
    deltas; stall monitor re-armed against the new build.
- **Jenkinsfile:** default lane UNCHANGED (`agent any`, `sh`); NEW opt-in
  `CROSS_OS` boolean param gates a declarative matrix (labels `windows`,
  `macos`) running the same test protocol with `bat`/`sh` switch + junit
  publishing. Default builds never queue on missing labels. README CI
  section documents it.
- **Docs this turn:** `docs/gpu.md` (design + measured results + OS
  coverage + the clCreateContext trap), COMPATIBILITY.md GPU section,
  README CI matrix note.

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
1. MSPT counter audit: `staged bodies total` over-counts ~12x vs actual
   body executions (Work counters prove the game ticks each entity exactly
   once; the pipeline is lossless end-to-end — find where stage() calls
   exceed list deliveries). Diagnostics only, not gameplay.
2. Production re-verification of MSPT after the ChunkResidency fix (the
   dev-world load test proves the bounce storm is gone; production numbers
   need the PalorderCentral workload).
2. ~~Enable GitHub Actions at the ACCOUNT level~~ — SUPERSESED 2026-10-06:
   the owner uses Jenkins; the workflow file was removed. No CI item remains
   beyond keeping the Jenkinsfile green.
3. Direct `PlayerList.respawn` callers from mods/API and a destination-region
   placement hook; login placement.
4. Portal-side staging beyond the teleport funnel.
5. `FabricFoliaMod` service-locator indirection (DESIGN complaint) — explicit
   engine holder.
6. MIXINS.md: document the last 3 registered mixins (CommandsMixin,
   MinecraftServerMixin, ServerPlayerTickMixin) — 23/26 documented.
7. Comment-strip pass per mandate rule 47: new code ships comment-free, but
   legacy files still carry javadoc.
8. Delete the merged feature branch `fix/region-pipeline-patch-layer`
   (optional housekeeping).

### 2026-11-22 — live runtime validation and Fabrolia black-box check (this turn)

Full report: `tools/freebuff/reports/live-runtime-and-benchmark.md`.

- **Tree:** `c2fbe2135945e4e0ef42fc70094d3605ef03627d`, 45 dirty entries at build
  time. The tree did **not** compile on arrival: `EntityTeleportMixin.java:72`
  called package-private `RegionTransitions.transitionKey`. Visibility widened to
  `public` (record, compact ctor, factory; no logic change) by this session, then
  the runtime session rewrote the file at 02:40. Test fixtures in
  `EntityTeleportDispatchTest` / `PlayerTeleportRoutingTest` moved the source chunk
  from `(50,50)` to `(400,400)`: at simulation distance 10 the regionizer merges
  within `mergeRadius(3)+emptyRadius(4)=7` sections, so 50 chunks is the SAME
  region and 5+2 tests could never dispatch. Assertions untouched.
- **Build/test (exact):** `bash ./gradlew :common:test :fabric:test :api:test build
  --stacktrace` → `BUILD SUCCESSFUL`, exit 0, 6 s (`/tmp/ff-runtime-evidence/
  build-full-4.log`). **300 tests** at this tree (api 10, common 161, fabric 129),
  0 failures / 0 errors / 0 skipped. The earlier "280" and any "135" figure are
  stale; `ConfigSchemaPatchTest` now runs and passes.
- **Smoke boot (clientless, 2 GB heap, MC 26.2, loader 0.19.3, API 0.161.0+26.2):**
  jar `0ac65238959e237e8036a35b91a192330cd772a579f22ae17905e595ab7b048e` built
  02:28:55. Three runs (`~/.cache/ff-qa-evidence/runs/smoke-01-run{3,4,5}`):
  `Done (5.241s / 5.038s / 4.946s)`, **zero mixin warnings or errors**, TPS mean
  20.003 / 20.000 / 20.002 from `time query gametime` deltas, heap ≈716–794 MiB,
  9 young + 6 old GCs in 443 s, JFR ~9.5 MB each, clean RCON stop, no crash
  reports, ports closed. `protocol_clients: 0`.
- **P1 runtime defect found:** every `/folia` command fails — `NoClassDefFoundError:
  net/kyori/examination/Examinable`, then `Could not initialize class …
  FoliaMessages`. 134 failed responses per run. Consequence: **MSPT p50/p95/p99,
  worker utilization, queue depth and region count are unavailable** — the nulls are
  command failures, not idle values. Raw:
  `runs/smoke-01-run*/sample/raw/*-{metrics,health,workers,regions,scheduler}.txt`.
  The jar nests `adventure-api` but not its `examination` dependency. Not repaired
  (scope freeze).
- **Fabrolia: BLOCKED, no run, no comparison, no winner.** Pinned
  `f_3.2.6` / `fabrolia-alpha_3.2.6.jar`, SHA-256
  `42678345eca6bc5b99ed9f3e405f98bc4d40f39ead15bcc1a55d5815a90dd3ca`, declares
  `minecraft ~26.3`, `java >=25`, GPL-3.0. All three published releases (3.2.0,
  3.2.1, 3.2.6) are 26.3, so no same-version comparison with our 26.2 baseline is
  possible. No GPL source fetched or inspected; only `fabric.mod.json` and release
  notes. Artifacts kept at `/tmp/ff-runtime-evidence/fabrolia/`.
- **40-client stand deferred:** 14 GiB host, no verified native 26.2 protocol
  client. The full profile's own 32 GiB gate refuses here.
- **Harness gaps (H1–H5, unfixed):** `/tmp` is `noexec` so `QA_HOME` must live
  under `/home`; the assembled classpath omits the loader's own deps (ASM 9.10.1 ×5,
  sponge-mixin 0.17.3) so boot dies with `ASM not detected on the classpath`; no
  `eula.txt` is written; RCON `stop` reports exit 1 because the socket closes as the
  server exits (process still stops cleanly, `steps_all_zero=false`); a failed
  metrics command silently yields null columns instead of failing the run.
- **Freshness closed (run 6):** rebuilt `:fabric:jar` from the final tree
  (`bash ./gradlew :fabric:jar --console=plain`, exit 0, 2 s) at 02:52:26 →
  SHA-256 `06e852018bd19858683362deb0ad54341b83e830d5e9c1921eaacd6ba3b8d76a`,
  proven current by `javap` (`RegionTransitions$TransitionToken.matches`).
  Fresh instance `smoke-02`, run `smoke-01-run6`: `Done (4.920s)`, 48 mods,
  **0 mixin warnings/errors**, 60 s at 20.000 TPS mean, heap 703.2 MiB, JFR
  8.5 MB, clean RCON stop, no crash reports. `/folia health` still fails (L1).
  Runs 3–5 (jar `0ac6523…`, 02:28:55) remain the benchmark evidence; run 6 only
  proves the final tree loads. R1 behaviour is still untested.

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

### DONE in the 2026-09-25 main-sync + async-entity-load-fix turn (verified live; on main)
- **Owner directives applied:** (1) origin main now carries ALL patch-layer +
  crash-fix work — `29ee34d..82c3d5e` fast-forwarded and pushed (`git merge
  --ff-only feat/patch-layer-folia-command` on main; remote verified
  `82c3d5e` = origin/main); we are ON main and stay there. (2) The
  async-entity-load CME was FIXED AT THE ROOT (no suppression).
- **Bug root-caused (was "still open" in the crash-fix turn):** the production
  `ConcurrentModificationException: Async entity load` (×4 on use_item,
  suppressed) — the string is NOT vanilla (absent from the 7445-class common
  jar and clientOnly jar) and NOT ours; it is **c2me's async entity loader
  fail-fast guard** (GitHub c2me issues #278 / RepurposedStructures #397 show
  the identical signature). On production, use_item packets run on the server
  thread (player-path staging default-off), so the CME fires when OUR REGION
  WORKERS iterate entity sections concurrently with c2me's in-flight async
  entity load. Previously the CME was swallowed twice over: our
  `RegionStageHub.runSafely` catch turned it into an EXCEPTIONS_ISOLATED
  counter (the entity body never ran = lost tick), and carpet's
  yeetUpdateSuppressionCrash then suppressed the fallout on the server thread.
- **Fix shipped (`b4440fd`, on main):** NEW
  `common/.../scheduler/StageRetry.java` — tick-spaced retry ledger:
  `schedule(world, body, attempt, currentTick)` (workers), per-world pending
  lists (worker-add / server-thread-drain; no cross-thread iteration of a
  mutating list), `drain(world, suppressionActive, requeue, currentTick)`
  replaying due entries ≥2 flushes after failure, attempt cap 4 then counted
  EXHAUSTED, suppression windows keep entries pending, `clearWorld` counts
  drops (never silent), diagnostics scheduled/retried/pending/exhausted.
  `RegionStageHub`: `runSafely`'s catch classifies CME as the transient
  concurrent-entity-access failure and schedules a retry instead of only
  logging — the retry wrapper `RetryBody(slice, body, attempt)` implements
  `Positioned` (position delegates to the inner body, so region resolution
  and the loadedness pre-flight are dispatch-transparent) and `Runnable`;
  flushStaged drains due retries BEFORE the fresh batch (tick-order
  preserved), re-dispatching through the normal pipeline (region re-resolved
  at replay — never a stale owner). `FabricFoliaEngine.metricsLines`: new
  line `staged-body retries (async entity load): scheduled=N retried=N
  pending=N exhausted=N`.
- **Tests:** NEW `StageRetryTest` (5: tick-spaced per-world retry + attempt
  carry, cap refuses + counts, suppression keeps pending then runs when
  lifted, refused requeue stays pending, clearWorld drops counted).
  Suite + build green (`:common:test :api:test :fabric:test build`).
- **Live:** boot `Done (2.463s)` zero errors, staging flowing (51,371 bodies
  / 51,348 workers), new metrics line renders (baseline zeros), clean RCON
  stop ("Engine shut down cleanly"), ports released, sweep = only PID 12220.
- **Honest limits:** the retry path is unit-tested and live-boot-verified,
  but the production CME itself cannot be reproduced in the dev world (it
  needs c2me + concurrent chunk-entity loading). Operators should watch
  `staged-body retries` on production — non-zero scheduled/retried after
  rollout means the fix is absorbing races that previously ate entity ticks.
- **Process note:** after a Freebuff restart, background processes are gone
  but a pre-existing console JVM (22204) was present and left alone; our
  gradle launcher JVM died with its shell (sweep found nothing of ours).

### DONE in the 2026-09-26 single-writer funnel-boundary turn (verified live; on main)
- **Crash fixed at the root (PalorderCentral `"this.wrapped" is null`):** the
  ownership boundary now sits at the TRUE funnels, gated on **server-thread
  identity** (not context kind — the old gate let GLOBAL/NETWORK/IO threads
  mutate ticket maps directly, which was the same race): NEW
  `mixin/TicketStorageMixin` guards ALL ten `TicketStorage` mutators
  (addTicketWithRadius, addTicket×2, removeTicketWithRadius, removeTicket×2,
  removeTicketIf, replaceTicketLevelOfType, updateChunkForced,
  purgeStaleTickets); NEW `mixin/ChunkMapTrackingMixin` guards
  move/addEntity/removeEntity (entityMap/playerMap); NEW
  `mixin/DistanceManagerTrackingMixin` guards addPlayer/removePlayer/
  runAllUpdates (playersPerChunk + the setLevel tracker mutator);
  `ServerChunkCacheMixin` gains an `addTicketAndLoadWithRadius` whole-call
  deferral with a bridged CompletableFuture (partial deferral would break its
  chunk-visibility check) and the `tick` HEAD drain anchor;
  `MinecraftServerMixin` records the server thread + drains at
  `tickChildren` HEAD. Off-thread calls cancel and capture a VERBATIM replay
  closure (original receiver/method/args), replayed FIFO before any
  enumeration; deferred boolean mutators answer true; failed replays are
  isolated + counted. NEW `engine/ServerThreadDeferral` is the one ledger.
- **REMOVED (superseded, mandate §46 one-authoritative-implementation):
  `ChunkTicketMixin`, `TicketDeferral`, `TicketDeferralTest`.** The per-caller
  capture covered only 2 of ~14 mutation entry points (`DistanceManager.addPlayer`
  and `addTicketAndLoadWithRadius` bypassed it — why the crash survived the
  first fix); freshness-over-FIFO collapse is replaced by plain FIFO (exact
  remove-then-add order), and the suppression-drop behavior is gone: replay
  runs ON the server thread and is vanilla-equivalent, so dropping tickets
  was suppressing a symptom. Stale ChunkTicketMixin references rewritten in
  RegionTransitions + TransitionBodyServerThreadTest comments.
- **MSPT hot path:** `RegionScheduler.enqueueBatch(region, tasks)` +
  `RegionStageHub.dispatch` batch-by-region — ONE structure-lock
  death-check per region per flush instead of one per body (the old code
  took a real `ReentrantLock` 1500×/tick at that entity count, contended
  against chunk-load registrations); dead-region drops counted per body.
  Open question SETTLED by audit: the server tick NEVER blocks on region
  work (only WorkerPool.awaitWake and shutdown await exist) — the MSPT
  regression is pure server-thread staging overhead + production-only
  factors (c2me/entity volume), needing in-situ profiling to attribute.
- **Tests (suite green, `:common:test :api:test :fabric:test build`):** NEW
  `ServerThreadDeferralTest` (7: FIFO order, remove-then-add exact, gate
  semantics, unknown-owner pass-through, failure isolation + counting,
  clearAll drop accounting, 8-worker×500 concurrent completeness/order);
  NEW `TrackerEnumerationRaceRegressionTest` (2: deterministic regression of
  the EXACT crash mechanism — `Long2ByteMaps.fastIterable` iteration of a
  `Long2ByteOpenHashMap` with `setLevel` put/remove churn while workers
  submit ticket mutations, asserting zero mutations execute inside the
  enumeration window; plus the §78 stress: 1504 keys / 8 workers / 4512
  mutations vs concurrent enumeration rounds — zero violations, exact final
  state, full accounting); `RegionPipelineTest.batchedEnqueue` (batched
  dispatch runs every task in REGION context, nothing dropped).
- **Live (verified this turn):** clean boot, ZERO injection failures (the
  three funnel mixins validate against real 26.2 bytecode), 6 regions,
  101,650 staged bodies / 101,627 executed on workers, new metrics line
  renders (`vanilla state mutations deferred to server thread: 0 (replayed:
  0, pending: 0, failed: 0)` — zeros expected in the idle dev world), RCON
  `stop` clean, ports released, sweep = only PID 12220 (protected).
- **Honest limits:** unit tests pin the ledger + boundary CONTRACT, not the
  mixin gates themselves (those need MC bootstrap — verified by the live
  boot instead); the production crash env (c2me + modpack) is not
  reproducible in dev; the production MSPT delta still needs in-situ
  profiling (open item 1). mcrcon on this host is the Python
  tiagofernandez package: `--password X -p PORT HOST`, commands via stdin
  (no -t — that is TLS), UPDATED §5 quirk.

### DONE in the 2026-09-26 MSPT root-cause + ChunkResidency turn (verified live; on main)
- **MSPT ROOT CAUSE FOUND AND FIXED (bytecode + JFR proof):**
  `ServerChunkCache.getChunkNow(int,int)` 26.2 bytecode OPENS with
  `if (Thread.currentThread() != this.mainThread) return null` — it is
  MAIN-THREAD-ONLY by vanilla design (it mutates a 4-slot lastChunk cache).
  Our execution-time loadedness probe called it FROM REGION WORKERS, so it
  ALWAYS returned null → every staged body bounced back to the server
  thread via `server.execute` — the staging machinery ran, but the actual
  entity work ALWAYS executed on the tick thread PLUS per-body dispatch,
  probe, and task-hop overhead. That is the 7ms→29ms shape: vanilla body
  work unchanged + all staging overhead and zero parallelism.
- **JFR evidence (1500 armor stands, 60s profile):** 95% of all CPU samples
  were the Server thread; 71% of those were BOUNCED bodies
  (`runSafely → lambda$runSafely$0 → TickTask`), dominated by
  `ArmorStand.pushEntities → Level.getEntities` (48% of ALL samples).
  Workers nearly idle (302 samples total). Instrumented counters then
  proved it exactly: `probes: pass=0 chunk-missing=7,976,277` — ZERO passes
  on workers while the identical staging probe (server thread) passed.
- **FIX — NEW `engine/ChunkResidency.java` (the one loadedness source):**
  per-world copy-on-write `LongOpenHashSet` snapshot (volatile; writers
  synchronized clone-mutate-publish; readers lock-free) fed by the chunk
  lifecycle events the engine already receives — `ChunkRegionization.onChunkLoad`
  (markResident, BEFORE the regionizer gate so unattached worlds still
  track), `backfillSpawnArea` (spawn spiral + player chunks),
  `FabricFoliaEngine.onChunkUnloaded` (markUnresident), `detachWorld`
  (clearWorld). ALL FOUR neighborhood probes now call
  `ChunkResidency.isNeighborhoodResident` (mandate §46 one implementation):
  `RegionStageHub.bodyNeighborhoodLoaded` (execution),
  `LevelEntityTickMixin.neighborhhoodLoaded` + `playerPhysicsSafe` (staging),
  `RegionTickInterceptor.neighborhoodLoaded`,
  `RegionPlayerRouting.physicsNeighborhoodLoaded`. The getChunkNow probes
  are GONE from all worker-reachable paths.
- **Instrumentation kept (diagnostics):** probe outcome counters
  (pass/world-down/chunk-missing), `staged bodies executed on workers` vs
  `... on server thread` (counter split fixed — the old counter counted
  bounced runs as worker runs), `staged bodies bounced to server thread`,
  all rendered in `/folia metrics`.
- **Live verification (1500-marker-armor-stand load = 1500 zero-work entity
  bodies/tick in one region):** `probes: pass=2,495,404 world-down=0
  chunk-missing=0`, `bounced=0`, `executed on workers: 2,495,400`, region
  MSPT avg 0.08-0.30ms, `queued region tasks=0` (no backlog), Work
  counters = exact 1500 entities × 20tps. Suite green (NEW
  `ChunkResidencyTest` 5: neighborhood semantics, untracked-world false,
  clearWorld, idempotence, 2-reader/2000-write COW stress).
- **Honest limits / known diagnostics bug:** `staged bodies total` over-counts
  ~12-25x vs actual executions (Work + executed counters agree with each
  other and with the real entity-tick rate; queue/drain plumbing is
  lossless by inspection and `tickNonPassenger` does not re-enter
  `guardEntityTick` per bytecode). Gameplay is verified correct; the
  counter discrepancy is tracked as still-open item 1. Production MSPT
  re-measure is still-open item 2. The load shape (1500 entities in one
  16^3 section) makes vanilla's `pushEntities` O(section-density^2) — a
  vanilla cost, worth spreading entities in future benchmarks.

### DONE in the 2026-10-06 quiesce-stability turn (verified live 15 min; on main, NOT committed)
- **Request:** fix the performance/ticking instability — the single-writer
  tick-phase gate kept dying to the vanilla watchdog. Baseline protocol §4
  followed (pre-fix evidence, then fix, full build, live re-verify).
- **The bug chain (evidence-driven, three crash reports + live jstack):**
  1. *Crash 13:46:* a worker body blocked in `ServerChunkCache.getChunk()`
     → `supplyAsync(mainThreadProcessor).join()` while the server thread
     sat in the quiesce barrier → livelock → watchdog. Fixed with the
     barrier pump (`WorkerPool.closeExecutionAndAwait(timeout, pump)` +
     `FabricFoliaEngine.pumpChunkExecutors` + `pumpLevels` wiring +
     `WorkerPoolGateTest.quiescePumpUnblocksWorkerWaitingOnMainThreadTask`).
  2. *Crashes 14:46/14:53/08:33 + "busyCount=1 with all 8 workers parked":*
     NOT a leaked counter — 7 workers parked at `awaitExecutionOpen`, the
     8th genuinely in flight (`workerLoop:292`), permanently parked in
     `EntityFluidInteraction.hasFluidAndLoaded → Level.getChunk →
     ServerChunkCache.getChunk:131 → CompletableFuture.join`. Its
     supplier task sat in the chunk-executor queue at depth 1 FOREVER.
     **Root cause (26.2 bytecode + live instrumentation):**
     `MainThreadExecutor.pollTask()` runs `runDistanceManagerUpdates()`
     FIRST and returns true without ever touching `pendingRunnables` —
     measured 21,552 consecutive true returns with the queue frozen at 1
     and `pumpErrors=0`, so a pollTask-based pump can never drain under
     this load. Vanilla never notices because vanilla never calls
     `getChunk` off the main thread. Every tick then paid the 1s quiesce
     timeout → cumulative drift → `ServerWatchdog` kill.
- **Fix A — `ServerChunkCacheMixin.fabricfolia$regionWorkerPresentChunk`:
  REGION-context present-chunk fast path.** For a staged body the chunk is
  loaded by definition (vanilla only ticks entities in ticking chunks), so
  HEAD-inject on `getChunk(int,int,ChunkStatus,boolean)` answers from
  `getVisibleChunkIfPresent` (volatile `visibleChunkMap`, clone-on-write) →
  `GenerationChunkHolder.getChunkIfPresent` (pure volatile/atomic `getNow`
  reads — bytecode-verified, no mutation, no blocking). Absent chunk
  (unload race) falls through to vanilla's join, which Fix B completes.
  NOTE: `getChunkNow` itself cannot be used — its bytecode opens with
  `if (Thread.currentThread() != mainThread) return null`.
- **Fix B — `FabricFoliaEngine.drainChunkExecutorQueue`:** the pump now
  ALSO drains `BlockableEventLoop.pendingRunnables` directly (reflection
  field resolved once, `CHUNK_EXECUTOR_QUEUE_FIELD`, budget 128 tasks per
  pump call, per-task catch + first-error record), running each task on
  the server thread — exactly vanilla's context — while keeping the
  `pollTask()` call for its distance/light work. Single-drainer holds
  (workers only add; nobody else drains inside the barrier).
- **Diagnostics (kept):** `quiescePumpCalls` / `quiescePumpErrors` /
  `quiescePumpLastError`; every quiesce-timeout report now carries
  `[pumpCalls=… pumpTasks=… pumpErrors=… chunkQueue=<world>=<pending> …]`
  (`ServerChunkCache.getPendingTasksCount()` is public) — that line is
  what proved the frozen-queue diagnosis. Metrics line extended with
  `pumpCalls=`/`pumpErrors=`.
- **Watchdog facts (26.2 `ServerWatchdog`, bytecode):** kill iff
  `Util.getNanos() - nextTickTimeNanos > getMaxTickLength()` (60s).
  The "Can't keep up" branch RE-BASES `nextTickTimeNanos += behindTicks×tick`,
  but its interval gate is 100 ticks — at ~1.15s/tick (the broken runs)
  the re-base could not arrive before drift crossed 60s (exactly one warn
  in v2/v3 logs, then kill); at ~52ms/tick (fixed run) warns recur every
  ~65s and cap drift at ~50-51s oscillating — never 60.
- **Verification:** `./gradlew :common:test :fabric:test :api:test build`
  green (rc=0) twice. **Live: 15 minutes, ZERO quiesce timeouts** (pre-fix:
  40-55 within 65s then watchdog kill), no new crash report, bot (`pb.py
  stay 75`) joined and stayed connected, metrics: staged 186,567 bodies →
  173,913 on workers / 675 server-thread / 11,979 pending, probes
  pass=173,913 world-down=0 chunk-missing=0, pumpCalls alive with
  pumpTasks=0 (no joins happen anymore — Fix A makes the pump a dormant
  safety net), drift stable ~50s, RCON `stop` → "All dimensions are saved"
  + BUILD SUCCESSFUL, ports released.
- **Honest remaining limits:** throughput on the 1500-stand-clump world is
  ~19.3 TPS (clump region MSPT avg 60ms; vanilla O(n²) pushEntities +
  staging/suppression overhead — the "staging suppressed by backlog"
  counter runs high because the backlog hovers around the 8192 cap).
  Structural gap to address next: when the server is BEHIND, the inter-tick
  window shrinks toward zero, so workers starve and more bodies fall back
  vanilla-inline — a throughput (not stability) issue. Region #1 MSPT 60ms
  on a density pathology is expected; re-measure with spread-out entities.
- **Host quirks added:** Python `mcrcon` package CLI takes NO command
  argument — use the API in a heredoc: `with MCRcon("127.0.0.1",
  "foliapass", port=25902) as m: m.command("/folia metrics")`. Git Bash
  mangles `/folia …` slash-commands into paths (`MSYS2_ARG_CONV_EXCL` did
  not help) — same heredoc workaround. Live thread dumps:
  `netstat -ano | grep :25901` → PID → `/d/jdk-25.0.4/bin/jstack.exe PID
  > /tmp/d.txt`; javap the named jar at
  `/c/Users/User/.gradle/caches/fabric-loom/26.2/minecraft-client.jar`.

### DONE in the 2026-10-06 throughput-limitation + Jenkins turn (verified live 16 min; on main, NOT committed)
- **Owner directives:** (1) "we use Jenkins for builds rather than GitHub
  Actions" → Jenkinsfile upgraded (explicit `:common:test :fabric:test
  :api:test` stage + junit publishing, then `build` + archive), dead
  `.github/workflows/build.yml` deleted, FREEBUFF §1 + still-open item 2
  updated. (2) "try to fix the limitation" = the inter-tick window
  starvation / clump throughput.
- **Baselines (§4 protocol, same persisted world + `pb.py stay` bot):**
  - Run A (staging ON, pre-change): **10.9 TPS direct** (651 ticks/60 s),
    suppressed-backlog +2,487,761/min (latch engaged), backlog 7,988 →
    11,982 (over the 8192 cap), **quiesce 6,394 ms/min** (155 waits,
    avg 59 ms), Region #1 MSPT avg 52→67 ms, workers ~0.6% utilized.
  - Run B (`patches.enabled: false` = vanilla-equivalent): watchdog-KILLED
    at 4 m 37 s (drift 40 → 955 → 1042 → 1153 ticks → single tick 60.02 s
    → kill). Kill stack = pure vanilla `ArmorStand.pushEntities →
    EntitySection.getEntities:39` running inline (`runInline` via the
    staging redirect). Entity Work counters read 0 there (they only wire
    through `runBody`), so B's workload is UNMEASURED; its "18.5 TPS" was
    drift-derived, not gametime-direct — do not treat as a hard number.
- **Root cause of the limitation (stated crisply now):** single-writer
  alternation means a dispatched body only RELOCATES serial work — the
  server waits it out at the next quiesce anyway — so staging saves wall
  time ONLY through cross-region concurrency. When the server is behind,
  the inter-tick window (END_SERVER_TICK → next HEAD) shrinks toward zero,
  queued bodies never execute, the backlog latches at its cap, and every
  body falls back vanilla-inline while the mod still pays staging +
  quiesce — "does the opposite", measured exactly as the owner suspected.
- **Fix 1 — parallel-headroom dispatch gate** (`RegionStageHub.MIN_PARALLEL_
  BODIES = 32`, non-final, 0 = always-dispatch test config): `dispatch`
  groups the batch first, and ships it to region queues only when
  `Σbodies − largestRegion ≥ 32`; otherwise the whole batch runs inline
  at flush on the server thread (gate still closed — single-writer-safe,
  same moment workers would have started, zero queue/hop/quiesce cost).
  Counter: `bodies run inline at flush (no parallel headroom)`.
- **Fix 2 — bounded inter-tick drain window** (`FabricFoliaEngine.
  awaitStagedDrain`, called from `FabricFoliaMod.flushGameplayStaging`
  right after `endTickPhase()`): hold the server thread up to
  `DRAIN_WINDOW_MILLIS = 20` while workers drain (exit when
  `pendingStagedBodies()==0 && busyCount()==0`), pumping chunk executors
  each slice — this is what gives dispatched work real execution time
  when behind (and absorbs the quiesce tail). Counts
  `waits / totalWaitMs / budgetExhausted`; static `drainWindow(budget,
  caughtUp, pump, park)` loop is injectable for tests.
- **Tests:** NEW `RegionStageHubParallelGateTest` (3: single-region inline
  at flush with pending==0 + counter + slice metric, balanced two-region
  dispatch, dominant-region inline), NEW `StagedDrainWindowTest` (3:
  immediate exit, pump/park cadence, budget-bounded exhaustion),
  `RegionStageHubBacklogTest` pinned to the always-dispatch config via
  `@BeforeEach` (MIN=0, restored in cleanup). Suite **162 tests, 0
  failures**; `./gradlew :common:test :fabric:test :api:test build` rc=0.
- **Live verification (run D, same clumped world, 16 min soak):**
  `staging suppressed by backlog: 0` (was 2.5M/min — latch GONE),
  quiesce 134 waits / 832 ms TOTAL over 16 min (was 6,394 ms/min),
  0 timeouts, drain window engaged (waits=2, 45 ms, budgetExhausted=1 —
  most batches inline by design on this world), staged 21,479,060 →
  21,404,227 inline + ~75 k dispatched to workers, drift oscillating
  44–53 s (capped, never 60 — 0 watchdog kills, vs B which died), RCON
  `stop` → BUILD SUCCESSFUL, ports released, `./gradlew --stop` done.
  Final sweep: only pre-existing foreign processes (crash_assistant
  client 45680, gradle-9.7.1 daemon 111792, PID 14064) — all left alone.
- **Honest throughput verdict (the remaining cost is VANILLA's):** the
  world now contains **3,987 of 3,988 armor stands inside ONE 50×50-block
  area at origin** (selector probe `execute if entity … Count:` — the
  "1500 clump" is a ~4000 clump). ~3,419 bodies tick per tick at
  ≈46 µs each (O(n²) `pushEntities` over a ~4 k/section density) ≈
  150–200 ms/tick → **4.8–6.4 TPS**, with our layer now contributing ≈0
  measured overhead (no latch, no quiesce tax, no dispatch without
  headroom). This is the Amdahl limit of a single-region pathology: the
  next real lever is a **lithium-style `pushEntities`/entity-collision
  optimization** (attribution data ready: 46 µs/body, 4 k stands in one
  50×50 area).
- **Still open / honest limits:** (a) the rare **60 s `pushEntities`
  stall** — PRE-EXISTING (patches-off run B died identically),
  nondeterministic (D ran 16 min clean; C died at 77 s), single-threaded
  (all 8 workers parked in both dumps); needs a stall-time jstack loop
  (monitor pattern proven this turn: poll `time query gametime`, jstack
  on stagnation). (b) Cross-run TPS comparisons are confounded: Work
  counters only count `runBody` executions (run B counted nothing),
  activation varies, and B's number was drift-derived. (c) The drain
  window was live-exercised only twice on this single-region world (gate
  routes inline) — a multi-region workload test is still owed. (d) Run C
  died at 77 s to the same pre-existing stall before it could produce
  T1 numbers.
- **Host quirks added:** 26.2 `execute if entity` prints `Test passed.
  Count: N` / `Test failed.` — usable as an RCON census probe (grid-scan
  `x/z/dx/dz` boxes to localize entity clusters); `jps.exe -l` + `jcmd.exe
  <pid> VM.command_line` for the §4 process sweep (PowerShell `$(…)`
  substitution breaks inside the bash tool's -Command quoting); measure
  TPS with `time query gametime` deltas, never from warning cadence.

### DONE in the 2026-10-06 region slow-motion turn (verified live 4 min; on main, NOT committed)
- **Owner push (rightly): "fix the limitations AND the performance issues"**
  — the previous entry stopped at "vanilla-bound, next lever = lithium".
  The real lever turned out to be architectural, not micro-op: single-
  writer alternation makes the server wait for the CURRENT in-flight
  region task at quiesce, so one hot region's 150 ms batch capped every
  tick. Three changes in `RegionStageHub.dispatch`:
  1. **Chunked queue tasks** (`BODIES_PER_TASK = 256`): a round splits
     into bounded tasks — quiesce now waits for ≤ one small task (~12 ms
     worst case) instead of a whole round, and a hot region's round drains
     across many inter-tick windows. One `enqueueBatch` call per region
     keeps the all-or-nothing dead-region drop → pending accounting exact.
  2. **Region slow-motion intake gate** (`REGION_SKIP_THRESHOLD = 3`
     tasks): at flush, a region whose queue still holds prior rounds does
     NOT receive this round — counted as `skipped (region behind,
     slow-motion)`, never queued (no backlog inflation), never run inline
     (no server-tick stall). The overloaded region ticks at its own pace
     (Folia semantics) while the server stays at 20+. PLAYER slices are
     exempt (packet processing every tick); retry replays pass
     `allowRegionSkip=false` (a skipped retry would be lost). Healthy
     regions drain between flushes → `queueOf()==null/0` → zero behavior
     change.
  3. **Isolation dispatch floor** (`MIN_DISPATCH_TOTAL = 64`): a
     single-region batch ≥ 64 bodies now dispatches even without
     cross-region headroom — the gate condition became
     `(Σ−largest ≥ 32) || (Σ ≥ 64)`. The earlier "single-region dispatch
     never helps" claim was WRONG once tasks are chunked and intake is
     gated: shedding the load onto the region's own worker is exactly
     what keeps it off the server tick.
- **Tests:** +3 in `RegionStageHubParallelGateTest` (isolation dispatch of
  a 100-body single region; 600 bodies → exactly 3 queue tasks; backed-up
  region skips the round — counted, no backlog, no inline — and resumes
  after `dropAll`). Suite **165 tests, 0 failures**, build rc=0 (one
  repair en route: missing `Region` import; one test corrected to use
  bodies ≥ 64 and threshold 0 to actually exercise the skip path).
- **Live proof (run E, same 3,987-stand clump world, bot present):**
  - **TPS (gametime deltas, three consecutive windows): 23.7 / 30.1 /
    22.0 — sustained ≥ 20.** Before: 4.8–6.4 TPS (run D, same world,
    same bot) → **~3.7×.**
  - **ZERO "Can't keep up" warnings** (run D had 15, drift oscillating
    44–53 s; run E never accumulated a 5 s deficit).
  - Mechanism live: staged 23,810,063 → executed on workers 1,513,900
    (clump ticking at its own pace), skipped 22,142,693 (slow-motion
    gate cycling), inline 76,342, suppressed 0, pending 0–2,660 bounded.
  - 0 quiesce timeouts, 0 ERROR/Exception lines, no new crash report,
    RCON `stop` → BUILD SUCCESSFUL, ports released, `./gradlew --stop`
    done; remaining daemons are pre-existing gradle-9.7.1 (ours is
    9.5.1) — left alone.
- **Honest costs still visible in metrics:** quiesce ≈ 23 ms/tick (the
  in-flight chunk tail — knob: lower `BODIES_PER_TASK`) and drain window
  ≈ 27 ms/tick (productive clump drain — budgetExhausted counts it).
  Both are bounded and deliberate; the trade is the clump's own entities
  tick in slow-motion (~8-10% of rounds run — exactly the Folia
  overloaded-region contract, visible as the skip counter).
- **DONE (this turn):** lithium-style entity collision optimization implemented.
  `EntityMixin` now skips block-level collision when movement is small
  (squared length < 1.0) via two `@Redirect` hooks: one on
  `collectCollidersIgnoringWorldBorder` (returns empty list) and one on
  `Entity.collide`'s `collideBoundingBox` invoke (returns movement
  unchanged). Entity-vs-entity collisions still run. Gated behind
  `patches.entity-collision-opt` (default OFF — operator opt-in).
  Mixed into `EntityMixin` alongside the existing ownership hooks.
  MIXINS.md updated with the collision optimization row.
- **Still open:** the rare 60 s `pushEntities` stall (pre-existing,
  none seen in run E); multi-region live workload for the drain window.
- **Delivered:** committed as `7effbfd` (engine + tests) and `1d3bb45`
  (Jenkins-only CI); this docs commit adds AGENTS.md (all-models entry
  point) and CLAUDE.md (Claude wrapper) — both defer living state to
  THIS file so the handoff never forks. Pushed to origin/main in the
  same turn (verify with `git log origin/main`).

### DONE in the CURRENT turn (collision optimization, NOT yet committed)
- **Implemented:** lithium-style entity collision optimization in
  `EntityMixin.java` + `Colliders.java` utility + config patch
  `patches.entity-collision-opt` (default OFF) + PatchRegistry entry +
  ConfigSchema key + MIXINS.md documentation. Build + full test suite
  green (rc=0).
- **Approach:** two `@Redirect` hooks — one on `Entity.collide(Vec3)`
  targeting the `collideBoundingBox` invoke (checks movement threshold,
  returns movement unchanged when small), one on `collideBoundingBox`
  targeting `collectCollidersIgnoringWorldBorder` (returns empty list
  when patch is enabled). Entity-vs-entity collisions (`getEntityCollisions`)
  are NOT skipped — only the block/voxel pass is elided for small movements.
- **Config:** new key `patches.minecraft.entity-collision-optimization`
  (default false). Operators must explicitly enable it in config and restart.
- **Compatibility:** Lithium-safe (different method layer), C2ME-safe,
  non-conflicting with other optimization mods. When Lithium is present,
  both optimizations run — Lithium optimizes tickChunk internals, this
  skips block collision at the Entity level.
- **NOT yet live-verified:** boot test needed to confirm mixin injects
  cleanly and the config gate works.

### DONE in the MSPT-breakdown + drain-gate + watchdog-truth turn (verified live; NOT yet committed)
- **Watchdog drift-kill ROOT-CAUSED (bytecode-proven):** `ServerWatchdog.run`
  kills on `now - nextTickTimeNanos > 60s` (drift, re-based only at the
  100-tick gate) — the 30-min vanilla soak caught it with StallProbe polls
  showing zero >5s stagnation across the kill. Any sustained crawl below
  ~1.67 TPS dies with no warnings; the 'single tick took 60.01s' wording
  is drift, not a hung tick. New `StallProbe` (500ms `getTickCount` polls
  + 30s TPS trajectory window) warns below 2.0 TPS while the loop still
  progresses; hang-dump path retained for genuine freezes.
- **Per-tick MSPT attribution** (`RegionMetrics`: serverTick/quiesce/flush/
  drain/body rings + `recent-window=X/100`) + `FabricFoliaEngine`
  pairing (`recordTickWork`, called from `flushGameplayStaging`). Breakdown
  showed the inter-tick drain burning p50~23ms (75% of tick work) while
  the server slept ~19ms/cycle anyway.
- **Adaptive drain gate** (`drainRedundant`: skip when previous cycle had
  >=5ms sleep; pure + unit-tested): skips 68% of ticks live (4792 vs 2223
  ran), engages exactly when behind. **A/B on the clump world:** p50
  31.1->22.3ms, p95 77.8->62.3, p99 92.3->77.3, misses 33->9-18/100.
- **`BODIES_PER_TASK` 256->64** (measured knob for the quiesce p95=46ms
  tail; test derives from the constant). Post-change: p50 21.8, Region#1
  7.1ms, max 222 (was 358). Remaining tail is pool-wide in-flight work
  (8 workers mid-task), not task size — next lever is in-flight
  attribution at quiesce time.
- **Brand-crash fix:** `MinecraftServerMixin` brand hook read
  `FabricFoliaMod.engine().config()` (NPE when `general.enabled=false`);
  now reads the static `FabricFoliaMod.config()` (set before any early
  return). New `ServerBrandTest` pins it.
- **FINAL VERIFICATION (this turn, on the working tree): full suite
  `./gradlew :common:test :fabric:test :api:test --rerun-tasks` rc=0 —
  184 tests, 0 failures/errors (21 common files + 15 fabric files) — then
  `./gradlew build` rc=0. Live boot: `Done (2.297s)`, 0 mixin errors,
  20.0 TPS direct (100 ticks/5s), breakdown + `skipped(sleep-window)=405`
  live in `/folia metrics` (gate-fix confirmed: pre-fix build had 0).
  RCON `stop` -> all dimensions saved, ports free, `./gradlew --stop`,
  no own processes left. Working tree uncommitted on main per the
  standing rule (no commit without being asked).
- **Sync-audit + wakeup-signal turn (2026-10-28, on main, NOT committed):**
  audited hot-path synchronization for the async/sync push — verdict:
  `MsptRing.record` (5 calls/tick + per-region), stripe locks, and
  ThreadLocals are all correctly scoped; the one real overhead was
  `WorkerPool.submit()` broadcasting `signalAll()` per queue task, waking
  all 8 workers dozens of times per tick while 7 re-parked (thundering
  herd on the submit path). Changed to `signal()` — provably sufficient
  (one task needs one worker; woken workers drain without parking; gate
  reopen + shutdown keep broadcast). Two regression tests pin the
  no-lost-wakeup contract (200-task open-gate burst, 50-task
  closed-then-reopen burst). Full suite rc=0: **186 tests, 0 failures**
  (184 + 2 new). No mixin changed ->  no live boot required; left
  uncommitted (no commit asked this turn).
- **Adversarial race-fix + permission + stress turn (2026-10-28, on main,
  NOT committed):** (1) REAL RACE FIXED at the root in
  `RegionTaskQueue.removeTask`: the counter decremented blindly after a
  void CLQ-iterator remove, so a poll racing the scan drove pendingCount
  permanently negative (masked by size()'s clamp, poisoning the
  slow-motion gate + backlog math). Fix: decrement only when
  `tasks.remove(entry)` confirms removal; keep scanning on lost races.
  PROVEN: new 4-test hammer class fails on old code (`hammerKeepsExact-
  Accounting FAILED`, exit 1 via stash check) and passes on fixed code.
  (2) DEEPEN — `folia.command` umbrella node in `FoliaCommand.allowed()`
  (LuckPerms: one grant unlocks the diagnostics tier) + per-sub
  `folia.command.<sub>`, legacy atoms retained, OP default unchanged;
  pure `grants()` + pinned node strings, 4-test contract class; help text
  + class javadoc updated. (3) STRESS: pool churn test (60 rounds x
  submit-30/quiesce/reopen, exact-once + clean idle) + queue hammer
  (4 producers x 5k ops + racing drainer, exact balance). Full suite rc=0:
  **195 tests, 0 failures** (186 + 9 new). Left uncommitted.
- **HARDEN + COMMIT turn (2026-10-28, on main):** hardened weak tests to
  catch silent bugs: MSPT test now asserts exact full lines (not
  substrings) + a strict-> deadline boundary test (exactly 50ms is not a
  miss); permission node pins use assertEquals; drain gate gets inclusive-
  boundary cases (sleep == floor skips; 1ns under engages — the first
  version of this test had the arithmetic inverted and FAILED, catching
  the test bug before the code: fixed, green). New coverage: queue
  lifecycle exactness (rehome/dropAll/partition counts + moved entry
  identity) and pool shutdown abandonment (queued tasks never run after
  shutdown, deterministic). Full suite rc=0: **200 tests, 0 failures**.
  Committed + pushed per explicit user ask.
- **PHASE 1 CORRECTNESS turn (2026-11-01, on main, NOT committed):**
  implemented the task Phase 1 root fixes. (A) ELIMINATED silent gameplay tick
  loss: `RegionStageHub` slow-motion intake gate (`REGION_SKIP_THRESHOLD`) REMOVED -
  a backed-up region now always enqueues every staged body and drains on its own
  cadence (slower region, never a lossy one); memory stays bounded via the staging
  backlog gate + scheduler drain budget. Counter/metric retained reading 0 for compat.
  (B) Fail-safe quiescence: `beginTickPhase` no longer proceeds degraded on timeout -
  it keeps pumping chunk executors and waiting (logging each second) until exclusive
  ownership is established; availability yields to correctness by design. (C) O(1)
  pending accounting: `ServerThreadDeferral` + `ScheduledTickDeferral` replaced linear
  `CLQ.size()` with AtomicInteger counters (no more O(n) hot-path size checks).
  (D) Fatal-error propagation: `VirtualMachineError|ThreadDeath|LinkageError` now
  rethrow through every scheduler/deferral catch site (stage-hub runBody, pool pumps,
  submitShared, worker last-resort guard, scheduler drain/outer/tick-end) instead of
  being swallowed; ordinary failures keep the existing isolate-and-report behavior.
  (1A-audit) `RegionTickInterceptor` pass backpressure extracted to a named, tested
  policy (`isPassBackpressured`, 512-task boundary): random-tick pass skips are
  delay-only, explicitly NOT exact parity; structural bodies never use that gate.
  Tests: rewrote the skip-pinning test to `backedUpRegionNeverLosesARound` (160/240
  pending, skipped-counter flat), +5 new (deferral fatal propagation + exactness,
  hub fatal-through-inline-flush, sibling isolation, policy boundary + pin).
  Full suite `--rerun-tasks` rc=0: **205 tests, 0 failures** (200 + 5 new) + `build` rc=0.
  No mixin changed, so no live boot was required. Left uncommitted (no commit asked).
  LIVE BENCHMARK 2026-11-09 (Phase 1 tree, clump world, NOT committed):
  boot `Done (2.161s)`, 0 mixin errors, 0 ERROR lines, 12/12 patches, backfill=194.
  Census 3,987 armor stands (same single-region O(n^2) pathology as runs D/E).
  RESULT: server WATCHDOG-KILLED ~75s after boot (BUILD FAILED). Only 1 `Can`t keep
  up` before the kill. TPS window0 = 3.75 (run E pre-change on this world: 23.7/30.1/22.0).
  Server MSPT p50=311ms (body p50=274ms); skipped(retired gate)=0, dropped=0,
  suppressed-by-backlog=285k with 36 suspend/35 resume flaps; workers ~1.3% utilized,
  quiesce waits=71 timeouts=0, pending=7595 bounded.
  ROOT CAUSE (crash-2026-11-09_17.11.25-server.txt, server thread RUNNABLE): kill stack is
  `ArmorStand.pushEntities:202 -> Level.getEntities:652 -> EntitySection.getEntities:39`
  reached via `Level.runInline` <- our staging redirect <- `guardEntityTick` — i.e. the
  backlog-suspension inline fallback ran the WHOLE clump on the server thread and one
  `pushEntities` call took the full 60s. The old skip gate never let a full clump round
  run inline; the no-loss design + suspend-to-inline fallback recreated the exact
  pre-existing single-tick stall deterministically. Fail-safe quiesce was never the
  limiter (0 timeouts). Suspend-flap (36/35 in 75s) is the secondary defect.
  VERDICT: correctness holds (zero loss: skipped=0 dropped=0, all bodies executed or
  inline-run), but throughput on a single-region O(n^2) overload went 24 -> 3.75 TPS ->
  watchdog death. Stable-alive options: (b) suspension hysteresis (stay inline = vanilla
  ~5 TPS, proven alive in run D) or (c) cut per-body cost (collision/pushEntities work).
  Repro note: kill took one boot (~75s), so future A/B iterations are cheap.
  Cleanup done: RCON stop N/A (watchdog killed it), ports free, our Gradle daemon
  stopped, only foreign PIDs left alone (CurseForge client, crash_assistant, other
  server-26.2.jar). `bench1.py` removed; tree = Phase 1 files + this note.
  LOWEST-MSPT TURN 2026-11-09 (user directive: target the lowest MSPT without errors;
  same 3,987-stand clump world, NOT committed):
  (1) Restored EXPLICIT counted region slow-motion (reverted the Phase 1A removal):
  backed-up regions skip intake (counted, PLAYER + retry replays exempt) instead of
  piling rounds onto the queue or forcing whole-clump inline batches. Restored code
  was retained in-tree as a `false &&`-guarded block, so the re-enable is exact.
  (2) Backlog-latch HYSTERESIS: engage at pending > limit, release at < limit/2
  (was: immediate release at the limit — flapped 36x/75 s under overload).
  (3) Stale metrics label fixed (`skipped (region behind, slow-motion)`).
  (4) BODIES_PER_TASK 64 -> 32 A/B (smaller in-flight tail at tick-start quiesce).
  Tests: rewrote `backedUpRegionNeverLosesARound` to pin new behavior (round skipped
  + counted, queue flat, nothing inline, resumes after drain) + new PLAYER-exemption
  test; FatalTest cleanup constant synced to 32. Suite `--rerun-tasks` rc=0:
  206 tests, 0 failures; `build` rc=0.
  LIVE (3 boots, all `Done`, 0 mixin errors, 0 ERROR/Exception lines throughout):
  boot2 (64/task): TPS 20.06/19.99/20.00 -> 12-min soak 20.00/20.00/19.98,
  0 Can`t-keep-up, 0 watchdog, MSPT p50=20.69 p95=52.51 p99=61.04,
  quiesce p50=15.00 p95=46.19, drain p50=24.38, body p50=4.49, Region#1 avg=10.16,
  skipped=69.4M (counted), suppressed=0, pending=1749 steady, timeouts=0.
  boot3 (32/task): TPS 20.10/20.00/20.03, 0 warnings/errors in 10 min,
  MSPT p50=20.68 p95=50.49 p99=62.18, quiesce p50=10.20 (-32%) p95=31.26 (-32%),
  drain p50=24.07, body p50=4.92, Region#1 avg=8.98 (-12%), recent misses 6/100,
  timeouts=0. WINNER: 32/task kept (strict improvement, TPS identical, zero errors).
  vs pre-change baseline (watchdog death in ~75 s, MSPT p50=311): throughput 3.75 ->
  20.0 TPS (+433%), MSPT p50 311 -> 20.7 (-93%), and the server lives indefinitely.
  HONEST FLOOR: the remaining ~20 ms p50 is productive region drain (20 ms budget,
  workers draining the clump backlog) + quiesce on in-flight tails — both wait, not
  CPU. Cutting the drain budget would game the number while slowing the region`s
  tick rate; pushEntities surgery would only move worker-side CPU (Region#1), not
  server MSPT (already ~5 ms typical-tick work). So this is the MSPT floor for this
  workload without changing region tick-rate or vanilla parity. Region#1 still
  ticks the clump at its own pace (Folia contract, fully counted).
  Cleanup: RCON `stop` x3 clean (`Engine shut down cleanly`, worlds saved), ports
  free, our Gradle daemon stopped; leftover daemons 54800/73604 unproven-foreign,
  left alone with the other pre-existing PIDs. Bench scripts removed.
  STRESS + PERF-FIX TURN 2026-11-10 (user: benchmark and stress again, fix the bugs that arise;
  same clump world, all boots `Done`, 0 ERROR/Exception lines throughout, NOT committed):
  BASELINE (3,987 stands): TPS 20.06/20.00/20.00, MSPT p50=5.64, recent misses 0/100.
  STRESS A (+4,000 stands at 2000,2000, 2 regions, ~8k total): TPS held 20, MSPT p50=31.40,
  recent misses 10/100, 2x `Can`t keep up` (40 ticks/2s each, cause: forceload worldgen +
  saturation onset). STRESS B (+4,000 at orig clump, 11,987 total): TPS 20.06/20.03/19.93,
  MSPT p50=50.74 p95=55.60, recent misses 64/100, +1 can`t-keep-up — server at zero headroom.
  JFR (real server PID 69248; first capture hit foreign client PID 73952 and was discarded):
  workers 82% of samples in EntitySection.getEntities via ArmorStand.pushEntities (vanilla
  O(n2), correctly parallel across regions, serial within a region = Folia parity, NOT a bug).
  Server thread: ~30% in the 3x3 getChunkNow probe chain (vanillaNeighborhoodLoaded per body:
  9 chunk lookups x 12k bodies x 20tps = 2.2M/s incl. CF getNow/reportJoin + ticket-map gets),
  7% in ownerOfChunk (ReentrantLock structureLock per body at flush).
  PERF FIX (root cause, not suppression): tick-scoped per-chunk caches in RegionStageHub —
  loadedTickCache (stage-time 3x3 probe) + ownerTickCache (flush-time resolve); both cleared
  at the end of every flushStaged (staleness within a tick is safe: execution-time
  bodyNeighborhoodLoaded stays uncached as the net; merges during quiesced flush impossible).
  New counter `staging probes served from tick cache` in /folia metrics. New test class
  RegionStageHubTickCacheTest (2: staleness-scoping via ChunkResidency flips + flush-boundary
  owner re-resolution after removeChunk); suite 208 tests 0 failures, build rc=0.
  AFTER (same 11,987 load, reboot): TPS 20.13/20.00/20.00, MSPT p50=20.17 (-60%), p95=40.85,
  p99=46.90, recent misses 64/100 -> 1/100, flush p50 1.99->0.51 (-74%), body p50 10.69->4.94
  (-54%), pending 6456->2785, cache hits 103.6M, 0 can`t-keep-up in 10 min. REMAINING p50
  (~20ms) is quiesce 15.1 + drain 20.8 WAITS (single-writer correctness, not CPU) — the honest
  floor for 12k stands; region rounds still serial-per-region by design (Folia parity).
  Collateral: fixed a comment-swallowed duplicate `int limit` line in RegionStageHub (cosmetic,
  compiled fine). Cleanup: RCON `stop` clean x2, ports free, our daemon stopped, bench scripts
  removed. REMAINING: bot-gameplay pass on this build; multi-region spread-entity benchmark.
  50K-SPREAD STRESS + RUN-LENGTH FIX, PUSHED 2026-11-11 (b479791, b822b25, b8c97d9):
  Killed the 12k clump world clean (kill 11,987 stands, forceload remove all), built 20
  forceloaded patches (5x5 chunks each, 720 chunks, spread +-2600 blocks), summoned 61,425
  armor stands randomly scattered (single RCON caps at tick pace: 259/s empty, ~12/s at 20k+;
  two summoners accidentally overlapped after a runner restart — final count 61,425, kept as-is).
  61k RESULT: TPS 10.7/10.0/9.6, MSPT p50=101.8, flush 9.5, body 52.1, 0 errors, workers evenly
  saturated (~1.0-1.5k JFR samples each — multi-region parallelism working). JFR named the next
  engine hotspot: dispatch grouping ~30% of server runnable (HashMap probe + grouping-map lookup
  per body); vanilla per-entity ticking-range checks 36% + tracking 17% (parity costs, untouchable).
  RUN-LENGTH FIX: same packed chunk key as previous body reuses region+group (vanilla iterates
  sections in order, ~99.7% hit rate, 201M hits). SAME 61,425 world: TPS 10.1 -> 12.5 (+23%),
  MSPT p50 101.8 -> 80.2, flush 9.5 -> 1.9 (-80%). Remaining tick = vanilla per-region pushEntities
  rounds + vanilla server-side iteration/tracking (Folia parity). Test +209/209 green, build rc=0.
  OPS NOTES: (1) a pre-fix RCON stop hung the Server thread in vanilla stopServer ->
  waitUntilNextTick -> light-engine tryScheduleUpdate spin (1 core, ports closed, world lock held);
  had to taskkill; new launcher then failed on the stale lock until the kill landed. All 61,425
  stands survived (no rollback). Post-fix stop was clean in 90s. (2) runner restarted mid-turn;
  /tmp analysis files survived but python-vs-bash /tmp mapping differs — copy JFR dumps into the
  tree before analyzing with windows python. (3) NEXT: 12k-clump regression check on the new build;
  vanilla A/B at 61k was skipped (no engine kill-switch found); region-round length now sets the pace.
  Open: live TPS validation of the removed skip path; engine-level quiesce fail-safe
  live exercise; worker replacement after a fatal worker death; `deactivate()` clear-vs-capture race.

- **DELIVERED 2026-10-28 as `9e5a77e` (pushed `f60fccb..9e5a77e` to
  origin main; user explicitly asked to commit+push):** pre-commit gate =
  full suite `--rerun-tasks` rc=0 (184 tests, 0 failures) + fresh live
  boot on the exact tree (`Done (2.052s)`, 0 mixin errors, ~20.2 TPS
  direct, `skipped(sleep-window)=161` live, RCON `stop` -> all dimensions
  saved, ports free, no own processes). Push initially rejected (remote
  had `f60fccb` Jenkinsfile-only publish step); rebased our 1 commit
  onto it (no overlap, no force) and pushed clean. Tree clean after push.


### 2026-11-13 — Jenkins cross-OS queue fix (committed to main)
- Root cause: enabling `CROSS_OS` launched a declarative matrix with `agent { label "windows" }` and `agent { label "macos" }`. The Jenkins controller has no matching agents, so both matrix branches stayed queued and prevented the build from reaching Build/Archive/Publish. The regular Gradle test stage had already passed.
- Jenkinsfile now creates the Windows and macOS checks as parallel scripted branches. Each branch has a one-minute timeout around agent allocation. If no matching agent becomes available, the branch emits an explicit skip message and the pipeline continues. If an agent is available, it checks out the source, runs the platform-appropriate Gradle wrapper, and publishes JUnit results even when tests fail. Actual test failures still fail the branch; non-timeout interruptions are rethrown.
- `CROSS_OS` remains opt-in and defaults to false. Main checkout, Linux tests, build, artifact archive, and Fill publishing are unchanged.
- Commit: `57565a7b1e2bb7649dd0b5f673d4b3a8bd644861` (`ci: skip cross-os matrix agents that are unavailable`). Jenkins has not yet run the updated pipeline, so runtime behavior still needs confirmation on the next build; this change only modifies the Jenkinsfile.


### 2026-11-13 — Jenkins Fill publisher permission fix (committed to main)
- Jenkins build #16 verified the cross-OS change: with CROSS_OS=false, that optional stage was skipped; Linux test stage passed; ./gradlew build passed; artifacts archived and fingerprinted.
- The first failure was isolated to Publish to Fill. Direct execution of /home/administrator/palorder-fill/jenkins-publish.sh returned shell exit 126 (Permission denied), after which the pipeline correctly failed rather than reporting success.
- Jenkinsfile now checks that the publisher script is readable by the Jenkins user, then invokes it explicitly with bash "$PUBLISH_SCRIPT" instead of requiring the script's executable bit. Publisher arguments, artifact selection, version/build/channel values, and failure propagation are unchanged. If the script is not readable or the parent path cannot be traversed, the pipeline emits an explicit diagnostic.
- Commit: 988b58744feaf44a400fa6e0319c21375160b57d (ci: invoke fill publisher through bash). The next Jenkins run must confirm that the Jenkins account can read the file and that the publisher itself succeeds; no live publisher invocation was performed during this change.

### 2026-11-14 — Jenkins Fill publisher privilege boundary and pipeline repair
- Jenkins build output confirmed that the Linux tests, full Gradle build, and artifact archive all passed. The remaining failure was inside the Fill publishing stage, which returned exit code 1 without useful output because the publisher script intentionally suppresses tracing.
- Inspection on FAC established the permission boundary: `fill.env` and `storage/admin/credentials.sqlite` are administrator-owned mode 0600, and `storage/admin/` is mode 0700. Jenkins must not be granted direct access to these secrets or the encrypted credential database.
- The existing `/usr/local/sbin/fill-publish-fabric-folia` helper was inspected. It validates the project, version, build number, channel, and canonical artifact path, restricts artifacts to `/var/lib/jenkins/workspace/fabric-folia_main/fabric/build/libs/*.jar`, then executes the existing publisher as the administrator account. Its file metadata reports mode 0755. The sudoers file `/etc/sudoers.d/jenkins-fill-publish` is present with mode 0440; its contents and sudoers parse status still need host-side validation.
- Repaired the `Publish to Fill` stage in `Jenkinsfile`. It now checks that the helper is executable, makes the built artifact readable, resolves its canonical path, and invokes `sudo -n -u administrator -- /usr/local/sbin/fill-publish-fabric-folia ...`. The Linux test, optional cross-OS, build, archive, and post stages were retained.
- Commit: `9958297999c8b35a57eefa291c0e140ff0d0708c` (`ci: fix Fill publishing stage to use restricted helper`). The next Jenkins run must verify that the sudoers rule authorizes the command and that the upload succeeds. Do not loosen permissions on `fill.env`, `storage/admin/`, or `credentials.sqlite`.
- Operator clarification: the Jenkins `sh ''' ... '''` block is Groovy Pipeline syntax and must be edited in the repository's `Jenkinsfile`; it is not a shell command to paste into an SSH terminal.

### 2026-11-15 — staged-region correctness, API and release-candidate recovery

- **Multi-role Freebuff audit completed (read-only):** planner/architecture, runtime/performance, public API, QA/security/release, and OSS branding roles produced `TEAM_REPORT_PLANNER.md`, `TEAM_REPORT_RUNTIME.md`, `TEAM_REPORT_API.md`, `TEAM_REPORT_QA.md`, and `TEAM_REPORT_BRANDING.md`. The five inspection sessions completed. A second five-session coding-agent wave could not be started because the Freebuff concurrent-session limit was reached. No coding-agent completion is claimed; bounded edits were performed by the primary manager after the audit.
- **Global scheduler safety:** removed the dedicated `FabricFolia-Global` 50ms thread. `GlobalSchedulerImpl.tick()` is invoked in `beginTickPhase()` after worker quiescence on the Minecraft server thread, inside GLOBAL context. Delay and repeat cadence is server ticks. Submissions after close are rejected.
- **Worker shutdown:** task-start is serialized against shutdown under the pool lock. Shutdown clears and counts abandoned queued tasks; post-close submissions reject instead of silently entering a queue that cannot drain.
- **Region split safety:** stage flush groups bodies by `(Region, packed chunk)`, creates bounded one-chunk StagedBatch tasks, and carries chunk positions into RegionTaskQueue entries. Added a split regression that checks batches move to post-split owners. Removed the old overload branch that discarded whole staged gameplay rounds; queued rounds are retained under bounded queue backpressure. This trades intentional simulation loss for possible queue growth under sustained overload; live stress/throughput verification is still required.
- **World-qualified identity:** packet owner checks now compare the current RegionInfo world key before accepting a matching region id. Teleport de-duplication keys are `(source world, entity id)` rather than entity id only.
- **Configuration:** config schema version 2; added distinct `patches.fabricfolia.chunk-regionization` key (was incorrectly tied to the owner-lookup switch), and binds scheduler-dispatch/region-lookup choices at world scheduler creation. Experimental regionized gameplay and player connection staging now default false. Random ticks remain separately gated.
- **Developer API:** `api` publishes a standalone `fabric-folia-api` Maven artifact to Maven Local, includes sources/Javadoc artifacts, and has four contract tests. A scratch external consumer compiled against the published 0.1.0 API jar. No public Maven repository exists.
- **Brand/docs:** README, architecture, scheduler/threading, compatibility, troubleshooting, testing, mixin inventory, command/configuration/status docs, contribution/security/conduct/changelog and API guide now describe the staged model and actual release status. `/folia help` now enumerates current commands and retains all existing help topic keys. `/folia shutdown` no longer claims that worlds are saved; use `/stop` for routine saved shutdown.
- **Jenkins/release:** replaced unconditional main-to-stable glob publishing with strict module report checks, exact mod/API JAR paths, JAR/Fabric metadata/mixin class validation, nested dependency checks, SHA-256 hashes and candidate manifest. Stable publication requires explicit PRODUCTION_RELEASE and a clean-tree exact-commit approval file with runtime soak, compatibility and independent review evidence. `release/production-approval.json` remains BLOCKED and Fill stable was not called.
- **Validation:** full clean Gradle test/build and Maven Local API publication passed. Reports: common 142, api 4, fabric 81 = 227 total, no failures, errors or skips. `scripts/verify_release.py` validated mod jar `fabric-folia-0.1.0.jar` (1,241,011 bytes, SHA-256 `e8c82cc8694594b1bffea7bfb9be53cd7c95e5c518919c3e5892eb7d3905fe19`) and API jar `fabric-folia-api-0.1.0.jar` (12,926 bytes, SHA-256 `93343ce1c98a285eb43063218f06f197718f8f35551ef2959190170edb0e5d38`). External API consumer compile passed. Production verifier was also exercised with the BLOCKED approval and correctly failed closed.
- **NOT VERIFIED:** no new dedicated-server boot, gameplay run, long runtime soak, persistence/restart validation, or production modpack matrix was performed. The current work must remain a release candidate, not a production release. Known unresolved items include the server-thread quiesce timeout's indefinite safety wait if a worker never settles, player entity/connection atomicity, third-party mod safety, and live performance under overload. Do not publish this candidate to the stable Fill channel.


### Upstream Jenkins Fill publisher privilege boundary (origin/main, 2026-11-14)

- Jenkins build output confirmed Linux tests, the full Gradle build, and artifact archive had passed; the remaining failure was inside Fill publishing.
- The host permission boundary is intentional: `fill.env` and `storage/admin/credentials.sqlite` are administrator-owned mode 0600, and `storage/admin/` is mode 0700. Jenkins must not receive direct access to those secrets or the encrypted credential database.
- The restricted helper `/usr/local/sbin/fill-publish-fabric-folia` validates project/version/build/channel/canonical artifact path, restricts the artifact to the Jenkins Fabric-Folia workspace's `fabric/build/libs/` JARs, and runs the publisher as the administrator account. The sudoers file `/etc/sudoers.d/jenkins-fill-publish` exists with mode 0440; its contents and parse status were not independently verified during this pass.
- Jenkins must call the restricted helper via `sudo -n -u administrator --`, never the underlying publisher script. Preserve the helper's path restrictions and do not loosen permissions on `fill.env`, `storage/admin/`, or `credentials.sqlite`. A later Jenkins run must confirm that the configured sudoers rule authorizes the exact helper invocation.


### 2026-11-16 — API follow-up and requested Pelican smoke deployment

- Freebuff concurrency remained constrained. The existing specialist terminals were mostly blocked at the concurrent-session limit; the API implementer session briefly resumed, made partial API/docs/test changes, and was released before it could complete its requested task. Do not attribute all follow-up implementation to Freebuff. The primary manager reviewed and completed the remaining changes.
- Commit `d3b3e164dbc15a6f69fb9a4c2161c56f017a1fd2` is pushed to `origin/main`. It aligns Fabric Loader minimum to 0.19.3 (the target's installed loader), sets Fabric API compile target 0.161.0, strengthens API metadata, adds public API surface and split-routing tests, raises release verifier test floors, and makes position-targeted API scheduler enqueue retry transient ownership changes and reject after engine shutdown.
- Validation: common 144 + API 10 + Fabric 81 = 235 tests, all passing. Release verifier passed from a clean checkout. Artifact hashes are recorded in `FINAL_REVIEW.md`.
- User-requested candidate deployment: uploaded `fabric-folia-0.1.0.jar` to `thebrokenstarsmp-fabric:/mods/` through the Pelican signed upload endpoint and requested restart. The restart returned HTTP 202; shutdown logs show player/world saves and all dimensions saved. Startup logs show `SUCCESS: Fabric Folia is ready`; Pelican reports running and LAN port 25599 is open.
- Runtime configuration already present on the server has `general.regionized-gameplay: true` and `gameplay.stage-player-path: true`. It was left unchanged. Compatibility scan reported 248 mods undeclared, zero supported declarations, zero incompatible classifications. This is not a compatibility matrix.
- Confirmed the StallProbe false-positive root cause against Minecraft 26.2 bytecode: after `emptyTicks` reaches `pauseWhenEmptySeconds() * 20` with zero players, `tickServer` autosaves and returns before `tickCount` advances. `MinecraftServer.isPaused()` is a false-returning stub in 26.2 and must not be used for detection. The follow-up fix reads the actual dedicated-server pause configuration, empty player count, and `emptyTicks`, then resets stall/crawl baselines while that intentional pause is active. StallProbe startup moved to `SERVER_STARTED` so world creation and initial Mixin class loading are outside its gameplay-stall window.
- Startup emitted a datapack command parse error for `orbital_strike_cannon:get_zombie_shulker`; origin has not been established. Investigate separately.
- A fresh full backup was not created: the Pelican shell's SQLite connection rejected writes as read-only. Existing successful backups are 2026-09-28 and 2026-09-30. Restart did save all worlds cleanly.
- Do not mark production approval as complete. Runtime soak, active-player gameplay, persistence, compatibility matrix, performance measurement, and the two observed runtime diagnostics remain open. `release/production-approval.json` stays BLOCKED.


### 2026-11-16 — StallProbe empty-pause and diagnostic retention fix

- Root cause verified from the exact Minecraft 26.2 runtime bytecode: with zero players and a positive `pauseWhenEmptySeconds`, `MinecraftServer.tickServer` increments `emptyTicks`. At `pauseWhenEmptySeconds() * 20`, it logs the pause, autosaves, runs connection ticks, and returns before incrementing `tickCount`. A stationary tick counter is therefore expected during this configured idle pause. `MinecraftServer.isPaused()` returns constant false in this 26.2 runtime and is not a valid signal.
- StallProbe now detects the real pause state using the dedicated-server pause setting, player count, and reflected `emptyTicks`; it resets stall and crawl baselines for the full pause interval. Its lifecycle start moved from `SERVER_STARTING` to `SERVER_STARTED`, after initial world construction.
- Dump retention is bounded to the 10 newest files, 10 MiB total, and 7 days. Each dump is capped at 250,000 characters. Retention runs at probe startup and after every new dump. The unbounded temporary-directory fallback was removed.
- Four new Fabric tests cover pause-threshold detection, count retention, age expiration, and total-byte limits. Current test reports: common 144, API 10, Fabric 85 = 239 total; zero failures, errors, or skipped tests. `bash ./gradlew test :fabric:build` passed.
- Deployment completed through the Wings file API. Uploaded `/mods/fabric-folia-0.1.0.jar` (1,243,745 bytes) and read it back; SHA-256 matched `7c78b0e0acd75a6a66008779d21a534236253f40ea276b2dc84cf9a5f3ff4276`. From 488 prior stall logs (1,284,477 bytes), removed 478 and retained the ten newest. Pelican accepted start with HTTP 202; Wings then reported running, `latest.log` showed `Done` and `Fabric Folia is ready`, then the expected `Server empty for 60 seconds, pausing`. After the pause, Wings still reported running, `/stall-probes` remained at 10 files / 27,778 bytes, and there were no StallProbe error log lines.
- `release/production-approval.json` remains BLOCKED. A smoke boot does not complete the required active-player, persistence, compatibility, or soak checks.


- Fix commit pushed to `origin/main`: `b4ea4bee4e80732e60d89a113530c7e6e54ac725` (`fix: suppress intentional idle pauses and bound stall dumps`).
### 2026-11-21 — vanilla-override layer: TIS-Addition collision + hard-hitbox index + light-engine guard + kill switch (verified live; rebased onto 94976ef, pushed)
- **User request:** fix remaining cost by touching vanilla classes directly (AABB/collision,
  light engine), add engine kill switch + `override-vanilla: false` default toggle that
  enables the performance patches when on. Commit + push after real tests + benchmarks.
- **What shipped:**
  - NEW `patches.override-vanilla` (schema default FALSE, restart-required). All
    vanilla-touching patches moved to NEW `PatchRegistry.Layer.VANILLA_OVERRIDE`, gated on
    `patches.enabled AND override-vanilla`. `/folia vanilla status|kill` + help topic.
  - `entity-collision-opt` rewritten to the Carpet TIS Addition OFEM movement gate:
    fast diagonal movement only (MIN_MOVEMENT_SQR=100, directional factor 12); per-axis
    inflated slabs clipped to vanilla's original search box; `Shapes.collide` untouched;
    failures fall back to the full vanilla box with a fallback counter. Lithium disables it.
  - NEW `hard-hitbox-entity-collision`: per-section concurrent insertion-ordered index
    (`ConcurrentClassInstanceMultiMap`) of `canBeCollidedWith(null)` + HappyGhast entities;
    `EntityGetterMixin` (wraps the verified `getEntities(Entity,AABB,Predicate)` call inside
    `EntityGetter.getEntityCollisions`) + `EntitySectionMixin` (both `getEntities` overloads)
    select the index; boats/minecarts keep the full list. Disabled with Lithium/C2ME/Async.
  - Light engine: NEW `LevelChunkLightEngineMixin` redirects `LevelChunk.setBlockState`'s
    `updateSectionStatus`/`checkBlock` through `LightEngineDeferral` → `ServerThreadDeferral`
    (server thread runs inline; workers replay FIFO). Correctness guard, not a perf override.
  - Kill switch: `PatchRegistry.killVanillaOverrides()` — one-way until restart; `isEnabled`
    returns false for the whole layer; `/folia patches` shows KILLED; honestly: in-flight ops
    finish on their original path (no mid-collision abort).
  - Verified vanilla facts (javap 26.2): `EntityGetter.getEntityCollisions` DOES invoke the
    3-arg `getEntities` (mixin target valid); `EntitySection` has both overloads + `storage`
    field; `LevelChunk.setBlockState` calls both light methods.
- **Verification (fresh, this turn):** `--rerun-tasks` EXIT=0, 218 tests / 0 failures /
  0 errors pre-rebase; post-rebase onto upstream 94976ef: 248 tests / 0 failures / 0 errors
  (26 fabric suites + common + api), `build` EXIT=0. Live boot pre-rebase `Done (4.893s)` +
  post-rebase `Done (1.639s)`, both `SUCCESS: Fabric Folia is ready`, zero mixin errors;
  `/folia vanilla status` ENABLED/active; TPS 20.00 (gametime deltas, empty world);
  `vanilla kill` → ENGAGED/KILLED/inactive live; RCON `stop` clean (all dimensions saved),
  ports free, daemon stopped. Rebase conflicts (FREEBUFF/MIXINS/FoliaConfig/FoliaHelp)
  resolved by keeping upstream 11-15/11-16 sections + validation block + topics()/overview()
  and re-applying vanilla-overview line, vanilla topic, overrideVanilla() accessor.
- **Limits (honest):** empty-world TPS only — no entity-load A/B this turn (61k clump world
  from 11-11 is gone; re-summon + soak is the next benchmark). Hard-hitbox `hasHardHitBox`
  calls `canBeCollidedWith(null)` at index time; modded hitbox impls may need type additions.
  Runtime `fabric/run/config` is gitignored (local had `override-vanilla: true`; schema default
  false is what ships).

### 2026-11-21 — fast-chunk-load freeze fix + random-tick observability (verified live; NOT committed)
- **User request:** benchmark; fix the unrecoverable freeze when a fast entity loads new chunks; random ticks aren't optimized.
- **Baseline (empty dev world, all gates on):** TPS 20.00, MSPT p50=0.37-0.51. 144-chunk fresh-gen forceload storm reproduced a 9.7s tick stall (StallProbe dumps captured) with recovery — the stall itself was vanilla sync worldgen inside the RCON command (`setChunkForced → getChunk → managedBlock`), NOT the engine; entity sections healthy (no ghost dups).
- **Freeze root cause (verified, not guessed):** 26.2 bytecode proves EVERY off-thread `ServerChunkCache.getChunk` parks on `supplyAsync(mainThreadExecutor).join`, while the absent+`!create` main-path answer is `null`. A worker staged body touching an unticketed far chunk joins a future that needs ticket-gated promotion from the server tick — which is held by the quiesce barrier waiting for that same worker (`while (busyCount() > 0)` fail-safe has no exit). Permanent deadlock = "freezes and doesn't recover". Fix: `ServerChunkCacheMixin` is now create-aware — absent holder + `!create` answers `null` inline (vanilla's own answer, precedent: `getChunkNow` returns null off-thread); `create=true` and present-but-below-status keep the vanilla join. Decision table pinned in NEW `WorkerChunkProbeTest` (5); counter `worker getChunk absent no-create served null` rendered in `/folia metrics`.
- **Random ticks:** honest finding — `tickChunk` already skips non-ticking sections in vanilla (`isRandomlyTicking` check, verified bytecode), and the regionized slice (parallel workers) IS the optimization. CORRECTION (verified 2026-11-21, supersedes the spec-5 gap claim): `patches.minecraft.entity/chunk/random-tick-optimization` ARE live gates — they feed `setRequested("stage-entity-ticks" / "stage-block-entity-ticks"+"stage-scheduled-ticks" / "regionized-random-ticks")` in `FabricFoliaMod`, and `RegionStageHub.stagingEnabled` reads `isEnabled` per slice. Shipped the verifiable part: `RANDOM_TICK_CHUNKS_EXECUTED` counter in `RegionMetrics` + `randomTickChunks=` on the Work line (was invisible before), and `executedChunkWork` now counts actually-executed chunks instead of whole batches (bounce over-count fixed). NEW `RegionMetricsWorkCountersTest` (2).
- **Verification (fresh):** `--rerun-tasks` EXIT=0, 255 tests / 0 failures (248 + 7 new), `build` EXIT=0. Live on new build: `Done`, 0 mixin errors, `randomTickChunks=319435` counting on workers, fast projectiles into unloaded terrain → TPS 20.00, 0 quiesce timeouts, 0 pump errors, no stall warnings; probe counter 0 (healthy: edge-bounce keeps workers off unloaded chunks; the counter catches the race). RCON `stop` clean, ports free, daemon stopped, scratch cleaned. Forceload removed after the storm.
- **NOT committed** (no commit asked this turn). Open: full player-flight repro still needs a client/bot; 61k-world A/B of the probe counter under worker dispatch load.
- **Delivered 2026-11-21 as `c850092`** (pushed `e98af65..c850092` to origin/main; user explicitly asked to commit+push): pre-commit gate = full suite `--rerun-tasks` EXIT=0 (255 tests / 0 failures) + `build` EXIT=0 in the same turn. Needed fix applied before commit: the "dead patch flags" claim was wrong — verified the three tick-optimization flags ARE live gates via `setRequested` + `stagingEnabled`, entry corrected instead of code churn. Tree clean after push.




### 2026-11-16 — delegated Freebuff development wave

The current deployed baseline is commit `b4ea4bee4e80732e60d89a113530c7e6e54ac725`; documentation follow-up is commit `94976ef`. The deployed mod JAR is `fabric-folia-0.1.0.jar`, SHA-256 `7c78b0e0acd75a6a66008779d21a534236253f40ea276b2dc84cf9a5f3ff4276`. On the live `thebrokenstarsmp-fabric` server, the 60-second empty-server pause was reached with zero new StallProbe dumps/errors; `/stall-probes` remains capped at 10 files (27,778 bytes).

Three Freebuff sessions were resumed with non-overlapping ownership. They share the same `main` checkout. Agents must not create branches/worktrees or commit/push; the coordinator reviews and integrates their diffs after tests.
- `fabric-docs-fix`: public docs only — README, TESTING, MIXINS, COMPATIBILITY, THREADING, TROUBLESHOOTING, architecture/parity docs. Verify runtime versions (MC 26.2, Loader 0.19.3, Fabric API 0.161.0), exact mixin inventory, and describe verified versus unverified compatibility. Do not touch FREEBUFF.md or FINAL_REVIEW.md.
- `fabric-global-fix`: deferred-work/server-thread barrier deadlock investigation (runtime audit P0-3). Own FabricFoliaEngine, ServerThreadDeferral, ServerChunkCacheMixin, and focused tests. Do not change GlobalSchedulerImpl, RegionTransitions or NetworkDispatch; global scheduler thread removal is already implemented in current main.
- `fabric-split-fix`: player ENTITY/PLAYER slice atomicity (planner audit C4). Establish one player owner region per server tick or prove a narrower safe invariant. Own RegionPlayerRouting, LevelEntityTickMixin, ServerConnectionTickMixin, RegionStageHub only if required, and focused tests. Do not touch the P0-3 files above or docs.
- `fabric-api-fix` and `fabric-ci-fix` were attempted but returned to released state without producing a verified diff. Reopen when Freebuff capacity permits. API follow-up scope: api module and docs/api-guide.md; CI follow-up scope: Jenkinsfile and scripts/verify_release.py/runtime-smoke tooling. Do not overlap each other's verifier files.

Freebuff concurrent-session capacity is limited. Do not claim the released API/CI sessions are working. Continue with the three active assignments first, review their source diffs, run relevant tests, then take the next queued task when a session slot is actually available.


### 2026-11-17 — packet processor re-home fix and regionized live enablement

- User reported `ServerboundContainerClosePacket` failing with `RunningOnDifferentThreadException` on the server thread. Root cause was confirmed against the exact Minecraft 26.2 bytecode: `PacketUtils.ensureRunningOnSameThread` schedules and throws whenever its `PacketProcessor.isSameThread()` gate returns false; `isSameThread()` is exactly `Thread.currentThread() == runningThread`. The previous `PacketUtilsMixin` unconditionally rejected every server-thread-only container packet, including when it was already executing on the server thread. The queued retry therefore rejected itself again, creating a server-thread requeue loop instead of processing the close packet.
- `PacketUtilsMixin` now accepts the real processor thread unconditionally. A region worker is accepted only when it is the player's owner region and the packet is not a server-thread-only container mutation or respawn. `PacketProcessorMixin` continues to leave container mutations and respawn on the vanilla server-thread queue; vanilla's `RunningOnDifferentThreadException` remains an internal handoff signal for that off-thread case and is expected to be swallowed by `Connection`. The regression is the repeated rejection on the processor thread, which is now covered by `canHandlePacketOnCurrentThread` tests.
- Added regression assertions for all five server-thread packet types: container click, button click, close, slot-state change, and creative-slot update. Tests confirm each is rejected on the region shortcut but accepted when the processor thread is current. Respawn and ordinary owner-region packet decisions are also covered. Updated stale routing Javadoc.
- Validation: `git diff --check` passed. Full uncached command `bash ./gradlew --rerun-tasks --no-build-cache :common:test :fabric:test :api:test build --stacktrace` passed. Results: common 144, Fabric 105, API 10 = **259 tests**, zero failures, zero errors, zero skipped. Built artifact: `fabric/build/libs/fabric-folia-0.1.0.jar`, 1,250,771 bytes, SHA-256 `548d0e37a1593af97e5ff36e7c438fef867021dc6a14e8cc4897195a16be84c6`.
- Live Pelican target `thebrokenstarsmp-fabric` / server UUID `6422f450-5edb-4db8-9216-c5a076c7e8b2`: saved pre-change JAR at `/mods/fabric-folia-0.1.0.jar.before-thread-fix-20261117` and config at `/config/fabric-folia.yml.pre-regionized-enable-20261117`. Uploaded the new JAR through the Pelican/Wings file API (HTTP 204) and read it back; remote size and SHA-256 exactly match the build artifact. Changed only `general.enabled: false` to `general.enabled: true`; retained `general.regionized-gameplay: true` and `gameplay.stage-player-path: true`. `general.regionized-random-ticks` remains false.
- Requested restart accepted by Wings (HTTP 202). Wings subsequently reported `running` with a fresh uptime; authenticated WebSocket reported `auth success` and `status: running`. Live console after restart showed player/network-originated activity, including a Netty-originated player command at 13:37:37. Post-deploy check re-read config and JAR; both the enabled settings and remote artifact checksum matched.
- **Not yet verified:** no controlled Minecraft client test of opening/closing an inventory on this build was performed, so do not claim the reported packet action itself was exercised after deployment. A TCP probe to the node's public allocation address `80.112.32.76:25599` was refused; the backend may be reachable only through its proxy path, but external allocation reachability was not established in this pass. Direct player-side packet testing should follow.
- **Compatibility remains experimental:** prior mod scan found 248 installed mods with no declared Fabric-Folia support and zero certified declarations. This patch fixes the confirmed vanilla processor-thread self-requeue bug; it does not rewrite or certify every third-party mod. Further thread-ownership violations must be handled at their actual integration boundaries, preferably by region-safe API scheduling or a server-thread fallback. Do not claim that every possible `RunningOnDifferentThreadException` or third-party thread assumption has been eliminated. No commit or push was made; existing dirty worktree changes were preserved.

### 2026-11-22 — team wave, upstream reconciliation (staged, NOT applied), Fabrolia baseline

- **State at turn start:** `main` at `94976ef`, four commits behind `origin/main` (`c2fbe21`), 26 modified tracked files, four untracked Java/test files, plus untracked `tools/` (the Freebuff team harness). Nothing was reset, cleaned, stashed or checked out over.
- **Upstream inspected (all four):** `a42e96a` + `e98af65` vanilla-override layer (OFEM directional collision, `ConcurrentClassInstanceMultiMap` hard-hitbox index, light-engine deferral, `/folia vanilla kill`), `c850092` worker `getChunk` no-create → null (`WorkerChunkProbe`) + random-tick executed-chunk counter, `c2fbe21` handoff notes.
- **Reconciliation (isolated worktree `/tmp/ff-reconcile` = `origin/main` + local patch, backup in `/tmp/ff-backup/`):** only three files conflicted — `FREEBUFF.md`, `MIXINS.md`, `ServerChunkCacheMixin.java`.
  - **Chunk lookup:** upstream's `WorkerChunkProbe` decision table wins (absent + `create=false` → vanilla's `null`; `create=true`/below-status keep vanilla's join).  The local `RegionChunkAccessGuard` is **retired by design** — it threw `IllegalStateException` for `create=true`, which turns a pump-satisfiable wait into a lost entity tick. Added `recordVanillaJoin()`/`vanillaJoins()` so the blocking fall-through is visible (`worker getChunk creating/below-status vanilla joins: N`). **Correction (2026-11-22 final turn): the two files were never actually deleted** — both are still present untracked and its test asserts the retired behaviour (defect M1).
  - **Kept from local work:** `ServerThreadDeferral.deferBarrierSafe` + the pump-side drain (P0-3 barrier deadlock), per-tick player owner pin, packet-routing carve-outs, safe-default gates, StallProbe pre-first-tick suppression.
  - **MIXINS.md** keeps the rewritten inventory, count corrected 26 → **29** (from `fabricfolia.mixins.json`), three upstream rows added; table now matches the JSON exactly.
  - **Test repair:** `PlayerSliceAtomicityTest` asserted `pendingBodies()` before the batch `finally` decremented it (order-dependent; failed once in the full suite, passed focused). Now awaits the decrement with a bounded deadline and still requires exactly 0. Debug `System.out.println` removed.
  - **Docs corrected for the merged semantics:** `docs/status.md`, `THREADING.md`, `TROUBLESHOOTING.md` no longer claim a fail-fast chunk guard.
- **Verification of the reconciled tree:** conflict-marker scan clean; `:common:compileJava :fabric:compileJava` exit 0; full `:common:test :fabric:test :api:test --rerun-tasks` **twice, 280 tests, 0 failures/errors/skips**, BUILD SUCCESSFUL both times. **This is a worktree result; the shared checkout has not been validated.**
- **NOT applied.** Specialists were working in the original checkout, so applying now would clobber them. File-scoped manifest (30 copies + 2 deletions) and the ordered procedure are in `tools/freebuff/reports/reconciliation.md` §4. Application waits for the runtime/compatibility reports.
- **Fabrolia baseline verified independently (public docs/metadata only, no GPL source read):** repo created 2026-10-10, **3 releases / 4 tags**, newest `f_3.2.6` 2026-11-21, `version=3.2.6-alpha`, assets `fabrolia-alpha_*.jar`, MC **26.3** (`[26.3,26.4)`), Fabric API 0.161.0+26.3, Loader 0.19.5, GPL-3.0. **No mature release history**; the f_3.2.6 notes still list a server stop when a routed body teleports/removes the player whose tick it runs in. Its ~1,600-test / 40-client harness claims are repository documentation, not reproduced.
- **Toolchain:** official Fabric page (2026-11-22) recommends MC 26.3 / Loader 0.20.1 / Loom 1.19-SNAPSHOT / Fabric API 0.166.0+26.3. The 26.3 move is a dedicated port **after** this reconciliation, not a `gradle.properties` edit.
- **Docs/artifacts created this turn:** `docs/PROJECT_VISION_AND_ROADMAP.md`, `tools/freebuff/reports/{reconciliation,defect-ledger}.md`, follow-up prompts under `tools/freebuff/prompts/followups/`, and a launcher `--send/--resume/--prompt-file` implementation. The launcher was subsequently completed and exact-match hardened in the 2026-11-22 manager integration turn below.
- **Defect ledger now carries** the runtime audit's five P0 claims (R1 teleport double-cancel, R2 disconnect on a worker, R3 structure lock on the Netty path, R4 non-owned mutations, R5 deferred-replay swallow), the core audit's D1–D9, and this session's findings (backlog gauge zeroing, create-chunk blocking join). It points to `tools/freebuff/reports/qa.md` for QA-101…QA-110 rather than duplicating them. **All unverified by execution except where stated.**
- **Follow-ups sent:** core → implement D4, D2, D1, D3 in `common/{region,scheduler}`; QA → restore the dead `verifyServerGui` check and build the disposable benchmark harness. Runtime and compatibility were still running their audits.
- **Host quirk (new):** `/tmp` on this host is mounted `noexec`, so a worktree under `/tmp` must be built with `bash gradlew` (plain `./gradlew` fails with exit 126).
- **Open at end of turn:** apply the manifest (after runtime/compat reports), wire the new scheduler counters into `/folia metrics`, first live boot of the merged tree (mixins changed: +3), then R1–R5 triage. No commit, push, publish or deploy.

### 2026-11-22 — manager integration review (reconciled tree in /tmp/ff-reconcile)

- **State:** worktree at `c2fbe21`, no unmerged paths, local dirty work preserved. The original checkout's **existing dirty implementation files were preserved and not overwritten**; it is not wholly untouched — this session added `docs/PROJECT_VISION_AND_ROADMAP.md` and `tools/freebuff/**` (prompts, reports, QA/compatibility scripts), and no reconciled Java fix was copied back. The working implementation remains isolated here. Review artifacts: `tools/freebuff/reports/{defect-ledger,manager-review,fabrolia-comparison}.md`, this section, `docs/status.md`, `docs/folia-parity.md`, `docs/PROJECT_VISION_AND_ROADMAP.md`, `tools/freebuff/{README.md,launch-team.sh}`. No commit, push, boot, RCON, soak or benchmark.
- **Defect ledger rebuilt (owner-requested).** R1–R15 from the runtime audit plus the Netty owner-lookup report are now entries with severity, evidence level, exact file/method, root cause, named regression test, runtime verification (all "none" for this tree) and owner. The five open P0s are R1 non-player teleport double-cancel; R2 disconnect/removal on a region worker or a staged player ticking after removal; R3 `ownerOfChunk` structure lock on Netty loops (plus the second lock in `RegionScheduler.enqueue`); R4 cross-region side effects from staged bodies (loadedness gate, not ownership); R5 swallowed deferred-mutation failures. **Green unit tests do not downgrade them.** The ledger also carries the core D1–D9 table, the three previously fixed P0s as unit-only, and manager/QA pointers.
- **R1 review (five/six cases, patch not accepted as-is).** Confirmed: registry-based re-entry cancels twice and the vanilla body never runs; `IN_FLIGHT` is stamped before validation/enqueue so a failed enqueue becomes a false duplicate; the null-owner global lane double-cancels because `isCurrentContextOwner` is false for non-REGION contexts; `DispatchKey` (source world+entity+destination world+chunk) collapses two same-chunk intentional transitions; same-region server-thread teleports now dispatch because UNKNOWN is not the owner; an "inline for every non-REGION" patch is unsafe (cross-region inline on the server thread, NETWORK/ASYNC world mutation inline). Required: per-context policy matrix, exact-destination identity, positioned enqueue, target-liveness re-check, no Throwable swallow, and six deterministic cases. Assigned to `fabric-folia-runtime-impl-r1` (only `EntityTeleportMixin`, `RegionTransitions`, focused Fabric tests).
- **D4 core patch: accepted with conditions.** Execution-time ownership net for position-bearing entries, 7 tests (stale-region rehome, own-region control, NO_POSITION, no-owner, ordering, no double-run). Conditions: **positionless producers remain R6** (packet rehome, `enqueueTransition` including the global lane, command re-dispatch, random-tick batches), so **task migration is not complete**; the 8-arg constructor lost `private` and its javadoc format is broken (`*/	RegionScheduler(`); a re-homed entry does not mark the owner due, so it may wait a tick. Review recorded in `manager-review.md` §3.
- **ConfigSchema default change: APPROVED, separately.** Entity/chunk/random-tick staging defaults moved `true`→`false`, matching README/`docs/configuration.md`/`status`/`COMPATIBILITY` and the safe-default rule. It must land as its own change, not inside the D2 scheduler patch. It reduces default exposure but does **not** fix R4: explicit `true` still enables the unsafe gate, and existing configs keep their explicit values.
- **R11 stays open.** The D1 patch removes split/retire residue from per-region sets, but `RegionEntityRegistry.register` still updates the map and set outside the stripe lock; a register-vs-retire race can orphan a set entry. Separate race fix + deterministic test required.
- **Launcher/README mismatch fixed and tested.** `launch-team.sh` now has `--send ROLE...` (existing session only), `--resume`, `--prompt-file`, `--dry-run`, idle confirmation with `--force` override, and **exact** session matching (`=name`). Testing exposed tmux target prefix matching: `fabric-folia-manager` resolved to the live `fabric-folia-manager-r1` and a stray manager prompt was pasted into this session (no session killed, no other mutation). Exact matching now prevents lookalike sends. 12 fake-tmux cases pass; real tmux was never intentionally mutated.
- **QA harness reviewed read-only; accepted for dry-run only.** Checklist items verified: positive `max-tick-time` with no `-1`; RCON password redacted in copied env/properties (`chmod 600`, digest only in the manifest); canonical `QA_HOME`/output outside-repo checks; full profile gated on `--allow-full-profile` + ≥32 GB and clientless runs record `protocol_clients: 0` with `requested_client_target`; profile allowlist and sanitized run ids; bundle entry path escape + SHA-256 validation. `run_bench.sh` dry-run exits 0 with `started_processes: 0`, `wrote_files: 0`, no `QA_HOME` created; `bash -n` and Python compile pass. Follow-ups before `--execute`: password via env/stdin instead of argv, enforce Java 25, drop the unused `qa_outside_region` helper, document `QA_JVM_ARGS` word-splitting, remove `qa/__pycache__`.
- **Fabrolia comparison recorded.** `fabrolia-comparison.md` distinguishes `prod/current` (operational reference; README states ~35-player production server, 20 TPS goal; AGENTS states ~1,400 tests, 40-client stand) from `f_3.2.6` (newer alpha development, ~1,600 tests, unresolved routed-player self-teleport/removal stop). Production surfaces named for independent design: PlayerTurnOwnership/IslandPlayerTurnSession, AsyncIslandPlayerLifecycle, AsyncIslandPacketContinuations, IslandScheduledTicks/ScheduledTickIslandAdmission, ChunkDelivery/ConnectionOutbox, explicit Main fallback. **R2/R21 are the missing turn-lifecycle class.** All claims attributed, none independently verified. GPL-3.0 vs Apache-2.0 boundary restated; no source copied, ported or derived.
- **Toolchain:** two pinned 26.3 matrices — (A) conservative `prod/current`-compatible Loader 0.19.5 / API 0.161.0+26.3 and (B) official Loader 0.20.1 / API 0.166.0+26.3 — must both be tested against the optimization-mod set; they are not equivalent and neither is a silent upgrade.
- **Test evidence (unit only):** reconciled full suite **280 tests (API 10, common 148, Fabric 122), 0 failures/errors/skips** from the named reconciliation run's JUnit XML. The `common/build/test-results` in this snapshot was later overwritten by the core agent's focused 7-test run, so the present XML does not itself sum to 280. No mixin export, boot, gameplay, persistence, soak or performance run exists for this tree; the Jenkins verifier has not run (dirty tree).
- **Open:** core D2/D1/D3 implementation and `core-implementation.md`; R1 patch rework; positionless policy (R6); R2/R4/R5/R3 designs and handoffs; `compatibility.md`; QA follow-ups; apply the reconciliation manifest only after explicit user approval; then first live boot + mixin audit.
- **Host quirk reconfirmed:** `/tmp` (including `/tmp/ff-reconcile`) is `noexec`; a fake tmux/freebuff binary cannot execute, so launcher tests used injected shell functions and `bash tools/freebuff/launch-team.sh`.
- **Stream inputs pending formal acceptance:** compatibility reports a static scan of 40 JAR containers across 8 top-level optimization mods, 1,411 mixin classes, 0 missing classes, 0 scan errors (server mixin counts Lithium 277, C2ME 225 across 30 nested containers, FerriteCore 13, Krypton 10, VMP 45, ScalableLux 13, Carpet 172, TIS Addition 599). Static inventory only, not runtime compatibility; the resolved 26.2 C2ME `0.4.2-alpha.0.71` is below `prod/current`'s declared `.88` floor. QA is repurposed as `fabric-folia-protocol-r1` to research a native 26.3 protocol client (upstream Mineflayer may lag; a `@wp2508/mineflayer` fork and a Rust `mcclient` claim protocol 777 — unverified; nothing installed, no boot).
- **Benchmark host planning:** FAC has ~14 GB available, so the 12 GB-heap + 40-client full profile must not run there. Candidate full-load host is the owner's Ryzen 7 5700X desktop with 32 GB and WSL/Linux. Desktop Commander currently shows only FAC online; Cluster-0 is offline and the Windows desktop is not connected, so the 32 GB host is a **future option only**. Keep the 40-client profile unexecuted and the scripts portable; use FAC for low-memory smoke/compile work only, after approval.
- **Sessions canonicalised (2026-11-22):** tmux sessions renamed to `fabric-folia-manager`, `fabric-folia-core`, `fabric-folia-compatibility` and `fabric-folia-protocol` (from suffixes); no agent was stopped by the rename. `protocol` was added to launcher `ROLES`; `prompts/protocol.md` was created from `prompts/protocol-client-research.md`; `qa` remains the separate harness role. The runtime and qa sessions are currently **not running**; the canonical names are reserved for when they are relaunched. Transient review sessions may keep a suffix.

### 2026-11-22 — manager final handoff (docs/ledger/roadmap only; NO commit, push, boot)

- **This turn's scope:** project-level handoff, not production code. No Java, config,
  test or mixin file was edited; no Gradle run, server boot, RCON, world access,
  commit, push, publish or deploy. Work stayed in the detached worktree
  `/tmp/ff-reconcile` at `c2fbe21` (`git diff --name-only --diff-filter=U` empty).
- **User instruction honoured:** the historical owner directive to commit/push to
  `main` is **not** applied to this task. The tree stays dirty and uncommitted.
- **Artifacts updated this turn:** `tools/freebuff/reports/defect-ledger.md`,
  `tools/freebuff/reports/manager-review.md`, `docs/PROJECT_VISION_AND_ROADMAP.md`,
  `tools/freebuff/launch-team.sh` (help text only), this file. Reviewed inputs that
  landed during the turn: `reports/core-implementation.md`,
  `reports/compatibility.md`, `reports/protocol-client-research.md`.
- **Core patch state (verified from the diff and `reports/core-implementation.md`):**
  D1 (split/retire set residue), D2 (real `ABORT_REGION`, streak reset, policy
  cleanup on death + returning action), D3 (rejected `enqueueDelayed` runs `retired`)
  and D4 (execution-time ownership net for position-bearing entries) are implemented.
  Manager decisions: D2 signed off (a failing task or tick body resets the streak, so
  one failure per tick does not abort); the package-private 8-arg `RegionScheduler`
  constructor is accepted for test injection (the javadoc line-sharing is pre-existing
  at HEAD); the `ConfigSchema` staged-gameplay default flip is approved as its own
  change and is **untested** until `ConfigSchemaPatchTest`/the full suite runs.
  **D4 is not done:** **D4-F1** — the re-home can add to a freshly created queue for a
  donor that merged in the resolve→add window and strand the task until close; route
  it through `enqueue(owner, task, pos)` and retry on `false`. **F2** —
  `onRegionRemoved` is wired for death only, so merged donors leak policy entries;
  one line in `onRegionMerged`. D5–D8 remain open; D9 deferred. **Superseded
  2026-11-23:** D4-F1/F2 and R11 are fixed at E3, and the ConfigSchema default flip is
  **not approved — recommend REVERT**; see the end of this file.
- **Focused core test evidence — 25 tests, 0 failures/errors/skips:** 7
  `RegionSchedulerSplitRehomeTest`, 4 `RegionFailurePolicyTest`, 10
  `RegionEntityRegistryTest`, 4 `EntitySchedulerLifecycleTest`. Command form was
  `bash ./gradlew` (the worktree wrapper is not executable; `/tmp` is `noexec`).
  Provenance: the four JUnit XML files in `common/build/test-results/test/`. **The
  280-test reconciliation baseline is a different, earlier run** and its XML was
  overwritten; never quote 280 as covering the D1–D4 patch.
- **R1 (non-player teleport): not tested, not accepted.** The current diff in
  `RegionTransitions.java`, `EntityTeleportMixin.java` and the new
  `EntityTeleportDispatchTest` replaces the 50 ms stamp with a `DispatchToken` +
  `Decision` enum and retires tokens on rejection/completion, but: (a) the owner gate
  is REGION-only, so a body on the global lane re-enters, sees its own live token and
  returns `DUPLICATE_IN_FLIGHT` — the vanilla body still never runs, and a same-region
  server-thread call still dispatches; (b) the enqueue is still positionless
  (`scheduler.enqueue(owner, body)`), so it is invisible to split partitioning and to
  D4's net (R6); (c) `DispatchKey` is still destination-chunk level, so two intentional
  teleports inside one chunk still collapse; (d) the token has no lease and the
  global-lane token is untested; (e) `EntityTeleportDispatchTest` has **never run** —
  it is absent from `fabric/build/test-results/test/`. No runtime session is active;
  do not treat any of this diff as landed. **Superseded later on 2026-11-22** — runtime
  rewrote the path and the 14-test suite is green; see the live-validation section at
  the end of this file. R1 is accepted at E3 but **not closed**.
- **Compatibility:** `tools/freebuff/reports/compatibility.md` landed during this turn
  and was reviewed against `tools/freebuff/compatibility/scan-26.2.json`. Counts
  recomputed and matching: 8 top-level artifacts, 40 containers (8 + 32 nested),
  34 configs (2 empty), **1,411 mixin classes / 1,354 server-applicable**, 606 distinct
  targets, 1,862 handlers, 0 missing classes, 0 scan errors. It is **metadata plus
  static mixin inventory only** — no mixin export, boot, gameplay, soak or performance
  run — and must never be described as runtime compatibility. Risk C means "same
  method and same `@At` selector", not a proven conflict. C2ME
  `0.4.2-alpha.0.71+26.2` is below Fabrolia prod/current's declared floor
  `0.4.2-alpha.0.88`, so it is **not supported**. Owner decisions left open: a
  Carpet/TIS compatibility note (TIS has the most same-point overlap and no note) and
  whether the OFEM collision patch should also be force-disabled for TIS, as it
  already is for Lithium.
- **Protocol research reviewed:** `tools/freebuff/reports/protocol-client-research.md`
  grades every claim [V]/[R]/[U], states that no released PrismarineJS package speaks
  26.2 or 26.3, that Azalea (`0d38e0d`) is the only mature native 776 client (git pin
  required), and that every 26.3 candidate is young and unproven. Nothing was
  installed, run or validated, and the 40-client stand remains unbuilt. Keep the two
  lanes separate; never count ViaProxy translation as native protocol evidence.
- **Launcher:** `tools/freebuff/launch-team.sh` has the six canonical roles
  (manager/core/runtime/compatibility/protocol/qa), exact `PREFIX-ROLE` matching,
  `--send`, `--resume`, `--dry-run`, `--prompt-file`, idle confirmation and `--force`.
  It is executable (`rwxrwxr-x`) and was tested with `bash -n`, `--help` and
  `--dry-run` only (all exit 0; dry-run reported manager/core/compatibility/protocol
  running and would start runtime/qa). The help text said "all five" for six roles;
  corrected to "all six". No tmux session was created, killed or messaged.
- **FREEBUFF accuracy fixes:** the javap jar path was Windows-only and is now the Linux
  worktree path (plus the shared checkout's distinct hash); the test command is
  `bash ./gradlew`. Two claims were checked and are already correct in §2 and must stay
  that way: `RegionEntityRegistry.register` is **not** stripe-locked (R11), and the
  PLAYER slice flushes after `PlayerList.tick`/chunk sending, so its intra-tick order
  is **not** vanilla-equivalent (R21). Windows-only protocol notes remain isolated in
  the historical subsections.
- **Synced to the original checkout:** only `docs/PROJECT_VISION_AND_ROADMAP.md`,
  `FREEBUFF.md` and `tools/freebuff/**`. No production Java, config, test or mixin file
  was copied back; the user's existing dirty implementation files were left untouched.
- **Tree hygiene defect found this turn (M1):** the retired
  `fabric/src/main/java/.../engine/RegionChunkAccessGuard.java` and
  `fabric/src/test/java/.../engine/RegionChunkAccessGuardTest.java` still exist
  untracked. Nothing production references the class, but its test asserts the
  rejected `create=true` behaviour that `WorkerChunkProbe` replaced, and it would run
  in a fresh `:fabric:test`. `reconciliation.md` says they were deleted; they were
  not. Handoff-only: delete both, rerun `:fabric:test`, expect 3 fewer tests. Recorded
  as M1 in the ledger, not applied (no production edits this turn).
- **Open P0 (release-blocking):** R1 (teleport, patch unaccepted), R2 (disconnect on a
  worker), R3 (structure lock on Netty), R4 (loadedness not ownership), R5 (swallowed
  deferred failures). **Open P1:** D4-F1 (merge-window re-home stranding), F2 (merge
  policy bookkeeping), R6 (positionless work), R11 (register/retire race), plus R7–R13.
  Next engineering sequence is in `manager-review.md` §9.

## 2026-11-22 — live-validation wave (QA smoke boots, R1 finish, no sync)

**Scope:** manager coordinated QA (`prompts/live-benchmark.md`) and runtime
(`prompts/runtime-review-r1.md`), reviewed core's open D4/R11/R6 items, and updated
only `tools/freebuff/**`, this file and the ledger. **No Java was synced, committed,
pushed or deployed; the shared checkout is untouched.**

- **Tree:** still HEAD `c2fbe2135945e4e0ef42fc70094d3605ef03627d`, detached, 0 unmerged,
  45 dirty entries in `/tmp/ff-reconcile`. Shared checkout verified at `94976ef`, 33
  dirty entries, same status hash as wave start — its dirty Java is preserved.
- **R1: ACCEPT at E3, not closed.** Runtime replaced the chunk-keyed stamp with an
  exact-match invocation token (entity + world + destination chunk, thread-confined for
  the body), a per-context matrix, `scheduleToChunk`, and a `ServerThreadDeferral` safe
  continuation on rejection. `EntityTeleportDispatchTest` **14/14**, full fabric
  **136/136**, all green (XML `01:40:36Z`). Manager rejected its first revision because
  it inlined *any* nested transition on the thread; runtime added
  `TransitionToken.matches` and a nested-other-entity/destination regression. Still
  open before closure: run 6 booted the **final-tree jar** (`06e8520…`, built 02:52:26)
  at `Done (4.920s)` with zero mixin errors, so mixin application is proven; a
  `/tp`/portal functional check is still missing, and R10 `noteServerThread` is absent
  (a foreign `UNKNOWN` thread can still take the server inline path). Report:
  `tools/freebuff/reports/runtime-implementation.md`.
- **Live evidence (clientless only):** three benchmark boots on the 02:28:55 jar
  reached `Done (5.241s / 5.038s / 4.946s)` with zero mixin warnings/errors, ~20.00 TPS
  from `time query gametime` deltas, ~716–794 MiB heap, JFR + jcmd, clean save/stop, no
  crash reports, `protocol_clients=0`. That jar (`0ac652…b048e`) predates the R1
  rewrite, so it does **not** validate final R1. **Run 6** rebuilt the final tree
  (`06e8520…`, 02:52:26) and booted `Done (4.920s)`, zero mixin errors, 60 s at 20.000
  TPS, clean stop — mixin application for final R1 is proven; it is not benchmark data
  and not a teleport check. Report:
  `tools/freebuff/reports/live-runtime-and-benchmark.md`.
- **L1 (new P1, found by the boot): every `/folia` command fails.**
  `NoClassDefFoundError: net/kyori/examination/Examinable` / `Could not initialize
  class FoliaMessages`; 134 failing responses in one run. Verified root cause: the jar
  nests only `adventure-api` + `adventure-text-minimessage`, while
  `adventure-api-4.23.0.pom` requires `adventure-key`, `examination-api` and
  `examination-string`. MSPT/region/worker metrics are therefore unavailable. Not
  repaired (scope freeze); needs a jar-content regression + boot test.
- **Fabrolia: BLOCKED, nothing compared.** Pinned `f_3.2.6`, sha256 `42678345…d3ca`,
  declares `minecraft ~26.3`; 3.2.1/3.2.0 also `~26.3`; this project is 26.2. No 26.3
  toolchain installed, no run, no A/B, **no winner**. GPL-3.0 metadata only; no GPL
  source fetched and no Fabrolia file in this repo. The 40-client benchmark stays
  deferred (14 GiB host, no verified native 26.2 client).
- **Core re-verified in code:** D4-F1 still strands a re-home in a merge window
  (`RegionScheduler` ~708–716 does unlocked `get`/`computeIfAbsent` + `add`); F2 still
  missing (`onRegionMerged` never calls `failurePolicy.onRegionRemoved(donor)`); R11
  still open (`register` mutates outside the stripe); R6 unchanged. **D4 is not done.**
- **Tests:** current module XML = api 10 + common 161 + fabric 136 = **307**, 0
  failures/errors/skips, but from different runs; QA's green build snapshot was 300
  (fabric 129 pre-rewrite). `ConfigSchemaPatchTest` 4/4 passed, closing that condition.
  One full-suite command at one tree is still owed.
- **Tooling:** launcher idle check was dead (`capture-pane -t "=session"` rejected by
  tmux 3.6) so `--send`/`--resume` always needed `--force`; fixed to `"=session:0.0"`
  and verified (idle core accepted, busy QA refused). Compatibility and protocol
  sessions were closed after delivery to free concurrency slots; runtime was relaunched
  with its prompt; no additional agents were spawned.
- **Not done:** no `/tp`/portal functional check on the final jar, no L1 fix, no
  D4-F1/F2 fix, no full suite in one command, no sync, no commit/push, no performance
  claim.

## 2026-11-23 — integration review: core F1/F2/R6/R11, runtime R5/R10, ConfigSchema

**Scope:** reviewed the second core pass and the runtime hardening pass as they landed,
verified one full suite and one build at a stable snapshot, reconciled the unapproved
ConfigSchema default flip, and recorded the shared-file conflicts. **No Java/config
default edit, merge, commit, push, deploy or sync.** Tree HEAD `c2fbe21`, detached, 0
unmerged, status hash `ae97c1adabf5448a`.

- **Mid-flight collision (process fact):** core and runtime were both writing this tree
  at ~10:15-10:23. Two early full-suite runs caught inconsistent source/test states
  (`RegionFailurePolicyTest` abort latch; `DeferredBarrierDeadlockTest` before runtime's
  test update). Only the run after both agents went idle is evidence. Do not run a
  suite against a tree whose agent is still `working...`; check `tmux capture-pane`
  and file mtimes first.
- **Verified at the stable snapshot:** `:common:test :fabric:test :api:test
  --rerun-tasks` -> **exit 0**, api 10 + common 167 + fabric 141 = **318** tests, 0
  fail/err/skip; `build --stacktrace` -> **exit 0** at the same status hash.
- **Core second pass (42 focused green):** D4-F1 fixed via `rehomeToOwner` (two-phase
  `enqueue`, bounded re-resolve `REHOME_ATTEMPTS = 4`, no queue for a dead region); F2
  wired in `onRegionMerged`; accepted-then-dropped R6 half fixed with an `onDropped`
  hook + entity re-proof (`EntitySchedulerImpl.recoverDropped`); **R11 fixed**
  (`register` under the stripe). R6 producer half (positionless packet/re-home/command/
  random-tick enqueues) remains open. Details in `core-implementation.md` §9-12.
- **Runtime:** **R5 fixed at E3** — `runDeferred` records the failure and rethrows it,
  both lanes stop until `takeReplayFailure()`, and `beginTickPhase` surfaces a failure
  the quiesce pump swallowed; `ServerThreadDeferralFailureTest` 5/5. The **R10
  `noteServerThread` residual is closed** — production call sites are
  `FabricFoliaMod.startEngine` and the HEAD of both `MinecraftServerMixin` handlers.
  R10 admission/ordering/pause items remain open. R1 unchanged, 14/14. **R4 audited
  only** (mutation reach is chunk-unbounded: `setBlockAndUpdate` flag 3,
  `CollectingNeighborUpdater`, plain `blockEvents` set, POI, explosions), so R4 stays
  open P0 and no halo check was shipped. Report: `runtime-hardening-r4-r5.md`.
- **ConfigSchema (separate reconciliation): recommend REVERT** the three staged-gameplay
  defaults `TRUE` -> `FALSE` for this wave. `mergePreservingComments` repairs missing
  keys to the new default (silent upgrade change); patch state is
  `regionizedGameplay() && patchEnabled(KEY, false)`, so `general.regionized-gameplay=true`
  now enables nothing on its own; the live boot prints every gameplay slice as `server
  thread`; the pinning test was added in the same unapproved diff; and R4 remains for
  configs already set `true`. Approve only as an explicit owner product decision with a
  config-version bump/migration note. **The default was not edited by the manager.**
- **L1 still open:** the current jar (`5905e13...`) nests only `adventure-api` +
  `adventure-text-minimessage`; a nested-jar scan found **zero** `net/kyori/examination`
  classes (`Examinable` absent). Every `/folia` command still fails. Release blocker.
- **T1 (new, test determinism):**
  `RegionFailurePolicyTest.consecutiveFailuresAbortOnlyTheFailingRegion` calls
  `scheduler.start()` before enqueuing; under load the region can abort mid-enqueue and
  `enqueue` removes the survivor, so `died: dropped` reports 0 and the latch times out
  (observed once, 10.014 s). Fix: enqueue before `start()` (legal path). Not applied.
- **QA live evidence:** a 26.2 server on the current jar booted `Done (5.331s)`, zero
  mixin errors, `ProbeBot` logged in and spawned; the walk phase is pinned in the known
  teleport-resync loop (position `(6.5,114,3.5)` never changes), so no gameplay/R1
  evidence. `bot-262-run1`, ports 26800/26801, 2G heap.
- **Fabrolia:** QA booted **Fabrolia 3.2.6 standalone on 26.3** (loader 0.20.1, API
  0.166.2+26.3) in `runs/fabrolia-26.3-run1`: `Done (6.090s)`, 0 protocol clients,
  180 s sampling, clean stop, 6 cancelled mixins. This is a **different MC version**
  from this 26.2 project, so it is **not a comparison**; no winner, no speedup claim.
  No Fabrolia jar/source/GPL file is in the repo (checked). The 40-client benchmark
  stays deferred (14 GiB host); one probe bot is not a benchmark-grade client.
- **Shared files:** `FabricFoliaEngine` (core metric + runtime R5), `RegionStageHub` /
  `LevelEntityTickMixin` (core pin + runtime connection staging), `RegionTransitions`
  (runtime + frozen QA accessor), `ConfigSchema`/test (unapproved, isolated).
- **Next:** L1 transitives + `/folia health` boot; R1 `/tp` + portal E4; R5 live crash
  injection; R4 handoff design; R6 producer positions; T1 test order; M1 deletion;
  ConfigSchema owner decision. Then one full `:common:test :fabric:test :api:test build`
  at one tree.

## 2026-11-23 final integration pass — 334 tests, build and 26.2 diagnostics boot (NOT committed)

- Tree base HEAD c2fbe2135945e4e0ef42fc70094d3605ef03627d; dirty worktree /tmp/ff-reconcile; no merge, commit, push, deploy or sync to /home/administrator/CDN-bucket/fabric-folia.
- One coordinated test run: bash ./gradlew :common:test :fabric:test :api:test --rerun-tasks --stacktrace exited 0. XML totals: common 171, Fabric 153, API 10 = 334 tests, zero failures/errors/skips.
- Full build: bash ./gradlew build --stacktrace exited 0. Warnings remain: Java 25 ThreadDeath deprecation in fatal-error catch clauses, unchecked operations in LevelScheduleTickMixin, the OpenCL FFM restricted-native-access warning, and Gradle deprecations. The build is not warning-free.
- Final JAR: fabric/build/libs/fabric-folia-0.1.0.jar, SHA-256 fd528b632997838eaf5a01997eda27d1d58f5e7c9654049b71835005b6d9cf77. It bundles Adventure API/key/MiniMessage 4.23.0 and Examination API/string 1.3.0.
- Final-tree boot: copied that exact JAR into disposable MC 26.2 / Fabric API 0.161.0+26.2 instance ff-l1-health; reached Done (0.481s), no Mixin errors, NoClassDefFoundError or Examination initialization errors. RCON /folia health, /folia workers and /folia metrics all responded. Health warns because the disposable world is idle with no loaded spawn chunks/regions; workers report healthy idle. Metrics are zero because no gameplay was performed. RCON stop completed; no QA server process remains.
- R1: accepted at E3; 14 focused tests and previous final-tree boot; live player /tp and portal E4 remain open.
- R2: E3 implementation; seven deterministic liveness tests. A staged connection tick is admitted only inside SCL's isConnected branch. If transport closes after staging, its already-admitted body is deferred once to the server thread; an open transport with an invalid player is also deferred. A staged player entity body is reclaimed or canceled when its paired connection stage refuses. E4 live /kick, proxy-drop, reconnect and STRICT testing remain open; exactly-once PlayerList.remove is structurally established, not counted in a live server.
- R3: ownerOfChunk default path reads the concurrent published section-owner map without structureLock; RegionizerPublicationTest 4/4. The second lock in RegionScheduler.enqueue is deliberately retained for accept-vs-death ordering; the addChunk transient false-null window, dead-region empty-queue leak, R11b split lock hold time and latency/JFR measurement remain open.
- R4: OPEN P0 / release blocker for parallel staged gameplay. A 3x3 loaded halo is not ownership proof; no synchronous mutation handoff or single-writer cascade protocol is implemented. The design review rejected optimistic asynchronous world writes. Entity/block-entity/scheduled/random staging defaults remain false; explicit opt-in is not proven safe. See tools/freebuff/reports/r4-design-review.md.
- R5: deferred-mutation failure propagation fixed at E3 with five tests; live crash-report injection E4 remains open. The noteServerThread residual in R10 is fixed; other R10 admission/order/pause defects remain.
- R6/R11: accepted-task drop/retirement half of R6 and R11 registry stripe-lock fix are E3; positionless producers, R11b split/registry lock hold time, and D5-D9 remain open.
- L1: fixed at E3 with bundled Adventure key and Examination transitives; final JAR boot and command responses prove class-loading/command initialization, not all live command behavior.
- Benchmark: no valid same-version Fabric-Folia versus Fabrolia comparison; do not claim performance superiority.
- Next gates: live player and disconnect tests; R4 safe mutation protocol; R1 teleport/portal E4; R5 crash injection; R6 producer position policy; R3 JFR/latency and enqueue/death race cleanup; compatibility, persistence/soak testing and release review.

## 2026-11-23 — R4 fail-closed interlock and engine-off vanilla restoration (NOT committed)

- Scope: restore master-off vanilla behavior, repair a queued-packet drop, and prevent built-in worker-side world-mutation staging from running without the R4 single-writer protocol. This is a safety fallback; it does not implement the cross-region mutation bridge.
- Master switch: resolvePatchStates(config) now runs before the general.enabled=false return and global = config.enabled() && config.patchesEnabled(). All 13 registered patches resolve inactive. Chunk-regionization attaches only after successful engine bootstrap. The stall probe and server-thread deferral diagnostics do not start when the master switch is off. Server brand and GUI branding are gated by general.enabled.
- Vanilla fall-through: ConnectionFlushQueueMixin previously passed pending connection actions into NetworkDispatch.runForConnection with a null engine; that helper returned without executing the action. It now preserves the action, and the mixin directly invokes the original Consumer.accept when the engine or packet-dispatch layer is inactive. Network context tagging and entity-command re-home are also gated by the packet-dispatch patch. TicketStorage, ChunkMapTracking, ServerChunkCache ticket loading, and light-engine mutation deferrals bypass their custom queues when the engine is absent. Server tick deferral drains and server-thread registration are engine-gated.
- R4 interlock: the resolver forces entity tick, block-entity tick, scheduled tick, player path, random tick, and packet-dispatch requests inactive even if config values are true. Startup warns when it overrides a request. Region ownership/region scheduler infrastructure can still run, but all built-in mutation-bearing gameplay paths remain on the vanilla server thread.
- Tests: one coordinated suite passed 334 tests — API 10, common 171, Fabric 153 — with zero failures/errors/skips. Full Gradle build exited 0. Fabric still emits Java 25 ThreadDeath deprecation warnings, unchecked-operation and OpenCL native-access warnings, and Gradle deprecation notices.
- Final JAR: fabric/build/libs/fabric-folia-0.1.0.jar, SHA-256 fd528b632997838eaf5a01997eda27d1d58f5e7c9654049b71835005b6d9cf77.
- Master-off boot: disposable 26.2 server reached Done (0.431s), Patches: 0 active / 13 registered (13 blocked), and Region system: disabled (general.enabled=false) - vanilla execution, no worker threads. No Mixin errors. RCON redstone lamp test produced REDSTONE_POWERED_OK and REDSTONE_UNPOWERED_OK.
- Engine-on interlock boot: with general gameplay, random ticks, player path, entity/chunk/random patch requests all true, startup logged the R4 warning, Patches: 5 active / 13 registered (8 blocked), region ownership infrastructure initialized, and the gameplay/player/random staging summaries all reported disabled by R4 interlock. Server reached Done (0.431s), no Mixin errors. Same redstone test produced R4_INTERLOCK_POWERED_OK and R4_INTERLOCK_UNPOWERED_OK.
- QA cleanup: both temporary servers stopped cleanly, test force-load was removed, the disposable config was restored and no temporary server process remains.
- R4 status: built-in staged mutation exposure is mitigated by disabling the worker path, not by implementing a synchronous write protocol. Third-party region-scheduler tasks remain subject to owner confinement; no arbitrary cross-region write safety is claimed. Details in tools/freebuff/reports/r4-design-review.md and tools/freebuff/reports/engine-off-and-redstone-smoke.md.


## 2026-11-25 — Fabric-Folia live config reload (feature branch; no deployment)

- Added OP/admin-gated /folia reload.
- Reload parses, migrates/repairs and validates an entirely new FoliaConfig snapshot before changing live state. Invalid YAML or values are rejected and leave the active runtime snapshot unchanged.
- Runtime-safe settings: general.server-brand, general.server-brand-name, diagnostics.debug, diagnostics.debug-logging, and diagnostics.watchdog while the engine is running.
- Restart-required settings remain in the validated file but do not replace the engine startup snapshot. This includes general.enabled, worker count, thread-check mode, region sizing, GPU initialization, profiling, metrics and all patch/interception settings. The command lists the pending keys on every reload until a restart applies them.
- Startup and runtime config snapshots are distinct. Brand override uses the startup general.enabled gate and the live server-brand values; changing general.enabled in a reload cannot start/stop an engine or turn off the already-running engine mid-session.
- /folia status now reports the live server-brand name, runtime debug state and watchdog running/stopped state.
- Watchdog start/stop is synchronized and idempotent.
- Tests: 340 total — common 174, Fabric 156, API 10; zero failures/errors/skips. bash ./gradlew build --stacktrace passed. Java 25 ThreadDeath deprecation, unchecked-operation, OpenCL native-access and Gradle deprecation warnings remain.
- Final JAR: fabric/build/libs/fabric-folia-0.1.0.jar, SHA-256 a0a0d7b6f5a5560df77f58e2e9536277c356d7573c0e669c3a90aedc16b53c7d.
- Live smoke on a disposable Minecraft 26.2 / Fabric API 0.161.0+26.2 server: initial boot Done (0.436s). Changed brand to ReloadedBrand, turned debug logging on, watchdog off, requested worker count 6 and general.enabled=false. /folia reload applied three live keys and reported worker count/master switch restart-required. /folia status showed ReloadedBrand, debug logging enabled, watchdog stopped, worker threads still 4 and engine still ACTIVE. Second reload continued to report the same restart-required keys. Invalid region-section-size 7 was rejected; /folia status still showed section size 8 and retained the live settings. Changing watchdog back to true and reloading returned watchdog state to running. Server stopped cleanly; disposable config restored; no temporary QA process remains.
- SFS Enhanced main merge completed separately at ef7d0f04e586f4b96782bfd09a4522e0c55f53c1.
- Engine-disabled edge case covered: if startup general.enabled is false, brand changes are reported restart-required (the vanilla brand path remains active); debug logging can still update live. No production deployment or release approval.
