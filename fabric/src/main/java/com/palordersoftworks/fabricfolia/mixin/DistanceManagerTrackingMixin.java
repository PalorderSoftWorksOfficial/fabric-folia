package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerTrackingMixin {

	@Inject(method = "addPlayer(Lnet/minecraft/core/SectionPos;Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
	private void fabricfolia$deferAddPlayer(SectionPos pos, ServerPlayer player, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		DistanceManager self = (DistanceManager) (Object) this;
		ServerThreadDeferral.defer(() -> self.addPlayer(pos, player));
	}

	@Inject(method = "removePlayer(Lnet/minecraft/core/SectionPos;Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
	private void fabricfolia$deferRemovePlayer(SectionPos pos, ServerPlayer player, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		DistanceManager self = (DistanceManager) (Object) this;
		ServerThreadDeferral.defer(() -> self.removePlayer(pos, player));
	}

	@Inject(method = "runAllUpdates(Lnet/minecraft/server/level/ChunkMap;)Z", at = @At("HEAD"), cancellable = true)
	private void fabricfolia$deferRunAllUpdates(ChunkMap chunkMap, CallbackInfoReturnable<Boolean> cir) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		cir.setReturnValue(false);
		cir.cancel();
		DistanceManager self = (DistanceManager) (Object) this;
		ServerThreadDeferral.defer(() -> self.runAllUpdates(chunkMap));
	}
}
