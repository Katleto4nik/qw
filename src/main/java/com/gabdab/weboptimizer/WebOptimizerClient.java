package com.gabdab.weboptimizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import org.lwjgl.glfw.GLFW;

public class WebOptimizerClient implements ClientModInitializer {
    private boolean menuKeyWasDown = false;

    @Override
    public void onInitializeClient() {
        WebConfig.load();

        // the whole decision + placing runs at the START of the tick (before input) -> fastest possible
        ClientTickEvents.START_CLIENT_TICK.register(WebLogic::tick);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            WebLogic.afterTick(client);
            handleMenuKey(client);
        });

        // remember when the player hits something (0.2 s pause before the next web)
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() && player == MinecraftClient.getInstance().player) {
                WebLogic.markAttack();
            }
            return ActionResult.PASS;
        });
    }

    /** Right Shift opens the HUD (and closes it again). */
    private void handleMenuKey(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        boolean down = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (down && !menuKeyWasDown && client.currentScreen == null && client.player != null) {
            client.setScreen(new WebScreen());
        }
        menuKeyWasDown = down;
    }
}
