# Distribution

Google Play material for AnkiMCP, the Android app for AnkiDroid. Nothing here is read by the app build.

| Path | What |
|---|---|
| `whatsnew/whatsnew-en-US` | Release notes for the current version (under 500 characters). `release.yml` uploads every `whatsnew-<BCP 47 locale>` file in `whatsnew/` with the AAB (r0adkll/upload-google-play reads only files named like that, directly in the directory; subdirectories such as `en-US/default.txt` are ignored). Rewrite it for each release. |
| `play/listing.md` | Store listing: app name, short and full description, category, tags, contact, website, privacy policy. |
| `play/data-safety.md` | Data safety form answers, traced to the code. |
| `play/permissions.md` | Every permission in the merged manifest, the special-use foreground service declaration text, the AnkiDroid permission explanation. |
| `play/testing.md` | Closed test (12 testers / 14 days): tester instructions, forum post, Play Console steps, content rating answers. |
| `play/graphics/icon-512.png` | Hi-res icon, 512x512, full-bleed navy (Play applies its own mask). |
| `play/graphics/feature-graphic-1024x500.png` | Feature graphic. |
| `play/graphics/screenshots/` | Phone screenshots, 1080x2412: `1-status.png` (email replaced by `you@example.com`), `2-log.png`, `3-about.png`. |

## Still needs a human

- **Final screenshots.** The current ones are interim: taken on a debug build (the Status
  screen shows the dev URL `tunnel.ankimcp.uk`), the status bar is cluttered, the Log screen
  is empty, and the email on the Status screen was painted over. Retake on a release build
  signed in with a test account, connected, with a few log entries; add one of Claude
  answering "list my Anki decks" if possible.
- **Privacy policy** at https://ankimcp.ai/privacy/ must cover the Android app (FCM token,
  Google as push provider, deletion), matching `play/data-safety.md`.
- **Account deletion URL** on the dashboard (see `play/data-safety.md`, Deletion).
- **Relay claims**: confirm the relay does not store request/response bodies before marking
  deck data as ephemeral.
- **FGS demo video** for the special-use declaration (`play/permissions.md`).
- **Test account** for Play's App access section, and a decision on tester access to the
  AnkiDroid tunnel on the free plan (`play/testing.md`).
- **Production relay and push** must be live before the closed test starts.
