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
		ReentrantLock stripe = migrationLocks[stripeFor(handle)];
		stripe.lock();
		try {
			if (ownerByHandle.putIfAbsent(handle, region) != null) {
				return false;
			}
			perRegionEntities.getOrCreateStructural(region).add(handle);
			return true;
		} finally {
			stripe.unlock();
		}
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
		Set<Object> parentSet = perRegionEntities.existingStructural(parent);
		for (Map.Entry<Region, List<Object>> e : byNewOwner.entrySet()) {
			Set<Object> childSet = perRegionEntities.getOrCreateStructural(e.getKey());
			for (Object handle : e.getValue()) {
				if (parentSet != null) {
					parentSet.remove(handle);
				}
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
		Region previous = ownerByHandle.remove(handle);
		if (previous != null) {
			Set<Object> previousSet = perRegionEntities.existingStructural(previous);
			if (previousSet != null) {
				previousSet.remove(handle);
			}
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
