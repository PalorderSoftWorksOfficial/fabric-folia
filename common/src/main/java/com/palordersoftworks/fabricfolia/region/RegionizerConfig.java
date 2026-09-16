/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

/**
 * Regionizer tuning parameters (spec 2.2: "merge radius and empty section
 * creation radius must be implemented and configurable with sane defaults
 * derived from Minecraft's actual simulation distance").
 *
 * <p><strong>Why these are not config-file options yet:</strong> spec 11 forbids
 * exposing internal knobs that an administrator cannot meaningfully judge. These
 * are derived automatically from the effective simulation distance (see
 * {@link #derive(int)}); they become admin-visible only if a real operational
 * need emerges.</p>
 *
 * <p><strong>Radius semantics:</strong> all radii are in REGION SECTIONS
 * (not chunks). A radius of r means: for any owned non-empty section, all
 * sections within Chebyshev distance r must be owned by the same region or
 * pending merge into it (invariant 2).</p>
 *
 * <p><strong>Default derivation:</strong> the buffer must be at least as wide as
 * anything that reads across a region boundary in one tick. The widest such
 * reach in Minecraft gameplay terms is bounded by simulation distance (entities
 * and scheduled ticks do not act beyond it) — converted to sections and padded
 * by one so neighbouring-tick interference cannot straddle the edge. This is the
 * same reasoning Folia applies; the constant is deliberately conservative
 * (buffer too wide wastes bookkeeping, too narrow is a correctness bug).</p>
 */
public record RegionizerConfig(
		/** Sections per side: N (power of two); N chunks = 1 << sectionChunkShift. */
		int sectionSizeChunks,
		/** Merge radius in sections (invariant 2 buffer, Chebyshev distance). */
		int mergeRadiusSections,
		/** Empty-section creation radius in sections (invariant 2 enforcement). */
		int emptySectionCreationRadius,
		/** Min sections a region must own before split recalculation runs. */
		int recalculationCount,
		/** Percent of dead sections required before recalculation runs (0-100). */
		int maxDeadSectionPercent
) {
	public RegionizerConfig {
		if (Integer.bitCount(sectionSizeChunks) != 1 || sectionSizeChunks < 1 || sectionSizeChunks > 32) {
			throw new IllegalArgumentException("sectionSizeChunks must be a power of two in [1,32]: "
					+ sectionSizeChunks);
		}
		if (mergeRadiusSections < 1) throw new IllegalArgumentException("mergeRadiusSections < 1");
		if (emptySectionCreationRadius < mergeRadiusSections) {
			throw new IllegalArgumentException(
					"emptySectionCreationRadius must be >= mergeRadiusSections (buffer must cover merge)");
		}
		if (recalculationCount < 1) throw new IllegalArgumentException("recalculationCount < 1");
		if (maxDeadSectionPercent < 0 || maxDeadSectionPercent > 100) {
			throw new IllegalArgumentException("maxDeadSectionPercent out of range");
		}
	}

	public int sectionChunkShift() {
		return Integer.numberOfTrailingZeros(sectionSizeChunks);
	}

	/**
	 * Derives a regionizer config from the server's effective simulation
	 * distance (in chunks).
	 *
	 * @param simulationDistanceChunks the effective simulation distance in chunks
	 */
	public static RegionizerConfig derive(int simulationDistanceChunks) {
		if (simulationDistanceChunks < 1) {
			throw new IllegalArgumentException("simulationDistanceChunks < 1");
		}
		int sectionShift = 3; // 8x8 default section size
		int sectionsAcrossSim = Math.max(1,
				(simulationDistanceChunks >> sectionShift) + (simulationDistanceChunks % (1 << sectionShift) == 0 ? 0 : 1));

		// The merge radius must cover the simulation horizon plus one section of
		// slack: two regions both ticking adjacent to a shared sim-relevant area
		// must never both "own" it, even transiently.
		int mergeRadius = sectionsAcrossSim + 1;
		// Buffer creation is one wider than merge: new activity must land inside
		// an already-buffered halo, not merely at its edge.
		int emptyRadius = mergeRadius + 1;

		return new RegionizerConfig(
				1 << sectionShift,
				mergeRadius,
				emptyRadius,
				16,   // recalculationCount: Folia-style performance knob
				30    // maxDeadSectionPercent: recalc when ~1/3 of sections are dead
		);
	}

	/** Compact defaults for tests (small radii, fast recalculation). */
	public static RegionizerConfig forTests() {
		return new RegionizerConfig(2, 1, 2, 2, 50);
	}
}
