/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.palordersoftworks.fabricfolia.api;

/**
 * Validation mode for thread-ownership diagnostics (spec section 8).
 *
 * <ul>
 *   <li>{@link #STRICT} — violations throw {@link ThreadContextViolationException}.
 *       Default for development builds: catch ownership mistakes at the exact
 *       call site during development, at full runtime cost per checked access.</li>
 *   <li>{@link #WARN} — violations are logged with the full diagnostic context and
 *       execution continues. Intended production default: real per-access STRICT
 *       checking has measurable overhead; the production default and its measured
 *       justification are documented in THREADING.md.</li>
 *   <li>{@link #OFF} — no checks, no runtime cost. For production operators who
 *       have measured and accepted the risk.</li>
 * </ul>
 */
public enum ValidationMode {
	STRICT,
	WARN,
	OFF
}
