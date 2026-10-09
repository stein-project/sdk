package com.example.examplemod;

import dev.xavier.stein.loader.api.Hud;
import dev.xavier.stein.loader.api.Keys;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Page;
import dev.xavier.stein.loader.api.SteinMod;
import dev.xavier.stein.loader.api.WorldRender;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.input.Keyboard;

/**
 * An example Stein Loader mod. It shows the main pieces of the API:
 *
 * <ul>
 *   <li>a key binding, listed in the game's Controls screen ({@link Keys});
 *   <li>drawing in the world after it is rendered ({@link #onRenderWorld});
 *   <li>a client-side command that never reaches the server ({@link #onSendChat});
 *   <li>an element on the HUD grid that players can move and resize ({@link FpsElement});
 *   <li>options on the in-game Panel (Right Shift) and a config screen of its own ({@link
 *       ExampleScreen}).
 * </ul>
 *
 * <p>Everything here runs on the game thread. Game types arrive as {@code Object} in the API: cast
 * them to the Minecraft classes you compile against.
 */
public final class ExampleMod implements SteinMod {

    static final String ID = "examplemod";

    /** Orange, opaque: colors are ARGB (0xAARRGGBB). */
    private static final int OUTLINE_COLOR = 0xFFFFAA00;

    /**
     * Draw an outline around the block you look at. Toggled by the key, the Panel option and the
     * config screen.
     */
    static boolean outline = true;

    private final KeyBinding toggleKey =
            new KeyBinding("key.examplemod.outline", Keyboard.KEY_O, "Example Mod");

    @Override
    public void afterStartGame() {
        // Key bindings and HUD elements are registered once the game is up.
        Keys.register(toggleKey);
        Hud.register(new FpsElement());
    }

    @Override
    public void onTickEnd() {
        while (toggleKey.isPressed()) {
            outline = !outline;
        }
    }

    @Override
    public void onRenderWorld(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        MovingObjectPosition hit = mc.objectMouseOver;
        if (!outline
                || hit == null
                || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return;
        }
        // WorldRender takes world coordinates and sizes in blocks: the Loader handles the camera.
        // Draw through it (or Render, for 2D) rather than with OpenGL or GlStateManager, which the
        // Stein renderer does not support.
        BlockPos pos = hit.getBlockPos();
        WorldRender.blockOutline(pos.getX(), pos.getY(), pos.getZ(), 0.01, OUTLINE_COLOR);
    }

    @Override
    public String onSendChat(String message) {
        if (!message.equalsIgnoreCase("/example")) {
            return message; // everything else goes to the server as usual
        }
        // Minecraft.debugFPS is private in the game. The SDK compiles against the game with private
        // fields and methods opened, and the Loader links the access at runtime — no reflection
        // needed.
        say("Example Mod: " + Minecraft.debugFPS + " fps, outline " + (outline ? "on" : "off"));
        return null; // null: nothing is sent — this was a client-side command
    }

    @Override
    public void onPage(Page page) {
        // Called each time the Panel opens: describe the options, the values are read live.
        page.option(Option.toggle("Block outline", () -> outline, v -> outline = v));
        page.screen("Settings...", ExampleScreen::new);
    }

    @Override
    public Object configScreen(Object parent) {
        // The "Configure" button in the Stein Loader mod list.
        return new ExampleScreen(parent);
    }

    static void say(String text) {
        Minecraft.getMinecraft()
                .ingameGUI
                .getChatGUI()
                .printChatMessage(new ChatComponentText(text));
    }
}
