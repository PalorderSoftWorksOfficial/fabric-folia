/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The per-server registry of {@link WorldRegionizer}s, keyed by world name.
 *
 * <p><strong>Why this exists:</strong> vanilla is multi-world (overworld,
 * nether, end, custom dimensions), and regionization is per-world — regions
 * never span dimensions. The registry is how the rest of the engine resolves
 * "the regionizer for dimension X" without knowing how many worlds exist. The
 * fabric module creates one entry per live dimension at server start
 * (spec 12's multi-world wiring; the foundation milestone had a single demo
 * world).</p>
 *
 * <p><strong>Threading:</strong> a ConcurrentHashMap — worlds register on the
 * server thread at start and the map is read from region workers and
 * diagnostics; the map's own operations are the only concurrent thing here
 * (spec 18 review note: registry, not a safety mechanism). Each
 * WorldRegionizer has its own structure lock, so worlds are fully independent
 * — contention in one dimension never stalls another (spec 6 independence,
 * extended across dimensions).</p>
 *
 * <p><strong>State classification (spec 4):</strong> GLOBAL — server-wide
 * bookkeeping about the regionization structure itself.</p>
 */
public final class WorldRegionizerRegistry {

	private final Map<String, WorldRegionizer> byWorld = new ConcurrentHashMap<>();

	/**
	 * Returns the regionizer for a world, creating it on first request with
	 * the supplied config. Later requests for the same world return the same
	 * instance regardless of the config passed — config is applied at
	 * creation, per-world, at attach time (changing section geometry on a
	 * live regionizer is not supported and is refused by design).
	 */
	public WorldRegionizer getOrCreate(String worldName, Function<String, RegionizerConfig> configForWorld) {
		Objects.requireNonNull(worldName, "worldName");
		return byWorld.computeIfAbsent(worldName, name ->
				new WorldRegionizer(name, configForWorld.apply(name),
						new java.util.concurrent.atomic.AtomicLong(1)::getAndIncrement,
						System::nanoTime));
	}

	/** @return the regionizer for a world, or null if no world is attached under that name. */
	public WorldRegionizer get(String worldName) {
		return byWorld.get(worldName);
	}

	/** @return true if a regionizer is attached for this world. */
	public boolean isAttached(String worldName) {
		return byWorld.containsKey(worldName);
	}

	/** Removes a world's regionizer (dimension unload / shutdown bookkeeping). */
	public void remove(String worldName) {
		byWorld.remove(worldName);
	}

	/** @return the number of attached worlds (diagnostics). */
	public int attachedWorldCount() {
		return byWorld.size();
	}

	/** @return the attached world names (diagnostics; snapshot, unordered). */
	public List<String> attachedWorlds() {
		return new ArrayList<>(byWorld.keySet());
	}
}
