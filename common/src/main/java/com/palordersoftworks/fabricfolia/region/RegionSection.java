/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

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
 * <p><strong>Concurrency contract:</strong> section state mutates only inside
 * {@link WorldRegionizer} operations (addChunk/removeChunk and tick-end
 * recalculation), which are serialized by the regionizer's structure lock.
 * Fields are package-visible plain ints: no volatile/atomic needed because no
 * legal access is ever concurrent (ownership model, not locking — spec 3/18).</p>
 */
public final class RegionSection {
	/** Section x coordinate, in section units (chunk >> sectionShift). */
	public final int x;
	/** Section z coordinate, in section units. */
	public final int z;

	/**
	 * Number of chunk positions currently registered in this section.
	 * 0 means the section is a buffer (empty) or dead section.
	 */
	int chunkCount;

	/** true while this section belongs to a region (alive or dead-but-owned). */
	boolean alive;

	RegionSection(int x, int z, boolean alive) {
		this.x = x;
		this.z = z;
		this.alive = alive;
	}

	/** @return true if the section holds at least one chunk. */
	public boolean isNotEmpty() {
		return chunkCount > 0;
	}

	/** @return true if the section is a buffer section (empty but alive). */
	public boolean isEmptyAlive() {
		return alive && chunkCount == 0;
	}

	/** @return true if this section is dead (empty and out of every buffer). */
	public boolean isDead() {
		return !alive;
	}

	@Override
	public String toString() {
		return "Section(" + x + "," + z + "; chunks=" + chunkCount + ", " + (alive ? "alive" : "dead") + ")";
	}
}
