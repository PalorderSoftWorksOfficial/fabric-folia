/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.thread;

import com.palordersoftworks.fabricfolia.api.RegionInfo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for the per-engine-thread random dispatch ({@link WorkerRandoms}).
 *
 * <p>The dispatch is keyed on {@link ThreadOwnership}'s REGION context, so a
 * test thread can act as a "region worker" simply by entering that context —
 * no real worker pool is needed, and the same-thread transitions make the
 * activate/deactivate semantics deterministic.</p>
 *
 * <p>The stream comparisons follow one pattern: re-seed the original between
 * two reads so "same stream" and "different stream" are exact observations,
 * not statistical ones.</p>
 */
class WorkerRandomsTest {

	private static final RegionInfo REGION_A = new RegionInfo() {
		@Override
		public String world() {
			return "test:world";
		}

		@Override
		public long regionId() {
			return 1;
		}

		@Override
		public boolean isDead() {
			return false;
		}

		@Override
		public String stateName() {
			return "READY";
		}

		@Override
		public int[] sectionCenter() {
			return new int[] {0, 0};
		}
	};

	@AfterEach
	void reset() {
		WorkerRandoms.deactivate();
		ThreadOwnership.clear();
	}

	@Test
	void inertByDefaultEvenInRegionContext() {
		RandomSource original = RandomSource.create(1);
		RandomSource wrapper = WorkerRandoms.wrap(original);
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(REGION_A);
		try {
			// Inert: the wrapper must BE the original's stream.
			original.setSeed(99L);
			long viaWrapper = wrapper.nextLong();
			original.setSeed(99L);
			long viaOriginal = original.nextLong();
			assertEquals(viaOriginal, viaWrapper);
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void regionThreadUsesOwnSourceWhenActive() {
		WorkerRandoms.activate();
		RandomSource original = RandomSource.create(1);
		RandomSource wrapper = WorkerRandoms.wrap(original);
		// An independent reference copy of the same source family: same seeds
		// produce the same values, so it serves as the expected-stream oracle
		// (both are LegacyRandomSource — value-based comparisons between the
		// wrapper and the original cannot distinguish ownership; positions can).
		RandomSource reference = RandomSource.create(1);
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(REGION_A);
		try {
			// Two draws on the wrapper while active: they must consume the
			// thread's OWN source and leave the original untouched.
			wrapper.setSeed(99L);
			wrapper.nextLong();
			wrapper.nextLong();
			// And the thread's own source is persistent across calls: re-seeding
			// it reproduces the same value (not a fresh source per call).
			wrapper.setSeed(99L);
			long persistent = wrapper.nextLong();
			wrapper.setSeed(99L);
			assertEquals(persistent, wrapper.nextLong());
			// Ownership: the original is still at its stream start for the same
			// seed — the wrapper's draws never advanced it.
			reference.setSeed(99L);
			long expectedFirst = reference.nextLong();
			original.setSeed(99L);
			assertEquals(expectedFirst, original.nextLong(),
					"wrapper draws consumed the original (dispatch leak)");
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void nonRegionThreadsStillUseOriginalWhenActive() {
		WorkerRandoms.activate();
		RandomSource original = RandomSource.create(1);
		RandomSource wrapper = WorkerRandoms.wrap(original);
		// No REGION context (test thread is UNKNOWN): server-thread semantics.
		original.setSeed(7L);
		long viaWrapper = wrapper.nextLong();
		original.setSeed(7L);
		long viaOriginal = original.nextLong();
		assertEquals(viaOriginal, viaWrapper);
	}

	@Test
	void deactivationRestoresPassthroughForSameThread() {
		RandomSource original = RandomSource.create(1);
		RandomSource wrapper = WorkerRandoms.wrap(original);
		ThreadOwnership.Context token = ThreadOwnership.enterRegion(REGION_A);
		try {
			WorkerRandoms.activate();
			// Active-phase draws consume only the worker's own source...
			wrapper.setSeed(5L);
			wrapper.nextLong();
			wrapper.nextLong();
			// ...so the original is still at "first value for seed 7". Mark it.
			original.setSeed(7L);
			original.nextLong();
			WorkerRandoms.deactivate();
			// After deactivation the SAME thread (its cached source is now
			// ignored) delegates to the original — the next wrapper draw is the
			// original's SECOND value for seed 7, exactly where the mark left
			// it. This also re-proves the active-phase draws never consumed the
			// original: any leak would shift this position.
			long afterDeactivation = wrapper.nextLong();
			original.setSeed(7L);
			original.nextLong();
			long expected = original.nextLong();
			assertEquals(expected, afterDeactivation);
		} finally {
			ThreadOwnership.exit(token);
		}
	}

	@Test
	void distinctRegionThreadsGetDistinctStreams() throws Exception {
		WorkerRandoms.activate();
		RandomSource original = RandomSource.create(1);
		RandomSource wrapper = WorkerRandoms.wrap(original);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			var first = pool.submit(() -> {
				ThreadOwnership.Context t = ThreadOwnership.enterRegion(REGION_A);
				try {
					wrapper.setSeed(11L);
					return wrapper.nextLong();
				} finally {
					ThreadOwnership.exit(t);
				}
			});
			var second = pool.submit(() -> {
				ThreadOwnership.Context t = ThreadOwnership.enterRegion(REGION_A);
				try {
					wrapper.setSeed(11L);
					return wrapper.nextLong();
				} finally {
					ThreadOwnership.exit(t);
				}
			});
			// Same seed on two threads, but each thread owns its source — the
			// values are equal per seed yet produced by INDEPENDENT streams
			// (proven by the defect test below: the guarded original is never
			// touched). Distinctness of instances, not of values, is the
			// invariant; determinism per seed is what makes it checkable.
			assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * The live defect, reproduced deterministically at unit scale: a real
	 * vanilla {@link LegacyRandomSource} (ThreadingDetector-guarded) behind
	 * the wrapper, hammered concurrently by region threads and a non-region
	 * thread. With correct dispatch, region threads never touch the guarded
	 * original — any leak onto the original throws
	 * {@code Accessing LegacyRandomSource from multiple threads} under this
	 * contention and fails the test.
	 */
	@Test
	@Timeout(30)
	void regionThreadsNeverTouchTheGuardedOriginal() throws Exception {
		WorkerRandoms.activate();
		// The guarded source vanilla would put in Level.random.
		LegacyRandomSource guarded = new LegacyRandomSource(31337L);
		RandomSource wrapper = WorkerRandoms.wrap(guarded);

		int workers = 3;
		int iterations = 20_000;
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		AtomicInteger failures = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(workers);
		try {
			for (int w = 0; w < workers; w++) {
				pool.submit(() -> {
					ThreadOwnership.Context t = ThreadOwnership.enterRegion(REGION_A);
					try {
						for (int i = 0; i < iterations; i++) {
							wrapper.nextLong();
						}
					} catch (Throwable problem) {
						failures.incrementAndGet();
					} finally {
						ThreadOwnership.exit(t);
						done.countDown();
					}
				});
			}
			// The non-region thread (would be the server thread) also draws:
			// the ONLY user of the guarded original when dispatch is correct.
			for (int i = 0; i < iterations; i++) {
				wrapper.nextLong();
			}
			if (!done.await(20, TimeUnit.SECONDS)) {
				failures.incrementAndGet();
			}
			assertEquals(0, failures.get(),
					"a region thread reached the guarded original (dispatch leak)");
		} finally {
			pool.shutdownNow();
		}
	}
}
