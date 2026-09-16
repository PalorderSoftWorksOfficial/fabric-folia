/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.entity.EntityRegionTracker;
import com.palordersoftworks.fabricfolia.entity.FabricFoliaEntityHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity-ownership capture at THE single add funnel (mandate §15 step 1):
 * {@code PersistentEntitySectionManager.addEntity(EntityAccess, boolean)} —
 * every path an entity enters a level through reaches it. Verified on the
 * 26.2 jar:
 *
 * <ul>
 *   <li>{@code ServerLevel.addEntity} → {@code PESM.addNewEntity} →
 *       {@code PESM.addEntity(T,boolean)} — spawn eggs, commands, dispensers,
 *       item tosses, passenger dismounts;</li>
 *   <li>{@code PESM.addEntityUuid} → {@code PESM.addEntity(T,boolean)} —
 *       chunk-load and dimension-transfer re-adds;</li>
 *   <li>{@code addLegacyChunkEntities} / {@code addWorldGenChunkEntities} →
 *       the same funnel per entity — worldgen and legacy chunk entities.</li>
 * </ul>
 *
 * <p>One hook therefore captures every entity entry, and chunk-loaded
 * entities are tracked from the moment they enter (the gap the earlier
 * ServerLevel-only hook had, and why the startup backfill existed). The
 * backfill remains for entities loaded BEFORE the engine attaches — the
 * mixin only observes after attach.</p>
 *
 * <p><strong>Tracker caching:</strong> on a successful add the hook caches
 * this dimension's tracker plus the entity's initial packed chunk on the
 * entity (duck-typed fields — see {@link FabricFoliaEntityHolder}), so the
 * per-move hook needs no dimension resolution and no regionizer contact for
 * same-chunk movement. The cache is set even when no region owns the chunk
 * yet: the entity is WATCHED, and its first boundary crossing into an owned
 * chunk registers it. A failed add leaves the entity untracked, mirroring
 * vanilla's own accounting. The cache is cleared by the removal hook.</p>
 *
 * <p><strong>Resolving the level:</strong> the manager does not expose its
 * level; the entity's own level is the level it was added to (single check,
 * valid for all four paths above).</p>
 *
 * <p><strong>Non-interference:</strong> an observer only — vanilla's return
 * value is never modified, and the hook is a no-op when the engine is
 * disabled or the world is not attached. Failure containment: a tracker
 * throwing is caught, un-cached, and logged rather than failing the vanilla
 * add.</p>
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin {

	/**
	 * @param entityAccess the entity vanilla is adding (nullable-safe: the
	 *                     tracker filters nulls, mirroring vanilla's own null
	 *                     handling); parameter is the erased {@code EntityAccess}
	 *                     with an internal cast — no reliance on injector coercion
	 */
	@Inject(method = "addEntity(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z",
			at = @At("RETURN"))
	private void folia$onAddEntity(EntityAccess entityAccess, boolean existing, CallbackInfoReturnable<Boolean> cir) {
		FabricFoliaEngine engine = FabricFoliaMod.engine();
		if (engine == null || entityAccess == null || !cir.getReturnValue()
				|| !(entityAccess instanceof Entity entity)) {
			return;
		}
		if (entity.level().isClientSide()
				|| !(entity.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		String worldName = serverLevel.dimension().identifier().toString();
		EntityRegionTracker tracker = engine.entityTrackerOrNull(worldName);
		if (tracker == null) {
			return;
		}
		try {
			FabricFoliaEntityHolder holder = (FabricFoliaEntityHolder) entity;
			holder.fabricfolia$setTracker(tracker);
			ChunkPos pos = entity.chunkPosition();
			holder.fabricfolia$setLastChunk(((long) pos.z() << 32) | (pos.x() & 0xFFFFFFFFL));
			tracker.onEntityAdded(entity);
		} catch (RuntimeException e) {
			// Ownership bookkeeping must never break vanilla gameplay:
			// un-cache so the hooks go inert for this entity, surface it.
			((FabricFoliaEntityHolder) entity).fabricfolia$setTracker(null);
			FabricFoliaMod.reportTrackerFailure("entity add", worldName, e);
		}
	}
}
