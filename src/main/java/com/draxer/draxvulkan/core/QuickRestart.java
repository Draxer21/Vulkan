package com.draxer.draxvulkan.core;

import com.draxer.draxvulkan.DraxVulkan;
import com.draxer.draxvulkan.VkConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reinicio rápido para cambiar de API gráfica.
 * Cambiar entre OpenGL y Vulkan en caliente no es posible (la ventana GLFW y todos los recursos
 * de GPU pertenecen a un backend), así que se cierra el juego limpiamente y, desde un shutdown hook,
 * se lanza uno nuevo con los mismos argumentos + Quick Play para volver al mismo mundo o servidor.
 */
public final class QuickRestart {
    private static volatile boolean scheduled = false;

    private QuickRestart() {}

    /** @return mensaje de error, o vacío si se programó el reinicio. */
    public static Optional<String> switchTo(String api) {
        if (scheduled) return Optional.of("Ya hay un reinicio en curso.");
        List<String> cmd;
        try {
            cmd = buildCommand();
        } catch (Throwable t) {
            DraxVulkan.LOG.warn("QuickRestart: no se pudo reconstruir el comando", t);
            return Optional.of("No se pudo leer cómo se lanzó el juego (" + t.getMessage() + "). Cambia la API y reinicia a mano.");
        }
        if (cmd == null) return Optional.of("No se pudo leer cómo se lanzó el juego. Cambia la API y reinicia a mano.");

        if (!GraphicsApiOption.set(api)) return Optional.of("No se pudo guardar la opción de API gráfica.");

        boolean rejoin;
        try { rejoin = VkConfig.QUICK_RESTART_REJOIN.get(); } catch (Throwable t) { rejoin = true; }
        if (rejoin) addQuickPlay(cmd);

        Path log = FMLPaths.GAMEDIR.get().resolve("logs").resolve("draxvulkan-relaunch.log");
        File workDir = new File(System.getProperty("user.dir"));
        final List<String> finalCmd = cmd;
        DraxVulkan.LOG.info("QuickRestart: relanzando con {} argumentos ({} → {})", cmd.size(), BackendInfo.backendName(), api);
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, String.join("\n", redact(finalCmd)));
        } catch (Throwable ignored) {}

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                new ProcessBuilder(finalCmd)
                        .directory(workDir)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .start();
            } catch (Throwable t) {
                System.err.println("[DraxVulkan] No se pudo relanzar el juego: " + t);
            }
        }, "DraxVulkan-Relaunch"));
        scheduled = true;
        stopGame();
        return Optional.empty();
    }

    private static void stopGame() {
        Minecraft mc = Minecraft.getInstance();
        try {
            Method stop = mc.getClass().getMethod("stop");
            stop.invoke(mc);
        } catch (Throwable t) {
            DraxVulkan.LOG.warn("QuickRestart: Minecraft#stop no disponible, se cierra con System.exit", t);
            System.exit(0);
        }
    }

    /** Reconstruye java + argumentos JVM + clase principal + argumentos del juego. */
    static List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin",
                        isWindows() ? "javaw.exe" : "java").toString());
        cmd.add(java);

        List<String> jvm = new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
        // Opciones del lanzador que no aparecen en getInputArguments
        String cp = System.getProperty("java.class.path");
        boolean hasCp = jvm.stream().anyMatch(a -> a.equals("-cp") || a.equals("-classpath") || a.startsWith("--class-path"));
        String mp = System.getProperty("jdk.module.path");
        boolean hasMp = jvm.stream().anyMatch(a -> a.equals("-p") || a.startsWith("--module-path"));
        String addMods = System.getProperty("jdk.module.addmods");
        boolean hasAddMods = jvm.stream().anyMatch(a -> a.startsWith("--add-modules"));
        jvm.removeIf(a -> a.startsWith("-Djdk.module.") || a.startsWith("-agentlib:jdwp"));
        cmd.addAll(jvm);
        if (mp != null && !mp.isBlank() && !hasMp) { cmd.add("--module-path"); cmd.add(mp); }
        if (addMods != null && !addMods.isBlank() && !hasAddMods) { cmd.add("--add-modules"); cmd.add(addMods); }
        if (cp != null && !cp.isBlank() && !hasCp) { cmd.add("-cp"); cmd.add(cp); }

        // Clase principal y argumentos del juego
        List<String> program = programArgs();
        if (program == null || program.isEmpty()) return null;
        String mainModule = System.getProperty("jdk.module.main");
        String first = program.get(0);
        if (mainModule != null && !mainModule.isBlank()) {
            cmd.add("-m");
            cmd.add(first.contains("/") ? first : mainModule + "/" + first);
        } else {
            cmd.add(first);
        }
        cmd.addAll(program.subList(1, program.size()));
        return cmd;
    }

    /** Clase principal + argumentos. En Windows ProcessHandle suele no darlos, así que se usa sun.java.command. */
    private static List<String> programArgs() {
        String sjc = System.getProperty("sun.java.command");
        if (sjc == null || sjc.isBlank()) return null;
        // Los argumentos de Minecraft son pares "--clave valor"; los tokens sin "--" seguidos
        // se vuelven a unir con espacio (rutas con espacios, p. ej. "Curseforge Instances").
        String[] raw = sjc.trim().split(" ");
        List<String> out = new ArrayList<>();
        out.add(raw[0]);
        boolean prevWasValue = false;
        for (int i = 1; i < raw.length; i++) {
            String t = raw[i];
            if (t.startsWith("--") || out.size() == 1) {
                out.add(t);
                prevWasValue = !t.startsWith("--");
            } else if (prevWasValue) {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + t);
            } else {
                out.add(t);
                prevWasValue = true;
            }
        }
        return out;
    }

    private static void addQuickPlay(List<String> cmd) {
        // Quitar Quick Play previo
        for (int i = 0; i < cmd.size(); i++) {
            if (cmd.get(i).startsWith("--quickPlay")) {
                cmd.remove(i);
                if (i < cmd.size() && !cmd.get(i).startsWith("--")) cmd.remove(i);
                i--;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        String world = singleplayerWorldId(mc);
        if (world != null) {
            cmd.add("--quickPlaySingleplayer");
            cmd.add(world);
            return;
        }
        String server = serverAddress(mc);
        if (server != null) {
            cmd.add("--quickPlayMultiplayer");
            cmd.add(server);
        }
    }

    private static String singleplayerWorldId(Minecraft mc) {
        try {
            Object server = mc.getSingleplayerServer();
            if (server == null) return null;
            for (Class<?> c = server.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getSimpleName().equals("LevelStorageAccess")) {
                        f.setAccessible(true);
                        Object access = f.get(server);
                        Method id = access.getClass().getMethod("getLevelId");
                        return (String) id.invoke(access);
                    }
                }
            }
        } catch (Throwable t) {
            DraxVulkan.LOG.debug("QuickRestart: no se pudo leer el mundo actual", t);
        }
        return null;
    }

    private static String serverAddress(Minecraft mc) {
        try {
            Object data = mc.getClass().getMethod("getCurrentServer").invoke(mc);
            if (data == null) return null;
            for (String name : new String[]{"ip", "address"}) {
                try {
                    Field f = data.getClass().getField(name);
                    return String.valueOf(f.get(data));
                } catch (NoSuchFieldException ignored) {}
            }
            for (String name : new String[]{"ip", "address", "getAddress"}) {
                try {
                    return String.valueOf(data.getClass().getMethod(name).invoke(data));
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable t) {
            DraxVulkan.LOG.debug("QuickRestart: no se pudo leer el servidor actual", t);
        }
        return null;
    }

    /** Oculta el token de sesión en el log. */
    private static List<String> redact(List<String> cmd) {
        List<String> out = new ArrayList<>(cmd);
        for (int i = 0; i < out.size() - 1; i++) {
            if (out.get(i).equalsIgnoreCase("--accessToken")) out.set(i + 1, "***");
        }
        return out;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static String otherApi() {
        return BackendInfo.isVulkan() ? "opengl" : "vulkan";
    }
}
