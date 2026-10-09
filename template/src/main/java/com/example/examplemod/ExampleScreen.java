package com.example.examplemod;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/**
 * A screen of the mod's own, written like any Minecraft screen: the methods it overrides (initGui,
 * drawScreen, actionPerformed) keep their readable names in your code, and the SDK gives them the
 * game's names when it builds.
 */
final class ExampleScreen extends GuiScreen {

    private final GuiScreen parent;

    /**
     * @param parent where to go back to — a GuiScreen (the API passes it as Object)
     */
    ExampleScreen(Object parent) {
        this.parent = (GuiScreen) parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(1, width / 2 - 100, height / 2 - 12, label()));
        buttonList.add(new GuiButton(0, width / 2 - 100, height / 2 + 16, "Done"));
    }

    private String label() {
        return "Block outline: " + (ExampleMod.outline ? "ON" : "OFF");
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 1) {
            ExampleMod.outline = !ExampleMod.outline;
            button.displayString = label();
        } else {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Example Mod", width / 2, height / 2 - 40, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
