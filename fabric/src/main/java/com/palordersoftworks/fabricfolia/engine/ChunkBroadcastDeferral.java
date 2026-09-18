/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for the worker-context block-broadcast deferral
 * ({@code ServerChunkCacheMixin}). Live on the crash path found by the
 * churn/teleport storm: worker {@code setBlock} reaches
 * {@code ServerChunkCache.blockChanged}, whose enqueue into the
 * server-thread-iterated {@code chunkHoldersToBroadcast} set is not
 * thread-safe — so region workers record the holder into a per-level
 * concurrent pending set and the server thread drains it at
 * {@code broadcastChangedChunks} HEAD, before vanilla's iterator exists.
 *
 * <p>Counters only here; the pending set itself lives on the mixin instance
 * (per-level state, no cross-level routing mistakes possible).</p>
 */
public final class ChunkBroadcastDeferral {

	private static final AtomicLong DEFERRED = new AtomicLong();
	private static final AtomicLong DRAINED = new AtomicLong();

	private ChunkBroadcastDeferral() {
	}

	/** Called once per holder newly queued by a region worker. */
	public static void recordDeferred() {
		DEFERRED.incrementAndGet();
	}

	/** Called by the server thread with the number of holders moved into the vanilla broadcast set. */
	public static void recordDrained(int n) {
		DRAINED.addAndGet(n);
	}

	/** Total holders deferred from workers (diagnostics, mandate §35). */
	public static long deferred() {
		return DEFERRED.get();
	}

	/** Total holders drained into vanilla broadcast batches (diagnostics). */
	public static long drained() {
		return DRAINED.get();
	}
}
