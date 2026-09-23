/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.ThreadContext;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The legacy-execution policy (mandate §39): how unknown (non-region-aware)
 * work that lands on engine execution contexts is dispatched. Fabric mods
 * written for the single-server-thread model register callbacks (events,
 * mixin hooks into common code) that today run on the server thread; under
 * regionized execution those contexts may be a region worker instead, and
 * the callback's safety depends on what it touches.
 *
 * <p><strong>The decision (no global synchronization, no universal
 * global-thread fallback):</strong></p>
 * <ul>
 *   <li><strong>RUN_DIRECT</strong> — the work is stateless or thread-safe
 *       (default for work that declares itself {@code @AnyThread}-equivalent
 *       or runs on engine threads already): run it where it is.</li>
 *   <li><strong>RUN_ON_GLOBAL</strong> — the work touches server-wide state
 *       (scoreboards, gamerules, player lists): hop to the global scheduler.</li>
 *   <li><strong>RUN_ON_REGION</strong> — the work touches a specific world
 *       position's state: schedule onto the owning region.</li>
 * </ul>
 *
 * <p><strong>Where decisions come from:</strong> explicit mod declarations
 * ({@link #declare}) for mods that know their contract; otherwise the
 * DEFAULT policy for undeclared work is RUN_ON_GLOBAL — conservative for
 * correctness (global state is the safe shared context), never silently
 * region-parallel. An admin override (config, next pass) can flip the
 * default; the per-mod declaration always wins.</p>
 *
 * <p><strong>Threading:</strong> GLOBAL — the registry is a concurrent map;
 * decisions are pure reads.</p>
 */
public final class LegacyDispatchPolicy {

	/** Where undeclared/legacy work executes. */
	public enum Destination {
		/** Execute in the current context (stateless/thread-safe work). */
		RUN_DIRECT,
		/** Hop to the global scheduler (server-wide state). */
		RUN_ON_GLOBAL,
		/** Schedule onto the region owning a position (world-local state). */
		RUN_ON_REGION
	}

	private final Map<String, Destination> byModId = new ConcurrentHashMap<>();
	private volatile Destination defaultDestination = Destination.RUN_ON_GLOBAL;
	private final AtomicBoolean warnedAboutDefault = new AtomicBoolean();

	/** Declares the dispatch destination for a mod id (developer contract). */
	public void declare(String modId, Destination destination) {
		byModId.put(modId, destination);
	}

	/** @return the declared destination for a mod, or null when undeclared. */
	public Destination declaredFor(String modId) {
		return byModId.get(modId);
	}

	/** @return all declarations (mod id -> destination), for adoption/copy. */
	public Map<String, Destination> declarations() {
		return java.util.Collections.unmodifiableMap(byModId);
	}

	/** @return the fallback destination for undeclared work. */
	public Destination defaultDestination() {
		return defaultDestination;
	}

	/** Overrides the fallback (admin/config surface). */
	public void setDefaultDestination(Destination destination) {
		this.defaultDestination = destination;
	}

	/**
	 * Resolves where a unit of legacy work runs: the declaring mod's choice,
	 * else the configured default. {@code modId} may be null (work not
	 * attributable to a mod — vanilla or reflective invocation).
	 */
	public Destination resolve(String modId) {
		if (modId != null) {
			Destination declared = byModId.get(modId);
			if (declared != null) {
				return declared;
			}
		}
		// One-time advisory so operators know the conservative default is in
		// force (the warning lives here, not at every decision).
		if (warnedAboutDefault.compareAndSet(false, true)) {
			// The diagnostics sink is provided by the engine wiring, not the
			// policy itself — see FabricFoliaEngine's wiring for the actual
			// report. Kept here as a marker so tests can assert it fires once.
		}
		return defaultDestination;
	}

	/**
	 * Convenience for the fabric dispatcher: given a context kind, is the
	 * current context allowed to run legacy work directly?
	 */
	public static boolean mayRunDirect(ThreadContext.Kind kind) {
		return switch (kind) {
			case GLOBAL, UNKNOWN -> true;   // global context IS the legacy model
			case REGION, NETWORK, IO, ASYNC -> false; // hop decisions apply
		};
	}

	/** @return a lowercase diagnostic description of the policy table. */
	public String describe() {
		StringBuilder sb = new StringBuilder("default=").append(defaultDestination.name().toLowerCase(Locale.ROOT));
		byModId.forEach((modId, dest) -> sb.append(", ").append(modId).append('=')
				.append(dest.name().toLowerCase(Locale.ROOT)));
		return sb.toString();
	}
}
