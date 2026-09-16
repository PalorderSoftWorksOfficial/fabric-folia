/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.entity;

/**
 * Duck-typed tracker attachment for {@code Entity} (the standard Fabric
 * mixin-accessor pattern): {@link com.palordersoftworks.fabricfolia.mixin.EntityMixin}
 * implements this on the Entity class with internal fields, so the add hook
 * hands the entity its dimension's tracker once and the per-move hook runs
 * with no map lookups and no per-move string building.
 *
 * <p><strong>The chunk cache:</strong> region ownership changes only when an
 * entity crosses a CHUNK boundary — within-chunk movement cannot change the
 * owning region. The move hook therefore compares a packed chunk coordinate
 * against {@code fabricfolia$lastChunk} (two shifts and a long compare) and
 * only calls through to the tracker on an actual boundary crossing. The
 * tracker itself runs the registry's atomic migrate; without the cache, every
 * position micro-update would acquire the regionizer's structure lock.</p>
 *
 * <p><strong>Lifecycle:</strong> both fields are set when the entity is added
 * to a tracked server dimension (regardless of whether a region owned its
 * chunk at that moment — registration happens on the first boundary crossing
 * into an owned chunk) and cleared on {@code setRemoved}. Dimension transfers
 * re-cache through the new dimension's add path.</p>
 */
public interface FabricFoliaEntityHolder {

	/** Caches (or clears, with null) the owning dimension's tracker. */
	void fabricfolia$setTracker(EntityRegionTracker tracker);

	/** The cached tracker, or null when not tracked in this phase. */
	EntityRegionTracker fabricfolia$tracker();

	/** Caches the last-seen packed chunk (see the chunk-cache note). */
	void fabricfolia$setLastChunk(long packedChunk);

	/** The last-seen packed chunk (meaningful only while tracked). */
	long fabricfolia$lastChunk();
}
