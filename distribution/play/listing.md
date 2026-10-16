# Store listing (en-US)

Paste the text inside each block as is. Character counts were checked with `wc -m` on the
block contents (Play counts characters, not bytes).

## App name (max 30)

```
AnkiDroid MCP
```

13 characters. Matches `app_name` in the app, so the launcher label and the listing agree.

Risk to know about: Play's metadata and impersonation policy looks at other apps' names in
titles. "AnkiDroid" is another app's name (an independent open-source project, not ours). If
review objects, a fallback that keeps the meaning is `AnkiMCP for AnkiDroid` (21) — the
"for <app>" form is the usual accepted pattern for companion apps. Keep "Claude" out of the
title for the same reason; naming it in the description is fine.

## Short description (max 80)

```
Let Claude read your AnkiDroid decks, using your AnkiMCP account.
```

## Full description (max 4000)

```
AnkiDroid MCP connects Claude to the AnkiDroid collection on your phone. Ask Claude about your decks and it reads them straight from AnkiDroid, without a desktop computer running Anki.

How it works
The app talks to AnkiDroid through AnkiDroid's official API and answers Claude's requests that reach it through the AnkiMCP tunnel. The phone only opens an outgoing connection; no ports are opened on it. Your collection stays in AnkiDroid: nothing is copied to a server, and only the answer to each request leaves the phone.

What Claude can do in this version
One read-only tool: list your decks with their names and today's new, learning and review counts. The app cannot change your collection yet. More tools will come in later versions.

What you need
- AnkiDroid installed and opened at least once, with its API enabled (AnkiDroid Settings > Advanced > Enable AnkiDroid API; on by default).
- An AnkiMCP account (https://ankimcp.ai), the same one you use with the desktop add-on. You sign in once in the browser with a short code; the app never sees your password.
- Claude with a custom connector: in Claude, open Settings > Connectors > Add custom connector, paste the MCP server URL the app shows (Copy URL), and sign in with the same AnkiMCP account.
- Android 8.0 or newer. Google Play services for on-demand wake-ups.

How it connects
When Claude needs your decks and the phone is not connected, AnkiMCP sends a push message and the app connects. It disconnects again when the screen turns off, or after 15, 30 or 60 minutes without requests (you choose). Turning the screen on does not reconnect; Claude's next request wakes the phone again. Nothing runs in the background between requests.
While connected, a notification with a Disconnect button is shown.

Battery and background limits
Realme, Oppo and OnePlus (ColorOS), Xiaomi, Redmi and Poco (MIUI/HyperOS), Vivo and iQOO, and Huawei and Honor phones freeze background apps, even ones that show a notification. When that happens, Claude's requests arrive late or not at all. On these phones, allow background activity and set battery optimisation to "Don't optimise" for both AnkiDroid MCP and AnkiDroid. The Status screen tells you where the setting is on your phone.

Privacy
- Claude's requests and the app's answers (for example deck names and due counts) pass through the AnkiMCP tunnel. AnkiMCP does not store deck data beyond the request in flight. What Claude does with the answer is covered by your agreement with Anthropic.
- A push token is registered with AnkiMCP so it can wake the phone. Signing out removes it.
- No ads, no analytics, no crash reporting.
Privacy policy: https://ankimcp.ai/privacy/

Problems?
Open the Log tab and tap Share log. The shared text includes the app version, Android version and phone model, but not your email, and anything that looks like a token is removed. Post it with a short description at https://forum.ankimcp.ai. Service status: https://status.ankimcp.ai

Open source
AnkiDroid MCP is free software under the GNU AGPL-3.0. Source code, releases and design notes: https://github.com/ankimcp/ankidroid-mcp

AnkiDroid MCP is made by the AnkiMCP project and is not affiliated with or endorsed by AnkiDroid, Anki or Anthropic. Claude is a trademark of Anthropic.
```

## Store settings

| Field | Value |
|---|---|
| App or game | App |
| Free or paid | Free |
| Category | Education |
| Tags (pick up to 5 in Play Console > Store presence > Store settings; Play only offers its own tag list) | Flashcards, Study tools, Learning tools, Productivity, Education. Pick the closest available ones; the list is fixed by Google. |
| Contact email | support@ankimcp.ai (Play shows it publicly) |
| Contact phone | leave empty |
| Website | https://ankimcp.ai |
| Privacy policy | https://ankimcp.ai/privacy/ |
| Default language | English (United States) – en-US |

Before submitting, check that https://ankimcp.ai/privacy/ covers the Android app explicitly:
the FCM registration token, Google as push provider, and the deletion path. It must match
data-safety.md, or review flags the mismatch.

## Graphics

| Asset | File | Play requirement |
|---|---|---|
| App icon | `graphics/icon-512.png` | 512x512 PNG, 32-bit, max 1 MB |
| Feature graphic | `graphics/feature-graphic-1024x500.png` | 1024x500 PNG or JPEG, no alpha |
| Phone screenshots | `graphics/screenshots/*.png` | 2-8, PNG/JPEG, 320-3840 px per side, 16:9 to 9:16 |
