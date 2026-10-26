package com.gabdab.weboptimizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Simple draggable HUD. Mouse and key state are polled through GLFW,
 * so the screen does not depend on the (frequently changing) input method signatures.
 */
public class WebScreen extends Screen {
    private static final int W = 220;
    private static final int H = 124;
    private static final int TITLE_H = 18;

    private boolean wasDown;
    private boolean shiftWasDown = true; // Right Shift is still held when the screen opens
    private boolean closeRequested = false;

    private boolean dragging = false;
    private int dragOffX, dragOffY;
    private boolean sliderDragging = false;

    public WebScreen() {
        super(Text.literal("WebOptimizer"));
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        wasDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        if (closeRequested) {
            closeRequested = false;
            this.close();
        }
    }

    @Override
    public void removed() {
        WebConfig.save();
    }

    // ------------------------------------------------------------------ input (polled)

    private void pollInput(int mouseX, int mouseY) {
        long window = MinecraftClient.getInstance().getWindow().getHandle();

        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down && !wasDown) onPress(mouseX, mouseY);
        if (down) onDrag(mouseX, mouseY);
        else {
            dragging = false;
            sliderDragging = false;
        }
        wasDown = down;

        boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (shift && !shiftWasDown) closeRequested = true;
        shiftWasDown = shift;
    }

    private void onPress(int mx, int my) {
        WebConfig c = WebConfig.INSTANCE;
        int x = c.hudX, y = c.hudY;

        if (inside(mx, my, x, y, W, TITLE_H)) {
            dragging = true;
            dragOffX = mx - x;
            dragOffY = my - y;
        } else if (inside(mx, my, x + 8, y + 26, W - 16, 18)) {
            c.enabled = !c.enabled;
            WebConfig.save();
        } else if (inside(mx, my, x + 8, y + 56, W - 16, 18)) {
            sliderDragging = true;
            updateSlider(mx);
        } else if (inside(mx, my, x + 8, y + 80, W - 16, 18)) {
            c.returnSword = !c.returnSword;
            WebConfig.save();
        }
    }

    private void onDrag(int mx, int my) {
        WebConfig c = WebConfig.INSTANCE;
        if (dragging) {
            c.hudX = clamp(mx - dragOffX, 0, Math.max(0, this.width - W));
            c.hudY = clamp(my - dragOffY, 0, Math.max(0, this.height - H));
        }
        if (sliderDragging) updateSlider(mx);
    }

    private void updateSlider(int mx) {
        WebConfig c = WebConfig.INSTANCE;
        int trackX = c.hudX + 8;
        int trackW = W - 16;
        double t = (mx - trackX) / (double) trackW;
        c.smoothness = clamp((int) Math.round(t * 1000.0), 0, 1000);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        pollInput(mouseX, mouseY);

        WebConfig c = WebConfig.INSTANCE;
        int x = c.hudX, y = c.hudY;

        // panel + title bar
        ctx.fill(x - 1, y - 1, x + W + 1, y + H + 1, 0xFF5BC0EB);
        ctx.fill(x, y, x + W, y + H, 0xF0141420);
        ctx.fill(x, y, x + W, y + TITLE_H, 0xFF22223A);
        drawCentered(ctx, "WebOptimizer", x + W / 2, y + 5, 0xFFFFFFFF);

        // ON / OFF button
        boolean hoverToggle = inside(mouseX, mouseY, x + 8, y + 26, W - 16, 18);
        int toggleColor = c.enabled ? (hoverToggle ? 0xFF3FA044 : 0xFF2E7D32) : (hoverToggle ? 0xFFD32F2F : 0xFFB71C1C);
        ctx.fill(x + 8, y + 26, x + W - 8, y + 44, toggleColor);
        drawCentered(ctx, c.enabled ? "ON" : "OFF", x + W / 2, y + 31, 0xFFFFFFFF);

        // smoothness slider
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Плавність: " + c.smoothness + " / 1000"), x + 8, y + 48, 0xFFDDDDDD);
        int trackX = x + 8, trackY = y + 62, trackW = W - 16;
        ctx.fill(trackX, trackY, trackX + trackW, trackY + 6, 0xFF2A2A40);
        int filled = (int) Math.round(trackW * (c.smoothness / 1000.0));
        ctx.fill(trackX, trackY, trackX + filled, trackY + 6, 0xFF5BC0EB);
        int knobX = clamp(trackX + filled - 2, trackX, trackX + trackW - 4);
        ctx.fill(knobX, trackY - 3, knobX + 4, trackY + 9, 0xFFFFFFFF);

        // return sword button
        boolean hoverSword = inside(mouseX, mouseY, x + 8, y + 80, W - 16, 18);
        int swordColor = c.returnSword ? (hoverSword ? 0xFF3FA044 : 0xFF2E7D32) : (hoverSword ? 0xFF3A3A55 : 0xFF2A2A40);
        ctx.fill(x + 8, y + 80, x + W - 8, y + 98, swordColor);
        drawCentered(ctx, "Повертати меч: " + (c.returnSword ? "ON" : "OFF"), x + W / 2, y + 85, 0xFFFFFFFF);

        // footer
        drawCentered(ctx, "By:GAB_DAB2025", x + W / 2, y + H - 14, 0xFF9AA0B5);
    }

    private void drawCentered(DrawContext ctx, String text, int cx, int y, int color) {
        int w = this.textRenderer.getWidth(text);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(text), cx - w / 2, y, color);
    }
}
