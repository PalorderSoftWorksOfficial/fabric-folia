/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Tick-spaced retry ledger for staged gameplay bodies that failed with a
 * transient concurrent-modification failure. Found by production telemetry
 * (Palorder Central, 2026-09-25): four suppressed
 * {@code java.util.ConcurrentModificationException: Async entity load} on
 * {@code use_item} — that guard string is c2me's async entity loader, which
 * throws when a foreign thread touches an entity section it is loading. Under
 * our staging, a region worker can execute an entity body while c2me loads
 * that chunk's entities; the body never ran, and the previous catch swallowed
 * the failure into a counter — a lost entity tick, silent to operators.
 * The policy here (and mandate-wide): <strong>fix, do not suppress</strong> —
 * a body that never ran must be retried, not dropped.
 *
 * <p><strong>Contract:</strong> the capturing catch schedules the raw body
 * with its current attempt number; the owning world's next flushes (server
 * thread) drain entries that are due — at least {@value #RETRY_SPACING_TICKS}
 * ticks after scheduling, giving the racing loader time to finish — and
 * requeue each through the world's normal dispatch (region re-resolved at
 * replay time, so a retried body never runs on a stale owner). A retry that
 * fails again is scheduled again with attempt+1, up to
 * {@value #MAX_ATTEMPTS} retries; past the cap it is counted EXHAUSTED and
 * surfaced through the isolation counter — never silently discarded.</p>
 *
 * <p><strong>Threading:</strong> {@link #schedule} is called from region
 * workers (the failing body's thread); {@link #drain} is called from the
 * server thread at flush. Per-world entry lists are synchronized; the map is
 * concurrent. Workers only ever add, the server thread only drains its own
 * world — there is no cross-thread iteration of a mutating list.</p>
 *
 * <p><strong>Suppression:</strong> when worker world-tick work is suppressed
 * (the c2me-compat window), drain keeps entries pending instead of running
 * them — an unsound window must not be forced through, and unlike ticket
 * placements these are gameplay bodies that must not be silently cleared.</p>
 */
public final class StageRetry {

	/** One deferred retry of a failed staged body. */
	public record Entry(String world, Runnable body, int attempt, long dueTick) {
	}

	/** Hard cap on retries per body (original execution + this many retries). */
	public static final int MAX_ATTEMPTS = 4;

	/** Minimum tick spacing between a failure and its retry (and between retries). */
	public static final long RETRY_SPACING_TICKS = 2;

	private static final Map<String, List<Entry>> PENDING = new ConcurrentHashMap<>();
	private static final AtomicLong SCHEDULED = new AtomicLong();
	private static final AtomicLong RETRIED = new AtomicLong();
	private static final AtomicLong EXHAUSTED = new AtomicLong();

	private StageRetry() {
	}

	/**
	 * Accepts a failed body for retry. {@code attempt} is the attempt number
	 * that just failed (0 for the original staged execution); the retry runs
	 * as attempt+1. Past {@link #MAX_ATTEMPTS} the body is counted exhausted
	 * instead of scheduled — the caller's isolation accounting surfaces it.
	 *
	 * @param world       world key of the body's level (drain scoping)
	 * @param body        the raw staged body that failed
	 * @param attempt     the attempt number that failed
	 * @param currentTick supplies the world's current server tick
	 */
	public static void schedule(String world, Runnable body, int attempt, LongSupplier currentTick) {
		int next = attempt + 1;
		if (next > MAX_ATTEMPTS) {
			EXHAUSTED.incrementAndGet();
			return;
		}
		List<Entry> list = PENDING.computeIfAbsent(world, k -> new ArrayList<>());
		synchronized (list) {
			list.add(new Entry(world, body, next, currentTick.getAsLong() + RETRY_SPACING_TICKS));
		}
		SCHEDULED.incrementAndGet();
	}

	/**
	 * Requeues every due retry of {@code world} through {@code requeue}.
	 * Server thread only (the world's flush). A requeue returning false keeps
	 * the entry pending for the next flush (e.g. the engine is mid-shutdown);
	 * any other outcome — including a dispatch-level drop — is terminal for
	 * that entry. Suppressed sessions skip the drain entirely, keeping
	 * entries pending rather than forcing them through an unsound window.
	 *
	 * @param world             world key to drain
	 * @param suppressionActive supplies true while worker world-tick work is suppressed
	 * @param requeue           dispatches one retry; false keeps it pending
	 * @param currentTick       supplies the world's current server tick
	 */
	public static void drain(String world, BooleanSupplier suppressionActive,
	                         Requeue requeue, LongSupplier currentTick) {
		List<Entry> list = PENDING.get(world);
		if (list == null || list.isEmpty()) {
			return;
		}
		if (suppressionActive.getAsBoolean()) {
			return;
		}
		long tick = currentTick.getAsLong();
		List<Entry> keep = null;
		synchronized (list) {
			Iterator<Entry> it = list.iterator();
			while (it.hasNext()) {
				Entry entry = it.next();
				if (entry.dueTick() > tick) {
					continue;
				}
				it.remove();
				if (requeue.accept(entry.world(), entry.body(), entry.attempt())) {
					RETRIED.incrementAndGet();
				} else if (keep == null) {
					keep = new ArrayList<>();
					keep.add(entry);
				} else {
					keep.add(entry);
				}
			}
			if (keep != null) {
				list.addAll(keep);
			}
		}
	}

	/**
	 * Drops every pending retry for one world (world detach / engine
	 * shutdown of that dimension). A body whose level never flushes again
	 * can never be retried; the drop is counted exhausted so it is never
	 * silent.
	 */
	public static void clearWorld(String world) {
		List<Entry> removed = PENDING.remove(world);
		if (removed != null) {
			EXHAUSTED.addAndGet(removed.size());
		}
	}

	/** @return entries currently pending across all worlds (diagnostics). */
	public static int pendingCount() {
		int total = 0;
		for (List<Entry> list : PENDING.values()) {
			synchronized (list) {
				total += list.size();
			}
		}
		return total;
	}

	/** Total bodies accepted for retry (diagnostics). */
	public static long scheduled() {
		return SCHEDULED.get();
	}

	/** Total retries actually requeued for execution (diagnostics). */
	public static long retried() {
		return RETRIED.get();
	}

	/** Total bodies given up after the attempt cap (diagnostics). */
	public static long exhausted() {
		return EXHAUSTED.get();
	}

	/** Requeues one due retry. Returning false keeps the entry pending. */
	@FunctionalInterface
	public interface Requeue {
		boolean accept(String world, Runnable body, int attempt);
	}
}
