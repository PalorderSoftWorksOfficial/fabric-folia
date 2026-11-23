# Exact Modrinth 26.2 Fabric artifacts resolved

The compatibility agent's resolver produced /tmp/compat-manifest.json on 2026-11-22. It selected these exact Modrinth versions tagged for Minecraft 26.2 + Fabric and downloaded their primary JARs. SHA-512 prefixes below are identifiers only; use the full values in the JSON manifest when pinning.

| Mod | Exact version | JAR |
|---|---|---|
| Lithium | mc26.2-0.25.3-fabric | lithium-fabric-0.25.3+mc26.2.jar |
| C2ME | 0.4.2-alpha.0.71+26.2 | c2me-fabric-mc26.2-0.4.2-alpha.0.71.jar |
| FerriteCore | 9.0.0-fabric | ferritecore-9.0.0-fabric.jar |
| Krypton | 0.3.1 | krypton-0.3.1.jar |
| VMP | 0.2.0+beta.7.236+26.2 | vmp-fabric-mc26.2-0.2.0+beta.7.236-all.jar |
| ScalableLux | 0.3.0-alpha.0.3+26.2 | ScalableLux-fabric-0.3.0-alpha.0.3-all.jar |
| Fabric Carpet | 26.2+v260616 | fabric-carpet-26.2+v260616.jar |
| Carpet TIS Addition | v1.83.3-mc26.2 | carpet-tis-addition-v1.83.3-mc26.2.jar |

These are candidate inputs for a 26.2 mixin-export/startup/gameplay matrix, not a compatibility result. C2ME, VMP and ScalableLux versions are alpha/beta builds. Preserve the manifest's full hashes, version IDs, dependency declarations and URLs alongside every test artifact.

The current project target is 26.2. Create a separate 26.3 manifest after the toolchain migration; do not silently replace this baseline.
