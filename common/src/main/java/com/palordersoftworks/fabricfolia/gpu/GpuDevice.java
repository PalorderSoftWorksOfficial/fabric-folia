/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gpu;

/**
 * Immutable description of one discovered OpenCL device. {@code fp64} is
 * mandatory for this subsystem: the kernels mirror Minecraft's double-precision
 * AABB math, so a device without doubles can never be parity-exact and is
 * treated as unusable.
 */
public record GpuDevice(String platform, String name, String vendor, boolean gpu,
		int computeUnits, long globalMemBytes, int clockMhz, boolean fp64) {

	public String summary() {
		return name + " [" + vendor + "] (CU " + computeUnits + ", "
				+ String.format(java.util.Locale.ROOT, "%.1f", globalMemBytes / (1024.0 * 1024.0 * 1024.0))
				+ " GiB, " + clockMhz + " MHz, fp64=" + fp64 + ")";
	}
}
