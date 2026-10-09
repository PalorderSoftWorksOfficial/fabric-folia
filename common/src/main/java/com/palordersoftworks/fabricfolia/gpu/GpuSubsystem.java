/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lifecycle and dispatch hub for the experimental OpenCL subsystem.
 *
 * <p>Contract (see COMPATIBILITY.md): GPU acceleration is opt-in via config,
 * isolated (no gameplay path depends on it), measurable (every dispatch and
 * every fallback is counted), and safely recoverable (any GPU failure —
 * discovery, build, verification, or a runtime enqueue error — degrades to
 * the CPU reference with the reason visible in metrics; queries never fail
 * because of the GPU).</p>
 *
 * <p>Why-comment on threading: initialize/close are lifecycle-singleton
 * operations (startup/shutdown, one caller); the hot path only reads the
 * volatile {@code backend} and adds to LongAdders, so dispatch costs one
 * volatile read plus counters and never contends on the lifecycle lock.</p>
 */
public final class GpuSubsystem {

	public enum State { UNINITIALIZED, DISABLED, CPU_FALLBACK, GPU_ACTIVE }

	public record Snapshot(State state, String description, String fallbackReason,
			List<GpuDevice> devices, boolean verified,
			long invocations, long boxesTested, long errors, long fallbacks) {
	}

	private static final System.Logger LOG = System.getLogger("Fabric-Folia");
	private static final Object LOCK = new Object();

	private static volatile State state = State.UNINITIALIZED;
	private static volatile GpuBackend backend = CpuBackend.INSTANCE;
	private static volatile String description = "not initialized";
	private static volatile String fallbackReason = "";
	private static volatile List<GpuDevice> devices = List.of();
	private static volatile boolean verified;

	private static final LongAdder invocations = new LongAdder();
	private static final LongAdder boxesTested = new LongAdder();
	private static final LongAdder errors = new LongAdder();
	private static final LongAdder fallbacks = new LongAdder();
	private static final LongAdder gpuNanos = new LongAdder();
	private static final LongAdder cpuNanos = new LongAdder();

	private GpuSubsystem() {
	}

	/**
	 * Discovers devices, opens the selected one, and (when requested) runs the
	 * parity battery before the backend is allowed to serve anything. Any
	 * failure lands in CPU_FALLBACK with the reason recorded — never an
	 * exception out of this method, because GPU availability must not be able
	 * to break server startup.
	 */
	public static Snapshot initialize(boolean enabled, String devicePref, boolean verifyOnBoot) {
		synchronized (LOCK) {
			closeLocked();
			invocations.reset();
			boxesTested.reset();
			errors.reset();
			fallbacks.reset();
			gpuNanos.reset();
			cpuNanos.reset();
			verified = false;
			if (!enabled) {
				state = State.DISABLED;
				description = "disabled by config (gpu.enabled=false)";
				fallbackReason = "";
				LOG.log(System.Logger.Level.INFO,
						"GPU subsystem disabled; CPU reference backend active");
				return snapshot();
			}
			try {
				OpenClRuntime.Discovery discovery = OpenClRuntime.discover();
				devices = discovery.devices().stream().map(OpenClRuntime.DeviceHandle::info).toList();
				if (discovery.devices().isEmpty()) {
					throw new GpuException(discovery.failure());
				}
				OpenClRuntime.DeviceHandle selected = OpenClRuntime.select(discovery.devices(), devicePref);
				OpenClRuntime runtime = OpenClRuntime.open(selected);
				if (verifyOnBoot) {
					String mismatch = GpuVerification.firstMismatch(runtime);
					if (mismatch != null) {
						runtime.close();
						throw new GpuException("post-build parity verification failed: " + mismatch);
					}
					verified = true;
				}
				backend = runtime;
				state = State.GPU_ACTIVE;
				description = runtime.describe();
				fallbackReason = "";
				LOG.log(System.Logger.Level.INFO, "GPU subsystem active: " + description);
			} catch (Throwable t) {
				backend = CpuBackend.INSTANCE;
				state = State.CPU_FALLBACK;
				description = "CPU reference (fallback)";
				fallbackReason = String.valueOf(t.getMessage());
				fallbacks.increment();
				LOG.log(System.Logger.Level.WARNING,
						"GPU subsystem unavailable, falling back to CPU reference: " + t.getMessage());
			}
			return snapshot();
		}
	}

	/**
	 * Dispatches one overlap query to the active backend. On any GPU-path
	 * failure the backend degrades to the CPU reference (counted and logged)
	 * and the CPU result is returned, so callers always get a correct answer.
	 */
	public static int[] overlapMask(double[] boxes, int count, double[] query) {
		GpuBackend b = backend;
		long t0 = System.nanoTime();
		int[] result;
		try {
			result = b.overlapMask(boxes, count, query);
		} catch (Throwable t) {
			if (!b.isGpu()) {
				// CPU path only throws for caller bugs (illegal arguments);
				// those must propagate, not be masked as a GPU problem.
				throw t instanceof RuntimeException re ? re : new GpuException("CPU backend failed: " + t, t);
			}
			errors.increment();
			degrade(b, t);
			result = CpuBackend.INSTANCE.overlapMask(boxes, count, query);
			b = CpuBackend.INSTANCE;
		}
		long dt = System.nanoTime() - t0;
		invocations.increment();
		boxesTested.add(count);
		if (b.isGpu()) {
			gpuNanos.add(dt);
		} else {
			cpuNanos.add(dt);
		}
		return result;
	}

	private static void degrade(GpuBackend failed, Throwable cause) {
		synchronized (LOCK) {
			failed.close();
			if (backend == failed) {
				backend = CpuBackend.INSTANCE;
				state = State.CPU_FALLBACK;
				description = "CPU reference (degraded after runtime failure)";
				fallbackReason = "runtime failure: " + cause;
				fallbacks.increment();
			}
		}
		LOG.log(System.Logger.Level.ERROR,
				"GPU backend failed mid-flight (" + cause.getMessage()
						+ "); switched to CPU reference for all further queries");
	}

	/** @return an immutable view of state, devices, and counters. */
	public static Snapshot snapshot() {
		return new Snapshot(state, description, fallbackReason, devices, verified,
				invocations.sum(), boxesTested.sum(), errors.sum(), fallbacks.sum());
	}

	/** @return the current state. */
	public static State state() {
		return state;
	}

	/** Releases GPU resources; dispatch returns to the CPU reference. */
	public static void close() {
		synchronized (LOCK) {
			closeLocked();
		}
	}

	private static void closeLocked() {
		GpuBackend b = backend;
		backend = CpuBackend.INSTANCE;
		if (b.isGpu()) {
			b.close();
		}
		state = State.UNINITIALIZED;
		description = "not initialized";
		fallbackReason = "";
		devices = List.of();
		verified = false;
	}

	/** @return {@code /folia metrics} lines (state, counters, timings). */
	public static List<String> metricsLines() {
		List<String> lines = new ArrayList<>();
		lines.add("GPU: state=" + state + (state == State.GPU_ACTIVE
				? " backend=" + description : " reason=" + fallbackReason));
		lines.add("GPU: invocations=" + invocations.sum() + " boxes=" + boxesTested.sum()
				+ " errors=" + errors.sum() + " fallbacks=" + fallbacks.sum()
				+ String.format(Locale.ROOT, " gpuMs=%.1f cpuMs=%.1f",
						gpuNanos.sum() / 1e6, cpuNanos.sum() / 1e6));
		if (!devices.isEmpty()) {
			lines.add("GPU: devices=" + describeDevices());
		}
		return lines;
	}

	/** @return a single-line device list for diagnostics commands. */
	public static String describeDevices() {
		StringBuilder sb = new StringBuilder();
		for (GpuDevice d : devices) {
			if (sb.length() > 0) {
				sb.append("; ");
			}
			sb.append(d.gpu() ? "[GPU] " : "[CPU] ").append(d.summary());
		}
		return sb.toString();
	}

	/**
	 * Measures CPU vs GPU overlap-mask throughput on seeded grid-aligned data
	 * and re-checks parity. Exposed for {@code /folia gpu bench}; returns one
	 * line per measurement plus a parity verdict.
	 */
	public static List<String> bench(int boxes) {
		java.util.Random rng = new java.util.Random(20261009L);
		double[] data = GpuVerification.gridBoxes(rng, boxes);
		double[] query = GpuVerification.gridQuery(rng);

		CpuBackend.INSTANCE.overlapMask(data, boxes, query);
		long t0 = System.nanoTime();
		int[] cpuMask = CpuBackend.INSTANCE.overlapMask(data, boxes, query);
		long cpuNs = System.nanoTime() - t0;

		List<String> lines = new ArrayList<>();
		lines.add(String.format(Locale.ROOT, "bench n=%d cpu=%.2f ms (%.1f boxes/ms)",
				boxes, cpuNs / 1e6, boxes / (cpuNs / 1e6)));

		if (state == State.GPU_ACTIVE) {
			overlapMask(data, boxes, query);
			t0 = System.nanoTime();
			int[] gpuMask = overlapMask(data, boxes, query);
			long gpuNs = System.nanoTime() - t0;
			boolean parity = java.util.Arrays.equals(cpuMask, gpuMask);
			lines.add(String.format(Locale.ROOT, "bench n=%d gpu=%.2f ms (%.1f boxes/ms) parity=%s",
					boxes, gpuNs / 1e6, boxes / (gpuNs / 1e6), parity ? "EXACT" : "MISMATCH"));
		} else {
			lines.add("bench: GPU not active (" + state + ") — " + fallbackReason);
		}
		return lines;
	}
}
