/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.patches;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Patch-registry semantics: default-on, layer AND per-patch gating, safe
 * unknown-name handling, and the invocation/fallback counters.
 */
class PatchRegistryTest {

	@AfterEach
	void reset() {
		for (PatchRegistry.Patch patch : PatchRegistry.patches()) {
			PatchRegistry.setPatchEnabled(patch.name(), true);
			PatchRegistry.setLayerEnabled(patch.layer(), true);
		}
		PatchRegistry.resetForTests();
	}

	@Test
	void registeredPatchIsEnabledByDefault() {
		PatchRegistry.Patch patch = PatchRegistry.register("test-default-on",
				PatchRegistry.Layer.FABRICFOLIA, "test patch");
		assertNotNull(patch);
		assertTrue(PatchRegistry.isEnabled("test-default-on"));
		assertSame(patch, PatchRegistry.patch("test-default-on"));
	}

	@Test
	void layerSwitchDisablesEveryPatchInThatLayer() {
		PatchRegistry.register("test-layer-a", PatchRegistry.Layer.MINECRAFT, "a");
		PatchRegistry.register("test-layer-b", PatchRegistry.Layer.FABRICFOLIA, "b");
		PatchRegistry.setLayerEnabled(PatchRegistry.Layer.MINECRAFT, false);
		assertFalse(PatchRegistry.isEnabled("test-layer-a"));
		assertTrue(PatchRegistry.isEnabled("test-layer-b"),
				"another layer's patch must stay enabled");
	}

	@Test
	void perPatchToggleOverridesLayerEnabledState() {
		PatchRegistry.register("test-own-toggle", PatchRegistry.Layer.FABRICFOLIA, "own toggle");
		PatchRegistry.setPatchEnabled("test-own-toggle", false);
		assertFalse(PatchRegistry.isEnabled("test-own-toggle"));
		PatchRegistry.setLayerEnabled(PatchRegistry.Layer.FABRICFOLIA, false);
		assertFalse(PatchRegistry.isEnabled("test-own-toggle"));
		PatchRegistry.setLayerEnabled(PatchRegistry.Layer.FABRICFOLIA, true);
		assertFalse(PatchRegistry.isEnabled("test-own-toggle"),
				"patch toggle must stay off after the layer re-enables");
	}

	@Test
	void unknownPatchNameIsDisabledNotAnError() {
		assertFalse(PatchRegistry.isEnabled("no-such-patch"));
		PatchRegistry.recordInvocation("no-such-patch");
		PatchRegistry.recordFallback("no-such-patch");
	}

	@Test
	void countersTrackInvocationsAndFallbacks() {
		PatchRegistry.register("test-counters", PatchRegistry.Layer.NETWORK, "counters");
		PatchRegistry.recordInvocation("test-counters");
		PatchRegistry.recordInvocation("test-counters");
		PatchRegistry.recordFallback("test-counters");
		PatchRegistry.Patch patch = PatchRegistry.patch("test-counters");
		assertEquals(2, patch.invocations());
		assertEquals(1, patch.fallbacks());
	}

	@Test
	void duplicateRegistrationReturnsTheSamePatch() {
		PatchRegistry.Patch first = PatchRegistry.register("test-dup",
				PatchRegistry.Layer.GC, "first");
		PatchRegistry.Patch second = PatchRegistry.register("test-dup",
				PatchRegistry.Layer.GC, "second-declaration");
		assertSame(first, second, "duplicate registration must be a no-op");
	}
}
