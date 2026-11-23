# Compatibility

## Pinned build target

| Component | Constraint |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 or newer |
| Fabric API | Built against 0.161.0+26.2; metadata minimum `>=0.160.0+26.2` |
| Java | 25 or newer |

These are the build and metadata constraints in `gradle.properties` and `fabric/src/main/resources/fabric.mod.json`. They are not a claim that every version combination, modpack, or workload has passed a soak test.

The mod is server-side only (`"environment": "server"`). It is not a Bukkit/Paper plugin, does not provide the Folia plugin API, and does not make Bukkit plugins work on Fabric.

## What compatibility means here

Fabric-Folia ships with gameplay staging disabled. In the default profile the engine, region ownership services, diagnostics and API are initialized, but entity (including item entities), block-entity, scheduled-tick, random-tick and player-physics bodies run on vanilla's server thread. Compatibility risk rises only when an operator enables one of the staged paths.

| Profile | Execution model | Evidence status |
|---|---|---|
| Default (`general.enabled: true`, gameplay staging off) | Vanilla gameplay execution plus engine diagnostics and safe optimization patches | Automated tests only; live active-player verification still required |
| `general.regionized-gameplay: true` with entity/chunk patch gates enabled | Selected entity, block-entity and scheduled-tick bodies staged to region workers | One 2026-11-16 startup-only smoke with the master gate enabled; no active-player gameplay or matrix |
| `general.regionized-random-ticks: true` | Random-tick and precipitation bodies staged | Scoped investigations only |
| `gameplay.stage-player-path: true` | Player connection tick and packet handling re-homed | Enabled during the 2026-11-16 smoke boot with no players connected; otherwise unvalidated |

## Verified scope versus undeclared mods

There are two different evidence classes in this repository, and they must not be merged:

1. **Project-scoped investigations.** `docs/compatibility/` contains per-mod notes for C2ME, FerriteCore, Krypton, Lithium, ScalableLux and VMP. Each note is evidence for the exact versions and scenario it names. It is not a current certification of Fabric-Folia, and it does not extend to a newer version of that mod.
2. **Undeclared mods.** Any mod not named in those notes is undeclared. Fabric-Folia has no per-mod opt-in and no per-entity-type policy. When regionized gameplay is enabled, every installed mod's entity and block-entity tick code is staged, including code that may assume it always runs on the server thread.

No current release matrix exists for the default-off gameplay profile or for any full modpack. Do not describe compatibility as "verified" for a mod or modpack unless the exact combination has been tested for the exact commit and versions in use.

### Live smoke evidence

On 2026-11-16 a candidate artifact was deployed to a Pelican-hosted Fabric 26.2 server running Fabric API 0.161.0+26.2 and restarted. The server reached `Done (` and `Fabric Folia is ready`; the existing server config had `general.regionized-gameplay` and `gameplay.stage-player-path` enabled. No players were active during the check, so entity, block-entity, scheduled-tick and player paths were not exercised. The startup compatibility scan reported 248 undeclared mods, zero declared supported mods and zero incompatible classifications. That is a scan result, not a compatibility matrix, and it does not make those 248 mods supported.

## Known runtime handling

| Interaction | Behavior | Limit |
|---|---|---|
| C2ME detected | If `general.regionized-random-ticks` is also enabled, the random-tick slice is suppressed for the session and vanilla random ticks run instead | Suppression is not a compatibility claim for C2ME's other hooks |
| Lithium detected | `patches.minecraft.entity-collision-optimization` is force-disabled; vanilla or Lithium collision behavior applies | Other staged paths are unaffected and untested with Lithium |

Fabric Loader enforces the metadata minimum for Fabric API before startup, but a newer API line can still change hook behavior. The C2ME and Lithium handling above is deliberate fail-safe behavior, not evidence that the surrounding combination is safe. Check the startup log and `/folia status` for the effective state.

## Risk classes for undeclared mods

Audit for these before enabling regionized gameplay:

- Code that assumes `MinecraftServer.isSameThread()` or a specific thread name.
- Static mutable caches, `ThreadLocal` state, or lazy initialization not designed for concurrent region workers.
- Direct `PlayerList`, scoreboard, advancement, or global server-state mutation from entity or block-entity tick code.
- Synchronous chunk loading or unloaded-chunk access from tick code.
- Custom schedulers, executors, or `synchronized` waits that can block a region worker.
- Mixins or event handlers that replace the same vanilla instruction sequence Fabric-Folia targets.

Region ownership checks (`threads.thread-check-mode`) can expose some violations in a disposable world. They cannot prove a third-party mod is thread-safe, and `OFF` hides ownership bugs rather than fixing them.

## Not implemented or not region-safe

- Player login placement.
- Portal search and creation on the origin side; only the teleport transition is routed.
- Explosion block and entity damage across region boundaries. Explosions triggered from a staged body execute in that body's region context.
- Direct `PlayerList.respawn` callers from other mods and destination-region placement.
- Per-region redstone time, per-region chunk state ownership, and independent per-region world ticks.
- Region-aware redstone, hopper and other multi-block contraption semantics that span regions. Only the vanilla containers that remain server-thread-owned are serialized.

## Testing a modpack

1. Back up the world and test on a disposable copy.
2. Boot with all staged paths disabled and confirm the server reaches `Done (` with no mixin errors.
3. Run `/folia health`, `/folia status`, `/folia compat` and record the versions.
4. Enable one staged path at a time, starting with `general.regionized-gameplay`.
5. Exercise entity-heavy areas, chunk load/unload, teleport, respawn, portals, explosions, redstone, save and restart.
6. Record TPS from `time query gametime` deltas rather than log warning cadence.
7. Keep player connection staging off unless the player path has been tested separately.

Removing or updating any mod, Minecraft, Fabric Loader, or Fabric API version invalidates the result.
