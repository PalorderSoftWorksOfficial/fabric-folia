/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.scheduler;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Entity migration as a first-class system (mandate §15): every entity is
 * owned by exactly one region at any instant, per-region entity sets are
 * region-local data (the first real {@link RegionLocalData} consumer), and
 * migration is an explicit atomic protocol — never a concurrent mutation of
 * two regions' state from two threads.
 *
 * <p><strong>Ownership (mandate §15 steps 1–3):</strong> the authoritative
 * handle→region map is structural state, mutated only (a) by {@link #migrate}
 * and {@link #unregister} under a per-entity stripe lock and (b) by the
 * regionizer's structural transitions (merge/split/death) under the
 * regionizer structure lock. Ticking region threads only ever READ through
 * {@link #ownerOfHandle}; they never write it — single-writer by construction,
 * no global lock on the read path (mandate §31).</p>
 *
 * <p><strong>The migration protocol (one stripe lock):</strong>
 * remove-from-old + add-to-new + repoint the authoritative map is one
 * critical section. A region thread can observe pre- or post-migration state,
 * never the torn middle — which is what guarantees an entity is never ticked
 * by two regions simultaneously (mandate §15 step 2) and never disappears
 * during migration (mandate §15 step 5).</p>
 *
 * <p><strong>Why stripes, not one lock:</strong> a single lock over all
 * migrations would serialize unrelated regions' entity movement — the global
 * bottleneck §31 forbids. Stripes partition the lock space by handle
 * identity; different entities migrating between different region pairs
 * proceed in parallel. Stripe locks are NEVER taken while holding the
 * regionizer structure lock (documented lock order: structure → stripes), so
 * the structural handlers below retarget the authoritative map directly —
 * the structure lock already excludes every other structural writer, and
 * stripe holders only ever touch their own entity's rows.</p>
 *
 * <p><strong>Region death:</strong> entities owned by the dead region are
 * retired (mapping dropped, listeners fire) — an entity cannot outlive a
 * world structure that no longer exists, and no migration lock of a dead
 * region is left behind anywhere.</p>
 *
 * <p><strong>Splits:</strong> children are born TRANSIENT, so no child thread
 * can race the retarget. Each of the parent's entities is re-owned by the
 * child that owns its home chunk now, via the caller-supplied
 * {@code homeChunkResolver}; entities whose home chunk has no owner (purged
 * section) are retired.</p>
 *
 * <p><strong>Merges:</strong> the survivor's per-region set absorbs the
 * donor's through the data layer; the authoritative map is bulk-retargeted
 * to the survivor.</p>
 *
 * <p><strong>The common-module boundary:</strong> this class has no Minecraft
 * types. The fabric module supplies the home-chunk resolver (entity → packed
 * chunk position) and calls {@link #register}/{@link #migrate}/
 * {@link #unregister} from the entity add/remove/move hooks it owns; the
 * machinery here is complete and tested against fakes.</p>
 *,
 * <p><strong>Listener ordering (load-bearing):</strong> the regionizer fires
 * listeners in registration order, and the hub's split handler OVERWRITES
 * child data entries with fresh sets. This registry must therefore register
 * itself AFTER the hub — construct the hub, {@code hub.attachTo(regionizer)},
 * then call {@link #attach()}. The registry's split handler then runs after
 * the hub's, sees the fresh empty child sets, and fills them from the
 * authoritative map.</p>
 */
public final class RegionEntityRegistry implements WorldRegionizer.Listener, AutoCloseable {

	/** Stripe count: power of two, keeps {@code stripeFor} mask-friendly. */
	private static final int STRIPES = 16;

	private final WorldRegionizer regionizer;
	private final RegionLocalData<Set<Object>> perRegionEntities;
	private final ConcurrentHashMap<Object, Region> ownerByHandle = new ConcurrentHashMap<>();
	private final ReentrantLock[] migrationLocks = new ReentrantLock[STRIPES];
	private final Function<Object, Long> homeChunkResolver;
	private final AtomicLong migrationsCompleted = new AtomicLong();
	private final AtomicLong retiredCount = new AtomicLong();

	/** Retired callbacks fired when an entity is retired (region death, purged home). */
	private final Set<Consumer<Object>> retirementListeners = ConcurrentHashMap.newKeySet();

	/**
	 * @param regionizer        the world's regionizer (source of ownership truth)
	 * @param hub               the world's region-data hub (per-region entity sets);
	 *                          must already be attached to the regionizer
	 * @param homeChunkResolver handle → packed chunk position in
	 *                          {@link RegionScheduler#packChunkPos} layout
	 *                          ((chunkZ &lt;&lt; 32) | (chunkX &amp; 0xFFFFFFFF)); decode with
	 *                          {@link RegionScheduler#chunkPosX}/{@link RegionScheduler#chunkPosZ}.
	 *                          May return null for an entity with no home chunk
	 */
	public RegionEntityRegistry(WorldRegionizer regionizer, RegionDataHub hub,
	                            Function<Object, Long> homeChunkResolver) {
		this.regionizer = regionizer;
		this.homeChunkResolver = homeChunkResolver;
		for (int i = 0; i < STRIPES; i++) {
			migrationLocks[i] = new ReentrantLock();
		}
		this.perRegionEntities = new RegionLocalData<>("entities", new RegionLocalData.Lifecycle<>() {
			@Override
			public Set<Object> create(Region region) {
				return ConcurrentHashMap.newKeySet();
			}

			@Override
			public void onMerge(Region donor, Region into, Set<Object> donorData, Set<Object> intoData) {
				intoData.addAll(donorData);
			}

			@Override
			public Set<Object> onSplit(Region parent, Region child, Set<Object> parentData) {
				// The child's set starts empty; the registry's own structural
				// listener (registered AFTER the hub) fills it by walking the
				// authoritative map — the one place that knows each entity's
				// home chunk. Keeping the set move in one place avoids two
				// code paths disagreeing about who owns an entity.
				return ConcurrentHashMap.newKeySet();
			}

			@Override
			public void onDestroy(Region region, Set<Object> data) {
				// Set release happens in onRegionDead below, together with
				// the authoritative-map retire (one place, one ordering).
			}
		}, hub);
	}

	/**
	 * Registers this registry as a regionizer listener. MUST be called after
	 * {@code hub.attachTo(regionizer)} — see the listener-ordering note on
	 * the class. Idempotent registration is avoided deliberately: calling
	 * attach twice is a wiring bug, not a tolerated one.
	 */
	public void attach() {
		regionizer.addListener(this);
	}

	/**
	 * Registers an entity handle as owned by {@code region} (login placement,
	 * entity add). MUST be called under {@code region}'s execution context or
	 * from structural transition code under the regionizer lock — never from
	 * an arbitrary thread.
	 *
	 * @return true if the entity was newly registered; false if it was
	 * 		already owned (by any region — re-owning from a non-owner context
	 * 		is a protocol violation the caller should treat as a bug)
	 */
	public boolean register(Object handle, Region region) {
		if (handle == null || region == null) {
			throw new IllegalArgumentException("handle and region must be non-null");
		}
		if (ownerByHandle.putIfAbsent(handle, region) != null) {
			return false;
		}
		perRegionEntities.getOrCreateStructural(region).add(handle);
		return true;
	}

	/** @return the region currently owning {@code handle}, or null if unowned/retired. */
	public Region ownerOfHandle(Object handle) {
		if (handle == null) {
			return null;
		}
		Region region = ownerByHandle.get(handle);
		if (region == null || region.isDead()) {
			return null;
		}
		return region;
	}

	/**
	 * Migrates {@code handle} to its new owning region atomically (mandate
	 * §15 steps 1–6): remove-from-old, add-to-new, repoint the authoritative
	 * map — one critical section per entity stripe. Callable from any thread
	 * (region threads, structural threads); the stripe lock makes the
	 * protocol safe regardless of who calls.
	 *
	 * @return true if the migration happened; false if the entity was not
	 * 		previously registered, is dead, or already belongs to {@code to}
	 */
	public boolean migrate(Object handle, Region to) {
		if (handle == null || to == null) {
			throw new IllegalArgumentException("handle and region must be non-null");
		}
		ReentrantLock stripe = migrationLocks[stripeFor(handle)];
		stripe.lock();
		try {
			Region from = ownerByHandle.get(handle);
			if (from == null || from == to || from.isDead()) {
				return false;
			}
			Set<Object> fromSet = perRegionEntities.existingStructural(from);
			if (fromSet != null) {
				fromSet.remove(handle);
			}
			perRegionEntities.getOrCreateStructural(to).add(handle);
			ownerByHandle.put(handle, to);
			migrationsCompleted.incrementAndGet();
			return true;
		} finally {
			stripe.unlock();
		}
	}

	/**
	 * Removes {@code handle} entirely (entity genuinely removed from the
	 * world — death, discard). The stripe lock guards against racing a
	 * concurrent migration of the same entity.
	 *
	 * @return true if the entity was registered and is now removed
	 */
	public boolean unregister(Object handle) {
		if (handle == null) {
			return false;
		}
		ReentrantLock stripe = migrationLocks[stripeFor(handle)];
		stripe.lock();
		try {
			Region from = ownerByHandle.remove(handle);
			if (from == null) {
				return false;
			}
			Set<Object> fromSet = perRegionEntities.existingStructural(from);
			if (fromSet != null) {
				fromSet.remove(handle);
			}
			return true;
		} finally {
			stripe.unlock();
		}
	}

	/**
	 * The per-region entity set: the region's own entities, readable and
	 * mutable only under that region's execution context (mandate §7 —
	 * region-local by construction, no global synchronized list).
	 */
	public RegionLocalData<Set<Object>> perRegionEntities() {
		return perRegionEntities;
	}

	/**
	 * The entity-scheduler resolver this registry feeds: handle → current
	 * owning region (null once retired/unregistered), re-consulted at task
	 * execution time so entity tasks follow migrations (mandate §14/§15).
	 */
	public EntitySchedulerImpl.EntityResolver resolver() {
		return this::ownerOfHandle;
	}

	/**
	 * Callback invoked when an entity is retired (its region died, or its
	 * home chunk vanished in a split). Listeners run on the structural
	 * thread, once per retired entity.
	 */
	public void addRetirementListener(Consumer<Object> listener) {
		retirementListeners.add(java.util.Objects.requireNonNull(listener, "listener"));
	}

	/** @return total completed migrations (diagnostics, mandate §35). */
	public long migrationsCompleted() {
		return migrationsCompleted.get();
	}

	/** @return total entities retired by structural transitions (diagnostics). */
	public long retiredCount() {
		return retiredCount.get();
	}

	/** @return number of live authoritative mappings (diagnostics). */
	public int trackedCount() {
		return ownerByHandle.size();
	}

	// --- Structural transitions (WorldRegionizer.Listener, structure lock) ---

	@Override
	public void onRegionMerged(Region donor, Region into) {
		// Bulk retarget under the structure lock (no stripes taken — see the
		// documented lock order). Data-set adoption ran earlier via the hub.
		for (Map.Entry<Object, Region> e : ownerByHandle.entrySet()) {
			if (e.getValue() == donor) {
				e.setValue(into);
			}
		}
	}

	@Override
	public void onRegionSplit(Region parent, List<Region> children) {
		// Children are TRANSIENT: nobody can race this retarget with a tick.
		Map<Region, List<Object>> byNewOwner = new HashMap<>();
		List<Object> homeless = new ArrayList<>();
		for (Map.Entry<Object, Region> e : ownerByHandle.entrySet()) {
			if (e.getValue() != parent) {
				continue;
			}
			Long packed = homeChunkResolver.apply(e.getKey());
			Region newOwner = packed == null
					? null
					: regionizer.ownerOfChunk(RegionScheduler.chunkPosX(packed), RegionScheduler.chunkPosZ(packed));
			if (newOwner == null) {
				homeless.add(e.getKey());
			} else {
				byNewOwner.computeIfAbsent(newOwner, k -> new ArrayList<>()).add(e.getKey());
			}
		}
		for (Map.Entry<Region, List<Object>> e : byNewOwner.entrySet()) {
			Set<Object> childSet = perRegionEntities.getOrCreateStructural(e.getKey());
			for (Object handle : e.getValue()) {
				childSet.add(handle);
				ownerByHandle.put(handle, e.getKey());
			}
		}
		for (Object handle : homeless) {
			retire(handle);
		}
	}

	@Override
	public void onRegionDead(Region region) {
		List<Object> doomed = new ArrayList<>();
		for (Map.Entry<Object, Region> e : ownerByHandle.entrySet()) {
			if (e.getValue() == region) {
				doomed.add(e.getKey());
			}
		}
		for (Object handle : doomed) {
			retire(handle);
		}
		Set<Object> set = perRegionEntities.existingStructural(region);
		if (set != null) {
			set.clear();
		}
	}

	private void retire(Object handle) {
		if (ownerByHandle.remove(handle) != null) {
			retiredCount.incrementAndGet();
			for (Consumer<Object> listener : retirementListeners) {
				listener.accept(handle);
			}
		}
	}

	private int stripeFor(Object handle) {
		return System.identityHashCode(handle) & (STRIPES - 1);
	}

	@Override
	public void close() {
		ownerByHandle.clear();
		retirementListeners.clear();
	}
}
