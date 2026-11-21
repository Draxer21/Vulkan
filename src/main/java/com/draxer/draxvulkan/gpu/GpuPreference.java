package com.draxer.draxvulkan.gpu;

import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU preferida para el backend Vulkan del juego.
 * Se lee directamente del archivo (no de ModConfig) porque el dispositivo Vulkan
 * se crea antes de que se carguen las configuraciones de los mods.
 */
public final class GpuPreference {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("DraxVulkan");
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("draxvulkan").resolve("gpu.properties");

    private static boolean loaded = false;
    private static int prefVendor = -1, prefDevice = -1;
    private static String prefName = null;
    private static Boolean preferredPresent = null;

    private GpuPreference() {}

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(FILE)) return;
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) { p.load(r); }
            String id = p.getProperty("preferred", "").trim();
            if (id.isEmpty() || id.equalsIgnoreCase("auto")) return;
            String[] parts = id.split(":");
            prefVendor = Integer.parseInt(parts[0], 16);
            prefDevice = Integer.parseInt(parts[1], 16);
            prefName = p.getProperty("name", "?");
        } catch (Throwable t) {
            LOG.warn("GpuPreference: gpu.properties inválido, se ignora", t);
            prefVendor = prefDevice = -1;
        }
    }

    /** ¿Puede el juego usar este dispositivo? */
    public static boolean allows(VkPhysicalDevice device) {
        load();
        if (prefVendor < 0) return true;
        int[] id = ids(device);
        if (id[0] == prefVendor && id[1] == prefDevice) {
            LOG.info("GpuPreference: usando la GPU elegida {}", prefName);
            return true;
        }
        // Solo se filtra si la GPU preferida realmente existe en este equipo.
        if (preferredPresent == null) preferredPresent = existsOn(device.getInstance());
        if (!preferredPresent) {
            LOG.warn("GpuPreference: la GPU elegida ({}) no está; se deja elegir a Minecraft", prefName);
            return true;
        }
        return false;
    }

    private static int[] ids(VkPhysicalDevice d) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(d, p);
            return new int[]{p.vendorID(), p.deviceID()};
        }
    }

    private static boolean existsOn(VkInstance instance) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer c = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, c, null);
            PointerBuffer b = stack.mallocPointer(c.get(0));
            vkEnumeratePhysicalDevices(instance, c, b);
            for (int i = 0; i < b.capacity(); i++) {
                int[] id = ids(new VkPhysicalDevice(b.get(i), instance));
                if (id[0] == prefVendor && id[1] == prefDevice) return true;
            }
        } catch (Throwable t) {
            LOG.warn("GpuPreference: no se pudo verificar la GPU elegida", t);
        }
        return false;
    }

    public static void save(GpuProbe.Gpu gpu) throws Exception {
        Files.createDirectories(FILE.getParent());
        Properties p = new Properties();
        if (gpu == null) {
            p.setProperty("preferred", "auto");
        } else {
            p.setProperty("preferred", String.format("%04x:%04x", gpu.vendorId(), gpu.deviceId()));
            p.setProperty("name", gpu.name());
        }
        try (var w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(w, "Drax Vulkan - GPU preferida (se aplica al reiniciar). preferred=auto para dejar elegir a Minecraft");
        }
    }

    public static String describe() {
        load();
        return prefVendor < 0 ? "automática" : prefName;
    }
}
