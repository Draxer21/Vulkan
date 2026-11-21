package com.draxer.draxvulkan.core;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Accesos a la UI del cliente que Mojang renombra seguido entre versiones 26.x.
 * Se resuelven por reflexión para que el mod compile y funcione aunque cambien los nombres.
 */
public final class ClientUi {
    private ClientUi() {}

    /** Muestra un mensaje en el chat del jugador local. */
    public static void chat(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        Object player = mc.player;
        if (player != null) {
            if (invoke(player, "displayClientMessage", new Class<?>[]{Component.class, boolean.class}, msg, false)) return;
            if (invoke(player, "sendSystemMessage", new Class<?>[]{Component.class}, msg)) return;
        }
        Object gui = mc.gui;
        for (String getter : new String[]{"getChat", "chat", "getChatComponent"}) {
            try {
                Method m = gui.getClass().getMethod(getter);
                Object chat = m.invoke(gui);
                if (chat != null && invoke(chat, "addMessage", new Class<?>[]{Component.class}, msg)) return;
            } catch (Throwable ignored) {}
        }
    }

    /** true si el jugador ocultó la interfaz con F1. */
    public static boolean isGuiHidden() {
        Minecraft mc = Minecraft.getInstance();
        for (Object holder : new Object[]{mc.options, mc.gui, mc}) {
            if (holder == null) continue;
            for (String name : new String[]{"hideGui", "hidden", "guiHidden"}) {
                try {
                    Field f = holder.getClass().getField(name);
                    if (f.getType() == boolean.class) return f.getBoolean(holder);
                } catch (Throwable ignored) {}
            }
            for (String name : new String[]{"isGuiHidden", "isHidden", "hideGui"}) {
                try {
                    Method m = holder.getClass().getMethod(name);
                    if (m.getReturnType() == boolean.class) return (boolean) m.invoke(holder);
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static Method focusMethod;
    private static boolean focusLookupDone = false;

    /** true si la ventana del juego tiene el foco (si no se puede saber, true). */
    public static boolean isWindowFocused() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (!focusLookupDone) {
                focusLookupDone = true;
                for (String n : new String[]{"isWindowActive", "isFocused"}) {
                    try { focusMethod = mc.getClass().getMethod(n); break; } catch (NoSuchMethodException ignored) {}
                }
            }
            if (focusMethod != null) return (boolean) focusMethod.invoke(mc);
            return mc.getWindow().isFocused();
        } catch (Throwable t) {
            return true;
        }
    }

    /** true mientras hay una pantalla de carga (recarga de recursos). */
    public static boolean isLoadingOverlay() {
        Minecraft mc = Minecraft.getInstance();
        for (String name : new String[]{"getOverlay", "overlay", "getLoadingOverlay"}) {
            try {
                Method m = mc.getClass().getMethod(name);
                return m.invoke(mc) != null;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return false;
            }
        }
        for (Class<?> c = mc.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals("overlay") || f.getType().getSimpleName().equals("Overlay")) {
                    try {
                        f.setAccessible(true);
                        return f.get(mc) != null;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return false;
    }

    private static boolean invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(name, types);
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
