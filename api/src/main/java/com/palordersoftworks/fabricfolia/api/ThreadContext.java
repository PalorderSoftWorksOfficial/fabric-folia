/*
 * Fabric-Folia — regionized multithreaded server execution for vanilla Minecraft
 * under Fabric Loader.
 *
 * Copyright 2026 Palorder Softworks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * ThreadContext.java carries the full header; this comment block documents the
 * type itself. (All API files ship the full Apache-2.0 header.)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.palordersoftworks.fabricfolia.api;

import com.palordersoftworks.fabricfolia.api.annotations.AnyThread;

/**
 * The public thread-ownership diagnostics facility (spec section 8).
 *
 * <p>From any point in the code, this interface can answer: what region, if any,
 * "owns" the current execution context right now? It is exposed publicly so other
 * mods can assert their own thread-safety assumptions instead of guessing.</p>
 *
 * <p><strong>Context kinds:</strong> REGION, GLOBAL, NETWORK, IO, ASYNC, and
 * UNKNOWN. ASYNC (Folia's async context) owns no region or global state —
 * async code reaches world/server state only through the scheduler handoffs
 * (ASYNC→REGION, ASYNC→GLOBAL).</p>
 *
 * <p><strong>Threading contract:</strong> all methods are {@code @AnyThread} and
 * non-blocking. They read thread-local ownership state and compare it to targets
 * passed in; they never mutate gameplay state.</p>
 */
@AnyThread
public interface ThreadContext {
	/**
	 * Describes what kind of execution context the current thread is running in.
	 */
	enum Kind {
		/** Executing a region tick or a task inside a region's task queue. */
		REGION,
		/** Executing global work on the global execution context. */
		GLOBAL,
		/** Executing on a Netty network event loop thread. */
		NETWORK,
		/** Executing chunk/entity save-load work on an IO worker. */
		IO,
		/** Executing on the async scheduler's dedicated pool (Folia's async context). */
		ASYNC,
		/** Any other thread: unknown mod executor, plugin pool, etc. */
		UNKNOWN
	}

	/** @return the kind of context the current thread is in. Never null. */
	Kind kind();

	/**
	 * @return the region that owns the current execution context, or empty if
	 * the current thread is not executing region-owned work. The returned view
	 * is safe to hold and read from any thread afterwards.
	 */
	java.util.Optional<RegionInfo> currentRegion();

	/**
	 * @return the current thread's name — included in every diagnostic report so
	 * operators see both the logical context and the physical thread.
	 */
	String threadName();

	/**
	 * Checks whether the current context may directly access state owned by the
	 * given region without scheduling. This is the predicate underlying every
	 * ownership assertion.
	 *
	 * @param targetRegion the region whose ownership is being questioned
	 * @return true only if the current context IS that region's owning context
	 */
	boolean mayAccessRegion(RegionInfo targetRegion);

	/**
	 * Throws {@link ThreadContextViolationException} if the current context may
	 * not directly access state owned by {@code targetRegion}. Respect's the
	 * configured validation mode: in OFF mode this is a no-op, in WARN mode a
	 * violation is logged with full diagnostics instead of thrown.
	 *
	 * @param operation  human-readable description of the operation attempted,
	 *                   used verbatim in diagnostics
	 * @param targetRegion the region that owns the state being accessed
	 * @throws ThreadContextViolationException in STRICT mode on violation
	 */
	void assertRegionAccess(String operation, RegionInfo targetRegion)
			throws ThreadContextViolationException;
}
