package com.draxer.draxvulkan.core;

import com.draxer.draxvulkan.DraxVulkan;
import com.mojang.blaze3d.systems.RenderSystem;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lee la información del dispositivo gráfico de Blaze3D.
 * Se usa reflexión sobre los records DeviceInfo / DeviceLimits para no depender
 * de nombres internos que Mojang cambia entre versiones.
 */
public final class BackendInfo {
    private static final Map<String, String> INFO = new LinkedHashMap<>();
    private static final Map<String, String> LIMITS = new LinkedHashMap<>();
    private static String backend = "desconocido";
    private static String gpu = "desconocida";

    private BackendInfo() {}

    public static synchronized void refresh() {
        INFO.clear();
        LIMITS.clear();
        Object device;
        try {
            device = RenderSystem.getDevice();
        } catch (Throwable t) {
            DraxVulkan.LOG.warn("No se pudo obtener el GpuDevice", t);
            return;
        }
        if (device == null) return;
        readRecord(device, INFO, "getDeviceInfo", "deviceInfo", "info");
        readRecord(device, LIMITS, "getDeviceLimits", "deviceLimits", "limits");

        backend = firstOf(INFO, "backendName", "backend");
        gpu = firstOf(INFO, "name", "renderer", "deviceName");
        if ("?".equals(backend)) {
            // Respaldo: deducirlo del nombre de clase del dispositivo
            String cls = device.getClass().getName().toLowerCase();
            backend = cls.contains("vulkan") ? "Vulkan" : cls.contains("opengl") || cls.contains(".gl") ? "OpenGL" : device.getClass().getSimpleName();
        }
    }

    private static void readRecord(Object device, Map<String, String> out, String... getterNames) {
        for (String g : getterNames) {
            try {
                Method m = device.getClass().getMethod(g);
                Object rec = m.invoke(device);
                if (rec == null) continue;
                if (rec.getClass().isRecord()) {
                    for (RecordComponent rc : rec.getClass().getRecordComponents()) {
                        Object v = rc.getAccessor().invoke(rec);
                        String s = String.valueOf(v);
                        if (s.length() > 160) s = s.substring(0, 157) + "...";
                        out.put(rc.getName(), s);
                    }
                } else {
                    out.put(g, String.valueOf(rec));
                }
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                DraxVulkan.LOG.debug("Fallo leyendo {}", g, t);
            }
        }
    }

    private static String firstOf(Map<String, String> map, String... keys) {
        for (String k : keys) {
            String v = map.get(k);
            if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        }
        return "?";
    }

    public static boolean isVulkan() { return backend.toLowerCase().contains("vulkan"); }
    public static String backendName() { return backend; }
    public static String gpuName() { return gpu; }
    public static String vendor() { return firstOf(INFO, "vendorName", "vendor"); }
    public static String driver() { return firstOf(INFO, "driverInfo", "driver", "version"); }
    public static String deviceType() { return firstOf(INFO, "type", "deviceType"); }
    public static synchronized Map<String, String> info() { return new LinkedHashMap<>(INFO); }
    public static synchronized Map<String, String> limits() { return new LinkedHashMap<>(LIMITS); }
}
