/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A region: a dynamically-sized, dynamically-shaped set of region sections,
 * created, merged, and split at runtime (spec 2.2).
 *
 * <p><strong>Invariants this type participates in</strong> (enforced by
 * {@link WorldRegionizer}; documented here because each field exists for one of
 * them):</p>
 * <ol>
 *   <li>Every chunk holder belongs to exactly one region — sections are moved
 *       atomically between regions under the regionizer lock, never shared.</li>
 *   <li>Every position within the merge radius of an owned section is owned by
 *       this region or pending merge into it — maintained by buffer-section
 *       creation and the merge machinery.</li>
 *   <li>A ticking region cannot expand its section set — {@link #state} guards
 *       this: {@code tryMarkTicking} fails unless READY, and addChunk routes new
 *       sections near a TICKING region into a TRANSIENT neighbor instead.</li>
 *   <li>A region is always in exactly one of four states — {@link RegionState}.</li>
 * </ol>
 *
 * <p><strong>Concurrency contract:</strong> structural fields (sections, state,
 * merge sets) are only mutated while holding the regionizer's structure lock, or
 * by the single worker context executing the region's tick-end protocol. The
 * tick data (tick counter, deadline) is owned by the executing context during
 * TICKING. No lock lives on Region itself by design: the regionizer serializes
 * structure, and the scheduler serializes execution (spec 3/10/18).</p>
 */
public final class Region implements com.palordersoftworks.fabricfolia.api.RegionInfo {
	/** Unique id within the world; never reused in one server run. */
	public final long id;
	/** The world (dimension) name this region belongs to. */
	public final String world;

	/**
	 * Current state (invariant 4). All TRANSITIONS happen under the regionizer
	 * structure lock (that lock owns the state machine, spec 3/18); volatile
	 * exists ONLY so cross-thread advisory reads (the scheduler's dispatch
	 * guards, diagnostics) see a fresh value without taking the lock — it is
	 * not a safety mechanism and must not be treated as one.
	 */
	volatile RegionState state = RegionState.TRANSIENT;

	/**
	 * Sections currently owned, keyed by packed section coordinate. MUTATION
	 * is guarded by the regionizer structure lock (all writers hold it); the
	 * concurrent map exists so lock-free READS from diagnostics (section
	 * counts, representative centers) are race-free instead of iterating a
	 * plain HashMap mid-resize.
	 */
	final java.util.Map<Long, RegionSection> sections = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Regions this region must merge INTO at a future tick end (merge-later
	 * targets). A region carrying obligations is TRANSIENT — it cannot tick
	 * while a merge into another region is pending (Folia's documented
	 * "merge later" logic, clean-room). Emptied either by absorption into a
	 * target or by {@code releaseObligationsOn} when a target dies first.
	 */
	final Set<Region> mergeLater = new LinkedHashSet<>();

	/**
	 * Regions expected to merge into THIS region when its current tick ends.
	 * Maintained symmetrically with the source regions' mergeLater sets.
	 */
	final Set<Region> expectingMergeFrom = new LinkedHashSet<>();

	/**
	 * This region's own tick counter. Advanced once per completed tick by the
	 * executing context. Delayed tasks scheduled against this region are
	 * expressed against this counter (execute when {@code tickCount >= target}),
	 * so they survive merges: a donor's queue (including its pending delayed
	 * tasks) is re-homed wholesale into the absorbing region, whose counter
	 * history continues forward. (Folia overview doc: relative deadlines are
	 * maintained through merges via counter offsets — this design achieves the
	 * same guarantee by construction: the counter is per-region-lifetime, and
	 * merges extend the absorbing region's lifetime.)
	 *
	 * <p>volatile: written by the executing context once per tick, read by
	 * delayed-task wrappers (same context) and diagnostics (any thread) —
	 * visibility only.</p>
	 */
	volatile long tickCount;

	/**
	 * Absolute deadline (System.nanoTime) when this region's next tick should
	 * start. Scheduling targets this; the scheduler dispatches READY regions
	 * earliest-deadline-first. volatile: written under the structure lock at
	 * tick end, read cross-thread by the dispatch loop's advisory guards.
	 */
	volatile long nextTickDeadlineNanos;

	/**
	 * Tick duration tracking (spec 6, 20): duration of the last tick in
	 * nanoseconds. Written by the executing context at tick end; read by
	 * diagnostics. volatile suffices: it is a single diagnostic value with no
	 * cross-field invariant (spec 18 review note: not a safety mechanism).
	 */
	private volatile long lastTickDurationNanos;

	/** @return duration of the region's last completed tick, in nanoseconds. */
	public long lastTickDurationNanos() {
		return lastTickDurationNanos;
	}

	/** Called by the executing context at tick end. */
	public void recordTickDuration(long nanos) {
		this.lastTickDurationNanos = nanos;
	}

	/** @return the scheduler's next-tick deadline (nanoTime), for dispatch. */
	public long nextTickDeadlineNanos() {
		return nextTickDeadlineNanos;
	}

	/** Sets the next-tick deadline; called by the regionizer/scheduler only. */
	public void setNextTickDeadlineNanos(long deadlineNanos) {
		this.nextTickDeadlineNanos = deadlineNanos;
	}

	/** Debug/diagnostics label: where this region came from. */
	String origin = "new";

	Region(long id, String world, RegionState state) {
		this.id = id;
		this.world = world;
		this.state = state;
	}

	// =================================================================================
	// State transition contracts (regionizer reference: tryMarkTicking / markNotTicking)
	// =================================================================================

	/**
	 * Attempts to move this region from READY to TICKING.
	 *
	 * <p><strong>Contract (region logic reference):</strong> returns false unless
	 * the region is in the READY state — this can happen even for a region that
	 * was scheduled to tick, because the regionizer may have marked it TRANSIENT
	 * between scheduling and dispatch (a nearby chunk load forced a merge). The
	 * caller must handle a false return by NOT ticking and re-consulting the
	 * regionizer.</p>
	 *
	 * <p><strong>Why this method exists:</strong> it is the latch that enforces
	 * invariant 3 (a ticking region never grows) and the single-owner-at-a-time
	 * guarantee for tick execution. It is called under the regionizer structure
	 * lock by the scheduler's dispatch path.</p>
	 *
	 * @return true if the region is now TICKING and owned by the caller's context
	 */
	public boolean tryMarkTicking() {
		if (state != RegionState.READY) {
			return false;
		}
		state = RegionState.TICKING;
		return true;
	}

	/**
	 * Ends the current tick. Must be called by the context that successfully
	 * called {@link #tryMarkTicking()}, exactly once, after finishing all tick
	 * work but BEFORE the region can be dispatched again.
	 *
	 * <p><strong>Tick-end protocol (documented order, region logic reference —
	 * clean-room):</strong></p>
	 * <ol>
	 *   <li>process pending merges (regions in {@link #expectingMergeFrom} are
	 *       absorbed by the regionizer);</li>
	 *   <li>if this region is itself pending merge into another region, it
	 *       becomes TRANSIENT;</li>
	 *   <li>otherwise remove dead sections and attempt a split.</li>
	 * </ol>
	 *
	 * <p>Executed by {@link WorldRegionizer#completeTick(Region)}; not public.</p>
	 */
	void markNotTicking() {
		// State transition is performed by completeTick after the protocol runs;
		// this method exists as the named contract point for documentation and
		// assertion purposes.
		if (state != RegionState.TICKING) {
			throw new IllegalStateException("markNotTicking on non-TICKING region " + id
					+ " (state " + state + ")");
		}
	}

	/** @return the current state. */
	public RegionState state() {
		return state;
	}

	/** @return number of sections owned (diagnostics). */
	public int sectionCount() {
		return sections.size();
	}

	/** @return number of non-empty sections owned (diagnostics). */
	public int nonEmptySectionCount() {
		int n = 0;
		for (RegionSection section : sections.values()) {
			if (section.isNotEmpty()) {
				n++;
			}
		}
		return n;
	}

	/** @return this region's tick counter. */
	public long tickCount() {
		return tickCount;
	}

	/**
	 * Advances the tick counter by one — called by the executing context's
	 * delayed-task wrapper each time the region's tick passes (the counter is
	 * the delayed-task time base; see {@link #tickCount}). Not for general use.
	 */
	public void advanceTickCounter() {
		tickCount++;
	}

	// =================================================================================
	// RegionInfo (public API) implementation: identity is object identity; the
	// world/name accessors read the structural fields documented above.
	// =================================================================================

	@Override
	public String world() {
		return world;
	}

	@Override
	public long regionId() {
		return id;
	}

	@Override
	public boolean isDead() {
		return state == RegionState.DEAD;
	}

	@Override
	public String stateName() {
		return state.name();
	}

	@Override
	public int[] sectionCenter() {
		// Reads the structural section map without the structure lock: the
		// map is only mutated under that lock, and this is a best-effort
		// representative point — a concurrent merge/split yields a slightly
		// stale center, which is fine for diagnostics and distance decisions
		// (the values are internally consistent ints from live sections).
		long sumX = 0;
		long sumZ = 0;
		int n = 0;
		for (Long key : sections.keySet()) {
			sumX += (int) (key >> 32);
			sumZ += (int) (key & 0xFFFFFFFFL);
			n++;
		}
		if (n == 0) {
			return null;
		}
		return new int[] {(int) (sumX / n), (int) (sumZ / n)};
	}

	@Override
	public String toString() {
		return "Region[" + world + ":" + id + ", " + state + ", sections=" + sections.size()
				+ ", mergeLater=" + mergeLater.size() + "]";
	}

	/** Collects owned section coordinates for split/merge/diagnostics. */
	List<RegionSection> snapshotSections() {
		return new ArrayList<>(sections.values());
	}
}
