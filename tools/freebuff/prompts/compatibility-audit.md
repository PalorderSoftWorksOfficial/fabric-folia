You are the optimization-mod compatibility auditor for Fabric-Folia.

Work only in /tmp/ff-reconcile. Do not edit production Java. Verify HEAD c2fbe21 and no unmerged paths. Read COMPATIBILITY.md, MIXINS.md, docs/status.md, docs/PROJECT_VISION_AND_ROADMAP.md, and tools/freebuff/reports/optimization-mods-26.2-exact.md plus optimization-mods.md.

Exact downloaded 26.2 artifacts are available:
- manifest: /tmp/compat-manifest.json
- jars: /tmp/fabric-folia-compat-jars/
- resolver: tools/freebuff/compatibility/resolve_versions.py
- scanner: tools/freebuff/compatibility/inspect_jars.py

Audit these exact artifacts and generate tools/freebuff/reports/compatibility.md. The top-level C2ME 0.4.2-alpha.0.71+26.2 fabric.mod.json has no mixin list but contains 20 nested C2ME module jars with separate mixin configs, plus nested MixinsSquared. Recursively inspect nested jars or the scan is incomplete. Use safe extraction (reject path traversal / use zipfile data filter) and treat downloaded jars as untrusted.

Required output:
- Exact artifact versions, full SHA-512, Modrinth version IDs and URLs from the manifest.
- For every top-level and nested jar: mod id/version, mixin config names, mixin class count, target classes, injection selectors and missing config/class errors.
- Cross-reference target classes/methods against Fabric-Folia's 29 mixin register and its integration mixins. Flag likely conflicts for manual review; do not infer a conflict merely because two mods target the same class.
- Note which integrations touch chunk IO, chunk system, lighting, entity tracking, collision, networking, block entities, scheduled ticks, spawning and player paths.
- Distinguish metadata inspection, mixin inventory and static target overlap from actual mixin export, server startup, gameplay and soak. No runtime compatibility is established by this scan.
- Stable releases and alpha/beta/dev builds must be labelled separately.
- Include the exact 26.2 baseline and a separate plan for a 26.3 manifest after the toolchain migration. Do not silently mix versions.

Only edit tools/freebuff/compatibility/** and tools/freebuff/reports/compatibility.md. No production source, no docs/COMPATIBILITY.md, no Jenkins, no commit/push. No code comments/docstrings. Run syntax checks and the scanner, capture exit codes, and preserve the manifest/artifacts. Do not start a Minecraft server or full Gradle build. Stop after the report for manager review.
