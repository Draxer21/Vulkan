package com.draxer.draxvulkan;

import com.draxer.draxvulkan.command.VulkanCommand;
import com.draxer.draxvulkan.compat.CompatScanner;
import com.draxer.draxvulkan.compat.EarlyWindowFix;
import com.draxer.draxvulkan.core.BackendInfo;
import com.draxer.draxvulkan.core.ClientUi;
import com.draxer.draxvulkan.core.CrashGuard;
import com.draxer.draxvulkan.core.FrameStats;
import com.draxer.draxvulkan.gpu.GpuProbe;
import com.draxer.draxvulkan.hud.VulkanHud;
import com.draxer.draxvulkan.perf.PipelineWarmup;
import com.draxer.draxvulkan.perf.PresentModeControl;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.client.gui.components.Button;
import com.draxer.draxvulkan.core.QuickRestart;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = DraxVulkan.MODID, dist = Dist.CLIENT)
public final class DraxVulkan {
    public static final String MODID = "draxvulkan";
    public static final String VERSION = "0.3.0";
    public static final Logger LOG = LoggerFactory.getLogger("DraxVulkan");

    public static final KeyMapping TOGGLE_HUD = new KeyMapping(
            "key.draxvulkan.toggle_hud",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            KeyMapping.Category.MISC);

    private static boolean welcomeShown = false;

    public DraxVulkan(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, VkConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Lo más temprano posible: revisar si la sesión anterior murió con Vulkan.
        CrashGuard.onModConstruct();

        modBus.addListener(this::onClientSetup);
        modBus.addListener(this::onRegisterKeys);
        modBus.addListener(this::onRegisterLayers);
        modBus.addListener(this::onConfigLoad);
        modBus.addListener(this::onConfigReload);

        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onFrame);
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onShutdown);
        NeoForge.EVENT_BUS.addListener(this::onScreenInit);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            BackendInfo.refresh();
            LOG.info("Backend gráfico activo: {} | GPU: {}", BackendInfo.backendName(), BackendInfo.gpuName());
            CrashGuard.onBackendKnown(BackendInfo.isVulkan());
            if (BackendInfo.isVulkan() && VkConfig.AUTO_FIX_EARLY_WINDOW.get()) {
                EarlyWindowFix.applyIfNeeded();
            }
            VulkanHud.setVisible(VkConfig.HUD_ON_START.get());
            GpuProbe.initAsync();
        });
        if (VkConfig.SCAN_ON_STARTUP.get()) {
            CompatScanner.scanAsync();
        }
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == VkConfig.SPEC) PresentModeControl.reapply();
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == VkConfig.SPEC) PresentModeControl.reapply();
    }

    private void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_HUD);
    }

    private void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath(MODID, "vulkan_hud"), new VulkanHud());
    }

    private void onClientTick(ClientTickEvent.Post event) {
        while (TOGGLE_HUD.consumeClick()) {
            VulkanHud.setVisible(!VulkanHud.isVisible());
        }
        GpuProbe.setPolling(VulkanHud.isVisible());
        PipelineWarmup.tick();
    }

    private void onFrame(RenderFrameEvent.Pre event) {
        FrameStats.onFrame();
    }

    private void onCommands(RegisterClientCommandsEvent event) {
        VulkanCommand.register(event.getDispatcher());
    }

    private void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (welcomeShown) return;
        welcomeShown = true;
        CrashGuard.consumePendingNotice().ifPresent(msg -> ClientUi.chat(Component.literal("§6[Drax Vulkan] §f" + msg)));
        if (VkConfig.WARN_IN_CHAT.get()) {
            int n = CompatScanner.suspectCount();
            if (BackendInfo.isVulkan() && n > 0) {
                ClientUi.chat(Component.literal(
                        "§6[Drax Vulkan] §e" + n + " mod(s) usan OpenGL directo y pueden fallar con Vulkan. §7Usa /draxvulkan compat"));
            }
        }
    }

    /** Botón "Cambiar a OpenGL/Vulkan" en el menú de Video. */
    private void onScreenInit(ScreenEvent.Init.Post event) {
        if (!event.getScreen().getClass().getSimpleName().contains("VideoSettings")) return;
        try {
            if (!VkConfig.QUICK_RESTART_BUTTON.get()) return;
        } catch (Throwable notLoaded) {
            return;
        }
        String target = QuickRestart.otherApi();
        String label = "§e⟳ §fCambiar a " + (target.equals("vulkan") ? "Vulkan" : "OpenGL");
        int w = 130;
        Button button = Button.builder(Component.literal(label), b -> {
                    var err = QuickRestart.switchTo(target);
                    err.ifPresent(e -> {
                        b.setMessage(Component.literal("§cNo se pudo, mira el log"));
                        LOG.warn("Reinicio rápido: {}", e);
                    });
                })
                .bounds(event.getScreen().width - w - 4, 4, w, 20)
                .build();
        event.addListener(button);
    }

    private void onShutdown(GameShuttingDownEvent event) {
        CrashGuard.onCleanShutdown();
        GpuProbe.shutdown();
    }
}
