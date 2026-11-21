package com.draxer.draxvulkan.mixin;

import com.draxer.draxvulkan.perf.PipelineWarmup;
import com.mojang.blaze3d.systems.GpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cuando el juego limpia la caché de pipelines (recarga de recursos), se vuelven a precompilar. */
@Mixin(GpuDevice.class)
public abstract class GpuDeviceMixin {
    @Inject(method = "clearPipelineCache", at = @At("TAIL"))
    private void draxvulkan$requeueWarmup(CallbackInfo ci) {
        PipelineWarmup.requestRestart();
    }
}
