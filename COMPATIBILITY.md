# Compatibility

## Pinned build target

| Component | Build constraint |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 or newer |
| Fabric API | 0.161.0+26.2 compile target; metadata minimum `>=0.160.0+26.2` |
| Java | 25 or newer |

These are build metadata constraints, not a claim that every listed combination or modpack has passed a production soak.

## Mod compatibility

Regionized gameplay and player connection staging are off by default. They move selected work away from the vanilla server thread. Mods that assume server-thread execution, share mutable static state, or access world objects from event callbacks may fail under those modes.

The docs/compatibility directory contains project-specific investigations. Treat each as scoped evidence for the tested versions and scenario, not a blanket compatibility guarantee. Re-test the exact server modpack after any Fabric-Folia, Minecraft, Loader, or Fabric API update.

## Not yet certified

There is no complete compatibility matrix or long-running production soak for the current release candidate. Do not claim Folia plugin compatibility. Bukkit/Paper plugins are not supported merely because Fabric-Folia uses region terminology.
