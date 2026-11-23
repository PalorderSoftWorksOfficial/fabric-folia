# Manager review — live-validation wave and integration handoff

<sup>Owner: manager · date: 2026-11-22 (second wave) · tree: `/tmp/ff-reconcile`
(detached) at `c2fbe2135945e4e0ef42fc70094d3605ef03627d` · no Java sync, commit, push,
deploy or production contact</sup>

This review covers the live-validation wave: QA's clientless smoke boots and the
blocked Fabrolia boundary, runtime's finished R1 implementation, and a re-check of
core's open D4/R11/R6 items. It separates what was observed from what is still
unverified, and records the R1 acceptance conditions. No production Java was edited
by the manager, and **nothing was synced to the shared checkout this turn**.

## 1. Tree state verified

| Item | Value |
| --- | --- |
| HEAD | `c2fbe2135945e4e0ef42fc70094d3605ef03627d` (`c2fbe21`) |
| Branch | detached (`git branch --show-current` empty) — expected for the worktree |
| Unmerged paths | none (`git diff --name-only --diff-filter=U` empty) |
| Tracked modifications | 35 files |
| Untracked | `docs/PROJECT_VISION_AND_ROADMAP.md`, `tools/freebuff/**`, `RegionSchedulerSplitRehomeTest`, `EntitySchedulerLifecycleTest`, `EntityTeleportDispatchTest`, `DeferredBarrierDeadlockTest`, `PlayerSliceAtomicityTest`, and the retired-but-not-deleted `RegionChunkAccessGuard.java` + `RegionChunkAccessGuardTest.java` (defect M1) |
| Build/test performed this turn | QA: full build + 3 clientless benchmark boots (jar 02:28:55) **and run 6 on the final-tree jar** (`06e8520…`, built 02:52:26). Runtime: `:fabric:test` **136/136** at 02:40. Manager: no Gradle, no boot, no RCON. |
| Commit/push/deploy | **none** |
| Sync to shared checkout | **none this turn**; the shared checkout stays at `94976ef` with its 33 dirty entries |
| Manager production edits | **none** (only `tools/freebuff/**` and this/report docs) |

QA's smoke boots and all benchmark artifacts live outside the repository. No
Fabrolia jar or GPL source is present in `/tmp/ff-reconcile` (searched).

## 2. What is complete

1. **Defect ledger rebuilt and current** — `tools/freebuff/reports/defect-ledger.md`.
   Every P0/P1/P2/P3 entry has a severity, an evidence level (E0–E4), exact
   file/method, root cause, named regression, runtime status and owner. The P0 table
   now carries owner + status columns, and the core D1–D9 table reflects the patch
   actually present in the worktree.
2. **Roadmap status is honest** — `docs/PROJECT_VISION_AND_ROADMAP.md`. R0 is marked
   partial with the remaining exit items named; R1 is marked blocked with the five
   P0s and the unaccepted R1 patch recorded; the ownership table names which sessions
   are active and which are not.
3. **Test evidence recorded, with tree labels** — the 25 focused core tests remain a
   separate run; QA's 02:30 green build covered 300 tests; the current XML set is
   **307** (api 10 / common 161 / fabric 136), all green. See §4 for provenance.
4. **FREEBUFF.md corrected** — Linux jar path, `bash ./gradlew`, the 25-test run, the
   R1 unaccepted state, protocol/compatibility status, launcher fix. The two known
   false claims are absent: `RegionEntityRegistry.register` is **not** stripe-locked,
   and the PLAYER slice is **not** vanilla-ordered. Windows-only notes remain in the
   historical subsections.
5. **Launcher verified and repaired** — six canonical roles, exact `PREFIX-ROLE`
   matching, `--send` / `--resume` / `--dry-run` / `--force` / `--prompt-file`,
   executable bit, help text "all six". The broken idle check (`capture-pane -t
   "=session"` in tmux 3.6) is fixed and verified; see §7.
6. **Protocol research reviewed** — `tools/freebuff/reports/protocol-client-research.md`
   is properly graded and does not present any candidate as validated.
7. **Live runtime evidence delivered** — `tools/freebuff/reports/live-runtime-and-benchmark.md`:
   3 clientless benchmark boots + 1 final-tree freshness boot, zero mixin errors,
   clean stops, JFR/jcmd, plus a real P1 (`/folia` command surface dead) and the
   Fabrolia version blocker.
8. **R1 implementation finished by runtime** — 14 focused tests, full fabric 136/136;
   accepted at E3 with the live-boot and R10 conditions in §3/§5.
9. **No sync this wave** — per instruction, all changes remain in `/tmp/ff-reconcile`
   (see §8).

## 3. What is not complete — do not describe it as done

| Item | State | Why it is not done |
| --- | --- | --- |
| R1 non-player teleport | code accepted at **E3 + final-jar boot**, not closed | runtime implemented the per-context matrix, exact-match invocation token, positioned `scheduleToChunk` and a server-thread safe continuation; 14/14 R1 and 136/136 fabric green. The final jar (`06e8520…`) booted the final tree with zero mixin errors (run 6). Still missing: a disposable-world `/tp`/portal check (E4 gameplay) and R10's `noteServerThread` wiring, so a foreign `UNKNOWN` thread can still take the server-thread inline fast path. |
| Core D5–D8, D9 | open / deferred | not patched; listed in the ledger with regressions to write. |
| R11 register/retire race | open | the D1 patch fixes split/retire set residue only; `register` still updates map + set outside the stripe lock. |
| R2, R3, R4, R5 | open P0 | no implementation, no design accepted. R4 still blocks any "region ownership safe" claim. |
| R6 positionless work | open P1 | packet re-homes, `enqueueTransition` (including the global lane), command re-dispatch and random-tick batches still carry no position. Task migration is **not** complete. |
| Compatibility **runtime** evidence | none | `compatibility.md` is delivered and reviewed, but it is a static inventory. No mixin export, boot or gameplay test exists for any mod combination. |
| 40-client protocol stand | not built | no driver selected, nothing installed, no bot has connected; deferred with the benchmark. |
| Full suite at the current tree | not run in one command | module XML is green (307 total) but assembled from different runs; QA's 300-test run predates the R1 rewrite. One command at one tree is still owed. |
| Tree hygiene (M1) | not cleaned | the retired `RegionChunkAccessGuard` and its test are still present and would run; `reconciliation.md` incorrectly says they were deleted. Delete them when production edits are allowed, then rerun `:fabric:test`. |
| `/folia` command surface (L1) | **broken in live boot** | every `/folia` command fails: `NoClassDefFoundError: net/kyori/examination/Examinable` / `Could not initialize class FoliaMessages`. Build nests only `adventure-api` + `minimessage`; `adventure-key`, `examination-api`, `examination-string` are missing. MSPT/region/worker metrics are unavailable. |
| Live boot of the current tree | **done (run 6)** | final-tree jar `06e8520…`, built 02:52:26, booted `Done (4.920s)`, zero mixin errors, clean stop, `/folia health` still fails (L1). The three benchmark runs remain the 02:28:55 jar (`0ac652…048e`). |
| Fabrolia comparison | **BLOCKED** | pinned `f_3.2.6` (sha256 `4267…d3ca`) declares `minecraft ~26.3`; this project is 26.2. No 26.3 toolchain installed, no run, no comparison, no winner. |
| 40-client benchmark | deferred | 14 GiB host (full profile requires 32 GiB) and no verified native 26.2 protocol client. |
| Compatibility/soak/player-load evidence | none | static inventory only; no mixin export, gameplay, player-load or soak run. |

## 4. Test evidence, exact

### 4.1 Focused core run — 25 tests (the only run for the D1–D4 patch)

| Class | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- |
| `RegionSchedulerSplitRehomeTest` | 7 | 0 | 0 | 0 |
| `RegionFailurePolicyTest` | 4 | 0 | 0 | 0 |
| `RegionEntityRegistryTest` | 10 | 0 | 0 | 0 |
| `EntitySchedulerLifecycleTest` | 4 | 0 | 0 | 0 |
| **Total** | **25** | **0** | **0** | **0** |

- **Paths:** `common/src/test/java/com/palordersoftworks/fabricfolia/scheduler/` for
  all four classes; results in
  `/tmp/ff-reconcile/common/build/test-results/test/TEST-com.palordersoftworks.fabricfolia.scheduler.*.xml`.
- **Command:** `bash ./gradlew :common:test` with `--tests` for the four classes,
  `--stacktrace` — **exit 0**, `BUILD SUCCESSFUL in 3.37s`, operator-run and recorded
  in `core-implementation.md` §2. The worktree `gradlew` lacks the executable bit and
  `/tmp` is mounted `noexec`, so `./gradlew` exits 126. `ConfigSchemaPatchTest` was
  excluded deliberately.
- **Limit:** only these four suites are in that results directory. This is not a full
  module run and says nothing about `fabric` mixins or runtime behaviour.

### 4.2 280-test baseline — a different, earlier run

280 tests (API 10, common 148, Fabric 122; 0 failures/errors/skips) from the
reconciliation run described in `tools/freebuff/reports/reconciliation.md` §3. Its
XML set was overwritten by later focused runs. It does **not** cover the core D1–D4
patch and must not be quoted as if it did.

### 4.3 R1 test status — 14/14 green (E3)

`fabric/src/test/java/com/palordersoftworks/fabricfolia/engine/EntityTeleportDispatchTest.java`
is now **14 tests, 0 failures/errors/skips** in
`fabric/build/test-results/test/TEST-com.palordersoftworks.fabricfolia.engine.EntityTeleportDispatchTest.xml`
(timestamp 2026-11-22T01:40:36Z). The full fabric module in that run is **136/136**.
The reference tests cover region/global/server-thread/NETWORK/ASYNC contexts, same-chunk
distinct destinations, two identical invocations, rejection deferral, a real split
re-home, and a nested different-entity/destination transition that must **not** inline.
Commands and hashes are in `runtime-implementation.md` §4; manager re-verified all
three file hashes and the XML totals.

### 4.4 Current per-module XML (different runs — not one command)

| Module | Tests | Fail | Err | Skip | Latest XML |
| --- | --- | --- | --- | --- | --- |
| api | 10 | 0 | 0 | 0 | 00:33Z |
| common | 161 | 0 | 0 | 0 | 01:28Z (includes `ConfigSchemaPatchTest` 4/4) |
| fabric | 136 | 0 | 0 | 0 | 01:40Z |
| **total** | **307** | **0** | **0** | **0** | mixed runs |

QA's green build snapshot was api 10 + common 161 + fabric 129 = **300** at 02:30
(before the R1 rewrite); it must not be quoted as the current total.

### 4.5 Live clientless runs (QA, jar 02:28:55 — stale for R1)

| Run | Done | TPS mean/min/p95 | Heap last | Stop | Mixin errors |
| --- | --- | --- | --- | --- | --- |
| run3 | 5.241 s | 20.003 / 19.908 / 20.090 | 793.6 MiB | clean | 0 |
| run4 | 5.038 s | 20.000 / 19.892 / 20.114 | 788.1 MiB | clean | 0 |
| run5 | 4.946 s | 20.002 / 19.901 / 20.086 | 789.9 MiB | clean | 0 |

`protocol_clients=0` throughout. TPS is gametime-delta over RCON, not warning cadence.
These are clientless idle baselines only; the clientless label and the stale-jar caveat
must travel with every citation.

**Run 6 — final-tree freshness check:** jar `fabric-folia-0.1.0.jar`, sha256
`06e852018bd19858683362deb0ad54341b83e830d5e9c1921eaacd6ba3b8d76a`, built 02:52:26
from the current tree (`RegionTransitions.java` mtime 02:40:11). Booted `Done (4.920s)`,
zero mixin warnings/errors, 60 s gametime sampling (8 samples, mean 20.000 TPS),
703.2 MiB heap, clean engine shutdown and world save, no crash report. `/folia health`
still returns the L1 `FoliaMessages` error, as expected. This closes the **mixin-
application** gap for R1; it is not a gameplay check and not benchmark data.

## 5. Defect snapshot at handoff

**Open P0 (release-blocking):** R1 is no longer in the "double-cancel always" state —
code is accepted at E3, but it is **not closed** (no fresh-jar E4 boot; R10 thread
identity). R2 disconnect/removal on a region worker or a removed player's staged body
still ticking; R3 `ownerOfChunk` `structureLock` on Netty loops plus the second
acquisition in `RegionScheduler.enqueue`; R4 staged bodies mutate outside their region
(loadedness gate, not ownership); R5 deferred vanilla-mutation failures swallowed.

**Open P1:** **L1** `/folia` command surface dead (missing Adventure transitive jars;
metrics unavailable); **D4-F1** merge-window re-home stranding; **F2** merge-side
policy bookkeeping leak; R6 positionless work strands on split; R11 `register`/`retire`
race; R7 context-restore leaks; R8 `Throwable` swallowing in the deferral drain;
R9 worker `hasScheduledTick` read of a non-concurrent map; R10 deferral admission /
ordering / pause window and `noteServerThread`; R12 optimistic deferred ticket return
contracts; R13 uncounted random-tick drop/bounce paths. **P2/P3:** R14 diagnostics
balance, R15 doc drift, D9 section-map growth, M1 retired guard files, L2 (fixed).

**Core patch decisions (after reading `core-implementation.md`):**

- **D1** split/retire set residue: patched. R11 (`register`) remains open.
- **D2** real abort + streak reset: **signed off**. A repeatedly failing region now
  really dies and its queue is dropped with a count. Accepted semantics: because a
  successful task or tick body resets the streak, a region failing exactly one queued
  task per tick does not abort; the threshold accumulates within one drain or through
  a failing tick body. Do not describe it as "a region that fails every tick always
  dies". F2 (below) is still required so merged donors do not leak policy entries.
- **D3** rejected delayed schedule now runs `retired`: patched, matches the API
  contract. Accepted-then-dropped tasks still cannot retire (R6 residual).
- **D4** execution-time net: patched for position-bearing entries, but **not done**.
  **D4-F1** (new P1): the re-home resolves the owner, then `get`/`computeIfAbsent` +
  `add`; if the owner merges in that window, the add creates a new queue for a dead
  donor and the task is stranded until close. Fix by routing through
  `enqueue(owner, task, pos)` and retrying on `false`. **F2** (new P1):
  `onRegionRemoved` is wired for death only, not merges. F5 (a re-homed entry waits
  for the target's next tick) and F6 (per-region delayed deadline after a re-home) are
  accepted limitations.
- **Constructor visibility:** the 8-arg `RegionScheduler` constructor is deliberately
  package-private for test policy injection — **accepted**. The javadoc line-sharing
  is pre-existing at HEAD, not introduced by the patch; cosmetic only.
- **ConfigSchema default flip** (`entity-tick`, `chunk-tick`, `random-tick` staging
  `true` → `false`): **approved and tested** — `ConfigSchemaPatchTest` 4/4 green in the
  common 161 run. It does not fix R4: explicit `true` still enables the unsafe gate,
  and existing configs keep their values.

**R1 acceptance (manager, this wave):** the code path is **accepted at E3** on the
strength of 14/14 focused tests and 136/136 fabric, plus a final-jar boot (run 6).
Conditions before closure: (1) a disposable-world `/tp`/portal functional check on the
final jar (the boot proves mixin application, not teleport behavior); (2) R10
`noteServerThread` wiring so the server-thread fast path cannot be taken by a foreign
`UNKNOWN` thread; (3) the report keeps the comment-policy deviation and the
inherited-edit disclosure visible. Do not describe R1 as closed, merged or
runtime-verified.

## 6. Compatibility and protocol review outcome

- **Compatibility:** `tools/freebuff/reports/compatibility.md` was delivered and
  reviewed. Its totals were recomputed from
  `tools/freebuff/compatibility/scan-26.2.json` and match exactly: 8 top-level
  artifacts, 40 containers (8 + 32 nested: C2ME 29, Krypton 1, VMP 1, TIS 1), 34
  mixin configs (2 empty), **1,411 mixin classes / 1,354 server-applicable** (the
  gap is C2ME's client-only module), 606 distinct targets, 1,862 handlers, 0 missing
  classes, 0 scan errors. The report correctly labels itself **metadata plus static
  mixin inventory only** — no mixin export, boot, gameplay, soak or performance run
  — and its C2ME gate is correct: `0.4.2-alpha.0.71+26.2` is below the `prod/current`
  Fabrolia floor `0.4.2-alpha.0.88`, so it is not supported. Risk categories A/B/C
  are same-target or same-injection-point candidates, **not** proven conflicts.
  Open items for the owner: Carpet/TIS have no compatibility note and TIS has the
  largest same-point overlap; the OFEM collision patch is force-disabled only when
  Lithium is detected, not when TIS is present.
- **Protocol:** `protocol-client-research.md` is accepted as research only. Verified
  facts: 26.2 = protocol 776, 26.3 = 777; no released PrismarineJS package speaks
  either; Azalea (`0d38e0d`) is the mature native-776 candidate and needs a git pin;
  26.3 candidates (rapidbot, go-mclib, mcproto, MCProtocolLib snapshots) are young and
  unproven; ViaProxy is a translation lane and must never count as native evidence.
  No package was installed, no client ran, no server was contacted.
- **Fabrolia black-box reference: BLOCKED, not compared.** The pinned release is
  `f_3.2.6`, asset `fabrolia-alpha_3.2.6.jar`, sha256
  `42678345eca6bc5b99ed9f3e405f98bc4d40f39ead15bcc1a55d5815a90dd3ca`, GPL-3.0, declaring
  `minecraft ~26.3` and `java >=25`. `3.2.1` and `3.2.0` also declare `~26.3`. This
  project is 26.2, so **no 26.3 toolchain was installed, no Fabrolia run occurred, no
  comparison exists and no winner is named**. Only jar metadata/release notes were
  inspected; no GPL source was fetched, read or copied. Artifacts stay outside the
  repository in `/tmp/ff-runtime-evidence/fabrolia/`.

## 7. Team launcher

`tools/freebuff/launch-team.sh` (executable, `rwxrwxr-x`):

- **Roles:** `manager core runtime compatibility protocol qa`.
- **Sessions:** `$FREEBUFF_TEAM_PREFIX-ROLE` (default `fabric-folia-ROLE`), matched
  exactly with `=name`, so a send cannot land in a lookalike session.
- **Modes:** `--send ROLE...` (existing session only), `--resume [ROLE...]` (all
  existing roles, skips absent), default start (skips existing), `--dry-run`,
  `--prompt-file PATH`, idle confirmation with `--force`.

Usage:

```bash
bash tools/freebuff/launch-team.sh                 # start missing roles
bash tools/freebuff/launch-team.sh --dry-run       # plan only
bash tools/freebuff/launch-team.sh --send core --prompt-file tools/freebuff/prompts/followups/<file>.md
bash tools/freebuff/launch-team.sh --resume
```

**Idle check repaired this wave (L2).** `session_idle` used `capture-pane -t
"=session"`; tmux 3.6 rejects that for a pane target, so `--send`/`--resume` always
refused without `--force`. It now targets `"=session:0.0"`. Verified: `--send core
--dry-run` exits 0 for the idle core session; `--send qa --dry-run` still refuses the
busy QA session. `bash -n` = 0.

**Team actions this wave:** compatibility and protocol had delivered their reports and
were idle, so their sessions were closed to free the concurrency slots; the runtime
session that had died at the limit screen was relaunched with
`prompts/runtime-review-r1.md`; QA and runtime each received two written scope
messages (QA freeze/edit disclosure/version boundary; runtime nested-token finding and
inherited-edit disclosure). No agent beyond the six assigned roles was started. The
runtime session is now idle; QA is finishing its report.

## 8. Sync to the original checkout — none this wave

Per instruction, **nothing was copied** to `/home/administrator/CDN-bucket/fabric-folia`
this wave. All changes (R1 Java, QA harness, reports, ledger, this review, the launcher
fix) remain in `/tmp/ff-reconcile`. The shared checkout was verified unchanged:
`94976ef`, 33 dirty entries, same status hash as at wave start, no unmerged paths.

The earlier handoff wave had synced only `docs/PROJECT_VISION_AND_ROADMAP.md`,
`FREEBUFF.md` and `tools/freebuff/**`; no production Java was ever copied, and no git
history command was run in either tree. That earlier sync is now stale relative to the
launcher fix and these reports, and must be repeated only on explicit instruction.

## 9. Next concrete engineering sequence

1. **Fix L1 before any metrics claim (runtime/core).** Nest Adventure's transitive
   runtime jars (`adventure-key`, `examination-api`, `examination-string`) or shade a
   complete set, add a jar-content regression, and add a boot test asserting
   `/folia health` answers over RCON. Until then every metrics/region/worker number is
   unavailable, and no benchmark or performance statement may use them.
2. **Close R1's remaining conditions (runtime).** Keep the accepted E3 code; wire R10's
   `noteServerThread` in engine bootstrap (or reject the server-thread fast path until
   it is wired), then run one disposable-world `/tp`/portal check on the booted final
   jar (run 6 already proves it loads). Only that turns R1 from "E3 + boot" into full
   E4. Do not widen R1 into R2.
3. **Close the core loop (core).** **D4-F1** first (route the re-home through
   `enqueue(owner, task, pos)`, retry on `false`), then **F2** (`onRegionRemoved` from
   `onRegionMerged`), then R11 (`register` under the stripe/atomic protocol) and R6
   (position every producer). Add the deterministic regressions named in the ledger.
4. **One full suite at one tree.** Delete the retired `RegionChunkAccessGuard` pair
   (M1), then run `bash ./gradlew :common:test :fabric:test :api:test build
   --stacktrace`, capture the exit code explicitly, and record per-module XML counts.
   The current 307 is assembled from different runs and is not that evidence.
5. **Fix the QA harness debt (QA).** Loader dependencies in `assemble_server.sh`,
   write `eula.txt`, treat RCON close-after-`stop` as success, and fail a run when a
   metrics command errors (H2–H5 in `live-runtime-and-benchmark.md`). Then repeat the
   three clientless runs on the **current** jar.
6. **R4 design decision (manager + runtime).** Choose single-owner halo enforcement or
   explicit cross-region handoff, with the three boundary tests. Nothing in R2–R5 is
   worth building before this.
7. **Compatibility** — static inventory only; next run a mixin export and a
   default-profile boot, starting with the risk-C rows, and decide the Carpet/TIS note.
8. **Protocol/40-client** — remains deferred until a >=32 GiB host and a verified
   native 26.2 client exist. Do not fake player load from the clientless baseline.
9. **Fabrolia** — keep BLOCKED until a `~26.2` asset exists or this project moves to
   26.3 and re-baselines. Then pin one release asset and SHA-256 and run an alternated
   A/B on identical settings; no GPL source in this repo, no winner from clientless data.
10. **Sync** — only on explicit instruction, and only docs/tooling; never Java without
    the user's approval.

## 10. What this review did and did not do

**Manager did:** inspect the QA raw evidence, the runtime diff/hashes/XML, and the
defect code paths directly; repair the launcher idle check; free concurrency slots by
closing two delivered sessions; send QA and runtime written scope messages; update the
ledger, this review and FREEBUFF. No production Java/config/test edit was made by the
manager.

**Manager did not:** run Gradle, boot a server, issue RCON, create a world, export
mixins, benchmark, soak, install a protocol client, fetch GPL source, contact a
production server, commit, push, publish, deploy, or sync anything to the shared
checkout. The clientless runs are QA's, the R1 tests are runtime's, and the Fabrolia
comparison does not exist.

---

## 11. 2026-11-23 integration review (third wave)

**Snapshot:** HEAD `c2fbe21`, detached, 0 unmerged, status hash
`ae97c1adabf5448a` (unchanged before and after verification). No commit, push, deploy,
sync or production contact.

### 11.1 What actually landed

Two agents were writing the same tree while this review started. Two early full-suite
runs therefore caught **mid-edit** states and are not evidence: one failed
`RegionFailurePolicyTest.consecutiveFailuresAbortOnlyTheFailingRegion` (a genuine
enqueue/abort race, now T1), the other failed `DeferredBarrierDeadlockTest` before
runtime's test update landed. All agents were then idle and the tree was stable.

- **Core (second pass):** D4-F1 fixed via `rehomeToOwner` (two-phase `enqueue`, bounded
  re-resolve, no queue for a dead region); F2 wired in `onRegionMerged`; the
  accepted-then-dropped half of R6 fixed with an `onDropped` hook and entity re-proof;
  **R11 fixed** (`register` under the same stripe). Report: `core-implementation.md`
  §9-12, 42 focused tests green.
- **Runtime:** **R5 fixed at E3** (deferral failure recorded, rethrown, both lanes
  poisoned until taken, surfaced by `beginTickPhase`; 5 new tests) and the **R10
  `noteServerThread` residual closed** (production call sites verified in
  `FabricFoliaMod` and both `MinecraftServerMixin` heads). R1 unchanged, still 14/14.
  R4 audited only: mutation reach is chunk-unbounded, so no halo check was shipped and
  R4 stays open P0. Report: `runtime-hardening-r4-r5.md`.
- **QA:** a live 26.2 server on the current jar (`5905e13...`) booted `Done (5.331s)`
  with zero mixin errors and a real `ProbeBot` login/spawn; its walk phase is pinned in
  the known teleport-resync loop (position never changes), so it is not gameplay or R1
  evidence. QA also booted **Fabrolia 3.2.6 standalone on 26.3** (`Done (6.090s)`, 0
  clients, clean stop) — a different MC version, therefore **not a comparison**.

### 11.2 Verified checks at the snapshot

| Check | Command | Result |
| --- | --- | --- |
| Three-module suite | `bash ./gradlew :common:test :fabric:test :api:test --rerun-tasks` | **exit 0**; api 10 + common 167 + fabric 141 = **318**, 0 fail/err/skip |
| Build | `bash ./gradlew build --stacktrace` | **exit 0** at the same status hash |
| L1 jar nesting | `unzip` nested `META-INF/jars/*` | only `adventure-api` + `adventure-text-minimessage`; **zero `examination` classes** — L1 still open |

### 11.3 ConfigSchema reconciliation

The three staged-gameplay defaults were flipped `TRUE` -> `FALSE` with new help text
and a new test. **Recommendation: REVERT for this wave.** The full argument is in the
ledger's 2026-11-23 matrix; the load-bearing facts are: (a) `mergePreservingComments`
repairs missing keys to the new default, so upgrades can change behavior silently;
(b) patch state is `regionizedGameplay() && patchEnabled(KEY, false)`, so
`general.regionized-gameplay=true` now enables **nothing** on its own; (c) the live
boot prints every gameplay slice as `server thread`; (d) the test was added in the same
unapproved diff; (e) R4 is not fixed for configs that already say `true`. Approving it
is defensible only as an explicit owner product decision with a config-version bump and
migration note. The manager did not edit the defaults.

### 11.4 Release blockers (unchanged or new)

1. **L1** — all `/folia` diagnostics still fail at runtime; the current jar still lacks
   the Adventure transitives. This blocks any metrics-based performance claim.
2. **R4** — open P0; no ownership-safe claim is allowed.
3. **R2, R3** — open P0, no implementation.
4. **R1 E4** — no `/tp`/portal gameplay check on a disposable world.
5. **R5 E4** — no live crash-report injection.
6. **R6 producer half** — packet/re-home/command/random-tick enqueues are still
   positionless.
7. **T1** — the abort-drop test is still racy (enqueue before `start()` fixes it).
8. **M1** — retired `RegionChunkAccessGuard` + test still compile and run.
9. **ConfigSchema** — unapproved default change in the tree.
10. **Fabrolia** — BLOCKED for comparison; a 26.3 standalone boot is not evidence for a
    26.2-vs-26.3 comparison, and no winner may be named.

### 11.5 Scope separation and shared files

No agent edited another agent's assigned module this wave, but four files are
cross-scope and are listed in the ledger: `FabricFoliaEngine` (core metric + runtime
R5), `RegionStageHub` / `LevelEntityTickMixin` (core pin + runtime connection staging),
`RegionTransitions` (runtime + one frozen QA accessor edit), and
`ConfigSchema`/`ConfigSchemaPatchTest` (unapproved, isolated). `FREEBUFF.md` remains
manager-owned; its mtime changed during the wave but no agent-authored section
remains.

### 11.6 What this review did not do

No live `/tp`, portal, RCON command, benchmark, soak, mixin export, GPL fetch, Java
edit, config edit, commit, push or sync. The 318-test and build results are this
review's only executed checks; every live statement above is quoted from the agents'
artifacts with its provenance named.
