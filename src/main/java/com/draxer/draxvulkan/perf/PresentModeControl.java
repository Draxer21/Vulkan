package com.draxer.draxvulkan.perf;

import com.draxer.draxvulkan.VkConfig;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Elige el modo de presentación del swapchain Vulkan.
 *  - SMART: vsync apagado (IMMEDIATE) → MAILBOX si existe (sin tearing, misma latencia baja);
 *           vsync encendido (FIFO) → FIFO_RELAXED si existe (no se divide a 30 FPS al caer bajo el refresco).
 *  - VANILLA: no toca nada.
 *  - Los demás fuerzan ese modo si la GPU lo soporta.
 */
public final class PresentModeControl {
    public enum Mode { VANILLA, SMART, MAILBOX, IMMEDIATE, FIFO, FIFO_RELAXED }

    private static final Logger LOG = LoggerFactory.getLogger("DraxVulkan");
    private static final ThreadLocal<Boolean> REENTRY = ThreadLocal.withInitial(() -> false);

    private static volatile String vanillaMode = "?";
    private static volatile String appliedMode = "?";
    private static volatile List<String> supported = List.of();
    private static volatile String configShape = "?";
    private static boolean warned = false;

    private PresentModeControl() {}

    private static Mode currentSetting() {
        try {
            return VkConfig.PRESENT_MODE.get();
        } catch (Throwable notLoadedYet) {
            return Mode.SMART; // la ventana se configura antes de que carguen las configs de mods
        }
    }

    /** ¿La superficie es OpenGL? (se mira la clase del backend interno de GpuSurface) */
    private static boolean isOpenGlSurface(Object surface) {
        for (Class<?> c = surface.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getName().equals("backend")) {
                    try {
                        f.setAccessible(true);
                        Object b = f.get(surface);
                        if (b == null) return false;
                        String n = b.getClass().getName().toLowerCase(Locale.ROOT);
                        return !n.contains("vulkan");
                    } catch (Throwable t) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    /**
     * OpenGL: tras la configuración de Minecraft, si hay vsync y el driver soporta
     * *_EXT_swap_control_tear, se usa intervalo -1 (vsync adaptativo = equivalente a FIFO_RELAXED).
     */
    public static void afterConfigure(Object surface, Object config) {
        if (REENTRY.get() || config == null || !isOpenGlSurface(surface)) return;
        try {
            configShape = config.toString();
            boolean vsync = false;
            String vanilla = "?";
            if (config.getClass().isRecord()) {
                for (RecordComponent rc : config.getClass().getRecordComponents()) {
                    rc.getAccessor().setAccessible(true);
                    Object v = rc.getAccessor().invoke(config);
                    if (v instanceof Enum<?> e && rc.getType().getSimpleName().toLowerCase(Locale.ROOT).contains("present")) {
                        vanilla = e.name();
                        String up = e.name().toUpperCase(Locale.ROOT);
                        vsync = up.contains("FIFO") || up.contains("VSYNC");
                    } else if (v instanceof Boolean b && rc.getName().toLowerCase(Locale.ROOT).contains("vsync")) {
                        vanilla = b ? "VSYNC" : "SIN VSYNC";
                        vsync = b;
                    }
                }
            }
            vanillaMode = vanilla;
            appliedMode = vanilla;
            Mode mode = currentSetting();
            boolean wantAdaptive = vsync && (mode == Mode.SMART || mode == Mode.FIFO_RELAXED);
            if (!wantAdaptive) return;
            boolean tear = org.lwjgl.glfw.GLFW.glfwExtensionSupported("WGL_EXT_swap_control_tear")
                    || org.lwjgl.glfw.GLFW.glfwExtensionSupported("GLX_EXT_swap_control_tear");
            if (!tear) {
                appliedMode = vanilla + " (sin soporte adaptativo)";
                return;
            }
            org.lwjgl.glfw.GLFW.glfwSwapInterval(-1);
            appliedMode = "VSYNC ADAPTATIVO";
            LOG.info("OpenGL: vsync adaptativo activado (swap interval -1)");
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOG.warn("PresentModeControl: no se pudo activar el vsync adaptativo en OpenGL", t);
            }
        }
    }

    public static void onConfigure(Object surface, Object config, CallbackInfo ci) {
        if (REENTRY.get() || config == null || isOpenGlSurface(surface)) return;
        try {
            Class<?> cc = config.getClass();
            configShape = config.toString();
            Collection<?> sup = supportedModes(surface);
            List<String> names = new ArrayList<>();
            for (Object o : sup) names.add(o instanceof Enum<?> e ? e.name() : String.valueOf(o));
            supported = names;

            if (!cc.isRecord()) return;
            RecordComponent[] comps = cc.getRecordComponents();
            int idx = -1;
            for (int i = 0; i < comps.length; i++) {
                Class<?> t = comps[i].getType();
                if (t.isEnum() && (comps[i].getName().toLowerCase(Locale.ROOT).contains("present")
                        || t.getSimpleName().toLowerCase(Locale.ROOT).contains("present"))) {
                    idx = i;
                }
            }
            if (idx < 0) return;

            Object[] vals = new Object[comps.length];
            Class<?>[] types = new Class<?>[comps.length];
            for (int i = 0; i < comps.length; i++) {
                comps[i].getAccessor().setAccessible(true);
                vals[i] = comps[i].getAccessor().invoke(config);
                types[i] = comps[i].getType();
            }
            Enum<?> vanilla = (Enum<?>) vals[idx];
            vanillaMode = vanilla.name();

            Enum<?> target = choose(currentSetting(), vanilla, sup);
            if (target == null || target == vanilla) {
                appliedMode = vanilla.name();
                return;
            }

            vals[idx] = target;
            Constructor<?> ctor = cc.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            Object newConfig = ctor.newInstance(vals);

            Method configure = null;
            for (Method m : surface.getClass().getMethods()) {
                if (m.getName().equals("configure") && m.getParameterCount() == 1) configure = m;
            }
            if (configure == null) return;

            REENTRY.set(true);
            try {
                configure.invoke(surface, newConfig);
            } finally {
                REENTRY.set(false);
            }
            appliedMode = target.name();
            LOG.info("Modo de presentación: {} → {}", vanilla.name(), target.name());
            ci.cancel();
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOG.warn("PresentModeControl: no se pudo cambiar el modo de presentación; se deja el de Minecraft", t);
            }
        }
    }

    private static Collection<?> supportedModes(Object surface) {
        try {
            Object r = surface.getClass().getMethod("supportedPresentModes").invoke(surface);
            if (r instanceof Collection<?> c) return c;
        } catch (Throwable ignored) {}
        return List.of();
    }

    private static Enum<?> choose(Mode mode, Enum<?> vanilla, Collection<?> sup) {
        return switch (mode) {
            case VANILLA -> null;
            case SMART -> {
                String v = vanilla.name().toUpperCase(Locale.ROOT);
                if (v.contains("IMMEDIATE")) yield find(sup, "MAILBOX");
                if (v.endsWith("FIFO") && !v.contains("RELAXED")) yield find(sup, "FIFO_RELAXED");
                yield null;
            }
            case MAILBOX -> find(sup, "MAILBOX");
            case IMMEDIATE -> find(sup, "IMMEDIATE");
            case FIFO -> find(sup, "FIFO");
            case FIFO_RELAXED -> find(sup, "FIFO_RELAXED");
        };
    }

    private static Enum<?> find(Collection<?> sup, String wanted) {
        for (Object o : sup) {
            if (!(o instanceof Enum<?> e)) continue;
            String n = e.name().toUpperCase(Locale.ROOT);
            if (n.equals(wanted) || n.endsWith("_" + wanted) && !(wanted.equals("FIFO") && n.contains("RELAXED"))) {
                return e;
            }
        }
        return null;
    }

    /** Fuerza a Minecraft a reconfigurar el swapchain (al cambiar la opción en el menú). */
    public static void reapply() {
        try {
            Minecraft mc = Minecraft.getInstance();
            Method m = mc.getClass().getMethod("invalidateSurfaceConfiguration");
            m.invoke(mc);
        } catch (Throwable t) {
            LOG.debug("No se pudo invalidar la superficie", t);
        }
    }

    public static String vanillaMode() { return vanillaMode; }
    public static String appliedMode() { return appliedMode; }
    public static List<String> supported() { return supported; }
    public static String configShape() { return configShape; }
}
