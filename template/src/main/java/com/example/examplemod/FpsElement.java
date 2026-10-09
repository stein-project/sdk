package com.example.examplemod;

import dev.xavier.stein.loader.api.HudElement;
import dev.xavier.stein.loader.api.HudPlacement;
import dev.xavier.stein.loader.api.Render;

import net.minecraft.client.Minecraft;

/**
 * A HUD element: the Loader keeps its place, scale and on/off state on the HUD grid (Right Shift
 * opens the editor), and calls {@link #layout} then {@link #draw} each frame. The mod only says how
 * big it is and draws at (0, 0) with {@link Render}. New elements start turned off — the player
 * turns them on in the editor.
 */
final class FpsElement implements HudElement {

    private static final int PREVIEW_FPS = 144; // shown in the HUD editor
    private static final int COLOR = 0xFFFFAA00; // ARGB

    private String text = "";
    private int width;

    @Override
    public String id() {
        return ExampleMod.ID + ".fps"; // unique among all mods: "<mod>.<name>"
    }

    @Override
    public String name() {
        return "Example FPS";
    }

    @Override
    public boolean layout(boolean preview, float partialTicks) {
        text = (preview ? PREVIEW_FPS : Minecraft.getDebugFPS()) + " FPS";
        width = Render.textWidth(text);
        return true;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return Render.fontHeight();
    }

    @Override
    public void draw(boolean alignRight, float partialTicks) {
        Render.text(text, 0, 0, COLOR, true);
    }

    @Override
    public HudPlacement defaultPlacement() {
        // Top-left corner, 4 pixels in, normal scale.
        return new HudPlacement(0, 0, 4, 4, 1.0F, false, false);
    }
}
