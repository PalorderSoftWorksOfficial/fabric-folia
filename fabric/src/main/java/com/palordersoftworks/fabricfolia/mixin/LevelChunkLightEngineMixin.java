package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.LightEngineDeferral;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelChunk.class)
public abstract class LevelChunkLightEngineMixin {

    @Redirect(
            method = "setBlockState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;updateSectionStatus(Lnet/minecraft/core/BlockPos;Z)V"
            )
    )
    private void fabricfolia$deferSectionStatus(LevelLightEngine lightEngine, BlockPos position, boolean nonEmpty) {
        LightEngineDeferral.updateSectionStatus(lightEngine, position, nonEmpty);
    }

    @Redirect(
            method = "setBlockState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;checkBlock(Lnet/minecraft/core/BlockPos;)V"
            )
    )
    private void fabricfolia$deferBlockLightCheck(LevelLightEngine lightEngine, BlockPos position) {
        LightEngineDeferral.checkBlock(lightEngine, position);
    }
}
