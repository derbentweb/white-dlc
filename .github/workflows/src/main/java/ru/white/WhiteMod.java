package ru.white;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.glfw.GLFW;
import ru.white.config.ConfigManager;
import ru.white.gui.ClickGui;
import ru.white.keybind.KeybindManager;
import ru.white.manager.rotation.RotationManager;
import ru.white.module.Module;
import ru.white.module.ModuleManager;

public class WhiteMod implements ClientModInitializer {

    public static ModuleManager MODULES;
    public static ConfigManager CONFIG;
    public static KeybindManager KEYBINDS;

    @Override
    public void onInitializeClient() {
        // Регистрирует собственный тик менеджера ротации раньше тика модулей
        RotationManager.get().init();

        MODULES = new ModuleManager();
        MODULES.init();

        CONFIG = new ConfigManager(MODULES);
        CONFIG.load();

        KEYBINDS = new KeybindManager();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (Module m : MODULES.getModules()) m.onTick();

            if (client.getWindow() == null) return;
            long handle = client.getWindow().getHandle();
            boolean pressed = GLFW.glfwGetKey(handle, KEYBINDS.getOpenKey()) == GLFW.GLFW_PRESS;
            if (KEYBINDS.pollEdge(pressed) && client.currentScreen == null) {
                client.setScreen(new ClickGui());
            }
        });
    }
}
