/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Stein SDK example mod, licensed under the MIT License (see LICENSE).
 */

package com.example.damageindicator;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.HudElement;
import dev.xavier.stein.loader.api.HudPlacement;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Render;

/**
 * "-3.5 ❤ (14/20)": the last hit you took, for three seconds. The player moves and scales it in the
 * HUD editor.
 */
final class LastHitElement implements HudElement {

    private static final int WHITE = 0xFFFFFFFF;

    private String text = "";
    private int width;

    @Override
    public String id() {
        return DamageIndicator.ID + ".lasthit";
    }

    @Override
    public String name() {
        return "Last hit taken";
    }

    @Override
    public boolean layout(boolean preview, float partialTicks) {
        boolean recent = System.currentTimeMillis() - DamageIndicator.lastDamageAt < 3000;
        if (!preview && !recent) {
            return false; // nothing to show: the grid skips it
        }
        float dmg = preview ? 3.5F : DamageIndicator.lastDamage;
        Object self = Entities.self();
        int hp = self == null ? 20 : Math.round(Entities.health(self));
        int max = self == null ? 20 : Math.round(Entities.maxHealth(self));
        text =
                Option.fmt(DamageIndicator.config.color)
                        + String.format("-%.1f ❤", dmg)
                        + " §7("
                        + hp
                        + "/"
                        + max
                        + ")";
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
        Render.text(text, 0, 0, WHITE, true);
    }

    @Override
    public HudPlacement defaultPlacement() {
        // Centre of the screen, a bit below the crosshair. Mod elements start off: the player turns
        // them on in the HUD editor.
        return new HudPlacement(1, 1, 0, 20, 1.0F, false, false);
    }
}
