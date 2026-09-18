/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.ThreadContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Legacy-dispatch policy behavior tests (spec 39): per-mod declarations
 * resolve ahead of the default, the default is configurable, and the
 * network-thread direct-run gate matches the architecture's rules.
 */
class LegacyDispatchPolicyTest {

	@Test
	void declaredModOverridesDefault() {
		LegacyDispatchPolicy policy = new LegacyDispatchPolicy();

		assertEquals(LegacyDispatchPolicy.Destination.RUN_ON_GLOBAL,
				policy.resolve("some.mod"), "undeclared mods use the default");
		assertNull(policy.declaredFor("some.mod"));

		policy.declare("some.mod", LegacyDispatchPolicy.Destination.RUN_DIRECT);
		assertEquals(LegacyDispatchPolicy.Destination.RUN_DIRECT,
				policy.resolve("some.mod"), "declaration wins over the default");
		assertEquals(LegacyDispatchPolicy.Destination.RUN_ON_GLOBAL,
				policy.resolve("other.mod"));

		var declarations = policy.declarations();
		assertEquals(1, declarations.size());
		assertEquals(LegacyDispatchPolicy.Destination.RUN_DIRECT,
				declarations.get("some.mod"));
	}

	@Test
	void defaultDestinationIsConfigurable() {
		LegacyDispatchPolicy policy = new LegacyDispatchPolicy();
		assertEquals(LegacyDispatchPolicy.Destination.RUN_ON_GLOBAL,
				policy.defaultDestination(), "safe default: global context");
		policy.setDefaultDestination(LegacyDispatchPolicy.Destination.RUN_DIRECT);
		assertEquals(LegacyDispatchPolicy.Destination.RUN_DIRECT,
				policy.resolve("undeclared.mod"));
	}

	@Test
	void resolveTreatsNullModIdAsDefault() {
		LegacyDispatchPolicy policy = new LegacyDispatchPolicy();
		policy.declare("mod", LegacyDispatchPolicy.Destination.RUN_DIRECT);
		assertEquals(LegacyDispatchPolicy.Destination.RUN_ON_GLOBAL,
				policy.resolve(null), "unattributable work uses the default");
	}

	@Test
	void directRunGateMatchesArchitectureRules() {
		// The global context IS the legacy execution model; unknown threads
		// (mod executors) were already arbitrary under vanilla. Region and
		// network contexts must hop through a scheduler (mandates 25/28).
		assertTrue(LegacyDispatchPolicy.mayRunDirect(ThreadContext.Kind.GLOBAL));
		assertTrue(LegacyDispatchPolicy.mayRunDirect(ThreadContext.Kind.UNKNOWN));
		assertFalse(LegacyDispatchPolicy.mayRunDirect(ThreadContext.Kind.REGION));
		assertFalse(LegacyDispatchPolicy.mayRunDirect(ThreadContext.Kind.NETWORK));
		assertFalse(LegacyDispatchPolicy.mayRunDirect(ThreadContext.Kind.IO));
	}

	@Test
	void describeListsDefaultAndDeclarations() {
		LegacyDispatchPolicy policy = new LegacyDispatchPolicy();
		policy.declare("a", LegacyDispatchPolicy.Destination.RUN_ON_REGION);
		String desc = policy.describe();
		assertTrue(desc.contains("default=run_on_global"));
		assertTrue(desc.contains("a=run_on_region"));
	}
}
