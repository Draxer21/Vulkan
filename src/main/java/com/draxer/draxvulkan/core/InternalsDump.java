package com.draxer.draxvulkan.core;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Vuelca la estructura del backend Vulkan de 26.2 (clases, campos y métodos) a un archivo.
 * Sirve para afinar en la siguiente versión del mod los ajustes internos
 * (modo de presentación, frames en vuelo, caché de pipelines) apuntando a nombres reales.
 */
public final class InternalsDump {
    private static final String[] CLASSES = {
            "com.mojang.blaze3d.vulkan.VulkanBackend",
            "com.mojang.blaze3d.vulkan.VulkanDevice",
            "com.mojang.blaze3d.vulkan.VulkanInstance",
            "com.mojang.blaze3d.vulkan.VulkanPhysicalDevice",
            "com.mojang.blaze3d.vulkan.VulkanGpuSurface",
            "com.mojang.blaze3d.vulkan.VulkanQueue",
            "com.mojang.blaze3d.vulkan.VulkanCommandEncoder",
            "com.mojang.blaze3d.vulkan.VulkanTransientMemory",
            "com.mojang.blaze3d.vulkan.DestructionQueue",
            "com.mojang.blaze3d.vulkan.init.VulkanFeature",
            "com.mojang.blaze3d.systems.GpuDevice",
            "com.mojang.blaze3d.systems.GpuSurface",
            "com.mojang.blaze3d.systems.HintsAndWorkarounds",
            "com.mojang.blaze3d.platform.Window",
            "net.minecraft.client.PreferredGraphicsApi",
    };

    private InternalsDump() {}

    public static Path write() throws Exception {
        Path out = FMLPaths.CONFIGDIR.get().resolve("draxvulkan").resolve("internals-26.2.txt");
        Files.createDirectories(out.getParent());
        StringBuilder sb = new StringBuilder("# Drax Vulkan - estructura interna del renderizador\n");
        sb.append("# Backend: ").append(BackendInfo.backendName()).append(" | GPU: ").append(BackendInfo.gpuName()).append("\n\n");
        sb.append("## DeviceInfo\n");
        BackendInfo.info().forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
        sb.append("## DeviceLimits\n");
        BackendInfo.limits().forEach((k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n'));
        sb.append('\n');

        Set<Class<?>> todo = new LinkedHashSet<>();
        try {
            Object dev = RenderSystem.getDevice();
            if (dev != null) {
                todo.add(dev.getClass());
                for (Field f : dev.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(dev);
                        if (v != null && v.getClass().getName().startsWith("com.mojang")) todo.add(v.getClass());
                    } catch (Throwable ignored) {}
                }
            }
            todo.add(Minecraft.getInstance().getWindow().getClass());
        } catch (Throwable ignored) {}
        for (String n : CLASSES) {
            try { todo.add(Class.forName(n)); } catch (Throwable ignored) {}
        }
        // Clases anidadas de GpuSurface (Configuration, PresentMode...)
        for (Class<?> c : new java.util.ArrayList<>(todo)) {
            if (c.getName().endsWith("GpuSurface") || c.getName().endsWith("VulkanGpuSurface")) {
                for (Class<?> inner : c.getDeclaredClasses()) todo.add(inner);
            }
        }
        try { todo.add(Class.forName("com.mojang.blaze3d.systems.GpuSurface$Configuration")); } catch (Throwable ignored) {}
        sb.append("## Present mode\n  vanilla=").append(com.draxer.draxvulkan.perf.PresentModeControl.vanillaMode())
          .append(" aplicado=").append(com.draxer.draxvulkan.perf.PresentModeControl.appliedMode())
          .append(" soportados=").append(com.draxer.draxvulkan.perf.PresentModeControl.supported())
          .append("\n  config=").append(com.draxer.draxvulkan.perf.PresentModeControl.configShape()).append("\n\n");
        for (Class<?> c : todo) dumpClass(sb, c);
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return out;
    }

    private static void dumpClass(StringBuilder sb, Class<?> c) {
        sb.append("== ").append(c.getName());
        if (c.getSuperclass() != null) sb.append(" extends ").append(c.getSuperclass().getSimpleName());
        if (c.getInterfaces().length > 0) {
            sb.append(" implements ");
            sb.append(String.join(", ", Arrays.stream(c.getInterfaces()).map(Class::getSimpleName).toList()));
        }
        sb.append('\n');
        if (c.isEnum()) {
            sb.append("  enum: ").append(Arrays.toString(c.getEnumConstants())).append('\n');
        }
        Field[] fields = c.getDeclaredFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));
        for (Field f : fields) {
            sb.append("  F ").append(Modifier.toString(f.getModifiers())).append(' ')
                    .append(f.getType().getSimpleName()).append(' ').append(f.getName()).append('\n');
        }
        Method[] methods = c.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method m : methods) {
            sb.append("  M ").append(Modifier.toString(m.getModifiers())).append(' ')
                    .append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(')
                    .append(String.join(", ", Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).toList()))
                    .append(")\n");
        }
        sb.append('\n');
    }
}
