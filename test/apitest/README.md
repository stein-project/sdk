# API Test — an in-game test of the Loader API

A mod that exercises the Stein Loader API in a running game, written the way an outside developer writes one:
readable names, built by the Stein SDK. It is a test, not an example to copy — see
[`examples/damage-indicator`](../../examples/damage-indicator) and the [template](../../template) for that.

Once you join a server it runs a sequence of steps, one every half second: items given with `/give`, armour put on and
taken off, items moved with the inventory closed and open, a click cancelled by `onWindowClick`, raycasts (plain and
with `Targeting.query`), a manual hit, damage to you and to a summoned villager, `ModConfig`, `Secrets`, `Lang`,
`Relations`, `Server`, `Account`, `Tasks`, `Http`, Panel options, Java 25 language features, and drawing with `Render`,
`WorldRender` and `Mesh`. Each check is logged as `[apitest] OK ...` or `[apitest] FAILED ...` in `logs/latest.log`, and
the totals (`END: n ok, m failed`) after the test kicks itself from the server.

## Build

`./gradlew templateTest` at the root of this repository builds it with the new SDK, into
`build/sdk-test/apitest/build/APITest.steinmod`. With a released SDK:

```sh
java -jar stein-sdk.jar build      # in this folder -> build/APITest.steinmod
```

## Run

You need a Minecraft 1.8.9 server **you run yourself**, where your player is an operator (the test uses `/give`,
`/summon`, `/effect`, `/setblock`, `/tp` and `/kick`) — never a public server. Offline mode is fine. Stand on flat open
ground with nothing within a few blocks in front of you.

1. Put `APITest.steinmod` in the `mods` folder of the game (or, from the Loader repository, `./gradlew runClient
   -Pstein.mods=<folder with it>`).
2. Join the server and wait about 15 seconds.
3. Read the `[apitest]` lines in `logs/latest.log`.

Some checks depend on the server: the relation checks between players need a second player in the Tab list (they are
skipped otherwise), and `Http.getText` needs internet access.
