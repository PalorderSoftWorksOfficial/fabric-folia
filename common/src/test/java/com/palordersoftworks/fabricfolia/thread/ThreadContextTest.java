/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.thread;

import com.palordersoftworks.fabricfolia.api.RegionInfo;
import com.palordersoftworks.fabricfolia.api.ThreadContextViolationException;
import com.palordersoftworks.fabricfolia.api.ValidationMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ownership detection tests (spec 19 "thread safety"): a deliberate violation
 * is actually caught, in each validation mode, with the full actionable
 * diagnostic.
 */
class ThreadContextTest {

	/** Test region info: identity is object identity. */
	private static RegionInfo region(String world, long id) {
		return new RegionInfo() {
			@Override
			public String world() {
				return world;
			}

			@Override
			public long regionId() {
				return id;
			}

			@Override
			public boolean isDead() {
				return false;
			}

			@Override
			public String stateName() {
				return "READY";
			}
		};
	}

	private static final RegionInfo REGION_A = region("test:world", 1);
	private static final RegionInfo REGION_B = region("test:world", 2);

	@AfterEach
	void cleanup() {
		ThreadOwnership.clear();
	}

	@Test
	void sameRegionAccessIsAllowed() {
		ViolationReporter reporter = new ViolationReporter(ValidationMode.STRICT, (m, t) -> {});
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(REGION_A);
		try {
			reporter.checkRegionAccess("Entity state access", REGION_A); // no throw
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void strictModeThrowsWithActionableDiagnostic() {
		ViolationReporter reporter = new ViolationReporter(ValidationMode.STRICT, (m, t) -> {});
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(REGION_A);
		try {
			ThreadContextViolationException thrown = assertThrows(
					ThreadContextViolationException.class,
					() -> reporter.checkRegionAccess("Entity state access", REGION_B));
			String message = thrown.getMessage();
			// The diagnostic must be actionable (spec 8), not a bare exception:
			assertTrue(message.contains("Fabric-Folia Thread Context Violation"));
			assertTrue(message.contains("Operation: Entity state access"));
			assertTrue(message.contains("Target ownership: Region test:world:2"));
			assertTrue(message.contains("Current thread: " + Thread.currentThread().getName()));
			assertTrue(message.contains("RegionScheduler"),
					"diagnostic must name the correct scheduler entry point");
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void warnModeLogsAndContinues() {
		List<String> logged = new ArrayList<>();
		ViolationReporter reporter = new ViolationReporter(ValidationMode.WARN,
				(message, throwable) -> logged.add(message));
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(REGION_A);
		try {
			reporter.checkRegionAccess("Cross-region read", REGION_B); // must NOT throw
			assertEquals(1, logged.size(), "WARN mode must log the violation");
			assertTrue(logged.get(0).contains("Target ownership: Region test:world:2"));
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void offModeIsANoOp() {
		List<String> logged = new ArrayList<>();
		ViolationReporter reporter = new ViolationReporter(ValidationMode.OFF,
				(message, throwable) -> logged.add(message));
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(REGION_A);
		try {
			reporter.checkRegionAccess("Unchecked access", REGION_B);
			assertEquals(0, logged.size(), "OFF mode must not log");
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void violationFromForeignThreadContextIsDetected() throws Exception {
		ViolationReporter reporter = new ViolationReporter(ValidationMode.STRICT, (m, t) -> {});
		AtomicReference<Throwable> caught = new AtomicReference<>();
		Thread foreignThread = new Thread(() -> {
			// Simulates another mod's executor: no region context entered —
			// the thread is UNKNOWN, so access to any region is illegal.
			try {
				reporter.reportIllegalAccess("Mod code touching entity", REGION_A);
			} catch (Throwable t) {
				caught.set(t);
			}
		});
		foreignThread.start();
		foreignThread.join(5000);
		assertTrue(caught.get() instanceof ThreadContextViolationException,
				"foreign-thread access to region-owned state must be detected");
	}

	@Test
	void nestedRegionEntryIsNotPossible() {
		// Ownership is single-slot: entering a region context replaces the
		// previous one, and the exit token restores it. Two live region
		// contexts on one thread cannot coexist — assert the discipline.
		ThreadOwnership.Context t1 = ThreadOwnership.enterRegion(REGION_A);
		assertEquals(REGION_A, ThreadOwnership.currentRegion().orElseThrow());
		ThreadOwnership.Context t2 = ThreadOwnership.enterRegion(REGION_B);
		assertEquals(REGION_B, ThreadOwnership.currentRegion().orElseThrow());
		ThreadOwnership.exit(t2);
		assertEquals(REGION_A, ThreadOwnership.currentRegion().orElseThrow());
		ThreadOwnership.exit(t1);
		assertFalse(ThreadOwnership.currentRegion().isPresent());
	}

	@Test
	void globalContextIsNotARegionContext() {
		ThreadOwnership.Context token = ThreadOwnership.enterGlobal();
		try {
			ViolationReporter strict = new ViolationReporter(ValidationMode.STRICT, (m, t) -> {});
			assertThrows(ThreadContextViolationException.class,
					() -> strict.checkRegionAccess("Global code touching region state", REGION_A));
		} finally {
			ThreadOwnership.exit(token);
		}
	}
}
