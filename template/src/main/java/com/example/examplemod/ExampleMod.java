package com.example.examplemod;

import dev.xavier.stein.loader.api.Hud;
import dev.xavier.stein.loader.api.Keys;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Page;
import dev.xavier.stein.loader.api.SteinMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

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
        // The matrix is the camera's, with the origin at the player's eyes: subtract the
        // interpolated camera position from anything you place in the world.
        Entity cam = mc.getRenderViewEntity();
        double x = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double y = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double z = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;
        BlockPos pos = hit.getBlockPos();
        AxisAlignedBB box =
                new AxisAlignedBB(pos, pos.add(1, 1, 1))
                        .expand(0.003, 0.003, 0.003)
                        .offset(-x, -y, -z);

        // Leave the GL state as you found it.
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2.5F);
        RenderGlobal.drawOutlinedBoundingBox(box, 255, 170, 0, 255);
        GL11.glLineWidth(1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
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
