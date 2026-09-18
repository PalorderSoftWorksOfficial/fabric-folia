/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.metrics.RegionMetrics;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region-safe transitions (mandates §16–§19): teleports, portals, dimension
 * transfers, respawns and login placement each move an entity between two
 * region-execution contexts, so they are classified and serialized here
 * rather than left to race across region workers.
 *
 * <p><strong>Design — vanilla owns the mechanics, Fabric Folia owns the
 * ordering.</strong> Vanilla's teleport paths are already careful about
 * chunk load and entity list mutation; what they assume is a single tick
 * thread, so concurrent region workers racing the same entity's position is
 * the hazard. The rule: an entity's transition body executes exactly once,
 * on ONE context, chosen by where the entity is going:
 * <ul>
 *   <li><strong>Destination region</strong> (region covers the arrival
 *       chunk): the body runs on that region's worker — the destination is
 *       the context that must see consistent world state at arrival.</li>
 *   <li><strong>No owner</strong> (world-border edge, unregionized chunks):
 *       the body hops to the global scheduler's server-thread context —
 *       never raw mutation from a random worker thread.</li>
 * </ul>
 * Dispatch is fire-and-forget (no synchronous wait on a foreign region —
 * deadlock-avoidance, mandate §32). Vanilla itself defers cross-dimension
 * moves inside its own tick loop, so the implied lag matches vanilla.</p>
 *
 * <p><strong>Idempotence:</strong> a transition is keyed by entity id and
 * collapses duplicate triggers within one tick window (portal-cooldown
 * races, tracker move-hook + transition mixin double-dispatch).</p>
 *
 * <p><strong>Threading:</strong> entry points are any thread (network,
 * region worker, server thread); this class holds no per-entity state
 * beyond the lock-free CAS guard and delegates to the per-world schedulers,
 * which serialize execution per region.</p>
 */
public final class RegionTransitions {

	private static final AtomicLong TRANSITIONS = new AtomicLong();
	private static final AtomicLong SAME_REGION = new AtomicLong();
	private static final AtomicLong DROPPED = new AtomicLong();

	/** In-flight transition guard: entity id -> stamp of the enqueued one. */
	private static final ConcurrentHashMap<Integer, Long> IN_FLIGHT = new ConcurrentHashMap<>();

	/** Transitions enqueued within the same engine tick collapse to one. */
	private static final long TICK_WINDOW_NANOS = 50_000_000L; // one 50ms tick

	private RegionTransitions() {
	}

	/**
	 * Executes an entity transition on the region owning the destination,
	 * falling back to the global (server-thread) context for positions no
	 * region owns. Safe from any thread.
	 *
	 * @param engine      the live engine (no-op false when null)
	 * @param destination the world the entity is moving to
	 * @param chunkX      destination chunk x (region resolution key)
	 * @param chunkZ      destination chunk z
	 * @param entity      the entity in transit (identity guard + diagnostics)
	 * @param vanillaBody the vanilla mutation to run on the destination
	 *                    context (the teleport/dimension/respawn call)
	 * @return true when the transition was dispatched, false when dropped
	 */
	public static boolean dispatch(FabricFoliaEngine engine,
			ServerLevel destination, int chunkX, int chunkZ,
			Entity entity, Runnable vanillaBody) {
		if (engine == null || destination == null || entity == null || vanillaBody == null) {
			return false;
		}

		// Idempotence window: collapse duplicate triggers for the same
		// entity within one tick.
		long now = System.nanoTime();
		Long prior = IN_FLIGHT.put(entity.getId(), now);
		if (prior != null && now - prior < TICK_WINDOW_NANOS) {
			return true; // duplicate of a just-dispatched transition
		}
		IN_FLIGHT.values().removeIf(stamp -> now - stamp >= TICK_WINDOW_NANOS);

		String worldKey = worldKey(destination);
		RegionScheduler scheduler = engine.schedulerFor(worldKey);
		if (scheduler == null) {
			DROPPED.incrementAndGet();
			return false;
		}

		TRANSITIONS.incrementAndGet();

		// Unowned destination: no region has a claim there; raw mutation
		// from a worker would be exactly the race this class prevents, so
		// hop to the global scheduler (server thread, serialized).
		Region owner = engine.regionizerFor(worldKey).ownerOfChunk(chunkX, chunkZ);
		if (owner == null) {
			engine.globalScheduler().run(guarded(engine, destination, entity, vanillaBody));
			return true;
		}

		boolean enqueued = scheduler.enqueue(owner,
				guarded(engine, destination, entity, vanillaBody));
		if (!enqueued) {
			// Region died between resolution and enqueue (unload race):
			// documented drop — the caller's vanilla path re-derives the
			// destination next tick (portal processors re-enter).
			DROPPED.incrementAndGet();
			return false;
		}
		return true;
	}

	/** Wraps a transition body with the engine's exception isolation. */
	private static Runnable guarded(FabricFoliaEngine engine,
			ServerLevel destination, Entity entity, Runnable body) {
		return () -> {
			try {
				body.run();
			} catch (Throwable t) {
				// Same isolation convention as staged gameplay bodies: log
				// world/entity context, never kill the executing worker.
				engine.reportError("Entity transition failed in world "
						+ worldKey(destination) + " (entity " + entity.getId() + "): " + t);
			}
		};
	}

	/**
	 * Classifies a transition for diagnostics/metrics: true when the entity's
	 * current owning region IS the destination region (no context change).
	 */
	public static boolean isSameRegion(FabricFoliaEngine engine,
			ServerLevel world, int chunkX, int chunkZ, Entity entity) {
		var tracker = engine.entityTrackerOrNull(worldKey(world));
		if (tracker == null) {
			return false;
		}
		Region current = tracker.registry().ownerOfHandle(entity);
		Region destination = engine.regionizerFor(worldKey(world)).ownerOfChunk(chunkX, chunkZ);
		if (current == null || destination == null) {
			return false;
		}
		if (current == destination) {
			SAME_REGION.incrementAndGet();
			return true;
		}
		return false;
	}

	/** Metrics lines for /folia metrics. */
	public static List<String> metricsLines() {
		return List.of(
				"transitions dispatched: " + TRANSITIONS.get()
						+ " (same-region: " + SAME_REGION.get()
						+ ", dropped: " + DROPPED.get() + ")");
	}

	private static String worldKey(ServerLevel level) {
		return level.dimension().identifier().toString();
	}
}
