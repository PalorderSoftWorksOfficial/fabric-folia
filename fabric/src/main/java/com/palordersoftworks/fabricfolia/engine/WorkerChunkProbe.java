package com.palordersoftworks.fabricfolia.engine;

public final class WorkerChunkProbe {

	public enum Outcome {
		RETURN_CACHED,
		RETURN_NULL,
		FALL_THROUGH
	}

	private static final java.util.concurrent.atomic.AtomicLong ABSENT_NULLS = new java.util.concurrent.atomic.AtomicLong();

	private WorkerChunkProbe() {
	}

	public static Outcome decide(boolean create, boolean holderPresent, boolean chunkPresent) {
		if (chunkPresent) {
			return Outcome.RETURN_CACHED;
		}
		if (!create && !holderPresent) {
			return Outcome.RETURN_NULL;
		}
		return Outcome.FALL_THROUGH;
	}

	public static void recordAbsentNull() {
		ABSENT_NULLS.incrementAndGet();
	}

	public static long absentNulls() {
		return ABSENT_NULLS.get();
	}

	static void resetForTests() {
		ABSENT_NULLS.set(0);
	}
}
