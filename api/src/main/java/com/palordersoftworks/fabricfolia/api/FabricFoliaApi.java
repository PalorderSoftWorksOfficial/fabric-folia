/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;

/**
 * The public entry point other Fabric mods use to become region-aware (spec 9).
 *
 * <p>Obtained via Fabric's entrypoint convention: mods register an entrypoint of
 * key {@code "fabricfolia"} in their {@code fabric.mod.json} and receive an
 * instance of this interface during server initialization. Until the receiving
 * endpoint is wired (a later phase), all accessor methods throw
 * {@link IllegalStateException} with an explicit "not initialized" message —
 * honest placeholders, never fake working ones (spec 17).</p>
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
	 * @return true if Fabric-Folia's regionized execution is currently active;
	 * false while disabled (vanilla single-threaded execution is in place).
	 */
	boolean isEnabled();
}
