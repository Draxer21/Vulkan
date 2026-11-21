package com.draxer.draxvulkan.gpu;

import com.draxer.draxvulkan.DraxVulkan;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Selección de GPU. El dispositivo Vulkan se crea antes de que cargue cualquier mod,
 * así que la elección se aplica para el PRÓXIMO inicio mediante:
 *  - Windows: preferencia de GPU por aplicación (la misma que en Configuración > Pantalla > Gráficos).
 *  - Cualquier SO: variables de entorno del cargador Vulkan / Mesa para pegar en el launcher.
 */
public final class GpuSelector {
    private GpuSelector() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Variables de entorno recomendadas para forzar la GPU indicada. */
    public static List<String> envFor(GpuProbe.Gpu gpu) {
        List<String> env = new ArrayList<>();
        String drv = gpu.driverName().toLowerCase(Locale.ROOT);
        String glob;
        if (drv.contains("nvidia")) glob = "*nv*";
        else if (drv.contains("radv")) glob = "*radeon*";
        else if (drv.contains("amd")) glob = "*amd*";
        else if (drv.contains("intel") || drv.contains("anv")) glob = "*intel*,*igvk*";
        else glob = "*" + gpu.vendorName().toLowerCase(Locale.ROOT) + "*";
        // Cargador Khronos: solo carga los drivers cuyo manifiesto coincide.
        env.add("VK_LOADER_DRIVERS_SELECT=" + glob);
        // Mesa (Linux): fuerza vendor:device y oculta el resto con '!'.
        env.add(String.format("MESA_VK_DEVICE_SELECT=%04x:%04x!", gpu.vendorId(), gpu.deviceId()));
        if (gpu.type().equals("dedicada") && !isWindows()) {
            env.add("DRI_PRIME=1");
            if (gpu.vendorId() == 0x10DE) {
                env.add("__NV_PRIME_RENDER_OFFLOAD=1");
            }
        }
        return env;
    }

    /** Escribe el archivo de ayuda con las variables para el launcher. */
    public static Path writeLaunchHints(GpuProbe.Gpu gpu) throws Exception {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("draxvulkan");
        Files.createDirectories(dir);
        Path file = dir.resolve("gpu-launch.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("# Drax Vulkan - GPU elegida: ").append(gpu.name()).append(" (").append(gpu.type()).append(")\n");
        sb.append("# Pega estas variables de entorno en tu launcher (Prism/MultiMC: Configuración > Entorno).\n");
        sb.append("# Usa solo la que corresponda a tu sistema; las demás se ignoran sin problema.\n\n");
        for (String e : envFor(gpu)) sb.append(e).append('\n');
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Windows: guarda la preferencia de GPU para el java/javaw.exe actual.
     * @param highPerformance true = GPU dedicada, false = ahorro de energía (integrada).
     * @return mensaje de resultado.
     */
    public static String applyWindowsPreference(boolean highPerformance) {
        if (!isWindows()) return "Solo disponible en Windows.";
        String exe = ProcessHandle.current().info().command().orElse(null);
        if (exe == null) return "No se pudo saber la ruta de Java.";
        String data = "GpuPreference=" + (highPerformance ? 2 : 1) + ";";
        try {
            Process p = new ProcessBuilder("reg", "add",
                    "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences",
                    "/v", exe, "/t", "REG_SZ", "/d", data, "/f")
                    .redirectErrorStream(true).start();
            int code = p.waitFor();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            DraxVulkan.LOG.info("reg add -> {} {}", code, out);
            if (code != 0) return "Windows rechazó el cambio: " + out;
            return "Preferencia guardada para " + exe + " (" + (highPerformance ? "alto rendimiento" : "ahorro de energía")
                    + "). Reinicia Minecraft para aplicarla.";
        } catch (Exception e) {
            return "Error al escribir el registro: " + e.getMessage();
        }
    }
}
