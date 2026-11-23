You are the optimization-mod compatibility report writer.

Work only in /tmp/ff-reconcile. Do not edit production code. Do not rerun the scanner unless the existing JSON is invalid. The recursive scan is complete at tools/freebuff/compatibility/scan-26.2.json.

Read:
- tools/freebuff/compatibility/scan-26.2.json
- /tmp/compat-manifest.json
- COMPATIBILITY.md and MIXINS.md
- tools/freebuff/reports/fabrolia-production-reference.md
- tools/freebuff/reports/fabrolia-prod-async-islands.md

Generate tools/freebuff/reports/compatibility.md with:
1. Exact artifact name/version, Modrinth version ID, source URL and SHA-512 for all 8 top-level 26.2 mods.
2. All 40 top-level/nested JAR containers, nested module IDs/versions, mixin config names and class counts.
3. Totals: 1,411 mixin classes, 1,354 server-applicable classes, 0 missing mixin classes, 0 static scan errors. Verify these counts from JSON, do not hardcode without checking.
4. Compare target classes and injection selectors with Fabric-Folia's actual declared 29 mixin classes and integration mixins. List exact overlapping target classes/methods and risk categories; distinguish same-target overlap from a proven conflict.
5. Identify C2ME 0.4.2-alpha.0.71+26.2 as below prod/current Fabrolia's declared minimum 0.4.2-alpha.0.88. Do not claim it is supported.
6. Keep the 26.2 matrix separate from 26.3. Include the two 26.3 toolchain baselines: Fabrolia prod/current Loader0.19.5/API0.161.0 and current official Fabric Loader0.20.1/API0.166.0/Loom1.19-SNAPSHOT. State these still need their own exact artifacts and scan.
7. State evidence limits: metadata and static mixin inventory only; no Mixin export, server boot, gameplay, soak or performance compatibility test has run.

Do not invent Modrinth IDs, hashes or links. If a manifest field is missing, state it. No code comments, no source edits, no commit/push. Write the report and stop for manager review.
