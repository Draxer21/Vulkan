package com.draxer.draxvulkan.perf;

import com.draxer.draxvulkan.VkConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Precompila los RenderPipelines de vanilla en segundo plano (unos pocos por tick)
 * para que Vulkan no tenga que compilar shaders la primera vez que algo aparece en pantalla
 * (eso es lo que provoca los tirones al ver un bloque, mob o efecto nuevo).
 */
public final class PipelineWarmup {
    private static final Logger LOG = LoggerFactory.getLogger("DraxVulkan");

    private static final ArrayDeque<Object> QUEUE = new ArrayDeque<>();
    private static volatile boolean restartRequested = true;
    private static int total = 0, done = 0, failed = 0;
    private static long nanos = 0;
    private static Method precompile;

    private PipelineWarmup() {}

    public static void requestRestart() {
        restartRequested = true;
    }

    /** Llamar en cada tick del cliente (hilo de render). */
    public static void tick() {
        boolean enabled;
        int perTick;
        try {
            enabled = VkConfig.PRECOMPILE_PIPELINES.get();
            perTick = VkConfig.PRECOMPILE_PER_TICK.get();
        } catch (Throwable notLoaded) {
            return;
        }
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null) return; // recargando recursos: esperar

        if (restartRequested) {
            restartRequested = false;
            collect();
        }
        if (QUEUE.isEmpty()) return;

        Object device = RenderSystem.getDevice();
        if (device == null) return;
        long start = System.nanoTime();
        for (int i = 0; i < perTick && !QUEUE.isEmpty(); i++) {
            Object pipeline = QUEUE.poll();
            try {
                if (precompile == null) precompile = findPrecompile(device.getClass(), pipeline.getClass());
                if (precompile == null) {
                    QUEUE.clear();
                    LOG.warn("PipelineWarmup: GpuDevice no tiene precompilePipeline(RenderPipeline)");
                    return;
                }
                precompile.invoke(device, pipeline);
                done++;
            } catch (Throwable t) {
                failed++;
                LOG.debug("PipelineWarmup: falló {}", pipeline, t);
            }
            // Nunca gastar más de ~4 ms por tick para no causar el tirón que queremos evitar.
            if (System.nanoTime() - start > 4_000_000L) break;
        }
        nanos += System.nanoTime() - start;
        if (QUEUE.isEmpty()) {
            LOG.info("PipelineWarmup: {} pipelines precompilados ({} fallidos) en {} ms", done, failed, nanos / 1_000_000);
        }
    }

    private static Method findPrecompile(Class<?> deviceClass, Class<?> pipelineClass) {
        for (Method m : deviceClass.getMethods()) {
            if (m.getName().equals("precompilePipeline") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(pipelineClass)) {
                return m;
            }
        }
        return null;
    }

    private static void collect() {
        QUEUE.clear();
        total = done = failed = 0;
        nanos = 0;
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        try {
            Class<?> pipelineClass = Class.forName("com.mojang.blaze3d.pipeline.RenderPipeline");
            Class<?> holder = Class.forName("net.minecraft.client.renderer.RenderPipelines");
            for (Field f : holder.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(null);
                List<Object> found = new ArrayList<>();
                if (pipelineClass.isInstance(v)) {
                    found.add(v);
                } else if (v instanceof Collection<?> c) {
                    for (Object o : c) {
                        if (pipelineClass.isInstance(o)) found.add(o);
                    }
                } else if (v instanceof Map<?, ?> m) {
                    for (Object o : m.values()) {
                        if (pipelineClass.isInstance(o)) found.add(o);
                    }
                }
                for (Object o : found) if (seen.put(o, true) == null) QUEUE.add(o);
            }
        } catch (Throwable t) {
            LOG.warn("PipelineWarmup: no se pudieron listar los RenderPipelines", t);
        }
        total = QUEUE.size();
        LOG.info("PipelineWarmup: {} pipelines en cola", total);
    }

    public static int total() { return total; }
    public static int done() { return done; }
    public static boolean running() { return !QUEUE.isEmpty(); }
}
