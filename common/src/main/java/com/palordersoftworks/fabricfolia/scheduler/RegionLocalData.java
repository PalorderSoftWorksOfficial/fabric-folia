/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.api.ThreadContextViolationException;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region-local data (Folia's {@code RegionizedData} concept, clean-room):
 * lets systems attach state to a region that is ONLY accessed under that
 * region's execution context — no global locks, no concurrent access, the
 * whole point of regionization (mandate §7/§26).
 *
 * <p><strong>Lifecycle (the part a plain map cannot give you):</strong> when
 * regions merge, split, or die, the {@link Lifecycle} callbacks run so the
 * data moves with the ownership instead of being lost or duplicated:</p>
 * <ul>
 *   <li><strong>Merge</strong> — {@code donor} is absorbed into {@code into}:
 *       the callback receives both data objects and reconciles them
 *       (typically: merge contents into the surviving object). The donor's
 *       entry is removed after the callback.</li>
 *   <li><strong>Split</strong> — {@code parent} was divided into children:
 *       the callback produces each child's initial data from the parent's
 *       (typically: redistribute entries by which child owns them). The
 *       parent keeps its (now-smaller) data.</li>
 *   <li><strong>Destroy</strong> — the region died: the callback releases
 *       the data (close, drop, diagnostics), and the entry is removed.</li>
 * </ul>
 *
 * <p><strong>Context rules:</strong> {@link #get} runs in a region context —
 * it creates lazily on first access and is enforced with the same
 * STRICT/WARN/OFF validation as every other ownership check. Lifecycle
 * callbacks run on the regionizer's structural thread (under the structure
 * lock) and do NOT hold a region context — they receive the data objects
 * directly and must not call {@link #get}.</p>
 *
 * @param <T> the per-region data type
 */
public final class RegionLocalData<T> {

	/**
	 * Lifecycle hooks invoked on structural transitions. All methods run
	 * under the regionizer structure lock, on the thread performing the
	 * transition — never in a region context, never concurrently with
	 * another structural transition of the same world.
	 */
	public interface Lifecycle<T> {
		/** Creates the data for {@code region} (first access, region context). */
		T create(Region region);

		/**
		 * {@code donor} merged into {@code into}: reconcile
		 * {@code donorData} into {@code intoData}. The donor entry is
		 * removed after this returns.
		 */
		void onMerge(Region donor, Region into, T donorData, T intoData);

		/**
		 * {@code parent} split; produce the initial data for {@code child}
		 * (a strict subset of the parent's sections) from {@code parentData}.
		 * The parent keeps its own (already-reduced) data.
		 */
		T onSplit(Region parent, Region child, T parentData);

		/** {@code region} died: release {@code data}. The entry is removed after. */
		void onDestroy(Region region, T data);
	}

	private final String name;
	private final Lifecycle<T> lifecycle;
	private final Map<Region, T> dataByRegion = new ConcurrentHashMap<>();

	/** @param name diagnostics identity (e.g. "entities", "blockTicks") */
	public RegionLocalData(String name, Lifecycle<T> lifecycle, RegionDataHub hub) {
		this.name = Objects.requireNonNull(name, "name");
		this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
		Objects.requireNonNull(hub, "hub").register(this);
	}

	/** @return the diagnostics name of this data set. */
	public String name() {
		return name;
	}

	/**
	 * Returns the data for {@code region}, creating it on first access.
	 * MUST be called in {@code region}'s execution context — any other
	 * context is a thread-ownership violation (reported per the configured
	 * validation mode, then thrown: handing out region-owned data to the
	 * wrong context is exactly the race regionization exists to prevent).
	 */
	public T get(Region region) {
		Objects.requireNonNull(region, "region");
		ThreadOwnership.Context current = ThreadOwnership.current();
		boolean owns = current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
				&& current.region() == region; // identity: Region implements RegionInfo
		if (!owns) {
			String where = current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION
					? "region " + current.region().world() + ":" + current.region().regionId()
					: current.kind().toString();
			throw new ThreadContextViolationException(
					"Region-local data '" + name + "' accessed for region "
							+ region.world() + ":" + region.regionId() + " from " + where
							+ ". Region-local state may only be touched by its owning region"
							+ " — schedule through the region scheduler instead.");
		}
		return dataByRegion.computeIfAbsent(region, lifecycle::create);
	}

	/**
	 * Structural access WITHOUT a region context: returns the data if it
	 * exists, without creating it. For the same callers as the lifecycle
	 * callbacks — structural transition work running under the regionizer's
	 * structure lock (the entity-migration protocol, which must move
	 * ownership atomically regardless of which region a worker happens to
	 * be ticking). Must never be handed to gameplay code.
	 */
	T existingStructural(Region region) {
		return dataByRegion.get(region);
	}

	/**
	 * Structural access WITHOUT a region context that creates the data if
	 * absent (same privilege and constraints as {@link #existingStructural}).
	 */
	T getOrCreateStructural(Region region) {
		return dataByRegion.computeIfAbsent(region, lifecycle::create);
	}

	/**
	 * Diagnostics-only read: returns the data if it exists, without creating
	 * it and without a context check. The returned object is live region
	 * state — callers must not mutate it; this exists for {@code /folia}
	 * diagnostics and tests, where reading a snapshot is worth the caveat.
	 */
	public T peekForDiagnostics(Region region) {
		return dataByRegion.get(region);
	}

	// Lifecycle plumbing — invoked ONLY by the RegionDataHub (regionizer
	// listener fan-out). Package-private to keep the call graph explicit.

	void onMerged(Region donor, Region into) {
		T donorData = dataByRegion.remove(donor);
		if (donorData == null) {
			return; // donor never had data: nothing to reconcile
		}
		T intoData = dataByRegion.get(into);
		if (intoData == null) {
			// into has not been touched yet: adopt the donor's data as its own
			// (it now owns everything the donor owned).
			dataByRegion.put(into, donorData);
			return;
		}
		lifecycle.onMerge(donor, into, donorData, intoData);
	}

	void onSplit(Region parent, List<Region> children) {
		T parentData = dataByRegion.get(parent);
		if (parentData == null) {
			return; // no data yet: children create lazily in their own context
		}
		for (Region child : children) {
			dataByRegion.put(child, lifecycle.onSplit(parent, child, parentData));
		}
	}

	void onDestroyed(Region region) {
		T data = dataByRegion.remove(region);
		if (data != null) {
			lifecycle.onDestroy(region, data);
		}
	}

	/** @return number of live data entries (diagnostics/tests). */
	int entryCount() {
		return dataByRegion.size();
	}
}
