/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Breno Xavier
 *
 * Stein SDK test mod, licensed under the MIT License (see LICENSE).
 */

package com.example.apitest;

import dev.xavier.stein.loader.api.Account;
import dev.xavier.stein.loader.api.Blend;
import dev.xavier.stein.loader.api.Chat;
import dev.xavier.stein.loader.api.Effects;
import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Game;
import dev.xavier.stein.loader.api.Http;
import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Keys;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.Mesh;
import dev.xavier.stein.loader.api.ModConfig;
import dev.xavier.stein.loader.api.ModContext;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Players;
import dev.xavier.stein.loader.api.Relation;
import dev.xavier.stein.loader.api.Relations;
import dev.xavier.stein.loader.api.Render;
import dev.xavier.stein.loader.api.Screens;
import dev.xavier.stein.loader.api.Secrets;
import dev.xavier.stein.loader.api.Server;
import dev.xavier.stein.loader.api.Stacks;
import dev.xavier.stein.loader.api.SteinMod;
import dev.xavier.stein.loader.api.Targeting;
import dev.xavier.stein.loader.api.Tasks;
import dev.xavier.stein.loader.api.Terrain;
import dev.xavier.stein.loader.api.WorldRender;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * An in-game test of the Loader API, written the way an outside developer writes a mod: readable
 * names, built by the Stein SDK. After joining a server it runs a sequence of steps, one every half
 * second (give items with /give, put armour on and take it off, move items with the inventory
 * closed and open, a cancelled click, raycasts, a manual hit, damage, configuration, relations,
 * drawing...), and logs {@code [apitest] OK} or {@code FAILED} for each check and the totals at the
 * end. See README.md for how to run it.
 */
public class ApiTest implements SteinMod {

    private static final String ID = "apitest";
    private static final String KICK_REASON = "end of apitest";

    /** The A key (LWJGL key code), for the key picker test. */
    private static final int KEY_A = 30;

    private final ModContext ctx = ModContext.of(this);
    private final Log log = ctx.log();

    private int ticks = -1;
    private int step;
    private int ok;
    private int failed;
    private boolean finished;

    // Counted by the events.
    private int clicks;
    private int slotEvents;
    private int attackEvents;
    private boolean cancelNextClick;
    private boolean cancelMobHurt;
    private int selfHurt;
    private int mobHurt;
    private int mobHurtTime = -1;
    private int mobDeaths;
    private float selfDamage;
    private float selfHealthNow;
    private float mobDamage;
    private int relationsEvents;

    // Tasks, downloads and drawing, checked a few steps later.
    private Boolean asyncOnGameThread;
    private boolean asyncBackOnGameThread;
    private boolean laterRan;
    private int httpLength;
    private int waits;
    private boolean drawTest;
    private int overlayFrames;
    private int worldFrames;
    private Render.Texture texture;
    private Mesh mesh;

    private void check(String what, boolean pass) {
        if (pass) {
            ok++;
        } else {
            failed++;
        }
        log.info((pass ? "OK      " : "FAILED  ") + what);
    }

    // --- events ---

    @Override
    public void onJoinGame() {
        if (finished) {
            log.info("back on the server after the kick");
            return;
        }
        ticks = 0;
        step = 0;
        log.info("joined: starting in 5 s");
    }

    @Override
    public boolean onWindowClick(int windowId, int slot, int button, int mode) {
        clicks++;
        if (cancelNextClick) {
            cancelNextClick = false;
            log.info("click cancelled: window " + windowId + " slot " + slot);
            return true;
        }
        return false;
    }

    @Override
    public void onSlotChanged(int windowId, int slot, Object stack) {
        slotEvents++;
    }

    @Override
    public boolean onAttackEntity(Object target) {
        attackEvents++;
        return false;
    }

    @Override
    public boolean onEntityHurt(Object entity) {
        if (entity == Entities.self()) {
            selfHurt++;
            return false;
        }
        if (entity instanceof EntityVillager) {
            mobHurt++;
            return cancelMobHurt;
        }
        return false;
    }

    @Override
    public void onEntityDeath(Object entity) {
        if (entity instanceof EntityVillager) {
            mobDeaths++;
        }
    }

    @Override
    public void onHealthChanged(
            Object entity,
            float oldHealth,
            float newHealth,
            float oldAbsorption,
            float newAbsorption) {
        float damage = (oldHealth + oldAbsorption) - (newHealth + newAbsorption);
        log.info(
                String.format(
                        "health of %s: %.1f -> %.1f (%+.1f)",
                        ((Entity) entity).getName(), oldHealth, newHealth, -damage));
        if (entity == Entities.self()) {
            selfDamage += Math.max(0, damage);
            selfHealthNow = newHealth;
        } else if (entity instanceof EntityVillager) {
            mobDamage += Math.max(0, damage);
            // Right after a hit whose effect was cancelled, the hurt animation must not start.
            mobHurtTime = Math.max(mobHurtTime, Entities.hurtTime(entity));
        }
    }

    @Override
    public void onRelationsChanged() {
        relationsEvents++;
    }

    /** The test ends by kicking itself: checks the disconnect event. */
    @Override
    public void onDisconnected(String address, String reason) {
        if (!finished) {
            return;
        }
        check(
                "onDisconnected: " + address + ", \"" + reason + "\"",
                address != null && reason.contains(KICK_REASON));
        log.info("END: " + ok + " ok, " + failed + " failed");
    }

    // --- context, configuration, secrets, language ---

    static final class TestConfig {
        boolean on = true;
        int count = 3;
        String name = "default";
    }

    private void checkContext() {
        check(
                "ModContext.of(this): id " + ctx.id() + " version " + ctx.version(),
                ID.equals(ctx.id()) && "1.0.0".equals(ctx.version()));
        ModConfig<TestConfig> cfg = ctx.config(TestConfig.class);
        cfg.get().count = 7;
        cfg.get().name = "saved";
        cfg.save();
        TestConfig back = cfg.reload();
        check(
                "ModConfig: saves and reloads",
                back.count == 7 && "saved".equals(back.name) && back.on);
        try {
            // A field with garbage: only that one falls back to its default; the rest holds.
            Files.writeString(
                    cfg.file().toPath(),
                    "{\"on\": false, \"count\": \"not a number\", \"name\": \"ok\"}");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        TestConfig lenient = cfg.reload();
        check(
                "ModConfig: a bad field falls back to its default, the rest holds",
                !lenient.on && lenient.count == 3 && "ok".equals(lenient.name));
        cfg.reset();
        check("ModContext.configFile", (ID + ".json").equals(ctx.configFile().getName()));

        ctx.secrets().put("password", "s3cr3t");
        check(
                "Secrets: round trip (protected by the system: "
                        + Secrets.isSystemProtected()
                        + ")",
                "s3cr3t".equals(ctx.secrets().get("password")));
        try {
            File file = new File(Minecraft.getMinecraft().mcDataDir, "config/" + ID + ".secrets");
            check(
                    "Secrets: the value is not in plain text in the file",
                    !Files.readString(file.toPath()).contains("s3cr3t"));
        } catch (IOException e) {
            check("Secrets: file written", false);
        }
        ctx.secrets().remove("password");

        String yes = Lang.tr("gui.yes");
        check(
                "Lang: language " + Lang.language() + ", gui.yes = " + yes,
                !Lang.language().isEmpty() && Lang.has("gui.yes") && !"gui.yes".equals(yes));
        check(
                "Lang: a missing key comes back as the key",
                !Lang.has("apitest.nothing")
                        && "apitest.nothing".equals(Lang.tr("apitest.nothing")));
        String chat = ctx.tr("chat.type.text", "me", "hi");
        check("Lang: arguments (" + chat + ")", chat.contains("me") && chat.contains("hi"));
    }

    // --- relations, server, account ---

    private void checkRelations() {
        String self = Players.self();
        check(
                "Players.self is in the Tab list (" + Players.online().size() + " players)",
                self != null && Players.online().contains(self));
        check("Relations.of(self) = SELF", Relations.of(self) == Relation.SELF);
        String other = null;
        for (String n : Players.online()) {
            if (!n.equals(self)) {
                other = n;
                break;
            }
        }
        if (other == null) {
            log.info("no other player in the Tab list: the player relations are skipped");
        } else {
            log.info(
                    "other player: "
                            + other
                            + ", group "
                            + Players.group(other)
                            + ", ping "
                            + Players.ping(other)
                            + ", relation "
                            + Relations.of(other));
            Relations.setPlayer(ID, other, Relation.ENEMY);
            check("setPlayer ENEMY wins over the providers", Relations.of(other) == Relation.ENEMY);
            Relations.setPlayer(ID, other, null);
            check("setPlayer null removes the entry", Relations.of(other) != Relation.ENEMY);
        }
        Relations.setGroup(ID, "Any Group", Relation.ALLY);
        check("setGroup takes any name", Relations.groups(Relation.ALLY).contains("ANY GROUP"));
        Relations.clear(ID);
        check(
                "clear removes everything from the source",
                !Relations.groups(Relation.ALLY).contains("ANY GROUP"));
        log.info("Relations.color(ALLY) = " + Integer.toHexString(Relations.color(Relation.ALLY)));
    }

    private void checkServerAccount() {
        String address = Server.address();
        check(
                "Server.address() = \"" + address + "\" (multiplayer)",
                Server.isMultiplayer() && address != null && !address.isEmpty());
        check("Server.lastAddress() = the one joined", address.equals(Server.lastAddress()));
        check(
                "Server.connectingAddress() = \"" + Server.connectingAddress() + "\"",
                address.equals(Server.connectingAddress()));
        check(
                "Account: "
                        + Account.name()
                        + ", "
                        + Account.kind()
                        + ", from the launcher "
                        + Account.isLauncher(),
                Account.name().equals(Players.self()));
    }

    // --- chat, screens, keys, entities, inventory rules ---

    private void checkGameApi() {
        Object c = Chat.text("§ahi");
        check(
                "Chat.text/plain/formatted",
                "hi".equals(Chat.plain(c))
                        && Chat.formatted(c).contains("§a")
                        && Chat.plain("not a component").isEmpty());
        Chat.print("§7[apitest] Chat.print");
        Chat.actionBar("apitest: Chat.actionBar");
        check(
                "Screens: no screen = NONE",
                Screens.currentKind() == Screens.Kind.NONE
                        && !Screens.isOpen()
                        && Screens.kind(null) == Screens.Kind.NONE);
        Object b = Screens.button(0x5E999, 1, 2, 30, 20, "a");
        List<Object> list = new ArrayList<>();
        list.add(b);
        Screens.setLabel(b, "b");
        check(
                "Screens.button/buttonId/X/Y/findButton",
                Screens.buttonId(b) == 0x5E999
                        && Screens.buttonX(b) == 1
                        && Screens.buttonY(b) == 2
                        && Screens.findButton(list, 0x5E999) == b
                        && Screens.buttonId("x") == -1);

        Keys.Binding sprint = Keys.game(Keys.GameKey.SPRINT);
        check(
                "Keys.game(SPRINT) key " + sprint.key(),
                sprint == Keys.game(Keys.GameKey.SPRINT) && sprint.key() != 0);
        Keys.Binding sneak = Keys.game(Keys.GameKey.SNEAK);
        sneak.hold(true);
        boolean held = sneak.isDown();
        sneak.hold(false);
        check("Keys.hold holds and releases (sneak)", held && !sneak.isDown());
        Keys.Binding mine = Keys.bind("key.apitest.test", Keys.KEY_NONE, "key.categories.apitest");
        check(
                "Keys.bind: a new key, not pressed",
                mine.key() == Keys.KEY_NONE && !mine.isDown() && !mine.consumeClick());

        Object me = Entities.self();
        String nick = Players.self();
        check(
                "Entities.players/player/name (" + Entities.players().size() + " in the world)",
                Entities.players().contains(me)
                        && Entities.player(nick) == me
                        && nick.equals(Entities.name(me)));
        check(
                "Entities.isPlayer/isInvisible/distance",
                Entities.isPlayer(me)
                        && !Entities.isInvisible(me)
                        && Entities.distance(me, me) == 0F
                        && !Entities.isPlayer("x")
                        && Entities.name("x") == null);
        log.info(
                "Players.displayName(self) = "
                        + Players.displayName(nick)
                        + ", nickOf(null) = "
                        + Players.nickOf(null));

        Object apples = Inventory.stack(6);
        Object chest = Inventory.armor(Inventory.CHESTPLATE);
        check(
                "Inventory.isCraftingSlot (0 and 1 yes, 9 no)",
                Inventory.isCraftingSlot(0)
                        && Inventory.isCraftingSlot(1)
                        && !Inventory.isCraftingSlot(9));
        check(
                "Inventory.accepts (chestplate in the chestplate slot, not in the helmet's)",
                Inventory.accepts(6, chest) && !Inventory.accepts(5, chest));
        check(
                "Inventory.room for the apples (10 -> 54 more fit)",
                Inventory.room(Inventory.slotOf(6)) == 54);
        check(
                "Inventory.stacksWith",
                Inventory.stacksWith(apples, apples)
                        && !Inventory.stacksWith(apples, chest)
                        && !Inventory.stacksWith(null, null));
        checkHudApi();
    }

    /** Stacks, Effects, Terrain, Entities, Players.list and Game: what a HUD uses. */
    private void checkHudApi() {
        Object sword = Stacks.create("diamond_sword", 1, 900);
        check(
                "Stacks.create/id/is/damage (" + Stacks.name(sword) + ")",
                "minecraft:diamond_sword".equals(Stacks.id(sword))
                        && Stacks.is(sword, "diamond_sword")
                        && Stacks.damage(sword) == 900
                        && Stacks.maxDamage(sword) == 1561
                        && Stacks.isDamageable(sword)
                        && Stacks.create("does_not_exist", 1, 0) == null);
        check("Inventory.count(apple) = 10", Inventory.count(s -> Stacks.is(s, "apple")) == 10);
        Effects.Effect speed = new Effects.Effect(1, 1, 90 * 20, false);
        check(
                "Effects.name/durationText ("
                        + Effects.name(speed)
                        + " "
                        + Effects.durationText(speed)
                        + ")",
                "1:30".equals(Effects.durationText(speed))
                        && Effects.name(speed).endsWith("II")
                        && Effects.hasIcon(1)
                        && !Effects.hasIcon(6));
        Object me = Entities.self();
        int x = (int) Math.floor(Entities.x(me, 1));
        int y = (int) Math.floor(Entities.y(me, 1));
        int z = (int) Math.floor(Entities.z(me, 1));
        check(
                "Terrain: world, chunk, height "
                        + Terrain.height(x, z)
                        + ", sky light "
                        + Terrain.skyLight(x, y + 1, z),
                Terrain.current() != null
                        && Terrain.isLoaded(x >> 4, z >> 4)
                        && Terrain.height(x, z) > 0
                        && Terrain.isAir(x, y + 1, z)
                        && Terrain.hasSky());
        check(
                "Entities.all/kind/x/y/z/yaw",
                Entities.all().contains(me)
                        && Entities.kind(me) == Entities.Kind.PLAYER
                        && !Double.isNaN(Entities.x(me, 0.5F))
                        && !Float.isNaN(Entities.yaw(me, 0.5F)));
        List<Players.Entry> tab = Players.list();
        boolean listed = false;
        for (Players.Entry e : tab) {
            listed |= e.nick.equals(Players.self()) && e.tabName.contains(e.nick);
        }
        check(
                "Players.list/count (" + tab.size() + "), header " + (Players.tabHeader() != null),
                tab.size() == Players.count() && listed);
        check(
                "Game.fps "
                        + Game.fps()
                        + ", limit "
                        + Game.fpsLimit()
                        + ", monitor "
                        + Game.refreshRate()
                        + " Hz",
                Game.fps() > 0 && Game.fpsLimit() >= 0);
    }

    /** Targeting.query: leaves that stop the ray or not, living only, margin, cut at the block. */
    private void checkRaycastQuery() {
        Object me = Entities.self();
        int x = (int) Math.floor(Entities.x(me, 1));
        int y = (int) Math.floor(Entities.y(me, 1));
        int z = (int) Math.floor(Entities.z(me, 1));
        Targeting.Ray plain = Targeting.raycast(Targeting.query().reach(6));
        check(
                "raycast(query): stops at the leaves ("
                        + plain.blockId
                        + " at "
                        + plain.blockX
                        + ","
                        + plain.blockY
                        + ","
                        + plain.blockZ
                        + "), the villager behind is not first",
                "minecraft:leaves".equals(plain.blockId)
                        && plain.blockZ == z + 2
                        && plain.blockY == y + 1
                        && plain.first() == null
                        && !plain.hits.isEmpty());
        Targeting.Ray through =
                Targeting.raycast(
                        Targeting.query().reach(6).living().passThrough(Targeting.FOLIAGE));
        Targeting.Hit villager = through.first();
        check(
                "raycast(query): FOLIAGE lets it through and living() finds the villager ("
                        + (villager == null ? "nobody" : Entities.typeName(villager.entity))
                        + ")",
                villager != null
                        && "Villager".equals(Entities.typeName(villager.entity))
                        && through.blockId == null);
        check(
                "raycast(query): cutAtBlock drops whoever is behind",
                Targeting.raycast(Targeting.query().reach(6).cutAtBlock()).hits.isEmpty());
        Targeting.Hit wide =
                Targeting.raycast(
                                Targeting.query()
                                        .reach(6)
                                        .passThrough(Targeting.FOLIAGE)
                                        .margin(0.3))
                        .first();
        check(
                "raycast(query): a 0.3 margin hits closer ("
                        + (wide == null ? "-" : String.format("%.2f", wide.distance))
                        + " < "
                        + (villager == null ? "-" : String.format("%.2f", villager.distance))
                        + ")",
                wide != null && villager != null && wide.distance < villager.distance);
        check(
                "Terrain.blockId = " + Terrain.blockId(x, y + 1, z + 2),
                "minecraft:leaves".equals(Terrain.blockId(x, y + 1, z + 2)));
        check(
                "Entities.isAlive/displayName/camera ("
                        + (villager == null ? "-" : Entities.displayName(villager.entity))
                        + ")",
                villager != null
                        && Entities.isAlive(villager.entity)
                        && !Entities.displayName(villager.entity).isEmpty()
                        && Entities.camera() == me);
        check(
                "Render.trim",
                "abc".equals(Render.trim("abcdef", Render.textWidth("abc")))
                        && Render.trim("abc", 1000).equals("abc"));
    }

    // --- Panel options, without a screen ---

    private double sliderValue = 2;
    private int keyValue;

    private void checkOptions() {
        Option.Slider s =
                Option.slider("Radius", 0, 10, 0.5, () -> sliderValue, v -> sliderValue = v, null);
        s.set(3.26);
        boolean snapped = sliderValue == 3.5;
        s.setFraction(2);
        boolean top = sliderValue == 10;
        s.click(false);
        boolean down = sliderValue == 9.5 && (s.value().equals("9,50") || s.value().equals("9.50"));
        check("Option.slider: step, limit, click (" + s.value() + ")", snapped && top && down);
        Option.Key k = Option.key("Key", () -> keyValue, v -> keyValue = v);
        k.click(true);
        boolean waiting = k.isListening();
        boolean used = k.keyPressed(KEY_A);
        boolean saved = keyValue == KEY_A && !k.isListening() && k.value().equals("A");
        k.click(true);
        k.keyPressed(Keys.KEY_ESCAPE);
        boolean escape = keyValue == KEY_A;
        k.click(false);
        check(
                "Option.key: waits, saves, Esc gives up, right click clears",
                waiting && used && saved && escape && keyValue == 0);
    }

    // --- Java 25 features the Loader must let through when it adapts the mod ---

    sealed interface Piece permits Weapon, Armour {}

    record Weapon(String name, int damage) implements Piece {}

    record Armour(String name, int points) implements Piece {}

    private static int value(Object o) {
        return switch (o) {
            case Weapon(var name, var damage) when damage > 5 -> damage * 2;
            case Weapon w -> w.damage();
            case Armour(String name, int points) -> points;
            case Integer i -> i;
            default -> -1;
        };
    }

    private boolean java25() {
        var items =
                List.of(new Weapon("sword", 7), new Weapon("stick", 1), new Armour("chest", 8), 3);
        int sum = 0;
        for (var o : items) {
            sum += value(o);
        }
        String text =
                """
                sum %d
                """
                        .formatted(sum)
                        .strip();
        log.info(text + ", " + items.get(0));
        return sum == 14 + 1 + 8 + 3 && items.get(0).equals(new Weapon("sword", 7));
    }

    // --- drawing: Render on the HUD, WorldRender and Mesh in the world ---

    @Override
    public void onOverlay(float partialTicks) {
        if (!drawTest) {
            return;
        }
        if (texture == null) {
            texture = Render.texture(16, 16);
            int[] px = new int[256];
            for (int i = 0; i < px.length; i++) {
                px[i] = (i / 16 + i % 16) % 2 == 0 ? 0xFFFF00FF : 0xFF00FFFF;
            }
            texture.set(0, 0, 16, 16, px, 0);
        }
        Render.push();
        Render.translate(10, 40);
        Render.roundRect(0, 0, 120, 80, 6, 0xC0202020);
        Render.text("API Render", 6, 6, 0xFFFFFFFF, true);
        Render.triangle(6, 50, 16, 30, 26, 50, 0xFFFF5555);
        Render.circle(40, 40, 8, 0xFF55FF55);
        texture.draw(60, 24, 32, 32);
        Render.item(Inventory.heldItem(), 98, 30, true);
        Render.playerHead(Players.self(), 98, 8, 16);
        Render.line(6, 60, 60, 70, 2, 0xFFFFFF55);
        Render.rectOutline(64, 58, 20, 16, 1, 0xFFFFFFFF);
        Render.gradient(88, 58, 26, 16, 0xFF5555FF, 0xFFFF55FF, true);
        Render.blend(Blend.ADDITIVE, () -> Render.circle(40, 66, 6, 0x80FFAA00));
        Render.pop();
        overlayFrames++;
    }

    @Override
    public void onRenderWorld(float partialTicks) {
        if (!drawTest) {
            return;
        }
        if (mesh == null) {
            mesh =
                    Mesh.builder(Mesh.Primitive.TRIANGLES, Mesh.Format.COLOR)
                            .vertex(0, 1, 0)
                            .color(0xFFFF5555)
                            .vertex(-0.5, 0, 0)
                            .color(0xFFFF5555)
                            .vertex(0.5, 0, 0)
                            .color(0xFFFF5555)
                            .build();
        }
        Object me = Entities.self();
        double x = Entities.x(me, partialTicks);
        double y = Entities.y(me, partialTicks);
        double z = Entities.z(me, partialTicks);
        WorldRender.blockOutline(
                (int) Math.floor(x),
                (int) Math.floor(y) - 1,
                (int) Math.floor(z),
                0.02,
                0xFF55FFFF);
        WorldRender.circle(x, y + 0.01, z, 1.5, 0x4055FF55);
        WorldRender.mesh(mesh, x, y + 2.5, z + 2);
        WorldRender.throughWalls(
                () -> WorldRender.label("apitest", x, y + 3, z + 2, 1, 0xFFFFFFFF));
        worldFrames++;
    }

    // --- the sequence ---

    private static boolean is(Object stack, Object item) {
        return stack instanceof ItemStack && ((ItemStack) stack).getItem() == item;
    }

    private static int index(Object item) {
        return Inventory.find(s -> is(s, item), 0, 35);
    }

    @Override
    public void onTickEnd() {
        Minecraft mc = Minecraft.getMinecraft();
        if (ticks < 0 || mc.thePlayer == null) {
            return;
        }
        ticks++;
        if (ticks < 100 || ticks % 10 != 0) {
            return;
        }
        String self = mc.thePlayer.getName();
        switch (step++) {
            case 0 -> Chat.send("/clear");
            case 1 -> {
                Chat.send("/give " + self + " diamond_chestplate");
                Chat.send("/give " + self + " iron_helmet");
                Chat.send("/give " + self + " diamond_sword");
                Chat.send("/give " + self + " apple 10");
            }
            case 2 -> {
                log.info(
                        "window "
                                + Inventory.windowId()
                                + ", "
                                + Inventory.slotCount()
                                + " slots, open: "
                                + Inventory.isScreenOpen()
                                + ", container: "
                                + Inventory.isContainerOpen());
                check(
                        "the items arrived (chestplate, helmet, sword, apples)",
                        index(Items.diamond_chestplate) >= 0
                                && index(Items.iron_helmet) >= 0
                                && index(Items.diamond_sword) >= 0
                                && index(Items.apple) >= 0);
                Object chestplate = Inventory.stack(index(Items.diamond_chestplate));
                check(
                        "armorPiece(chestplate) = CHESTPLATE",
                        Inventory.armorPiece(chestplate) == Inventory.CHESTPLATE);
                check("armorValue(diamond chestplate) = 8", Inventory.armorValue(chestplate) == 8);
                check(
                        "slotOf/inventoryIndexOf round trip (hotbar 0 = slot 36)",
                        Inventory.slotOf(0) == 36 && Inventory.inventoryIndexOf(36) == 0);
                check(
                        "slotOf(helmet armour slot) = 5",
                        Inventory.slotOf(Inventory.ARMOR_START + Inventory.HELMET) == 5);
                check(
                        "equip(chestplate) with the inventory closed",
                        Inventory.equip(index(Items.diamond_chestplate)));
                check("equip(helmet)", Inventory.equip(index(Items.iron_helmet)));
            }
            case 3 -> {
                check(
                        "chestplate on",
                        is(Inventory.armor(Inventory.CHESTPLATE), Items.diamond_chestplate));
                check("helmet on", is(Inventory.armor(Inventory.HELMET), Items.iron_helmet));
                check("unequip(helmet)", Inventory.unequip(Inventory.HELMET));
            }
            case 4 -> {
                check(
                        "the helmet went back to the inventory",
                        Inventory.armor(Inventory.HELMET) == null && index(Items.iron_helmet) >= 0);
                int apple = index(Items.apple);
                check(
                        "move(apples -> hotbar 5)",
                        Inventory.move(Inventory.slotOf(apple), Inventory.slotOf(5)));
            }
            case 5 -> {
                check("apples in hotbar 5", is(Inventory.stack(5), Items.apple));
                int sword = index(Items.diamond_sword);
                if (sword >= 9) {
                    Inventory.swapHotbar(Inventory.slotOf(sword), 2);
                    sword = 2;
                }
                Inventory.selectHotbar(sword);
                check(
                        "selectHotbar(sword) and heldItem",
                        is(Inventory.heldItem(), Items.diamond_sword));
            }
            case 6 -> {
                int before = clicks;
                Object where = Inventory.stack(5);
                cancelNextClick = true;
                boolean sent = Inventory.pickup(Inventory.slotOf(5));
                check(
                        "click cancelled by onWindowClick: the item stayed, the cursor is empty",
                        sent
                                && clicks == before + 1
                                && Inventory.stack(5) == where
                                && Inventory.cursor() == null);
                Inventory.openInventory();
            }
            case 7 -> {
                check("openInventory: screen open", Inventory.isScreenOpen());
                check(
                        "move with the inventory open (apples 5 -> 6)",
                        Inventory.move(Inventory.slotOf(5), Inventory.slotOf(6)));
            }
            case 8 -> {
                check(
                        "apples in hotbar 6",
                        is(Inventory.stack(6), Items.apple) && Inventory.stack(5) == null);
                Inventory.closeScreen();
            }
            case 9 -> {
                check("closeScreen: screen closed", !Inventory.isScreenOpen());
                check(
                        "events: " + clicks + " clicks seen, " + slotEvents + " slots changed",
                        clicks >= 8 && slotEvents > 0);
                checkRaycast();
                check(
                        "Java 25 in the mod: record, sealed, pattern switch, text block, var",
                        java25());
                checkOptions();
                // Damage: the player and a villager summoned now. The hit comes in the next step:
                // in this tick the villager has not reached the client yet.
                Chat.send("/summon Villager ~ ~ ~2");
            }
            case 10 -> {
                cancelMobHurt = true;
                Chat.send("/effect " + self + " 7 1 0");
                Chat.send("/effect @e[type=Villager] 7 1 0");
            }
            case 11 -> {
                check("onEntityHurt of the player", selfHurt > 0);
                float health = Entities.health(Entities.self());
                check(
                        "onHealthChanged of the player (took "
                                + selfDamage
                                + ", health "
                                + health
                                + ")",
                        selfDamage > 0 && Math.abs(selfHealthNow - health) < 0.01);
                check(
                        "onEntityHurt of the villager ("
                                + mobHurt
                                + "x), effect cancelled: hurtTime "
                                + mobHurtTime,
                        mobHurt > 0 && mobHurtTime == 0);
                check("onHealthChanged of the villager (took " + mobDamage + ")", mobDamage > 0);
                cancelMobHurt = false;
                Chat.send("/kill @e[type=Villager]");
            }
            case 12 -> {
                check("onEntityDeath of the villager (" + mobDeaths + "x)", mobDeaths > 0);
                checkContext();
                checkRelations();
                checkServerAccount();
                checkGameApi();
                Tasks.async(
                        () -> Minecraft.getMinecraft().isCallingFromMinecraftThread(),
                        onGame -> {
                            asyncOnGameThread = onGame;
                            asyncBackOnGameThread =
                                    Minecraft.getMinecraft().isCallingFromMinecraftThread();
                        },
                        e -> log.warn("Tasks.async failed: " + e));
                Tasks.later(5, () -> laterRan = true);
                Http.getText(
                        "https://example.com/", t -> httpLength = t.length(), e -> httpLength = -1);
                drawTest = true;
            }
            case 13 -> {
                // The tasks and the download come back by themselves: wait up to 5 s for it.
                if (httpLength == 0 && waits++ < 10) {
                    step--;
                    return;
                }
                check(
                        "Tasks.async ran off the game thread and came back on it",
                        Boolean.FALSE.equals(asyncOnGameThread) && asyncBackOnGameThread);
                check("Tasks.later ran", laterRan);
                check("Http.getText (" + httpLength + " characters)", httpLength > 0);
                check("onRelationsChanged arrived (" + relationsEvents + "x)", relationsEvents > 0);
                check(
                        "Render drew on the HUD without errors (" + overlayFrames + " frames)",
                        overlayFrames > 0);
                check(
                        "WorldRender and Mesh drew in the world without errors ("
                                + worldFrames
                                + " frames)",
                        worldFrames > 0);
            }
            case 14 -> {
                // The ray with options: leaves in front, a villager standing behind (NoAI).
                Chat.send("/tp " + self + " ~ ~ ~ 0 0");
                Chat.send("/setblock ~ ~1 ~2 leaves");
                Chat.send("/summon Villager ~ ~ ~4 {NoAI:1}");
            }
            case 15 -> {
                checkRaycastQuery();
                Chat.send("/setblock ~ ~1 ~2 air");
                Chat.send("/kill @e[type=Villager]");
            }
            case 16 -> {
                drawTest = false;
                if (texture != null) {
                    texture.release();
                    texture = null;
                }
                if (mesh != null) {
                    mesh.release();
                    mesh = null;
                }
                finished = true;
                ticks = -1;
                Chat.send("/kick " + self + " " + KICK_REASON);
            }
            default -> {}
        }
    }

    /** The plain raycast against the game's own aim, and a manual hit. */
    private void checkRaycast() {
        Targeting.Ray ray = Targeting.raycast(40);
        log.info(
                "attack reach "
                        + Targeting.attackReach()
                        + "; ray of 40: "
                        + ray.hits.size()
                        + " entities, block at "
                        + ray.blockDistance);
        double last = -1;
        boolean sorted = true;
        for (int i = 0; i < ray.hits.size(); i++) {
            Targeting.Hit h = ray.hits.get(i);
            sorted &= h.distance >= last;
            last = h.distance;
            if (i < 4) {
                log.info(String.format("  %.2f  %s", h.distance, ((Entity) h.entity).getName()));
            }
        }
        check("raycast in order of distance", sorted);
        Object aim = Targeting.pointedEntity();
        Targeting.Hit first = Targeting.raycast().first();
        check(
                "raycast() matches the game's aim ("
                        + (aim == null ? "nobody" : ((Entity) aim).getName())
                        + ")",
                aim == null ? first == null : first != null && first.entity == aim);
        int before = attackEvents;
        if (!ray.hits.isEmpty()) {
            Targeting.swing();
            check("manual attack", Targeting.attack(ray.hits.get(0).entity));
        }
        check("a manual attack does not go through onAttackEntity", attackEvents == before);
    }
}
