# Optimization mod version research snapshot

Checked 2026-11-22. Modrinth metadata indicates these projects publish or declare Minecraft 26.3 support, but metadata alone does not prove they work with Fabric-Folia.

| Mod | 26.3 evidence/version observed | Maturity note |
|---|---|---|
| Lithium | 0.26.3 for Fabric, version slug mc26.3-0.26.3-fabric | Published release; release notes include correctness fixes. |
| C2ME | 0.4.2-alpha.0.89+26.3 | Alpha/dev build; latest listing observed updated 2 days ago. Do not treat as stable. |
| ScalableLux | 0.3.0-alpha.0.6+26.3 | Alpha/dev build. |
| Krypton | 0.3.2 | Published release for 26.3; author states best-effort support and no guarantees of stability/compatibility. |
| VMP | Declares 26.3 support | Project description explicitly says early development and expects breakage. Exact 26.3 version still needs verification. |
| FerriteCore | Declares 26.3 support | Exact Fabric version still needs verification from the version API/listing. |

Sources:
- Lithium 0.26.3: https://modrinth.com/mod/lithium/version/fWv5bnZk
- C2ME 0.4.2-alpha.0.89+26.3: https://modrinth.com/mod/c2me-fabric/version/FXjQDzq7
- ScalableLux 0.3.0-alpha.0.6+26.3: https://modrinth.com/mod/scalablelux/version/0.3.0-alpha.0.6%2B26.3
- Krypton 0.3.2: https://modrinth.com/mod/krypton/version/UugdIYJw
- VMP project page: https://modrinth.com/mod/vmp-fabric
- FerriteCore project page: https://modrinth.com/mod/ferrite-core

Testing implication: separate the compatibility matrix into stable/release mods and alpha/devbuilds. For each exact artifact, record loader/game versions, checksum, mixin export result, clean startup, gameplay coverage and soak. Test one mod at a time before combinations. Prioritize C2ME and ScalableLux because they touch chunk IO/world generation and lighting; VMP and Lithium affect entity tracking and gameplay hot paths; Krypton affects networking; FerriteCore is primarily memory optimization but still needs startup/gameplay validation.

Do not state a project is compatible merely because its Modrinth page lists Minecraft 26.3.