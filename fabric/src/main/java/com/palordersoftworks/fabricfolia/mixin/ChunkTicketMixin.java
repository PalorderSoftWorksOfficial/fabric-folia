/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.engine.TicketDeferral;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps vanilla's chunk-ticket bookkeeping single-threaded when region workers
 * legitimately place tickets ({@code Entity.placePortalTicket} via
 * {@code NetherPortalBlock} / {@code TeleportTransition.postTeleport},
 * {@code ServerPlayer.placeEnderPearlTicket} on pearl landing). Found by a
 * production crash (Palorder Central, 2026-09-25): a worker's
 * {@code addTicketWithRadius} rehashed the ticket/tracker maps mid-enumeration
 * of {@code DistanceManager.forEachEntityTickingChunk} — fastutil's
 * {@code "this.wrapped" is null} iterator NPE, world-tick crash.
 *
 * <p><strong>Root cause (verified against the 26.2 jar):</strong>
 * {@code addTicketWithRadius} → {@code TicketStorage.addTicketWithRadius} →
 * {@code DistanceManager} tracker updates (same tick via the
 * {@code simulationChunkUpdatedListener}); {@code SimulationChunkTracker.chunks}
 * is the exact {@code Long2ByteOpenHashMap} from the stack trace. Vanilla never
 * writes tickets off the server thread, so nothing there is thread-safe. On
 * the server thread the calls run unchanged (teleport funnels, portal
 * processors, the {@code /folia pin} command, and the replay path itself all
 * run there).</p>
 *
 * <p><strong>Fix (single ownership, no locks):</strong> REGION-context callers
 * defer the placement into {@link TicketDeferral}; that world's server-thread
 * tick replays it at HEAD of {@code ServerChunkCache.tick} — before
 * {@code runAllUpdates}/{@code forEachEntityTickingChunk} — so the tracker
 * maps are mutated only on the server thread. The replay is verbatim
 * ({@code addTicketWithRadius(type, pos, radius)}; the raw-level overload's
 * negative-level normalization is never exercised — worker captures always
 * carry a real ticket level), re-checks C2ME suppression at replay time (a
 * suppressed session drops pending tickets rather than applying them), and
 * the drain runs every tick, so placement lands within one 50ms tick —
 * indistinguishable in practice (teleport bodies already queue cross-context
 * work, and pearl-landing + first tick are not synchronized for the player
 * anyway).</p>
 *
 * <p><strong>Compatibility with the broadcast-set deferral:</strong> disjoint
 * state — pending broadcast holders vs pending ticket placements — with the
 * same proven shape. The removal side is untouched: timeout removals run on
 * the server thread (vanilla).</p>
 */
@Mixin(ServerChunkCache.class)
public abstract class ChunkTicketMixin {

	@Inject(method = "addTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V",
			at = @At("HEAD"), cancellable = true)
	private void fabricfolia$deferWorkerTicketRadius(TicketType type, ChunkPos pos, int radius, CallbackInfo ci) {
		if (com.palordersoftworks.fabricfolia.thread.ThreadOwnership.current().kind() != ThreadContext.Kind.REGION) {
			return; // server thread and every other context: vanilla behavior
		}
		ci.cancel();
		ServerChunkCache self = (ServerChunkCache) (Object) this;
		TicketDeferral.defer(new TicketDeferral.Placement(
				self.getLevel().dimension().identifier().toString(),
				pos.pack(), type, radius, -1));
	}

	@Inject(method = "addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V",
			at = @At("HEAD"), cancellable = true)
	private void fabricfolia$deferWorkerTicket(Ticket ticket, ChunkPos pos, CallbackInfo ci) {
		if (com.palordersoftworks.fabricfolia.thread.ThreadOwnership.current().kind() != ThreadContext.Kind.REGION) {
			return;
		}
		ci.cancel();
		ServerChunkCache self = (ServerChunkCache) (Object) this;
		TicketDeferral.defer(new TicketDeferral.Placement(
				self.getLevel().dimension().identifier().toString(),
				pos.pack(), ticket.getType(), -1, ticket.getTicketLevel()));
	}

	/**
	 * This world's tick replays its own deferred placements before this
	 * tick's enumeration ({@code runAllUpdates} →
	 * {@code forEachEntityTickingChunk}) can touch the tracker maps.
	 * Non-server-thread callers pass through untouched.
	 */
	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V",
			at = @At("HEAD"))
	private void folia$drainDeferredTickets(java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
		if (com.palordersoftworks.fabricfolia.thread.ThreadOwnership.current().kind() == ThreadContext.Kind.REGION) {
			return;
		}
		ServerChunkCache self = (ServerChunkCache) (Object) this;
		String worldKey = self.getLevel().dimension().identifier().toString();
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		boolean suppressed = engine != null && engine.randomTickInterceptSuppressed();
		TicketDeferral.drain(worldKey, placement -> {
			if (placement.radius() >= 0) {
				self.addTicketWithRadius(placement.type(),
						ChunkPos.unpack(placement.packedChunk()), placement.radius());
			} else {
				self.addTicket(new Ticket(placement.type(), placement.ticketLevel()),
						ChunkPos.unpack(placement.packedChunk()));
			}
		}, () -> {
			FabricFoliaEngine current = FabricFoliaMod.engine();
			return current != null && current.randomTickInterceptSuppressed();
		});
	}
}
