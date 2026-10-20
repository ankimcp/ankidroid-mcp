# Store listing (en-US)

Paste the text inside each block as is. Character counts were checked with `wc -m` on the
block contents (Play counts characters, not bytes).

## App name (max 30)

```
AnkiMCP
```

7 characters. Matches `app_name` in the app, so the launcher label and the listing agree.

Decided (D60): Play's metadata and impersonation policy looks at other apps' names in titles,
and "AnkiDroid" is another app's name (an independent open-source project, not ours). The
earlier title "AnkiDroid MCP" could read as AnkiDroid's own product, so the title is our own
name only, and "AnkiDroid" appears only in the descriptions, where they say which app it works
with. Keep "Claude" out of the title for the same reason; naming it in the description is fine.

## Short description (max 80)

```
Let Claude read and edit your AnkiDroid cards via the AnkiMCP tunnel or Wi-Fi.
```

## Full description (max 4000)

```
AnkiMCP connects Claude to the AnkiDroid collection on your phone. Claude reads and edits it straight through AnkiDroid, without a computer running Anki.

How it works
The app uses AnkiDroid's official API and answers Claude's requests that reach it through the AnkiMCP tunnel. The phone only opens an outgoing connection; no ports are opened on it (unless you turn on the Wi-Fi server below). Your collection stays in AnkiDroid: nothing is copied to a server, and only the answer to each request leaves the phone.

What Claude can do
Read decks and due counts; search and read notes; add, edit and delete notes; create decks; manage tags; flag, suspend, bury and move cards; work with note types; study with you and record your ratings; ask you to sync (you tap a notification). Each change is read back from AnkiDroid to check it.

What you need
- AnkiDroid 2.24 or newer, opened once, with its API enabled (Settings > Advanced > Enable AnkiDroid API; on by default).
- An AnkiMCP account (https://ankimcp.ai), the same one you use with the desktop add-on. You sign in once in the browser with a short code; the app never sees your password.
- Claude with a custom connector: open Settings > Connectors > Add custom connector, paste the MCP server URL the app shows (Copy URL), and sign in with the same AnkiMCP account.
- Android 8.0 or newer. Google Play services for on-demand wake-ups.
- Optional: Plus or Pro, monthly subscriptions sold through Google Play in the app.

No account? Use it on your Wi-Fi
The app can also work entirely on your Wi-Fi, for free and without an account: it serves MCP directly to Claude Desktop or Claude Code on a computer on the same network. Turn on "Local server (Wi-Fi)" on the Local tab and share the address the app shows to the computer. It is open to anyone on that Wi-Fi unless you turn on "Require access token", and uses plain HTTP without encryption, so use it on a network you trust. Claude on the web can't reach your phone this way. While it is on, the app shows a notification; turn battery optimisation off for the app (the Battery card links to the setting), because nothing can wake the phone in this mode.

How it connects
When Claude needs your decks and the phone is not connected, AnkiMCP sends a push message and the app connects. It disconnects again when the screen turns off, or after 15, 30 or 60 minutes without requests (you choose). Claude's next request wakes the phone again. Nothing runs in the background between requests.
While connected, a notification with a Disconnect button is shown.

Battery and background limits
Realme, Oppo and OnePlus (ColorOS), Xiaomi, Redmi and Poco (MIUI/HyperOS), Vivo and iQOO, and Huawei and Honor phones freeze background apps, even ones that show a notification. When that happens, Claude's requests arrive late or not at all. On these phones, allow background activity and set battery optimisation to "Don't optimise" for both AnkiMCP and AnkiDroid. The app shows where the setting is on your phone.

Privacy
- Claude's requests and the app's answers (for example deck names and due counts) pass through the AnkiMCP tunnel. AnkiMCP does not store deck data beyond the request in flight. What Claude does with the answer is covered by your agreement with Anthropic.
- A push token is registered with AnkiMCP so it can wake the phone. Signing out removes it.
- No ads, no analytics, no crash reporting.
Privacy policy: https://ankimcp.ai/privacy/

Problems?
Open the Log tab and tap Share log. The shared text includes the app version, Android version and phone model, but not your email, and anything that looks like a token is removed. Post it with a short description at https://forum.ankimcp.ai. Service status: https://status.ankimcp.ai

Open source
AnkiMCP is free software under the GNU AGPL-3.0. Source code: https://github.com/ankimcp/ankidroid-mcp

AnkiMCP for AnkiDroid is made by the AnkiMCP project and is not affiliated with or endorsed by AnkiDroid, Anki or Anthropic. Claude is a trademark of Anthropic.
```

## Store settings

| Field | Value |
|---|---|
| App or game | App |
| Free or paid | Free |
| In-app purchases | Yes: subscriptions `ankimcp_plus` and `ankimcp_pro`, base plan `monthly`, no free trial (D62). The listing then carries Play's "In-app purchases" label; check it appears on the store page preview once the subscriptions are active in Play Console (Monetize > Subscriptions), before submitting. |
| Category | Education |
| Tags (pick up to 5 in Play Console > Store presence > Store settings; Play only offers its own tag list) | Flashcards, Study tools, Learning tools, Productivity, Education. Pick the closest available ones; the list is fixed by Google. |
| Contact email | support@ankimcp.ai (Play shows it publicly) |
| Contact phone | leave empty |
| Website | https://ankimcp.ai |
| Privacy policy | https://ankimcp.ai/privacy/ |
| Default language | English (United States) – en-US |

Before submitting, check that https://ankimcp.ai/privacy/ covers the Android app explicitly:
the FCM registration token, Google as push provider, Google Play purchases (the purchase token
sent to AnkiMCP), and the deletion path. It must match
data-safety.md, or review flags the mismatch.

## Graphics

| Asset | File | Play requirement |
|---|---|---|
| App icon | `graphics/icon-512.png` | 512x512 PNG, 32-bit, max 1 MB |
| Feature graphic | `graphics/feature-graphic-1024x500.png` | 1024x500 PNG or JPEG, no alpha |
| Phone screenshots | `graphics/screenshots/*.png` | 2-8, PNG/JPEG, 320-3840 px per side, 16:9 to 9:16 |
