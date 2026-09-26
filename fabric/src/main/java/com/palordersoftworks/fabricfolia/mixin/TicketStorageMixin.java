package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(TicketStorage.class)
public abstract class TicketStorageMixin {

	@Inject(
		method = "addTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferAddTicketWithRadius(TicketType type, ChunkPos pos, int radius, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.addTicketWithRadius(type, pos, radius));
	}

	@Inject(
		method = "addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferAddTicket(Ticket ticket, ChunkPos pos, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.addTicket(ticket, pos));
	}

	@Inject(
		method = "addTicket(JLnet/minecraft/server/level/Ticket;)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferAddTicketAt(long packedPos, Ticket ticket, CallbackInfoReturnable<Boolean> cir) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		cir.setReturnValue(true);
		cir.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.addTicket(packedPos, ticket));
	}

	@Inject(
		method = "removeTicketWithRadius(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferRemoveTicketWithRadius(TicketType type, ChunkPos pos, int radius, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.removeTicketWithRadius(type, pos, radius));
	}

	@Inject(
		method = "removeTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferRemoveTicket(Ticket ticket, ChunkPos pos, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.removeTicket(ticket, pos));
	}

	@Inject(
		method = "removeTicket(JLnet/minecraft/server/level/Ticket;)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferRemoveTicketAt(long packedPos, Ticket ticket, CallbackInfoReturnable<Boolean> cir) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		cir.setReturnValue(true);
		cir.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.removeTicket(packedPos, ticket));
	}

	@Inject(
		method = "removeTicketIf(Lnet/minecraft/world/level/TicketStorage$TicketPredicate;Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferRemoveTicketIf(TicketStorage.TicketPredicate predicate, Long2ObjectOpenHashMap<List<Ticket>> tickets, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.removeTicketIf(predicate, tickets));
	}

	@Inject(
		method = "replaceTicketLevelOfType(ILnet/minecraft/server/level/TicketType;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferReplaceTicketLevelOfType(int level, TicketType type, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.replaceTicketLevelOfType(level, type));
	}

	@Inject(
		method = "updateChunkForced(Lnet/minecraft/world/level/ChunkPos;Z)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferUpdateChunkForced(ChunkPos pos, boolean forced, CallbackInfoReturnable<Boolean> cir) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		cir.setReturnValue(true);
		cir.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.updateChunkForced(pos, forced));
	}

	@Inject(
		method = "purgeStaleTickets(Lnet/minecraft/server/level/ChunkMap;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void fabricfolia$deferPurgeStaleTickets(ChunkMap chunkMap, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		TicketStorage self = (TicketStorage) (Object) this;
		ServerThreadDeferral.defer(() -> self.purgeStaleTickets(chunkMap));
	}
}
