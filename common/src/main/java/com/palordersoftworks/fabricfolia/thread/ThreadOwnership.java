/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.thread;

import com.palordersoftworks.fabricfolia.api.RegionInfo;
import com.palordersoftworks.fabricfolia.api.ThreadContext.Kind;

import java.util.Optional;

/**
 * The execution-context registry: the mechanism that lets any code ask "what
 * region owns the current thread's execution right now?" (spec 8).
 *
 * <p><strong>Why ThreadLocal:</strong> the ownership question is per-execution,
 * and Java's unit of execution is the thread. Worker threads enter/exit region
 * contexts at tick boundaries and task dispatches via
 * {@link #enter}/{@link #exit}; the regionizer's state machine guarantees at
 * most one region is executing per context at any moment, so a single slot
 * suffices — no stack of owners can legally exist (a nested region context
 * would itself be a violation).</p>
 *
 * <p><strong>Clearing discipline:</strong> enter/exit MUST be used in
 * try/finally pairs; the scheduler's dispatch loop and every task wrapper
 * enforce this. A worker thread that exits a context without clearing would
 * poison every subsequent task on that worker, so {@link #exit} resets to the
 * worker's idle state explicitly.</p>
 *
 * <p><strong>State classification (spec 4):</strong> GLOBAL — this registry
 * describes the execution model itself.</p>
 */
public final class ThreadOwnership {

	/** Immutable snapshot of one execution context. */
	public record Context(Kind kind, RegionInfo region, String threadName) {
		public static final Context UNKNOWN =
				new Context(Kind.UNKNOWN, null, Thread.currentThread().getName());

		public static Context global(String threadName) {
			return new Context(Kind.GLOBAL, null, threadName);
		}

		public static Context network(String threadName) {
			return new Context(Kind.NETWORK, null, threadName);
		}

		public static Context io(String threadName) {
			return new Context(Kind.IO, null, threadName);
		}

		public static Context region(RegionInfo region, String threadName) {
			return new Context(Kind.REGION, region, threadName);
		}
	}

	private static final ThreadLocal<Context> CURRENT = ThreadLocal.withInitial(() -> Context.UNKNOWN);

	private ThreadOwnership() {
	}

	/** @return the current execution context (never null). */
	public static Context current() {
		return CURRENT.get();
	}

	/**
	 * Enters a region context. Returns a token to pass to {@link #exit};
	 * capturing the token keeps the discipline explicit at call sites.
	 */
	public static Context enterRegion(RegionInfo region) {
		Context previous = CURRENT.get();
		CURRENT.set(Context.region(region, Thread.currentThread().getName()));
		return previous;
	}

	/** Enters the global context; returns the previous context token. */
	public static Context enterGlobal() {
		Context previous = CURRENT.get();
		CURRENT.set(Context.global(Thread.currentThread().getName()));
		return previous;
	}

	/** Enters a network or IO context; returns the previous context token. */
	public static Context enterSide(Kind kind) {
		Context previous = CURRENT.get();
		CURRENT.set(switch (kind) {
			case NETWORK -> Context.network(Thread.currentThread().getName());
			case IO -> Context.io(Thread.currentThread().getName());
			default -> throw new IllegalArgumentException("enterSide requires NETWORK or IO");
		});
		return previous;
	}

	/**
	 * Restores the previous context. Must be called in finally with the token
	 * returned by the matching enter* call.
	 */
	public static void exit(Context token) {
		CURRENT.set(token);
	}

	/** Hard reset to UNKNOWN (worker-thread init and shutdown paths). */
	public static void clear() {
		CURRENT.set(Context.UNKNOWN);
	}

	/** @return the owning region of the current context, or empty. */
	public static Optional<RegionInfo> currentRegion() {
		Context context = CURRENT.get();
		return context.kind() == Kind.REGION
				? Optional.ofNullable(context.region())
				: Optional.empty();
	}
}
