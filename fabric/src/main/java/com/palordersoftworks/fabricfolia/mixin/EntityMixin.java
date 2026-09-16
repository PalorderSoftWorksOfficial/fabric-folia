/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.entity.EntityRegionTracker;
import com.palordersoftworks.fabricfolia.entity.FabricFoliaEntityHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity ownership capture on the {@code Entity} class: the removal hook
 * (every {@code setRemoved} reason — death, discard, chunk unload, dimension
 * transfer) and the movement hook that re-homes an entity when it crosses a
 * region boundary.
 *
 * <p><strong>Why the cached-tracker + chunk-cache design:</strong>
 * {@code setPosRaw} is the innermost primitive every server-side position
 * change funnels into (movement, teleports via {@code teleportSetPosition},
 * dismounts, vehicle carrying) —
 * including calls made while an entity is being loaded but not yet added.
 * Rather than re-resolve the dimension on every call, the add hook caches
 * this dimension's tracker plus the entity's initial packed chunk; the move
 * hook is then one null-field check in the not-tracked case (load path,
 * client entities, failed adds) and one long compare in the same-chunk case
 * (region ownership cannot change within a chunk). The cache is cleared on
 * {@code setRemoved}, so dimension transfers re-cache through the new
 * dimension's add path.</p>
 *
 * <p><strong>Non-interference:</strong> observer only — vanilla's position
 * math and removal run unchanged; both hooks are no-ops without a cached
 * tracker; failures are contained, un-cache, and log, never propagated into
 * vanilla's movement or removal paths.</p>
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements FabricFoliaEntityHolder {

	@Unique
	private EntityRegionTracker fabricfolia$tracker;

	@Unique
	private long fabricfolia$lastChunk;

	@Override
	public void fabricfolia$setTracker(EntityRegionTracker tracker) {
		this.fabricfolia$tracker = tracker;
	}

	@Override
	public EntityRegionTracker fabricfolia$tracker() {
		return this.fabricfolia$tracker;
	}

	@Override
	public void fabricfolia$setLastChunk(long packedChunk) {
		this.fabricfolia$lastChunk = packedChunk;
	}

	@Override
	public long fabricfolia$lastChunk() {
		return this.fabricfolia$lastChunk;
	}

	/**
	 * setPosRaw is the innermost position primitive (verified on the 26.2 jar:
	 * setPos(DDD) ends in it, and Entity.teleportSetPosition — the teleport
	 * path — calls it directly). The load path also lands here, but those
	 * entities have no cached tracker yet, so the hook stays inert for them.
	 */
	@Inject(method = "setPosRaw(DDD)V", at = @At("TAIL"))
	private void folia$onSetPos(double x, double y, double z, CallbackInfo ci) {
		EntityRegionTracker tracker = this.fabricfolia$tracker;
		if (tracker == null) {
			return;
		}
		Entity self = (Entity) (Object) this;
		long packed = packChunkOf(self);
		if (packed == this.fabricfolia$lastChunk) {
			return; // same chunk: ownership cannot have changed
		}
		this.fabricfolia$lastChunk = packed;
		try {
			tracker.onEntityMoved(self);
		} catch (RuntimeException e) {
			// Bookkeeping failed for this entity: un-cache so the hook goes
			// inert instead of throwing on every future move.
			this.fabricfolia$setTracker(null);
			FabricFoliaMod.reportTrackerFailure("entity move", tracker.worldName(), e);
		}
	}

	@Inject(method = "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
			at = @At("TAIL"))
	private void folia$onSetRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
		EntityRegionTracker tracker = this.fabricfolia$tracker;
		if (tracker == null) {
			return;
		}
		// Clear FIRST: the entity is leaving the level by vanilla's own
		// definition (the funnel every removal reason passes through), and
		// the tracker must be inert for any position churn during removal.
		// Dimension transfers re-add through the new dimension's add path,
		// which re-caches.
		this.fabricfolia$setTracker(null);
		try {
			tracker.onEntityRemoved((Entity) (Object) this);
		} catch (RuntimeException e) {
			FabricFoliaMod.reportTrackerFailure("entity remove", tracker.worldName(), e);
		}
	}

	/**
	 * Vanilla's chunk packing layout (z in the high bits, x in the low —
	 * verified against the 26.2 jar's ChunkPos): computed here rather than
	 * stored per entity so the cache needs only the one long field.
	 */
	@Unique
	private static long packChunkOf(Entity entity) {
		ChunkPos pos = entity.chunkPosition();
		return ((long) pos.z() << 32) | (pos.x() & 0xFFFFFFFFL);
	}
}
