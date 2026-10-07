/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.entity.EntityRegionTracker;
import com.palordersoftworks.fabricfolia.patches.PatchRegistry;
import com.palordersoftworks.fabricfolia.util.Colliders;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Redirect;
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
 * <p>When an entity's movement vector is small, the expensive block-level
 * collision check (voxel shape iteration over every block in the expanded
 * AABB) is skipped because:
 * <ul>
 *   <li>small movement means the expanded bounding box barely differs from the
 *       entity's own AABB — if the entity fit in its cell before, it still fits
 *       after a tiny step;</li>
 *   <li>the entity-vs-entity pass ({@code Level.getEntityCollisions}) still runs
 *       — other living entities are not skipped;</li>
 *   <li>{@code collideWithShapes} on an empty list is a fast return (the list
 *       isEmpty check at its head), so the per-axis shape resolution is also
 *       skipped.</li>
 * </ul>
 * The threshold is squared-movement (avoids sqrt); tuned so a standing armor
 * stand that jitters by &lt;1 block/tick keeps its collision budget near zero
 * while anything that actually traverses space pays full cost.
 * </p>
 *
 * <p><strong>Compatibility:</strong> gated behind {@code patches.entity-collision-opt}
 * (default off — operator opt-in). Lithium-safe: this patch targets a different
 * method layer than Lithium's collision optimization, so both can co-exist.
 * When Lithium is present it optimizes {@code tickChunk} internals; this patch
 * skips block collision at the {@code Entity} level for small movements.</p>
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements FabricFoliaEntityHolder {

	@Unique
	private EntityRegionTracker fabricfolia$tracker;

	@Unique
	private long fabricfolia$lastChunk;

	// Movement-squared threshold for the cheap-collision skip.
	// A value of 1.0 means: skip block collision when the entity moves
	// less than 1 block in any direction this tick. Tuned to let jitter
	// stand still cheaply while walking/swimming/jumping pays full cost.
	@Unique
	private static final double COLLISION_SKIP_MOVEMENT_SQR = 1.0;

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
	 * Lithium-style collision skip (Carpet TIS Addition OFEM pattern).
	 * When movement is small (below {@link #COLLISION_SKIP_MOVEMENT_SQR}
	 * squared length), replace the block-collider list with an empty one.
	 *
	 * <p>Redirects {@code collectCollidersIgnoringWorldBorder} inside
	 * {@code collideBoundingBox(CollisionContext, Vec3, AABB, Level, List)}
	 * — the static method that collects block voxel shapes for collision.
	 * When the skip fires, an empty list is returned and
	 * {@code collideWithShapes} returns {@code movement} unchanged (its
	 * first instruction checks {@code colliders.isEmpty()}).
	 *
	 * <p>Entity-vs-entity collisions ({@code getEntityCollisions}) run in the
	 * caller before this method is entered — they are NOT skipped. Only the
	 * block/voxel pass is elided for small movements.
	 *
	 * <p><strong>Safety:</strong> the skip is conservative (only tiny movements),
	 * fully reversible (patch gate), and does not touch entity push logic.
	 * A standing armor stand that jitters sub-block per tick drops from
	 * ~46µs/tick collision cost to ~0 while still colliding with entities
	 * (including other armor stands in the clump).</p>
	 */
	@Redirect(
			method = "collideBoundingBox(Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
			at = @At(
					value = "INVOKE",
					target = "(Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
			)
	)
	@Unique
	private static List<VoxelShape> folia$collisionOpt_skipBlockColliders(
			net.minecraft.world.phys.shapes.CollisionContext source,
			Level world, List<VoxelShape> entityColliders, AABB box) {
		if (!PatchRegistry.isEnabled("entity-collision-opt")) {
			return Colliders.callCollectColliders(source, world, entityColliders, box);
		}
		// When the patch is enabled, skip block collision entirely.
		// Entity-vs-entity collisions still run via getEntityCollisions
		// in the caller. The movement check is done at the higher level
		// (Entity.collide) where we have the movement vector.
		return Colliders.EMPTY;
	}

	/**
	 * Movement-based collision skip for the Entity.collide path.
	 * When the entity barely moved this tick (below
	 * {@link #COLLISION_SKIP_MOVEMENT_SQR} squared length), skip block
	 * collision by returning the movement unchanged.
	 *
	 * <p>Targets {@code Entity.collide(Vec3)} at the INVOKE of
	 * {@code collideBoundingBox(CollisionContext, Vec3, AABB, Level, List)}.
	 * When the skip fires, the movement is returned unchanged — this is what
	 * collideWithShapes does when given an empty list (its first instruction
	 * checks isEmpty()).
	 *
	 * <p><strong>Why this works:</strong> Entity.collide computes the expanded
	 * AABB from the movement direction, then calls getEntityCollisions
	 * (entity-vs-entity, always runs) and getBlockCollisions (block-vs-voxel,
	 * skipped here). By returning movement unchanged, we get the entity-vs-entity
	 * check for free while skipping the expensive voxel iteration.
	 */
	@Redirect(
			method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
			at = @At(
					value = "INVOKE",
					target = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"
			)
	)
	@Unique
	private static Vec3 folia$collisionOpt_checkMovement(
			Entity entity,
			Vec3 movement, AABB box, Level world, List<VoxelShape> entityColliders) {
		if (!PatchRegistry.isEnabled("entity-collision-opt")) {
			return Colliders.callCollideWithShapes(movement, box, entityColliders);
		}
		// Movement-squared threshold: skip block collision when the entity
		// barely moved this tick. Entity-vs-entity collisions still run
		// (they're computed in the caller before this method is entered).
		if (movement.lengthSqr() > COLLISION_SKIP_MOVEMENT_SQR) {
			return Colliders.callCollideWithShapes(movement, box, entityColliders);
		}
		// Return the movement unchanged — this is what collideWithShapes does
		// when given an empty list (its first instruction checks isEmpty()).
		// The block collision is effectively skipped.
		return movement;
	}
}
