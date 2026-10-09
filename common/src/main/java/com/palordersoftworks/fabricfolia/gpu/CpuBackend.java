/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gpu;

import java.util.Arrays;

/**
 * Canonical CPU reference of the vanilla {@code AABB#intersects} predicate
 * (verified against the 26.2 jar): strict inequalities, so boxes that merely
 * touch faces do NOT intersect. This is the source of truth every GPU result
 * is compared against — the parity tests pin it against literal expectations.
 */
public final class CpuBackend implements GpuBackend {

	public static final CpuBackend INSTANCE = new CpuBackend();

	private CpuBackend() {
	}

	/** @return true when the box at {@code off} intersects the query box. */
	public static boolean intersects(double[] boxes, int off,
			double qMinX, double qMinY, double qMinZ, double qMaxX, double qMaxY, double qMaxZ) {
		return boxes[off] < qMaxX && boxes[off + 3] > qMinX
				&& boxes[off + 1] < qMaxY && boxes[off + 4] > qMinY
				&& boxes[off + 2] < qMaxZ && boxes[off + 5] > qMinZ;
	}

	/** @return the overlap mask for {@code count} boxes against {@code query}. */
	public static int[] mask(double[] boxes, int count, double[] query) {
		if (boxes == null || query == null || query.length < 6) {
			throw new IllegalArgumentException("overlapMask requires boxes and a 6-double query box");
		}
		if (count < 0 || boxes.length < count * 6) {
			throw new IllegalArgumentException("overlapMask count " + count
					+ " exceeds boxes capacity " + Arrays.deepToString(new Object[] {boxes.length / 6}));
		}
		int[] out = new int[count];
		for (int i = 0; i < count; i++) {
			if (intersects(boxes, i * 6, query[0], query[1], query[2], query[3], query[4], query[5])) {
				out[i] = 1;
			}
		}
		return out;
	}

	@Override
	public boolean isGpu() {
		return false;
	}

	@Override
	public String describe() {
		return "CPU reference";
	}

	@Override
	public int[] overlapMask(double[] boxes, int count, double[] query) {
		return mask(boxes, count, query);
	}

	@Override
	public void close() {
	}
}
