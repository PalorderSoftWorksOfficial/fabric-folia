package com.palordersoftworks.fabricfolia.util;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.palordersoftworks.fabricfolia.patches.PatchRegistry;
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

public final class OfemCollision {

    public static final double MIN_MOVEMENT_SQR = 100.0;
    public static final double DIRECTIONAL_MOVEMENT_FACTOR = 12.0;

    private static final ThreadLocal<Vec3> MOVEMENT = new ThreadLocal<>();
    private static final ThreadLocal<Ctx> CONTEXT = new ThreadLocal<>();

    public static final class Ctx {
        private final Level level;
        private final Entity entity;
        private final AABB vanillaSearchBox;
        private boolean fallback;

        private Ctx(Level level, Entity entity, AABB vanillaSearchBox) {
            this.level = level;
            this.entity = entity;
            this.vanillaSearchBox = vanillaSearchBox;
        }

        public Level level() {
            return level;
        }

        public Entity entity() {
            return entity;
        }

        public AABB vanillaSearchBox() {
            return vanillaSearchBox;
        }

        public boolean fallback() {
            return fallback;
        }

        public void fallback(boolean fallback) {
            this.fallback = fallback;
        }
    }

    private OfemCollision() {
    }

    public static Vec3 captureMovement(Vec3 movement) {
        if (PatchRegistry.isEnabled("entity-collision-opt")) {
            MOVEMENT.set(movement);
        } else {
            MOVEMENT.remove();
            CONTEXT.remove();
        }
        return movement;
    }

    public static Iterable<VoxelShape> collect(Level world, Entity entity, AABB box,
                                               Operation<Iterable<VoxelShape>> original) {
        if (PatchRegistry.isEnabled("entity-collision-opt")) {
            Vec3 movement = MOVEMENT.get();
            if (movement != null && checkMovement(movement)) {
                CONTEXT.set(new Ctx(world, entity, box));
                PatchRegistry.recordInvocation("entity-collision-opt");
                return Collections.emptyList();
            }
        }
        CONTEXT.remove();
        return original.call(world, entity, box);
    }

    public static boolean isOptimizing() {
        return CONTEXT.get() != null;
    }

    public static void rewriteShapes(Args args) {
        Ctx context = CONTEXT.get();
        if (context == null) {
            return;
        }

        Direction.Axis axis = args.get(0);
        AABB box = args.get(1);
        List<VoxelShape> entityAndBorder = args.get(2);
        double maxDist = args.get(3);

        List<VoxelShape> blockShapes;
        if (context.fallback()) {
            blockShapes = collectShapes(context, context.vanillaSearchBox());
        } else {
            try {
                AABB searchBox = axisSearchBox(box, context.vanillaSearchBox(), axis, maxDist);
                blockShapes = collectShapes(context, searchBox);
            } catch (RuntimeException failure) {
                context.fallback(true);
                blockShapes = collectShapes(context, context.vanillaSearchBox());
                PatchRegistry.recordFallback("entity-collision-opt");
            }
        }

        List<VoxelShape> combined = new ArrayList<>(entityAndBorder.size() + blockShapes.size());
        combined.addAll(entityAndBorder);
        combined.addAll(blockShapes);
        args.set(2, combined);
    }

    public static AABB axisSearchBox(AABB box, AABB vanillaSearchBox, Direction.Axis axis, double maxDist) {
        AABB swept = switch (axis) {
            case X -> box.expandTowards(maxDist, 0.0, 0.0);
            case Y -> box.expandTowards(0.0, maxDist, 0.0);
            case Z -> box.expandTowards(0.0, 0.0, maxDist);
        };
        return swept.inflate(1.0).intersect(vanillaSearchBox);
    }

    public static boolean checkMovement(Vec3 movement) {
        double x = Math.abs(movement.x);
        double y = Math.abs(movement.y);
        double z = Math.abs(movement.z);
        double movementSqr = movement.lengthSqr();
        return Double.isFinite(movementSqr)
                && movementSqr >= MIN_MOVEMENT_SQR
                && x * y * z > (x + y + z) * DIRECTIONAL_MOVEMENT_FACTOR;
    }

    public static void clear() {
        CONTEXT.remove();
        MOVEMENT.remove();
    }

    private static List<VoxelShape> collectShapes(Ctx context, AABB box) {
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : context.level().getBlockCollisions(context.entity(), box)) {
            shapes.add(shape);
        }
        return shapes;
    }
}
