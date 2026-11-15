# TEAM REPORT — Branding, Documentation & OSS Maintainer Experience

**Project:** fabric-folia (Fabric-Folia)
**Audit type:** read-only perception + documentation-architecture audit
**Date:** 2026-11-15
**Method:** read README, LICENSE, `fabric.mod.json`, `gradle.properties`, `build.gradle.kts`
(api/fabric), ARCHITECTURE, REGIONS, THREADING, SCHEDULING, MIXINS, COMPATIBILITY,
TROUBLESHOOTING, TESTING, `docs/folia-parity.md`, `docs/gpu.md`,
`docs/compatibility/*`, AGENTS/CLAUDE/FREEBUFF, the API surface, and the registered
command tree. No build, boot, test run, commit or push was performed.

---

## 1. Verdict in one paragraph

The engineering evidence in this repository is unusually strong — a parity ledger
with a fixed status vocabulary, per-mixin tables, measured compatibility pages, and a
symptom-first troubleshooting doc. **The presentation undermines it.** The three
documents a newcomer reads first (README status, ARCHITECTURE status, troubleshooting
scope) each describe a *different, older* milestone than the parity ledger does, and
one of them says gameplay interception is "not active" while the ledger marks entity,
block-entity, scheduled-tick and player paths as implemented/tested/validated. The
project is simultaneously **underclaiming** its capability and **overclaiming** its
release readiness (unqualified `0.1.0`, no pre-release marker, no install path, no
download artifact, no warning about production worlds). Naming is inconsistent across
five surfaces (`Fabric-Folia` / `Fabric Folia` / `folia-0.1.0.jar` / `fabricfolia` /
package root), the shipped 512×512 `logo.png` is never referenced, and the entire
"contributor" and "operator support" layer — CONTRIBUTING, SECURITY, CoC, CHANGELOG,
command reference, config reference, API examples, docs index, release notes — does
not exist. A first-time Fabric developer would conclude "impressive internals,
abandoned or alpha project, no way in." Fixing this is mostly deletion and one
canonical status source, not new writing.

---

## 2. What a first-time visitor actually sees (60-second walkthrough)

| Surface | What they see | Perceived signal |
|---|---|---|
| Repo root | 11 markdown files + a **103 KB** `FREEBUFF.md` | internal working directory published by accident |
| README first screen | one Jenkins badge, plain-text title, one-paragraph tagline, then a status paragraph | no logo, no badges (license/version/MC/Java), no install command |
| README status | "first interception milestone … All other vanilla passes still run on the server thread" (`README.md:13-22`) | project is early/experimental — contradicted 40 lines of parity table later |
| README build | `fabric/build/libs/fabric-0.1.0.jar` (`README.md:90,108`) | file does not exist; Gradle produces `folia-0.1.0.jar` (`fabric/build.gradle.kts:17-19`) |
| ARCHITECTURE first screen | "gameplay interception is not active" (`ARCHITECTURE.md:11`) | directly contradicts README and the ledger |
| docs listing | no index; 8 root docs + 2 `docs/` docs + `docs/compatibility/` | no obvious reading order, two competing "start here" candidates |
| Contributing | nothing | external PRs effectively unsolicited |
| Getting a jar | nothing (no releases, no tags, no Modrinth/CurseForge, no install section) | cannot adopt without building from source |
| `fabric.mod.json` | no `icon` field | generic mod entry in the server/mod list despite owning a logo |

---

## 3. Evidence ledger — conflicting claims (all verified in-tree)

These are the load-bearing defects. Each is a *contradiction*, not a style opinion.

| # | Claim A | Claim B | Consequence |
|---|---|---|---|
| C1 | `README.md:13-22`: "first interception milestone … All other vanilla passes still run on the server thread; the remaining tick pipeline is the next milestone" | `docs/folia-parity.md:86-101`: entity ticking, block entities, scheduled block/fluid ticks `IMPLEMENTED`/`TESTED (live)`; `:114` player path `VALIDATED (live, MC 26.2 + protocol bot)` | README advertises roughly 10 % of the engine. This is the single biggest credibility leak: the README's own "Documentation" section links to a ledger that refutes its status paragraph. |
| C2 | `ARCHITECTURE.md:11`: "gameplay interception is not active" | same as above; `docs/folia-parity.md` staged-slice rows | A design doc that says the opposite of the shipped design. Worse than silence. |
| C3 | `ARCHITECTURE.md:247-260`: scheduled ticks, block entities, entity pass "still execute on the server thread" | `docs/folia-parity.md:90-101` | Anyone evaluating safety boundaries reads a false hazard statement. |
| C4 | `ARCHITECTURE.md:261-267`: `MissingRegionPolicy.RUN_ON_GLOBAL` fallback is "a documented TODO"; entity scheduler has "a stub resolver" | `docs/folia-parity.md:27` (entity scheduler wired to live `RegionEntityRegistry`), `:114` (entity hooks validated live) | Contradicts the ledger on two API behaviors other mods depend on. |
| C5 | `TROUBLESHOOTING.md:6`: "the regionized-random-ticks slice is the only intercepted vanilla pass so far" | parity ledger staged slices | Operators debug against a pipeline that no longer exists. |
| C6 | `docs/compatibility/README.md:62-64`: "today: the per-chunk random-tick pass" / "those passes are not regionized yet" | parity ledger | The compatibility guide for mod authors describes the pre-staging architecture, and its advice about what is safe on a worker is therefore wrong. |
| C7 | `docs/compatibility/README.md:69` links `docs/developer/` | directory does not exist | Broken link on the page mod authors are sent to for API guidance. |
| C8 | `COMPATIBILITY.md:28`: matrix measured **2026-09-15**, protocol configured `regionized-random-ticks: true` (the opt-in slice) | The staged pipeline is the default path now | The "measured, never assumed" matrix measures a configuration most users will not run. Honest, but scoped to the wrong build. |
| C9 | `TESTING.md:10-11`: `./gradlew test` is "equivalent today" to `:common:test`; "all tests live in common/" | 22 test files in `common`, **19 in `fabric`** (real engine boots), `api` has its own test source set; AGENTS/CLAUDE require three-module runs | Contributors run the wrong command and believe green == covered. |
| C10 | `TESTING.md:21`: "116 … as of 2026-09-24" | `FREEBUFF.md` records 184 tests; suite has since grown | Stale count presented as authoritative. |
| C11 | `MIXINS.md:8`: "26 mixins registered; 23 documented" | `fabricfolia.mixins.json` contains 26 → internally consistent; but `FREEBUFF.md` §1 says 24 | The agent handoff contradicts the public mixin register; new contributors cannot tell which is true. |
| C12 | `README.md:100`: Fabric API `0.160.0+26.2 (or newer for 26.2)` | `fabric.mod.json:28`: `"fabric-api": "*"` | Docs promise a floor the metadata does not enforce; a wrong API version installs then misbehaves. |
| C13 | `README.md:120`: fabric module contains "(future) Mixins" | 26 registered mixins | Wrong module description on the main architecture summary. |
| C14 | `api/.../FabricFoliaApi.java` class javadoc: "Until the receiving endpoint is wired (a later phase), all accessor methods throw `IllegalStateException`" | `engine/FabricFoliaApiImpl.java` exists and `FabricFoliaMod.java:263-264` delivers the `fabricfolia` entrypoint | The public API's own javadoc tells mod authors the API does not work. |
| C15 | `README.md:171` and `README.md:198` both describe `docs/folia-parity.md` | duplicate entry | Reading list looks unmaintained. |
| C16 | 43 references to an absent private spec/mandates across public docs (`spec section 12`, `spec 17`, `mandate §43`, `spec 8`) | `AGENTS.md:82` points at `docs/mandates/`, which **does not exist** | Public docs cite rules the public cannot read; this reads as an internal document leaked into the open. |

**Reading of C1–C16:** the project has a *status propagation* failure, not a writing
failure. `docs/folia-parity.md` is the only document that has kept up; everything else
drifted. Any fix must establish one canonical status source and make other docs
*derive* from it rather than restate it.

---

## 4. Branding and identity audit

### 4.1 Name — five spellings, one collision surface

| Surface | Value | File |
|---|---|---|
| Display name | `Fabric-Folia` | `README.md:3`, `fabric.mod.json` `name` |
| Docs headings | `Fabric Folia` (space) | `TROUBLESHOOTING.md:1`, `THREADING.md:1`, `MIXINS.md:1`, `docs/folia-parity.md:1` |
| Repo / archives base | `fabric-folia` | `gradle.properties:34`, `GitHub` |
| Jar name | `folia-<version>.jar` | `fabric/build.gradle.kts:17-19` |
| Mod id / entrypoint key / package | `fabricfolia` / `fabricfolia` / `com.palordersoftworks.fabricfolia` | `fabric.mod.json:3`, `FabricFoliaMod.java:55,263` |

The compound name asserts a relationship with two third-party trademarks
(**Fabric** — FabricMC; **Folia** — PaperMC) that the project does not have. The
clean-room disclaimer exists (`README.md:202-204`, `ARCHITECTURE.md:189-206`), but it
is at the *bottom of the README* and framed purely as a **licensing** statement, not an
**affiliation/trademark** statement. Nothing in the repo says "not affiliated with
FabricMC, PaperMC, or Mojang". For a public repo whose name is `fabric-folia`, that is
the highest-risk branding gap and the cheapest to fix.

Recommended (no rename required, but decide and document):
- Canonical form `Fabric-Folia` everywhere (hyphen, title case), declared once in a
  `docs/branding.md` naming table.
- Artifact name aligned to the brand: `fabric-folia-<version>.jar`. The current
  `folia-0.1.0.jar` is the worst of both worlds — it drops the "fabric" qualifier and
  reads like a PaperMC build. (The build comment at `fabric/build.gradle.kts:15-19`
  treats the name as "free to carry the brand"; it currently carries the *other*
  project's brand.)
- Display name in `fabric.mod.json`: `Fabric-Folia` is acceptable **only** with the
  disclaimers below; otherwise consider a non-compound name. A rename is a product
  decision and out of scope for a docs pass — but it must be an explicit decision, not
  drift.
- One-line disclaimer, repeated in README header, `fabric.mod.json` description, and
  `docs/branding.md`: *"Fabric-Folia is an independent project. It is not affiliated
  with, endorsed by, or supported by FabricMC, PaperMC (Folia), or Mojang/Microsoft."*

### 4.2 Version identity

- One version string: `0.1.0` (`gradle.properties:33`), unqualified, no `-alpha`/`-SNAPSHOT`.
- Zero git tags (`git tag -l` → empty) and no releases → the version is not a
  *published* identity, just a build property.
- Status prose is the only maturity signal, and it is wrong (C1).
- `fabric.mod.json` has no `icon`, no `contact.issues`, no `custom` block; `homepage`
  and `sources` are identical.

Recommendation: treat maturity as data. Publish `0.1.0-alpha.N` (or `0.1.0-SNAPSHOT`
plus a `STATUS.md` "pre-release, not production-ready" banner) and cut one git tag so
the README badge, jar name, changelog and compatibility matrix can all cite the same
version. Note that `COMPATIBILITY.md:28` already pins "Fabric Folia 0.1.0, measured
2026-09-15" — that matrix is now keyed to a build that predates the staged pipeline.

### 4.3 Logo

- `fabric/src/main/resources/logo.png` — 512×512 RGBA, 25,946 bytes, present since the
  first commit (`Sep 16 12:18`, untouched by 55 commits).
- **Not referenced anywhere**: not in `fabric.mod.json` (`icon` absent), not in
  `README.md`, not in docs.
- 512×512 is 4× the conventional mod-list icon and ~26 KB inside a mod jar that is
  otherwise code.

Recommendation: ship a 128×128 (and 256×256) icon, declare `"icon": "assets/fabricfolia/icon.png"`
in `fabric.mod.json`, use the 512 version as README header art, and keep the source
512 file out of the jar. Today the project has brand art it never shows — the cheapest
possible first-glance upgrade.

---

## 5. Status honesty audit

The project is honest in the *hardest* place (`docs/folia-parity.md` uses a fixed
five-step vocabulary, names tests, and distinguishes TESTED from VALIDATED) and
dishonest-by-omission in the *easiest* places. Three distinct problems:

1. **Underclaiming (P0).** README and ARCHITECTURE describe a milestone ~8 commits old
   and omit the staged tick pipeline, the player path, the patch registry, metrics
   instrumentation, GPU subsystem, and `/folia` diagnostics overhaul. A reader cannot
   evaluate the project, and a maintainer loses the "what is it worth trying" pitch.
2. **Overclaiming readiness (P0).** "proven end-to-end on live servers"
   (`README.md:13`) sits beside an unqualified `0.1.0`, no release artifact, no install
   instructions, and no "don't run this on a world you care about" warning. "Live
   server" evidence is real (FREEBUFF records production incidents), but "proven
   end-to-end" is not the same claim as "safe to deploy". `docs/folia-parity.md` gets
   this right; the README does not.
3. **Feature invisibility (P1).** Shipped, documented-in-isolation surfaces are absent
   from the README's own index: `/folia version|workers|scheduler|patches|health|metrics|entities|pin|unpin|gpu`
   (`FoliaCommand.java:138-486` vs `README.md:145-157`), the patch/config layer, and
   `docs/gpu.md` (102 lines, not linked from anywhere in the README).

**The fix is structural:** make `docs/status.md` the single canonical maturity record
(one paragraph + a table of "works / partial / not yet", generated by hand from the
parity ledger), and have README embed only a short summary plus a link, with a
"last verified: <date>, commit <sha>" stamp. Never restate the pipeline in more than
one place.

---

## 6. Documentation completeness matrix

| Artifact | Exists | Assessment |
|---|---|---|
| `README.md` | ✅ 204 lines | Strong prose, wrong status, no install path, no logo/badges, duplicate doc entry |
| `ARCHITECTURE.md` | ✅ 288 | Best content in repo; status + gaps section stale (C2–C4) |
| `REGIONS.md` | ✅ 155 | Clear, operator-friendly; good model for other docs |
| `THREADING.md` | ✅ 410 | Excellent contract doc; leaks `(spec 8)` and `spec 20` |
| `SCHEDULING.md` | ✅ 137 | Still framed as "the plan for partitioning"; describes plan, not shipped pipeline |
| `MIXINS.md` | ✅ 263 | Strong; 3/26 mixins explicitly undocumented (`MIXINS.md:8`) |
| `COMPATIBILITY.md` + `docs/compatibility/` | ✅ 195 + 7 pages | Measured and honest; matrix scoped to pre-staging build (C8); guide content stale (C6); broken link (C7) |
| `TROUBLESHOOTING.md` | ✅ 176 | Good format; scope line stale (C5); covers a subset of shipped messages |
| `TESTING.md` | ✅ 370 | Good intent; wrong commands/counts (C9, C10) |
| `docs/folia-parity.md` | ✅ 131 | **The only accurate status document.** Should be the source of truth |
| `docs/gpu.md` | ✅ 102 | Orphaned — not in README index |
| Docs index / `docs/README.md` | ❌ | No reading order; 8 root docs compete |
| Install / operator quickstart | ❌ | Zero install steps, zero backup warning, zero "server-only" statement |
| Command manual (`docs/commands.md`) | ❌ | README lists 6 of ~20 subcommands; `help` topics exist in-game only |
| Config reference (`docs/configuration.md`) | ❌ | Config is self-documenting at runtime — but invisible until you boot |
| API guide + runnable example (`docs/developer/`) | ❌ | Referenced by `docs/compatibility/README.md:69`; doesn't exist |
| `CONTRIBUTING.md` | ❌ | Build, no-Mockito rule, comment-free convention, mixin 7-question gate, live-boot requirement all undocumented publicly |
| `CODE_OF_CONDUCT.md` | ❌ | |
| `SECURITY.md` | ❌ | Notably absent for a mod that mutates world state off-thread; no private-report channel |
| `CHANGELOG.md` / release notes | ❌ | 55 commits, zero user-facing history |
| `docs/roadmap.md` / `STATUS.md` | ❌ | Roadmap lives inside stale ARCHITECTURE §8 |
| Issue/PR templates | ❌ | No `.github/` at all — Actions is correctly unused, but templates/SECURITY are independent of Actions |
| `NOTICE` / attribution file | ❌ | Attribution only in prose (`ARCHITECTURE.md:189-206`) |
| Branding/naming page | ❌ | Five spellings, no rule |
| Internal handoff separation | ❌ | `FREEBUFF.md` (103 KB) at root, mixed with public docs |

---

## 7. Onboarding friction, by persona

### 7.1 Server operator (highest-volume audience)

Dead end at step 2. They cannot download anything (no tags/releases/Modrinth/CurseForge),
and if they build it, the README's jar path is wrong (C12), there is no install section,
no statement that this is **server-only** (`fabric.mod.json:"environment":"server"` — a
client install does nothing, undocumented), no `/folia` command reference beyond six
subcommands, no config walkthrough before first boot, and no warning that regionized
execution changes where game code runs. The troubleshooting doc is genuinely good but
its scope line tells them only random ticks are regionized (C5), so they will
misdiagnose anything involving entities or players.

Additional operator risks worth surfacing explicitly, because they are documented
internally but not publicly: `gameplay.stage-player-path` default-off changes behavior;
patch toggles exist and are restart-scoped; the GPU subsystem is opt-in and measured
*slower* than CPU (`FREEBUFF.md` 2026-10-09). None of this is in the README.

### 7.2 Fabric mod developer (second audience)

1. Reads README "Modules" → told `fabric` has "(future) Mixins" (C13) and that only
   random ticks are intercepted (C1) → concludes there is no API worth adopting.
2. Follows `docs/compatibility/README.md:69` to `docs/developer/` → 404.
3. Opens `FabricFoliaApi` javadoc → told the accessors throw `IllegalStateException`
   (C14) → stops.
4. Never finds the `"fabricfolia"` entrypoint contract, a sample mod, or a
   `RegionScheduler`/`EntityScheduler` usage example — despite the API being real and
   delivered at `FabricFoliaMod.java:253-270`.

The API is the project's highest-leverage adoption surface and its documentation is
actively discouraging.

### 7.3 Prospective contributor / fellow maintainer

No CONTRIBUTING, so the real rules are invisible: JDK 25, Gradle wrapper, no Mockito,
`fabric` tests boot the real engine, any mixin change requires a live boot + clean
shutdown + process sweep, new code ships comment-free (mandate rule 47), work lands on
`main`, conventional commits, Jenkins is CI and GitHub Actions is deliberately absent
(so a GitHub PR shows *no checks* — an outsider will assume CI is broken). That last
point is a real trust problem: the single most common question after "how do I build
this" is "why is there no CI on my PR?", and the repo answers it nowhere public.

`FREEBUFF.md` compounds this: it is the largest file in the repo, it is written for AI
agents, it contains host paths, an RCON password, uncommitted-work notes and a
"user's #1 pain" line. Publishing it unlabeled reads as a leak and buries the actual
docs.

---

## 8. Licensing, attribution and trademark boundary

What is already correct and should be preserved verbatim in spirit:

- Apache-2.0, `Copyright 2026 Palorder Softworks` (`LICENSE` trailing block).
- Clean-room claim is explicit and consistent in three places: `README.md:202-204`,
  `ARCHITECTURE.md:4 (clean-room...)`, `ARCHITECTURE.md:189-206` with named sources
  (`docs.papermc.io/folia/reference/region-logic`, `/overview`) and a stated rationale
  (GPL-3.0 Folia patches, never read/copied).
- `fabric.mod.json` `license: Apache-2.0`; `fabric/build.gradle.kts:98-102` ships
  `LICENSE` inside the jar (renamed `LICENSE_fabric-folia`).
- Third-party dependency licenses are named in build comments (SnakeYAML Engine
  Apache-2.0, Adventure, JSR-305 compile-only).

What is missing:

1. **No `NOTICE` file.** Apache-2.0 §4(d) only obliges you to propagate an upstream
   NOTICE you received, so this is not a compliance breach — but a short `NOTICE`
   naming the documentation-derived work (PaperMC/Folia region logic, EDF dispatch,
   global-region concept) is the conventional, auditable form of the attribution that
   currently lives only in ARCHITECTURE prose. It also gives downstream packagers one
   file to read.
2. **Attribution is prose-only and buried.** The README's only licensing paragraph is
   the *last* section; ARCHITECTURE §4 is 200 lines in. For a project whose name
   contains "Folia", attribution should be visible in the README header area
   ("Independent clean-room implementation; architectural reference: PaperMC Folia
   documentation. No Folia/Paper source code is used.").
3. **No trademark/affiliation disclaimer** (see §4.1). This is the actual legal-adjacent
   gap: the current text answers "did you copy GPL code?" but not "are you official?".
4. **No dependency license inventory.** The shipped jar nests `api`, `common`,
   SnakeYAML Engine, Adventure API and MiniMessage (`fabric/build.gradle.kts:44-56`).
   A `THIRD-PARTY.md` (name, version, license, bundled y/n) is cheap and is what a
   distributor or a cautious operator looks for.
5. **`fabric.mod.json` lacks `"jars"`** while a build comment asserts the bundled
   libraries are "declared in fabric.mod.json `jars`"
   (`fabric/build.gradle.kts:44-56`). Either Loom generates it at build time or the
   comment is wrong. Unverifiable without a build; flag for the implementer to confirm
   and then either correct the comment or the metadata. (Do not "fix" by guessing.)
6. **No per-file SPDX identifier.** Headers are full Apache blocks or abbreviated
   ("abbreviated headers in this review are intentional", `RegionScheduler.java:6-8`).
   Fine for humans, hostile to license scanners. Adding `SPDX-License-Identifier:
   Apache-2.0` to new files is a zero-risk improvement.

Boundary statement to adopt (single canonical wording, used in README, NOTICE, and
`fabric.mod.json` description):

> Fabric-Folia is an independent, clean-room Apache-2.0 project. It is not affiliated
> with or endorsed by FabricMC, PaperMC, or Mojang/Microsoft. Folia's server patches
> are GPL-3.0 and none of that source is used or consulted; only PaperMC's public
> architecture documentation is referenced, and the attribution is recorded in NOTICE.

---

## 9. Proposed documentation information architecture

Design rules: **one fact, one home**; status is data with a date and a commit; every
public doc has an owner and a "last verified" line; internal process is physically
separated.

```
README.md                     # pitch, proof, install, 3 links deep — no status tables
docs/
  README.md                   # the index: 5 reader paths, one screen
  status.md                   # CANONICAL maturity record (works/partial/not-yet + date+sha)
  roadmap.md                  # next milestones, each with an exit criterion
  commands.md                 # every /folia subcommand, permission, sample output
  configuration.md            # every key, default, effect, restart-vs-live, safety note
  compatibility/
    README.md                 # declaration format + policy (moved, deduped)
    matrix.md                 # measured matrix, keyed to a version + date + config
    <mod>.md                  # unchanged per-mod pages
  developer/
    README.md                 # API entrypoint contract, threading rules, 3 worked examples
    scheduler-recipes.md      # region/entity/global/async: when to use which
    mixin-policy.md           # the 7-question gate, moved from MIXINS.md prose
    testing.md                # moved from TESTING.md; correct commands, 3 modules
  operations/
    install.md                # jar → server dir → first boot → verify → backup
    troubleshooting.md        # moved from TROUBLESHOOTING.md
    metrics.md                # what /folia metrics means, how to read MSPT
  architecture/
    overview.md               # ARCHITECTURE.md (status section removed)
    regions.md  threading.md  scheduling.md  mixins.md  gpu.md
  branding.md                 # name, spelling, logo usage, disclaimers, voice
NOTICE                        # documentation-derived attribution
THIRD-PARTY.md                # bundled dependency license inventory
CONTRIBUTING.md               # build, tests, mixin gate, live-boot rule, PR flow, CI=Jenkins
SECURITY.md                   # private reporting, supported versions, world-safety class
CODE_OF_CONDUCT.md            # Contributor Covenant
CHANGELOG.md                  # Keep a Changelog, keyed to tags
docs/internal/
  AGENTS.md  CLAUDE.md  FREEBUFF.md   # moved; banner: "AI-agent handoff, not user docs"
```

Moves that matter most for perception, in order:
1. `FREEBUFF.md` out of the root (into `docs/internal/`, unlinked from README, banner
   at top). It is currently the largest and most recently modified file a visitor
   sees.
2. `docs/status.md` created and made the *only* place status is stated.
3. Root-level `ARCHITECTURE.md` / `THREADING.md` / etc. moved under `docs/` with
   redirect stubs (or left in place for one release — but then `docs/README.md` must
   be the single index and README must link to it, not list 10 files inline).

---

## 10. Proposed README outline (target ≤ 150 lines)

Ordered by what each audience needs, with the honesty guardrail in the first screen.

1. **Header** — logo (256 px), name, one-sentence description, badges: license ·
   Minecraft 26.2 · Java 25 · latest pre-release tag · Jenkins.
   *Guardrail: no "stable" or "production-ready" language anywhere above the fold.*
2. **What it is** (4 lines) — regionized multithreaded execution as a normal Fabric
   mod, server-side only, clean-room, no custom server distribution.
3. **Maturity banner** (3 lines, boxed) — "**Pre-release (0.1.0-alpha).** Not
   production-ready; back up worlds. Verified against MC 26.2 / Loader 0.19.5 /
   API 0.160.0 on <date>. Full status: docs/status.md."
4. **What works today** (5–7 bullets, each linked to `docs/status.md` row) — regionizer
   + schedulers, staged entity/BE/scheduled-tick execution, player path (gated), patch
   layer, `/folia` diagnostics, measured compat set. One line each, no adjectives.
5. **What does not work yet** (3–4 bullets) — login placement, first-class
   respawn/dimension flows, chunk-lifecycle ownership, redstone/per-region tick state.
   *This section is the trust builder; do not omit it.*
6. **Install** (numbered, copy-pasteable) — download jar from Releases → drop in
   `mods/` → start → confirm ready line → back up before enabling gameplay staging.
7. **Requirements** table — MC 26.2 server, Loader ≥0.19.5, Fabric API
   0.160.0+26.2, Java 25, **server only**.
8. **Configuration** (3 lines + link) — generated at `config/fabric-folia.yml`,
   self-documenting; full reference in `docs/configuration.md`.
9. **Commands** (compact table of the 6 most-used, link to `docs/commands.md`).
10. **Compatibility** (3 lines + matrix link) — "measured, not assumed"; name the six
    measured mods; state the C2ME suppression honestly.
11. **For mod developers** (4 lines + `docs/developer/`) — `"fabricfolia"` entrypoint,
    four schedulers, threading annotations; one 10-line code block.
12. **How it works** (5 lines + `docs/architecture/overview.md`).
13. **Project status & roadmap** (3 lines + `docs/status.md`, `docs/roadmap.md`).
14. **Contributing / Security / License** — one line each; license section keeps the
    Folia attribution **and** the affiliation disclaimer.

---

## 11. Prioritised acceptance criteria

Each is verifiable by reading a file or running one grep — no build required.

### P0 — before this repo is shown to anyone

| ID | Criterion | Verification |
|---|---|---|
| A1 | `README.md` status section is replaced by a summary that agrees with `docs/folia-parity.md` on every subsystem it mentions; no sentence claims a pass is "not intercepted" if the ledger marks it IMPLEMENTED or higher. | diff README claims vs ledger rows; zero contradictions |
| A2 | `ARCHITECTURE.md` line 11 no longer says interception "is not active", and §8's "not implemented" list matches the ledger's NOT_IMPLEMENTED rows exactly. | manual cross-read |
| A3 | `TROUBLESHOOTING.md:6` scope line and `docs/compatibility/README.md:62-64` describe the current staged pipeline. | grep "only intercepted", "not regionized yet" → 0 hits |
| A4 | `docs/status.md` exists, is linked from README, and carries `Last verified: <date> · <commit sha>`. Every other doc that states status links to it instead of restating it. | one canonical status paragraph per subsystem |
| A5 | No public doc says `fabric/build/libs/fabric-0.1.0.jar`; the documented artifact name equals the one Gradle produces (`folia-0.1.0.jar` today, `fabric-folia-*.jar` after the naming decision). | grep `fabric-0.1.0` → 0 hits |
| A6 | A prominent, unambiguous pre-release warning exists (README banner + `fabric.mod.json` description or `status.md`), including a world-backup instruction. | read README first screen |
| A7 | An install section exists: download source → `mods/` → first boot → expected ready line. No step assumes building. | follow it on a clean server dir |
| A8 | Affiliation disclaimer present in README (top or license section), `NOTICE`, and `fabric.mod.json` description: not affiliated with FabricMC, PaperMC/Folia, Mojang. | grep "not affiliated" → 3 files |
| A9 | `NOTICE` exists with the PaperMC/Folia documentation attribution currently only in ARCHITECTURE §4, and ARCHITECTURE §4 points to it. | file exists + cross-link |
| A10 | `CONTRIBUTING.md` exists and states: JDK 25, wrapper, the real three-module test command, no Mockito, mixin 7-question gate, live-boot + clean-shutdown rule, comment-free new code, `main`, conventional commits, **CI is Jenkins and GitHub shows no checks**. | read it |
| A11 | Public docs contain no unreachable `spec section N` / `mandate §N` citations unless the referenced file exists in-repo. | grep 43 current hits → 0 unresolved |

### P1 — first-week maintainability

| ID | Criterion | Verification |
|---|---|---|
| B1 | Docs index (`docs/README.md`) with three reader paths (operator / mod dev / contributor) and no duplicate entries. README's inline 10-item list is replaced by a link. | duplicate `docs/folia-parity.md` entry gone (C15) |
| B2 | `docs/commands.md` documents **all** subcommands in `FoliaCommand.java` (including `version`, `workers`, `scheduler`, `patches`, `health`, `metrics`, `entities`, `pin`, `unpin`, `gpu`, `help <topic>`) with permission and one sample of real output. | command count match |
| B3 | `docs/configuration.md` publishes the full key list with defaults and restart-vs-live semantics; generated file remains the runtime authority. | keys match schema |
| B4 | `docs/developer/` exists: entrypoint contract + 3 runnable scheduler examples; `api/FabricFoliaApi.java` javadoc no longer says the accessors throw (C14); broken link at `docs/compatibility/README.md:69` resolves. | link check + read javadoc |
| B5 | `TESTING.md` commands corrected to the three-module run and test count replaced by "count is generated/not authoritative" or an actual current number; "all tests live in common" removed. | grep |
| B6 | Compatibility matrix re-measured against the **default** staged configuration on a named version/date, or explicitly labeled "measured on the opt-in random-tick slice (pre-staging)". | matrix header states config + version |
| B7 | `SECURITY.md` (private channel, supported versions, note that regionized execution moves game code between threads) and `CODE_OF_CONDUCT.md` exist. | files exist |
| B8 | `CHANGELOG.md` with `Unreleased` + the first tag; `git tag` non-empty for the version the docs cite. | tag exists |
| B9 | `THIRD-PARTY.md` lists every bundled dependency (SnakeYAML Engine, Adventure API, MiniMessage) with license, and confirms/clarifies the `jars` metadata question. | file exists; `fabric.mod.json` `jars` resolved |
| B10 | `fabric.mod.json`: `icon` declared, `fabric-api` floor matches README (`0.160.0+26.2`), `contact.issues` added. | JSON read |
| B11 | Logo shipped at 128/256 px in `assets/fabricfolia/` and shown in README; jar contains only the small icon. | jar size / README render |
| B12 | `FREEBUFF.md`, `AGENTS.md`, `CLAUDE.md` moved to `docs/internal/` with an "AI-agent handoff — not user documentation" banner, and removed from the README/nav. | root file list |

### P2 — polish that compounds

| ID | Criterion |
|---|---|
| C1 | `docs/branding.md` fixes one spelling (`Fabric-Folia`), one artifact name, logo usage rules and the disclaimer wording. |
| C2 | Issue templates (bug with `/folia metrics` + `status` attached, compatibility report, feature request) and a PR template that asks "did you run the three-module suite and a live boot?". |
| C3 | `docs/roadmap.md` with milestone exit criteria (login placement, respawn/dimension, chunk lifecycle) — moved out of ARCHITECTURE §8. |
| C4 | `docs/operations/metrics.md` explains MSPT p50/p95/p99, phase rings and the vanilla watchdog misconception (already root-caused internally; it is the single most useful operator doc that could exist). |
| C5 | `MIXINS.md`: document the last 3 mixins; reconcile the 26-vs-24 count with `FREEBUFF.md`. |
| C6 | First-run experience: a `/folia health` sample output in `docs/status.md` so "is it working?" has a visual answer. |
| C7 | `SPDX-License-Identifier` on new source files. |
| C8 | `SCHEDULING.md` retitled/rewritten from "the plan for partitioning" to "how the shipped staging model partitions", or explicitly marked as the design record for a superseded plan. |

---

## 12. Sequencing recommendation

1. **One hour, one person:** fix A5 (wrong jar name), A3 (two stale scope lines),
   A8/A9 (disclaimer + NOTICE), A6 (warning banner). These are pure trust repairs.
2. **Half day:** A1/A2/A4 — write `docs/status.md` *from* `docs/folia-parity.md`, then
   rewrite the two status sections to point at it. Do not attempt to update README
   feature-by-feature before the canonical status exists.
3. **One day:** A7 (install), A10 (CONTRIBUTING), B2 (commands), B4 (developer docs) —
   the adoption path.
4. **After the first tag:** B6 (re-measure matrix on the default config) and C8
   (scheduling doc reframe). Both require a build/boot, which this audit did not do.

Explicitly **not** recommended: a docs-site generator, a full rewrite of ARCHITECTURE,
or renaming the project. The content is better than most projects at this stage; the
problems are drift, missing entry points, and one naming decision.

---

## 13. Limitations of this audit

- No build, boot, test run or RCON session was performed (per instruction). All
  "current behavior" claims are taken from the repository's own documents; where those
  documents disagree, the parity ledger was treated as the most likely current state
  because it is the most recently updated and the most specific — not because it was
  independently executed.
- The `fabric.mod.json` `"jars"` question (whether Loom injects nested-jar metadata)
  could not be settled without a build and is flagged, not asserted.
- The test counts (22 common / 19 fabric files) are file counts, not test-case counts;
  they are used only to refute "all tests live in common".
- Maturity judgments (alpha vs beta) are perceptual claims, not measurements.
- Jenkins reachability was not checked; the badge URL and self-hosted instance are
  taken as documented.
