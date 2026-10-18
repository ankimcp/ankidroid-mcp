# AnkiMCP

AnkiMCP for AnkiDroid lets Claude work with the AnkiDroid collection on your Android phone. The app
runs on the phone, reads AnkiDroid through AnkiDroid's official API (its ContentProvider), and
answers Claude's MCP requests that reach it through the AnkiMCP tunnel. The phone opens an
outbound connection only; no ports are opened on it. Without an account it can instead serve
MCP directly on your Wi-Fi to Claude Desktop or Claude Code on a computer next to it (see
[Use on your Wi-Fi without an account](#use-on-your-wi-fi-without-an-account)); only then
does it open a port. Its tools are named and shaped like the
[desktop add-on](https://github.com/ankimcp/anki-mcp-server-addon)'s where AnkiDroid's API can
do the same:

| Tool | What it does |
|---|---|
| `list_decks` | Deck names, ids, filtered flag; with `include_stats`, today's due counts |
| `create_deck` | Creates a deck (up to `Parent::Child`) |
| `find_notes` | Note ids for an Anki search, paged; optionally each note's first field |
| `notes_info` | Fields, tags, note type, card ids and modification time of up to 100 notes |
| `add_note`, `add_notes` | Adds one note, or up to 20, to a deck, with a duplicate check |
| `update_note_fields`, `update_notes` | Replaces or patches note fields (up to 20 notes) |
| `delete_notes` | Deletes up to 20 notes and their cards, after a confirmation flag |
| `model_names`, `model_field_names`, `model_templates`, `model_styling` | Note types, their fields, card templates, CSS and LaTeX preamble |
| `create_model`, `update_model_styling`, `update_model_templates`, `model_field_add` | Creates a note type, changes its CSS or templates, appends a field |
| `tag_list`, `tag_add`, `tag_remove`, `tag_replace`, `tag_batch` | Lists tags; adds, removes or replaces tags on up to 20 notes |
| `card_bury`, `card_suspend`, `card_set_flag`, `card_change_deck` | Buries, suspends, flags (AnkiDroid 2.25+) or moves up to 20 cards |
| `get_due_cards` | The next card the scheduler would show for a deck; can bury cards with images or audio |
| `present_card`, `rate_card` | A card's rendered question (and answer on request); records your rating |
| `cards_stats` | Per-card type, queue, interval and tags for a deck, 200 cards per page |
| `get_card_memory_state` | Stored FSRS stability, difficulty and retrievability of up to 100 cards |
| `get_fsrs_params` | FSRS parameters, desired retention and maximum interval of each preset |
| `filtered_deck_get_info` | Name, filtered flag and card count of decks by id |
| `sync` | Posts a "Tap to sync AnkiDroid" notification; the sync starts when you tap it |

Every change is checked by reading it back from AnkiDroid. It also serves the add-on's
`twenty_rules` prompt and `anki://query-syntax` resource, and a `review_session` prompt adapted
for the phone. Tools that open AnkiDroid screens, handle media, or change something AnkiDroid's
API cannot change (unsuspend, unbury, reschedule, filtered decks, FSRS settings) are not offered;
see docs/DECISIONS.md D57 and D58.

<!-- Screenshots: Status, Log and About tabs, side by side. -->

## Requirements

- Android 8.0 (API 26) or newer.
- [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki) 2.24 or newer, set
  up (opened once), with its API enabled (Settings > Advanced > "Enable AnkiDroid API", on by
  default). Older AnkiDroid versions only get `list_decks`; every other tool asks you to
  update.
- An [AnkiMCP](https://ankimcp.ai) account (the same one you use with the desktop add-on or
  CLI), unless you only use the app on your Wi-Fi.
- For push wake-ups: Google Play services on the phone.

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

1. Open **AnkiMCP** and grant access to AnkiDroid when asked.
2. On the **Status** tab, under **Remote**, tap **Sign in**, open the sign-in page, check
   that it shows the same code, and approve.
   On Android 13+ allow notifications too, or you won't see when Claude wakes the phone.
3. On the **Status** tab, under **Remote**, tap **Connect**. The dot before "Remote"
   is green when the tunnel is connected, amber while it connects and grey when it is off;
   the dot before "Local" does the same for the local Wi-Fi server.
4. In Claude, open Settings > Connectors > Add custom connector, paste the MCP server URL from
   the Status tab (**Copy URL**), and sign in with the same AnkiMCP account. Then ask Claude
   to "list my Anki decks".

**How it connects.** Claude wakes the phone with a push message when it needs your decks; the
connection closes when the screen turns off or after 15, 30 or 60 idle minutes (your choice on
the Status tab), and the next request wakes the phone again. There is no "always connected"
mode.

**Battery.** Realme, Oppo and OnePlus (ColorOS), Xiaomi, Vivo and Huawei/Honor phones freeze
background apps even while they show a notification. Allow background activity, and set
battery optimisation to "Don't optimise", for both **AnkiMCP** and **AnkiDroid**; each
app has its own page (on ColorOS: App info > Battery usage > "Allow background activity").
Without it for AnkiMCP, Claude's requests time out with the screen off; without it for
AnkiDroid, `list_decks` reports that AnkiDroid was asleep. The Status tab names the setting
for your phone and opens both App info pages.

## Use on your Wi-Fi without an account

The app can serve MCP itself, over HTTP on your local network, to Claude Desktop or Claude
Code on a computer on the same Wi-Fi. No AnkiMCP account, no tunnel, free. Claude on the web
(claude.ai) cannot reach a phone on your LAN, so this is for the desktop apps only.

**Security: open by default, plain HTTP, no TLS.** By default the server needs no password:
anyone on the same Wi-Fi can use it to read and change your collection. Turn on **Require access token** on the Local server card to accept
only requests that carry the token the app generates. Either way requests and answers (your
deck names, note contents, and the token if you use one) cross your Wi-Fi unencrypted. The
server only listens on the phone's private Wi-Fi address, and always rejects requests whose
`Host` or `Origin` is not the phone (against DNS-rebinding attacks from web pages, with or
without a token). Use it on a network you trust, such as your home Wi-Fi, not on public
Wi-Fi.

1. On the **Status** tab, under **Local**, turn on **Local server (Wi-Fi)**. No sign-in
   needed.
2. The Local server card shows the URL, e.g. `http://192.168.1.20:8765/mcp` (and the access
   token, if you require one). **Share** sends them to the computer (through any app on the
   phone's share sheet); **Copy** puts one on the clipboard. "Connect to Wi-Fi" means the
   phone is not on Wi-Fi. The server only listens on a private (home-network) address; on a
   Wi-Fi that gives the phone a public internet address it stays off and the card says so.
3. On the computer:
   - Claude Code (put in the URL the card shows):

     ```sh
     claude mcp add --transport http ankidroid http://192.168.1.20:8765/mcp
     # with "Require access token" on:
     claude mcp add --transport http ankidroid http://192.168.1.20:8765/mcp --header "Authorization: Bearer <token>"
     ```

   - On a Mac, allow the app that runs Claude Code or mcp-remote (Terminal, iTerm, the Claude
     app) under System Settings > Privacy & Security > **Local Network**. Until then
     Node-based clients get `EHOSTUNREACH`, while Apple's `curl` is exempt, which is why
     `curl` reaches the phone and Claude Code does not.

   - Claude Desktop: its config file only starts local programs, so it reaches the phone
     through [mcp-remote](https://www.npmjs.com/package/mcp-remote), which needs Node.js. Add
     this to `claude_desktop_config.json` (Settings > Developer > Edit Config) and restart
     Claude Desktop:

     ```json
     {
       "mcpServers": {
         "ankidroid": {
           "command": "npx",
           "args": ["-y", "mcp-remote", "http://192.168.1.20:8765/mcp", "--allow-http",
                    "--transport", "http-only"]
         }
       }
     }
     ```

     With **Require access token** on, add `"--header", "Authorization:${AUTH_HEADER}"` to
     `args` and `"env": { "AUTH_HEADER": "Bearer <token>" }`.

4. Ask Claude to "list my Anki decks".

The port (8765 by default, the same as AnkiConnect Android) can be changed on the card. If the
phone gets a new address (another Wi-Fi, a new DHCP lease), the server moves to it; update
the URL on the computer, or give the phone a fixed address in your router. With a token
required, **Regenerate** replaces it; computers set up with the old one stop working until
they get the new one.

While the local server is on, the app keeps a notification ("Local MCP server on
192.168.x.x:8765") with a **Stop local server** button. Nothing can wake the phone in this
mode, so the Status screen's Battery card asks you to turn battery optimisation off for the app. Do it: without it,
some phones stop the server from answering about 30 s after the screen turns off. On a
Realme phone (ColorOS 15) the app froze 30 s after screen-off and requests hung; with
battery optimisation off it still froze, but each incoming request woke it and was answered
in under a second. The setting takes effect the next time the phone pauses the app, so turn
it on before you rely on the server with the screen off. The server comes back when you open the app; it does not start by itself
after a reboot.

## Plans

The Plan card on Status > Remote (signed in) shows your AnkiMCP plan. On the free plan it
offers Plus and Pro, monthly Google Play subscriptions with the price Google Play shows in
your currency; with a Play subscription it shows when it renews or ends, lets you switch
between Plus and Pro, and links to Manage subscription in Google Play (cancel there). A plan
paid on the web or granted to your account is shown as such, with no purchase buttons; manage
it in your dashboard. If a payment is still pending (for example cash at a store), the plan
starts once Google Play confirms it, also if the app was closed in between. Details:
docs/DECISIONS.md D62, docs/PROTOCOL.md.

## What leaves your phone

- Claude's MCP requests and the app's responses to them (for example your deck names, due
  counts, and the fields and tags of notes Claude looks up), through the AnkiMCP tunnel.
  AnkiMCP does not store collection data beyond the request in flight.
- Your AnkiMCP account token, to authenticate the tunnel connection.
- An FCM registration token, sent to AnkiMCP so it can wake the phone. Sign out removes it.
- If you subscribe in the app: the Google Play purchase token, sent to AnkiMCP so it can
  verify the purchase with Google and activate your plan. Payment details stay with Google
  Play.
- With the local server on: the same MCP answers, sent over your Wi-Fi straight to the
  computer that asked, unencrypted; nothing goes to AnkiMCP.

See the [AnkiMCP privacy policy](https://ankimcp.ai/privacy/).

## Troubleshooting

Open the **Log** tab and tap **Share log** (or **Copy**). The shared text has the app version,
Android version, phone model, idle window and whether push wake-ups are registered, but not your email, and anything that looks
like a token is removed. Post it with a short description on
[forum.ankimcp.ai](https://forum.ankimcp.ai/).

### Sharing a detailed log

The Log tab only shows the last 100 events since the app started. The app also keeps a log
file on the phone (about 2 MB at most, never uploaded) that survives restarts, so a problem
from last night is still there in the morning.

1. If you can reproduce the problem, turn on **Detailed logging** on the Log tab first. It
   adds debug lines and this app's own Android system log lines, and turns itself off after
   24 hours.
2. Reproduce the problem (or wait for it, e.g. overnight).
3. Tap **Share detailed log** and pick where to send it (email, a chat app, Drive). The app
   writes one text file and hands it to the app you pick; it sends nothing by itself.

The file starts with the app and AnkiDroid versions, your phone model and Android version, and
your settings (idle window, local server, battery and notification state). Your email, tokens
and anything token-shaped are removed. It does not contain your phone's own system messages:
Android does not let apps read them, and on Realme, Oppo, OnePlus, Xiaomi, Vivo and Huawei
phones those are where the battery freezer says what it did. So when you post it, say which
phone you have and roughly when the problem happened. The shared copy is replaced by your next
share and deleted when the app starts an hour or more after it was made.

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
"Push not configured in this build", and the app works like a manual connection.

Point a debug build at another relay, e.g. the development deployment, or a local one from the
emulator:

```sh
./gradlew :app:assembleDebug -Pankimcp.tunnelUrl=wss://ankidroid.ankimcp.uk
./gradlew :app:assembleDebug -Pankimcp.tunnelUrl=ws://10.0.2.2:3004
```

Release builds always use `wss://ankidroid.ankimcp.ai`. Cleartext `ws://` is only allowed to
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
all-permissive notice, and the runtime libraries are Apache-2.0 except NanoHTTPD (BSD
3-Clause); see [NOTICE](NOTICE).
