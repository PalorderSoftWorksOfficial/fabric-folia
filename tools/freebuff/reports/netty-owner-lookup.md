# Packet-path ownership lookup audit

Date: 2026-11-22. Read-only source inspection of the original dirty checkout. No code changed.

## Finding: packet re-home can block a Netty event loop on the regionizer structure lock

Severity: P1 latency/liveness risk under topology churn. A deadlock is not yet proven; the event-loop blocking path is directly visible in source.

### Call path A

1. Vanilla PacketUtils.ensureRunningOnSameThread re-homes tick-sensitive packets through PacketProcessor.scheduleIfPossible.
2. PacketProcessorMixin.fabricfolia$routeRehomeToOwner injects at the head of scheduleIfPossible and calls RegionPlayerRouting.schedulePacketToRegion.
3. RegionPlayerRouting.schedulePacketToRegion resolves the region with regionizer.ownerOfChunk(pos.x(), pos.z()).
4. WorldRegionizer.ownerOfChunk at common/src/main/java/com/palordersoftworks/fabricfolia/region/WorldRegionizer.java:619-626 takes structureLock.lock().

### Call path B

1. PacketUtilsMixin.fabricfolia$ownerRegionPassesCheck redirects PacketProcessor.isSameThread inside PacketUtils.ensureRunningOnSameThread.
2. The redirect calls RegionPlayerRouting.isOwnerRegion.
3. isOwnerRegion resolves the player's owner using regionizer.ownerOfChunk, which takes the same structureLock.

The PacketUtilsMixin comment claims this is a lock-free owner lookup. The current implementation is not lock-free: WorldRegionizer.ownerOfChunk explicitly acquires structureLock.

### Impact

These methods execute on the packet handler's current thread. Vanilla's re-home path can be entered from a Netty event loop. During addChunk, removeChunk, split, merge or section reclamation, the structure lock can be held while topology changes are applied. A packet event loop can therefore block behind world topology work. This can increase packet latency, stall a player's channel, and under sustained contention delay all channels assigned to that event loop.

This is not evidence of a server deadlock by itself. The risk is a blocking event-loop dependency on a structural lock that is also used by tick/topology operations.

### Root-cause options

Preferred minimal safe option:
- Add a nonblocking owner lookup for packet-routing callers (e.g. tryOwnerOfChunk using structureLock.tryLock() and an explicit BUSY result).
- If the structure lock is busy, do not wait on the event loop: leave the packet in vanilla's server-thread processor queue. schedulePacketToRegion already has a false/fallback path; isOwnerRegion can answer false so vanilla re-homes.
- Distinguish BUSY from genuinely unowned so metrics can count the fallback reason.
- Correct the misleading lock-free comment.

Alternative:
- Publish an immutable/volatile ownership snapshot atomically after each structural transaction, and make packet-routing reads consult only that snapshot. This avoids fallback under contention but requires careful split/merge epoch publication and a memory/lookup-cost analysis.

Do not use tryLock and then guess an owner. BUSY must mean fallback, not an arbitrary region.

### Regression test

Add a deterministic test that holds the regionizer structural lock while a separate thread invokes the packet-path owner lookup. Assert that the packet-path call returns the explicit BUSY/fallback result before the test releases the lock. Assert that the caller never waits for the lock and that the diagnostic counter increments once. Add a companion test that, without contention, returns the correct world-qualified owner.

The test should target the new nonblocking API rather than relying on timing a real Netty event loop. A runtime follow-up should inject topology churn while protocol clients send packet traffic and record event-loop lag/packet latency.

### Exact references

- fabric/src/main/java/com/palordersoftworks/fabricfolia/mixin/PacketProcessorMixin.java:45-50
- fabric/src/main/java/com/palordersoftworks/fabricfolia/mixin/PacketUtilsMixin.java:43-55
- fabric/src/main/java/com/palordersoftworks/fabricfolia/engine/RegionPlayerRouting.java:283-327 (schedulePacketToRegion)
- fabric/src/main/java/com/palordersoftworks/fabricfolia/engine/RegionPlayerRouting.java:345-376 (isOwnerRegion)
- common/src/main/java/com/palordersoftworks/fabricfolia/region/WorldRegionizer.java:619-626 (ownerOfChunk locks)
