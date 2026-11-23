You are the protocol-client and benchmark-driver research specialist for Fabric-Folia.

Work only in /tmp/ff-reconcile. Do not edit production code and do not install packages. Read tools/freebuff/reports/qa-harness.md and tools/freebuff/reports/fabrolia-stand-reference.md.

Research a real protocol-client driver for Minecraft 26.2 and 26.3, with emphasis on native protocol 777 for 26.3. Compare:
- Upstream PrismarineJS Mineflayer and node-minecraft-protocol release support.
- Current 26.3 forks such as @wp2508/mineflayer.
- Rust headless client mcclient.
- Go protocol/client libraries such as go-mclib.
- MCProtocolLib or other maintained Java clients.
- ViaProxy/ViaVersion translation as a fallback, clearly not equivalent to native protocol validation.

Use current repository metadata, release notes and official/project sources. For every candidate record exact version/commit, protocol versions, license, maintenance activity, offline-mode support, multi-client capability, login/configuration/keepalive/chunk/movement/teleport/block interaction/inventory/mount/disconnect/respawn/cross-dimension coverage, and known gaps. Do not trust a README claim without a reproducible protocol smoke test plan.

Recommend the safest driver for a 40-client stand, including process model, unique offline identities, rate control, per-client deterministic scenarios, packet/event logs, and how to detect login stalls/disconnects/teleport corrections. Separate protocol-native testing from Via translation. Do not select a third-party fork as production tooling without license/maintenance review.

Create tools/freebuff/reports/protocol-client-research.md. No code, no installs, no server boot, no network access to the user's production servers, no commit/push. Stop after the research report for manager review.
