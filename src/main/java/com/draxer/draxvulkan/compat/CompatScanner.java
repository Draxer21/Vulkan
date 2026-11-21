package com.draxer.draxvulkan.compat;

import com.draxer.draxvulkan.DraxVulkan;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Busca mods que llaman a OpenGL directamente (org/lwjgl/opengl/GL*) en vez de pasar por Blaze3D.
 * Con el backend Vulkan no existe contexto OpenGL, así que esas llamadas no dibujan nada o crashean.
 */
public final class CompatScanner {
    /** risky=false: solo rutas OpenGL alternativas (el mod sabe distinguir el backend), probablemente OK. */
    public record Finding(String modId, String name, int glClasses, String note, boolean risky) {}

    /** Mods con soporte confirmado para Vulkan desde cierta versión. */
    private static final Map<String, String> VULKAN_READY = Map.of(
            "sodium", "0.9.0");

    /** Si una clase que usa GL también menciona algo de esto, es una ruta alternativa consciente del backend. */
    private static final byte[][] BACKEND_AWARE = {
            "vulkan".getBytes(StandardCharsets.US_ASCII),
            "Vulkan".getBytes(StandardCharsets.US_ASCII),
            "com/mojang/blaze3d/opengl/".getBytes(StandardCharsets.US_ASCII),
            "PreferredGraphicsApi".getBytes(StandardCharsets.US_ASCII),
            "backendName".getBytes(StandardCharsets.US_ASCII),
            "GlDevice".getBytes(StandardCharsets.US_ASCII),
    };

    /** Mods con problemas conocidos bajo Vulkan (26.2). */
    private static final Map<String, String> KNOWN = Map.of(
            "voxy", "Renderiza en OpenGL directo: no dibuja nada con Vulkan.",
            "distanthorizons", "Necesita una versión con motor Blaze3D para funcionar con Vulkan.",
            "immediatelyfast", "Optimiza rutas de OpenGL: revisa que tu versión soporte Vulkan.",
            "oculus", "Port antiguo de shaders basado en OpenGL.");

    private static final Set<String> SKIP = Set.of("minecraft", "neoforge", DraxVulkan.MODID);
    private static final byte[] NEEDLE = "org/lwjgl/opengl/GL".getBytes(StandardCharsets.US_ASCII);

    private static final List<Finding> FINDINGS = Collections.synchronizedList(new ArrayList<>());
    private static volatile boolean done = false;

    private CompatScanner() {}

    public static void scanAsync() {
        Thread t = new Thread(CompatScanner::scan, "DraxVulkan-CompatScan");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public static void scan() {
        FINDINGS.clear();
        Map<Path, IModInfo> files = new ConcurrentHashMap<>();
        for (IModInfo info : ModList.get().getMods()) {
            if (SKIP.contains(info.getModId())) continue;
            Path p = pathOf(info);
            if (KNOWN.containsKey(info.getModId())) {
                FINDINGS.add(new Finding(info.getModId(), info.getDisplayName(), -1, KNOWN.get(info.getModId()), true));
            }
            if (p != null) files.putIfAbsent(p, info);
        }
        for (var e : files.entrySet()) {
            IModInfo info = e.getValue();
            if (KNOWN.containsKey(info.getModId())) continue;
            if (isVulkanReady(info)) continue;
            int[] hits = countGlClasses(e.getKey());
            int hard = hits[0], soft = hits[1];
            if (hard > 0) {
                FINDINGS.add(new Finding(info.getModId(), info.getDisplayName(), hard + soft,
                        hard + " clase(s) llaman a OpenGL directo" + (soft > 0 ? " (+" + soft + " rutas alternativas)" : ""), true));
            } else if (soft > 0) {
                FINDINGS.add(new Finding(info.getModId(), info.getDisplayName(), soft,
                        soft + " ruta(s) OpenGL alternativas; detecta el backend, probablemente OK", false));
            }
        }
        done = true;
        DraxVulkan.LOG.info("CompatScanner: {} mod(s) sospechosos", FINDINGS.size());
        for (Finding f : FINDINGS) DraxVulkan.LOG.info("  {} ({}): {}", f.name(), f.modId(), f.note());
    }

    private static Path pathOf(IModInfo info) {
        try {
            Object modFile = info.getOwningFile().getFile();
            for (String m : new String[]{"getFilePath", "getPath"}) {
                try {
                    Method method = modFile.getClass().getMethod(m);
                    Object r = method.invoke(modFile);
                    if (r instanceof Path p) return p;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable t) {
            DraxVulkan.LOG.debug("No se pudo obtener la ruta de {}", info.getModId(), t);
        }
        return null;
    }

    private static boolean isVulkanReady(IModInfo info) {
        String min = VULKAN_READY.get(info.getModId());
        if (min == null) return false;
        try {
            return info.getVersion().compareTo(new org.apache.maven.artifact.versioning.DefaultArtifactVersion(min)) >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return {clases con GL "duro", clases con GL pero conscientes del backend} */
    private static int[] countGlClasses(Path path) {
        int[] hits = new int[2];
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> s = Files.walk(path)) {
                    for (Path c : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                        classify(Files.readAllBytes(c), hits);
                    }
                }
            } else if (Files.isRegularFile(path)) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    Enumeration<? extends ZipEntry> en = zip.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry z = en.nextElement();
                        if (!z.getName().endsWith(".class") || z.getSize() > 4_000_000) continue;
                        // Las librerías incluidas (jar-in-jar) no se cuentan aquí.
                        try (var in = zip.getInputStream(z)) {
                            classify(in.readAllBytes(), hits);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            DraxVulkan.LOG.debug("Fallo escaneando {}", path, t);
        }
        return hits;
    }

    private static void classify(byte[] data, int[] hits) {
        if (!contains(data, NEEDLE)) return;
        for (byte[] marker : BACKEND_AWARE) {
            if (contains(data, marker)) {
                hits[1]++;
                return;
            }
        }
        hits[0]++;
    }

    private static boolean contains(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    public static boolean isDone() { return done; }
    /** Solo cuenta los que tienen riesgo real. */
    public static int suspectCount() {
        synchronized (FINDINGS) { return (int) FINDINGS.stream().filter(Finding::risky).count(); }
    }
    public static List<Finding> findings() { synchronized (FINDINGS) { return new ArrayList<>(FINDINGS); } }
}
