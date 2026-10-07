/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.util;

import java.util.Collections;
import java.util.List;

/**
 * Collision-skip helpers for the entity collision optimization
 * (lithium-style OFEM pattern).
 *
 * <p><strong>Why a dedicated class:</strong> a single shared empty list avoids
 * per-call allocation in the hot path (thousands of entities/tick in dense
 * worlds). The instance is immutable and thread-safe.</p>
 *
 * <p><strong>Reflection helpers:</strong> used by @Redirect mixins to call the
 * original method when the optimization is disabled. The target methods are
 * private static in {@code Entity}, so we use reflection to access them.</p>
 */
public final class Colliders {

    @SuppressWarnings("unused")
    private Colliders() {}

    /** An immutable empty list of voxel shapes — passed to
     * {@code collideWithShapes} to skip block collision when the
     * entity's movement is too small to matter. */
    public static final List<net.minecraft.world.phys.shapes.VoxelShape> EMPTY =
            Collections.emptyList();

    private static final Class<?> ENTITY_CLASS;
    private static final java.lang.reflect.Method COLLECT_COLLIDERS;
    private static final java.lang.reflect.Method COLLIDE_WITH_SHAPES;

    static {
        try {
            ENTITY_CLASS = Class.forName("net.minecraft.world.entity.Entity");
            COLLECT_COLLIDERS = ENTITY_CLASS.getDeclaredMethod(
                    "collectCollidersIgnoringWorldBorder",
                    Class.forName("net.minecraft.world.phys.shapes.CollisionContext"),
                    Class.forName("net.minecraft.world.level.Level"),
                    List.class,
                    Class.forName("net.minecraft.world.phys.AABB"));
            COLLECT_COLLIDERS.setAccessible(true);

            COLLIDE_WITH_SHAPES = ENTITY_CLASS.getDeclaredMethod(
                    "collideWithShapes",
                    Class.forName("net.minecraft.world.phys.Vec3"),
                    Class.forName("net.minecraft.world.phys.AABB"),
                    List.class);
            COLLIDE_WITH_SHAPES.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Colliders reflection", e);
        }
    }

    /**
     * Calls the original {@code collectCollidersIgnoringWorldBorder} method.
     */
    public static List<net.minecraft.world.phys.shapes.VoxelShape> callCollectColliders(
            Object source, Object world, List<?> entityColliders, Object box) {
        try {
            return (List<net.minecraft.world.phys.shapes.VoxelShape>)
                    COLLECT_COLLIDERS.invoke(null, source, world, entityColliders, box);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call collectCollidersIgnoringWorldBorder", e);
        }
    }

    /**
     * Calls the original {@code collideWithShapes} method.
     */
    public static net.minecraft.world.phys.Vec3 callCollideWithShapes(
            Object movement, Object box, List<?> colliders) {
        try {
            return (net.minecraft.world.phys.Vec3)
                    COLLIDE_WITH_SHAPES.invoke(null, movement, box, colliders);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call collideWithShapes", e);
        }
    }
}
