# Fabrolia production branch reference

Checked 2026-11-22 through the GitHub repository connector. This is the reference that best matches the request to study Fabrolia's production implementation.

## prod/current ref

The repository ref prod/current resolves to:
- version=0.2.38+alpha-26.3
- Minecraft 26.3
- Java 25
- Fabric Loader 0.19.5
- Fabric API 0.161.0+26.3
- Lithium mc26.3-0.26.2
- VMP 0.2.0+beta.7.238+26.3

The README for this ref says it is the fork running one approximately 35-player production server and states its goal as vanilla behavior at 20 TPS on a busy server. It documents testing with C2ME, Lithium, VMP, Carpet, ServerCore, ScalableLux, FerriteCore, MCFC and BlueMap. It explicitly declares incompatibilities with Moonrise, Open Parties and Claims, and C2ME versions older than 0.4.2-alpha.0.88.

Its AGENTS.md describes:
- Around 1,400 tests, including about 57 source/bytecode wiring tests.
- A 40-real-client server stand with verify and performance modes.
- Spark profile comparison and JFR/thread-level diagnostics.
- Full modpack mixin-export verification.
- A fail-stop policy for impossible/stuck states.
- Vanilla parity requirements for farms, redstone and datapacks.
- Explicit region ownership, island claims, global lane and per-island logical clocks.

## Compare with f_3.2.6

The newest published release tag f_3.2.6 is newer but its artifact is named fabrolia-alpha_3.2.6.jar. Its current AGENTS.md describes around 1,600 tests and more recent player-lifecycle/connection work, but also documents unresolved routed-player self-teleport/removal server-stop behavior.

Use prod/current as the production architecture and operational reference; use f_3.2.6 as a newer development/reference line. Do not conflate either with a formally stable non-alpha release.

## Source links

- Production ref README: https://github.com/DUzzL/fabrolia/blob/prod/current/README.md
- Production ref agent guide: https://github.com/DUzzL/fabrolia/blob/prod/current/AGENTS.md
- Production ref RegionScheduler: https://github.com/DUzzL/fabrolia/blob/prod/current/common/src/main/java/com/axalotl/fabrolia/common/regions/RegionScheduler.java
- Production ref IslandTickUnit: https://github.com/DUzzL/fabrolia/blob/prod/current/common/src/main/java/com/axalotl/fabrolia/common/regions/IslandTickUnit.java
- Newer release: https://github.com/DUzzL/fabrolia/releases/tag/f_3.2.6

## Licensing boundary

The production ref is GPL-3.0, like the rest of Fabrolia. Fabric-Folia is Apache-2.0. This note is a conceptual and operational comparison only. Do not copy, port, translate, or derive implementation code from either Fabrolia ref.
