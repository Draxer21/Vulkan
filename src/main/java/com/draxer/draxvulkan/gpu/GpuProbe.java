package com.draxer.draxvulkan.gpu;

import com.draxer.draxvulkan.DraxVulkan;
import com.draxer.draxvulkan.VkConfig;
import com.draxer.draxvulkan.core.BackendInfo;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Sonda Vulkan propia (instancia ligera e independiente de la del juego).
 * Enumera las GPU del sistema y, con VK_EXT_memory_budget, lee el uso real de VRAM
 * del proceso completo (incluye lo que usa Minecraft).
 */
public final class GpuProbe {
    public record Gpu(int index, String name, String type, int vendorId, int deviceId,
                      String driverName, String driverInfo, String apiVersion,
                      long deviceLocalBytes, boolean hasBudget) {
        public String vendorName() {
            return switch (vendorId) {
                case 0x10DE -> "NVIDIA";
                case 0x1002 -> "AMD";
                case 0x8086 -> "Intel";
                case 0x106B -> "Apple";
                case 0x13B5 -> "ARM";
                case 0x5143 -> "Qualcomm";
                default -> String.format("0x%04X", vendorId);
            };
        }
    }

    public record Vram(long usedBytes, long budgetBytes) {}

    private static VkInstance instance;
    private static final List<VkPhysicalDevice> DEVICES = new ArrayList<>();
    private static final List<Gpu> GPUS = new ArrayList<>();
    private static volatile boolean initDone = false;
    private static volatile boolean ready = false;
    private static volatile String error = null;
    private static volatile Vram vram = null;
    private static volatile boolean polling = false;
    private static Thread worker;

    private GpuProbe() {}

    /** Inicia la sonda en un hilo aparte para no trabar el arranque. */
    public static void initAsync() {
        Thread t = new Thread(GpuProbe::init, "DraxVulkan-GpuProbeInit");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void init() {
        if (initDone) return;
        initDone = true;
        try {
            try {
                org.lwjgl.vulkan.VK.getFunctionProvider();
            } catch (IllegalStateException notLoaded) {
                org.lwjgl.vulkan.VK.create();
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // ¿Hay portability enumeration? (necesario en macOS/MoltenVK)
                IntBuffer extCount = stack.mallocInt(1);
                vkEnumerateInstanceExtensionProperties((String) null, extCount, null);
                VkExtensionProperties.Buffer exts = VkExtensionProperties.calloc(extCount.get(0));
                boolean portability = false;
                try {
                    vkEnumerateInstanceExtensionProperties((String) null, extCount, exts);
                    for (int i = 0; i < exts.capacity(); i++) {
                        if ("VK_KHR_portability_enumeration".equals(exts.get(i).extensionNameString())) portability = true;
                    }
                } finally {
                    exts.free();
                }

                VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                        .sType$Default()
                        .pApplicationName(stack.UTF8("DraxVulkan-Probe"))
                        .pEngineName(stack.UTF8("DraxVulkan"))
                        .apiVersion(VK_API_VERSION_1_2);
                VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                        .sType$Default()
                        .pApplicationInfo(app);
                if (portability) {
                    PointerBuffer names = stack.mallocPointer(1);
                    names.put(stack.UTF8("VK_KHR_portability_enumeration")).flip();
                    ci.ppEnabledExtensionNames(names).flags(0x00000001); // VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
                }
                PointerBuffer pInst = stack.mallocPointer(1);
                int res = vkCreateInstance(ci, null, pInst);
                if (res != VK_SUCCESS) {
                    error = "vkCreateInstance falló (" + res + ")";
                    return;
                }
                instance = new VkInstance(pInst.get(0), ci);

                IntBuffer count = stack.mallocInt(1);
                vkEnumeratePhysicalDevices(instance, count, null);
                PointerBuffer devs = stack.mallocPointer(count.get(0));
                vkEnumeratePhysicalDevices(instance, count, devs);
                for (int i = 0; i < devs.capacity(); i++) {
                    VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                    try {
                        GpuProbe.Gpu g = describe(DEVICES.size(), pd);
                        DEVICES.add(pd);
                        GPUS.add(g);
                    } catch (Throwable t) {
                        DraxVulkan.LOG.warn("GpuProbe: no se pudo leer la GPU {}", i, t);
                    }
                }
            }
            ready = true;
            if (GPUS.isEmpty()) error = "0 GPU enumeradas";
            DraxVulkan.LOG.info("GpuProbe: {} GPU(s) detectadas", GPUS.size());
            for (Gpu g : GPUS) {
                DraxVulkan.LOG.info("  [{}] {} ({}, {}) driver={} {}", g.index(), g.name(), g.type(), g.vendorName(), g.driverName(), g.driverInfo());
            }
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            DraxVulkan.LOG.warn("GpuProbe: no se pudo iniciar la sonda Vulkan", t);
        }
    }

    private static Gpu describe(int index, VkPhysicalDevice pd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties base = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(pd, base);

            String driverName = "?", driverInfo = "?";
            if (VK_API_VERSION_MAJOR(base.apiVersion()) > 1 || VK_API_VERSION_MINOR(base.apiVersion()) >= 2) {
                VkPhysicalDeviceDriverProperties drv = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
                VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(drv.address());
                vkGetPhysicalDeviceProperties2(pd, p2);
                driverName = drv.driverNameString();
                driverInfo = drv.driverInfoString();
            }

            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(pd, mem);
            long local = 0;
            for (int h = 0; h < mem.memoryHeapCount(); h++) {
                if ((mem.memoryHeaps(h).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) local += mem.memoryHeaps(h).size();
            }

            String type = switch (base.deviceType()) {
                case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "dedicada";
                case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "integrada";
                case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "virtual";
                case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU (software)";
                default -> "otra";
            };
            int api = base.apiVersion();
            String apiStr = VK_API_VERSION_MAJOR(api) + "." + VK_API_VERSION_MINOR(api) + "." + VK_API_VERSION_PATCH(api);
            return new Gpu(index, base.deviceNameString(), type, base.vendorID(), base.deviceID(),
                    driverName, driverInfo, apiStr, local, hasExtension(pd, "VK_EXT_memory_budget"));
        }
    }

    private static boolean hasExtension(VkPhysicalDevice pd, String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer c = stack.mallocInt(1);
            vkEnumerateDeviceExtensionProperties(pd, (String) null, c, null);
            // En memoria normal: las GPU tienen cientos de extensiones y no caben en la pila de 64 KB de LWJGL.
            VkExtensionProperties.Buffer b = VkExtensionProperties.calloc(c.get(0));
            try {
                vkEnumerateDeviceExtensionProperties(pd, (String) null, c, b);
                for (int i = 0; i < b.capacity(); i++) {
                    if (name.equals(b.get(i).extensionNameString())) return true;
                }
            } finally {
                b.free();
            }
        }
        return false;
    }

    /** GPU que coincide con la que usa el juego (por nombre); si no, la primera dedicada. */
    public static Gpu activeGpu() {
        init();
        String game = BackendInfo.gpuName();
        for (Gpu g : GPUS) if (game != null && game.contains(g.name())) return g;
        for (Gpu g : GPUS) if (g.type().equals("dedicada")) return g;
        return GPUS.isEmpty() ? null : GPUS.get(0);
    }

    private static Vram queryVram(Gpu gpu) {
        if (gpu == null || !gpu.hasBudget()) return null;
        VkPhysicalDevice pd = DEVICES.get(gpu.index());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryBudgetPropertiesEXT budget = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack).sType$Default();
            VkPhysicalDeviceMemoryProperties2 mp2 = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default().pNext(budget.address());
            vkGetPhysicalDeviceMemoryProperties2(pd, mp2);
            VkPhysicalDeviceMemoryProperties mem = mp2.memoryProperties();
            long used = 0, total = 0;
            for (int h = 0; h < mem.memoryHeapCount(); h++) {
                if ((mem.memoryHeaps(h).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    used += budget.heapUsage(h);
                    total += budget.heapBudget(h);
                }
            }
            return new Vram(used, total);
        }
    }

    public static synchronized void setPolling(boolean on) {
        polling = on;
        if (on && worker == null) {
            worker = new Thread(() -> {
                init();
                while (true) {
                    try {
                        if (polling && instance != null) vram = queryVram(activeGpu());
                        Thread.sleep(VkConfig.VRAM_POLL_MS.get());
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        DraxVulkan.LOG.debug("GpuProbe: fallo leyendo VRAM", t);
                        vram = null;
                        try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    }
                }
            }, "DraxVulkan-GpuProbe");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public static synchronized void shutdown() {
        if (worker != null) worker.interrupt();
        if (instance != null) {
            try { vkDestroyInstance(instance, null); } catch (Throwable ignored) {}
            instance = null;
        }
    }

    /** No bloquea: devuelve null si la sonda aún no terminó (para usar desde el render). */
    public static Gpu activeGpuIfReady() {
        return ready ? activeGpu() : null;
    }

    public static boolean isReady() { return ready; }

    public static List<Gpu> gpus() { init(); return Collections.unmodifiableList(GPUS); }
    public static Vram vram() { return vram; }
    public static String error() { return error; }
}
