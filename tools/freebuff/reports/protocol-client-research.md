# Protocol-client and benchmark-driver research — MC 26.2 / 26.3

Date: 2026-11-22. Tree: `/tmp/ff-reconcile`, `HEAD=c2fbe2135945e4e0ef42fc70094d3605ef03627d`.

**Nothing was executed against a server. No client was installed, built, run or
smoke-tested. No production server was contacted. No code was written, no commit,
no push.** Every statement below is either read from an official/project source
during this session or explicitly marked as an unverified project claim.

Evidence grades used throughout:

- **[V]** verified this session from source, registry metadata or the project's
  own repository API (commit, tag, license, version constant).
- **[R]** the project's README / PR description asserts it; not exercised here.
- **[U]** unknown or unverified; must be settled by the smoke plan in §6.

---

## 1. Bottom line

1. **26.2 is protocol 776; 26.3 is protocol 777** (Minecraft Wiki, minecraft-data,
   mctoolbox agree). A native driver must speak the server's exact number. A 26.3
   client cannot join a 26.2 server and vice versa.
2. **No upstream PrismarineJS release speaks 26.2 or 26.3.** `minecraft-protocol`
   1.68.0 and `mineflayer` 4.39.0 both cap at **26.1**. 26.2 and 26.3 exist only
   as open PR branches. This contradicts the assumption in
   `tools/freebuff/qa/README.md` that Mineflayer is a ready fallback.
3. **The only mature, maintained project with native 26.2 (776) today is Azalea**
   (`main` at `0d38e0d`, `PROTOCOL_VERSION = 776`). Its published crate is still
   26.1, so 26.2 requires a git pin.
4. **Native 26.3 (777) candidates are all young:** rapidbot (7 weeks old, 5 stars),
   go-mclib (0 stars, no tagged release), mcproto (protocol layer only), and
   MCProtocolLib (`26.3-4` snapshot, no tagged 26.x release). None is proven.
5. **`@wp2508/mineflayer` is not production tooling.** It is a one-person fork with
   nine shadow-forked dependencies (`@wp2508/minecraft-protocol`,
   `minecraft-data`, `protodef`, `prismarine-chunk`, …), ~1,080 weekly downloads,
   and no third-party validation. Full audit or reject.
6. **ViaProxy is a translation layer, not protocol validation.** It is GPL-3.0,
   rewrites packets, and must be reported separately from native evidence. It is
   the correct tool only to keep an old client alive against a newer server.
7. **Recommendation:** two explicitly separated lanes.
   - **26.2 native:** Azalea pinned to `0d38e0d`, gated by the §6 smoke suite;
     fallback is an in-house minimal driver on MCProtocolLib pinned to a 26.2-era
     commit.
   - **26.3 native:** rapidbot `b365a4a` as candidate 1, go-mclib `022b47e` as
     candidate 2, both gated by the same suite.
   - **Translated lane:** ViaProxy 3.4.14, labelled `translated`, never counted
     toward native pass/fail.
   No candidate is approved production tooling until the smoke suite passes and
   the license/maintenance review in §7 is signed off.

---

## 2. Protocol baseline (verified)

| Fact | 26.2 | 26.3 | Source |
|---|---|---|---|
| Release | 2026-06-16 | 2026-09-15 | Minecraft Wiki / mcreference |
| Network protocol | **776** | **777** | Wiki protocol table, minecraft-data `protocolVersions.json`, mctoolbox |
| Data version | 4903 | 5023 | mctoolbox, minecraft-data |
| Java runtime | 25 | 25 | Wiki version pages |
| Configuration state | yes (since 1.20.2 / 764) | yes | Wiki protocol |
| Next snapshots | — | 26.4: `0x40000153`…`0x4000015C` | Wiki protocol table |

Consequences for the harness:

- The pinned server in `assemble_server.sh` is 26.2 → **776**. Any driver that
  reports 777 will be rejected at handshake (or worse, accepted by a lax server
  and then desynchronise).
- Protocol numbers are **not stable identifiers for packet ids**. Packet ids move
  between versions; the driver must take ids from its own version data, never
  hardcode them from a wiki page.

---

## 3. Candidate summary

| Candidate | Exact revision | Protocol | License | Last activity | Verdict |
|---|---|---|---|---|---|
| node-minecraft-protocol (upstream) | npm **1.68.0**, gitHead `2ba6755` | 26.1 max (776/777 only in open PRs) | BSD-3-Clause | 2026-08-27 release | Library only; not a driver; no 26.2/26.3 release |
| mineflayer (upstream) | npm **4.39.0**, gitHead `c168635` | 26.1 max | MIT | 2026-10-25 master | Best API, wrong version; 26.2/26.3 unmerged |
| @wp2508/mineflayer | npm **4.42.9** | claims 777 | MIT (fork) | 2026-11-13 | **Reject for production** without audit |
| Azalea | `0d38e0d` (2026-10-16) | **776 / 26.2** | MIT | active, 832★ | Best native 26.2 candidate |
| rapidbot | `b365a4a` (2026-11-12) | **777 / 26.3** | MIT | active, 5★, 7 weeks old | Best native 26.3 candidate; immature |
| go-mclib/client | `v0.0.0-20261112211548-022b47e95d21` | **777 / 26.3** | MIT | 2026-11-12, 0★ | Viable second 26.3 candidate; unproven |
| mcproto (Rust) | `328e31b`-era HEAD, 96 commits | **777 only** | MIT | 2026 | Protocol codec only, not a driver |
| MCProtocolLib | `65efb9d`, version `26.3-4` | **777** | MIT | 2026-11-12 | Strong org, snapshots only, codec not bot |
| ViaProxy | **3.4.14** | translates to 777 | **GPL-3.0** | 2026-09-27 | Fallback lane only |
| ViaVersion | **5.12.2** | translates | **GPL-3.0** | 2026-10-27 | Server plugin, not a client |

Stars/downloads are maintenance signals only, not quality proof.

---

## 4. Candidate detail

### 4.1 Upstream PrismarineJS `node-minecraft-protocol` (nmp)

- **Version:** npm `1.68.0`, `gitHead 2ba6755`, published 2026-08-27 [V].
- **Protocol:** `src/version.js` at tag 1.68.0 and on `master` both list
  `supportedVersions` ending at **26.1**, `defaultVersion: '26.1'` [V]. This is
  the decisive fact: the released and master code does **not** speak 776 or 777.
- **26.2:** branch `pc26_2` head `0dfb576` (2026-08-11, “Update to version 26.2”),
  never merged [V].
- **26.3:** PR **#1532** (“🎈 26.3”) is **open**, branch `pc26_3`, commit `a3c018e`
  (2026-09-15). A maintainer review on 2026-10-03 said the scaffold is “not ready
  for a master merge” because it depends on unpublished data branches and
  temporary ProtoDef overrides [V].
- **License:** BSD-3-Clause [V, npm registry].
- **Offline mode:** yes — `auth: 'offline'` / no auth, standard nmp client [R].
- **Multi-client:** yes, N client objects in one Node process; event-loop bound.
- **Coverage:** parses/serialises every packet for its supported versions; handles
  compression, encryption, and keep-alive automatically. It has **no physics, no
  world simulation, no movement controller** — packets are events you handle.
  Teleport acknowledgement, chunk-batch acknowledgement and respawn requests are
  application code.
- **Known gaps:** no 776/777 release; version data (`minecraft-data`) must match
  the branch; packet ids come from that data, so a mismatched data package is a
  silent desync risk; 26.3 needs a fork or a branch pin.
- **Role:** foundation for a custom driver, not a driver.

### 4.2 Upstream PrismarineJS `mineflayer`

- **Version:** npm `4.39.0`, `gitHead c168635`, MIT [V].
- **Protocol:** README on `master` says “1.8 to **26.1**” [V].
- **26.2:** PR **#4192** (“26.2: merge master and drop the protodef debug
  overrides”) is **open**, targets `pc26_2`; branch head `c77e6d5` is from
  2026-06-16 [V]. Stale.
- **26.3:** PR **#4124** (scaffold) and PR **#4206** (27 commits: merge master,
  punch/wake/teleport/particle fixes) both **open**. #4206 reports, as a claim,
  “64 passing, 1 pending” internal and “55 passing, 0 failing” external against a
  vanilla 26.3 server, plus a 10-minute live run [R]. Not merged.
- **License:** MIT [V].
- **Offline mode:** yes, `auth: 'offline'` [R].
- **Multi-client:** yes, one process; each bot is an object.
- **Coverage for supported versions:** login, configuration, keep-alive, chunks,
  physics/movement, teleport handling, block place/break, inventory/containers,
  **vehicles/mount**, disconnect, respawn, dimension changes. This is the richest
  high-level API of any candidate [R].
- **Known gaps:** no released 26.2/26.3; 26.3 chunk/light mapping still depends on
  an open `prismarine-chunk` PR (#347); the 26.3 fixes listed in #4206 are exactly
  the packet types this project cares about (teleport, interaction), so an
  unmerged branch is the riskiest place to sit.
- **Role:** best ergonomics, unusable as-is for this stand. Do not adopt a fork to
  paper over the missing release.

### 4.3 `@wp2508/mineflayer` (third-party fork)

- **Version:** npm `4.42.9`, MIT, publisher `wp2508`, repo `wupengabc/mineflayer`
  [V].
- **Protocol:** README claims 26.1, 26.2 and **26.3 (777)** [R].
- **Dependency tree is the problem.** The package resolves **nine shadow forks**:
  `@wp2508/minecraft-protocol@1.69.9`, `@wp2508/minecraft-data@^3.111.8`,
  `@wp2508/protodef@^1.19.2`, `@wp2508/prismarine-chat`, `-block`, `-chunk`,
  `-physics`, `-registry`, plus `socks` [V, npm registry]. Trusting this driver
  means trusting nine packages from one publisher, none of which is the version
  upstream tests against.
- **Maintenance:** ~1,080 downloads/week; version numbering diverges from
  upstream (4.42.x vs 4.39.0), so “same API” cannot be assumed [V].
- **Offline mode / multi-client:** inherited from Mineflayer, expected yes [R].
- **Coverage:** claims the full Mineflayer surface plus 26.3 packet mappings,
  including a 26.2 outbound `0x3e–0x44` fix [R].
- **Known gaps:** no npm provenance attestation shown in the registry document;
  no independent test evidence found; one maintainer; no upstream review.
- **Verdict:** **do not select as production tooling.** If it is ever considered,
  vendor the exact tarball, diff every shadow fork against its upstream tag,
  run the §6 suite, and record the audit. License is compatible (MIT), trust is
  not established.

### 4.4 Azalea (Rust)

- **Version:** git `main` head `0d38e0d` (2026-10-16) [V]. crates.io release is
  `0.16.0+mc26.1`, about five months old [V].
- **Protocol:** `azalea-protocol/src/packets/mod.rs` on `main`:
  `PROTOCOL_VERSION: i32 = 776; VERSION_NAME = "26.2"` [V]. **Native 776.**
- **License:** MIT [V]. 832 stars, 134 forks, active (updated 2026-11-21) [V].
- **Offline mode:** `Account::offline("name")` [V, docs].
- **Multi-client:** explicitly a goal (“make it easy to have many bots working at
  the same time”); has a `swarm` module [R].
- **Coverage:** login/config/keep-alive handled by `azalea-protocol`; chunks and
  chunk batching handled; accurate physics; pathfinder; `mine`; `block_interact`;
  inventories; attack. Dimension changes and respawn are part of the client
  state machine [R].
- **Known gaps:** published crate is 26.1 → **git pin required** for 26.2; some
  physics unfinished (entity pushing, sprint swimming) [R]; vehicle/mount control
  is not documented in the README [U]; one primary maintainer; breaking changes
  warned; Bevy/ECS means a non-trivial learning curve for scenario code.
- **Role:** **primary native 26.2 driver candidate.** Rust also makes 40 clients
  on one host far more plausible than 40 JVMs.

### 4.5 rapidbot (Rust, headless)

- **Version:** `main` head `b365a4a` (2026-11-12); repo pushed 2026-11-21; created
  2026-10-02; 196 commits at first inspection [V]. 5 stars, 4 forks [V].
- **Protocol:** README: “Targets **Minecraft 26.3** (protocol 777)” [V/R].
- **License:** MIT, `LICENSE` present [V].
- **Offline mode:** `Account::Offline { name }` in the README example [V/R].
- **Multi-client:** “Several bots can run in one process; each has its own main
  thread” [R]. Good process model for a 40-client stand.
- **Coverage:** vanilla-exact physics and tick timing; route finding; digging;
  hotbar/inventory/worn items; container close; chat with modeled keystrokes;
  attack/combat; resource-pack policy. Login, configuration and keep-alive are
  part of `rapidbot-client` [R].
- **Hard build prerequisite:** block shapes, item components, `en_us`, `Mth`
  tables and test vectors must be generated from **the operator's own 26.3 jar**
  (`tools/fetch_vanilla.py 26.3`, `gen_packet_ids.py`, `extract_blocks.py`,
  `extract_items.py`, `gen_clip_vectors.py`). The build **stops** without them
  [V/R]. That is good for licensing hygiene and bad for reproducibility unless
  the generated data is hashed into the run manifest.
- **Known gaps:** seven weeks old, single maintainer, no tagged release, no
  independent audit; mount/vehicle, respawn and portal scenarios are **not**
  documented [U]; a Sentry DSN is enabled by default in the recorder tool only
  [R] — confirm before running any network-enabled component.
- **Role:** **candidate 1 for native 26.3**, contingent on the smoke suite.

### 4.6 mcproto (Rust)

- **Version:** `main`, created 2026-04-19, 96 commits, MIT [V].
- **Protocol:** packet modules state “protocol 777 (Minecraft Java Edition 26.3)”
  throughout; handshake test uses `VarInt(777)` [V].
- **Scope:** codecs, typed packet payloads, framing, compression, encryption,
  Microsoft auth. **No client driver, no physics, no world model** [V/R].
- **Role:** a possible foundation if we ever write our own Rust driver; not a
  candidate driver by itself. Low visibility (no independent consumers found).

### 4.7 go-mclib (`client`, `protocol`, `data`)

- **Version:** module `github.com/go-mclib/client`
  `v0.0.0-20261112211548-022b47e95d21`, commit `022b47e`, 2026-11-12 [V].
  No tagged release; pseudo-version only.
- **Protocol:** `go-mclib/data` is generated for **26.3 only**, `ProtocolVersion =
  777`, `DataVersion = 5023` [V, pkg.go.dev].
- **License:** MIT [V/R].
- **Maintenance:** 0 stars, 0 forks, 0 open issues; active Aug–Nov 2026 [V].
  Single organisation, no visible community.
- **Offline mode:** yes — “connecting to offline/online mode servers” [R].
- **Multi-client:** goroutines; no fleet harness shipped [U].
- **Coverage (README, all [R]):** keep-alive; chat/signed commands/tab completion;
  health/XP/auto-respawn; loaded world and block interactions; authoritative
  inventory with confirmed clicks; entity observation and combat; vanilla 26.3
  collision/physics; A* navigation; voice chat. Passengers are observed but
  **vehicle control is not documented** [U].
- **Known gaps:** no release engineering, no third-party users, self-described
  work in progress, no mount/portal scenario, no independent test evidence.
- **Role:** **candidate 2 for native 26.3.** The feature list is the closest match
  to this project's needs, but the trust evidence is the weakest.

### 4.8 MCProtocolLib (Java)

- **Version:** `master` head `65efb9d` (2026-11-12); `protocol/build.gradle.kts`
  sets `version = "26.3-4"`; `MinecraftCodec` declares
  `.protocolVersion(777).minecraftVersion("26.3")` [V].
- **Release status:** the newest **GitHub release** is `1.21.4-1`; the releases
  page shows no 26.x tag at all. 26.x is published only to
  `repo.opencollab.dev` **snapshots** [V]. Pin the exact snapshot and record its
  checksum; a moving snapshot is not reproducible.
- **License:** MIT [V].
- **Maintenance:** GeyserMC org, multiple contributors, commits through
  2026-11-12 [V]. Best governance of the codec candidates.
- **Coverage:** complete packet codec for handshake/status/login/configuration/
  play; session/proxy helpers. **No physics, no bot logic, no scenarios** — we
  write the client loop [V/R].
- **Known gaps:** no tagged 26.x release; snapshot API churn; 777 only (a 26.2
  stand needs the historical 26.2 commit); one JVM per N clients is heavy.
- **Role:** **in-house fallback**. If Azalea fails the smoke gate, a minimal
  MCProtocolLib-based driver (login → config → play → keep-alive → position ack →
  chunk ack → block/inventory actions) is the lowest-trust-risk path because the
  codec is organisation-backed and MIT.

### 4.9 ViaProxy / ViaVersion (translation only)

- **ViaProxy 3.4.14**, published 2026-09-27, **GPL-3.0** [V]; standalone proxy,
  supports a **26.3 target** and lets an older client (e.g. 26.1) connect [R,
  maintainer discussion].
- **ViaVersion 5.12.2**, published 2026-10-27, **GPL-3.0** [V]; a server plugin,
  not a client.
- **Why it is not equivalent:** the proxy terminates the client's protocol and
  re-encodes packets to 777 (or 776). Packet ids, framing, and some field
  semantics differ. A teleport that the server dispatches to a region worker is
  observed by the proxy, not by a native 26.2/26.3 client. Region re-homing,
  packet-order guarantees and cross-dimension sequences are exactly the things
  translation can mask or reorder.
- **Valid uses:** keeping a 26.1 client alive to exercise content paths; a
  cross-check that the server accepts translated traffic; quick manual play.
- **Invalid uses:** any pass/fail claim about native packet handling; any metric
  labelled “protocol clients” without `translated: true`.
- **License note:** GPL-3.0. Run it as a separate process; do not vendor, link or
  redistribute it with this Apache-2.0 project.

---

## 5. Capability matrix

Legend: **Y** supported by the project (documented), **P** partial / needs our
code, **N** absent, **?** unverified. All Y/P entries for upstream Mineflayer and
nmp apply **only to 26.1**; for 26.2/26.3 they are branch claims until the smoke
suite runs. Evidence grade is per row group, not per cell.

| Capability | nmp | mineflayer | @wp2508 | Azalea (776) | rapidbot (777) | go-mclib (777) | MCProtocolLib (777) |
|---|---|---|---|---|---|---|---|
| Offline login | Y | Y | Y | Y | Y | Y | P (codec) |
| Configuration state | Y | Y | Y | Y | Y | Y | Y (codec) |
| Keep-alive echo | Y | Y | Y | Y | Y | Y | P |
| Chunk receive + batch ack | P | Y | Y | Y | Y | Y | P |
| Movement / physics | N | Y | Y | Y | Y | Y | N |
| Teleport packet + ack | P | Y | Y | Y | Y | Y | P |
| Block place/break | P | Y | Y | Y | Y | Y | P |
| Inventory / containers | P | Y | Y | Y | P | Y | P |
| Mount / vehicle | N | Y | Y | ? | ? | ? | P |
| Disconnect handling | Y | Y | Y | Y | Y | Y | Y |
| Respawn | P | Y | Y | Y | ? | Y | P |
| Cross-dimension | P | Y | Y | Y | ? | ? | P |
| Multi-client | Y | Y | Y | Y | Y | P | Y |
| Evidence grade | V (versions) | V (versions) | R | V (776 const) | R/V | V (777 const) | V (777 const) |

Notes:

- “P (codec)” means the packet type exists and can be encoded/decoded, but the
  behaviour (ack, request, retry) is application code.
- `?` means the project does not document it. The smoke plan must answer it; do
  not assume a capability because a sibling project has it.
- Mount is the weakest column across every 26.3 candidate. If the stand must
  validate boat/minecart passenger routing, budget time to implement it on top of
  the chosen driver.

---

## 6. Reproducible protocol smoke test plan

Every candidate must pass this **before** selection. The plan is deliberately
driver-agnostic: each phase names an observable, not an API. Run it against a
disposable 26.2 instance (protocol 776) and a disposable 26.3 instance (777);
a driver that only supports one number is tested only there.

### 6.0 Preconditions and pinning

1. Server artifact SHA-256, Fabric loader/API versions, mod jar SHA-256 and world
   seed recorded in the manifest (already implemented in `assemble_server.sh`).
2. Driver pinned to an exact commit/tag and recorded with its lockfile hash
   (`package-lock.json`, `Cargo.lock`, `go.sum`) or Maven snapshot checksum.
3. Protocol number asserted, not assumed: read it from the driver's version data
   **and** from the server's status response, and require equality.
4. `online-mode=false` only. No Microsoft accounts, no auth services.

### 6.1 Phases

| # | Phase | Action | Pass criterion | Artifact |
|---|---|---|---|---|
| S1 | Status ping | Handshake `nextState=1`, status request | `version.protocol == 776` (or 777) and a non-empty MOTD | `s1-status.json` |
| S2 | Login | Handshake `nextState=2`, Login Start with unique name | Login Success within 10 s; UUID equals offline UUID for the name | `s2-login.ndjson` |
| S3 | Configuration | Client Information, accept registry data, Finish Configuration ack | No disconnect for 10 s; server log shows join continuing | `s3-config.ndjson` |
| S4 | Play join | Play Login packet | Spawn position, dimension key and game mode received | `s4-join.ndjson` |
| S5 | Chunks | Chunk batch start/data, send Chunk Batch Received | ≥ 9 columns around spawn; block at spawn readable; batch ack cadence stable | `s5-chunks.csv` |
| S6 | Keep-alive | Idle 60 s, echo every keep-alive | 0 timeouts; RTT p99 recorded | `s6-keepalive.csv` |
| S7 | Movement | Walk 32 blocks on +X on flat ground | Server-side position moves ≥ 30 blocks (RCON `data get entity`); no fall correction while walking | `s7-move.csv` |
| S8 | Teleport | RCON `/tp` the client +100 X | Exactly one position packet with teleport id, acknowledged; final server/client positions agree within 0.1 | `s8-teleport.csv` |
| S9 | Block interaction | Place and break a block at a known coordinate | Clientbound block change both ways; RCON `execute if block` agrees | `s9-block.ndjson` |
| S10 | Inventory | RCON `give`, then move a stack in the inventory | Server-side inventory matches; no desync after 5 s | `s10-inventory.ndjson` |
| S11 | Mount | Summon a boat, mount, move, dismount | Passenger/vehicle packets observed; server reports the player as passenger, then not | `s11-mount.ndjson` |
| S12 | Respawn | RCON `/kill` | Death state, respawn request, new player entity id, spawn position | `s12-respawn.ndjson` |
| S13 | Cross-dimension | Nether portal or `/execute in the_nether run tp` | Dimension change sequence, registry/dimension switch, chunk reload, **no disconnect** | `s13-dimension.ndjson` |
| S14 | Disconnect | Client closes cleanly | Server removes the player, no exception, reconnect with the same name succeeds | `s14-disconnect.log` |
| S15 | Fleet soak | 40 clients, 10 min, ramp 4/s | 0 unexpected disconnects; keep-alive p99 < 2 s; correction rate stable | `s15-fleet/summary.json` |

S1–S5 must pass for a driver to be considered “usable”; S6–S14 define “complete”
for stand evidence; S15 is required before the full benchmark profile.

### 6.2 Failure detection (how, not just what)

**Login stall.** Per-client state machine with hard deadlines: TCP connect 2 s,
Login Success 10 s, configuration finish 5 s, play join 10 s. On expiry emit
`LOGIN_STALL` with `last_state`, `last_packet`, `elapsed_ms`, and keep the socket
open for 2 s to capture a late server disconnect reason. A stall is a failure
even if a retry later succeeds; retries are logged separately.

**Disconnect.** Classify each close: server kick with reason (login, config or
play disconnect packet), TCP reset, read timeout, driver exception. Capture the
reason component as text. Any server-side `handleDisconnection() called twice`
warning is recorded but not treated as a client failure (known pre-existing quirk).

**Teleport correction.** Count clientbound position/teleport packets carrying a
teleport id, plus the delta between the client's predicted position and the
server position. Expected: exactly one correction after S8's `/tp`, zero during
steady walking on flat ground. Flag `DRIFT` when a correction arrives with no
server-side teleport cause, or when the client fails to acknowledge within 1 s
(server will resend and eventually rubber-band). Correlate with the server's
`player transitions dispatched` metric to distinguish a client bug from an
engine dispatch bug.

**Chunk starvation.** If the client stops sending Chunk Batch Received, the server
throttles chunk sending; detect as “chunks stop arriving while the connection is
healthy” and report it as a driver defect, not a server stall.

**Keep-alive.** Track sent/received id pairs and RTT. Two missed keep-alives is a
warning; three is a failure (vanilla disconnects around 30 s).

### 6.3 Evidence rules

- Raw NDJSON per client, append-only, one line per packet/event:
  `ts_mono_ns, run_id, client_id, session_id, direction, state, packet_name,
  packet_id, size, teleport_id, pos, note`.
- A summary JSON derived from the NDJSON, never hand-written.
- A phase table with pass/fail per client; a single failed client fails the phase.
- Screenshots are not evidence. Log lines are not evidence without the packet
  record that produced them.

---

## 7. License and maintenance review (production gate)

| Candidate | License | Compatible with Apache-2.0 tooling? | Distribution risk | Maintenance risk |
|---|---|---|---|---|
| nmp | BSD-3-Clause | yes | notice required | low (org), but no 26.x release |
| mineflayer | MIT | yes | notice | low (org), no 26.x release |
| @wp2508/mineflayer | MIT + 9 shadow forks | yes on paper | **high** — unaudited fork tree | **high** — one publisher |
| Azalea | MIT | yes | notice | medium — one primary maintainer, breaking changes |
| rapidbot | MIT | yes | notice | **high** — 7 weeks old, one maintainer |
| go-mclib | MIT | yes | notice | **high** — no release, no users |
| MCProtocolLib | MIT | yes | notice | low (GeyserMC), snapshot-only |
| ViaProxy / ViaVersion | **GPL-3.0** | **no for linking/vendoring** | **high** | low (org) |

Gate for any third-party fork:

1. Exact commit and lockfile hash pinned; no floating ranges.
2. Every transitive dependency resolved to a named upstream or a diffed fork.
3. License files present for every package in the tree.
4. A named maintainer and a public issue tracker.
5. The §6 suite passes on the exact pin.
6. A recorded decision that the fork is test tooling, not shipped code.

`@wp2508/mineflayer` fails 2 and 6 today. ViaProxy fails 3 for any distribution
that bundles it; running it as a separate process is acceptable.

---

## 8. Recommended 40-client stand design

### 8.1 Lanes (never mixed in one result)

| Lane | Driver | Server | Label in artifacts | Counts as native evidence? |
|---|---|---|---|---|
| A. 26.2 native | Azalea `0d38e0d` (git pin) | 26.2 / 776 | `native-26.2` | **yes** |
| B. 26.3 native | rapidbot `b365a4a` (or go-mclib `022b47e`) | 26.3 / 777 | `native-26.3` | **yes** |
| C. Translated | Mineflayer 4.39.0 (26.1) via ViaProxy 3.4.14 | 26.3 / 777 | `translated-via-3.4.14` | **no** |

`run_bench.sh` must record `protocol_driver`, `driver_commit`,
`driver_protocol_version`, `translated: true|false` and
`protocol_clients_native` separately from `protocol_clients`. A translated run
must never satisfy the full profile's client requirement.

### 8.2 Process model

- **Supervisor** (one process): owns the run lock, allocates client ids, enforces
  the global connect rate, aggregates heartbeats, writes `summary.json`, and stops
  all workers on timeout or on a server boot-gate failure.
- **Workers**: 5 processes × 8 clients, or 4 × 10. One worker crash loses at most
  its own clients and is reported as a worker failure, not a server failure.
- **Isolation**: server on its own CPU set; workers on a disjoint set; per-worker
  memory cap; no client shares a thread with the supervisor's sampler.
- **Lifecycle**: clients never outlive the run. Supervisor kills the worker group
  (not individual PIDs) after the RCON stop, and the existing trap/sweep rules
  apply.

Why not 40 in one process: a single driver exception or GC pause would corrupt
the whole sample and conflate client failure with server behaviour.

### 8.3 Unique offline identities

- In offline mode the server derives the UUID from `OfflinePlayer:<name>`, so
  **unique names are the identity**.
- Name format: `ff-<runid8>-<nn>` (≤ 16 chars). `runid8` is the first 8 hex chars
  of the run id already written to the manifest.
- Never reuse a name across runs: player data, advancements and stats would leak
  between runs. The supervisor asserts names are unique within the run and absent
  from the world's `playerdata/` before starting.
- Record the derived UUID per client in the manifest and assert S2's Login Success
  UUID matches it.

### 8.4 Rate control

- Connect ramp: ≤ 4 clients/second, jittered; no thundering herd at t=0.
- Movement: 20 ticks/s per client, but stagger client tick phase so packets do
  not align on the same millisecond.
- Chunk batch: desired batch size 8–16, acknowledgement per batch; never more
  than 2 outstanding.
- Global budget: cap total packet rate; when the server's MSPT p95 exceeds a
  threshold, the supervisor pauses new connects (not existing clients) and marks
  the run `rate_limited` so the sample is not silently distorted.
- No client retries a connect more than twice; a retry is recorded.

### 8.5 Deterministic per-client scenarios

- Scenario = ordered list of `(tick_index, action)`; no wall-clock sleeps.
- Seed = `hash(run_id, client_index)`; all random choices (yaw jitter, target
  selection) draw from it.
- Profiles map onto the existing workload names: `distributed`, `hot-island`,
  `items`, `pistons`, `chunk-churn`.
- Each phase has a barrier: the supervisor starts phase N+1 only when all live
  clients report phase N complete or failed, so a slow client cannot silently
  shift the workload.
- A scenario that depends on terrain must first assert the spawn area (S5 block
  read), not assume flat ground.

### 8.6 Packet/event logs

- One NDJSON stream per client, buffered and flushed on phase boundaries and on
  disconnect; loss of the last lines is acceptable only for a crashed client.
- Include the server tick index when obtainable from the protocol (time update),
  so client events can be aligned with server MSPT samples.
- Keep the driver's own debug logging off by default; it changes timing.
- Artifacts land under the existing run directory, never in the repository.

### 8.7 Detecting the three failure classes

| Failure | Signal | Attribution |
|---|---|---|
| Login stall | S2/S3 deadline exceeded; `LOGIN_STALL` event | driver vs server: compare server log join attempt |
| Disconnect | kick reason / TCP reset / timeout | server kick reason names the cause; timeout points at keep-alive |
| Teleport correction | position packet with teleport id + delta | correlate with server `player transitions dispatched` and `/tp` events |

The engine already exposes `player transitions dispatched` and respawn metrics;
the client-side correction count is the missing half of that pair. Recording both
is what makes a teleport bug attributable instead of “the bot jittered”.

### 8.8 Hardware reality

The FAC host is 16 GB. A 16 GB server heap plus 40 clients is not viable there
(pre-existing constraint, `tools/freebuff/reports/fabrolia-stand-reference.md`).
Native Rust clients are the only realistic way to fit 40 on a 32 GB benchmark
host; JVM/Node clients multiply the footprint. This is a further argument for
lane A on Azalea and lane B on a Rust driver. Do not measure memory from this
report — measure it during S15.

---

## 9. Recommendation

**Adopt, in order:**

1. **Lane A (26.2 native): Azalea `0d38e0d`.** Only maintained project with
   native 776. Gate on S1–S15; if mount or cross-dimension is missing, implement
   the scenario on top of its API rather than switching drivers.
2. **Lane B (26.3 native): rapidbot `b365a4a` first, go-mclib `022b47e` second.**
   Both are 777-only and both need the full smoke gate; run the same scenario
   suite on both and keep the one with fewer corrections and no stalls.
3. **Fallback for either lane:** an in-house minimal driver on **MCProtocolLib
   `26.3-4`** (or the matching 26.2 commit), MIT and organisation-backed. This is
   the lowest-trust-risk option and the only one that does not depend on a
   single-person project, at the cost of writing the client loop.
4. **Lane C (translated): ViaProxy 3.4.14 + Mineflayer 4.39.0 (26.1).** Useful
   for content-path checks and manual play; never reported as native evidence;
   never satisfies the 40-client requirement.

**Do not adopt:** `@wp2508/mineflayer` (unreviewed fork tree), any unmerged
PrismarineJS branch as “production” without a pin and the smoke suite, or
ViaProxy as a substitute for native validation.

**Open decisions for the manager:**

1. Is the stand allowed to run on 26.3 at all, or must it stay on 26.2 (the mod's
   target)? This determines whether lane B is in scope now.
2. Approve a 32 GB benchmark host for the full profile; the FAC host can only run
   the reduced smoke profile.
3. Approve the in-house MCProtocolLib fallback budget if Azalea fails S11/S13.
4. Approve pinning a third-party Rust project (rapidbot or go-mclib) as test
   tooling after the smoke gate, with the audit recorded in this file.

---

## 10. What was not done

- No smoke test was executed; every capability marked [R] is a claim.
- No package was installed; no Rust/Node/Go/Java client was built.
- No server was booted; no RCON session was opened.
- No network access to any production or user server.
- No packet ids were extracted from a jar in this session; the report deliberately
  avoids hardcoded ids.
- Mount, respawn and cross-dimension support for rapidbot and go-mclib remain
  unverified and are the first things S11–S13 must answer.

## 11. Sources (retrieved 2026-11-22)

- Minecraft Wiki, Protocol version / Java Edition 26.3 / protocol packets.
- mctoolbox.net version table (26.2=776/4903, 26.3=777/5023).
- PrismarineJS/node-minecraft-protocol: npm registry `latest` (1.68.0), tag
  `1.68.0` `src/version.js`, `master` `src/version.js`, PR #1532, branches
  `pc26_2`/`pc26_3`.
- PrismarineJS/mineflayer: npm `latest` (4.39.0), `master` docs/README, PRs
  #4124, #4192, #4206, branches `pc26_2`/`pc26_3`.
- npm registry `@wp2508/mineflayer` `latest` (4.42.9) and dependency list;
  npm downloads API (1,080/week).
- azalea-rs/azalea: `main` README, `azalea-protocol/src/packets/mod.rs`
  (`PROTOCOL_VERSION 776`), HEAD `0d38e0d`, crates.io 0.16.0+mc26.1.
- creator1116/rapidbot: README, LICENSE, HEAD `b365a4a`, repo API.
- go-mclib/client README; proxy.golang.org `@latest`;
  pkg.go.dev `go-mclib/data` (`ProtocolVersion 777`).
- MCSLTeam/mcproto README and protocol-777 packet modules.
- GeyserMC/MCProtocolLib: `master` README, `protocol/build.gradle.kts`
  (`26.3-4`), `MinecraftCodec` (`protocolVersion(777)`), HEAD `65efb9d`,
  releases page.
- ViaVersion/ViaProxy releases (3.4.14) and LICENSE (GPL-3.0);
  ViaVersion releases (5.12.2).
