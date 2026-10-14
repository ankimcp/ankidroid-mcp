# AnkiDroid MCP

An Android app that lets an AI assistant (Claude) read your AnkiDroid collection.

It signs in to your AnkiMCP account, opens an outbound WebSocket to the AnkiMCP tunnel
relay, and answers MCP requests that the relay forwards from Claude. Your phone needs no
open ports; the relay exposes `https://tunnel.ankimcp.ai/mcp` and routes requests for your
account to the phone. By default the phone does not keep that connection open: the server
wakes it with a push message when Claude needs it.

Phase 1 exposes one read-only tool, `list_decks` (deck names, ids and today's due counts),
read through AnkiDroid's official ContentProvider.

## Requirements

- Android 8.0 (API 26) or newer, with [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki)
  installed and set up (opened once).
- An AnkiMCP account (the same one used with the desktop add-on or CLI).
- For push wake-ups: Google Play services on the phone, and a build that includes the Firebase
  config (below).

## Build

Needs JDK 17+ and an Android SDK with platform 36 (`sdk.dir` in `local.properties` or
`ANDROID_HOME`).

```sh
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android lint
./gradlew :app:assembleRelease        # minified, unsigned
```

### Firebase config (push wake-ups)

`app/google-services.json` (Firebase project `ankimcp-e2d35`) is not in the repo and is
gitignored. It is stored in Bitwarden; fetch it with the Bitwarden CLI (signed in and
unlocked):

```sh
bw get notes "Firebase ankimcp-e2d35 google-services.json (AnkiDroid MCP)" > app/google-services.json
```

Without the file the build still works: Gradle prints
`app/google-services.json not found: building without Firebase push`, the app shows
"Push not configured in this build", and on-demand mode falls back to connecting manually.

### Dev relay

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
   approve, and the app continues automatically. On Android 13+ the app then asks to show
   notifications; allow it, or you won't see when Claude wakes the phone.
4. Pick a connection mode on the Status screen (see below). **Connect** opens the tunnel right
   away; a persistent notification shows its status while it is open.

## Connection modes

- **On demand** (default): when Claude makes a request and the phone is not connected, the
  AnkiMCP server sends a push message, the app connects (the notification appears), answers,
  and disconnects again after 15, 30 (default) or 60 minutes without requests. Connecting by
  hand works the same way. The Status screen shows whether push is registered and, while
  connected, when the idle disconnect will happen. If push is not available (no Firebase config
  in the build, or the phone's current push token is not registered with the server), on demand
  behaves like a manual connection that stays open until you tap **Disconnect**.
- **Always connected**: the previous "Keep connected" behaviour. The app connects on launch and
  after a reboot, never disconnects when idle, and keeps retrying every 30 seconds if the
  connection keeps failing. **Disconnect** (in the app or the notification) switches back to on
  demand.

If your session expires while the phone is asleep, a wake-up shows a "Sign in again to
AnkiMCP" notification instead of connecting. **Sign out** removes the phone's push
registration from the server first.

## Connect Claude

1. In claude.ai, open **Settings > Connectors > Add custom connector**.
2. Enter the URL shown on the app's Status screen: `https://tunnel.ankimcp.ai/mcp`
   (there's a Copy button).
3. Complete the AnkiMCP sign-in when Claude asks, using the same account as the app.
4. Ask Claude "list my Anki decks". In on-demand mode the first request may take a few seconds
   longer while the phone wakes up and connects.

If the desktop add-on or CLI is connected with the same account, whichever connected last wins
(the other one is closed with "Connected from another device").

## Docs

- [docs/PROTOCOL.md](docs/PROTOCOL.md): wire protocol as implemented, how it differs from the
  add-on, and the push registration contract.
- [docs/DECISIONS.md](docs/DECISIONS.md): design decisions and known gaps.
- [NOTICE](NOTICE): third-party notices (vendored `FlashCardsContract.kt`).
