/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gpu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the CPU reference predicate against hand-computed literal expectations
 * (the trust anchor for GPU parity) and covers the subsystem's opt-in /
 * fallback / metrics contracts on machines with or without OpenCL.
 */
class AabbOverlapTest {

	@AfterEach
	void tearDown() {
		GpuSubsystem.close();
	}

	private static void assertMask(int[] expected, double[] boxes, int count, double[] query) {
		assertArrayEquals(expected, CpuBackend.mask(boxes, count, query));
		assertArrayEquals(expected, GpuSubsystem.overlapMask(boxes, count, query),
				"subsystem dispatch must equal the reference");
	}

	@Test
	void strictIntersectionsPinnedToVanillaSemantics() {
		double[] unit = {0, 0, 0, 1, 1, 1};
		assertMask(new int[] {1}, unit, 1, new double[] {0, 0, 0, 1, 1, 1});
		assertMask(new int[] {0}, unit, 1, new double[] {1, 0, 0, 2, 1, 1});
		assertMask(new int[] {0}, unit, 1, new double[] {-1, 0, 0, 0, 1, 1});
		assertMask(new int[] {1}, unit, 1, new double[] {1 - 1e-9, 0, 0, 2, 1, 1});
		assertMask(new int[] {1}, unit, 1, new double[] {0.25, 0.25, 0.25, 0.75, 0.75, 0.75});
		assertMask(new int[] {1}, unit, 1, new double[] {-1, -1, -1, 2, 2, 2});
		assertMask(new int[] {0}, unit, 1, new double[] {0, 1, 0, 1, 2, 1});
	}

	@Test
	void extremeMagnitudesAndNanArePinningCases() {
		assertMask(new int[] {0}, new double[] {Double.NaN, 0, 0, 1, 1, 1}, 1,
				new double[] {-1, -1, -1, 2, 2, 2});
		assertMask(new int[] {0}, new double[] {0, 0, 0, 1, 1, 1}, 1,
				new double[] {Double.NaN, 0, 0, 1, 1, 1});
		assertMask(new int[] {1}, new double[] {-1e308, -1e308, -1e308, 1e308, 1e308, 1e308}, 1,
				new double[] {0, 0, 0, 1, 1, 1});
		assertMask(new int[] {0}, new double[] {1e308, 1e308, 1e308, 1.5e308, 1.5e308, 1.5e308}, 1,
				new double[] {-1e308, -1e308, -1e308, 1e308, 1e308, 1e308});
	}

	@Test
	void rowOfBoxesWithSpanningQuery() {
		double[] boxes = new double[6 * 12];
		for (int i = 0; i < 12; i++) {
			boxes[i * 6] = i;
			boxes[i * 6 + 3] = i + 1;
			boxes[i * 6 + 4] = 1;
			boxes[i * 6 + 5] = 1;
		}
		assertMask(new int[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0}, boxes, 12,
				new double[] {0.5, 0, 0, 9.5, 1, 1});
	}

	@Test
	void seededRandomMatchesNaiveReimplmentation() {
		Random rng = new Random(7L);
		double[] boxes = GpuVerification.gridBoxes(rng, 5000);
		double[] query = GpuVerification.gridQuery(rng);
		int[] naive = new int[5000];
		for (int i = 0; i < 5000; i++) {
			int o = i * 6;
			naive[i] = boxes[o] < query[3] && boxes[o + 3] > query[0]
					&& boxes[o + 1] < query[4] && boxes[o + 4] > query[1]
					&& boxes[o + 2] < query[5] && boxes[o + 5] > query[2] ? 1 : 0;
		}
		assertArrayEquals(naive, CpuBackend.mask(boxes, 5000, query));
	}

	@Test
	void illegalArgumentsPropagateInsteadOfMaskingAsGpuProblems() {
		assertThrows(IllegalArgumentException.class,
				() -> CpuBackend.mask(new double[6], 2, new double[6]));
		assertThrows(IllegalArgumentException.class,
				() -> CpuBackend.mask(new double[12], -1, new double[6]));
		assertThrows(IllegalArgumentException.class,
				() -> CpuBackend.mask(new double[6], 1, new double[3]));
		GpuSubsystem.initialize(false, "", true);
		assertThrows(IllegalArgumentException.class,
				() -> GpuSubsystem.overlapMask(new double[6], 2, new double[6]));
	}

	@Test
	void disabledByDefaultStaysOnCpuAndAnswersCorrectly() {
		GpuSubsystem.Snapshot snap = GpuSubsystem.initialize(false, "", true);
		assertEquals(GpuSubsystem.State.DISABLED, snap.state());
		assertFalse(snap.fallbackReason().contains("OpenCL"),
				"disabled-by-config must not even probe OpenCL");
		double[] boxes = {0, 0, 0, 1, 1, 1, 5, 5, 5, 6, 6, 6};
		assertMask(new int[] {1, 0}, boxes, 2, new double[] {0.5, 0.5, 0.5, 1.5, 1.5, 1.5});
		assertTrue(GpuSubsystem.metricsLines().get(0).contains("DISABLED"));
	}

	@Test
	void impossibleDevicePreferenceFallsBackWithVisibleReason() {
		GpuSubsystem.Snapshot snap = GpuSubsystem.initialize(true, "no-such-device-9f3c", true);
		assertEquals(GpuSubsystem.State.CPU_FALLBACK, snap.state());
		assertFalse(snap.fallbackReason().isBlank(), "fallback must record a reason");
		assertFalse(snap.verified());
		double[] boxes = {0, 0, 0, 2, 2, 2};
		assertArrayEquals(new int[] {1}, GpuSubsystem.overlapMask(boxes, 1, new double[] {1, 1, 1, 3, 3, 3}));
	}

	@Test
	void benchReportsStateAndProducesLines() {
		GpuSubsystem.initialize(false, "", true);
		assertTrue(GpuSubsystem.bench(4096).get(0).contains("bench n=4096"));
	}

	@Test
	void verificationBatteryRejectsDivergentBackend() {
		GpuBackend liar = new GpuBackend() {
			@Override public boolean isGpu() { return true; }
			@Override public String describe() { return "lying backend"; }
			@Override public int[] overlapMask(double[] boxes, int count, double[] query) {
				int[] out = CpuBackend.mask(boxes, count, query);
				if (count > 0 && out.length > 0) {
					out[0] = 1 - out[0];
				}
				return out;
			}
			@Override public void close() { }
		};
		String mismatch = GpuVerification.firstMismatch(liar);
		assertTrue(mismatch != null && mismatch.contains("cpu[0]="),
				"verification must catch and locate a divergent backend, got: " + mismatch);
	}

	@Test
	void verificationBatteryPassesHonestBackend() {
		assertEquals(null, GpuVerification.firstMismatch(CpuBackend.INSTANCE));
	}

	@Test
	void snapshotCountersOnlyAdvanceThroughDispatch() {
		GpuSubsystem.initialize(false, "", true);
		long before = GpuSubsystem.snapshot().invocations();
		GpuSubsystem.bench(1024);
		long after = GpuSubsystem.snapshot().invocations();
		assertTrue(after >= before, "bench dispatches must be counted");
		assertTrue(GpuSubsystem.describeDevices().isEmpty(), "disabled subsystem has no devices");
		assertFalse(GpuSubsystem.metricsLines().isEmpty());
	}
}
