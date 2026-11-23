/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.engine.RegionTransitions;
import com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityTeleportMixin {

	@Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;",
			at = @At("HEAD"), cancellable = true)
	private void fabricfolia$regionSafeTeleport(TeleportTransition transition,
			CallbackInfoReturnable<Entity> cir) {
		Entity self = (Entity) (Object) this;
		ServerLevel newLevel = transition.newLevel();
		if (newLevel == null || newLevel.isClientSide()) {
			return;
		}

		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null) {
			return;
		}

		ThreadOwnership.Context context = ThreadOwnership.current();
		String destWorldKey = newLevel.dimension().identifier().toString();
		int destChunkX = SectionPos.blockToSectionCoord(transition.position().x());
		int destChunkZ = SectionPos.blockToSectionCoord(transition.position().z());
		boolean sameRegion = context.kind() == ThreadContext.Kind.UNKNOWN
				&& ServerThreadDeferral.isServerThread()
				&& newLevel == self.level()
				&& RegionTransitions.isSameRegion(engine, newLevel, destChunkX, destChunkZ, self);

		RegionTransitions.Decision decision = RegionTransitions.resolveEntityTransition(
				engine, destWorldKey, destChunkX, destChunkZ,
				RegionTransitions.transitionKey(self), sameRegion, () -> self.teleport(transition));
		if (decision == RegionTransitions.Decision.DISPATCHED
				|| decision == RegionTransitions.Decision.DEFERRED_TO_SERVER) {
			cir.setReturnValue(self);
		}
	}
}
