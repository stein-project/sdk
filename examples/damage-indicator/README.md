# Damage Indicator — example mod

A small, complete Stein Loader mod on the (experimental) API of Stein Loader 0.1.23 (library 0.1.16). Read it top to bottom: it is
short, and every part is something you will want in your own mod.

| Feature | API used |
|---|---|
| Damage numbers over any entity that loses health, rising and fading | `onHealthChanged`, `onRenderWorld` |
| The last hit you took, as a HUD element the player moves and scales | `HudElement`, `Hud.register`, `Entities` |
| A key that turns the numbers on and off, with a note on the action bar | `Keys`, `Screens`, `Chat.actionBar` |
| A Panel page: toggle, colour, **slider** (duration), **key picker** | `onPage`, `Option.slider`, `Option.key` |
| Settings saved in `config/damageindicator.json` | `ModContext.config` (`ModConfig`) |

## Build

```sh
./gradlew installMod               # builds build/libs/DamageIndicator.steinmod and copies it into .minecraft/mods
```

Or without Gradle:

```sh
java -jar stein-sdk.jar setup      # once
java -jar stein-sdk.jar install    # builds build/DamageIndicator.steinmod and copies it into .minecraft/mods
```

In game: turn on **Last hit taken** in the HUD editor, and open the mod's page on the Panel (Right Shift).

## Notes

- Other players' health reaches you only if the server sends it: many servers send full health for everyone, and then
  only your own hits have numbers.
- It only shows things: nothing it does changes what the game sends to the server.

MIT licensed, like the API: copy anything you like.
