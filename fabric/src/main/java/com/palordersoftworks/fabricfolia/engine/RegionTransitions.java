/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.engine;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.region.Region;
import com.palordersoftworks.fabricfolia.region.WorldRegionizer;
import com.palordersoftworks.fabricfolia.scheduler.RegionScheduler;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public final class RegionTransitions {

	public enum Decision {
		INLINE,
		DISPATCHED,
		DEFERRED_TO_SERVER,
		DROPPED
	}

	private static final AtomicLong TRANSITIONS = new AtomicLong();
	private static final AtomicLong SAME_REGION = new AtomicLong();
	private static final AtomicLong INLINE = new AtomicLong();
	private static final AtomicLong DROPPED = new AtomicLong();
	private static final AtomicLong UNATTACHED = new AtomicLong();
	private static final AtomicLong DEFERRED_TO_SERVER = new AtomicLong();
	/** Transitions captured from the ServerPlayer.teleport override. */
	private static final AtomicLong PLAYER_TRANSITIONS = new AtomicLong();

	private static final ThreadLocal<TransitionToken> RUNNING = new ThreadLocal<>();

	private RegionTransitions() {
	}

	public static record EntityTransitionKey(String sourceWorld, int entityId) {
		public EntityTransitionKey {
			if (sourceWorld == null || sourceWorld.isBlank()) {
				throw new IllegalArgumentException("sourceWorld must not be blank");
			}
		}
	}

	public static EntityTransitionKey transitionKey(Entity entity) {
		return new EntityTransitionKey(
				entity.level().dimension().identifier().toString(), entity.getId());
	}

	private static final class TransitionToken {

		private final String worldKey;
		private final int chunkX;
		private final int chunkZ;
		private final EntityTransitionKey entity;

		TransitionToken(String worldKey, int chunkX, int chunkZ, EntityTransitionKey entity) {
			this.worldKey = worldKey;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.entity = entity;
		}

		boolean matches(String world, int chunkX, int chunkZ, EntityTransitionKey entityKey) {
			return this.chunkX == chunkX
					&& this.chunkZ == chunkZ
					&& java.util.Objects.equals(this.worldKey, world)
					&& java.util.Objects.equals(this.entity, entityKey);
		}

		@Override
		public String toString() {
			return "transition[" + worldKey + " " + chunkX + "," + chunkZ
					+ (entity == null ? "" : " entity=" + entity.entityId()) + "]";
		}
	}

	public static boolean dispatch(FabricFoliaEngine engine,
			ServerLevel destination, int chunkX, int chunkZ,
			Entity entity, Runnable vanillaBody) {
		if (engine == null || destination == null || entity == null || vanillaBody == null) {
			return false;
		}
		return isDispatchAccepted(resolveEntityTransition(engine, worldKey(destination),
				chunkX, chunkZ, transitionKey(entity), vanillaBody));
	}

	public static boolean dispatchKeyed(FabricFoliaEngine engine,
			String worldKey, int chunkX, int chunkZ,
			Entity entity, Runnable vanillaBody) {
		if (engine == null || worldKey == null || vanillaBody == null) {
			return false;
		}
		return isDispatchAccepted(resolveEntityTransition(engine, worldKey, chunkX, chunkZ,
				entity == null ? null : transitionKey(entity), vanillaBody));
	}

	public static Decision resolveEntityTransition(FabricFoliaEngine engine,
			String worldKey, int chunkX, int chunkZ,
			EntityTransitionKey entityKey, Runnable vanillaBody) {
		return resolveEntityTransition(engine, worldKey, chunkX, chunkZ, entityKey, false,
				vanillaBody);
	}

	public static Decision resolveEntityTransition(FabricFoliaEngine engine,
			String worldKey, int chunkX, int chunkZ,
			EntityTransitionKey entityKey, boolean sameRegion, Runnable vanillaBody) {
		TransitionToken running = RUNNING.get();
		if (running != null && running.matches(worldKey, chunkX, chunkZ, entityKey)) {
			INLINE.incrementAndGet();
			return Decision.INLINE;
		}
		if (engine == null || worldKey == null || vanillaBody == null) {
			return Decision.DROPPED;
		}

		ThreadOwnership.Context context = ThreadOwnership.current();
		ThreadContext.Kind kind = context.kind();
		boolean serverThread = ServerThreadDeferral.isServerThread();
		TransitionToken token = new TransitionToken(worldKey, chunkX, chunkZ, entityKey);
		Runnable body = bodyWithToken(token, vanillaBody);

		RegionScheduler scheduler = engine.schedulerFor(worldKey);
		WorldRegionizer regionizer = engine.regionizerFor(worldKey);
		if (scheduler == null || regionizer == null) {
			UNATTACHED.incrementAndGet();
			if (kind == ThreadContext.Kind.GLOBAL
					|| (kind == ThreadContext.Kind.UNKNOWN && serverThread)) {
				return Decision.DROPPED;
			}
			return deferToServer(body);
		}

		Region owner = regionizer.ownerOfChunk(chunkX, chunkZ);
		switch (kind) {
			case REGION:
				if (owner != null && owner == context.region()) {
					INLINE.incrementAndGet();
					return Decision.INLINE;
				}
				return dispatchTo(engine, scheduler, owner, chunkX, chunkZ, body);
			case GLOBAL:
				if (owner == null) {
					INLINE.incrementAndGet();
					return Decision.INLINE;
				}
				return dispatchTo(engine, scheduler, owner, chunkX, chunkZ, body);
			case NETWORK:
			case ASYNC:
			case IO:
				return dispatchTo(engine, scheduler, owner, chunkX, chunkZ, body);
			default:
				if (serverThread && sameRegion) {
					SAME_REGION.incrementAndGet();
					INLINE.incrementAndGet();
					return Decision.INLINE;
				}
				return dispatchTo(engine, scheduler, owner, chunkX, chunkZ, body);
		}
	}

	private static Decision dispatchTo(FabricFoliaEngine engine, RegionScheduler scheduler,
			Region owner, int chunkX, int chunkZ, Runnable body) {
		if (owner == null) {
			try {
				engine.globalScheduler().run(body);
			} catch (RejectedExecutionException rejected) {
				return deferToServer(body);
			}
			TRANSITIONS.incrementAndGet();
			return Decision.DISPATCHED;
		}
		if (scheduler.scheduleToChunk(chunkX, chunkZ, body)) {
			TRANSITIONS.incrementAndGet();
			return Decision.DISPATCHED;
		}
		return deferToServer(body);
	}

	private static Decision deferToServer(Runnable body) {
		ServerThreadDeferral.defer(body);
		DEFERRED_TO_SERVER.incrementAndGet();
		return Decision.DEFERRED_TO_SERVER;
	}

	private static Runnable bodyWithToken(TransitionToken token, Runnable body) {
		return () -> {
			TransitionToken previous = RUNNING.get();
			RUNNING.set(token);
			try {
				body.run();
			} finally {
				if (previous == null) {
					RUNNING.remove();
				} else {
					RUNNING.set(previous);
				}
			}
		};
	}

	private static boolean isDispatchAccepted(Decision decision) {
		return decision == Decision.DISPATCHED || decision == Decision.DEFERRED_TO_SERVER;
	}

	public static boolean isCurrentContextOwner(String worldKey,
			int chunkX, int chunkZ) {
		return isCurrentContextOwner(FabricFoliaMod.engine(), worldKey, chunkX, chunkZ);
	}

	public static boolean isCurrentContextOwner(FabricFoliaEngine engine, String worldKey,
			int chunkX, int chunkZ) {
		ThreadOwnership.Context current = ThreadOwnership.current();
		if (current.kind() != ThreadContext.Kind.REGION || current.region() == null) {
			return false;
		}
		if (engine == null || worldKey == null) {
			return false;
		}
		WorldRegionizer regionizer = engine.regionizerFor(worldKey);
		if (regionizer == null) {
			return false;
		}
		Region owner = regionizer.ownerOfChunk(chunkX, chunkZ);
		return owner != null && owner == current.region();
	}

	public static boolean isSameRegion(FabricFoliaEngine engine,
			ServerLevel world, int chunkX, int chunkZ, Entity entity) {
		if (engine == null || world == null || entity == null) {
			return false;
		}
		var tracker = engine.entityTrackerOrNull(worldKey(world));
		WorldRegionizer regionizer = engine.regionizerFor(worldKey(world));
		if (tracker == null || regionizer == null) {
			return false;
		}
		Region current = tracker.registry().ownerOfHandle(entity);
		Region destination = regionizer.ownerOfChunk(chunkX, chunkZ);
		if (current == null || destination == null) {
			return false;
		}
		if (current == destination) {
			SAME_REGION.incrementAndGet();
			return true;
		}
		return false;
	}

	/** Records a player teleport capture (the player mixin's diagnostics counter). */
	public static void recordPlayerTransition() {
		PLAYER_TRANSITIONS.incrementAndGet();
	}

	/**
	 * Immutable counter snapshot for tests and diagnostics. Plain volatile
	 * reads (mandate §35: counters are not safety state — a racy snapshot is
	 * accepted and documented, same as the command output).
	 */
	public record Snapshot(long transitions, long sameRegion, long inline,
	                       long dropped, long unattached,
	                       long deferredToServer, long playerTransitions) {
	}

	/** @return a best-effort point-in-time read of all transition counters. */
	public static Snapshot snapshot() {
		return new Snapshot(TRANSITIONS.get(), SAME_REGION.get(), INLINE.get(),
				DROPPED.get(), UNATTACHED.get(), DEFERRED_TO_SERVER.get(),
				PLAYER_TRANSITIONS.get());
	}

	/** Metrics lines for /folia metrics. */
	public static List<String> metricsLines() {
		java.util.ArrayList<String> lines = new java.util.ArrayList<>();
		lines.add("transitions dispatched: " + TRANSITIONS.get()
				+ " (same-region: " + SAME_REGION.get()
				+ ", inline: " + INLINE.get()
				+ ", dropped: " + DROPPED.get()
				+ ", unattached-world: " + UNATTACHED.get()
				+ ", deferred-to-server: " + DEFERRED_TO_SERVER.get() + ")");
		lines.add("player transitions dispatched: " + PLAYER_TRANSITIONS.get());
		lines.add("block broadcasts deferred from workers: "
				+ ChunkBroadcastDeferral.deferred()
				+ " (drained: " + ChunkBroadcastDeferral.drained() + ")");
		return lines;
	}

	private static String worldKey(ServerLevel level) {
		return level.dimension().identifier().toString();
	}
}
