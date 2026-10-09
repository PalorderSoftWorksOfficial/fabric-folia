/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gpu;

/**
 * A backend answering vanilla-AABB overlap queries. The contract is
 * bit-exact equality with the vanilla {@code AABB#intersects} predicate:
 * the CPU reference is canonical, the GPU kernel must reproduce it exactly.
 */
public interface GpuBackend extends AutoCloseable {

	/** @return true when this backend executes on an accelerator. */
	boolean isGpu();

	/** @return short human-readable description for diagnostics. */
	String describe();

	/**
	 * @param boxes flat {@code [minX,minY,minZ,maxX,maxY,maxZ] * count}
	 * @param count how many boxes to test (must satisfy {@code boxes.length >= count * 6})
	 * @param query the query box {@code {minX,minY,minZ,maxX,maxY,maxZ}}
	 * @return mask[i] = 1 when boxes[i] intersects the query, else 0
	 */
	int[] overlapMask(double[] boxes, int count, double[] query);

	@Override
	void close();
}
