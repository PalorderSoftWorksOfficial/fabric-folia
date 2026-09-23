/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.console;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The startup compatibility scan (presentation + classification only — this
 * class changes no behavior and holds no state beyond log suppression).
 *
 * <p><strong>Classification rules (documented in docs/compatibility/):</strong></p>
 * <ul>
 *   <li><strong>Measured status:</strong> mods whose interaction with
 *       Fabric-Folia has actually been runtime-tested by the compat harness
 *       (see COMPATIBILITY.md) carry that measured status. A measured status
 *       is evidence, not a guarantee across versions — re-validated on every
 *       Minecraft update.</li>
 *   <li><strong>DECLARED:</strong> the mod's {@code fabric.mod.json} contains
 *       {@code "custom": { "fabricfolia": { "compatibility": "supported" } }}
 *       (accepted values: {@code supported}, {@code experimental}, {@code
 *       incompatible}). This is the mod author's declaration about their own
 *       mod — honored as such.</li>
 *   <li><strong>NOT DECLARED:</strong> anything else. This is the normal
 *       state for the overwhelming majority of mods. It does <em>not</em>
 *       mean incompatible: most mods that do not touch region-owned state
 *       from the wrong thread work through legacy compatibility handling.
 *       Undeclared mods produce exactly one informational line at startup —
 *       never a per-mod warning storm.</li>
 * </ul>
 *
 * <p><strong>Anti-spam (requirement 6):</strong> the scan runs once at
 * startup; its output is the compatibility summary. No per-tick, per-chunk,
 * or per-access compatibility logging exists — diagnostics about individual
 * accesses belong to the thread-context violation report, which is a
 * different message with a different purpose.</p>
 */
public final class CompatScanner {

	/** Classification of one scanned mod. */
	public enum Status {
		/** Runtime-tested by the compat harness at the recorded versions. */
		MEASURED,
		/** The mod declares Fabric-Folia support in its own metadata. */
		DECLARED,
		/** The mod declares experimental/partial support. */
		DECLARED_EXPERIMENTAL,
		/** The mod declares itself incompatible. */
		DECLARED_INCOMPATIBLE,
		/** No declaration either way — the normal case, not an error. */
		NOT_DECLARED
	}

	/** One mod's scan result. */
	public record Entry(String id, String version, Status status, String note) {
	}

	/**
	 * Mods whose interaction with Fabric-Folia has been measured at
	 * runtime (recorded in COMPATIBILITY.md). Keys are mod ids. A status
	 * here is always version-pinned in the docs; it is updated only by
	 * re-running the measurements, never by assumption.
	 */
	private static final Map<String, String> MEASURED = Map.of(
			"lithium", "runtime-tested by the Fabric Folia compatibility harness (see COMPATIBILITY.md for versions and result)",
			"c2me", "runtime-tested by the Fabric Folia compatibility harness (see COMPATIBILITY.md for versions and result)",
			"ferritecore", "runtime-tested by the Fabric Folia compatibility harness (see COMPATIBILITY.md for versions and result)",
			"krypton", "runtime-tested by the Fabric Folia compatibility harness (see COMPATIBILITY.md for versions and result)",
			"vmp", "runtime-tested by the Fabric Folia compatibility harness (see COMPATIBILITY.md for versions and result)",
			"scalablelux", "runtime-tested by the Fabric Folia compatibility harness (see COMPATIBILITY.md for versions and result)");

	private static final String CUSTOM_KEY = "fabricfolia";
	private static final String DECLARATION_KEY = "compatibility";

	private CompatScanner() {
	}

	/**
	 * Scans every installed mod and logs the compatibility picture once:
	 * measured/declared mods by name, a single explanatory line covering
	 * undeclared mods, and the summary. Returns the entries for tests and
	 * future command surfacing.
	 */
	public static List<Entry> scanAndReport() {
		List<Entry> entries = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			ModMetadata meta = mod.getMetadata();
			if ("fabricloader".equals(meta.getId()) || "java".equals(meta.getId())
					|| "minecraft".equals(meta.getId())) {
				continue;
			}
			entries.add(classify(meta));
		}
		entries.sort((a, b) -> a.status().compareTo(b.status()) != 0
				? a.status().compareTo(b.status())
				: a.id().compareTo(b.id()));

		List<Entry> measured = entries.stream().filter(e -> e.status() == Status.MEASURED).toList();
		List<Entry> declared = entries.stream().filter(e -> e.status() == Status.DECLARED).toList();
		List<Entry> experimental = entries.stream().filter(e -> e.status() == Status.DECLARED_EXPERIMENTAL).toList();
		List<Entry> incompatible = entries.stream().filter(e -> e.status() == Status.DECLARED_INCOMPATIBLE).toList();
		int undeclared = (int) entries.stream().filter(e -> e.status() == Status.NOT_DECLARED).count();

		Console.compat("Compatibility Check");
		for (Entry e : measured) {
			Console.compat("  " + e.id() + " " + e.version() + ": detected (" + e.note() + ")");
		}
		for (Entry e : declared) {
			Console.compat("  " + e.id() + " " + e.version() + ": declares Fabric Folia support");
		}
		for (Entry e : experimental) {
			Console.warning("  Mod \"" + e.id() + "\" declares EXPERIMENTAL Fabric Folia support. "
					+ "It may not be fully region-safe; the thread-context diagnostics (threads.thread-check-mode) will name it if it touches region-owned state unsafely. "
					+ "See docs/compatibility/README.md.");
		}
		for (Entry e : incompatible) {
			Console.warning("  Mod \"" + e.id() + "\" declares itself INCOMPATIBLE with Fabric Folia. "
					+ "The server can still start, but that mod's behavior is not guaranteed. Remove it if you see errors naming it. "
					+ "See docs/compatibility/README.md.");
		}
		if (undeclared > 0) {
			// ONE line for all undeclared mods — never a per-mod warning storm.
			Console.info("  " + undeclared + " installed mod(s) have not declared Fabric Folia support. "
					+ "This does not necessarily mean they are incompatible: most mods work through legacy compatibility handling. "
					+ "Set diagnostics.debug-logging=true for the full list, and see docs/compatibility/README.md.");
		}

		// Summary (requirement 7).
		Console.compat("Compatibility Summary");
		Console.compat("  Measured:     " + measured.size());
		Console.compat("  Supported:    " + declared.size());
		Console.compat("  Undeclared:   " + undeclared);
		Console.compat("  Experimental: " + experimental.size());
		Console.compat("  Incompatible: " + incompatible.size());
		int warnings = experimental.size() + incompatible.size();
		if (warnings > 0) {
			Console.warning("Server startup completed with " + warnings + " compatibility warning(s).");
		}
		return entries;
	}

	private static Entry classify(ModMetadata meta) {
		String id = meta.getId();
		String version = meta.getVersion().getFriendlyString();
		if (MEASURED.containsKey(id)) {
			return new Entry(id, version, Status.MEASURED, MEASURED.get(id));
		}
		CustomValue custom = meta.getCustomValue(CUSTOM_KEY);
		if (custom != null && custom.getType() == CustomValue.CvType.OBJECT) {
			CustomValue compat = ((CustomValue.CvObject) custom).get(DECLARATION_KEY);
			if (compat != null && compat.getAsString() != null) {
				return switch (compat.getAsString()) {
					case "supported" -> new Entry(id, version, Status.DECLARED, null);
					case "experimental" -> new Entry(id, version, Status.DECLARED_EXPERIMENTAL, null);
					case "incompatible" -> new Entry(id, version, Status.DECLARED_INCOMPATIBLE, null);
					default -> new Entry(id, version, Status.NOT_DECLARED, null);
				};
			}
		}
		return new Entry(id, version, Status.NOT_DECLARED, null);
	}

	/** @return ids of all undeclared mods (debug-logging support). */
	public static List<String> undeclaredIds(List<Entry> entries) {
		return entries.stream().filter(e -> e.status() == Status.NOT_DECLARED)
				.map(Entry::id).sorted().toList();
	}

	/** Debug-visible map of every entry (debug-logging support). */
	public static Map<String, String> debugTable(List<Entry> entries) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Entry e : entries) {
			out.put(e.id(), e.status() + " (" + e.version() + ")");
		}
		return out;
	}
}
