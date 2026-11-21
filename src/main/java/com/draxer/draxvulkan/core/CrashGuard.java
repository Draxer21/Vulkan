package com.draxer.draxvulkan.core;

import com.draxer.draxvulkan.DraxVulkan;
import com.draxer.draxvulkan.VkConfig;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Protección contra crashes con Vulkan.
 * Deja un archivo de "sesión abierta" al iniciar y lo borra al cerrar limpio.
 * Si al arrancar el archivo sigue ahí, la sesión anterior terminó mal.
 * Tras N cierres anómalos seguidos con Vulkan, cambia el juego a OpenGL.
 */
public final class CrashGuard {
    private static final Path DIR = FMLPaths.CONFIGDIR.get().resolve("draxvulkan");
    private static final Path LOCK = DIR.resolve("session.lock");
    private static final Path STATE = DIR.resolve("crashguard.properties");

    private static String pendingNotice = null;
    private static boolean previousSessionCrashed = false;
    private static String previousBackend = "unknown";

    private CrashGuard() {}

    public static void onModConstruct() {
        try {
            Files.createDirectories(DIR);
            if (Files.exists(LOCK)) {
                previousSessionCrashed = true;
                previousBackend = Files.readString(LOCK, StandardCharsets.UTF_8).trim();
            }
            writeLock("starting");
        } catch (Throwable t) {
            DraxVulkan.LOG.warn("CrashGuard: no se pudo preparar", t);
        }
    }

    /** Se llama cuando ya sabemos qué backend está activo. */
    public static void onBackendKnown(boolean vulkan) {
        writeLock(vulkan ? "vulkan" : "opengl");
        Properties p = load();
        int crashes = Integer.parseInt(p.getProperty("vulkanCrashes", "0"));

        boolean prevWasVulkan = previousBackend.equals("vulkan") || previousBackend.equals("starting");
        if (previousSessionCrashed && prevWasVulkan) {
            crashes++;
            DraxVulkan.LOG.warn("CrashGuard: la sesión anterior terminó mal con Vulkan ({} seguidas)", crashes);
        }

        if (!vulkan) {
            // Si ya estamos en OpenGL no hay nada que proteger; el contador se conserva para informar.
            p.setProperty("vulkanCrashes", String.valueOf(crashes));
            save(p);
            if ("true".equals(p.getProperty("switchedToOpenGL"))) {
                pendingNotice = "Se cambió a OpenGL tras " + p.getProperty("lastCrashCount", "?")
                        + " cierres inesperados con Vulkan. Para volver: /draxvulkan backend vulkan";
                p.remove("switchedToOpenGL");
                save(p);
            }
            return;
        }

        if (VkConfig.CRASH_GUARD.get() && crashes >= VkConfig.CRASH_THRESHOLD.get()) {
            if (GraphicsApiOption.set("opengl")) {
                p.setProperty("switchedToOpenGL", "true");
                p.setProperty("lastCrashCount", String.valueOf(crashes));
                crashes = 0;
                pendingNotice = "Vulkan se cerró de forma inesperada varias veces seguidas. "
                        + "Se dejó OpenGL para el próximo inicio. Para volver: /draxvulkan backend vulkan";
            }
        } else if (previousSessionCrashed && prevWasVulkan) {
            pendingNotice = "La sesión anterior con Vulkan terminó de forma inesperada ("
                    + crashes + "/" + VkConfig.CRASH_THRESHOLD.get() + " antes de volver a OpenGL).";
        }
        p.setProperty("vulkanCrashes", String.valueOf(crashes));
        save(p);
    }

    public static void onCleanShutdown() {
        try {
            Files.deleteIfExists(LOCK);
            Properties p = load();
            p.setProperty("vulkanCrashes", "0");
            save(p);
        } catch (Throwable ignored) {}
    }

    public static Optional<String> consumePendingNotice() {
        String n = pendingNotice;
        pendingNotice = null;
        return Optional.ofNullable(n);
    }

    public static int currentCount() {
        return Integer.parseInt(load().getProperty("vulkanCrashes", "0"));
    }

    private static void writeLock(String backend) {
        try {
            Files.writeString(LOCK, backend, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }

    private static Properties load() {
        Properties p = new Properties();
        try {
            if (Files.exists(STATE)) {
                try (var r = Files.newBufferedReader(STATE, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
            }
        } catch (Throwable ignored) {}
        return p;
    }

    private static void save(Properties p) {
        try (var w = Files.newBufferedWriter(STATE, StandardCharsets.UTF_8)) {
            p.store(w, "Drax Vulkan - CrashGuard");
        } catch (Throwable ignored) {}
    }
}
