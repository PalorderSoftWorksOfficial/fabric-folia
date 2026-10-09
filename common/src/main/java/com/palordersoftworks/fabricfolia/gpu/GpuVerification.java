/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Boot-time (and test-time) parity battery: runs the GPU kernel against the
 * CPU reference on hand-picked edge cases plus seeded random batches, and
 * reports the FIRST mismatch. A GPU backend that fails here is never allowed
 * to serve a query — the subsystem falls back to the CPU reference with the
 * mismatch description visible in the fallback reason.
 */
final class GpuVerification {

	private GpuVerification() {
	}

	record TestCase(String name, double[] boxes, int count, double[] query) {
	}

	/**
	 * @return null when the GPU backend matches the CPU reference on every
	 * case, else a description of the first mismatch found.
	 */
	static String firstMismatch(GpuBackend gpu) {
		List<TestCase> cases = edgeCases();
		for (int batch = 0; batch < 4; batch++) {
			Random rng = new Random(20261009L + batch);
			int n = 1024 * (batch + 1);
			cases.add(new TestCase("random grid-aligned batch (seed=" + (20261009L + batch) + ", n=" + n + ")",
					gridBoxes(rng, n), n, gridQuery(rng)));
		}
		Random extreme = new Random(42L);
		cases.add(new TestCase("extreme magnitudes (NaN/Inf/1e308)", extremeBoxes(extreme, 2048), 2048,
				new double[] {-1e308, -1e308, -1e308, 1e308, 1e308, 1e308}));

		for (TestCase c : cases) {
			int[] expected = CpuBackend.mask(c.boxes(), c.count(), c.query());
			int[] actual;
			try {
				actual = gpu.overlapMask(c.boxes(), c.count(), c.query());
			} catch (Throwable t) {
				return c.name() + ": threw " + t;
			}
			if (!Arrays.equals(expected, actual)) {
				int diff = -1;
				for (int i = 0; i < expected.length; i++) {
					if (expected[i] != actual[i]) {
						diff = i;
						break;
					}
				}
				return c.name() + ": cpu[" + diff + "]=" + expected[diff]
						+ " gpu[" + diff + "]=" + (diff < actual.length ? actual[diff] : "<missing>");
			}
		}
		return null;
	}

	static List<TestCase> edgeCases() {
		List<TestCase> cases = new ArrayList<>();
		double[] unit = {0, 0, 0, 1, 1, 1};
		cases.add(new TestCase("identical boxes (interiors overlap)", unit, 1,
				new double[] {0, 0, 0, 1, 1, 1}));
		cases.add(new TestCase("shared +X face", unit, 1, new double[] {1, 0, 0, 2, 1, 1}));
		cases.add(new TestCase("shared -X face", unit, 1, new double[] {-1, 0, 0, 0, 1, 1}));
		cases.add(new TestCase("epsilon overlap", unit, 1, new double[] {1 - 1e-9, 0, 0, 2, 1, 1}));
		cases.add(new TestCase("containment (query inside box)", unit, 1,
				new double[] {0.25, 0.25, 0.25, 0.75, 0.75, 0.75}));
		cases.add(new TestCase("containment (box inside query)", unit, 1,
				new double[] {-1, -1, -1, 2, 2, 2}));
		cases.add(new TestCase("separated on Y only", unit, 1, new double[] {0, 1, 0, 1, 2, 1}));
		cases.add(new TestCase("negative coords", new double[] {-5, -5, -5, -4, -4, -4}, 1,
				new double[] {-4.5, -4.5, -4.5, -3.5, -3.5, -3.5}));
		cases.add(new TestCase("NaN box", new double[] {Double.NaN, 0, 0, 1, 1, 1}, 1,
				new double[] {-1, -1, -1, 2, 2, 2}));
		cases.add(new TestCase("NaN query", unit, 1,
				new double[] {Double.NaN, 0, 0, 1, 1, 1}));
		cases.add(new TestCase("overflowing spans", new double[] {-1e308, -1e308, -1e308, 1e308, 1e308, 1e308}, 1,
				new double[] {0, 0, 0, 1, 1, 1}));
		cases.add(new TestCase("adjacent at 1e308 boundary", new double[] {1e308, 1e308, 1e308, 1.5e308, 1.5e308, 1.5e308}, 1,
				new double[] {-1e308, -1e308, -1e308, 1e308, 1e308, 1e308}));
		cases.add(new TestCase("empty query (degenerate)", unit, 1,
				new double[] {0.5, 0.5, 0.5, 0.5, 0.5, 0.5}));
		cases.add(new TestCase("degenerate zero-size box vs query", new double[] {0.5, 0.5, 0.5, 0.5, 0.5, 0.5}, 1,
				new double[] {0, 0, 0, 1, 1, 1}));
		double[] mixed = new double[6 * 12];
		for (int i = 0; i < 12; i++) {
			mixed[i * 6] = i;
			mixed[i * 6 + 1] = 0;
			mixed[i * 6 + 2] = 0;
			mixed[i * 6 + 3] = i + 1;
			mixed[i * 6 + 4] = 1;
			mixed[i * 6 + 5] = 1;
		}
		// query spans [0.5, 9.5): hits boxes 0..9 strictly; 9 touches at 9.5? no — box 9 is [9,10): 9 < 9.5 yes.
		cases.add(new TestCase("row of unit boxes, spanning query", mixed, 12,
				new double[] {0.5, 0, 0, 9.5, 1, 1}));
		cases.add(new TestCase("zero count", unit, 0, new double[] {0, 0, 0, 1, 1, 1}));
		return cases;
	}

	/** Random boxes snapped to a 1/16 grid so exact face-touching is common. */
	static double[] gridBoxes(Random rng, int n) {
		double[] boxes = new double[n * 6];
		for (int i = 0; i < n; i++) {
			double size = 0.05 + rng.nextDouble() * 4;
			double x = (rng.nextInt(4000) - 2000) + rng.nextInt(16) / 16.0;
			double y = (rng.nextInt(256) - 64) + rng.nextInt(16) / 16.0;
			double z = (rng.nextInt(4000) - 2000) + rng.nextInt(16) / 16.0;
			boxes[i * 6] = x;
			boxes[i * 6 + 1] = y;
			boxes[i * 6 + 2] = z;
			boxes[i * 6 + 3] = x + size;
			boxes[i * 6 + 4] = y + size;
			boxes[i * 6 + 5] = z + size;
		}
		return boxes;
	}

	static double[] gridQuery(Random rng) {
		double size = 1 + rng.nextDouble() * 8;
		double x = (rng.nextInt(4000) - 2000) + rng.nextInt(16) / 16.0;
		double y = (rng.nextInt(256) - 64) + rng.nextInt(16) / 16.0;
		double z = (rng.nextInt(4000) - 2000) + rng.nextInt(16) / 16.0;
		return new double[] {x, y, z, x + size, y + size, z + size};
	}

	static double[] extremeBoxes(Random rng, int n) {
		double[] boxes = new double[n * 6];
		double[] pool = {0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE,
				-Double.MAX_VALUE, 1e308, -1e308, 1.0, -1.0, 0.5, Double.NaN};
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < 6; j++) {
				boxes[i * 6 + j] = pool[rng.nextInt(pool.length)];
			}
		}
		return boxes;
	}
}
