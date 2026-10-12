# Contributing to the Stein SDK

Read the [Stein contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first: it
covers the rules, code style, commits and the CLA. This page is what is specific to the SDK.

## Build and check

The SDK packs the Stein Loader API: the released one (`steinApiVersion` in `gradle.properties`, downloaded from the
Maven repository), or the Loader built next to this repository (`../loader/build/libs`, or `-PloaderDir=...`) when
there is one. The Stein Loader is not public, so contributors build against the released API:

```sh
./gradlew spotlessApply  # google-java-format --aosp on every Java file
./gradlew build          # build/sdk/stein-sdk.jar and the mod template, formatting check
./gradlew templateTest   # the template, the examples and test/apitest built with the new jar, and the result checked
./gradlew pluginTest     # the template built by its own Gradle wrapper with the plugin from build/maven-repo
./gradlew publishPlugin  # the Gradle plugin into build/maven-repo (-PsteinRepo=<folder>)
```

You need a JDK 25 or newer; Gradle finds or downloads the JDK 25 it runs and compiles with
(`gradle/gradle-daemon-jvm.properties`, regenerated with `./gradlew updateDaemonJvm --jvm-version=25`).

The Bridge and the Loader build with this repository's `NamedBuild` and `OpenJar` (as an included build): a change
to `Mappings`, `NamedBuild` or `OpenJar` also changes how they are built. Say so in the pull request: the maintainer
builds them before merging.

## What to keep in mind

- **Mod authors run this.** Error messages must say what is wrong and what to do about it.
- **Existing mods keep building.** The commands, their arguments, the files they write and the `steinmod.json` keys
  are what mod projects rely on: add, do not rename.
- **Nothing from Mojang or MCP goes into the jars.** `stein-sdk setup` downloads the game and the mappings on the
  developer's machine. `./gradlew publishPlugin` fails if a jar contains game files.
- **The template and the example are documentation.** Keep them small, commented and building with the latest
  release. When the API changes, update the example and the guide (`README.md`) in the same pull request, and set
  `"loader"` in their `steinmod.json` to the oldest Loader library version that has everything they use.
  `test/apitest` runs the whole API in game; extend it when you add to the API.
- **Everything here is MIT-licensed**, so mods can use any licence. New files use the MIT header. The template's
  sources carry no header on purpose: they are the first files of someone else's mod.

## Releases

Push a `v<version>` tag matching `Sdk.VERSION`, with `changelog/<version>.en_US.md` (and optionally `.pt_BR.md`) committed: the
release workflow builds, runs the tests and publishes `stein-sdk.jar`, `stein-mod-template.zip` and the guide.
The template, the example and the guide pin the plugin version: change them with `Sdk.VERSION`.

The release goes to the public distribution repository `Stein-Project/stein-sdk`: the files on its release, the guide,
the examples, `LICENSE` and `NOTICE` on its `main` branch, and the Gradle plugin and `stein-api` on its `maven` branch.
The Gradle plugin downloads `stein-sdk.jar` from those releases and the template reads the plugin from that `maven`
branch (`SdkTool.RELEASES`, `template/settings.gradle`, the examples, the guide); move them together. Plugins and
templates published before the move read `x4vieer/stein-sdk`, which is frozen: they keep the versions already
there, and new versions go only to `Stein-Project/stein-sdk` (whose `maven` branch started as a copy of the old one).
