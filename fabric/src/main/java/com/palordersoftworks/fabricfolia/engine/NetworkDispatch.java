/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Network dispatch (mandate §28): the machine-checked boundary between
 * Netty's event-loop threads and the execution contexts that may mutate
 * game state.
 *
 * <p><strong>The hazard.</strong> Vanilla packet handlers run on the server
 * thread, so they mutate world state freely. Under regionized execution the
 * same code paths execute on network event loops for packets that arrive
 * while the server thread is elsewhere, and any handler that touches
 * region-owned state (positions, blocks, inventories backing block
 * entities) from a network thread races that region's worker.</p>
 *
 * <p><strong>The rule this class enforces:</strong> network-context code may
 * classify and route, but never mutate. The hop is asynchronous
 * (fire-and-forget to the global scheduler or the owning region) — never a
 * synchronous block on a tick thread, which would stall the event loop and
 * deadlock under load (mandate §32).</p>
 *
 * <p><strong>Threading:</strong> pure functions over thread-local context;
 * the counters are the only state and are atomic.</p>
 */
public final class NetworkDispatch {

	private static final AtomicLong HOPS_GLOBAL = new AtomicLong();
	private static final AtomicLong HOPS_REGION = new AtomicLong();
	private static final AtomicLong INLINE_NETWORK = new AtomicLong();
	private static final AtomicLong NETWORK_EXECUTIONS = new AtomicLong();

	private NetworkDispatch() {
	}

	/** Counts one bounded network-context execution (mixin observability). */
	public static void noteNetworkExecution() {
		NETWORK_EXECUTIONS.incrementAndGet();
	}

	/** @return true when the current thread is a network event loop. */
	public static boolean isNetworkContext() {
		return ThreadOwnership.current().kind() == ThreadContext.Kind.NETWORK;
	}

	/**
	 * Runs a packet-task according to ownership: on the global context when
	 * no region is involved, or on the region owning the target chunk when
	 * one is. From non-network threads this executes inline (the caller is
	 * already on a valid mutation context).
	 *
	 * @param engine    the live engine (inline no-op dispatch when null)
	 * @param worldKey  the target world's key, or null for global-only work
	 * @param chunkX    target chunk x, ignored when worldKey is null
	 * @param chunkZ    target chunk z, ignored when worldKey is null
	 * @param task      the mutation to place on the owning context
	 */
	public static void runOnOwner(FabricFoliaEngine engine, String worldKey,
			int chunkX, int chunkZ, Runnable task) {
		if (!isNetworkContext()) {
			// Server thread, region worker, global thread: already a valid
			// mutation context for this task's classification.
			task.run();
			return;
		}
		INLINE_NETWORK.incrementAndGet(); // name kept: counted network-origin tasks
		if (engine == null) {
			return; // engine down: no sanctioned mutation context exists
		}
		if (worldKey == null) {
			HOPS_GLOBAL.incrementAndGet();
			engine.globalScheduler().run(task);
			return;
		}
		var scheduler = engine.schedulerFor(worldKey);
		var regionizer = engine.regionizerFor(worldKey);
		if (scheduler == null || regionizer == null) {
			HOPS_GLOBAL.incrementAndGet();
			engine.globalScheduler().run(task);
			return;
		}
		var region = regionizer.ownerOfChunk(chunkX, chunkZ);
		if (region == null || !scheduler.enqueue(region, task)) {
			// Unowned or dying region: global context is the safe fallback.
			HOPS_GLOBAL.incrementAndGet();
			engine.globalScheduler().run(task);
			return;
		}
		HOPS_REGION.incrementAndGet();
	}

	/** Metrics lines for /folia metrics. */
	public static List<String> metricsLines() {
		return List.of(
				"network dispatch: executions classified network=" + NETWORK_EXECUTIONS.get()
						+ ", region hops=" + HOPS_REGION.get()
						+ " global hops=" + HOPS_GLOBAL.get()
						+ " network-origin tasks=" + INLINE_NETWORK.get());
	}
}
