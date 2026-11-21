package com.draxer.draxvulkan.mixin;

import com.draxer.draxvulkan.gpu.GpuPreference;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Selección de GPU: si el usuario eligió una GPU, las demás se marcan como "no aptas"
 * para que VulkanBackend.findPhysicalDevice termine eligiendo la preferida.
 */
@Mixin(targets = "com.mojang.blaze3d.vulkan.VulkanBackend")
public abstract class VulkanBackendMixin {
    @Inject(method = "isDeviceSuitable", at = @At("RETURN"), cancellable = true)
    private static void draxvulkan$filterDevice(VkPhysicalDevice device, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && !GpuPreference.allows(device)) {
            cir.setReturnValue(false);
        }
    }
}
