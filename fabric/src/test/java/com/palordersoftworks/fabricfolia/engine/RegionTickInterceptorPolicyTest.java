package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTickInterceptorPolicyTest {

	@Test
	void limitValueIsPinned() {
		assertEquals(512, RegionTickInterceptor.PASS_BACKPRESSURE_QUEUE_TASKS);
	}

	@Test
	void boundaryIsStrictlyGreater() {
		assertFalse(RegionTickInterceptor.isPassBackpressured(0), "an idle region never skips");
		assertFalse(RegionTickInterceptor.isPassBackpressured(512), "exactly at the limit still runs");
		assertTrue(RegionTickInterceptor.isPassBackpressured(513), "one task over the limit skips the pass");
	}
}
