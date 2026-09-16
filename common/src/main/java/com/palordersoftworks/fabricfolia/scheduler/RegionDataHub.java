/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The fan-out point between the regionizer's structural transitions and every
 * {@link RegionLocalData} set: implemented as an additional
 * {@link WorldRegionizer.Listener} so merge/split/dead events reach region
 * data without the scheduler's own listener needing to know about data sets
 * (mandate §26: merge handlers, split handling, destroy release).
 *
 * <p><strong>Ordering:</strong> listeners fire under the regionizer structure
 * lock in registration order. The hub is registered
 * <em>after</em> the scheduler's queue listener, so queues re-home/drop
 * before data callbacks run — a data merge handler that schedules follow-up
 * work lands in the post-transition queue registry, not the pre-transition
 * one.</p>
 */
public final class RegionDataHub implements WorldRegionizer.Listener {

	private final List<RegionLocalData<?>> dataSets = new CopyOnWriteArrayList<>();

	/** Called by {@link RegionLocalData}'s constructor; not public API. */
	void register(RegionLocalData<?> dataSet) {
		dataSets.add(dataSet);
	}

	/** @return number of registered data sets (diagnostics/tests). */
	public int registeredCount() {
		return dataSets.size();
	}

	/** Attaches the hub to a regionizer (one hub per world regionizer). */	public void attachTo(WorldRegionizer regionizer) {
		regionizer.addListener(this);
	}

	@Override
	public void onRegionMerged(Region donor, Region into) {
		for (RegionLocalData<?> dataSet : dataSets) {
			dataSet.onMerged(donor, into);
		}
	}

	@Override
	public void onRegionSplit(Region parent, List<Region> children) {
		for (RegionLocalData<?> dataSet : dataSets) {
			dataSet.onSplit(parent, children);
		}
	}

	@Override
	public void onRegionDead(Region region) {
		for (RegionLocalData<?> dataSet : dataSets) {
			dataSet.onDestroyed(region);
		}
	}
}
