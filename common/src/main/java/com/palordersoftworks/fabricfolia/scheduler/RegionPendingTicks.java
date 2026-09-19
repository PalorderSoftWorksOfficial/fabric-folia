/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region-owned ledger of pending scheduled ticks (block or fluid), keyed by
 * the CHUNK position each tick targets — the clean-room first step of
 * mandate §21's "each region must maintain the tick state necessary for its
 * owned world data": the worker-visible half of vanilla's coordinator,
 * owned per region, without touching the server-global {@code LevelTicks}
 * structures.
 *
 * <p><strong>Why this exists:</strong> block behaviors that run on region
 * workers (observers, tripwire, targets, lightning rods — every one of them
 * reachable from random ticks and neighbor updates in staged bodies) ask
 * {@code level.getBlockTicks().hasScheduledTick(...)} before re-scheduling.
 * Under vanilla threading that read is serialized with the writer; under
 * regionized execution the coordinator is server-thread state and a worker
 * read is a data race. The ledger gives each region its own accounting of
 * the pending ticks its chunks own, so the worker question is answered from
 * region-local state.</p>
 *
 * <p><strong>Two-phase lifecycle (mirrors ScheduledTickDeferral):</strong></p>
 * <ol>
 *   <li><strong>Capture</strong> — a worker's scheduleTick lands in the
 *       ledger via {@link #record} at the same moment the tick object is
 *       buffered for server-thread replay. Owner = the capturing region
 *       (the worker's context), so no regionizer lock is taken.</li>
 *   <li><strong>Release</strong> — the server thread re-inserts the tick
 *       into vanilla's container, but the ledger entry's job is done only
 *       when the tick actually EXECUTES (the drain, next tick or later):
 *       {@link #release} at drain time keeps the ledger true through that
 *       window. One capture → one release: the deferral protocol guarantees
 *       exactly one drain execution per buffered tick.</li>
 * </ol>
 *
 * <p><strong>Ownership semantics:</strong> the key is the target chunk — the
 * same identity vanilla's coordinator uses (its per-chunk containers) — so
 * structural transitions move entries exactly when ownership moves:</p>
 * <ul>
 *   <li><strong>Merge</strong> — donor counts fold into the survivor
 *       (identical chunk keys coincide; counts add).</li>
 *   <li><strong>Split</strong> — entries partition by which child owns each
 *       chunk (children are installed before the regionizer fires the
 *       event, so {@code ownerOfChunk} is authoritative). A chunk is owned
 *       by exactly one region: counts never divide.</li>
 *   <li><strong>Death</strong> — the region died; its chunks are gone and
 *       its pending ticks could never have drained; the entries drop with
 *       the region.</li>
 * </ul>
 *
 * <p><strong>Answering questions:</strong> {@link #isPending} takes no
 * regionizer lock on the worker fast path (context → per-region map → chunk
 * map, two gets). Foreign/server-thread callers fall back to the structural
 * owner lookup. An answer can be conservatively TRUE where vanilla would
 * dedup the duplicate anyway ({@code LevelChunkTicks.schedule} is a
 * set-add) — never falsely empty for a tick that will actually run, which
 * is the direction blocks depend on.</p>
 */
public final class RegionPendingTicks {

	/**
	 * Per-region data: pending-tick counts by target chunk, packed
	 * {@code (chunkX & 0xFFFFFFFF) | (chunkZ & 0xFFFFFFFF) << 32} — the same
	 * packing vanilla's coordinator uses. Concurrent because the owning
	 * worker's {@code record}, the server thread's {@code release}, and
	 * other regions' {@code isPending} reads all touch the same map (the
	 * region-locality here is per-ENTRY, not per-map).
	 */
	private static final class Ledger {
		final Map<Long, Integer> counts = new ConcurrentHashMap<>();
	}

	/** Lifecycle: fold on merge, partition on split, drop on death. */
	private final class Lifecycle implements RegionLocalData.Lifecycle<Ledger> {
		@Override
		public Ledger create(Region region) {
			return new Ledger();
		}

		@Override
		public void onMerge(Region donor, Region into, Ledger donorData, Ledger intoData) {
			for (Map.Entry<Long, Integer> e : donorData.counts.entrySet()) {
				intoData.counts.merge(e.getKey(), e.getValue(), Integer::sum);
			}
			donorData.counts.clear();
		}

		@Override
		public Ledger onSplit(Region parent, Region child, Ledger parentData) {
			Ledger childLedger = new Ledger();
			var it = parentData.counts.entrySet().iterator();
			while (it.hasNext()) {
				var e = it.next();
				if (regionizer.ownerOfChunk(chunkX(e.getKey()), chunkZ(e.getKey())) == child) {
					childLedger.counts.put(e.getKey(), e.getValue());
					it.remove();
				}
			}
			return childLedger;
		}

		@Override
		public void onDestroy(Region region, Ledger data) {
			data.counts.clear(); // dropped with the region; nothing to hand off
		}
	}

	private final WorldRegionizer regionizer;
	private final RegionLocalData<Ledger> data;

	/**
	 * @param name       diagnostics name (e.g. {@code "pendingBlockTicks"})
	 * @param regionizer the world's regionizer (owner lookups for release and
	 *                   split partitioning)
	 * @param hub        this world's region-data hub (lifecycle fan-out)
	 */
	public RegionPendingTicks(String name, WorldRegionizer regionizer, RegionDataHub hub) {
		this.regionizer = Objects.requireNonNull(regionizer, "regionizer");
		this.data = new RegionLocalData<>(name, new Lifecycle(), hub);
	}

	/** @return the diagnostics name of this ledger. */
	public String name() {
		return data.name();
	}

	/**
	 * Records a pending tick targeting {@code (chunkX, chunkZ)}. MUST run in
	 * the owning region's context (the worker that captured the tick): the
	 * count lands in THAT region's ledger with no regionizer lock. Caller
	 * guarantees one record per captured tick (the deferral protocol's
	 * capture path).
	 */
	public void record(int chunkX, int chunkZ) {
		ThreadOwnership.Context current = ThreadOwnership.current();
		if (current.kind() != com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION) {
			throw new com.palordersoftworks.fabricfolia.api.ThreadContextViolationException(
					"Pending-tick ledger records only from a region context (got " + current.kind()
							+ "); schedule through the region scheduler instead.");
		}
		Region region = (Region) current.region();
		data.get(region).counts.merge(pack(chunkX, chunkZ), 1, Integer::sum);
	}

	/**
	 * Releases one pending tick targeting {@code (chunkX, chunkZ)} — the
	 * drain executed it. Runs on the SERVER thread (the drain's thread), so
	 * it resolves the structural owner. A region that died mid-window (its
	 * entries dropped) or never owned the chunk releases nothing.
	 *
	 * @return true when a ledger entry was actually removed — i.e. this tick
	 *         was worker-captured and the record/release pair closed.
	 *         Server-thread-originated ticks have no entry and return false,
	 *         which keeps the records−releases balance meaningful.
	 */
	public boolean release(int chunkX, int chunkZ) {
		long key = pack(chunkX, chunkZ);
		Region owner = regionizer.ownerOfChunk(chunkX, chunkZ);
		if (owner == null) {
			return false; // no region owns the area anymore: nothing is owed
		}
		Ledger ledger = data.peekForDiagnostics(owner);
		if (ledger == null) {
			return false; // region never recorded anything: nothing to release
		}
		boolean[] removed = {false};
		ledger.counts.computeIfPresent(key, (k, count) -> {
			removed[0] = true;
			return count <= 1 ? null : count - 1;
		});
		return removed[0];
	}

	/**
	 * Answers {@code hasScheduledTick}-class questions for the tick-chunk:
	 * the caller's own region's ledger first (lock-free), then the
	 * structural owner's ledger for foreign-region callers. A region with no
	 * entry answers false — entries exist only while a capture→drain window
	 * is open, so an empty ledger is a true "no".
	 */
	public boolean isPending(int chunkX, int chunkZ) {
		long key = pack(chunkX, chunkZ);
		ThreadOwnership.Context current = ThreadOwnership.current();
		if (current.kind() == com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION) {
			Ledger ledger = data.peekForDiagnostics((Region) current.region());
			if (ledger != null && ledger.counts.containsKey(key)) {
				return true;
			}
			// Fall through: the context's region may not own this chunk (a
			// worker asking about a neighbor position is rare but legal);
			// the structural lookup below gives the authoritative answer.
		}
		Region owner = regionizer.ownerOfChunk(chunkX, chunkZ);
		if (owner == null) {
			return false; // no region, no ledger, no pending ticks of ours
		}
		Ledger ledger = data.peekForDiagnostics(owner);
		return ledger != null && ledger.counts.containsKey(key);
	}

	/** @return total pending ticks tracked for {@code region} (diagnostics). */
	public int pendingCount(Region region) {
		Ledger ledger = data.peekForDiagnostics(region);
		int total = 0;
		if (ledger != null) {
			for (Integer count : ledger.counts.values()) {
				total += count;
			}
		}
		return total;
	}

	/** @return number of regions currently holding ledger entries (diagnostics). */
	public int ledgerCount() {
		return data.entryCount();
	}

	/** Vanilla-compatible chunk-key packing (ChunkPos.pack semantics). */
	public static long pack(int chunkX, int chunkZ) {
		return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ & 0xFFFFFFFFL) << 32;
	}

	/** @return the chunk X packed by {@link #pack}. */
	public static int chunkX(long packed) {
		return (int) packed;
	}

	/** @return the chunk Z packed by {@link #pack}. */
	public static int chunkZ(long packed) {
		return (int) (packed >> 32);
	}
}
