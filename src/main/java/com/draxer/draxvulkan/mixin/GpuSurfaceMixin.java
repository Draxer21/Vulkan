package com.draxer.draxvulkan.mixin;

import com.draxer.draxvulkan.perf.PresentModeControl;
import com.mojang.blaze3d.systems.GpuSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Intercepta la configuración del swapchain para elegir el modo de presentación. */
@Mixin(GpuSurface.class)
public abstract class GpuSurfaceMixin {
    @Inject(method = "configure", at = @At("HEAD"), cancellable = true)
    private void draxvulkan$choosePresentMode(@Coerce Object configuration, CallbackInfo ci) {
        PresentModeControl.onConfigure(this, configuration, ci);
    }
}
