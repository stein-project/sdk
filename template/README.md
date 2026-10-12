# Example Mod

A starting point for a Stein Loader mod for Minecraft 1.8.9.

With Gradle (Java 25 or newer):

```sh
./gradlew build         # -> build/libs/ExampleMod.steinmod   (Windows: gradlew build)
./gradlew installMod    # build and copy into .minecraft/mods
```

Or with the SDK alone (JDK 25):

```sh
java -jar stein-sdk.jar setup      # once
java -jar stein-sdk.jar build      # -> build/ExampleMod.steinmod
java -jar stein-sdk.jar install    # build and copy into .minecraft/mods
```

To use it in IntelliJ IDEA, open this folder and let it import the Gradle project.

What it shows:

- `ExampleMod` — the entry point: a key binding (O), an outline around the block you look at (`onRenderWorld` with
  `WorldRender`), a client-side `/example` command (`onSendChat`) that reads a private game field directly, and options
  on the Panel.
- `FpsElement` — an element on the HUD grid (Right Shift to place it; new elements start turned off).
- `ExampleScreen` — a settings screen written like any `GuiScreen`, opened from the mod list's *Configure* button.

Rename the package, the `id` in `src/main/resources/steinmod.json`, the `assets/examplemod` folder and
`rootProject.name` in `settings.gradle` to your own.
The full guide is in the SDK's `README.md`.

The template is MIT-licensed, like the SDK: start your mod from it under any licence you like.
