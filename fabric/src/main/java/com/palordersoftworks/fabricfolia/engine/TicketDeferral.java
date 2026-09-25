/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import net.minecraft.server.level.TicketType;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Metrics and the per-world server-thread drain loop for the worker-context
 * chunk-ticket deferral ({@code ChunkTicketMixin}). Found by a production
 * crash (Palorder Central, 2026-09-25): a region worker's portal /
 * ender-pearl placement mutated the ticket set while the server thread's
 * {@code DistanceManager.forEachEntityTickingChunk} enumerated the simulation
 * tracker's {@code Long2ByteOpenHashMap} — fastutil rehashed mid-iteration and
 * the iterator died with {@code "this.wrapped" is null} (NPE), crashing the
 * world tick. Vanilla never writes tickets off the server thread, so its
 * structures are plain maps — the same disease the broadcast-set deferral
 * fixed for {@code chunkHoldersToBroadcast}, one organ over.
 *
 * <p><strong>Fix shape (single ownership, no locks):</strong> the mixin
 * intercepts worker-context {@code ServerChunkCache.addTicketWithRadius} /
 * {@code addTicket} calls and hands them here; each world's server-thread
 * tick replays its own placements at HEAD of {@code ServerChunkCache.tick} —
 * before {@code runAllUpdates}/{@code forEachEntityTickingChunk} can
 * enumerate — so the tracker maps are only ever mutated on the server thread.
 * The pending map is keyed by (world, chunk, ticket type): a placement is
 * replayed by the drain of exactly the world it belongs to, never by a
 * foreign level's tick, and the replay carries the placement's own
 * {@code TicketType} reference (26.2 types are immutable global records with
 * no name registry — holding the reference IS the identity). Suppression
 * (C2ME compat) is re-checked at replay time, so a suppressed session drops
 * its pending tickets rather than applying them.</p>
 *
 * <p><strong>Freshness over FIFO:</strong> a later duplicate placement
 * <em>overwrites</em> the earlier one instead of queuing behind it. Vanilla's
 * own ticket add is idempotent for the same (type, level) pair, and the drain
 * runs every tick (≤50ms) — so collapsing is always behaviorally equivalent
 * for this session and prevents unbounded growth under a portal/pearl storm.</p>
 */
public final class TicketDeferral {

	private record Key(String world, long chunk, TicketType type) {
	}

	/**
	 * One deferred ticket placement, to be replayed verbatim. {@code radius}
	 * &ge; 0 means "replay as {@code addTicketWithRadius}"; otherwise
	 * {@code ticketLevel} replays as a raw-level {@code addTicket}.
	 */
	public record Placement(String world, long packedChunk, TicketType type,
	                        int radius, int ticketLevel) {
	}

	private static final Map<Key, Placement> PENDING = new ConcurrentHashMap<>();
	private static final AtomicLong DEFERRED = new AtomicLong();
	private static final AtomicLong DRAINED = new AtomicLong();
	private static final AtomicLong DRAINED_BATCHES = new AtomicLong();

	private TicketDeferral() {
	}

	/**
	 * Defers one ticket placement from a worker context. The placement is
	 * replayed by the owning world's server-thread tick within one tick
	 * (HEAD of that level's {@code ServerChunkCache.tick}).
	 */
	public static void defer(Placement placement) {
		PENDING.put(new Key(placement.world(), placement.packedChunk(),
				placement.type()), placement);
		DEFERRED.incrementAndGet();
	}

	/**
	 * Replays {@code worldKey}'s deferred placements into vanilla, on the
	 * server thread. Called from that world's {@code ServerChunkCache.tick}
	 * HEAD by the capture mixin. Placements for other worlds are left for
	 * their own level's tick. When {@code suppressionActive} supplies true
	 * (C2ME worker-tick suppression), ALL pending placements are dropped
	 * instead of applied — an unsound replay must never be rescued by the
	 * drain.
	 *
	 * @param apply             replays one placement into vanilla
	 * @param suppressionActive supplies true when worker world-tick work is
	 *                          suppressed and deferred placements must be dropped
	 */
	public static void drain(String worldKey, Consumer<Placement> apply,
			BooleanSupplier suppressionActive) {
		if (PENDING.isEmpty()) {
			return;
		}
		if (suppressionActive.getAsBoolean()) {
			// Fresh pending entries may still arrive after this point (the
			// capture checks the same suppression first, so races produce at
			// most one extra batch next tick — which this branch drops again).
			PENDING.clear();
			return;
		}
		int n = 0;
		Iterator<Map.Entry<Key, Placement>> it = PENDING.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Key, Placement> e = it.next();
			if (!e.getKey().world().equals(worldKey)) {
				continue; // another world's placement: its own tick replays it
			}
			it.remove();
			apply.accept(e.getValue());
			n++;
		}
		if (n > 0) {
			DRAINED.addAndGet(n);
			DRAINED_BATCHES.incrementAndGet();
		}
	}

	/**
	 * Drops every pending placement for one world (world detach / engine
	 * shutdown of that dimension). A placement whose level never ticks again
	 * can never be replayed; dropping it matches the detach semantics —
	 * tickets are session state, and the dimension's own ticket storage is
	 * saved with it.
	 */
	public static void clearWorld(String worldKey) {
		PENDING.keySet().removeIf(key -> key.world().equals(worldKey));
	}

	/** @return the number of placements currently pending (diagnostics). */
	public static int pendingCount() {
		return PENDING.size();
	}

	/** Total placements deferred from workers (diagnostics). */
	public static long deferred() {
		return DEFERRED.get();
	}

	/** Total placements replayed server-thread (diagnostics). */
	public static long drained() {
		return DRAINED.get();
	}

	/** Total drain cycles that replayed at least one placement (diagnostics). */
	public static long drainedBatches() {
		return DRAINED_BATCHES.get();
	}
}
