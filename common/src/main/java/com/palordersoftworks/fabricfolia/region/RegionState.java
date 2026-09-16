/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

/**
 * The four states a region may be in (regionizer invariant 4 — a region is
 * always in exactly one of these states; see REGIONS.md).
 *
 * <p><strong>Transition map</strong> (all transitions occur under the owning
 * {@link WorldRegionizer}'s structure lock):</p>
 *
 * <pre>
 * TRANSIENT --(merge makes it self-sufficient)--> READY
 * READY     --(tryMarkTicking succeeds)---------> TICKING
 * TICKING   --(markNotTicking: has merge-later targets)--> TRANSIENT
 * TICKING   --(markNotTicking: otherwise)--------> READY
 * any       --(merged away)---------------------> DEAD
 * </pre>
 *
 * <p><strong>Why TRANSIENT may not tick:</strong> a transient region is pending
 * merge into another region or is otherwise not self-sufficient; letting it tick
 * would allow two contexts to observe overlapping state once the merge lands
 * (invariant 2's buffer guarantee is only true for READY regions).</p>
 */
public enum RegionState {
	/** Exists but may not tick; typically pending a merge into another region. */
	TRANSIENT,
	/** Fully independent, buffer-satisfied, eligible to tick. */
	READY,
	/** Currently executing a tick on exactly one worker context. */
	TICKING,
	/** Merged away or retired; owns nothing and will be discarded. */
	DEAD
}
