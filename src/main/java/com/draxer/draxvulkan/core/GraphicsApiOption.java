package com.draxer.draxvulkan.core;

import com.draxer.draxvulkan.DraxVulkan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.neoforged.fml.loading.FMLPaths;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cambia la opción "Graphics API" (preferredGraphicsBackend) tanto en memoria como en options.txt.
 * Valores válidos en 26.2: default, opengl, vulkan.
 */
public final class GraphicsApiOption {
    private GraphicsApiOption() {}

    public static Path optionsFile() {
        return FMLPaths.GAMEDIR.get().resolve("options.txt");
    }

    /** @return true si se aplicó al menos en un sitio. */
    public static boolean set(String value) {
        value = value.toLowerCase(Locale.ROOT);
        boolean ok = setInMemory(value);
        ok |= setInFile(value);
        return ok;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean setInMemory(String value) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.options == null) return false;
            Method m = mc.options.getClass().getMethod("preferredGraphicsBackend");
            OptionInstance opt = (OptionInstance) m.invoke(mc.options);
            Object current = opt.get();
            if (!(current instanceof Enum<?> e)) return false;
            for (Object c : e.getDeclaringClass().getEnumConstants()) {
                String name = ((Enum<?>) c).name().toLowerCase(Locale.ROOT);
                if (name.equals(value) || name.replace("_", "").equals(value)
                        || (value.equals("opengl") && name.contains("gl"))
                        || (value.equals("vulkan") && name.contains("vulkan"))) {
                    opt.set(c);
                    mc.options.save();
                    return true;
                }
            }
        } catch (Throwable t) {
            DraxVulkan.LOG.debug("No se pudo cambiar la opción en memoria", t);
        }
        return false;
    }

    public static boolean setInFile(String value) {
        Path file = optionsFile();
        try {
            if (!Files.exists(file)) return false;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(lines.size() + 1);
            boolean found = false;
            for (String l : lines) {
                if (l.startsWith("preferredGraphicsBackend:")) {
                    out.add("preferredGraphicsBackend:\"" + value + "\"");
                    found = true;
                } else {
                    out.add(l);
                }
            }
            if (!found) out.add("preferredGraphicsBackend:\"" + value + "\"");
            Files.write(file, out, StandardCharsets.UTF_8);
            return true;
        } catch (Throwable t) {
            DraxVulkan.LOG.warn("No se pudo editar options.txt", t);
            return false;
        }
    }

    public static String readFromFile() {
        try {
            for (String l : Files.readAllLines(optionsFile(), StandardCharsets.UTF_8)) {
                if (l.startsWith("preferredGraphicsBackend:")) {
                    return l.substring(l.indexOf(':') + 1).replace("\"", "").trim();
                }
            }
        } catch (Throwable ignored) {}
        return "default";
    }
}
