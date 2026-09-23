package com.palordersoftworks.fabricfolia.mixin;

import net.minecraft.ReportType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ReportType.class)
public class ReportTypeMixin {

    @Inject(method = "appendHeader", at = @At("HEAD"))
    private void fabricfolia$addDisclaimer(
            StringBuilder builder,
            List<String> extraComments,
            CallbackInfo ci
    ) {
        extraComments.add(
                "FABRIC FOLIA IS PRESENT! DO NOT REPORT BUGS TO THE MOD AUTHOR " +
                        "IF ITS RELATED TO FABRIC FOLIA. ONLY REPORT TO FABRIC FOLIA'S " +
                        "MOD AUTHOR FIRST IF YOU'RE UNSURE."
        );
    }
}
