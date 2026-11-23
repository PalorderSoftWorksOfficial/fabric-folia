# R2 — player disconnect/removal never executes on a region worker

**Tree:** `/tmp/ff-reconcile`, HEAD `c2fbe2135945e4e0ef42fc70094d3605ef03627d`, inherited dirty
worktree preserved. No commit, no push, no deploy. Scope: `fabric/` runtime only — `R3`/`R4` and the
SFS repo untouched.

**Verdict: R2 fixed at E3** (deterministic regression, 7 tests, whole Fabric suite green).
E4 (live bot `/kick`, proxy drop, reconnect loop) remains impossible here.

**Revision 3 (this turn):** a closed transport at execution now **defers** the already-admitted body
to the server thread. Revision 2 abandoned it, which skipped a `flushQueue()` +
`TickablePacketListener.tick()` that vanilla had already committed to — see §4 for the control flow
and why the tick is delayed-but-once, not extra.

---

## 1. Evidence

```
$ bash ./gradlew :fabric:test --tests "…PlayerConnectionLivenessTest"   → exit 0, 7 tests, 0 failures
$ bash ./gradlew :fabric:test                                          → exit 0
  148 tests, 0 failures / 0 errors / 0 skipped   (141 at HEAD, +7 in this class)
```

Exit status captured explicitly (`out=$(…); rc=$?; echo EXIT=$rc`). Full AGENTS check not run: the
assignment restricted this turn to focused Fabric tests (the inherited `common/` red recorded in
`runtime-hardening-r4-r5.md` §1 is unchanged and unrelated).

SHA-256:

```
c001711cc5ab050bf49a089b34998ff6b0af5663f554c518fd9ba0ec2edf16af  RegionPlayerRouting.java
79cf55240dbe2fc3f19fe9ab567b999e8982a9fe73da19ea3f521c28efefd158  RegionStageHub.java
c00de4e79b753136758826efdf3a89f9863e3d5e6817828f7b6e4b37bbb406ca  PlayerConnectionLivenessTest.java
```

## 2. Vanilla facts (verified on the 26.2 jar, not assumed)

`Connection.tick()V` (`javap -c`), in order:

```
 0  flushQueue()
 9  listener instanceof TickablePacketListener → listener.tick()
27  isConnected() == false
34  disconnectionHandled == false
41  → handleDisconnection()
45  channel.flush()
62  tickCount++ / tickSecond() every 20
```

`handleDisconnection()` → `getPacketListener().onDisconnect(DisconnectionDetails)`.
`ServerGamePacketListenerImpl.onDisconnect` → `removePlayerFromWorld()` →
`MinecraftServer.invalidateStatus()`, `PlayerList.broadcastSystemMessage`,
`ServerPlayer.disconnect()`, **`PlayerList.remove(player)`**, `TextFilter.leave()` — all on the
calling thread, and `disconnectionHandled` makes a second call a no-op after the warn log.

`ServerConnectionListener.tick()` (`javap -c`):

```
for connection in connections:
    if (connection.isConnecting()) continue;
    if (connection.isConnected()) connection.tick();
    else { log; send disconnect packet; setReadOnly(); }
    connections.remove(connection)          // disconnected ones leave the list here
    connection.handleDisconnection()        // …and are torn down on the SERVER thread
```

So vanilla **never ticks a disconnected connection**: SCL's next server-thread pass removes it and
calls `handleDisconnection()` itself. Its other job is per-connection transport upkeep
(`flushQueue`, listener tick, channel flush) for connections that are still open.

## 3. The two defects in this tree

**(a) Selection-time liveness only.** `RegionPlayerRouting.stageConnectionTick` checked
`isConnected()`/`isRemoved()` when the body was *selected* on the server thread; the body executed
later (hub flush → region worker). A connection that died in between ran `handleDisconnection()` →
`PlayerList.remove` + save on a worker.

**(b) Pinned entity body left behind.** The ENTITY capture (`LevelEntityTickMixin` →
`RegionStageHub.stagePlayerEntity`) pins `PLAYER_TICK_TARGETS` and stages the entity body. When the
PLAYER stage then refused for a liveness reason, only the pin was dropped at flush; the staged
ENTITY body still ran later on a worker — a removed player continuing staged ticking.

## 4. Correction (revision 2) — transport vs player liveness

### 4.1 The control flow that decides what is owed

The capture is a `@Redirect` of `Connection.tick()` **inside** `ServerConnectionListener.tick()`, so
the redirect is only reached after vanilla's own outer branch has already admitted the call:

```
SCL.tick():  ... if (connection.isConnected()) {  →  Connection.tick()      ← redirect point
                                                 else { remove; handleDisconnection(); }
```

`stageConnectionTick` re-checks `isConnected()` before staging, so a `StagedConnectionBody` exists
**only for a `Connection.tick()` invocation vanilla has already entered while the connection was
connected**. That admission is the unit that must not be lost: for that tick, vanilla owes
`flushQueue()` + `TickablePacketListener.tick()` + channel flush + `tickSecond()` accounting.

If the transport closes after staging but before worker execution, the owed body still exists. It is
neither "a tick vanilla would not perform" nor "a body vanilla will re-issue": SCL only calls
`Connection.tick()` when `isConnected()` is true, and on its next pass the connection is closed, so
SCL takes the else branch — removes it from the list and calls `handleDisconnection()` itself, on the
server thread, with `disconnectionHandled` suppressing any duplicate teardown.

### 4.2 Behaviour (two probes, evaluated at execution)

| probe | condition | execution-time action |
| --- | --- | --- |
| transport | `connection.isConnected()` | closed → **defer the admitted body** to `ServerThreadDeferral` (`closedTransportDeferredTicks()++`); never on a region worker, never dropped. |
| player | still a `ServerGamePacketListenerImpl`, `player != null`, `!isRemoved()`, server side | invalid while the transport is open → **defer** likewise (`invalidPlayerDeferredTicks()++`). |
| both live | — | run the vanilla `Connection.tick()` on the owning region worker, unchanged. |

The transport verdict is checked first, so a closed connection's deferral is counted once, under the
transport counter, even if the player is also already invalid.

**Delayed-but-once, not extra.** The deferred run is the *same* admitted `Connection.tick()`: it
executes once on the server thread at the next deferral drain. Any teardown it reaches is
guard-protected (`disconnectionHandled`), and the next SCL pass performs the removal itself without
ticking the connection again — so exactly one listener tick and exactly one
`handleDisconnection()`/`PlayerList.remove` happen per admitted connection tick. Abandoning the body
(revision 2) was the wrong trade: it silently skipped an owed transport tick. Deferring restores
vanilla's total work and keeps every part of it on the server thread.

**Stage-time refusals stay vanilla.** The redirect sits inside a call SCL already made, so returning
false (`!isConnected()` at stage time, unowned/loading, non-play listener, memory connection,
backlog) means the caller runs vanilla's own body inline on the server thread — never a worker.

**Pinned entity body** (unchanged from revision 1, defect (b)):

- `reclaimPlayerEntityBody(uuid)` — removes the pin and the still-staged ENTITY body and runs it once
  inline on the server thread (live, disconnected player; the body was due in this tick's entity pass).
- `cancelPlayerEntityBody(uuid)` — removes the pin and the staged body and drops it (an already
  removed player must not tick on any thread).
- Both consume the pin exactly once, so the next tick re-pins; a body already dispatched is counted
  in `PLAYER_ENTITY_RECLAIM_MISSED` rather than lost silently.

**Observability** (`/folia metrics`):

| counter | meaning |
| --- | --- |
| `RegionPlayerRouting.closedTransportDeferredTicks()` | admitted bodies deferred because the transport closed before execution |
| `RegionPlayerRouting.invalidPlayerDeferredTicks()` | bodies deferred because the player became invalid while connected |
| `RegionPlayerRouting.deferredConnectionTicks()` | sum of both (total server-thread deferrals at execution) |
| `RegionStageHub.playerEntityReclaimed()` | staged entity bodies re-run inline when the connection stage refused |
| `RegionStageHub.playerEntityCanceled()` | staged entity bodies dropped because the player was already removed |
| `RegionStageHub.playerEntityReclaimMissed()` | liveness releases whose body had already left the staging pool |

## 5. Deterministic regression — `PlayerConnectionLivenessTest` (7 tests, all green)

| Test | Assertion |
| --- | --- |
| `liveConnectionBodyStillRunsOnTheOwnerWorker` | baseline: both probes live → one execution in the REGION context of the owning region, no deferral |
| `closedTransportAtExecutionDefersTheAdmittedBodyExactlyOnce` | transport closes after staging → nothing runs on the worker, `closedTransportDeferredTicks()+1`, one entry queued (`pendingCount()==1`); after the server-thread drain the body ran **exactly once** with a non-REGION context and nothing remains queued; a following flush + drain adds no second tick |
| `closedTransportTakesPrecedenceOverPlayerInvalidity` | both probes false → one deferral counted under the transport counter, `invalidPlayerDeferredTicks()` unchanged, the body still runs exactly once after the drain |
| `invalidPlayerOnOpenTransportDefersToTheServerThread` | transport open, player invalid → not on the worker, `deferredConnectionTicks()+1`, one deferred entry; after the drain the vanilla body ran **exactly once** with a non-REGION context |
| `stagedEntityBodyIsReclaimedInlineWhenTheConnectionStageRefuses` | ENTITY staged then reclaimed → ran inline exactly once, non-REGION, counter +1; the flush does not repeat it, `pendingBodies()==0` |
| `stagedEntityBodyIsCanceledWhenThePlayerIsAlreadyRemoved` | ENTITY staged then canceled → nothing runs before or after flush, counter +1, pin consumed once |
| `livenessReleaseClearsThePinSoTheNextTickRePins` | after a release the next tick's ENTITY stage re-pins and only that body runs |

## 6. Open limitations

1. **No E4 live proof.** `/kick`, proxy drop, reconnect loop, STRICT mode with player staging on were
   not run. A live run should assert zero disconnect-path work off the server thread (thread of
   `PlayerList.remove`), one removal per disconnect, and no ghost entity tick after removal.
2. **`PlayerList.remove` is not constructible in a unit test**, so "exactly once" is established
   structurally — one admitted body, one invocation of it, no further `Connection.tick()` from SCL
   once the transport is closed, and the verified `disconnectionHandled` guard on teardown — not by
   observing a real removal count. E4 is required to close that gap.
3. **Deferred, not at the SCL call site.** A connection that dies mid-tick has its admitted body run at
   the next server-thread deferral drain (same for all deferrals), not inline in the SCL iteration;
   total work is unchanged and the tick runs once. No listener tick is skipped or duplicated.
4. **Config asymmetry not addressed.** ENTITY staging on + PLAYER staging off still splits a *live*
   player's state machine across the server thread and a worker. Pre-existing, not a liveness defect,
   out of R2's scope.
5. **Packet path unchanged.** A queued handler for a player removed after enqueue still re-homes to
   the server thread through `isOwnerRegion` → `RunningOnDifferentThreadException` (read from the
   handlers' `ensureRunningOnSameThread` prologue, not by a new test).
6. **R3/R4 untouched** as instructed: ownership lookups still take the structure lock, and staged
   block/entity mutation still has no ownership handoff.

## 7. Changed paths

```
fabric/src/main/java/com/palordersoftworks/fabricfolia/engine/RegionPlayerRouting.java
fabric/src/main/java/com/palordersoftworks/fabricfolia/engine/RegionStageHub.java
fabric/src/main/java/com/palordersoftworks/fabricfolia/engine/FabricFoliaEngine.java   (metrics line only)
fabric/src/test/java/com/palordersoftworks/fabricfolia/engine/PlayerConnectionLivenessTest.java  (new)
```

No mixin changed, so no boot/mixin-apply check was required. The new code carries no explanatory
comments: the counters have none, the deferral helper is named only, and the two public release
methods carry `@return` tags only.
