package com.palordersoftworks.fabricfolia.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.palordersoftworks.fabricfolia.util.HardHitBoxEntityIndex;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

@Mixin(EntityGetter.class)
public interface EntityGetterMixin {

    @WrapOperation(
            method = "getEntityCollisions",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/EntityGetter;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"
            )
    )
    private List<Entity> fabricfolia$filterHardHitboxCandidates(
            EntityGetter getter,
            Entity entity,
            AABB box,
            Predicate<? super Entity> predicate,
            Operation<List<Entity>> original
    ) {
        if (!HardHitBoxEntityIndex.shouldFilter(entity)) {
            return original.call(getter, entity, box, predicate);
        }
        HardHitBoxEntityIndex.enter();
        try {
            return original.call(getter, entity, box, predicate);
        } finally {
            HardHitBoxEntityIndex.exit();
        }
    }
}
