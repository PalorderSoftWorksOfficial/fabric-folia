package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.config.FoliaConfig;
import com.palordersoftworks.fabricfolia.engine.FabricFoliaEngine;
import com.palordersoftworks.fabricfolia.engine.ServerThreadDeferral;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Inject(method = "getServerModName", at = @At("HEAD"), cancellable = true)
    private void fabricfolia$brand(CallbackInfoReturnable<String> cir) {
        FoliaConfig config = FabricFoliaMod.engine().config();

        if (config != null && config.serverBrand()) {
            cir.setReturnValue(config.serverBrandName());
        }
    }

    @Inject(method = "processPacketsAndTick(Z)V", at = @At("HEAD"))
    private void fabricfolia$beginTickPhase(boolean hasTimeLeft, CallbackInfo ci) {
        // Tick-phase boundary: close the worker execution gate and quiesce
        // BEFORE vanilla's packet drain and world passes. From here until
        // the end-of-tick flush reopens the gate, only the server thread
        // touches gameplay state — the single-writer phase rule that keeps
        // vanilla's iteration and worker-side mutations of the same
        // structures (entity sections, block-entity lists, tick lists)
        // from ever running concurrently.
        ServerThreadDeferral.noteServerThread(Thread.currentThread());
        FabricFoliaEngine engine = FabricFoliaMod.engine();
        if (engine != null) {
            engine.beginTickPhase();
        }
    }

    @Inject(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void fabricfolia$drainDeferredVanillaState(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerThreadDeferral.noteServerThread(Thread.currentThread());
        ServerThreadDeferral.drainAll();
    }
}
