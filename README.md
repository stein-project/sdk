# Stein Loader — Mod Developer Guide

Stein Loader is a mod platform for the **Minecraft 1.8.9 client**, running on the Stein Bridge: Java 25, LWJGL 3 and a
faster renderer that does not change how the game looks. Mods are client-side and work on any vanilla server.

This guide covers everything you need to write, build and ship a mod.

> **The API is experimental.** It works and it is what our own mods use, but anything marked `@Experimental` may still
> change between Stein Loader versions. Tell us what you miss — this is the time to shape it.

**Download** `stein-mod-template.zip` (and `stein-sdk.jar` if you build without Gradle) from the
[latest release](https://github.com/x4vieer/stein-sdk/releases/latest). **Example:** [`examples/damage-indicator`](examples/damage-indicator) is a
complete mod — damage numbers over entities, a HUD element, a Panel page with a slider and a key picker, and a key that
turns the numbers on and off. **Licence:** the SDK, the API, the template and the examples are [MIT](LICENSE).

- [How it differs from Forge](#how-it-differs-from-forge)
- [Quick start with Gradle](#quick-start-with-gradle)
- [Command line (no Gradle)](#command-line-no-gradle)
- [Project layout](#project-layout)
- [steinmod.json](#steinmodjson)
- [Game names and private members](#game-names-and-private-members)
- [Events](#events)
- [Services](#services)
- [Rules of the road](#rules-of-the-road)
- [Troubleshooting](#troubleshooting)

## How it differs from Forge

| | Forge 1.8.9 | Stein Loader |
|---|---|---|
| Runs on | Java 8, LWJGL 2 | Java 25, LWJGL 3 |
| Side | client and server | client only (any vanilla server) |
| How mods change the game | event bus + coremods (ASM) | a fixed set of events; **no coremods, no Mixin** |
| Rendering | stock fixed-function OpenGL | Stein GFX on OpenGL 4.1+ core (the fixed-function pipeline only as a fallback on older cards); draw through `Render`, `WorldRender` and `Mesh` |

The last two rows are the trade-off. Mods cannot rewrite game bytecode, so two mods never fight over the same code,
and the engine underneath can keep changing without breaking you. In exchange, you can only change the game where the
Loader gives you an event. If you need one that does not exist, ask for it (see [Troubleshooting](#troubleshooting)).

## Quick start with Gradle

You need **Java 17 or newer** to run Gradle and **Minecraft 1.8.9** installed. If you have never played 1.8.9, the
SDK downloads the game from Mojang, the same way the launcher does. Nothing else to install: the Gradle wrapper
downloads Gradle, and the template downloads **JDK 25** for compiling if you do not have it.

Your mod is **Java 25**, not the Java 8 of Forge mods: it compiles with `--release 25`, and records, sealed types,
pattern matching in `switch`, text blocks and `var` all work in game.

```sh
# 1. Start from the template (or copy examples/damage-indicator).
unzip stein-mod-template.zip -d my-mod && cd my-mod

# 2. Build: produces build/libs/ExampleMod.steinmod (the "name" without spaces).
./gradlew build            # Windows: gradlew build

# 3. Or build and copy into .minecraft/mods in one go.
./gradlew installMod
```

On Linux and macOS, run `chmod +x gradlew` once if the zip lost the executable bit.

The first build prepares the game for compiling (see [below](#command-line-no-gradle): the same `setup`, once per
machine, in `~/.stein-sdk`). Then start the game with the **Stein Loader** profile.

The project is an ordinary Gradle project using the plugin `dev.xavier.stein.mod`:

```groovy
// settings.gradle
pluginManagement {
    repositories {
        maven { url = uri("https://raw.githubusercontent.com/x4vieer/stein-sdk/maven") }
        gradlePluginPortal()
    }
}

// build.gradle
plugins {
    id 'dev.xavier.stein.mod' version '0.2.3'
}

stein {                                          // all optional
    sdkVersion = '0.2.3'                         // the stein-sdk.jar release to use (default: the plugin's version)
    minecraftDir = file('D:/Games/.minecraft')   // if your .minecraft is not in the usual place
}
```

The plugin downloads `stein-sdk.jar` from the SDK releases into the Gradle cache and uses it for everything
that involves the game: Gradle only compiles your code, and the SDK translates, checks and packs it — the `.steinmod`
is the same as the one `java -jar stein-sdk.jar build` makes.

| Task | What it does |
|---|---|
| `build` / `assemble` | compile, then `steinmod` |
| `steinmod` | translate the compiled classes to the game's names, check every reference to the game, pack `build/libs/<Name>.steinmod` |
| `installMod` | `steinmod`, then copy it into `<minecraftDir>/mods` |
| `steinSetup` | prepare the game for compiling; runs by itself the first time, skipped once done |

The plain `jar` task is turned off: a jar with readable names does not run in the game.

**IDE.** Open the project folder in IntelliJ IDEA (or *File → Open* the `build.gradle`) and let it import it. The
game with readable names, `stein-api.jar` and the game's libraries show up as compile-only libraries, including the
members your `access` opens. The first import takes a minute more: that is the one-time setup. To read the API
documentation in the editor, attach `stein-api-sources.jar` (next to `stein-api.jar` in `~/.stein-sdk/1.8.9`) when
IntelliJ offers *Choose Sources…*. Changed `access` in `steinmod.json`? Reload the Gradle project. Eclipse works the
same with Buildship.

Jars in `libs/` (the API of another mod, see [Talking to another mod](#talking-to-another-mod)) are added for compiling
too, as with the command line. The API is also published as `dev.xavier.stein:stein-api` in the same Maven repository,
for tools that want it; you do not need it with the plugin.

## Command line (no Gradle)

The SDK is a single jar and works without Gradle. You need **JDK 25**.

```sh
# 1. Once: prepare the game for compiling (readable names, see below).
java -jar stein-sdk.jar setup

# 2. Start from the template.
unzip stein-mod-template.zip -d my-mod && cd my-mod

# 3. Build: produces build/ExampleMod.steinmod (the "name" without spaces)
java -jar stein-sdk.jar build

# 4. Or build and copy into .minecraft/mods in one go.
java -jar stein-sdk.jar install
```

Then start the game with the **Stein Loader** profile. Your mod shows up in the mod list (the Stein Loader tab on the
pause menu), with the reason if it did not load.

`setup` uses the Minecraft jar from your own installation (checked against Mojang's checksum) and downloads the MCP
mappings from the Forge maven. It builds the compile jars **on your machine**, in `~/.stein-sdk` (or
`STEIN_SDK_HOME`). Nothing from Mojang or MCP is shipped inside the SDK — and you must not ship it in your mod either.
Use `--mc <folder>` if your `.minecraft` is somewhere else.

### IDE

Run `java -jar stein-sdk.jar classpath .` in your project and add the listed jars as libraries of your project
(IntelliJ: *Project Structure → Libraries*; Eclipse: *Build Path → Add External JARs*). With the project folder given,
the game jar listed first is the one with your `access` members opened (in `build/stein`); without it, the shared one.
Attach `stein-api-sources.jar` (next to `stein-api.jar`) as the sources of `stein-api.jar` to get the API
documentation in the editor. Set the project SDK to Java 25. Build with the SDK command, not the IDE: the SDK also
translates the names back to the game's.

### Other build tools

`build` is two steps you can also run separately — compile against `classpath <project>` with `--release 25`, then:

```sh
java -jar stein-sdk.jar pack <classesDir> <resourcesDir> <out.steinmod>
```

`pack` translates the compiled classes to the game's names, checks every reference to the game and packs them with
the resources (which must include `steinmod.json`). That is what the Gradle plugin runs.

## Project layout

```
my-mod/
  src/main/java/...               your code
  src/main/resources/
    steinmod.json                 the manifest (required)
    pack.mcmeta                   if you have assets
    assets/<your-id>/...          textures, lang files, sounds
  libs/                           extra jars to compile against (optional)
  build.gradle, settings.gradle   Gradle (optional; gradlew and gradle/ come with them)
  build/libs/<Name>.steinmod      the output with Gradle (build/<Name>.steinmod with the command line)
```

A `.steinmod` is a jar with another extension (old Forge tries to load every `.jar` in the mods folder). Everything in
`src/main/resources` goes into it as is; `assets/` becomes a resource pack, like a Forge mod.

## steinmod.json

```json
{
  "id": "examplemod",
  "name": "Example Mod",
  "version": "1.0.0",
  "author": "You",
  "description": "Shown in the mod list.",
  "entry": "com.example.examplemod.ExampleMod",
  "loader": "0.1.14",
  "depends": {"othermod": "1.2.0"},
  "optional": {"steincombat": "0.1.12"},
  "access": ["net/minecraft/client/gui/GuiPlayerTabOverlay.drawPing"]
}
```

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | Unique id: lowercase, no spaces. Other mods and the config use it. |
| `name`, `version`, `description` | | Shown in the mod list. `version` is compared as numbers (`1.10.0` > `1.9.0`). |
| `author` / `authors` | | A string, or a list of strings. |
| `entry` | yes | The class implementing `SteinMod` (or a list of them). Needs a public no-argument constructor. |
| `loader` | yes | The **minimum Stein Loader version** your mod needs. Use `0.1.14` or newer: the Loader adapts your mod to the engine when it loads it. |
| `depends` | | Mods you cannot run without, `{"id": "minimum version"}`. Without them your mod does not load, and the list says why. They load before you. |
| `optional` | | Mods you use when present. If present, they must be at least that version; they load before you. |
| `access` | | Protected game methods you call from outside their class hierarchy (see below). Compile-time only. |
| `announce` | | `false` keeps the mod out of the list the Loader sends to multiplayer servers (it still shows in the player's mod list). Default `true`. Loader 0.1.16+. |

## Game names and private members

You write your mod with the **MCP stable_22** names — `Minecraft.getMinecraft()`, `mc.thePlayer`,
`GuiScreen.drawScreen` — the same names as Forge 1.8.9 mods and tutorials. The SDK translates them to the game's
obfuscated names when it builds, and checks that every game class, field and method you use exists. Overridden methods
(`initGui`, `drawScreen`, `actionPerformed` in your `GuiScreen`) are translated too.

**Private fields and methods are available directly.** The compile jar has them opened, so you write:

```java
int fps = Minecraft.debugFPS;                  // private static in the game
overlay.header                                  // private field of GuiPlayerTabOverlay
mc.session = new Session(...);                  // private *final* field — writing works too
```

In the game those members stay private; when your mod loads, the Loader links each access to the member once, and
the JIT treats it as a direct access. **Do not use reflection with names in strings** (`getDeclaredField("...")`):
strings are not translated, so it breaks in the real game.

Two limits:

- **Protected methods** stay protected in the compile jar, so you can override them as usual (`protected void
  keyTyped`). To *call* one from outside its hierarchy, list it in `access` as `"net/minecraft/…/Class.methodName"`,
  with the name you write in your code.
- **Constructors and non-public classes** cannot be opened: `new SomePrivateClass()` will not work.

## Events

Implement `dev.xavier.stein.loader.api.SteinMod` and override what you need; every method has a default that does
nothing. Game types arrive as `Object` (the API does not depend on the game's classes): cast them to what the comment
says. All events run on the game thread, in dependency order. An exception in your event is logged and the game — and
the other mods — go on.

| Event | When | Forge 1.8.9 equivalent |
|---|---|---|
| `beforeStartGame()` | before `Minecraft.startGame`, nothing loaded yet | — |
| `afterStartGame()` | window, resources and options ready — **register keys and HUD elements here** | `FMLInitializationEvent` |
| `onTickEnd()` | end of each client tick (20 per second) | `ClientTickEvent` (END) |
| `onKeyInput()`, `onMouseInput()` | after each keyboard / mouse event the game handled | `KeyInputEvent`, `MouseInputEvent` |
| `onOverlay(pt)` | after the HUD is drawn | `RenderGameOverlayEvent.Post` (ALL) |
| `onOverlayTop(pt)` | after everything, on top of the HUD grid | — |
| `onRenderWorld(pt)` | world drawn (before the hand), camera matrix | `RenderWorldLastEvent` |
| `beforeDrawScreen`, `afterDrawScreen` | around the open screen's `drawScreen` | `DrawScreenEvent.Pre/Post` |
| `onGuiInit(screen, buttons)` | a screen was built; add your buttons | `InitGuiEvent.Post` |
| `onGuiAction(screen, button)` | a button was clicked; return `true` to take it | `ActionPerformedEvent.Pre` |
| `onGuiMouseInput(screen)` | mouse event on a screen; return `true` to take it | `GuiScreenEvent.MouseInputEvent.Pre` |
| `onChat(message)` | chat message from the server; return another, or `null` to drop | `ClientChatReceivedEvent` |
| `onSendChat(message)` | what the player sends (chat or command); return another, or `null` to not send — client commands | `ClientChatEvent` |
| `onCustomPayload(channel, bytes)` | plugin message from the server | custom payload / `FMLNetworkEvent` |
| `onPlaySound(sound)` | a sound about to play; return another, or `null` to mute | `PlaySoundEvent` |
| `onJoinGame()` | joined a server or world | `ClientConnectedToServerEvent` |
| `onAttackEntity(target)` | the player's attack, before it is sent; `true` cancels | `AttackEntityEvent` |
| `onInteractEntity(target)` | the player right-clicks an entity, before it is sent; `true` cancels (the held item is used instead, as when clicking the air) | `EntityInteractEvent` |
| `onWindowClick(window, slot, button, mode)` | any inventory click (game or `Inventory`), before it is sent; `true` cancels | `GuiContainer` mouse events, roughly |
| `onSlotChanged(window, slot, stack)`, `onWindowItems(window)` | the server changed a slot / sent a whole window, already applied | — |
| `onEntityHurt(entity)` | any entity you see took damage (you too): the red flash, shake and sound come from here; `true` removes that effect on your client only | `LivingHurtEvent`, roughly (client side) |
| `onEntityDeath(entity)` | an entity died, before its death animation | `LivingDeathEvent`, roughly (client side) |
| `onRelationsChanged()` | anything in `Relations` changed (end of that tick) | — |
| `onShutdown()` | the game is closing: save pending files, nothing else (runs on a shutdown thread) | — |
| `onDisconnected(address, reason)` | the connection dropped or was refused: the disconnect screen just opened | `ClientDisconnectionFromServerEvent`, roughly |
| `onHealthChanged(entity, oldHealth, newHealth, oldAbsorption, newAbsorption)` | health or absorption changed, already applied — damage is the drop, healing the rise | — |
| `onFov`, `onFovModifier` | field of view of the frame / the player's FOV factor | `FOVModifier`, `FOVUpdateEvent` |
| `onTextureStitch(map)`, `onModelsReloaded()` | texture atlas built / models ready | `TextureStitchEvent.Pre`, `ModelBakeEvent` |
| `onItemModel`, `onBlockModel`, `onArmorTexture` | swap an item model, a block model (chunk threads!), an armor texture | — |
| `onHideEntity`, `onGhostEntity` | do not draw an entity / draw it as a ghost | `RenderLivingEvent.Pre` (cancel) |
| `onPlayerList`, `onPlayerListName`, `onNametag` | the Tab list, a name in it, the name above a head | — |
| `onPage(page)`, `configScreen(parent)` | your page on the Panel (Right Shift) / your config screen | `IModGuiFactory` |

The Javadoc of each method (in `stein-api-sources.jar`) says what it costs and what to watch for. Anything marked
`@Experimental` may still change in a minor Loader version; the rest of the API does not break mods built against
it.

## Services

| Class | What for |
|---|---|
| `Keys.bind(name, key, category)` | a key of your mod in the Controls screen, keeping the player's choice; `consumeClick()`, `isDown()`, `hold(true)` (e.g. a sneak toggle with `Keys.game(GameKey.SNEAK)`). Also the raw keyboard and mouse: `isDown(key)`, `isShiftDown()`, `isMouseDown(b)`, and the current mouse event (`mouseButton`, `mousePressed`, `mouseWheel`) |
| `Hud.register(element)` | a `HudElement` on the HUD grid: the player moves, scales and toggles it; you size and draw it |
| `Page`, `Option` | your options on the Panel (`onPage`): toggles, cycles, colours, actions, lists, screens, sliders (`Option.slider`), key pickers (`Option.key`) |
| `Panel.open(parent, page)` | open the Panel on your page, e.g. from a button |
| `Net.sendPayload(channel, bytes)` | a plugin message to the server |
| `Net.set(proxy)` | route the connection (proxies) |
| `Mods.isLoaded(id)`, `Mods.version(id)` | other mods |
| `Inventory` | read the player inventory and the open window; move items with real clicks (`move`, `quickMove`, `swapHotbar`, `drop`, `equip`, `unequip`, raw `click`), open or closed inventory; slot rules (`accepts`, `room`, `isCraftingSlot`, `stacksWith`) — see below |
| `Targeting` | who and what is on the crosshair line (`raycast`, every entity in order, the block that stops it; `raycast(query)` for longer looks with filters); plus the game's own `attack`, `swing` and `interact` |
| `Entities` | health, max health, absorption, armour and hurt time of any entity; `self()` is you; the players in the world (`players`, `player(nick)`), `name`, `distance`, `isInvisible`; every loaded entity (`all`), its `kind` (player, hostile, passive), `typeName`, and its position and yaw for the frame being drawn (`x`, `y`, `z`, `yaw` with `partialTicks`) |
| `Stacks` | item stacks: `create("diamond_sword", 1, damage)` for previews, `id`, `is(stack, "bow")`, `name`, `count`, `damage` / `maxDamage`, `sameKind`; `Inventory.count(s -> Stacks.is(s, "arrow"))` sums the inventory |
| `Effects` | active potion effects as API values (`Effects.of(entity)`), their translated `name` with the level and `durationText` |
| `Terrain` | the loaded world for maps and radars: `height`, `isAir` / `isSolid` / `isWater`, `skyLight`, `hasSky`, spawners, and `color(x, y, z)` — the block as a detailed minimap paints it (texture average with the biome tint) — or the game's `mapColor` |
| `ModContext.of(this)` | your mod's id, version, `log()`, `folder()`, `config(Type.class)` and `secrets()` — like Spigot's `JavaPlugin` |
| `ModConfig<T>` | settings in `config/<id>.json`: field-by-field load (a bad value falls back to its default only), atomic save, `saveSoon()` |
| `Secrets` | passwords and tokens outside the JSON; on Windows protected by the system (DPAPI) |
| `Log.of(id)` | `[id] message` in the game log; `debug` with `-Dstein.debug=<id>` |
| `Tasks`, `Http` | work off the game thread and back on it (`async`, `later`, `onGameThread`); `Http.getText` |
| `Relations`, `Players` | who is ally / enemy / in your group, for every mod — see below; the Tab list, teams, groups, ping; `Players.list()` reads the whole Tab list in one pass (use it instead of asking nick by nick when drawing a list), plus the Tab `header`/`footer` and the list score |
| `Render`, `WorldRender`, `Mesh`, `Blend` | 2D and 3D drawing without touching OpenGL — see Render and Drawing below (3D, lines, meshes and blend modes: Loader 0.1.24); also polygon fans, repeating textures (`texture(w, h, true)` + `drawFan`), items with your own count, effect icons, the Tab ping bars, player heads with the original skin on offline servers, text wrapping and the GUI size |
| `Server`, `Account` | the current server (normalised address, `connect`, `connectingAddress`), the session (offline nick, launcher account; never the token) |
| `Lang.tr(key, args)` | your `assets/<id>/lang/*.lang` in the player's language (English as fallback); also `ctx.tr(...)` |
| `Chat` | a line in the chat only for the player (`print`), the action bar, sending as the player (`send`, goes through `onSendChat`), reading and making text components |
| `Screens` | which screen is open (`kind`: menu, multiplayer, disconnected, inventory, container…), its size, and the game's buttons (`button`, `buttonId`, `setLabel`) for `onGuiInit` / `onGuiAction` |
| `Game.fps()`, `fpsLimit()`, `refreshRate()` | the F3 FPS, the frame limit and the monitor refresh rate |
| `Game.partialTicks()`, `Game.itemData(stack)`, … | small helpers — see the Javadoc |

Everything added in Loader library **0.1.16** (players get it with Stein Loader 0.1.23) is `@Experimental`: a mod using it
sets `"loader": "0.1.16"`.

### Your mod's context

```java
public final class MyMod implements SteinMod {
    static final class Config { boolean enabled = true; String color = "c"; }

    private final ModContext ctx = ModContext.of(this);
    private final ModConfig<Config> config = ctx.config(Config.class);   // config/mymod.json

    @Override
    public void afterStartGame() {
        ctx.log().info("enabled: " + config.get().enabled);
    }

    @Override
    public void onShutdown() {
        config.save();   // or config.saveSoon() on every change; onShutdown catches the last second
    }
}
```

### Relations

One place, shared by all mods, for who is who. It holds data and events only: nothing here protects, hides or paints
anyone — that is up to whoever asks.

- **Ask:** `Relations.of(nick or entity)` → `SELF`, `GROUP` (your own group), `ALLY`, `ENEMY`, `NEUTRAL` or `NONE`
  (nobody says anything). `Relations.color(relation)` is the colour the player chose for it.
- **Say:** `Relations.setPlayer(source, nick, relation)`, `setGroup(source, name, relation)` — `source` is usually your
  mod id, so `clear(source)` removes only what is yours. A group is any name (clan, team, guild).
- **Decide by yourself:** `Relations.register(source, (nick, group) -> relation or null)`. `Players.groupResolver(source,
  nick -> group, "" for none, or null)` tells what group a player is in on a server with its own convention.
- `onRelationsChanged()` arrives at the end of a tick in which anything changed.

The official Combat mod is a provider (its lists, clans, URL feeds and colours); the HUD only asks.

### Render

```java
Render.roundRect(x, y, 120, 20, 4, 0xC0202020);
Render.text("Hello", x + 6, y + 6, 0xFFFFFFFF, true);
Render.item(Inventory.heldItem(), x + 100, y + 2, true);
Render.Texture map = Render.texture(128, 128);    // your own pixels: map.set(...), map.draw(...), map.release()
```

Shapes, text, images from resources, items, player heads, push/translate/scale/rotate, clipping and textures made by the
mod. The Stein renderer will change underneath (towards Vulkan); code drawing through `Render` keeps working, code calling
OpenGL, `GlStateManager` or `Tessellator` directly will not.

### Drawing

> The drawing API below (lines, outlines, gradients, arcs, meshes, blend modes and all of `WorldRender`) is new in the
> Loader library **0.1.24** and still `@Experimental`: a mod using it sets `"loader": "0.1.24"`.

**2D, in `Render`** (HUD elements, `onOverlay`, screens). Besides shapes, text, items and textures, it draws
`Render.line` and `Render.polyline` (thick lines, a chart or a path), `Render.rectOutline`, `Render.gradient`
(vertical or horizontal), `Render.arc` (cooldowns, progress rings), `Render.mesh` and `Render.blend(Blend, () -> ...)`.
`Render.Texture.smooth` picks linear instead of nearest filtering. Colors are ARGB; `Render.alpha(argb, 0.5f)` scales the alpha.

**3D, in `WorldRender`**, called from `onRenderWorld`. You give **world coordinates** (the Loader handles the camera and
`partialTicks`; `WorldRender.cameraX/Y/Z`, `distance` and `partialTicks` are there if you need them) and **sizes in
blocks**. `push(x, y, z)` / `rotate` / `scale` / `pop` open a local space, so a shape can be built around its own origin.
`blockOutline`, `entityOutline` and `boxOutline` draw edges; `box`, `block`, `entityBox`, `quad`, `triangle`, `circle`,
`ring` and `beam` draw solids; `label` (text) and `image` (an icon) always face the camera. Normally things hide behind
blocks; wrap the call in `WorldRender.throughWalls(() -> ...)` for markers that must never hide.

```java
@Override
public void onRenderWorld(float pt) {
    // The block you look at, outlined through walls, with a label over it.
    WorldRender.throughWalls(() -> {
        WorldRender.blockOutline(x, y, z, 0.02, 0xFF55FFFF);
        WorldRender.label("Chest", x + 0.5, y + 1.4, z + 0.5, 1, 0xFFFFFFFF);
    });
}
```

**`Mesh`**: geometry built once and drawn many times, in 2D (`Render.mesh`) or 3D (`WorldRender.mesh`), at any
transform. Primitives: `LINES`, `LINE_STRIP`, `TRIANGLES`, `TRIANGLE_STRIP`, `TRIANGLE_FAN`, `QUADS`. Formats: `COLOR`,
`TEXTURE`, `TEXTURE_COLOR`. Build it outside the drawing events (when the mod starts, or when its content changes), never
every frame, and call `release()` when you are done with it.

```java
Mesh arrow = Mesh.builder(Mesh.Primitive.TRIANGLES, Mesh.Format.COLOR)
        .vertex(0, 1, 0).color(0xFFFF5555)
        .vertex(-0.5, 0, 0).color(0xFFFF5555)
        .vertex(0.5, 0, 0).color(0xFFFF5555)
        .build();
// later, in onRenderWorld: WorldRender.mesh(arrow, x, y, z);
```

**`Blend`** modes, for `Render.blend` and `WorldRender.blend`: `NORMAL`, `ADDITIVE` (glows, beams), `MULTIPLY`
(darken), `SCREEN` (lighten softly).

**Not supported:** custom shaders, stencil, points, GL line width (use `Render.line` or `Render.polyline`), your own display
lists or VBOs, and direct OpenGL, `GlStateManager` or `Tessellator` calls — under Stein GFX they may draw nothing.

### Inventory

`Inventory`, `Targeting`, `Entities` and the events next to them are new in the Loader library **0.1.15** (players get it with Stein Loader 0.1.22) and still `@Experimental`: a mod using them sets `"loader": "0.1.15"`.

Two ways to point at a place. A **slot** is the slot number in the *current window* — the open chest, furnace or
crafting table, or, with none open, the player's own inventory (window 0, which exists even with the inventory
closed); it is what the game's click uses, and it changes from window to window. An **inventory index** is the fixed
position in the player's inventory: 0-8 hotbar, 9-35 main, 36-39 armour (36 boots … 39 helmet). `slotOf(index)` and
`inventoryIndexOf(slot)` translate.

Reading is free. Every action (`move`, `equip`, `click`…) is a real click (`PlayerControllerMP.windowClick`): the
server gets the same packet a player's click sends. Keep actions for what the player asked for, one at a time — a
button, a key — and not for automating play: many servers forbid that, and they watch click speed.

```java
// How many arrows you carry, next to the bow on the HUD (a HudElement's layout/draw).
int arrows = Inventory.count(s -> Stacks.is(s, "arrow"));
Render.item(Stacks.create("arrow", 1, 0), 0, 0, false, String.valueOf(arrows));
```

### Targeting

`Targeting.pointedEntity()` is what the game is aiming at. `Targeting.raycast()` lists every entity on the crosshair
line within attack reach (3 blocks, 6 in creative), nearest first, and `Ray.blockDistance` is where the line hits a
block (with `blockId` and `blockX/Y/Z`: which block, and where). For looking farther, `raycast(query)` takes options:
a reach, `living()` for living entities only, a bigger `margin` around each entity (easier to point at something far
away), blocks the line goes through (`passThrough(Targeting.FOLIAGE)`, `Targeting.GLASS`, or your own ids), and
`cutAtBlock()`.

```java
// "What am I looking at": name and health of the living thing on the crosshair, up to 32 blocks, seeing through leaves.
Targeting.Hit hit = Targeting.raycast(Targeting.query()
        .reach(32).living().margin(0.3).passThrough(Targeting.FOLIAGE)).first();
if (hit != null) {
    String line = Entities.displayName(hit.entity) + " §c" + Math.round(Entities.health(hit.entity)) + " ❤";
    Render.text(line, 0, 0, 0xFFFFFFFF, true);
}
```

### Damage

`onEntityHurt` fires when anyone you can see takes a hit — it is the server's "hurt" signal, the one behind the red
flash — and `onHealthChanged` tells how much, from the health the server syncs (yours always; others' only if the
server sends it — many servers send full health for everyone). `Entities` reads health, absorption and armour.

```java
// Damage indicator: remember each hit, draw it over the entity for a second (in onRenderWorld).
@Override
public void onHealthChanged(Object entity, float oldHealth, float newHealth, float oldAbs, float newAbs) {
    float damage = (oldHealth + oldAbs) - (newHealth + newAbs);
    if (damage > 0) {
        hits.add(new Hit(entity, damage, System.currentTimeMillis()));
    }
}
```

### Talking to another mod

All mods share one class loader. Declare the other mod in `depends` (or `optional`), put a jar of its API classes in
your `libs/` (built with readable names, like your own code — only for compiling, it is not copied into your mod),
and call it directly. With `optional`, check `Mods.isLoaded("theirid")` before touching any of its
classes: a class is only looked up when the code using it runs.

## Rules of the road

- **Stay fast.** Some events run for every entity or item every frame (`onHideEntity`, `onItemModel`, …): answer
  quickly and do not allocate there. `onBlockModel` runs on the chunk-building threads, several at once: no shared
  mutable state without care, and no OpenGL. The F3 screen shows what each mod costs per frame ("Mod time"), counting
  its per-frame events and its HUD elements — keep yours low.
- **Draw through `Render`, `WorldRender` and `Mesh`**; direct OpenGL, `GlStateManager` and `Tessellator` calls are not
  supported. Stein GFX, the default renderer, runs the game in an OpenGL 4.1 core context: a mod that calls old,
  fixed-function OpenGL (`glBegin`/`glVertex`, display lists) is left out of the game, with the reason in the mod list,
  and `GlStateManager` or `Tessellator` drawing may show nothing. The fixed-function pipeline is only a fallback for
  older graphics cards.
- **Save your settings** with `ModContext.of(this).config(Settings.class)`: it lands in `config/<your-id>.json` and survives a bad value.
- **Be honest with servers.** Most servers forbid anything that plays for the player or gives a combat advantage (automated clicks, extended reach, hitting through others). Build things that show information or make the game nicer to use. The Loader tells the server which mods are loaded.

## Troubleshooting

- **"reference(s) to the game that do not exist"** at build: a name that is not in 1.8.9, or a typo. The message
  names the class and member.
- **Mod greyed out in the list:** the reason is next to it — missing dependency, Loader too old, broken manifest, old
  OpenGL calls with Stein GFX.
- **Mod failed to load:** the reason is in the list, the stack trace in `logs/latest.log`.
- **Missing an event?** Tell us where in the game you need to hook in and what the Forge equivalent is. Events are how
  the Loader grows.

## Building the SDK

The SDK packs the Stein Loader API, so it is built after the Loader. Clone
[bridge](https://github.com/Stein-Project/bridge), [loader](https://github.com/Stein-Project/loader) and this
repository side by side, build the Loader (see its README), then:

```sh
./gradlew build            # build/sdk/stein-sdk.jar and build/sdk/stein-mod-template.zip
./gradlew templateTest     # the template, the examples and test/apitest built with the new jar (command line)
./gradlew pluginTest       # the template built by its own Gradle wrapper with the plugin from build/maven-repo
./gradlew publishPlugin    # the Gradle plugin and stein-api into build/maven-repo (-PsteinRepo=<folder>)
```

You need a JDK 17 or newer to start Gradle (`JAVA_HOME`); Gradle finds or downloads the JDK 25 it runs and compiles
with. The first `templateTest` downloads Minecraft 1.8.9 from Mojang and the MCP mappings into `build/sdk-home`.
[`test/apitest`](test/apitest) is an in-game test of the whole API (see its README).

## Contributing

Issues and pull requests are welcome. Read the [contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md)
first. Contributions require signing the [Contributor License Agreement](https://github.com/Stein-Project/.github/blob/main/CLA.md).

## Licence

The Stein SDK, the Stein Loader API, the template and the examples are licensed under the [MIT License](LICENSE).
Third-party notices are in [NOTICE](NOTICE). The Stein name and logo are not covered by the licence: see the
[trademark policy](TRADEMARK.md).

Stein is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.

Created by [Breno Xavier](https://github.com/brenoxavier).
