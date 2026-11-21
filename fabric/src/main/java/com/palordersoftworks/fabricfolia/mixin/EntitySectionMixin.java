package com.palordersoftworks.fabricfolia.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.palordersoftworks.fabricfolia.patches.PatchRegistry;
import com.palordersoftworks.fabricfolia.util.ConcurrentClassInstanceMultiMap;
import com.palordersoftworks.fabricfolia.util.HardHitBoxEntityIndex;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.Visibility;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntitySection.class)
public abstract class EntitySectionMixin<T extends EntityAccess> {

    @Unique
    private ClassInstanceMultiMap<T> fabricfolia$hardHitBoxes;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void fabricfolia$createHardHitboxIndex(Class<T> entityClass, Visibility status, CallbackInfo ci) {
        if (entityClass == Entity.class && PatchRegistry.isEnabled("hard-hitbox-entity-collision")) {
            this.fabricfolia$hardHitBoxes = new ConcurrentClassInstanceMultiMap<>(entityClass);
        }
    }

    @Inject(method = "add", at = @At("TAIL"))
    private void fabricfolia$indexHardHitboxAdd(T entity, CallbackInfo ci) {
        if (this.fabricfolia$hardHitBoxes != null
                && entity instanceof Entity minecraftEntity
                && HardHitBoxEntityIndex.hasHardHitBox(minecraftEntity)) {
            this.fabricfolia$hardHitBoxes.add(entity);
        }
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void fabricfolia$indexHardHitboxRemove(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && this.fabricfolia$hardHitBoxes != null) {
            this.fabricfolia$hardHitBoxes.remove(entity);
        }
    }

    @ModifyExpressionValue(
            method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/entity/EntitySection;storage:Lnet/minecraft/util/ClassInstanceMultiMap;",
                    opcode = Opcodes.GETFIELD
            )
    )
    private ClassInstanceMultiMap<T> fabricfolia$selectHardHitboxes(ClassInstanceMultiMap<T> original) {
        return HardHitBoxEntityIndex.isActive() && this.fabricfolia$hardHitBoxes != null
                ? this.fabricfolia$hardHitBoxes
                : original;
    }

    @ModifyExpressionValue(
            method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/level/entity/EntitySection;storage:Lnet/minecraft/util/ClassInstanceMultiMap;",
                    opcode = Opcodes.GETFIELD
            )
    )
    private ClassInstanceMultiMap<T> fabricfolia$selectTypedHardHitboxes(ClassInstanceMultiMap<T> original) {
        return HardHitBoxEntityIndex.isActive() && this.fabricfolia$hardHitBoxes != null
                ? this.fabricfolia$hardHitBoxes
                : original;
    }
}
