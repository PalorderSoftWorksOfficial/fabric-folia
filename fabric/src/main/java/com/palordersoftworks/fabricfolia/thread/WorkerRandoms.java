/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.thread;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-thread random sources for engine execution threads (the measured
 * {@code LegacyRandomSource} cross-thread defect: vanilla's
 * {@code Level.random} is one ThreadingDetector-guarded instance per world,
 * and region workers executing {@code tickChunk} on that level raced it —
 * every such run logged
 * {@code Accessing LegacyRandomSource from multiple threads}).
 *
 * <p><strong>Mechanism (bytecode-verified on the 26.2 jar):</strong> every
 * path to shared random state funnels through the single
 * {@code Level.random} field — the internal reads in {@code tickChunk} /
 * {@code tickBlock} / weather, and the public {@code getRandom()} accessor
 * everything else uses. Wrapping that one field at construction therefore
 * covers every funnel by construction; no call site changes.</p>
 *
 * <p><strong>Dispatch rule:</strong> a thread inside a REGION ownership
 * context gets its own lazily-created {@link RandomSource} instance (same
 * implementation family and unique-seed behavior as vanilla's constructor
 * call — {@code RandomSource.create()}); every other thread — the server
 * thread, global dispatch, async/IO/network, unknown mod executors — gets
 * the original instance and therefore vanilla's exact observable behavior.
 * Keying on the REGION context is deliberate: region threads are the only
 * threads that execute level gameplay work in this port, and any OTHER
 * context touching {@code level.random} is an ownership violation that our
 * diagnostics are supposed to surface — masking it here would hide real
 * violations from STRICT mode (mandate §24: do not silently permit data
 * races).</p>
 *
 * <p><strong>Seeding behavior preservation:</strong> the original instance is
 * never replaced or re-seeded — server-thread sequences are bit-identical to
 * vanilla. Worker sources are fresh per thread with unique seeds, mirroring
 * what vanilla itself does per level ({@code RandomSupport.generateUniqueSeed})
 * and, per call-site, what vanilla already does for {@code Level.randValue}
 * ({@code createThreadLocalInstance}) — per-thread dispatch is
 * vanilla-sanctioned precedent, not an invention.</p>
 *
 * <p><strong>Lifecycle:</strong> {@link #activate} at engine bootstrap,
 * {@link #deactivate} at engine shutdown. Between deactivation and the next
 * activation (or when the engine is disabled) the wrapper is fully inert:
 * every thread gets the original. Per-thread entries live in a ThreadLocal
 * and are reclaimed with their thread — worker threads die with the worker
 * pool at engine shutdown (a restart creates a new pool with new threads),
 * so no cleanup from foreign threads is needed or possible.</p>
 *
 * <p><strong>State classification (spec 4):</strong> the active flag is
 * GLOBAL (engine lifecycle); the per-thread sources are thread-confined
 * worker state — no locks anywhere on the hot path.</p>
 */
public final class WorkerRandoms {

	private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
	private static final ThreadLocal<RandomSource> WORKER_SOURCE = new ThreadLocal<>();

	private WorkerRandoms() {
	}

	/** Enables worker-thread dispatch (engine bootstrap). */
	public static void activate() {
		ACTIVE.set(true);
	}

	/** Disables worker-thread dispatch (engine shutdown); wrapper goes inert. */
	public static void deactivate() {
		ACTIVE.set(false);
	}

	/** @return true when the current thread should use its own random source. */
	static boolean isEngineRegionThread() {
		return ACTIVE.get()
				&& ThreadOwnership.current().kind() == ThreadContext.Kind.REGION;
	}

	/** @return this thread's own random source, creating it on first use. */
	static RandomSource workerSource() {
		RandomSource source = WORKER_SOURCE.get();
		if (source == null) {
			source = RandomSource.create();
			WORKER_SOURCE.set(source);
		}
		return source;
	}

	/**
	 * Wraps a level's random source for dispatch. Inert until
	 * {@link #activate()}: before that, every thread delegates to the
	 * original. Safe to call on every level construction.
	 */
	public static RandomSource wrap(RandomSource original) {
		return new Dispatching(original);
	}

	/**
	 * The dispatching wrapper: every abstract operation routes to the
	 * effective source (worker's own on region threads, original elsewhere).
	 * Default interface methods (triangle, nextIntBetweenInclusive,
	 * consumeCount) compose from the abstracts on {@code this} and therefore
	 * route correctly without overrides.
	 */
	private static final class Dispatching implements RandomSource {

		private final RandomSource original;

		Dispatching(RandomSource original) {
			this.original = original;
		}

		private RandomSource effective() {
			return isEngineRegionThread() ? workerSource() : original;
		}

		@Override
		public void setSeed(long seed) {
			effective().setSeed(seed);
		}

		@Override
		public int nextInt() {
			return effective().nextInt();
		}

		@Override
		public int nextInt(int bound) {
			return effective().nextInt(bound);
		}

		@Override
		public long nextLong() {
			return effective().nextLong();
		}

		@Override
		public boolean nextBoolean() {
			return effective().nextBoolean();
		}

		@Override
		public float nextFloat() {
			return effective().nextFloat();
		}

		@Override
		public double nextDouble() {
			return effective().nextDouble();
		}

		@Override
		public double nextGaussian() {
			return effective().nextGaussian();
		}

		@Override
		public RandomSource fork() {
			return effective().fork();
		}

		@Override
		public PositionalRandomFactory forkPositional() {
			return effective().forkPositional();
		}
	}
}
