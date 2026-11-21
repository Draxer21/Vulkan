package com.draxer.draxvulkan.compat;

import com.draxer.draxvulkan.DraxVulkan;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * La pantalla de carga temprana de NeoForge crea su propio contexto OpenGL y puede
 * congelar o crashear el arranque cuando el juego usa Vulkan. Se desactiva en fml.toml
 * (efecto a partir del próximo inicio).
 */
public final class EarlyWindowFix {
    private static boolean changed = false;

    private EarlyWindowFix() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("fml.toml");
    }

    public static boolean isEarlyWindowOn() {
        try {
            for (String l : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String t = l.replace(" ", "");
                if (t.startsWith("earlyWindowControl=")) return t.endsWith("true");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void applyIfNeeded() {
        Path f = file();
        try {
            if (!Files.exists(f)) return;
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(lines.size());
            boolean mod = false;
            for (String l : lines) {
                String t = l.replace(" ", "");
                if (t.startsWith("earlyWindowControl=true")) {
                    out.add("earlyWindowControl = false # cambiado por Drax Vulkan (incompatible con Vulkan)");
                    mod = true;
                } else {
                    out.add(l);
                }
            }
            if (mod) {
                Files.write(f, out, StandardCharsets.UTF_8);
                changed = true;
                DraxVulkan.LOG.info("EarlyWindowFix: pantalla de carga temprana desactivada en fml.toml");
            }
        } catch (Throwable t) {
            DraxVulkan.LOG.warn("EarlyWindowFix: no se pudo editar fml.toml", t);
        }
    }

    public static boolean changedThisSession() { return changed; }
}
