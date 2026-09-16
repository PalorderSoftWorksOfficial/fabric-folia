# Fabric Folia — Mixin Register

Every mixin Fabric Folia ships, why it exists, and the mandatory analysis
that gates any future one. Fabric Folia is deliberately mixin-minimal: the
intercept seam is a single call site, and everything else is done through
Fabric API events (lifecycle, commands).

## Current inventory (1 mixin)

| Field | Value |
|---|---|
| Class | `fabricfolia.mixins.json` → `ServerChunkCacheTickMixin` |
| Target | `ServerChunkCache.tickChunks(ProfilerFiller,long)` |
| Transformation | `@Redirect` of the single `ChunkMap.forEachBlockTickingChunk(Consumer)` invocation |
| Effect when armed | vanilla's per-chunk enumeration feeds a collecting consumer; work executes on region workers |
| Effect when disarmed | forwards to vanilla's own consumer unchanged (bytecode-identical behavior) |
| Why a redirect (not inject) | the call site's consumer is the entire per-chunk work handoff; a redirect is total and reversible, an inject could not suppress vanilla's inline execution |
| Conditional | checks `FabricFoliaMod.interceptorOrNull()`; null → call the original target |

## The seven questions (answered for the current mixin)

1. **Why is the mixin required?** Vanilla's random-tick work is inlined at
   this exact call site inside `tickChunks`; there is no event, extension
   point, or Fabric API hook anywhere on this path. Without interception
   there is no way to move per-chunk random ticks off the server thread.
2. **What Minecraft method/class does it modify?**
   `net.minecraft.server.level.ServerChunkCache.tickChunks(ProfilerFiller,long)`
   — one instruction site, verified by javap against the 26.2 jar (the only
   `forEachBlockTickingChunk` call in the game).
3. **Does Lithium modify the same target?** No. Lithium optimizes method
   *bodies* elsewhere (collision, mob spawning lookups, `tickChunk`
   internals). No lithium mixin targets `ServerChunkCache.tickChunks` or
   `ChunkMap.forEachBlockTickingChunk`.
4. **Does C2ME modify the same target?** Not the dispatch site itself —
   and the measured interaction is now **root-caused elsewhere**: C2ME's
   `fixes-worldgen-threading-issues` module redirects `Level`'s random
   initialization to a thread-ownership-checked random (`MixinWorld` →
   `CheckedThreadLocalRandom`), which rejects the worker-thread tick body's
   reads of the level's shared random. That is a data-ownership conflict
   one level below the dispatch redirect, not a bytecode collision at this
   site — see COMPATIBILITY.md and docs/compatibility/c2me.md for the full
   mechanism and the resolved behavior (automatic slice suppression).
5. **Does another major optimization mod modify the same target?** No —
   measured: FerriteCore (memory layouts), Krypton (networking), VMP
   (thread-priority/tick-rate plumbing), ScalableLux (lighting engine) all
   ran the full protocol green against this mixin.
6. **Can the transformation use a safer extension point instead?** No —
   searched; none exists on this path (that is why this is the only mixin).
7. **Can it be isolated behind a compatibility layer?** It already is:
   the entire behavior sits behind `general.regionized-random-ticks` and a
   null-check fall-through; disabling is live and total.

## Rules for any future mixin

1. Answer all seven questions in this file *before* the mixin lands; a mixin
   without an entry here is a review-reject.
2. Grep the current published mixin configs of Lithium, C2ME, VMP, and
   ScalableLux for the target class; if a conflict is plausible, design the
   seam elsewhere or gate it off by default.
3. Never resolve conflicts by forcing `@Priority` without a documented
   technical reason written here.
4. Every mixin must have a disarm path (config flag or null fall-through)
   and a harness combo that proves both the armed and disarmed behavior.
5. One behavior per mixin; if a target needs two changes, they are two
   entries and two flags.

## Known interaction: C2ME

Root-caused and resolved by startup policy (automatic slice suppression
with an actionable warning) — the mechanism is C2ME's random-owner guard
vs. the worker tick body, not a mixin collision at our dispatch site. See
COMPATIBILITY.md's C2ME section and docs/compatibility/c2me.md. The real
fix (a region-confined random source on the worker path) belongs to the
intercept layer — and is itself mixin-constrained: Lithium compiles its
own random-tick loop into `ServerLevel`, so `tickChunk`-internal redirects
collide with Lithium and must not be attempted naively.
