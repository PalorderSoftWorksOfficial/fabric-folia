package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.FabricFoliaMod;
import com.palordersoftworks.fabricfolia.config.FoliaConfig;
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

    @Inject(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void fabricfolia$drainDeferredVanillaState(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerThreadDeferral.noteServerThread(Thread.currentThread());
        ServerThreadDeferral.drainAll();
    }
}
