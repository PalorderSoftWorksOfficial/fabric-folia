/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;

/**
 * The public entry point other Fabric mods use to become region-aware.
 *
 * <p>Obtained via Fabric's entrypoint convention: mods register an entrypoint of
 * key {@code "fabricfolia"} in their {@code fabric.mod.json} and receive an
 * instance of this interface once per server start, on the server thread, at the
 * end of server startup and before the first tick. The delivered instance is
 * backed by the live engine for the rest of the server run.</p>
 *
 * <p><strong>Availability:</strong> the entrypoint is delivered only when the
 * Fabric-Folia engine starts. When regionized execution is disabled by config,
 * the server runs vanilla single-threaded execution and initializers are never
 * invoked; consumers must also check that Fabric-Folia is installed. After
 * server shutdown, the scheduler accessors throw {@link IllegalStateException}
 * and {@link #isEnabled()} reports false; {@link #threadContext()} remains
 * readable.</p>
 *
 * <p>Everything reachable from here is thread-safe to call from any context;
 * the returned schedulers document their own per-method threading contracts.</p>
 */
@AnyThread
public interface FabricFoliaApi {
	/** @return the region scheduler for position-targeted work. */
	RegionScheduler regionScheduler();

	/** @return the entity scheduler for entity-following work. */
	EntityScheduler entityScheduler();

	/** @return the global scheduler for server-wide work. */
	GlobalScheduler globalScheduler();

	/**
	 * @return the async scheduler for work independent of region ticking and
	 * 		 of the global cadence — the fourth scheduler (spec §12). Tasks run
	 * 		 on dedicated async threads with NO region/global context; they must
	 * 		 not touch region- or global-owned state directly.
	 */
	AsyncScheduler asyncScheduler();

	/** @return the thread-ownership diagnostics facility. */
	ThreadContext threadContext();

	/**
	 * @return true while this instance is live — the engine started, the
	 * initializer was delivered, and the server has not shut down. This is not a
	 * capability report for individual regionized patches; an instance is only
	 * delivered when the engine is enabled, and it reports false after shutdown.
	 */
	boolean isEnabled();

	/**
	 * The entrypoint receiver: mods become region-aware by declaring an
	 * entrypoint of key {@code "fabricfolia"} in their {@code fabric.mod.json}
	 * whose class implements this interface. It is invoked once per server
	 * start, on the server thread, before the first tick runs; the given
	 * {@link FabricFoliaApi} instance remains valid until server shutdown.
	 */
	@AnyThread
	interface Initializer {
		/** Called once per server start with the live API. */
		void onInitialized(FabricFoliaApi api);
	}
}
