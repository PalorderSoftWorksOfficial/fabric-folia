/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gpu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live GPU-vs-CPU parity: runs only when the machine exposes an OpenCL device
 * (skipped via JUnit assumption elsewhere, e.g. headless CI agents). The same
 * battery runs at server boot when gpu.verify-on-boot is true, so a green CI
 * skip never silently substitutes for the runtime check.
 */
class GpuParityTest {

	@AfterEach
	void tearDown() {
		GpuSubsystem.close();
	}

	@Test
	void gpuKernelMatchesCpuReferenceBitExactly() {
		GpuSubsystem.Snapshot snap = GpuSubsystem.initialize(true, "", true);
		Assumptions.assumeTrue(snap.state() == GpuSubsystem.State.GPU_ACTIVE,
				"no OpenCL device on this machine: " + snap.fallbackReason());
		assertTrue(snap.verified(), "boot verification must have run before dispatch");

		Random rng = new Random(20261009L);
		for (int round = 0; round < 8; round++) {
			int n = 1 + rng.nextInt(30000);
			double[] boxes = GpuVerification.gridBoxes(rng, n);
			double[] query = GpuVerification.gridQuery(rng);
			assertArrayEquals(CpuBackend.mask(boxes, n, query),
					GpuSubsystem.overlapMask(boxes, n, query),
					"round " + round + " (n=" + n + ") diverged from the CPU reference");
		}

		double[] extreme = GpuVerification.extremeBoxes(new Random(42L), 2048);
		double[] wide = {-1e308, -1e308, -1e308, 1e308, 1e308, 1e308};
		assertArrayEquals(CpuBackend.mask(extreme, 2048, wide),
				GpuSubsystem.overlapMask(extreme, 2048, wide),
				"extreme magnitudes must stay bit-exact");

		GpuSubsystem.Snapshot after = GpuSubsystem.snapshot();
		assertEquals(GpuSubsystem.State.GPU_ACTIVE, after.state());
		assertTrue(after.invocations() >= 9, "dispatches must be counted");
		assertEquals(0, after.errors(), "no dispatch may error on a healthy device");
	}

	@Test
	void cpuDeviceSelectionWorksWithoutAGpu() {
		// "cpu" selects a CPU OpenCL device when present; on machines with no
		// OpenCL at all this must land in CPU_FALLBACK, never throw.
		GpuSubsystem.Snapshot snap = GpuSubsystem.initialize(true, "cpu", false);
		assertTrue(snap.state() == GpuSubsystem.State.GPU_ACTIVE
						|| snap.state() == GpuSubsystem.State.CPU_FALLBACK,
				"unexpected state: " + snap.state());
		if (snap.state() == GpuSubsystem.State.GPU_ACTIVE) {
			assertTrue(snap.description().contains("/") || snap.description().length() > 0);
		} else {
			assertTrue(snap.fallbackReason().length() > 0);
		}
	}
}
