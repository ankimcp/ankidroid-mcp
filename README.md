# AnkiDroid MCP

AnkiDroid MCP lets Claude work with the AnkiDroid collection on your Android phone. The app
runs on the phone, reads AnkiDroid through AnkiDroid's official API (its ContentProvider), and
answers Claude's MCP requests that reach it through the AnkiMCP tunnel. The phone opens an
outbound connection only; no ports are opened on it. This version has one read-only tool,
`list_decks` (deck names, ids and today's due counts).

<!-- Screenshots: Status, Log and About tabs, side by side. -->

## Requirements

- Android 8.0 (API 26) or newer.
- [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki), set up (opened
  once), with its API enabled (Settings > Advanced > "Enable AnkiDroid API", on by default).
- An [AnkiMCP](https://ankimcp.ai) account (the same one you use with the desktop add-on or
  CLI).
- For on-demand wake-ups: Google Play services on the phone.

## Install

- Google Play: coming soon.
- GitHub: download `ankidroid-mcp-<version>.apk` and `SHA256SUMS.txt` from
  [Releases](https://github.com/ankimcp/ankidroid-mcp/releases), check the file, then open
  the APK on the phone:

  ```sh
  shasum -a 256 -c SHA256SUMS.txt
  ```

  The Play and GitHub builds are signed with different keys, so one cannot be installed over
  the other; uninstall first when switching.

## Setup

1. Open **AnkiDroid MCP** and grant access to AnkiDroid when asked.
2. Tap **Sign in**, open the sign-in page, check that it shows the same code, and approve.
   On Android 13+ allow notifications too, or you won't see when Claude wakes the phone.
3. On the **Status** tab, tap **Connect**.
4. In Claude, open Settings > Connectors > Add custom connector, paste the MCP server URL from
   the Status tab (**Copy URL**), and sign in with the same AnkiMCP account. Then ask Claude
   to "list my Anki decks".

**Connection modes.** *On demand* (the default) lets Claude wake the phone with a push message
and disconnects when the screen turns off or after 15-60 idle minutes; *Always connected* keeps
the tunnel open and uses more battery.

**Battery.** Realme, Oppo and OnePlus (ColorOS), Xiaomi, Vivo and Huawei/Honor phones freeze
background apps even while they show a notification. Allow background activity, and set
battery optimisation to "Don't optimise", for both **AnkiDroid MCP** and **AnkiDroid**. The
Status tab names the setting for your phone.

## What leaves your phone

- Claude's MCP requests and the app's responses to them (for example your deck names and due
  counts), through the AnkiMCP tunnel. AnkiMCP does not store deck data beyond the request in
  flight.
- Your AnkiMCP account token, to authenticate the tunnel connection.
- An FCM registration token, sent to AnkiMCP so it can wake the phone. Sign out removes it.

See the [AnkiMCP privacy policy](https://ankimcp.ai/privacy/).

## Troubleshooting

Open the **Log** tab and tap **Share log** (or **Copy**). The shared text has the app version,
Android version, phone model and connection mode, but not your email, and anything that looks
like a token is removed. Post it with a short description on
[forum.ankimcp.ai](https://forum.ankimcp.ai/).

## Building from source

Needs JDK 17+ and an Android SDK with platform 36 (`sdk.dir` in `local.properties` or
`ANDROID_HOME`).

```sh
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android lint
./gradlew :app:assembleRelease        # minified; unsigned without the signing variables (docs/RELEASING.md)
```

`app/google-services.json` (Firebase) is optional and not in the repo. Without it the build
prints `app/google-services.json not found: building without Firebase push`, the app shows
"Push not configured in this build", and on demand works like a manual connection.

Point a debug build at another relay, e.g. a local one from the emulator:

```sh
./gradlew :app:assembleDebug -Pankimcp.tunnelUrl=ws://10.0.2.2:3004
```

Release builds always use `wss://tunnel.ankimcp.ai`. Cleartext `ws://` is only allowed to
`10.0.2.2`, `localhost` and `127.0.0.1`.

## Contributing / development

CI (`.github/workflows/ci.yml`) runs unit tests, lint and a debug build on every push to `main`
and every pull request, without `google-services.json`; the debug APK and the test and lint
reports are attached to the run. Releases are tag-driven and signed in CI; see
[docs/RELEASING.md](docs/RELEASING.md).

## Links

- [ankimcp.ai](https://ankimcp.ai), [dashboard](https://web.ankimcp.ai),
  [forum](https://forum.ankimcp.ai/), [service status](https://status.ankimcp.ai/)
- [docs/PROTOCOL.md](docs/PROTOCOL.md): tunnel protocol and push registration contract
- [docs/DECISIONS.md](docs/DECISIONS.md): design decisions and known gaps
- [docs/RELEASING.md](docs/RELEASING.md): versioning, releases, verifying an APK

## Licence

[AGPL-3.0](LICENSE). `FlashCardsContract.kt` is vendored from AnkiDroid under its own
all-permissive notice, and the runtime libraries are Apache-2.0; see [NOTICE](NOTICE).
