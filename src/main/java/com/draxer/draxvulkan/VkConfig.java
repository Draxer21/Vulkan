package com.draxer.draxvulkan;

import com.draxer.draxvulkan.perf.PresentModeControl;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class VkConfig {
    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // --- HUD ---
    public static final ModConfigSpec.BooleanValue HUD_ON_START;
    public static final ModConfigSpec.EnumValue<Corner> HUD_CORNER;
    public static final ModConfigSpec.BooleanValue HUD_GRAPH;
    public static final ModConfigSpec.IntValue VRAM_POLL_MS;

    // --- Rendimiento ---
    public static final ModConfigSpec.EnumValue<PresentModeControl.Mode> PRESENT_MODE;
    public static final ModConfigSpec.BooleanValue PRECOMPILE_PIPELINES;
    public static final ModConfigSpec.IntValue PRECOMPILE_PER_TICK;

    // --- Reinicio rápido ---
    public static final ModConfigSpec.BooleanValue QUICK_RESTART_BUTTON;
    public static final ModConfigSpec.BooleanValue QUICK_RESTART_REJOIN;

    // --- Protección contra crashes ---
    public static final ModConfigSpec.BooleanValue CRASH_GUARD;
    public static final ModConfigSpec.IntValue CRASH_THRESHOLD;

    // --- Compatibilidad ---
    public static final ModConfigSpec.BooleanValue SCAN_ON_STARTUP;
    public static final ModConfigSpec.BooleanValue WARN_IN_CHAT;
    public static final ModConfigSpec.BooleanValue AUTO_FIX_EARLY_WINDOW;

    static {
        B.push("hud");
        HUD_ON_START = B.comment("Mostrar el HUD de Vulkan al iniciar (F8 lo alterna)").define("showOnStart", false);
        HUD_CORNER = B.comment("Esquina de la pantalla").defineEnum("corner", Corner.TOP_RIGHT);
        HUD_GRAPH = B.comment("Dibujar el gráfico de tiempos de frame").define("frameGraph", true);
        VRAM_POLL_MS = B.comment("Cada cuántos ms se consulta el uso de VRAM").defineInRange("vramPollMs", 1000, 250, 10000);
        B.pop();

        B.push("performance");
        PRESENT_MODE = B.comment("Modo de presentación. Vulkan SMART: MAILBOX con vsync apagado (sin tearing), FIFO_RELAXED con vsync encendido (sin caídas a 30 FPS). OpenGL SMART: vsync adaptativo cuando el vsync está encendido. VANILLA: no tocar.")
                .defineEnum("presentMode", PresentModeControl.Mode.SMART);
        PRECOMPILE_PIPELINES = B.comment("(Vulkan y OpenGL) Precompilar los pipelines de render en segundo plano para evitar tirones la primera vez que algo aparece").define("precompilePipelines", true);
        PRECOMPILE_PER_TICK = B.comment("Pipelines a precompilar por tick (máximo ~4 ms por tick)").defineInRange("precompilePerTick", 3, 1, 32);
        B.pop();

        B.push("quickRestart");
        QUICK_RESTART_BUTTON = B.comment("Mostrar el botón de cambio de API en el menú de Video").define("videoButton", true);
        QUICK_RESTART_REJOIN = B.comment("Al reiniciar, volver automáticamente al mismo mundo o servidor (Quick Play)").define("rejoin", true);
        B.pop();

        B.push("crashGuard");
        CRASH_GUARD = B.comment("Si el juego se cierra mal varias veces seguidas usando Vulkan, cambiar a OpenGL automáticamente").define("enabled", true);
        CRASH_THRESHOLD = B.comment("Cierres anómalos seguidos antes de volver a OpenGL").defineInRange("threshold", 2, 1, 10);
        B.pop();

        B.push("compat");
        SCAN_ON_STARTUP = B.comment("Escanear los mods instalados buscando llamadas directas a OpenGL").define("scanOnStartup", true);
        WARN_IN_CHAT = B.comment("Avisar en el chat al entrar a un mundo si hay mods sospechosos").define("warnInChat", true);
        AUTO_FIX_EARLY_WINDOW = B.comment("Desactivar la pantalla de carga temprana de NeoForge (fml.toml) si se usa Vulkan, ya que puede congelar o romper el arranque").define("autoFixEarlyWindow", true);
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private VkConfig() {}
}
