/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

import java.util.HashSet;
import java.util.Set;

/**
 * A region section: the NxN-chunk bookkeeping unit of the regionizer (spec 2.2;
 * the "8x8" default lives here, NOT as a fixed region shape).
 *
 * <p><strong>State model (from Folia's documented region logic, clean-room):</strong>
 * a section is dead or alive, and independently empty or non-empty:</p>
 * <ul>
 *   <li><strong>non-empty:</strong> contains at least one chunk position.</li>
 *   <li><strong>alive:</strong> empty but within the empty-section creation
 *       radius of some non-empty section — part of a region's buffer.</li>
 *   <li><strong>dead:</strong> empty AND no non-empty section within the
 *       empty-section creation radius. Dead sections still belong to their
 *       region (invariant 1 holds for them); they exist purely so recalculation
 *       (split) logic can be deferred until enough of them accumulate.</li>
 * </ul>
 *
 * <p><strong>Ownership is position-exact:</strong> {@link #owned} holds every
 * distinct owned chunk position, so {@code addChunk} is idempotent per position
 * (the vanilla entity-ticking pass re-offers the same loaded chunk every tick)
 * and exactly one {@code removeChunk} per unloaded chunk can empty a section.
 * The former integer event counter inflated with every re-offer, which made
 * section death — and therefore unload-driven splits — unreachable on a live
 * server (defect found in live validation).</p>
 *
 * <p><strong>Concurrency contract:</strong> section state mutates only inside
 * {@link WorldRegionizer} operations (addChunk/removeChunk and tick-end
 * recalculation), which are serialized by the regionizer's structure lock.
 * Fields are package-visible with no volatile/atomic because no legal access is
 * ever concurrent (ownership model, not locking — spec 3/18).</p>
 */
public final class RegionSection {
	/** Section x coordinate, in section units (chunk >> sectionShift). */
	public final int x;
	/** Section z coordinate, in section units. */
	public final int z;

	/**
	 * Distinct chunk positions currently owned by this section, packed as
	 * {@code (z & 0xFFFFFFFFL) | (x << 32)} (the vanilla ChunkPos layout).
	 */
	final Set<Long> owned = new HashSet<>();

	/** true while this section belongs to a region (alive or dead-but-owned). */
	boolean alive;

	RegionSection(int x, int z, boolean alive) {
		this.x = x;
		this.z = z;
		this.alive = alive;
	}

	/** Records ownership of one chunk position. @return true if newly owned. */
	boolean addChunk(int chunkX, int chunkZ) {
		return owned.add(pack(chunkX, chunkZ));
	}

	/** Releases ownership of one chunk position. @return true if it was owned. */
	boolean removeChunk(int chunkX, int chunkZ) {
		return owned.remove(pack(chunkX, chunkZ));
	}

	private static long pack(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	/** @return true if the section holds no chunk. */
	public boolean isEmpty() {
		return owned.isEmpty();
	}

	/** @return true if the section holds at least one chunk. */
	public boolean isNotEmpty() {
		return !owned.isEmpty();
	}

	/** @return true if the section is a buffer section (empty but alive). */
	public boolean isEmptyAlive() {
		return alive && owned.isEmpty();
	}

	/** @return true if this section is dead (empty and out of every buffer). */
	public boolean isDead() {
		return !alive;
	}

	@Override
	public String toString() {
		return "Section(" + x + "," + z + "; chunks=" + owned.size() + ", " + (alive ? "alive" : "dead") + ")";
	}
}
