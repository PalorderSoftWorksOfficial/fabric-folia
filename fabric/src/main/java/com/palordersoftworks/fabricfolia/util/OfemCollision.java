/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.util;

import com.palordersoftworks.fabricfolia.patches.PatchRegistry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lithium-style axis-only collision context (Carpet TIS Addition OFEM
 * pattern), adapted for MC 26.2.
 *
 * <p><strong>How it works:</strong> vanilla's {@code collideWithShapes}
 * resolves movement one axis at a time via {@code Shapes.collide(axis, box,
 * colliders, maxDist)}. A block shape can only limit movement along an axis
 * if it intersects the box swept along that axis by {@code maxDist}. So for
 * each axis pass we replace the full 3D-expanded collider list with a thin
 * 1-axis slab query ({@code box.expandTowards(axis * maxDist)}) — the result
 * of {@code Shapes.collide} is mathematically identical to vanilla, but the
 * shape set is much smaller for sub-block movement (the common case: idle,
 * walking, falling entities).</p>
 *
 * <p><strong>Thread-affinity:</strong> every region worker resolves one
 * entity's movement synchronously (capture → collect → resolve) on its own
 * thread, so a plain ThreadLocal context is safe. The context is set by the
 * {@code getBlockCollisions} wrap, consumed by the {@code Shapes.collide}
 * arg rewrite, and cleared at {@code Entity.collide} TAIL plus on every
 * non-optimizing collect — pooled workers never inherit stale state.</p>
 *
 * <p><strong>Safety:</strong> when the patch is disabled, the movement is
 * above threshold, or any state is missing, every hook falls through to the
 * original vanilla call — behavior is byte-for-byte vanilla. The foreign
 * {@code collideBoundingBox(CollisionContext, ...)} flow (piston/block-push)
 * clears the context at HEAD so a stale context can never rewrite its
 * shape list.</p>
 */
public final class OfemCollision {

    /** Squared-movement gate: only sub-block-per-tick movement uses the
     *  axis-only slab; larger movement (projectiles, elytra) pays vanilla's
     *  single full-box collection, which is cheaper there. */
    public static final double MOVEMENT_SQR_THRESHOLD = 1.0;

    private static final ThreadLocal<Vec3> MOVEMENT = new ThreadLocal<>();
    private static final ThreadLocal<Ctx> CONTEXT = new ThreadLocal<>();

    /** Immutable per-collision context: the world/entity the axis-only
     *  block queries run against. Axis/box/maxDist come from the live
     *  Shapes.collide args, not stored here. */
    public record Ctx(Level level, Entity entity) {}

    private OfemCollision() {}

    /** HEAD capture on collideBoundingBox(Entity, ...): records this tick's
     *  movement for the wrap gate; clears when the patch is off. */
    public static Vec3 captureMovement(Vec3 movement) {
        if (PatchRegistry.isEnabled("entity-collision-opt")) {
            MOVEMENT.set(movement);
        } else {
            MOVEMENT.remove();
        }
        return movement;
    }

    /**
     * WrapOperation body for {@code Level.getBlockCollisions} inside
     * {@code collectCollidersIgnoringWorldBorder(Entity, ...)}.
     * Returns an empty shape list (block colliders deferred to the
     * per-axis slab queries) when the patch is on and movement is small;
     * otherwise runs the original collection. Never throws — a failed
     * optimization must not break movement.
     */
    public static Iterable<VoxelShape> collect(Level world, Entity entity, AABB box,
                                               Operation<Iterable<VoxelShape>> original) {
        if (PatchRegistry.isEnabled("entity-collision-opt")) {
            Vec3 movement = MOVEMENT.get();
            if (movement != null && checkMovement(movement)) {
                CONTEXT.set(new Ctx(world, entity));
                return Collections.emptyList();
            }
        }
        CONTEXT.remove();
        return original.call(world, entity, box);
    }

    /** True while an axis-only resolution is in flight on this thread. */
    public static boolean isOptimizing() {
        return CONTEXT.get() != null;
    }

    /**
     * ModifyArgs body for the {@code Shapes.collide(axis, box, colliders,
     * maxDist)} call in {@code collideWithShapes}: appends the block shapes
     * from the axis-swept slab to the entity/border collider list. The slab
     * ({@code box.expandTowards(maxDist along axis)}) contains exactly the
     * shapes that can limit movement within maxDist on this axis, so
     * {@code Shapes.collide} sees an equivalent problem at a fraction of
     * the shape count.
     */
    public static void rewriteShapes(Args args) {
        // Mixin Args carrier: axis(0), box(1), shapes(2), maxDist(3).
        Ctx ctx = CONTEXT.get();
        if (ctx == null) {
            return;
        }
        try {
            Direction.Axis axis = args.get(0);
            AABB box = args.get(1);
            List<VoxelShape> entityAndBorder = args.get(2);
            double maxDist = args.get(3);
            AABB slab = switch (axis) {
                case X -> box.expandTowards(maxDist, 0.0, 0.0);
                case Y -> box.expandTowards(0.0, maxDist, 0.0);
                case Z -> box.expandTowards(0.0, 0.0, maxDist);
            };
            List<VoxelShape> combined = new ArrayList<>(entityAndBorder.size() + 4);
            combined.addAll(entityAndBorder);
            ctx.level().getBlockCollisions(ctx.entity(), slab).forEach(combined::add);
            args.set(2, combined);
        } catch (RuntimeException e) {
            // Optimization failure must never corrupt movement: drop the
            // context so this resolution falls back to the entity/border
            // list vanilla already provided, and surface the failure.
            CONTEXT.remove();
            e.printStackTrace();
        }
    }

    /** Movement gate: finite and sub-block per tick (squared). */
    public static boolean checkMovement(Vec3 movement) {
        double sqr = movement.lengthSqr();
        return Double.isFinite(sqr) && sqr <= MOVEMENT_SQR_THRESHOLD;
    }

    /** TAIL clear on Entity.collide / foreign-flow HEAD clear: pooled
     *  region workers must never carry this thread's context into an
     *  unrelated call. */
    public static void clear() {
        CONTEXT.remove();
        MOVEMENT.remove();
    }
}
