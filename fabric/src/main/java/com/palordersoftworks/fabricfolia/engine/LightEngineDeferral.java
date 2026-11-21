package com.palordersoftworks.fabricfolia.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LevelLightEngine;

public final class LightEngineDeferral {

    private LightEngineDeferral() {
    }

    public static void updateSectionStatus(LevelLightEngine engine, BlockPos position, boolean nonEmpty) {
        BlockPos stablePosition = position.immutable();
        runMutation(() -> engine.updateSectionStatus(stablePosition, nonEmpty));
    }

    public static void checkBlock(LevelLightEngine engine, BlockPos position) {
        BlockPos stablePosition = position.immutable();
        runMutation(() -> engine.checkBlock(stablePosition));
    }

    public static void runMutation(Runnable mutation) {
        if (ServerThreadDeferral.isServerThread()) {
            mutation.run();
        } else {
            ServerThreadDeferral.defer(mutation);
        }
    }
}
