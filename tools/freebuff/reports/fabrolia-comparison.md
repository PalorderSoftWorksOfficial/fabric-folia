# Fabrolia reference comparison — prod/current vs f_3.2.6

<sup>Author: integration manager · date: 2026-11-22 · status: attributed comparison, not independent verification</sup>

Purpose: keep one honest comparison between the two Fabrolia reference lines and
Fabric-Folia, so roadmap and release wording cannot drift into an unsupported parity or
performance claim. This document is a **concept and requirements** comparison. It is
not a source review, not a test reproduction and not a compatibility certificate.

## 1. Evidence classes used here

| Class | Meaning |
| --- | --- |
| **A** | Public repository metadata / README / AGENTS.md / release notes, as recorded in `tools/freebuff/reports/fabrolia-*.md`. Attributed to that document, not reproduced here. |
| **B** | Owner's source-level comparison notes supplied 2026-11-22. Names architecture surfaces only; no code was copied, ported, translated or derived. |
| **C** | Fabric-Folia's own tree and reports (`defect-ledger.md`, `runtime.md`, `docs/status.md`). |
| **V** | Independently verified by this session. **No Fabrolia claim below is class V.** |

Nothing in this document was produced by building, booting or benchmarking either
Fabrolia ref. No GPL-3.0 source was read into this repository.

## 2. Ref identity

| Fact | `prod/current` | `f_3.2.6` | Fabric-Folia (ours) |
| --- | --- | --- | --- |
| Role | Operational production reference | Newer alpha development line | Staged-region execution mod |
| Version string | `0.2.38+alpha-26.3` (A) | `3.2.6-alpha`; asset `fabrolia-alpha_3.2.6.jar` (A) | `0.1.0` |
| Minecraft | 26.3 (A) | 26.3, declared range `[26.3,26.4)` (A) | 26.2 |
| Loader / API / Java | Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25 (A) | Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25 (A) | Loader 0.19.3+, API 0.160.0+26.2 min, Java 25 |
| Toolchain matrix note | Conservative reference pin; **not** the latest official Fabric values | Same conservative pin | Must test both 26.3 matrices (A: 0.19.5/0.161.0; B: official 0.20.1/0.166.0); they are not equivalent |
| License | GPL-3.0 (A) | GPL-3.0 (A) | Apache-2.0 |
| Published releases | `prod/current` is a moving ref, not a release tag (A) | 3 releases total, 4 tags, repository created 2026-10-10 (A) | none stable; approval BLOCKED (C) |

## 3. Claimed maturity (attributed, not verified)

- `prod/current` README states it is the fork running **one ~35-player production
  server**, with the goal of **vanilla behaviour at 20 TPS on a busy server**. Its
  AGENTS.md states **~1,400 tests** including ~57 source/bytecode wiring tests, a
  **40-real-client server stand** with verify and performance modes, Spark profile
  comparison, JFR/thread diagnostics, full modpack mixin-export verification, a
  fail-stop policy and vanilla-parity requirements for farms, redstone and datapacks.
  It documents testing with C2ME, Lithium, VMP, Carpet, ServerCore, ScalableLux,
  FerriteCore, MCFC and BlueMap, and incompatibilities with Moonrise, Open Parties and
  Claims and C2ME below `0.4.2-alpha.0.88`. (A) Our resolved 26.2 C2ME artifact is
  `0.4.2-alpha.0.71+26.2`, i.e. below that declared floor; a 26.3 comparison must move
  to a `.88`-or-newer build.
- `f_3.2.6` AGENTS.md states **~1,600 tests** and newer player-lifecycle/connection
  work. Its release notes document a fix for a player moved, mounted or dismounted
  between the two halves of an island tick and a fence against unrelated server-thread
  work inside a parked island tick — **and still document a server stop when a routed
  body teleports or removes the very player whose own tick is executing.** (A)
- Fabric-Folia: 280 automated tests on the reconciled tree (API 10, common 148,
  Fabric 122), zero failures/errors/skips; **no boot, gameplay, soak or benchmark on
  that tree.** (C) This is not comparable evidence to either stated Fabrolia harness.

Reading: `prod/current` is the better **operational** reference — it is the line its
README says runs a real server and it has the longer-lived test/stand discipline.
`f_3.2.6` is **newer development**, not a correctness certificate: it ships as an
alpha-named artifact and its own notes carry an unresolved routed-player stop case.
Neither claim transfers to our tree.

## 4. Architecture surfaces (class B where noted)

These are the surfaces the owner's production source-level comparison names. They are
recorded as **design requirements to satisfy independently**, never as implementation
to copy.

| Surface | `prod/current` (B/A) | Fabric-Folia equivalent today (C) | Gap |
| --- | --- | --- | --- |
| Player turn lifecycle | Explicit `PlayerTurnOwnership` / `IslandPlayerTurnSession`; player ticking is a turn/session, not a chunk-to-region lookup | Per-player per-tick owner **pin** (`RegionStageHub`) for the ENTITY and PLAYER slices; no turn/session object, no phase-boundary revalidation after every lifecycle event | R2 (disconnect/removal), R21 (PLAYER slice executes after `PlayerList.tick`/chunk send), P1-M3 cross-dimension |
| Player lifecycle across ticks | `AsyncIslandPlayerLifecycle` | Lifecycle mutations execute wherever the routed body runs; no dedicated lifecycle owner | R2, R4, P1-M3 |
| Packet continuations | `AsyncIslandPacketContinuations` with ordered continuation | Packet re-home to region queue; `NO_POSITION` task; no per-player FIFO/fairness metric | R6, P1-M5 |
| Scheduled ticks | `IslandScheduledTicks` + `ScheduledTickIslandAdmission` (owner-local due times and admission) | `RegionPendingTicks` ledger + `ScheduledTickDeferral` replay; global `LevelTicks` containers remain server-thread | R9, R12, P1-M2; no independent island clock |
| Chunk delivery | `ChunkDelivery` / `ConnectionOutbox` as a transactional lifecycle | Vanilla `PlayerChunkSender` stays server-thread; no outbox/ack/retry model | not implemented; no claim |
| Main fallback | Explicit, bucketed, ordered Main FIFO with a defined drain point and ownership retained while Main runs | `ServerThreadDeferral` queues + quiesce pump; not bucketed per island and no bounded admission | R3, R5, R10, R23 |
| Topology / ownership | Island claims with stable identity and merge/split lifecycle | Region state machine + structure lock; ownership is a position map, not chunk state | R3, R11; no epoch/claim object |
| Clocks | World / island-logical / heartbeat clocks with deadline re-keying | One global tick clock; region tick counters are diagnostics, not simulation clocks | not implemented (by design, later gate) |

The important structural point: the production ref's player work is not "resolve the
player's chunk and stage it". It is a **turn/session lifecycle** with async lifecycle
and packet-continuation surfaces and an explicit Main fallback. Fabric-Folia has a
pin and a routed-body model. Those are different architectural classes, and our R2/R21
findings are exactly the failures a turn/session model is built to prevent.

## 5. What to adopt as invariants (independently designed)

1. A player tick is a **turn** with an explicit owner, a phase boundary and a
   revalidation point after teleport, mount/dismount, respawn, disconnect and
   cross-dimension transfer.
2. Lifecycle work that mutates global state has **one declared owner** (server thread)
   and an ordered fallback queue; a routed body must not perform it on a region worker.
3. Packet work has an ordered per-connection continuation, not just a region queue
   slot; latency and fairness are measurable.
4. Scheduled ticks carry an owner-local due time with admission and re-keying on
   ownership change; vanilla ordering and restoration are preserved.
5. Chunk delivery is a lifecycle (snapshot, send, ack, retry, retire), not a side
   effect of the tick.
6. Main fallback is explicit, bounded and ordered per unit; Main never waits on a
   worker that needs Main.
7. Split/merge changes ownership through a claim/epoch barrier, so no accepted task is
   stranded (R6) and no body executes against a changed topology (R4).
8. Every fallback, bounce, deferral, drop and retry is counted and readable.

These map to the P0/P1 entries in `defect-ledger.md`; they are not new features to
build before the P0s are closed.

## 6. What we must not do (licensing boundary, binding)

- Fabrolia is **GPL-3.0**; Fabric-Folia is **Apache-2.0**. No Fabrolia source is
  copied, ported, translated, paraphrased into code, or used to derive an
  implementation. No class names, package layout, configuration surface or algorithm
  structure is adopted from it.
- The permitted inputs are: public README/AGENTS/release notes, public architecture
  descriptions (paraphrased), our own vanilla-bytecode verification and our own
  invariants.
- Naming a concept in this document (turn session, outbox, admission) is a requirements
  statement. The Fabric-Folia implementation must be designed from the Fabric API and
  verified 26.2 behaviour.
- Do not cite either Fabrolia ref as evidence that Fabric-Folia is correct, stable,
  compatible or fast.

## 7. Comparison verdict

| Question | Answer |
| --- | --- |
| Is `prod/current` a useful production reference? | Yes, for operational discipline and surface inventory (attributed claims: ~35 players, 20 TPS goal, ~1,400 tests, 40-client stand, Spark/JFR). |
| Is `f_3.2.6` proof that the architecture is correct? | No. Newer alpha development; ~1,600 tests are repository documentation; its own notes still list a routed-player self-teleport/removal stop. |
| Does Fabric-Folia match either? | No. It is a staged-region execution layer with 280 unit tests and **zero live evidence on the reconciled tree**. |
| What is the honest parity statement? | Behavioural parity is a **target measured one acceptance gate at a time**; today the project is behind on player turn lifecycle, packet continuations, owner-local scheduled ticks, chunk delivery and bounded Main fallback. |

Sources: `tools/freebuff/reports/fabrolia-production-reference.md`,
`fabrolia-prod-async-islands.md`, `fabrolia-reference.md`,
`fabrolia-stand-reference.md`; `docs/PROJECT_VISION_AND_ROADMAP.md` §4.
