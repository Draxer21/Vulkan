package com.draxer.draxvulkan.hud;

import com.draxer.draxvulkan.DraxVulkan;
import com.draxer.draxvulkan.VkConfig;
import com.draxer.draxvulkan.compat.CompatScanner;
import com.draxer.draxvulkan.core.BackendInfo;
import com.draxer.draxvulkan.core.ClientUi;
import com.draxer.draxvulkan.core.FrameStats;
import com.draxer.draxvulkan.gpu.GpuProbe;
import com.draxer.draxvulkan.perf.PipelineWarmup;
import com.draxer.draxvulkan.perf.PresentModeControl;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.gui.GuiLayer;

import java.util.ArrayList;
import java.util.List;

/** HUD de diagnóstico (F8). */
public final class VulkanHud implements GuiLayer {
    private static boolean visible = false;

    private static final int BG = 0xA0101018;
    private static final int TITLE = 0xFFE0463A;   // rojo Vulkan
    private static final int LABEL = 0xFFAAAAAA;
    private static final int VALUE = 0xFFFFFFFF;
    private static final int GOOD = 0xFF55FF55;
    private static final int WARN = 0xFFFFD84A;
    private static final int BAD = 0xFFFF5555;

    public static boolean isVisible() { return visible; }
    public static void setVisible(boolean v) { visible = v; }

    private record Line(String label, String value, int color) {}

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();
        if (ClientUi.isGuiHidden()) return;
        Font font = mc.font;

        List<Line> lines = new ArrayList<>();
        boolean vk = BackendInfo.isVulkan();
        lines.add(new Line("Backend", BackendInfo.backendName(), vk ? GOOD : WARN));
        lines.add(new Line("GPU", BackendInfo.gpuName(), VALUE));

        GpuProbe.Gpu gpu = GpuProbe.activeGpuIfReady();
        if (gpu != null) {
            lines.add(new Line("Tipo", gpu.type() + " · " + gpu.vendorName() + " · VK " + gpu.apiVersion(),
                    gpu.type().equals("integrada") ? WARN : VALUE));
            lines.add(new Line("Driver", gpu.driverName() + " " + gpu.driverInfo(), VALUE));
        } else {
            lines.add(new Line("Driver", BackendInfo.driver(), VALUE));
            String err = GpuProbe.error();
            lines.add(err != null ? new Line("Sonda", "error: " + err, BAD) : new Line("Sonda", "iniciando...", LABEL));
        }

        float fps = FrameStats.avgFps();
        float low = FrameStats.onePctLowFps();
        lines.add(new Line("FPS", String.format("%.0f  (1%% bajos %.0f)", fps, low), fpsColor(fps)));
        lines.add(new Line("Frame", String.format("%.2f ms  · pico %.1f ms", FrameStats.avgMs(), FrameStats.maxMs()),
                FrameStats.maxMs() > 50 ? BAD : FrameStats.maxMs() > 25 ? WARN : VALUE));

        GpuProbe.Vram vram = GpuProbe.vram();
        if (vram != null && vram.budgetBytes() > 0) {
            double pct = 100.0 * vram.usedBytes() / vram.budgetBytes();
            lines.add(new Line("VRAM", String.format("%s / %s (%.0f%%)", mb(vram.usedBytes()), mb(vram.budgetBytes()), pct),
                    pct > 90 ? BAD : pct > 75 ? WARN : VALUE));
        } else if (gpu != null) {
            lines.add(new Line("VRAM", mb(gpu.deviceLocalBytes()) + " total (sin VK_EXT_memory_budget)", LABEL));
        }

        if (vk) {
            String applied = PresentModeControl.appliedMode();
            String vanilla = PresentModeControl.vanillaMode();
            lines.add(new Line("Present", applied.equals(vanilla) ? applied : applied + " §7(vanilla " + vanilla + ")",
                    applied.equals(vanilla) ? VALUE : GOOD));
            if (PipelineWarmup.total() > 0) {
                lines.add(new Line("Pipelines", PipelineWarmup.done() + "/" + PipelineWarmup.total()
                        + (PipelineWarmup.running() ? " precompilando..." : " listos"),
                        PipelineWarmup.running() ? WARN : GOOD));
            }
        }

        if (CompatScanner.isDone() && vk && CompatScanner.suspectCount() > 0) {
            lines.add(new Line("Compat", CompatScanner.suspectCount() + " mod(s) en riesgo · /draxvulkan compat", WARN));
        }

        // --- medidas ---
        int pad = 4, lh = font.lineHeight + 1;
        String title = "Drax Vulkan " + DraxVulkan.VERSION;
        int w = font.width(title);
        for (Line l : lines) w = Math.max(w, font.width(l.label() + ": ") + font.width(l.value()));
        boolean graph = VkConfig.HUD_GRAPH.get();
        int graphH = graph ? 30 : 0;
        w = Math.max(w, 150);
        int h = lh * (lines.size() + 1) + pad * 2 + (graph ? graphH + 3 : 0);

        int sw = g.guiWidth(), sh = g.guiHeight();
        int x, y;
        switch (VkConfig.HUD_CORNER.get()) {
            case TOP_LEFT -> { x = 2; y = 2; }
            case BOTTOM_LEFT -> { x = 2; y = sh - h - 2; }
            case BOTTOM_RIGHT -> { x = sw - w - pad * 2 - 2; y = sh - h - 2; }
            default -> { x = sw - w - pad * 2 - 2; y = 2; }
        }

        g.fill(x, y, x + w + pad * 2, y + h, BG);
        int cy = y + pad;
        g.text(font, title, x + pad, cy, TITLE, true);
        cy += lh;
        for (Line l : lines) {
            String lab = l.label() + ": ";
            g.text(font, lab, x + pad, cy, LABEL, false);
            g.text(font, l.value(), x + pad + font.width(lab), cy, l.color(), false);
            cy += lh;
        }

        if (graph) {
            cy += 2;
            int gx = x + pad, gw = w;
            float[] ms = FrameStats.recent(Math.min(gw, FrameStats.SIZE));
            // Escala adaptable: se ajusta al peor frame reciente (mínimo 5 ms, máximo 50 ms).
            float peak = 0;
            for (float v : ms) peak = Math.max(peak, v);
            float scale = Math.max(5f, Math.min(50f, peak * 1.25f));
            int base = cy + graphH;
            for (float guide : new float[]{16.6f, 33.3f}) {
                if (guide < scale) {
                    int gy = base - (int) (graphH * guide / scale);
                    g.fill(gx, gy, gx + gw, gy + 1, guide < 20 ? 0x4055FF55 : 0x40FFD84A);
                }
            }
            int start = gw - ms.length;
            for (int i = 0; i < ms.length; i++) {
                float v = Math.min(ms[i], scale);
                int bh = Math.max(1, (int) (graphH * v / scale));
                int col = ms[i] > 33.3f ? 0xC0FF5555 : ms[i] > 16.7f ? 0xC0FFD84A : 0xC055FF55;
                int px = gx + start + i;
                g.fill(px, base - bh, px + 1, base, col);
            }
            g.text(font, String.format("%.0f ms", scale), gx + gw - font.width(String.format("%.0f ms", scale)), cy, LABEL, false);
        }
    }

    private static int fpsColor(float fps) {
        return fps >= 60 ? GOOD : fps >= 30 ? WARN : BAD;
    }

    private static String mb(long bytes) {
        if (bytes >= (1L << 30)) return String.format("%.1f GB", bytes / (double) (1L << 30));
        return (bytes >> 20) + " MB";
    }
}
