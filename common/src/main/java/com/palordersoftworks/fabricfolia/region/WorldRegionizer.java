/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Per-world regionizer implementing Folia's four documented invariants
 * (clean-room from the PaperMC region-logic reference, spec 14):
 *
 * <ol>
 *   <li><strong>Single ownership:</strong> every existing chunk belongs to
 *       exactly one region.</li>
 *   <li><strong>Buffer:</strong> for every owned chunk, every position within
 *       the merge radius is owned by the same region (or pending merge).</li>
 *   <li><strong>No growth while ticking:</strong> a TICKING region never
 *       expands its section set.</li>
 *   <li><strong>Four states:</strong> TRANSIENT, READY, TICKING, DEAD.</li>
 * </ol>
 *
 * <p><strong>Concurrency model:</strong> a single structure lock serializes
 * regionizer operations (addChunk/removeChunk/tryMarkTicking/completeTick).
 * This lock is NOT the safety mechanism of the architecture — it protects only
 * the regionizer's own bookkeeping maps during structural transitions, and it
 * is never held while region ticks execute. Ownership of gameplay state is
 * enforced by the state machine (invariants 3/4), not by this lock (spec 3/18).</p>
 *
 * <p><strong>Lock-hierarchy contract:</strong> structure lock → region queues.
 * The scheduler acquires the structure lock only in its dispatch path and never
 * while a tick runs, so no tick ever waits on the regionizer lock. There is no
 * lock ordering cycle: nothing acquires the structure lock while holding a
 * region's queue lock.</p>
 */
public final class WorldRegionizer {

	/**
	 * Structural lifecycle listener. Implementations are invoked while the
	 * regionizer structure lock is held (single-threaded per event) and must
	 * be cheap and non-blocking — no gameplay work happens here.
	 */
	public interface Listener {
		/**
		 * {@code donor} was absorbed into {@code into}; both are non-ticking
		 * at this point.
		 */
	void onRegionMerged(Region donor, Region into);

	/**
	 * {@code parent} was split into {@code children} (all READY; sections and
	 * tick counters already redistributed by the regionizer). Fired under the
	 * structure lock, before the children are dispatchable — listeners must
	 * redistribute per-region data (queues, region-local state) here so
	 * children never start with stranded ownership.
	 */
	void onRegionSplit(Region parent, List<Region> children);

	/** {@code region} became DEAD (emptied out); it is inert from now on. */
	void onRegionDead(Region region);
}

	private final RegionizerConfig config;
	private final int shift;
	private final String world;
	private final LongSupplier idGenerator;
	private final LongSupplier nanoClock;

	/** Serializes ALL structural operations below. See class-level concurrency model. */
	private final ReentrantLock structureLock = new ReentrantLock();

	/** Owned sections by packed coordinate. Guarded by structureLock. */
	private final Map<Long, RegionSection> sections = new HashMap<>();
	/** Live regions by id. Guarded by structureLock. */
	private final Map<Long, Region> regions = new HashMap<>();
	/** Sections with any chunks, per region id, for split recalculation. Guarded by structureLock. */
	private final Set<Region> liveRegions = new LinkedHashSet<>();
	/**
	 * Reverse ownership index: section key → owning region. Maintained under
	 * the structure lock at every ownership mutation (adopt, immediate merge,
	 * split, kill); turns ownerOfChunk from a linear scan over live regions
	 * into one map read (the hot ownership lookup on every staged body, packet
	 * re-home, and ownership check). When the owning region dies the section
	 * is gone or re-adopted, so no stale entries accumulate.
	 */
	private final java.util.Map<Long, Region> sectionOwners = new java.util.HashMap<>();

	private long nextRegionId = 1;

	/** Structural lifecycle listeners, in registration order. Guarded by structureLock. */
	private List<Listener> listeners;

	/**
	 * Chunk lifecycle accounting sink, set once by the host (fabric engine)
	 * so regionizer-internal events surface in metrics without the regionizer
	 * importing the metrics layer. Null until installed: counters then simply
	 * stay silent (unit tests, hosts without metrics).
	 */
	public interface ChunkMetricSink {
		/** A new chunk position became owned (first registration of its section). */
		void chunkRegistered();

		/** A chunk position's registration became void (unload, last chunk of its section). */
		void chunkUnregistered();
	}

	/** Structural lifecycle accounting sink (merges/splits/aborts). */
	public interface StructuralMetricSink {
		/** A merge completed (donor absorbed into a survivor). */
		void regionMerged();

		/** A region split produced one child region. */
		void regionSplit();

		/** A region was aborted by tick-end protocol failure. */
		void regionAborted();
	}

	private volatile ChunkMetricSink chunkMetrics;
	private volatile StructuralMetricSink structuralMetrics;

	/** Installs the chunk lifecycle metric sink. Idempotent: last write wins. */
	public void setChunkMetricSink(ChunkMetricSink sink) {
		this.chunkMetrics = sink;
	}

	/** Installs the structural lifecycle metric sink (merges/splits/aborts). */
	public void setStructuralMetricSink(StructuralMetricSink sink) {
		this.structuralMetrics = sink;
	}

	public WorldRegionizer(String world, RegionizerConfig config,
	                       LongSupplier idGenerator, LongSupplier nanoClock) {
		this.world = world;
		this.config = config;
		this.shift = config.sectionChunkShift();
		this.idGenerator = idGenerator;
		this.nanoClock = nanoClock;
	}

	// =================================================================================
	// Coordinate math
	// =================================================================================

	/**
	 * Registers a structural lifecycle listener (list semantics: multiple
	 * listeners fire in registration order under the structure lock — the
	 * scheduler's queue registry first, then any region-data hub). A listener
	 * added twice fires twice; add each listener once.
	 */
	public void addListener(Listener listener) {
		structureLock.lock();
		try {
			if (this.listeners == null) {
				this.listeners = new ArrayList<>();
			}
			this.listeners.add(listener);
		} finally {
			structureLock.unlock();
		}
	}

	/**
	 * Executes {@code action} while holding the structure lock — the facility
	 * the scheduler uses to make its enqueue death-race re-check ordered
	 * against merges/deaths (the events it must stay ordered with run under
	 * this same lock). {@code action} must be non-blocking and must not call
	 * back into regionizer operations that take the lock themselves in a way
	 * that assumes reentrancy is disallowed (the lock is reentrant, but the
	 * action should behave like it holds the structure lock it does).
	 */
	public void underStructureLock(Runnable action) {
		structureLock.lock();
		try {
			action.run();
		} finally {
			structureLock.unlock();
		}
	}

	/**
	 * Value-returning variant used by the scheduler's enqueue death-race
	 * re-check: reads region state under the structure lock, the same lock
	 * domain merges and deaths run under, so the result is ordered against
	 * those transitions.
	 */
	public boolean underStructureLock(BooleanSupplier supplier) {
		structureLock.lock();
		try {
			return supplier.getAsBoolean();
		} finally {
			structureLock.unlock();
		}
	}

	/** @return packed section coordinate for a chunk coordinate. */
	public static long sectionKey(int sectionX, int sectionZ) {
		return ((long) sectionX << 32) | (sectionZ & 0xFFFFFFFFL);
	}

	private static int keyX(long key) {
		return (int) (key >> 32);
	}

	private static int keyZ(long key) {
		return (int) key;
	}

	/** @return section x for a chunk x. */
	public int sectionX(int chunkX) {
		return chunkX >> shift;
	}

	/** @return section z for a chunk z. */
	public int sectionZ(int chunkZ) {
		return chunkZ >> shift;
	}

	// =================================================================================
	// Chunk registration (regionizer reference: addChunk / removeChunk)
	// =================================================================================

	/**
	 * Registers a chunk position with the regionizer (a chunk holder appeared).
	 *
	 * <p><strong>Invariant effects:</strong> may create the target section, its
	 * buffer halo, and new regions; may merge existing regions into a selected
	 * target to restore invariant 2; never expands a TICKING region (invariant 3)
	 * — work near a ticking region is absorbed by a TRANSIENT neighbor instead,
	 * which merges into it at its tick end.</p>
	 *
	 * @return the region that owns the chunk after the operation
	 */
	public Region addChunk(int chunkX, int chunkZ) {
		structureLock.lock();
		try {
			long sectionPos = sectionKey(sectionX(chunkX), sectionZ(chunkZ));
			ChunkMetricSink sink = this.chunkMetrics;

			RegionSection target = sections.get(sectionPos);
			if (target != null && target.isNotEmpty()) {
				// Fast path: section already exists and is non-empty. Its region
				// is unchanged (the chunk joins it, invariant 1 preserved).
				// Ownership is position-exact: a re-offer of an already-owned
				// chunk (the vanilla entity-ticking pass re-offers every loaded
				// tick) adds nothing — counts are idempotent per position.
				if (target.addChunk(chunkX, chunkZ) && sink != null) {
					sink.chunkRegistered();
				}
				return ownerOfSectionLocked(target);
			}

			boolean createdSection = false;
			if (target == null) {
				target = new RegionSection(keyX(sectionPos), keyZ(sectionPos), true);
				sections.put(sectionPos, target);
				createdSection = true;
			}
			// Slow path: the section was null (fresh) or empty (buffer section
			// re-activated) — the position is genuinely newly owned either way.
			target.addChunk(chunkX, chunkZ);
			if (sink != null) {
				sink.chunkRegistered();
			}

			// Create/refresh the buffer halo (empty-section creation radius).
			// Halo refresh also re-arms dead buffer sections around renewed
			// activity — required for invariant 2 after activity moves.
			haloCreate(target);

			// Collect all regions within merge radius + creation radius of the
			// target section: they must end up merged (invariant 2).
			Set<Region> nearby = regionsNear(target, config.mergeRadiusSections() + config.emptySectionCreationRadius());

			Region owner;
			if (nearby.isEmpty()) {
				owner = newRegion(RegionState.READY);
				origin(owner, createdSection ? "new-activity" : "revived-activity");
			} else {
				// Prefer a non-ticking region as merge target. If every nearby
				// region is TICKING, create a TRANSIENT region to hold the new
				// sections; it will merge into the ticking region at its tick end
				// (invariant 3: the ticking region never grows itself).
				owner = selectNonTicking(nearby);
				if (owner == null) {
					owner = newRegion(RegionState.TRANSIENT);
					origin(owner, "transient-for-ticking");
				}
			}

			adopt(owner, target);
			for (Region nearbyRegion : nearby) {
				if (nearbyRegion == owner) continue;
				mergeInto(nearbyRegion, owner);
			}

			// A READY region that just gained sections keeps its eligibility;
			// a TRANSIENT owner stays transient until its merge completes at the
			// target's tick end.
			return owner;
		} finally {
			structureLock.unlock();
		}
	}

	/**
	 * Unregisters a chunk position (its holder was destroyed). Marks the
	 * containing section empty; the section and its halo transition to dead in
	 * the documented way. Never changes region states (per the reference:
	 * removeChunk "will not update any region state, and nor will it purge
	 * region sections" — dead-section removal is deferred to tick-end
	 * recalculation).
	 */
	public void removeChunk(int chunkX, int chunkZ) {
		structureLock.lock();
		try {
			long sectionPos = sectionKey(sectionX(chunkX), sectionZ(chunkZ));
			RegionSection target = sections.get(sectionPos);
			if (target == null || target.isEmpty()) {
				// Defensive no-op: the chunk system's contract is balanced
				// add/remove, but an unmatched remove (chunk load failure paths,
				// future integration bugs) must degrade to a no-op rather than
				// corrupt counters or kill the server thread. The regionizer's
				// invariants survive either way: an over-counted section is
				// reclaimed by dead-section purge at tick end.
				return;
			}
			boolean released = target.removeChunk(chunkX, chunkZ);
			if (!released) {
				return; // position not owned here: unmatched remove, no-op
			}
			ChunkMetricSink sink = this.chunkMetrics;
			if (sink != null) {
				sink.chunkUnregistered();
			}			if (target.isEmpty()) {
				// Section is now empty: it and its halo may become dead unless
				// other activity still supports them.
				haloRelease(target);
			}
		} finally {
			structureLock.unlock();
		}
	}

	// =================================================================================
	// Halo (empty-section creation radius) bookkeeping
	// =================================================================================

	/**
	 * Creates/refreshes the buffer halo around a non-empty section.
	 * A section within {@code emptySectionCreationRadius} of any non-empty
	 * section must be alive (invariant 2's physical basis).
	 */
	private void haloCreate(RegionSection center) {
		int radius = config.emptySectionCreationRadius();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				long pos = sectionKey(center.x + dx, center.z + dz);
				RegionSection section = sections.get(pos);
				if (section == null) {
					section = new RegionSection(center.x + dx, center.z + dz, true);
					sections.put(pos, section);
				} else if (!section.alive) {
					section.alive = true;
				}
			}
		}
	}

	/**
	 * After a section empties: kills empty sections that are no longer within
	 * creation radius of ANY non-empty section.
	 */
	private void haloRelease(RegionSection emptied) {
		int radius = config.emptySectionCreationRadius();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				long pos = sectionKey(emptied.x + dx, emptied.z + dz);
				RegionSection section = sections.get(pos);
				if (section == null) {
					continue;
				}
				if (!isSupported(section)) {
					section.alive = false;
				}
			}
		}
	}

	/** @return true if the section is non-empty or within creation radius of a non-empty section. */
	private boolean isSupported(RegionSection section) {
		if (section.isNotEmpty()) return true;
		int radius = config.emptySectionCreationRadius();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx == 0 && dz == 0) continue;
				RegionSection other = sections.get(sectionKey(section.x + dx, section.z + dz));
				if (other != null && other.isNotEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	// =================================================================================
	// Region creation / merge / selection
	// =================================================================================

	private Region newRegion(RegionState state) {
		Region region = new Region(idGenerator.getAsLong(), world, state);
		regions.put(region.id, region);
		if (state != RegionState.DEAD) {
			liveRegions.add(region);
		}
		return region;
	}

	private void origin(Region region, String label) {
		region.origin = label;
	}

	/**
	 * Moves a section (and implicitly its chunk count) into a region's ownership.
	 * Invariant 1 is preserved because adopt happens under the structure lock,
	 * so no section can be adopted by two regions in an observable interleaving.
	 */
	private void adopt(Region region, RegionSection section) {
		long key = sectionKey(section.x, section.z);
		region.sections.put(key, section);
		sectionOwners.put(key, region);
	}

	/**
	 * Selects a non-ticking region from {@code nearby}, preferring one that
	 * already owns the most sections (reduces merge churn), or null if all are
	 * TICKING.
	 */
	private Region selectNonTicking(Set<Region> nearby) {
		Region best = null;
		for (Region region : nearby) {
			if (region.state == RegionState.TICKING) {
				continue;
			}
			if (best == null || region.sections.size() > best.sections.size()) {
				best = region;
			}
		}
		return best;
	}

	/**
	 * Merges region {@code from} into region {@code to}.
	 *
	 * <p><strong>Reference contract (clean-room):</strong> a merge may only take
	 * place when the absorbed region is not ticking; its sections move to the
	 * target; its merge-later obligations are forwarded to the target; if the
	 * absorbed region had OTHER merge-later targets than the target itself, the
	 * target must downgrade to TRANSIENT (invariant 2 needs the merged region to
	 * re-buffer before ticking again).</p>
	 *
	 * <p>If either side is TICKING, the merge is deferred: the non-ticking side
	 * records the obligation in its mergeLater set, and the ticking side lists
	 * the non-ticking side in expectingMergeFrom, so the non-ticking region is
	 * absorbed into the ticking one at the ticking region's tick end. (The
	 * non-ticking side always carries the obligation — a ticking region can
	 * never be absorbed mid-tick, and a region carrying an obligation is
	 * downgraded to TRANSIENT so it cannot tick.)</p>
	 */
	private void mergeInto(Region from, Region to) {
		if (from == to || from.state == RegionState.DEAD) {
			return;
		}
		if (to.state == RegionState.TICKING) {
			// Defer: cannot mutate the ticking region's section set (invariant 3).
			from.mergeLater.add(to);
			to.expectingMergeFrom.add(from);
			// from must not tick while carrying a deferred merge: it cannot be
			// ready.
			if (from.state == RegionState.READY) {
				from.state = RegionState.TRANSIENT;
			// (If from is TRANSIENT already, it stays TRANSIENT.)
			}
			return;
		}
		if (from.state == RegionState.TICKING) {
			// REVERSE deferral: from is ticking and can never be absorbed while
			// ticking (it exclusively owns its sections until tick end). The
			// NON-TICKING side ('to') carries the obligation: it merges into
			// 'from' at from's tick end — reference: "x runs the merge later
			// logic into y" where x is non-ticking and y is ticking. 'to' is
			// downgraded so it cannot tick while carrying the obligation: it
			// may be absorbed (with all its state) the moment 'from' ends its
			// tick, and a region executing game work against state that is
			// about to change owners mid-flight is exactly the race the state
			// machine exists to prevent.
			to.mergeLater.add(from);
			from.expectingMergeFrom.add(to);
			to.state = RegionState.TRANSIENT;
			return;
		}

		// Immediate merge: move sections.
		for (Map.Entry<Long, RegionSection> e : from.sections.entrySet()) {
			to.sections.put(e.getKey(), e.getValue());
			sectionOwners.put(e.getKey(), to);
		}
		from.sections.clear();

		// Forward deferred obligations.
		for (Region target : from.mergeLater) {
			if (target != to) {
				to.mergeLater.add(target);
				target.expectingMergeFrom.remove(from);
				target.expectingMergeFrom.add(to);
			}
		}
		from.mergeLater.clear();

		// Downgrade rule (reference): 'to' is marked transient only if 'from'
		// contained merge-later targets that were NOT 'to' — forwarded above,
		// so a non-empty forwarded set is exactly that condition. Absorbing a
		// transient donor whose only obligation was this merge must NOT
		// downgrade: nothing would ever re-ready the absorber (there is no
		// TRANSIENT→READY transition for a region with empty mergeLater) and
		// the region would sit undispatchable forever.
		if (!to.mergeLater.isEmpty()) {
			to.state = RegionState.TRANSIENT;
		}

		killMergedRegion(from, to);
		recordMerge();
	}

	private List<Listener> listeners() {
		// Callers run under structureLock (or are the constructor); the list is
		// never null after the first addListener and effectively-final after
		// startup wiring. Copy to avoid holding the lock through callbacks is
		// unnecessary: callbacks are invoked while holding it by design.
		return listeners == null ? List.of() : listeners;
	}

	private void killRegion(Region region) {
		region.state = RegionState.DEAD;
		for (Long key : region.sections.keySet()) {
			sectionOwners.remove(key, region);
		}
		region.sections.clear();
		region.mergeLater.clear();
		region.expectingMergeFrom.clear();
		liveRegions.remove(region);
		releaseObligationsOn(region);
		for (Listener listener : listeners()) {
			listener.onRegionDead(region);
		}
	}

	/**
	 * A dead region can never honor its pending merge obligations. Every live
	 * region that was waiting to merge INTO it (mergeLater) or waiting for it
	 * to merge in (expectingMergeFrom) is released — completeTick's DEAD-donor
	 * skip only covers the donor side lazily, and the waiting side must not
	 * keep a dead target.
	 *
	 * <p>A region whose ONLY reason for being TRANSIENT was such an obligation
	 * returns to READY with a fresh deadline: the merge machinery is the sole
	 * producer of mergeLater-driven TRANSIENT states, so an emptied set restores
	 * exactly the pre-obligation state. Without this, the region sits
	 * undispatchable forever (tryBeginTick fails on TRANSIENT and no other
	 * path re-readies it) — its chunks and queue strand with it.</p>
	 */
	private void releaseObligationsOn(Region dead) {
		for (Region live : liveRegions) {
			if (live.mergeLater.remove(dead)
					&& live.mergeLater.isEmpty()
					&& live.state == RegionState.TRANSIENT) {
				live.state = RegionState.READY;
				scheduleNextTick(live);
			}
			live.expectingMergeFrom.remove(dead);
		}
	}

	/**
	 * Kills a region that was ABSORBED by {@code into}: fires
	 * {@link Listener#onRegionMerged} (not onRegionDead) so the listener re-homes
	 * the donor's queued tasks into the survivor's queue instead of dropping
	 * them (spec 7's cross-region handoff: work scheduled against a territory
	 * must follow the region that now owns it).
	 */
	private void killMergedRegion(Region donor, Region into) {
		donor.state = RegionState.DEAD;
		for (Long key : donor.sections.keySet()) {
			sectionOwners.remove(key, donor);
		}
		donor.sections.clear();
		liveRegions.remove(donor);
		for (Listener listener : listeners()) {
			listener.onRegionMerged(donor, into);
		}
	}

	// =================================================================================
	// Queries
	// =================================================================================

	/** @return the region owning the section containing this chunk, or null. */
	public Region ownerOfChunk(int chunkX, int chunkZ) {
		structureLock.lock();
		try {
			RegionSection section = sections.get(sectionKey(sectionX(chunkX), sectionZ(chunkZ)));
			return section == null ? null : ownerOfSectionLocked(section);
		} finally {
			structureLock.unlock();
		}
	}

	private Region ownerOfSectionLocked(RegionSection section) {
		if (ownerLookupIndex) {
			return sectionOwners.get(sectionKey(section.x, section.z));
		}
		for (Region region : liveRegions) {
			if (region.sections.containsKey(sectionKey(section.x, section.z))) {
				return region;
			}
		}
		return null;
	}

	/** Patch gate: the O(1) ownership index (fabricfolia.region-lookup). */
	private volatile boolean ownerLookupIndex = true;
	/** Structural counters (diagnostics; structure-lock writes). */
	private long mergedRegions;
	private long splitRegions;

	/** Sets the ownership-index gate (patch resolution; startup only). */
	public void setOwnerLookupIndex(boolean enabled) {
		this.ownerLookupIndex = enabled;
	}

	/**
	 * Zero-allocation live-region iteration for hot scan paths (the scheduler
	 * dispatch loop). The consumer runs under the structure lock and must not
	 * call back into regionizer operations (re-entrancy would deadlock — same
	 * contract as the tick-end protocol callbacks).
	 */
	public void forEachLiveRegion(java.util.function.Consumer<Region> consumer) {
		structureLock.lock();
		try {
			for (Region region : liveRegions) {
				consumer.accept(region);
			}
		} finally {
			structureLock.unlock();
		}
	}

	/** @return all live regions (READY/TICKING/TRANSIENT) in this world. */
	public List<Region> liveRegions() {
		structureLock.lock();
		try {
			return new ArrayList<>(liveRegions);
		} finally {
			structureLock.unlock();
		}
	}

	/**
	 * Snapshot of a region's owned sections (in section coordinates), taken
	 * under the structure lock.
	 *
	 * <p><strong>Why this exists:</strong> the vanilla intercept layer maps
	 * chunks that vanilla is about to tick onto regions — it needs to ask
	 * "which sections does the region I am about to execute own?" and get a
	 * stable answer that it can consult WITHOUT the structure lock while the
	 * region ticks. Invariant 3 (no growth while ticking) makes the snapshot
	 * a valid ownership picture for the tick's duration: the regionizer will
	 * not expand the set under the executor, and any merge-with-a-ticking-
	 * region activity defers to the tick end (invariant 3's flip side).</p>
	 *
	 * <p><strong>Contract:</strong> call once per tick attempt, before
	 * {@code tryBeginTick} (or at least before executing work); the snapshot
	 * is the boundary work may touch during that tick. Sections are given as
	 * {@code [sectionX, sectionZ]} pairs keyed in a long-packed form
	 * (x&lt;&lt;32 | z&amp;0xFFFFFFFF), the same packing used internally.</p>
	 */
	public Set<Long> ownedSectionsSnapshot(Region region) {
		structureLock.lock();
		try {
			return new HashSet<>(region.sections.keySet());
		} finally {
			structureLock.unlock();
		}
	}

	private Set<Region> regionsNear(RegionSection center, int radiusSections) {
		Set<Region> found = new HashSet<>();
		int radius = radiusSections;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				RegionSection s = sections.get(sectionKey(center.x + dx, center.z + dz));
				if (s == null) continue;
				Region owner = ownerOfSectionLocked(s);
				if (owner != null) {
					found.add(owner);
				}
			}
			// NOTE: 'radius' is effectively final in this loop, deliberately
			// shadowing nothing; kept simple for clarity over micro-perf.
		}
		return found;
	}

	// =================================================================================
	// Tick lifecycle (reference: tryMarkTicking / markNotTicking contract)
	// =================================================================================

	/**
	 * Scheduler dispatch path: attempts to begin a tick of {@code region}.
	 *
	 * <p><strong>Contract:</strong> returns false if the region is not READY
	 * (it may have gone TRANSIENT after scheduling — e.g. a nearby chunk load
	 * scheduled a merge into it). On false the scheduler must NOT tick the
	 * region and must re-derive its state. On true, the caller owns the region's
	 * tick and MUST call {@link #completeTick(Region)} when done, on the same
	 * worker context.</p>
	 */
	public boolean tryBeginTick(Region region) {
		structureLock.lock();
		try {
			return region.tryMarkTicking();
		} finally {
			structureLock.unlock();
		}
	}

	/**
	 * Tick-end protocol (documented order — reference: markNotTicking):
	 * <ol>
	 *   <li>process pending merges (absorb expectingMergeFrom regions);</li>
	 *   <li>if this region is pending merge into another, become TRANSIENT;</li>
	 *   <li>otherwise remove dead sections; a region that ticked away its
	 *       last section DIES here (regardless of the recalculation gate —
	 *       a zero-section region has nothing to own or tick, and leaving it
	 *       alive would strand its queue and region-local data forever);</li>
	 *   <li>otherwise attempt a split.</li>
	 * </ol>
	 *
	 * <p>Must be called by the worker context that ran the tick, exactly once
	 * per successful {@link #tryBeginTick}.</p>
	 *
	 * @param tickDurationNanos duration of the tick that just completed
	 */
	public void completeTick(Region region, long tickDurationNanos) {
		structureLock.lock();
		try {
			if (region.state != RegionState.TICKING) {
				throw new IllegalStateException("completeTick on non-ticking region " + region);
			}
			region.markNotTicking(); // assertion + named contract point

			// 1. Process pending merges INTO this region.
			for (Region donor : new ArrayList<>(region.expectingMergeFrom)) {
				if (donor.state == RegionState.DEAD) {
					region.expectingMergeFrom.remove(donor);
					continue;
				}
				// Donors are non-ticking by construction (they downgraded when
			// they acquired the mergeLater obligation).
				mergeNowLocked(donor, region);
			}
			region.expectingMergeFrom.clear();

			// 2. If we owe merges into others, we are transient. (Delayed tasks
			//    scheduled against regions we absorbed were re-homed with their
			//    queues by the scheduler listener; tick-counter deadlines keep
			//    their meaning in the merged region, which inherits the donor's
			//    counting history via the merge offset — see Region#tickCount.)
			if (!region.mergeLater.isEmpty()) {
				region.state = RegionState.TRANSIENT;
				return;
			}

			// 3. Split recalculation. Dead sections ACCUMULATE IN PLACE between
			//    recalculations (reference semantics): they are removed only by
			//    a recalculation pass, so the gate below sees the real gathered
			//    load. Purging every tick (the previous behavior) meant each
			//    death was observed alone, one tick after it happened — the
			//    gate never fired and live splits were unreachable (found in
			//    live churn validation). Region death stays unconditional and
			//    UNGATED: a region with zero alive sections has nothing to own
			//    or tick, and leaving it READY would strand queues and
			//    region-local data on a region no dispatcher can serve. (The
			//    tick counter is NOT advanced here: the executing context
			//    advances it at tick START via Region#advanceTickCounter — the
			//    delayed-task time base is "ticks started", uniform across
			//    enqueue timing.)
			int dead = countDeadSections(region);
			if (dead == region.sections.size()) {
				killRegion(region);
				return;
			}
			region.state = RegionState.READY;
			scheduleNextTick(region);
			int total = region.sections.size();
			if (total >= config.recalculationCount()
					&& dead * 100 >= config.maxDeadSectionPercent() * total) {
				removeDeadSections(region);
				attemptSplit(region, dead);
			}
		} finally {
			structureLock.unlock();
		}
	}

	/** Immediate-merge path used during tick-end (donor non-ticking, target current). */
	private void mergeNowLocked(Region from, Region to) {
		for (Map.Entry<Long, RegionSection> e : from.sections.entrySet()) {
			to.sections.put(e.getKey(), e.getValue());
			sectionOwners.put(e.getKey(), to);
		}
		from.sections.clear();
		for (Region target : from.mergeLater) {
			if (target != to) {
				to.mergeLater.add(target);
				target.expectingMergeFrom.remove(from);
				target.expectingMergeFrom.add(to);
			}
		}
		from.mergeLater.clear();
		killMergedRegion(from, to);
		recordMerge();
	}

	/** Structural metrics: one completed region merge (donor absorbed). */
	private void recordMerge() {
		mergedRegions++;
		StructuralMetricSink sink = this.structuralMetrics;
		if (sink != null) {
			sink.regionMerged();
		}
	}

	/** Structural metrics: one region split (a child born). */
	private void recordSplit() {
		splitRegions++;
		StructuralMetricSink sink = this.structuralMetrics;
		if (sink != null) {
			sink.regionSplit();
		}
	}

	/** @return cumulative absorbed regions (diagnostics; written under the structure lock). */
	public long mergedRegionCount() {
		return mergedRegions;
	}

	/** @return cumulative split children (diagnostics; written under the structure lock). */
	public long splitRegionCount() {
		return splitRegions;
	}

	/** Structural metrics: one region aborted (tick-end protocol failure). */
	private void recordAbort() {
		StructuralMetricSink sink = this.structuralMetrics;
		if (sink != null) {
			sink.regionAborted();
		}
	}

	/**
	 * Sets the next tick deadline from the region's own clock: 50ms per tick
	 * (spec 6). Written by the executing context at tick end; read by the
	 * scheduler. Regions schedule independently: a slow region only delays
	 * itself.
	 */
	private void scheduleNextTick(Region region) {
		region.nextTickDeadlineNanos = nanoClock.getAsLong() + RegionizerConfig.TICK_PERIOD_NANOS;
	}

	/**
	 * Fail-safe kill (spec 26): marks a region DEAD because its own tick-end
	 * protocol failed, firing the listener so its task queue is dropped. A
	 * region whose protocol threw can never be safely dispatched again — the
	 * alternative (leaving it TICKING forever) silently wedges every chunk it
	 * owns, which is strictly worse than the loud, bounded loss of one region.
	 * Sections it owned are released; chunks re-regionize via addChunk when the
	 * chunk system next touches them. The caller reports {@code cause}.
	 */
	public void abortTick(Region region, Throwable cause) {
		structureLock.lock();
		try {
			if (region.state != RegionState.TICKING) {
				return; // completed or already dead: nothing to abort
			}
			killRegion(region);
			recordAbort();
		} finally {
			structureLock.unlock();
		}
	}

	/** @return how many of the region's sections are currently dead (unpurged). */
	private int countDeadSections(Region region) {
		int dead = 0;
		for (RegionSection section : region.sections.values()) {
			if (!section.alive) {
				dead++;
			}
		}
		return dead;
	}

	private int removeDeadSections(Region region) {
		int removed = 0;
		Iterator<Map.Entry<Long, RegionSection>> it = region.sections.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Long, RegionSection> entry = it.next();
			RegionSection section = entry.getValue();
			if (!section.alive) {
				sectionOwners.remove(entry.getKey(), region);
				it.remove();
				removed++;
			}
		}
		return removed;
	}

	/**
	 * Attempts to split a region into independent sub-regions by flood-fill over
	 * its ALIVE sections: any maximal connected component that is a strict
	 * subset gets its own region.
	 *
	 * <p><strong>Why flood-fill is sufficient here:</strong> a region is a set of
	 * sections; independent sub-areas within it are exactly the connected
	 * components over alive sections. Only one component ever exists right after
	 * a merge; splits appear when buffer sections die off. Running this at tick
	 * end (per the reference) is sufficient to converge.</p>
	 */
	private void attemptSplit(Region region, int deadSectionsBeforePurge) {
		if (region.sections.isEmpty()) {
			killRegion(region);
			return;
		}
		// The gate was already evaluated by the caller against the
		// pre-purge load (deadSectionsBeforePurge vs. alive+dead); this
		// method only recomputes it for defense-in-depth. The alive
		// remainder is what can actually split.
		int totalBeforePurge = region.sections.size() + deadSectionsBeforePurge;
		if (totalBeforePurge == 0
				|| deadSectionsBeforePurge * 100 < config.maxDeadSectionPercent() * totalBeforePurge) {
			return; // not enough dead sections to justify recalculation
		}
		List<List<RegionSection>> components = connectedComponents(region);
		if (components.size() <= 1) {
			return; // still one connected area: nothing to split
		}
		// Largest component stays; others become new READY regions. Split
		// children inherit the parent tick counter (reference: "split children
		// inherit redstone/current tick; relative deadlines are maintained as
		// there is no tick number change") — so delayed tasks re-homed or
		// re-resolved against a child keep their meaning.
		components.sort((a, b) -> Integer.compare(b.size(), a.size()));
		List<Region> children = new ArrayList<>();
		for (int i = 1; i < components.size(); i++) {
			Region child = newRegion(RegionState.READY);
			child.origin = "split";
			for (RegionSection section : components.get(i)) {
				long key = sectionKey(section.x, section.z);
				region.sections.remove(key);
				child.sections.put(key, section);
				sectionOwners.put(key, child);
			}
			child.tickCount = region.tickCount;
			scheduleNextTick(child);
			children.add(child);
		}
		// Lifecycle hook BEFORE the parent is dispatchable again (we are
		// under the structure lock, inside completeTick's tick-end protocol):
		// listeners redistribute per-region data — queues partition by
		// chunk ownership, region-local data runs its split handler — so no
		// child ever starts with stranded ownership (mandate §8).
		if (!children.isEmpty()) {
			recordSplit();
			for (Listener listener : listeners()) {
				listener.onRegionSplit(region, List.copyOf(children));
			}
		}
	}

	private List<List<RegionSection>> connectedComponents(Region region) {
		List<List<RegionSection>> components = new ArrayList<>();
		Set<Long> visited = new HashSet<>();
		for (RegionSection start : region.sections.values()) {
			if (!sectionAlive(start) || visited.contains(sectionKey(start.x, start.z))) {
				continue;
			}
			List<RegionSection> component = new ArrayList<>();
			java.util.ArrayDeque<RegionSection> stack = new java.util.ArrayDeque<>();
			stack.push(start);
			visited.add(sectionKey(start.x, start.z));
			while (!stack.isEmpty()) {
				RegionSection current = stack.pop();
				component.add(current);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dz == 0) continue;
						long neighborKey = sectionKey(current.x + dx, current.z + dz);
						if (visited.contains(neighborKey)) continue;
						RegionSection neighbor = region.sections.get(neighborKey);
						if (neighbor != null && sectionAlive(neighbor)) {
							visited.add(neighborKey);
							stack.push(neighbor);
						}
					}
				}
			}
			components.add(component);
		}
		return components;
	}

	private static boolean sectionAlive(RegionSection section) {
		return section.alive;
	}
}
