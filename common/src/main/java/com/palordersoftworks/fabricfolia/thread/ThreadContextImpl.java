/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.thread;

import com.palordersoftworks.fabricfolia.api.RegionInfo;
import com.palordersoftworks.fabricfolia.api.ThreadContext;

import java.util.Optional;

/**
 * Implementation of the public {@link ThreadContext} diagnostics API on top of
 * {@link ThreadOwnership} (registry) and {@link ViolationReporter} (policy).
 *
 * <p>All methods are non-blocking and thread-safe: they read thread-local state
 * and compare references. The reference comparison is valid because region
 * identity is object identity within a run — the regionizer never swaps a
 * Region object's identity for the same logical region (merge/split create NEW
 * region objects and kill the old ones, and dead objects keep their ids for
 * diagnostics).</p>
 */
public final class ThreadContextImpl implements ThreadContext {

	private final ViolationReporter reporter;

	public ThreadContextImpl(ViolationReporter reporter) {
		this.reporter = reporter;
	}

	@Override
	public Kind kind() {
		return ThreadOwnership.current().kind();
	}

	@Override
	public Optional<RegionInfo> currentRegion() {
		return ThreadOwnership.currentRegion();
	}

	@Override
	public String threadName() {
		return Thread.currentThread().getName();
	}

	@Override
	public boolean mayAccessRegion(RegionInfo targetRegion) {
		if (targetRegion == null) {
			return false;
		}
		ThreadOwnership.Context current = ThreadOwnership.current();
		return current.kind() == Kind.REGION && current.region() == targetRegion;
	}

	@Override
	public void assertRegionAccess(String operation, RegionInfo targetRegion) {
		reporter.checkRegionAccess(operation, targetRegion);
	}
}
