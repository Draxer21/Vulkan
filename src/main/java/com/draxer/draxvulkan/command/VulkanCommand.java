package com.draxer.draxvulkan.command;

import com.draxer.draxvulkan.DraxVulkan;
import com.draxer.draxvulkan.compat.CompatScanner;
import com.draxer.draxvulkan.compat.EarlyWindowFix;
import com.draxer.draxvulkan.core.BackendInfo;
import com.draxer.draxvulkan.core.CrashGuard;
import com.draxer.draxvulkan.core.FrameStats;
import com.draxer.draxvulkan.core.GraphicsApiOption;
import com.draxer.draxvulkan.core.InternalsDump;
import com.draxer.draxvulkan.VkConfig;
import com.draxer.draxvulkan.gpu.GpuPreference;
import com.draxer.draxvulkan.gpu.GpuProbe;
import com.draxer.draxvulkan.core.QuickRestart;
import com.draxer.draxvulkan.perf.Bench;
import com.draxer.draxvulkan.perf.PipelineWarmup;
import com.draxer.draxvulkan.perf.PresentModeControl;
import com.draxer.draxvulkan.gpu.GpuSelector;
import com.draxer.draxvulkan.hud.VulkanHud;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** /draxvulkan (comando de cliente). */
public final class VulkanCommand {
    private VulkanCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("draxvulkan")
                .executes(VulkanCommand::help)
                .then(Commands.literal("info").executes(VulkanCommand::info))
                .then(Commands.literal("hud").executes(c -> {
                    VulkanHud.setVisible(!VulkanHud.isVisible());
                    return say(c, "HUD " + (VulkanHud.isVisible() ? "§aactivado" : "§cdesactivado"));
                }))
                .then(Commands.literal("gpus").executes(VulkanCommand::gpus))
                .then(Commands.literal("gpu")
                        .then(Commands.literal("auto").executes(VulkanCommand::gpuAuto))
                        .then(Commands.argument("indice", IntegerArgumentType.integer(0))
                                .executes(VulkanCommand::gpuPrefer)
                                .then(Commands.literal("env").executes(c -> gpu(c, false)))
                                .then(Commands.literal("windows").executes(c -> gpu(c, true)))))
                .then(presentNode())
                .then(Commands.literal("backend")
                        .then(Commands.literal("vulkan").executes(c -> backend(c, "vulkan")))
                        .then(Commands.literal("opengl").executes(c -> backend(c, "opengl")))
                        .then(Commands.literal("default").executes(c -> backend(c, "default"))))
                .then(Commands.literal("compat").executes(VulkanCommand::compat)
                        .then(Commands.literal("rescan").executes(c -> {
                            CompatScanner.scanAsync();
                            return say(c, "Escaneando mods en segundo plano... usa /draxvulkan compat en unos segundos.");
                        })))
                .then(Commands.literal("switch")
                        .executes(c -> doSwitch(c, QuickRestart.otherApi()))
                        .then(Commands.literal("vulkan").executes(c -> doSwitch(c, "vulkan")))
                        .then(Commands.literal("opengl").executes(c -> doSwitch(c, "opengl"))))
                .then(Commands.literal("bench")
                        .executes(c -> bench(c, 30))
                        .then(Commands.argument("segundos", IntegerArgumentType.integer(5, 300))
                                .executes(c -> bench(c, IntegerArgumentType.getInteger(c, "segundos")))))
                .then(Commands.literal("dump").executes(VulkanCommand::dump)));
    }

    private static int help(CommandContext<CommandSourceStack> c) {
        say(c, "§cDrax Vulkan " + DraxVulkan.VERSION + "§f — comandos:");
        say(c, "§e/draxvulkan info§7 — backend, GPU, driver, límites y rendimiento");
        say(c, "§e/draxvulkan hud§7 — mostrar/ocultar el HUD (o F8)");
        say(c, "§e/draxvulkan gpus§7 — listar GPU · §e/draxvulkan gpu <n|auto>§7 — elegir GPU (al reiniciar)");
        say(c, "§e/draxvulkan present <smart|mailbox|immediate|fifo|fifo_relaxed|vanilla>§7 — modo de presentación");
        say(c, "§e/draxvulkan switch [vulkan|opengl]§7 — cambiar de API con reinicio rápido (vuelve al mismo mundo)");
        say(c, "§e/draxvulkan bench [segundos]§7 — medir rendimiento y comparar Vulkan vs OpenGL");
        say(c, "§e/draxvulkan backend <vulkan|opengl|default>§7 — cambiar API gráfica (sin reiniciar)");
        say(c, "§e/draxvulkan compat§7 — mods que usan OpenGL directo");
        say(c, "§e/draxvulkan dump§7 — volcar estructura interna (para afinar el mod)");
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        BackendInfo.refresh();
        say(c, "§cBackend: §f" + BackendInfo.backendName() + " §7(opción guardada: " + GraphicsApiOption.readFromFile() + ")");
        for (Map.Entry<String, String> e : BackendInfo.info().entrySet()) {
            if (e.getKey().toLowerCase().contains("extension")) continue;
            say(c, "§7" + e.getKey() + ": §f" + e.getValue());
        }
        Map<String, String> lim = BackendInfo.limits();
        if (!lim.isEmpty()) {
            say(c, "§7Límites: §f" + String.join(", ", lim.entrySet().stream().limit(6).map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        say(c, String.format("§7Rendimiento: §f%.0f FPS§7, 1%% bajos §f%.0f§7, pico §f%.1f ms",
                FrameStats.avgFps(), FrameStats.onePctLowFps(), FrameStats.maxMs()));
        say(c, "§7Present: §f" + PresentModeControl.appliedMode() + " §7(vanilla " + PresentModeControl.vanillaMode()
                + ", soportados " + PresentModeControl.supported() + ")");
        say(c, "§7Pipelines precompilados: §f" + PipelineWarmup.done() + "/" + PipelineWarmup.total());
        say(c, "§7GPU preferida: §f" + GpuPreference.describe());
        say(c, "§7Cierres anómalos seguidos con Vulkan: §f" + CrashGuard.currentCount());
        if (BackendInfo.isVulkan() && EarlyWindowFix.isEarlyWindowOn()) {
            say(c, "§eLa pantalla de carga temprana de NeoForge sigue activa en fml.toml.");
        }
        return 1;
    }

    private static int gpus(CommandContext<CommandSourceStack> c) {
        List<GpuProbe.Gpu> list = GpuProbe.gpus();
        if (list.isEmpty()) {
            return say(c, "§cNo se pudieron enumerar GPU Vulkan." + (GpuProbe.error() != null ? " " + GpuProbe.error() : ""));
        }
        GpuProbe.Gpu active = GpuProbe.activeGpu();
        for (GpuProbe.Gpu g : list) {
            String mark = active != null && active.index() == g.index() ? " §a← en uso" : "";
            say(c, "§e[" + g.index() + "] §f" + g.name() + " §7(" + g.type() + ", " + g.vendorName()
                    + ", " + (g.deviceLocalBytes() >> 20) + " MB, VK " + g.apiVersion() + ")" + mark);
        }
        return say(c, "§7Usa §e/draxvulkan gpu <n>§7 para elegir una (se aplica al reiniciar).");
    }

    private static int gpu(CommandContext<CommandSourceStack> c, boolean windows) {
        int idx = IntegerArgumentType.getInteger(c, "indice");
        List<GpuProbe.Gpu> list = GpuProbe.gpus();
        if (idx >= list.size()) return say(c, "§cNo existe la GPU " + idx + ". Usa /draxvulkan gpus");
        GpuProbe.Gpu g = list.get(idx);
        if (windows) {
            return say(c, "§f" + GpuSelector.applyWindowsPreference(!g.type().equals("integrada")));
        }
        try {
            Path f = GpuSelector.writeLaunchHints(g);
            String env = String.join("\n", GpuSelector.envFor(g));
            Minecraft.getInstance().keyboardHandler.setClipboard(env);
            say(c, "§aGPU elegida: §f" + g.name());
            say(c, "§7Variables copiadas al portapapeles y guardadas en §f" + f.getFileName());
            say(c, "§7Pégalas en las variables de entorno de tu launcher y reinicia.");
            if (GpuSelector.isWindows()) {
                say(c, "§7En Windows también puedes usar §e/draxvulkan gpu " + idx + " windows§7.");
            }
            return 1;
        } catch (Exception e) {
            return say(c, "§cError: " + e.getMessage());
        }
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> presentNode() {
        var node = Commands.literal("present").executes(c -> say(c, "§7Modo actual: §f" + PresentModeControl.appliedMode()
                + " §7· soportados: §f" + PresentModeControl.supported()));
        for (PresentModeControl.Mode m : PresentModeControl.Mode.values()) {
            node.then(Commands.literal(m.name().toLowerCase()).executes(c -> {
                VkConfig.PRESENT_MODE.set(m);
                VkConfig.SPEC.save();
                PresentModeControl.reapply();
                return say(c, "§aModo de presentación: §f" + m.name() + "§7 (se aplica al instante si tu GPU lo soporta)");
            }));
        }
        return node;
    }

    private static int gpuPrefer(CommandContext<CommandSourceStack> c) {
        int idx = IntegerArgumentType.getInteger(c, "indice");
        List<GpuProbe.Gpu> list = GpuProbe.gpus();
        if (idx >= list.size()) return say(c, "§cNo existe la GPU " + idx + ". Usa /draxvulkan gpus");
        GpuProbe.Gpu g = list.get(idx);
        try {
            GpuPreference.save(g);
            return say(c, "§aMinecraft usará §f" + g.name() + "§a desde el próximo inicio. §7(/draxvulkan gpu auto para deshacer)");
        } catch (Exception e) {
            return say(c, "§cError: " + e.getMessage());
        }
    }

    private static int gpuAuto(CommandContext<CommandSourceStack> c) {
        try {
            GpuPreference.save(null);
            return say(c, "§aSelección de GPU automática desde el próximo inicio.");
        } catch (Exception e) {
            return say(c, "§cError: " + e.getMessage());
        }
    }

    private static int doSwitch(CommandContext<CommandSourceStack> c, String api) {
        if (BackendInfo.backendName().toLowerCase().contains(api.equals("opengl") ? "gl" : "vulkan")) {
            return say(c, "§7Ya estás usando " + api + ".");
        }
        say(c, "§aCambiando a §f" + api + "§a: el juego se guarda, se cierra y se vuelve a abrir en este mundo...");
        var err = QuickRestart.switchTo(api);
        return err.map(e -> say(c, "§c" + e)).orElse(1);
    }

    private static int bench(CommandContext<CommandSourceStack> c, int seconds) {
        if (!Bench.start(seconds)) return say(c, "§7Ya hay un benchmark en curso.");
        return say(c, "§aBenchmark de " + seconds + " s iniciado. §7Quédate quieto mirando el mismo lugar; el resultado sale en el chat.");
    }

    private static int backend(CommandContext<CommandSourceStack> c, String value) {
        boolean ok = GraphicsApiOption.set(value);
        return say(c, ok ? "§aAPI gráfica: §f" + value + "§a. Reinicia Minecraft para aplicarla."
                : "§cNo se pudo cambiar la opción. Edita preferredGraphicsBackend en options.txt.");
    }

    private static int compat(CommandContext<CommandSourceStack> c) {
        if (!CompatScanner.isDone()) return say(c, "§7El escaneo aún no termina (o está desactivado). Usa /draxvulkan compat rescan");
        var list = CompatScanner.findings();
        if (list.isEmpty()) return say(c, "§aNingún mod parece llamar a OpenGL directamente.");
        int risky = CompatScanner.suspectCount();
        if (risky > 0) say(c, "§e" + risky + " mod(s) podrían fallar con Vulkan:");
        for (var f : list) if (f.risky()) say(c, "§f" + f.name() + " §7(" + f.modId() + "): " + f.note());
        if (list.size() > risky) say(c, "§7Probablemente OK (rutas alternativas):");
        for (var f : list) if (!f.risky()) say(c, "§8" + f.name() + " (" + f.modId() + "): " + f.note());
        return say(c, "§7Si alguno no dibuja o crashea, actualízalo o usa /draxvulkan backend opengl.");
    }

    private static int dump(CommandContext<CommandSourceStack> c) {
        try {
            Path p = InternalsDump.write();
            return say(c, "§aEstructura interna guardada en §fconfig/draxvulkan/" + p.getFileName());
        } catch (Exception e) {
            return say(c, "§cError: " + e.getMessage());
        }
    }

    private static int say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
