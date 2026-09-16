/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.entity;

import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.EntitySchedulerImpl;
import com.palordersoftworks.fabricfolia.scheduler.RegionDataHub;
import com.palordersoftworks.fabricfolia.scheduler.RegionEntityRegistry;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.util.function.Consumer;

/**
 * The fabric-side bridge between vanilla's entity lifecycle and the tested
 * {@link RegionEntityRegistry} migration protocol — the first live consumer
 * of the regionized entity ownership system (mandate §15).
 *
 * <p><strong>Thread discipline (load-bearing):</strong> every public entry
 * point here runs on the SERVER THREAD — vanilla calls {@code addEntity} and
 * {@code setPos} from its tick, chunk-load, and worldgen paths on that one
 * thread, and the mixin hooks forward synchronously. That single-writer
 * discipline makes the resolve→register/migrate sequence race-free against
 * the regionizer: {@link WorldRegionizer#ownerOfChunk} takes the structure
 * lock itself, and the registry's protocol is atomic per entity. If entity
 * ticks ever move OFF the server thread (the next integration phase), these
 * entry points must be re-plumbed onto region contexts — the registry
 * protocol stays valid, the callers change. Documented, not pretended.</p>
 *
 * <p><strong>What is wired (honest scope):</strong> entity add (all vanilla
 * spawn paths via the {@code ServerLevel.addEntity} funnel), removal (every
 * {@code setRemoved} reason), and server-thread movement re-homing via the
 * registry's atomic {@code migrate}. Entity TICKING stays on the server
 * thread this phase; the registry's per-region sets and the entity
 * scheduler's follow semantics are live for scheduling and diagnostics now,
 * so when ticking moves onto region workers the ownership substrate is
 * already proven.</p>
 *
 * <p><strong>The chunk cache:</strong> region ownership can only change when
 * an entity crosses a CHUNK boundary — within-chunk movement cannot change
 * the owning region. The mixin's per-move hook compares the entity's packed
 * chunk against its cached value (two shifts + a long compare) and calls
 * {@link #onEntityMoved} only on an actual crossing, so the common case is
 * lock-free and the hot path never touches the regionizer's structure lock
 * for stationary or intra-chunk movement.</p>
 */
public final class EntityRegionTracker {

	private final ServerLevel level;
	private final String worldName;
	private final WorldRegionizer regionizer;
	private final RegionEntityRegistry registry;
	private final Consumer<String> info;

	/**
	 * Creates the tracker and its registry wiring. The hub MUST be attached
	 * to the regionizer before this constructor runs (listener order: hub
	 * first, registry second — see {@link RegionEntityRegistry}'s
	 * listener-ordering note).
	 *
	 * @param level      the tracked dimension
	 * @param worldName  stable dimension name (registry key)
	 * @param regionizer the world's regionizer
	 * @param hub        the world's region-data hub (already attached)
	 * @param info       startup/diagnostic log sink
	 */
	public EntityRegionTracker(ServerLevel level, String worldName,
	                           WorldRegionizer regionizer, RegionDataHub hub,
	                           Consumer<String> info) {
		this.level = level;
		this.worldName = worldName;
		this.regionizer = regionizer;
		this.registry = new RegionEntityRegistry(regionizer, hub, EntityRegionTracker::homeChunkOf);
		// Listener order (load-bearing): the hub's split handler overwrites
		// child data entries with fresh empty sets; the registry must run
		// after it, see the fresh sets, and fill them from the authoritative
		// map. The constructor wires the data layer; attach() registers the
		// structural listener in that order.
		this.registry.attach();
		this.info = info;
	}

	/**
	 * Backfills every entity already loaded in this dimension (startup:
	 * worlds load their chunks before SERVER_STARTED attaches the tracker).
	 * Server thread. Entities whose chunk has no owning region yet register
	 * lazily on first boundary crossing — chunk regionization stays the
	 * chunk layer's job.
	 */
	public void backfillExisting() {
		int registered = 0;
		for (Entity entity : level.getAllEntities()) {
			if (onEntityAdded(entity)) {
				registered++;
			}
		}
		info.accept("  Entity tracking: " + registered + " existing entities registered in " + worldName + ".");
	}

	/**
	 * Entity entering the world (any vanilla add path). Server thread.
	 *
	 * @return true if the entity was newly registered with an owning region
	 * (false when trackable-but-unowned: the mixin still watches it, and
	 * the first boundary crossing into an owned chunk registers it)
	 */
	public boolean onEntityAdded(Entity entity) {
		if (isTracked(entity)) {
			Region owner = regionizer.ownerOfChunk(chunkX(entity), chunkZ(entity));
			return owner != null && registry.register(entity, owner);
		}
		return false;
	}

	/** Entity leaving the world (any removal reason). Server thread. */
	public void onEntityRemoved(Entity entity) {
		if (isTracked(entity)) {
			registry.unregister(entity);
		}
	}

	/**
	 * Entity crossed a chunk boundary. Server thread. Resolves the new
	 * owning region and runs the registry's atomic migrate when it changed —
	 * the §15 protocol: remove-from-old, add-to-new, repoint, one critical
	 * section.
	 */
	public void onEntityMoved(Entity entity) {
		if (!isTracked(entity)) {
			return;
		}
		Region current = registry.ownerOfHandle(entity);
		Region target = regionizer.ownerOfChunk(chunkX(entity), chunkZ(entity));
		if (target != null && target != current) {
			// current == null (unowned chunk until now) degrades to register;
			// migrate handles the ordinary cross-region move.
			if (current == null) {
				registry.register(entity, target);
			} else {
				registry.migrate(entity, target);
			}
		}
	}

	/**
	 * The entity-scheduler resolver: handle → current owning region, so
	 * entity tasks follow migrations (mandate §14/§15).
	 */
	public EntitySchedulerImpl.EntityResolver resolver() {
		return registry.resolver();
	}

	/** The registry (diagnostics: /folia regions, metrics). */
	public RegionEntityRegistry registry() {
		return registry;
	}

	/** @return the tracked dimension's stable name (error reporting). */
	public String worldName() {
		return worldName;
	}

	/**
	 * One diagnostics line for {@code /folia entities}: tracked, migration,
	 * and retirement counters. Counter reads are unsynchronized longs
	 * (mandate §35 metrics, not safety state); snapshot-racy display is
	 * accepted and stated. This aggregates ONLY this tracker's registry —
	 * the engine's view is the per-world list of these lines.
	 */
	public String diagnosticsLine() {
		return registry.trackedCount() + " tracked, "
				+ registry.migrationsCompleted() + " migration(s), "
				+ registry.retiredCount() + " retired";
	}

	/** Tears down tracking (world detach): drops all registrations. */
	public void close() {
		registry.close();
	}

	/**
	 * Client-side and already-removed entities are not tracked: region
	 * ownership only exists for live entities in server dimensions.
	 */
	private static boolean isTracked(Entity entity) {
		return entity != null && !entity.isRemoved() && !entity.level().isClientSide();
	}

	private static int chunkX(Entity entity) {
		return entity.chunkPosition().x();
	}

	private static int chunkZ(Entity entity) {
		return entity.chunkPosition().z();
	}

	/**
	 * The home-chunk resolver the registry needs for split retargeting:
	 * handle → packed chunk position in {@link RegionScheduler#packChunkPos}
	 * layout ((chunkZ &lt;&lt; 32) | (chunkX &amp; 0xFFFFFFFF)). Untracked
	 * handles (should not happen — every registered handle is an
	 * {@code Entity}) resolve to null and the entity is retired on split
	 * rather than guessed at.
	 */
	private static Long homeChunkOf(Object handle) {
		if (!(handle instanceof Entity entity)) {
			return null;
		}
		ChunkPos pos = entity.chunkPosition();
		return RegionScheduler.packChunkPos(pos.x(), pos.z());
	}
}
