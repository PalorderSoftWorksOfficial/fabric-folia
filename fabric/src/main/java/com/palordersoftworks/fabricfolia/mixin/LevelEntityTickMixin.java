/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.engine.RegionStageHub;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The entity-tick staging capture (mandates 15/27): when regionized gameplay
 * is active for this level, the SERVER thread's body dispatch inside
 * {@code guardEntityTick} is redirected to stage the body onto the owning
 * region's worker instead.
 *
 * <p><strong>Why this seam (bytecode-verified on 26.2):</strong>
 * {@code Level.guardEntityTick(Consumer, Entity)} is the single funnel
 * through which the per-entity tick body flows: ServerLevel's entity-loop
 * lambda runs vanilla's gates ({@code checkDespawn}, tick-rate gating) on
 * the server thread and then calls {@code guardEntityTick}, whose body is
 * the crash-guarded {@code consumer.accept(entity)}. Staging at that accept
 * call moves the COMPLETE body — {@code tickNonPassenger} with its
 * {@code tickCount++}, {@code tick()}, and passenger recursion — while
 * vanilla keeps every decision (which entities, which gates) on the server
 * thread. Nothing about the tick semantics is replicated or forked.</p>
 *
 * <p><strong>Inline-path fidelity:</strong> for unloaded-neighborhood
 * entities, region-worker execution contexts, and inactive staging the
 * handler calls {@code consumer.accept} directly from the redirect site —
 * inside {@code guardEntityTick}'s try range, so a throwable is wrapped by
 * VANILLA's own exception table and handler code, byte-for-byte vanilla
 * crash semantics. The staged body, by contrast, runs on a region worker
 * through the hub's {@code runSafely} guard (mandate 34: a region-local
 * exception is isolated and reported, not server-fatal).</p>
 *
 * <p><strong>Re-entry discipline:</strong> the staged body calls the
 * consumer directly (never {@code guardEntityTick}), so the redirect cannot
 * re-enter from a worker. The REGION-context check is defense-in-depth.</p>
 *
 * <p><strong>Players:</strong> {@code ServerPlayer} bodies are staged like
 * every other entity (the region owning the player's chunk executes the
 * body); the two server-thread couplings inside the player body are
 * bounced by {@link ServerPlayerTickMixin}. The connection tick's
 * per-connection body — which drives {@code SGPLI.tick} →
 * {@code ServerPlayer.doTick()} and the physics super-chain — is staged
 * separately by {@code ServerConnectionTickMixin} into the hub's PLAYER
 * slice, after this entity slice, mirroring vanilla's intra-tick pass
 * order.</p>
 */
@Mixin(Level.class)
public abstract class LevelEntityTickMixin {

	@Redirect(
			method = "guardEntityTick",
			at = @At(value = "INVOKE",
					target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"),				require = 1)
	private void fabricfolia$stageEntityTick(java.util.function.Consumer<?> consumer, Object entity) {
		boolean staging = ThreadOwnership.current().kind() != ThreadContext.Kind.REGION
				&& RegionStageHub.isStaging((Level) (Object) this, RegionStageHub.Slice.ENTITY);
		if (staging && entity instanceof net.minecraft.server.level.ServerPlayer player) {
			if (!fabricfolia$playerPhysicsSafe(player)) {
				// A staged player body whose 3x3 chunk neighborhood is not
				// fully loaded parks a worker on a synchronous chunk load (the
				// same hazard as any edge entity). Keep the vanilla inline
				// path for edge players until chunk access is region-safe.
				runInline(consumer, entity);
				return;
			}
			// Player body: stage like any entity (the two server-thread
			// couplings inside are handled by ServerPlayerTickMixin).
			RegionStageHub.stage((Level) (Object) this, RegionStageHub.Slice.ENTITY,
					new StagedEntityBody(consumer, (Entity) entity));
			return;
		}
		if (staging && fabricfolia$neighborhoodLoaded((Entity) entity)) {
			// Stage the vanilla body; the region worker executes it in
			// REGION context via the hub's runSafely guard.
			RegionStageHub.stage((Level) (Object) this, RegionStageHub.Slice.ENTITY,
					new StagedEntityBody(consumer, (Entity) entity));
			return;
		}
		// Vanilla path: invoked at the original call site, inside
		// guardEntityTick's try range — vanilla's crash guard applies.
		runInline(consumer, entity);
	}

	private static void runInline(java.util.function.Consumer<?> consumer, Object entity) {
		@SuppressWarnings("unchecked")
		java.util.function.Consumer<Object> raw = (java.util.function.Consumer<Object>) consumer;
		raw.accept(entity);
	}

	/**
	 * A staged player body must not park its worker on a chunk load. Same
	 * probe as the generic entity check, evaluated at staging time (server
	 * thread) for players because the worker-side bounce for players would
	 * reorder against the region's other staged player work.
	 */
	private boolean fabricfolia$playerPhysicsSafe(net.minecraft.server.level.ServerPlayer player) {
		if (!(((Object) this) instanceof net.minecraft.server.level.ServerLevel level)) {
			return true;
		}
		net.minecraft.world.level.ChunkPos center = player.chunkPosition();
		return com.palordersoftworks.fabricfolia.engine.ChunkResidency.isNeighborhoodResident(
				level.dimension().identifier().toString(), center.x(), center.z());
	}

	/**
	 * An entity whose 3x3 chunk neighborhood is not fully loaded keeps the
	 * vanilla inline path this phase. Its tick body can legitimately ask for
	 * edge-adjacent chunk data (fluid interactions, collision iteration,
	 * portal search); from a region worker such a request parks on a
	 * SYNCHRONOUS chunk load that the worker cannot pump — observed live as a
	 * region latched TICKING for the load's duration with every packet and
	 * movement task for its chunks starved behind it (a walking player at the
	 * loaded edge rubber-banded; workers' dumps showed Mob.tick waiting in
	 * ServerChunkCache.getChunk().join()). Vanilla runs these bodies on the
	 * server thread — the chunk system's owner — so edge entities stay there
	 * until chunk access is region-safe. Runs on the server thread (staging
	 * decision); getChunkNow is a non-blocking loaded-chunk probe.
	 */
	private boolean fabricfolia$neighborhoodLoaded(Entity entity) {
		if (!(((Object) this) instanceof net.minecraft.server.level.ServerLevel level)) {
			return true; // non-server levels are not regionized anyway
		}
		net.minecraft.world.level.ChunkPos center = entity.chunkPosition();
		return com.palordersoftworks.fabricfolia.engine.ChunkResidency.isNeighborhoodResident(
				level.dimension().identifier().toString(), center.x(), center.z());
	}

	/**
	 * The staged entity body: its position resolves the owning region at
	 * flush — after all of vanilla's server-thread passes, so a tick-time
	 * migration is reflected in the owner that executes the body (see
	 * RegionStageHub's correctness boundaries). The body IS vanilla's
	 * consumer; nothing is copied.
	 */
	private record StagedEntityBody(java.util.function.Consumer<?> consumer,
	                                Entity entity)
			implements RegionStageHub.Positioned, Runnable {

		@Override
		public net.minecraft.world.level.ChunkPos fabricfolia$position() {
			return entity.chunkPosition();
		}

		@Override
		public void run() {
			@SuppressWarnings("unchecked")
			java.util.function.Consumer<Object> raw = (java.util.function.Consumer<Object>) consumer;
			raw.accept(entity);
		}
	}
}
