/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.api.ThreadContext.Kind;
import com.palordersoftworks.fabricfolia.engine.ChunkBroadcastDeferral;
import com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Makes {@code ServerChunkCache.blockChanged} safe when called from a region
 * worker (mandate §20 — chunk/broadcast state must not be mutated
 * concurrently from unrelated region threads; found by the live
 * transition+churn storm as a fastutil iterator NPE inside
 * {@code broadcastChangedChunks}).
 *
 * <p><strong>Root cause (verified on the 26.2 jar):</strong>
 * {@code chunkHoldersToBroadcast} is a plain {@code ReferenceOpenHashSet}
 * written by {@code blockChanged} / {@code onChunkReadyToSend} / the light
 * lambda and iterated+cleared once per tick on the server thread
 * ({@code broadcastChangedChunks}). Vanilla never needs this to be
 * thread-safe because vanilla never changes blocks off the server thread —
 * but our staged block-entity / scheduled-tick bodies legitimately do, on
 * region workers. A worker {@code add} racing the server thread's iteration
 * corrupts the set (observed: {@code "this.wrapped" is null} inside
 * {@code ReferenceOpenHashSet$SetIterator.next}).</p>
 *
 * <p><strong>Fix (single ownership, no new locks):</strong> when the caller
 * is a region worker, the holder goes into a per-level
 * {@code ConcurrentHashMap.newKeySet} instead; the server thread drains that
 * pending set at {@code broadcastChangedChunks} HEAD — before vanilla's
 * iterator exists — into the real set. Broadcasts happen exactly once per
 * changed holder, one phase later than vanilla in the worker case; the
 * server thread's own path is untouched (fast path unchanged for vanilla
 * callers). The pending set is drained only on the server thread, so no
 * other synchronization is needed.</p>
 *
 * <p><strong>Shutdown edge:</strong> a pending holder whose level dies
 * between defer and drain is simply broadcast next tick or never — a
 * broadcast batch is client-visibility bookkeeping, not world state, so
 * dropping it at shutdown is safe; vanilla equally skips broadcasts for
 * holders vanishing mid-tick.</p>
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {

	/**
	 * Per-level pending set: holders whose {@code blockChanged} enqueue came
	 * from a region worker. Written by workers, drained only by the server
	 * thread — {@code newKeySet} iteration safety plus single-drainer makes
	 * this race-free without locks.
	 */
	@Unique
	private final Set<ChunkHolder> fabricfolia$pendingBroadcasts = ConcurrentHashMap.newKeySet();

	@Shadow
	protected abstract ChunkHolder getVisibleChunkIfPresent(long packedPos);

	/**
	 * Drains worker-deferred broadcast candidates into the vanilla set at
	 * broadcast time, before vanilla iterates it. Server thread only.
	 */
	@Inject(method = "broadcastChangedChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V",
			at = @At("HEAD"))
	private void folia$drainPendingBroadcasts(net.minecraft.util.profiling.ProfilerFiller profiler, CallbackInfo ci) {
		if (this.fabricfolia$pendingBroadcasts.isEmpty()) {
			return;
		}
		for (ChunkHolder holder : this.fabricfolia$pendingBroadcasts) {
			if (holder.hasChangesToBroadcast()) {
				// onChunkReadyToSend is vanilla's own "add to broadcast set if
				// it has changes" entry point; reusing it keeps the enqueue
				// predicate identical to vanilla's.
				((ServerChunkCache) (Object) this).onChunkReadyToSend(holder);
			}
		}
		ChunkBroadcastDeferral.recordDrained(this.fabricfolia$pendingBroadcasts.size());
		this.fabricfolia$pendingBroadcasts.clear();
	}

	/**
	 * Worker-context block changes defer their broadcast-set enqueue; the
	 * server thread and all other contexts run vanilla's path unchanged.
	 */
	@Inject(method = "blockChanged(Lnet/minecraft/core/BlockPos;)V",
			at = @At("HEAD"), cancellable = true)
	private void folia$onBlockChanged(net.minecraft.core.BlockPos pos, CallbackInfo ci) {
		if (com.palordersoftworks.fabricfolia.thread.ThreadOwnership.current().kind() != Kind.REGION) {
			return;
		}
		ci.cancel();
		ChunkHolder holder = this.getVisibleChunkIfPresent(net.minecraft.world.level.ChunkPos.pack(pos));
		if (holder != null && holder.blockChanged(pos)) {
			this.fabricfolia$pendingBroadcasts.add(holder);
			ChunkBroadcastDeferral.recordDeferred();
		}
	}

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("HEAD"))
	private void fabricfolia$drainDeferredVanillaState(BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			ServerThreadDeferral.drainAll();
		}
	}

	@Inject(method = "addTicketAndLoadWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
	private void fabricfolia$deferTicketLoad(TicketType type, ChunkPos pos, int radius, CallbackInfoReturnable<CompletableFuture<?>> cir) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ServerChunkCache self = (ServerChunkCache) (Object) this;
		CompletableFuture<Object> bridge = new CompletableFuture<>();
		ServerThreadDeferral.defer(() -> {
			try {
				self.addTicketAndLoadWithRadius(type, pos, radius).whenComplete((result, error) -> {
					if (error != null) {
						bridge.completeExceptionally(error);
					} else {
						bridge.complete(result);
					}
				});
			} catch (Throwable t) {
				bridge.completeExceptionally(t);
			}
		});
		cir.setReturnValue(bridge);
		cir.cancel();
	}
}
