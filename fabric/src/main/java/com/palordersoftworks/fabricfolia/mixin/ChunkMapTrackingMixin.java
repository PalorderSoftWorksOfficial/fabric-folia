package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapTrackingMixin {

	@Shadow
	public abstract void move(ServerPlayer player);

	@Shadow
	protected abstract void addEntity(Entity entity);

	@Shadow
	protected abstract void removeEntity(Entity entity);

	@Inject(method = "move(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true)
	private void fabricfolia$deferMove(ServerPlayer player, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		ServerThreadDeferral.defer(() -> this.move(player));
	}

	@Inject(method = "addEntity(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void fabricfolia$deferAddEntity(Entity entity, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		ServerThreadDeferral.defer(() -> this.addEntity(entity));
	}

	@Inject(method = "removeEntity(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void fabricfolia$deferRemoveEntity(Entity entity, CallbackInfo ci) {
		if (ServerThreadDeferral.isServerThread()) {
			return;
		}
		ci.cancel();
		ServerThreadDeferral.defer(() -> this.removeEntity(entity));
	}
}
