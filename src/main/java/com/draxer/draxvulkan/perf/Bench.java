package com.draxer.draxvulkan.perf;

import com.draxer.draxvulkan.core.BackendInfo;
import com.draxer.draxvulkan.core.ClientUi;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;

/**
 * /draxvulkan bench: mide N segundos (FPS promedio, 1 % y 0,1 % bajos, pico) y guarda el resultado
 * por API gráfica para comparar Vulkan contra OpenGL en el mismo PC.
 */
public final class Bench {
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("draxvulkan").resolve("bench.properties");

    private static boolean running = false;
    private static long endAt;
    private static float[] samples = new float[4096];
    private static int n = 0;

    private Bench() {}

    public static boolean start(int seconds) {
        if (running) return false;
        n = 0;
        endAt = System.nanoTime() + seconds * 1_000_000_000L;
        running = true;
        return true;
    }

    public static boolean isRunning() { return running; }

    public static int secondsLeft() {
        return running ? (int) Math.max(0, (endAt - System.nanoTime()) / 1_000_000_000L) : 0;
    }

    /** Llamado por FrameStats con cada frame válido. */
    public static void onFrame(float ms) {
        if (!running) return;
        if (n == samples.length) samples = Arrays.copyOf(samples, n * 2);
        samples[n++] = ms;
        if (System.nanoTime() >= endAt) finish();
    }

    /** Si la ventana pierde el foco, la medición se invalida. */
    public static void abort(String reason) {
        if (!running) return;
        running = false;
        ClientUi.chat(Component.literal("§6[Drax Vulkan] §cBenchmark cancelado: " + reason));
    }

    private static void finish() {
        running = false;
        if (n < 30) {
            ClientUi.chat(Component.literal("§6[Drax Vulkan] §cMuy pocos frames para medir."));
            return;
        }
        float[] s = Arrays.copyOf(samples, n);
        double sum = 0;
        for (float v : s) sum += v;
        Arrays.sort(s);
        float avgFps = (float) (1000.0 * n / sum);
        float low1 = 1000f / mean(s, Math.max(1, n / 100));
        float low01 = 1000f / mean(s, Math.max(1, n / 1000));
        float peak = s[n - 1];

        String api = BackendInfo.isVulkan() ? "vulkan" : "opengl";
        String present = PresentModeControl.appliedMode();
        String line = String.format(Locale.ROOT, "%.0f FPS · 1%% bajos %.0f · 0,1%% bajos %.0f · pico %.1f ms",
                avgFps, low1, low01, peak);
        ClientUi.chat(Component.literal("§6[Drax Vulkan] §aBenchmark " + api.toUpperCase(Locale.ROOT)
                + " §7(" + present + "): §f" + line));

        Properties p = load();
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        p.setProperty(api + ".avg", String.format(Locale.ROOT, "%.1f", avgFps));
        p.setProperty(api + ".low1", String.format(Locale.ROOT, "%.1f", low1));
        p.setProperty(api + ".low01", String.format(Locale.ROOT, "%.1f", low01));
        p.setProperty(api + ".peakMs", String.format(Locale.ROOT, "%.1f", peak));
        p.setProperty(api + ".present", present);
        p.setProperty(api + ".date", date);
        save(p);

        String other = api.equals("vulkan") ? "opengl" : "vulkan";
        if (p.getProperty(other + ".avg") != null) {
            float oAvg = Float.parseFloat(p.getProperty(other + ".avg"));
            float oLow = Float.parseFloat(p.getProperty(other + ".low1"));
            ClientUi.chat(Component.literal(String.format(Locale.ROOT,
                    "§7vs %s (%s): §f%.0f FPS · 1%% bajos %.0f §7→ promedio %s, 1%% bajos %s",
                    other.toUpperCase(Locale.ROOT), p.getProperty(other + ".date", "?"), oAvg, oLow,
                    diff(avgFps, oAvg), diff(low1, oLow))));
            ClientUi.chat(Component.literal("§8Compara siempre en el mismo lugar, mirando hacia el mismo lado y con la misma distancia de render."));
        } else {
            ClientUi.chat(Component.literal("§7Cambia a " + other.toUpperCase(Locale.ROOT)
                    + " (§e/draxvulkan switch§7), vuelve al mismo lugar y repite §e/draxvulkan bench§7 para comparar."));
        }
    }

    private static String diff(float mine, float other) {
        if (other <= 0) return "?";
        float pct = (mine - other) / other * 100f;
        return (pct >= 0 ? "§a+" : "§c") + String.format(Locale.ROOT, "%.0f%%", pct) + "§7";
    }

    /** Promedio de los k frames más lentos (el arreglo viene ordenado ascendente). */
    private static float mean(float[] sorted, int k) {
        float t = 0;
        for (int i = sorted.length - k; i < sorted.length; i++) t += sorted[i];
        return t / k;
    }

    private static Properties load() {
        Properties p = new Properties();
        try {
            if (Files.exists(FILE)) try (var r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) { p.load(r); }
        } catch (Throwable ignored) {}
        return p;
    }

    private static void save(Properties p) {
        try {
            Files.createDirectories(FILE.getParent());
            try (var w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) { p.store(w, "Drax Vulkan - benchmarks"); }
        } catch (Throwable ignored) {}
    }
}
