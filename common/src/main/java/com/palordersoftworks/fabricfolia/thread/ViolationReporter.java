/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.thread;

import com.palordersoftworks.fabricfolia.api.RegionInfo;
import com.palordersoftworks.fabricfolia.api.ThreadContextViolationException;
import com.palordersoftworks.fabricfolia.api.ValidationMode;

import java.util.function.BiConsumer;

/**
 * Applies the configured {@link ValidationMode} to detected ownership violations
 * and renders the actionable diagnostic (spec 8).
 *
 * <p><strong>Diagnostic requirements (spec 8):</strong> every report names the
 * operation attempted, the current execution context, the target's actual
 * ownership, the physical thread, and the correct scheduler entry point. A bare
 * exception with none of this is explicitly NOT acceptable output.</p>
 *
 * <p><strong>Report sink:</strong> injected as a BiConsumer<String, Throwable> so
 * common stays slf4j-free (the fabric module wires the server logger; tests
 * capture violations in memory). STRICT additionally throws, so call sites that
 * check STRICT directly get the throw without a formatting round-trip.</p>
 *
 * <p><strong>Spec 18 compliance:</strong> violations are never swallowed — WARN
 * logs and continues (operator-visible), STRICT throws (fail-fast), OFF is a
 * deliberate operator choice documented in the config comments. There is no
 * code path that catches a violation and proceeds silently.</p>
 */
public final class ViolationReporter {

	private final ValidationMode mode;
	private final BiConsumer<String, Throwable> sink;

	public ViolationReporter(ValidationMode mode, BiConsumer<String, Throwable> sink) {
		this.mode = mode;
		this.sink = sink;
	}

	public ValidationMode mode() {
		return mode;
	}

	/**
	 * Checks region-vs-region access. Returns normally if allowed; in WARN mode
	 * logs; in STRICT mode throws. In OFF mode this is a no-op that costs one
	 * enum compare.
	 */
	public void checkRegionAccess(String operation, RegionInfo target) {
		if (mode == ValidationMode.OFF) {
			return;
		}
		ThreadOwnership.Context current = ThreadOwnership.current();
		if (current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
				&& current.region() == target) {
			return; // same region object: same owner — the common case, checked first
		}
		String message = render(operation, current, target);
		switch (mode) {
			case STRICT -> throw new ThreadContextViolationException(message);
			case WARN -> sink.accept(message, null);
			default -> { /* OFF unreachable: early return above */ }
		}
	}

	/**
	 * Reports an illegal access detected from ANY context into region-owned
	 * state (used by hooks that guard owned objects, e.g. entity access from
	 * network threads or other mods' executors).
	 */
	public void reportIllegalAccess(String operation, RegionInfo target) {
		if (mode == ValidationMode.OFF) {
			return;
		}
		String message = render(operation, ThreadOwnership.current(), target);
		if (mode == ValidationMode.STRICT) {
			throw new ThreadContextViolationException(message);
		}
		sink.accept(message, null);
	}

	/** Renders the full diagnostic (spec 8 format). */
	public static String render(String operation, ThreadOwnership.Context current, RegionInfo target) {
		String currentDesc;
		if (current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
				&& current.region() != null) {
			currentDesc = "Region " + current.region().world() + ":" + current.region().regionId();
		} else {
			currentDesc = switch (current.kind()) {
				case GLOBAL -> "Global context";
				case NETWORK -> "Network thread";
				case IO -> "IO thread";
				default -> "Unknown context";
			};
		}
		String targetDesc = target == null ? "unknown"
				: "Region " + target.world() + ":" + target.regionId();

		return "Fabric-Folia Thread Context Violation\n\n"
				+ "Operation: " + operation + "\n"
				+ "Current execution context: " + currentDesc + "\n"
				+ "Target ownership: " + targetDesc + "\n"
				+ "Current thread: " + Thread.currentThread().getName() + "\n"
				+ "Expected context: " + targetDesc + "\n\n"
				+ "This access is unsafe because the target object is owned by another region.\n"
				+ "Schedule the operation through the appropriate RegionScheduler entry point.";
	}
}
