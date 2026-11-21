package com.palordersoftworks.fabricfolia.util;

import com.palordersoftworks.fabricfolia.patches.PatchRegistry;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

public final class HardHitBoxEntityIndex {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private HardHitBoxEntityIndex() {
    }

    public static boolean shouldFilter(Entity entity) {
        return PatchRegistry.isEnabled("hard-hitbox-entity-collision")
                && !treatsGeneralEntityAsHardHitBox(entity);
    }

    public static boolean hasHardHitBox(Entity entity) {
        return entity.canBeCollidedWith(null) || entity instanceof HappyGhast;
    }

    public static boolean treatsGeneralEntityAsHardHitBox(Entity entity) {
        return entity instanceof Boat || entity instanceof AbstractMinecart;
    }

    public static void enter() {
        ACTIVE.set(true);
    }

    public static void exit() {
        ACTIVE.remove();
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }
}
