/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.patches;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The central registry and resolver of FabricFolia's toggleable performance
 * patches.
 *
 * <p><strong>Model:</strong> a patch is a named, layered optimization with a
 * real implementation behind its {@link #isEnabled} gate. Patches declare
 * dependencies and conflicts; {@link #resolveAndApply} validates the whole
 * registry once at startup — the resolved, stable state is what runs all
 * session. Layers can be enabled/disabled globally from configuration;
 * a patch activates only when its layer is on AND its own toggle is on AND
 * its dependencies are active AND no conflict is active.</p>
 *
 * <p><strong>Lifecycle honesty (spec 15):</strong> every patch declares
 * {@link Lifecycle#STARTUP_ONLY} or {@link Lifecycle#RUNTIME}; the registry
 * is resolved once at startup and there is no mid-session flip API. Mixin
 * gates are pinned at class-load time, so all current patches are
 * STARTUP_ONLY — runtime toggles would silently not apply and are therefore
 * not offered.</p>
 *
 * <p><strong>Metadata honesty (spec 5):</strong> only patches with real
 * implementations behind their gates are registered. No speculative entries.</p>
 */
public final class PatchRegistry {

	/** The layers a patch can belong to (grouping + global toggles). */
	public enum Layer {
		MINECRAFT,
		FABRIC,
		FABRICFOLIA,
		NETWORK,
		ALLOCATION,
		SCHEDULER,
		REGION
	}

	/** When a patch's state may change. */
	public enum Lifecycle {
		/** Resolved once at startup; config changes require a restart. */
		STARTUP_ONLY,
		/** May be re-evaluated at runtime without restart. */
		RUNTIME
	}

	/** Why a patch is not active after resolution. */
	public enum Status {
		/** Active (layer on, toggle on, dependencies active, conflicts clear). */
		ACTIVE,
		/** Its own toggle or its layer's switch is off. */
		DISABLED,
		/** A declared dependency is not active. */
		BLOCKED_DEPENDENCY,
		/** A declared conflict is active. */
		BLOCKED_CONFLICT,
		/** Failed during activation (reason reported). */
		FAILED
	}

	/** One registered patch: metadata plus its runtime enable state. */
	public static final class Patch {
		private final String id;
		private final String name;
		private final Layer layer;
		private final String description;
		private final String target;
		private final Set<String> dependencies;
		private final Set<String> conflicts;
		private final Lifecycle lifecycle;
		private volatile boolean requested = true;
		private volatile Status status = Status.DISABLED;
		private volatile String statusReason = "not resolved yet";
		private volatile long invocations;
		private volatile long fallbacks;

		Patch(String id, String name, Layer layer, String description, String target,
				Set<String> dependencies, Set<String> conflicts, Lifecycle lifecycle) {
			this.id = id;
			this.name = name;
			this.layer = layer;
			this.description = description;
			this.target = target;
			this.dependencies = dependencies;
			this.conflicts = conflicts;
			this.lifecycle = lifecycle;
		}

		public String id() {
			return id;
		}

		public String name() {
			return name;
		}

		public Layer layer() {
			return layer;
		}

		public String description() {
			return description;
		}

		public String target() {
			return target;
		}

		public Set<String> dependencies() {
			return Collections.unmodifiableSet(dependencies);
		}

		public Set<String> conflicts() {
			return Collections.unmodifiableSet(conflicts);
		}

		public Lifecycle lifecycle() {
			return lifecycle;
		}

		/** @return whether the operator requested this patch on. */
		public boolean requested() {
			return requested;
		}

		/** @return the post-resolution status. */
		public Status status() {
			return status;
		}

		/** @return why the patch is in its current status. */
		public String statusReason() {
			return statusReason;
		}

		void setStatus(Status status, String reason) {
			this.status = status;
			this.statusReason = reason;
		}

		public long invocations() {
			return invocations;
		}

		public long fallbacks() {
			return fallbacks;
		}
	}

	/** A resolution problem: the patch and why it could not activate. */
	public record BlockedPatch(String id, Status status, String reason) {
	}

	private static final Map<String, Patch> PATCHES = new LinkedHashMap<>();
	private static final Map<Layer, Boolean> LAYER_ENABLED = new ConcurrentHashMap<>();
	private static volatile boolean resolved;

	private PatchRegistry() {
	}

	/**
	 * Declares a patch. First registration wins; re-entry is a no-op.
	 *
	 * @param id           unique id (also the config toggle key under patches)
	 * @param name         human-readable name (diagnostics)
	 * @param layer        the patch's layer
	 * @param description  what the patch actually does
	 * @param target       the code path the patch optimizes
	 * @param dependencies patch ids that must be active for this one to run
	 * @param conflicts    patch ids that must not be active alongside this one
	 * @param lifecycle    STARTUP_ONLY or RUNTIME
	 * @return the registered patch (the existing one on duplicate ids)
	 */
	public static Patch register(String id, String name, Layer layer,
			String description, String target,
			Set<String> dependencies, Set<String> conflicts, Lifecycle lifecycle) {
		synchronized (PATCHES) {
			Patch existing = PATCHES.get(id);
			if (existing != null) {
				return existing;
			}
			Patch patch = new Patch(id, name, layer, description, target,
					new LinkedHashSet<>(dependencies), new LinkedHashSet<>(conflicts),
					lifecycle);
			PATCHES.put(id, patch);
			return patch;
		}
	}

	/** Convenience overload: no dependencies, no conflicts. */
	public static Patch register(String id, String name, Layer layer,
			String description, String target, Lifecycle lifecycle) {
		return register(id, name, layer, description, target,
				Set.of(), Set.of(), lifecycle);
	}

	/** Sets a layer's operator-requested state (config resolution). */
	public static void setLayerEnabled(Layer layer, boolean enabled) {
		LAYER_ENABLED.put(layer, enabled);
	}

	/** @return the operator-requested state of a layer (default: enabled). */
	public static boolean isLayerEnabled(Layer layer) {
		return LAYER_ENABLED.getOrDefault(layer, Boolean.TRUE);
	}

	/** Sets one patch's operator-requested state (config resolution). */
	public static void setRequested(String id, boolean enabled) {
		Patch patch = PATCHES.get(id);
		if (patch != null) {
			patch.requested = enabled;
		}
	}

	/** @return the operator-requested state of a patch (default: enabled). */
	public static boolean isRequested(String id) {
		Patch patch = PATCHES.get(id);
		return patch != null && patch.requested;
	}

	/**
	 * Resolves the whole registry: validates dependencies and conflicts and
	 * computes every patch's final status. Order-independent (a patch whose
	 * dependency resolves later in the same pass still activates). Runs once
	 * at startup, before any server code.
	 *
	 * @return patches that could not activate, with reasons
	 */
	public static List<BlockedPatch> resolveAndApply() {
		synchronized (PATCHES) {
			for (boolean changed = true; changed; ) {
				changed = false;
				for (Patch patch : PATCHES.values()) {
					Status prior = patch.status;
					Status now = evaluate(patch);
					if (now != prior) {
						patch.setStatus(now, reasonFor(patch, now));
						changed = true;
					}
				}
			}
			resolved = true;
			List<BlockedPatch> blocked = new ArrayList<>();
			for (Patch patch : PATCHES.values()) {
				if (patch.status() != Status.ACTIVE) {
					blocked.add(new BlockedPatch(patch.id(), patch.status(), patch.statusReason()));
				}
			}
			return blocked;
		}
	}

	private static Status evaluate(Patch patch) {
		if (!patch.requested || !isLayerEnabled(patch.layer())) {
			return Status.DISABLED;
		}
		for (String dependency : patch.dependencies()) {
			Patch dep = PATCHES.get(dependency);
			if (dep == null) {
				return Status.BLOCKED_DEPENDENCY;
			}
			if (dep.status() != Status.ACTIVE) {
				return Status.BLOCKED_DEPENDENCY;
			}
		}
		for (String conflict : patch.conflicts()) {
			Patch other = PATCHES.get(conflict);
			if (other != null && other.status() == Status.ACTIVE) {
				return Status.BLOCKED_CONFLICT;
			}
		}
		return Status.ACTIVE;
	}

	private static String reasonFor(Patch patch, Status status) {
		return switch (status) {
			case ACTIVE -> "active";
			case DISABLED -> !patch.requested
					? "disabled by configuration (patches." + patch.id() + ")"
					: "disabled by configuration (patches." + layerKey(patch.layer()) + ")";
			case BLOCKED_DEPENDENCY -> {
				for (String dependency : patch.dependencies()) {
					Patch dep = PATCHES.get(dependency);
					if (dep == null || dep.status() != Status.ACTIVE) {
						yield "requires " + dependency + " ("
								+ (dep == null ? "unknown patch" : dep.status().name().toLowerCase(Locale.ROOT)) + ")";
					}
				}
				yield "a dependency is not active";
			}
			case BLOCKED_CONFLICT -> {
				for (String conflict : patch.conflicts()) {
					Patch other = PATCHES.get(conflict);
					if (other != null && other.status() == Status.ACTIVE) {
						yield "conflicts with " + conflict + " (active)";
					}
				}
				yield "conflicts with another active patch";
			}
			case FAILED -> "activation failure (reported at registration)";
		};
	}

	/**
	 * The hot-path gate: true when the patch may take its optimized path.
	 * One volatile read in the steady state; safe from every execution
	 * context. False before resolution — call sites naturally fall back to
	 * the original path until the registry resolves.
	 */
	public static boolean isEnabled(String id) {
		Patch patch = PATCHES.get(id);
		return patch != null && patch.status() == Status.ACTIVE;
	}

	/** @return the registered patch, or null when the id is unknown. */
	public static Patch patch(String id) {
		return PATCHES.get(id);
	}

	/** Records one optimized-path execution (diagnostics). */
	public static void recordInvocation(String id) {
		Patch patch = PATCHES.get(id);
		if (patch != null) {
			patch.invocations++;
		}
	}

	/** Records one original-path fallback (diagnostics). */
	public static void recordFallback(String id) {
		Patch record = PATCHES.get(id);
		if (record != null) {
			record.fallbacks++;
		}
	}

	/** @return all registered patches (declaration order). */
	public static List<Patch> patches() {
		synchronized (PATCHES) {
			return new ArrayList<>(PATCHES.values());
		}
	}

	/** @return whether {@link #resolveAndApply} has run. */
	public static boolean isResolved() {
		return resolved;
	}

	private static String layerKey(Layer layer) {
		return layer.name().toLowerCase(Locale.ROOT);
	}

	/** Clears all registrations and state (tests). */
	public static void resetForTests() {
		synchronized (PATCHES) {
			PATCHES.clear();
			LAYER_ENABLED.clear();
			resolved = false;
		}
	}
}
