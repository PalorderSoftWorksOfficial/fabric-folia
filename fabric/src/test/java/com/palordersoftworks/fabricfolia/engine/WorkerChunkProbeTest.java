package com.palordersoftworks.fabricfolia.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerChunkProbeTest {

	@AfterEach
	void reset() {
		WorkerChunkProbe.resetForTests();
	}

	@Test
	void presentChunkReturnsCachedRegardlessOfCreate() {
		assertEquals(WorkerChunkProbe.Outcome.RETURN_CACHED,
				WorkerChunkProbe.decide(false, true, true));
		assertEquals(WorkerChunkProbe.Outcome.RETURN_CACHED,
				WorkerChunkProbe.decide(true, true, true));
	}

	@Test
	void absentHolderWithoutCreateReturnsNullInsteadOfParking() {
		assertEquals(WorkerChunkProbe.Outcome.RETURN_NULL,
				WorkerChunkProbe.decide(false, false, false));
	}

	@Test
	void absentHolderWithCreateFallsThroughToVanilla() {
		assertEquals(WorkerChunkProbe.Outcome.FALL_THROUGH,
				WorkerChunkProbe.decide(true, false, false));
	}

	@Test
	void presentBelowStatusFallsThroughToVanilla() {
		assertEquals(WorkerChunkProbe.Outcome.FALL_THROUGH,
				WorkerChunkProbe.decide(false, true, false));
		assertEquals(WorkerChunkProbe.Outcome.FALL_THROUGH,
				WorkerChunkProbe.decide(true, true, false));
	}

	@Test
	void absentNullCounterIsObservable() {
		assertEquals(0, WorkerChunkProbe.absentNulls());
		WorkerChunkProbe.recordAbsentNull();
		WorkerChunkProbe.recordAbsentNull();
		assertEquals(2, WorkerChunkProbe.absentNulls());
	}
}
