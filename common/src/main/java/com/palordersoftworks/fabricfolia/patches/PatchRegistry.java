/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.patches;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The central registry of FabricFolia's toggleable performance patches.
 *
 * <p><strong>Model:</strong> every optimization registers itself here with a
 * name and a layer. A patch is ACTIVE only when its layer is enabled AND its
 * own toggle is enabled. The hot-path hooks consult
 * {@link #isEnabled(String)} — one volatile boolean read — and fall back to
 * the original path when disabled, so disabling any patch restores the
 * unoptimized behavior without code changes.</p>
 *
 * <p><strong>Lifecycle:</strong> patches are declared during engine bootstrap
 * (before any server code runs), so the enabled/disabled snapshot is stable
 * for the server's lifetime. Toggles map to configuration read at startup;
 * there is deliberately no runtime flip API — every patch documents whether
 * its state can change, and the state never changes mid-tick.</p>
 *
 * <p><strong>Threading:</strong> all state is in concurrent maps; reads are
 * wait-free. Registration happens once at startup under normal single-thread
 * init; the registry stays safe anyway.</p>
 */
public final class PatchRegistry {

	/** The layers a patch can belong to (diagnostics grouping + global toggles). */
	public enum Layer {
		MINECRAFT,
		FABRIC,
		FABRICFOLIA,
		NETWORK,
		GC
	}

	/** One registered patch: identity, layer, and its own toggle. */
	public static final class Patch {
		private final String name;
		private final Layer layer;
		private final String description;
		private volatile boolean enabled = true;
		private volatile long invocations;
		private volatile long fallbacks;

		Patch(String name, Layer layer, String description) {
			this.name = name;
			this.layer = layer;
			this.description = description;
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

		public boolean enabled() {
			return enabled;
		}

		/** @return how many times the optimized path was taken (diagnostics). */
		public long invocations() {
			return invocations;
		}

		/** @return how many times the patch declined and used the original path. */
		public long fallbacks() {
			return fallbacks;
		}

		void recordInvocation() {
			invocations++;
		}

		void recordFallback() {
			fallbacks++;
		}
	}

	private static final Map<String, Patch> PATCHES = new ConcurrentHashMap<>();
	private static final Map<Layer, Boolean> LAYER_ENABLED = new ConcurrentHashMap<>();
	private static final long[] REGISTRY_VERSION = {0};

	private PatchRegistry() {
	}

	/**
	 * Declares a patch. Registration must happen at engine bootstrap before
	 * the server starts; a duplicate name replaces nothing (first wins, the
	 * duplicate call is a no-op) so re-entry from tests or repeated init is
	 * harmless.
	 *
	 * @return the registered patch (existing one when already declared)
	 */
	public static Patch register(String name, Layer layer, String description) {
		Patch existing = PATCHES.get(name);
		if (existing != null) {
			return existing;
		}
		Patch patch = new Patch(name, layer, description);
		PATCHES.put(name, patch);
		REGISTRY_VERSION[0]++;
		return patch;
	}

	/** Sets a layer's enabled state (config resolution at startup). */
	public static void setLayerEnabled(Layer layer, boolean enabled) {
		LAYER_ENABLED.put(layer, enabled);
	}

	/** Sets one patch's own toggle (config resolution at startup). */
	public static void setPatchEnabled(String name, boolean enabled) {
		Patch patch = PATCHES.get(name);
		if (patch != null) {
			patch.enabled = enabled;
		}
	}

	/**
	 * The hot-path gate: true when the named patch may take its optimized
	 * path (layer enabled AND patch enabled AND registry initialized). One
	 * volatile read in the steady state — safe to call from every execution
	 * context including workers.
	 */
	public static boolean isEnabled(String name) {
		Patch patch = PATCHES.get(name);
		if (patch == null) {
			return false;
		}
		return patch.enabled && LAYER_ENABLED.getOrDefault(patch.layer(), Boolean.TRUE);
	}

	/** @return the registered patch, or null when the name is unknown. */
	public static Patch patch(String name) {
		return PATCHES.get(name);
	}

	/**
	 * Records that the patch's optimized path ran (optional telemetry for
	 * the diagnostics view; counter contention is acceptable — call sites
	 * are per-tick, not per-element).
	 */
	public static void recordInvocation(String name) {
		Patch patch = PATCHES.get(name);
		if (patch != null) {
			patch.recordInvocation();
		}
	}

	/** Records that the patch declined and the original path ran instead. */
	public static void recordFallback(String name) {
		Patch patch = PATCHES.get(name);
		if (patch != null) {
			patch.recordFallback();
		}
	}

	/** @return all registered patches (diagnostics ordering: declaration). */
	public static List<Patch> patches() {
		return new ArrayList<>(PATCHES.values());
	}

	/** @return the monotonically increasing registry revision (change detection). */
	public static long registryVersion() {
		return REGISTRY_VERSION[0];
	}

	/** @return one human-readable line per patch (the /folia patches view). */
	public static List<String> descriptionLines() {
		List<String> lines = new ArrayList<>();
		for (Patch patch : PATCHES.values()) {
			lines.add(patch.name() + " [" + patch.layer().name().toLowerCase(Locale.ROOT) + "]"
					+ (patch.enabled() ? " enabled" : " DISABLED")
					+ " — " + patch.description());
		}
		if (lines.isEmpty()) {
			lines.add("(no patches registered)");
		}
		return lines;
	}

	/** Clears all registrations (tests only). */
	static void resetForTests() {
		PATCHES.clear();
		LAYER_ENABLED.clear();
		REGISTRY_VERSION[0]++;
	}
}
