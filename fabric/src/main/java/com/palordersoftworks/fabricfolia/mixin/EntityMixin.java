/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.entity.EntityRegionTracker;
import com.palordersoftworks.fabricfolia.util.OfemCollision;
import com.palordersoftworks.fabricfolia.entity.FabricFoliaEntityHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import java.util.List;

/**
 * Entity ownership capture on the {@code Entity} class: the removal hook
 * (every {@code setRemoved} reason — death, discard, chunk unload, dimension
 * transfer) and the movement hook that re-homes an entity when it crosses a
 * region boundary.
 *
 * <p><strong>Why the cached-tracker + chunk-cache design:</strong>
 * {@code setPosRaw} is the innermost position primitive every server-side position
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
 *
 * <h2>Collision optimization (lithium-style, Carpet TIS Addition OFEM pattern)</h2>
 *
 * <p>Vanilla resolves movement one axis at a time in {@code collideWithShapes}
 * via {@code Shapes.collide(axis, box, colliders, maxDist)}, but collects block
 * colliders for the full 3D-expanded box up front. A shape can only limit
 * movement along an axis if it intersects the box swept along that axis, so
 * for small movements this patch defers block collection and queries a thin
 * 1-axis slab per axis pass instead ({@link OfemCollision}). The
 * {@code Shapes.collide} result is mathematically identical to vanilla; only
 * the shape-set size changes. Entity-vs-entity colliders and the world-border
 * shape always flow through unchanged.</p>
 *
 * <p><strong>Safety:</strong> behind {@code patches.entity-collision-opt}
 * (default OFF — operator opt-in, startup-only). Every hook falls through to
 * the original vanilla call when the patch is off, the movement exceeds the
 * sub-block threshold, or any context is missing; the step-up path and the
 * foreign {@code collideBoundingBox(CollisionContext, ...)} flow see a fully
 * vanilla collider list (context is cleared at the Entity-overload TAIL).
 * If Lithium is installed the patch is force-disabled at startup — Lithium's
 * own entity-movement optimization owns this path; double-optimizing the same
 * methods is an unverified combination, so we defer to it.
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

	/**
	 * Lithium-style axis-only collision (OFEM pattern, see class javadoc).
	 * Block colliders are deferred: the full-box collection is replaced by
	 * per-axis slab queries inside {@code collideWithShapes}. Gate: patch on
	 * AND movement sub-block per tick AND a live context. Everything else
	 * falls through to the original vanilla collection.
	 */
	@WrapOperation(
			method = "collectCollidersIgnoringWorldBorder(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/Level;getBlockCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/lang/Iterable;"
			)
	)
	private static Iterable<VoxelShape> folia$collisionOpt_axisOnlyBlockCollisions(
			Level world, Entity entity, AABB box,
			Operation<Iterable<VoxelShape>> original) {
		return OfemCollision.collect(world, entity, box, original);
	}

	/**
	 * When the axis-only context is live, {@code collideWithShapes} must
	 * not early-return on an empty entity/border list — the block shapes
	 * arrive later via the per-axis rewrite. With no context this returns
	 * the vanilla value untouched.
	 */
	@ModifyExpressionValue(
			method = "collideWithShapes",
			at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z", ordinal = 0)
	)
	private static boolean folia$collisionOpt_forceAxisResolution(boolean isEmpty) {
		return OfemCollision.isOptimizing() ? false : isEmpty;
	}

	/**
	 * Per-axis shape rewrite: append the axis-swept slab's block shapes to
	 * the entity/border collider list so {@code Shapes.collide} sees exactly
	 * the shapes that can limit movement on this axis — the same answer
	 * vanilla computes from the full 3D box, at a fraction of the shape
	 * count for sub-block movement.
	 */
	@ModifyArgs(
			method = "collideWithShapes",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/phys/shapes/Shapes;collide(Lnet/minecraft/core/Direction$Axis;Lnet/minecraft/world/phys/AABB;Ljava/lang/Iterable;D)D"
			)
	)
	private static void folia$collisionOpt_axisSlabShapes(Args args) {
		OfemCollision.rewriteShapes(args);
	}

	/**
	 * HEAD movement capture for the OFEM gate. Returns the movement
	 * unchanged — observer only.
	 */
	@ModifyVariable(
			method = "collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
			at = @At("HEAD"),
			argsOnly = true
	)
	private static Vec3 folia$collisionOpt_captureMovement(Vec3 movement) {
		return OfemCollision.captureMovement(movement);
	}

	/**
	 * TAIL clear on the Entity overload: the step-up path and
	 * collectAllColliders run after this method returns and must see a
	 * fully vanilla collider list (pooled workers never inherit state).
	 */
	@Inject(
			method = "collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
			at = @At("RETURN")
	)
	private static void folia$collisionOpt_clearAfterResolution(
			Entity entity, Vec3 movement, AABB box, Level world,
			List<VoxelShape> entityColliders, CallbackInfoReturnable<Vec3> cir) {
		OfemCollision.clear();
	}
}
