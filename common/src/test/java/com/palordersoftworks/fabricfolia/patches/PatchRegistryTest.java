/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.patches;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Patch-registry semantics (v2): resolution pass, dependency and conflict
 * handling, layer gating, unknown-name safety, and the invocation counters.
 */
class PatchRegistryTest {

	@AfterEach
	void reset() {
		PatchRegistry.resetForTests();
	}

	@Test
	void registeredPatchResolvesActiveByDefault() {
		PatchRegistry.Patch patch = PatchRegistry.register("t-on", "T On",
				PatchRegistry.Layer.FABRICFOLIA, "d", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		assertNotNull(patch);
		PatchRegistry.resolveAndApply();
		assertTrue(PatchRegistry.isEnabled("t-on"));
		assertEquals(PatchRegistry.Status.ACTIVE, patch.status());
		assertTrue(PatchRegistry.isResolved());
	}

	@Test
	void perPatchRequestOffDisablesThePatch() {
		PatchRegistry.register("t-off", "T Off", PatchRegistry.Layer.MINECRAFT,
				"d", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.setRequested("t-off", false);
		PatchRegistry.resolveAndApply();
		assertFalse(PatchRegistry.isEnabled("t-off"));
	}

	@Test
	void layerSwitchDisablesEveryPatchInThatLayer() {
		PatchRegistry.register("t-layer-a", "A", PatchRegistry.Layer.MINECRAFT,
				"d", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.register("t-layer-b", "B", PatchRegistry.Layer.SCHEDULER,
				"d", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.setLayerEnabled(PatchRegistry.Layer.MINECRAFT, false);
		PatchRegistry.resolveAndApply();
		assertFalse(PatchRegistry.isEnabled("t-layer-a"));
		assertTrue(PatchRegistry.isEnabled("t-layer-b"));
	}

	@Test
	void dependencyBlocksWhenInactiveAndReportsReason() {
		PatchRegistry.register("t-dep", "Dep", PatchRegistry.Layer.MINECRAFT,
				"d", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.setRequested("t-dep", false);
		PatchRegistry.Patch dependent = PatchRegistry.register("t-dependent", "Dependent",
				PatchRegistry.Layer.MINECRAFT, "d", "t",
				Set.of("t-dep"), Set.of(), PatchRegistry.Lifecycle.STARTUP_ONLY);
		List<PatchRegistry.BlockedPatch> blocked = PatchRegistry.resolveAndApply();
		assertFalse(PatchRegistry.isEnabled("t-dependent"));
		assertEquals(PatchRegistry.Status.BLOCKED_DEPENDENCY, dependent.status());
		assertTrue(dependent.statusReason().contains("t-dep"));
		assertTrue(blocked.stream().anyMatch(b -> b.id().equals("t-dependent")));
	}

	@Test
	void dependencyChainResolvesOrderIndependently() {
		PatchRegistry.register("t-chain-b", "B", PatchRegistry.Layer.MINECRAFT,
				"d", "t", Set.of("t-chain-a"), Set.of(), PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.register("t-chain-a", "A", PatchRegistry.Layer.MINECRAFT,
				"d", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.resolveAndApply();
		assertTrue(PatchRegistry.isEnabled("t-chain-a"));
		assertTrue(PatchRegistry.isEnabled("t-chain-b"), "registration order must not matter");
	}

	@Test
	void conflictBlocksTheSecondPatch() {
		PatchRegistry.register("t-conflict-a", "A", PatchRegistry.Layer.REGION,
				"d", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.Patch b = PatchRegistry.register("t-conflict-b", "B",
				PatchRegistry.Layer.REGION, "d", "t",
				Set.of(), Set.of("t-conflict-a"), PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.resolveAndApply();
		assertTrue(PatchRegistry.isEnabled("t-conflict-a"));
		assertFalse(PatchRegistry.isEnabled("t-conflict-b"));
		assertEquals(PatchRegistry.Status.BLOCKED_CONFLICT, b.status());
		assertTrue(b.statusReason().contains("t-conflict-a"));
	}

	@Test
	void unknownPatchNameIsDisabledNotAnError() {
		assertFalse(PatchRegistry.isEnabled("no-such-patch"));
		PatchRegistry.recordInvocation("no-such-patch");
		PatchRegistry.recordFallback("no-such-patch");
		assertFalse(PatchRegistry.isResolved());
	}

	@Test
	void countersTrackInvocationsAndFallbacks() {
		PatchRegistry.Patch patch = PatchRegistry.register("t-counters", "C",
				PatchRegistry.Layer.NETWORK, "d", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.resolveAndApply();
		PatchRegistry.recordInvocation("t-counters");
		PatchRegistry.recordInvocation("t-counters");
		PatchRegistry.recordFallback("t-counters");
		assertEquals(2, patch.invocations());
		assertEquals(1, patch.fallbacks());
	}

	@Test
	void duplicateRegistrationReturnsTheSamePatch() {
		PatchRegistry.Patch first = PatchRegistry.register("t-dup", "D",
				PatchRegistry.Layer.ALLOCATION, "first", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		PatchRegistry.Patch second = PatchRegistry.register("t-dup", "D",
				PatchRegistry.Layer.ALLOCATION, "second-declaration", "t", PatchRegistry.Lifecycle.STARTUP_ONLY);
		assertSame(first, second, "duplicate registration must be a no-op");
	}

	@Test
	void metadataIsExposedForDiagnostics() {
		PatchRegistry.Patch patch = PatchRegistry.register("t-meta", "Meta Patch",
				PatchRegistry.Layer.FABRICFOLIA, "does a thing", "Some.Target",
				Set.of("a"), Set.of("b"), PatchRegistry.Lifecycle.RUNTIME);
		assertEquals("Meta Patch", patch.name());
		assertEquals("Some.Target", patch.target());
		assertEquals(PatchRegistry.Lifecycle.RUNTIME, patch.lifecycle());
		assertTrue(patch.dependencies().contains("a"));
		assertTrue(patch.conflicts().contains("b"));
	}
}
