# Contributing to the Stein SDK

Read the [Stein contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first: it
covers the rules, code style, commits and the CLA. This page is what is specific to the SDK.

## Build and check

With [loader](https://github.com/Stein-Project/loader) (and its [bridge](https://github.com/Stein-Project/bridge))
cloned next to this repository; the SDK reads `../loader/build/libs/stein-api.jar`, so build the Loader first
(`./gradlew build` there, or pass `-PloaderDir=...`):

```sh
./gradlew spotlessApply  # google-java-format --aosp on every Java file
./gradlew build          # build/sdk/stein-sdk.jar and the mod template, formatting check
./gradlew templateTest   # builds the template with the new jar and checks the result
./gradlew publishPlugin  # the Gradle plugin into build/maven-repo (-PsteinRepo=<folder>)
```

## What to keep in mind

- **Mod authors run this.** Error messages must say what is wrong and what to do about it.
- **Nothing from Mojang or MCP goes into the jars.** `stein-sdk setup` downloads the game and the mappings on the
  developer's machine. `./gradlew publishPlugin` fails if a jar contains game files.
- **The template and the example are documentation.** Keep them small, commented and building with the latest
  release. When the API changes, update the example and the guide (`README.md`) in the same pull request.
- **Everything here is MIT-licensed**, so mods can use any licence. New files use the MIT header.
