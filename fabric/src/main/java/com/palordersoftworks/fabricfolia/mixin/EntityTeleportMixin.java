/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.api.ThreadContext;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.engine.RegionTransitions;
import com.palordersoftworks.fabricfolia.thread.ThreadOwnership;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region-safe teleportation (mandates §16/§19): vanilla's
 * {@link Entity#teleport(TeleportTransition)} is the single funnel every
 * portal, dimension change, and respawn transition flows through. It is
 * written for the single-server-thread model; when a REGION-context worker
 * invokes it, the entity move and its world mutation race the destination
 * region's worker.
 *
 * <p>The fix: on a region worker, route the vanilla body through
 * {@link RegionTransitions#dispatch} to the destination region's context
 * (or the global/server-thread context for unowned arrivals) and cancel the
 * original call. Same-region transitions are left untouched — the current
 * region already owns the destination. On the server thread (vanilla
 * execution, disabled engine, network-driven player teleports) nothing
 * changes: the method runs exactly as vanilla wrote it.</p>
 */
@Mixin(Entity.class)
public abstract class EntityTeleportMixin {

	@Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;",
			at = @At("HEAD"), cancellable = true)
	private void fabricfolia$regionSafeTeleport(TeleportTransition transition,
			CallbackInfoReturnable<Entity> cir) {
		ThreadOwnership.Context context = ThreadOwnership.current();
		if (context.kind() != ThreadContext.Kind.REGION) {
			return; // server thread / network / async: vanilla behavior
		}

		Entity self = (Entity) (Object) this;
		ServerLevel newLevel = transition.newLevel();

		// Cross-dimension is ALWAYS a context change; same-world checks the
		// destination region against the entity's current owner.
		int destChunkX = SectionPos.blockToSectionCoord(transition.position().x());
		int destChunkZ = SectionPos.blockToSectionCoord(transition.position().z());
		boolean sameRegion = newLevel == self.level()
				&& RegionTransitions.isSameRegion(FabricFoliaMod.engine(),
						newLevel, destChunkX, destChunkZ, self);
		if (sameRegion) {
			return; // current region owns the destination: run inline
		}

		FabricFoliaEngine engine = FabricFoliaMod.engine();
		boolean dispatched = RegionTransitions.dispatch(engine, newLevel,
				destChunkX, destChunkZ,
				self, () -> self.teleport(transition));
		if (dispatched) {
			cir.setReturnValue(self); // vanilla's own no-op return convention
		}
		// Not dispatched (engine down / world unattached): fall through to
		// the vanilla body — a region worker running vanilla's own teleport
		// is the pre-existing risk path, and dropping the move entirely
		// would strand portals.
	}
}
