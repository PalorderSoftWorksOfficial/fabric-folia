# Official Fabric toolchain snapshot

Checked 2026-11-22 against the official Fabric developer page: https://fabricmc.net/develop/

Current recommended values shown for Minecraft 26.3:
- minecraft_version=26.3
- loader_version=0.20.1
- loom_version=1.19-SNAPSHOT
- fabric_api_version=0.166.0+26.3

The official Minecraft 26.3 release/porting article is https://fabricmc.net/2026/09/15/263.html. At release time it recommended Loom 1.17 and Gradle 9.6.0 with Loader 0.19.5, but those are historical release-day values. Prefer the current official developer page above and verify Gradle requirements for the selected Loom release before updating the wrapper.

Official Loom documentation: https://docs.fabricmc.net/develop/loom/. For Minecraft 26.1 and newer, Loom uses plugin ID net.fabricmc.fabric-loom for non-obfuscated versions. Verify the project's existing plugin DSL and runtime naming model against this before migration.

The repository currently documents Minecraft 26.2 and Loader 0.19.3, so the 26.3 move is a toolchain and vanilla API/mixin port, not just changing gradle.properties. The official 26.3 porting article lists API removals including CompostingChanceRegistry, FuelRegistry, FabricPotionBrewingBuilder, StrippableBlockRegistry, TillableBlockRegistry and FlattenableBlockRegistry; audit code and dependent mods for these changes.

Do not blindly upgrade the toolchain during the current dirty-tree reconciliation. First preserve and integrate the four pending upstream commits, then migrate in a dedicated tested change.