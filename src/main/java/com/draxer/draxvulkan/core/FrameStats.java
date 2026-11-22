package com.draxer.draxvulkan.core;

import java.util.Arrays;

/** Anillo de tiempos de frame para promedio, 1% bajos y pico. */
public final class FrameStats {
    public static final int SIZE = 1000;
    private static final float[] MS = new float[SIZE];
    private static int head = 0;
    private static int count = 0;
    private static long last = 0;

    private static long lastCalc = 0;
    private static float avgMs, onePctLowFps, maxMs;

    private FrameStats() {}

    public static void onFrame() {
        long now = System.nanoTime();
        // Sin foco Minecraft limita los FPS a propósito: esos frames no cuentan.
        if (!ClientUi.isWindowFocused()) {
            last = 0;
            com.draxer.draxvulkan.perf.Bench.abort("la ventana perdió el foco");
            return;
        }
        if (last != 0) {
            float ms = (now - last) / 1_000_000f;
            if (ms < 5000f) { // ignora pausas enormes (pantallas de carga, alt-tab)
                com.draxer.draxvulkan.perf.Bench.onFrame(ms);
                MS[head] = ms;
                head = (head + 1) % SIZE;
                if (count < SIZE) count++;
            }
        }
        last = now;
        if (now - lastCalc > 500_000_000L) {
            lastCalc = now;
            recalc();
        }
    }

    private static void recalc() {
        if (count == 0) return;
        float[] copy = new float[count];
        float sum = 0, max = 0;
        for (int i = 0; i < count; i++) {
            float v = MS[i];
            copy[i] = v;
            sum += v;
            if (v > max) max = v;
        }
        Arrays.sort(copy);
        int n = Math.max(1, count / 100);
        float worst = 0;
        for (int i = count - n; i < count; i++) worst += copy[i];
        worst /= n;
        avgMs = sum / count;
        maxMs = max;
        onePctLowFps = worst > 0 ? 1000f / worst : 0;
    }

    public static float avgMs() { return avgMs; }
    public static float avgFps() { return avgMs > 0 ? 1000f / avgMs : 0; }
    public static float onePctLowFps() { return onePctLowFps; }
    public static float maxMs() { return maxMs; }

    /** Últimos n tiempos en orden cronológico. */
    public static float[] recent(int n) {
        n = Math.min(n, count);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int idx = (head - n + i + SIZE) % SIZE;
            out[i] = MS[idx];
        }
        return out;
    }
}
