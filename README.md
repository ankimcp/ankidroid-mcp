# AnkiDroid MCP

An Android app that lets an AI assistant (Claude) read your AnkiDroid collection.

It signs in to your AnkiMCP account, opens an outbound WebSocket to the AnkiMCP tunnel
relay, and answers MCP requests that the relay forwards from Claude. Your phone needs no
open ports; the relay exposes `https://tunnel.ankimcp.ai/mcp` and routes requests for your
account to the phone.

Phase 1 exposes one read-only tool, `list_decks` (deck names, ids and today's due counts),
read through AnkiDroid's official ContentProvider.

## Requirements

- Android 8.0 (API 26) or newer, with [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki)
  installed and set up (opened once).
- An AnkiMCP account (the same one used with the desktop add-on or CLI).

## Build

Needs JDK 17+ and an Android SDK with platform 36 (`sdk.dir` in `local.properties` or
`ANDROID_HOME`).

```sh
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android lint
./gradlew :app:assembleRelease        # minified, unsigned
```

Point a debug build at another relay (e.g. a local tunnel on the emulator host):

```sh
./gradlew :app:assembleDebug -Pankimcp.tunnelUrl=ws://10.0.2.2:3004
```

Release builds always use `wss://tunnel.ankimcp.ai`. Cleartext `ws://` is only allowed to
`10.0.2.2`, `localhost` and `127.0.0.1`.

The app targets API 36 (Android 16), as Google Play requires; see docs/DECISIONS.md (D1).

## Use it

1. Install the APK and open **AnkiDroid MCP**.
2. Onboarding: confirm AnkiDroid is installed and grant access to its database. Optionally turn
   off battery optimisation for the app so the connection survives Doze.
3. Sign in: tap **Sign in**, then **Open sign-in page**. Check that the page shows the same code,
   approve, and the app continues automatically.
4. Tap **Connect**. A persistent notification shows the tunnel status. Turn on
   **Keep connected** if you want the app to reconnect on launch and after reboot, and to keep
   retrying every 30 seconds instead of giving up. **Disconnect** (in the app or the
   notification) turns Keep connected off again.

## Connect Claude

1. In claude.ai, open **Settings > Connectors > Add custom connector**.
2. Enter the URL shown on the app's Status screen: `https://tunnel.ankimcp.ai/mcp`
   (there's a Copy button).
3. Complete the AnkiMCP sign-in when Claude asks, using the same account as the app.
4. With the phone connected, ask Claude "list my Anki decks".

If the desktop add-on or CLI is connected with the same account, whichever connected last wins
(the other one is closed with "Connected from another device").

## Docs

- [docs/PROTOCOL.md](docs/PROTOCOL.md): wire protocol as implemented, and how it differs from the add-on.
- [docs/DECISIONS.md](docs/DECISIONS.md): design decisions and known gaps.
- [NOTICE](NOTICE): third-party notices (vendored `FlashCardsContract.kt`).
