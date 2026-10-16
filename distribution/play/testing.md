# Closed test plan

Personal developer accounts must run a closed test with **at least 12 testers opted in
continuously for at least 14 days** before they can apply for production access (Play Console
Help, "App testing requirements for new personal developer accounts"). Internal testing does
not count. Recruit 15-20 so drop-outs don't reset the clock: the 14 days count only while 12
are opted in at the same time.

## Blockers before day 1

- The production relay must serve `ankidroid.ankimcp.ai` (PROTOCOL.md: dev only so far).
  Release builds have that URL hard-coded; testers on a Play build cannot reach dev.
- Push wake-ups must be enabled server side, or On demand behaves like a manual connection
  and testers only see half the app.
- Testers need an AnkiMCP account that can use the AnkiDroid tunnel. Check whether the free
  plan allows it; if not, decide how testers get access (promo code, temporary plan).
- Testers who installed the GitHub APK must uninstall it first (different signing key).

## Tester instructions

Send this to every tester (email or forum DM) after they are on the list.

```
Thanks for testing AnkiDroid MCP. Takes about 10 minutes to set up, then just use it normally for two weeks.

1. Join the test: open https://play.google.com/apps/testing/ai.ankimcp.ankidroid on your phone (same Google account you gave me), tap "Become a tester", then install from the Play Store link on that page. If you installed the APK from GitHub before, uninstall it first.
2. Make sure AnkiDroid is installed and opened once. In AnkiDroid: Settings > Advanced > "Enable AnkiDroid API" must be on (it is by default).
3. Open AnkiDroid MCP, allow access to AnkiDroid, tap Sign in, check the code matches in the browser and approve with your AnkiMCP account. On Android 13+ allow notifications.
4. On the Status tab tap Copy URL. In Claude (web or app): Settings > Connectors > Add custom connector, paste the URL, sign in with the same AnkiMCP account.
5. Things to try, and tell me what happened:
   a. Tap Connect, then ask Claude "list my Anki decks". Do the decks and due counts match AnkiDroid?
   b. Turn the screen off, wait 5 minutes, ask Claude again from another device (or the web). Does the phone wake up and answer? How long did it take?
   c. Reboot the phone, then ask Claude again.
   d. Leave the phone for a few hours with the screen off, ask Claude again. Note battery use in Android's battery settings.
   e. Tap Disconnect in the notification. Sign out and sign in again.
   f. If you have a Realme, Oppo, OnePlus, Xiaomi/Redmi/Poco, Vivo/iQOO or Huawei/Honor phone: follow the Battery card on the Status tab and tell me if it helped.
6. When something goes wrong: Log tab > Share log, and post it with what you did at https://forum.ankimcp.ai (the shared log has no email or tokens). 

Please keep the app installed and stay opted in for the full 14 days, even if you don't use it every day: Google counts opted-in testers per day.
```

## Forum post draft

Title: **Looking for 15 testers: Claude + AnkiDroid on Android (2 weeks)**

```
AnkiDroid MCP is a small open-source Android app that lets Claude read your AnkiDroid decks through your AnkiMCP account, the same way the desktop add-on does for Anki, but without a computer running. Version 0.1.0 has one read-only tool (deck names and today's due counts); more will follow.

Google requires 12 testers for 14 days before a new app can go on the Play Store, so I need your help.

You need:
- an Android phone (8.0+) with AnkiDroid
- an AnkiMCP account and Claude with a custom connector
- the Google account email you use on that phone

What you'd do: install from Play, set it up (about 10 minutes), ask Claude about your decks a few times over two weeks, try screen off and a reboot, and report anything odd here. Phones from Realme, Oppo, OnePlus, Xiaomi, Vivo and Huawei are especially welcome: they freeze background apps and I want to know how the app copes.

Reply here or DM me with the Google account email (or join the Google Group: <GROUP LINK>). Source: https://github.com/ankimcp/ankidroid-mcp
```

## Play Console steps, in order

1. **Create app** (All apps > Create app): name `AnkiDroid MCP`, default language en-US, App,
   Free. Accept the declarations (Developer Program Policies, US export laws).
2. **First upload, by hand.** Test and release > Testing > Internal testing > Create new
   release; enrol in Play App Signing; upload the AAB from the GitHub Release (or the
   `play-bundle` artifact of the release run) and roll out. The Play API cannot create an
   app, so `release.yml`'s play job fails with "Package not found" until this exists. From
   then on the workflow uploads to internal on every tag.
3. **Store listing** (Grow users > Store presence > Main store listing): paste from
   `listing.md`; upload `graphics/icon-512.png`, `graphics/feature-graphic-1024x500.png`,
   and the screenshots. Store settings: category Education, tags, contact email `support@ankimcp.ai`,
   website.
4. **App content** (Policy > App content), every item must be green:
   - Privacy policy: `https://ankimcp.ai/privacy/`
   - App access: "All or some functionality is restricted". Give a test AnkiMCP account
     (email + password) and steps: install AnkiDroid, open it once, open the app, grant the
     AnkiDroid permission, Sign in, approve the code in the browser with the test account,
     Connect. Without this review fails or stalls.
   - Ads: No, the app contains no ads.
   - Content rating: start the IARC questionnaire (below).
   - Target audience and content: 18 and over only. "Could your store listing unintentionally
     appeal to children?" No.
   - News app: No. Government app: No. Financial features: none. Health: none.
   - Advertising ID: No, the app does not use the advertising ID (no ads or analytics SDK;
     the manifest does not request `com.google.android.gms.permission.AD_ID`).
   - Data safety: from `data-safety.md`.
   - Foreground service permissions: special use, from `permissions.md`, with the video link.
5. **Closed testing track** (Testing > Closed testing > the default "Closed testing - Alpha"
   track, or Create track):
   - Countries/regions: all, or at least where testers are.
   - Testers: add both
     - an email list (Create email list, paste the addresses, comma separated), and
     - a Google Group (e.g. `ankidroid-mcp-testers@googlegroups.com`, set "Who can join" to
       "Anyone can ask" or "Anyone on the web can join"), entered by its address.
   - Feedback URL or email: `https://forum.ankimcp.ai`.
   - Create release: "Add from library" with the build already uploaded to internal (or
     promote the internal release), release notes from `distribution/whatsnew/whatsnew-en-US`,
     Save > Review > Start rollout to Closed testing. Then Publishing overview > Send for review.
6. **Share the opt-in link** once review passes (Closed testing > Testers > "Copy link"):
   `https://play.google.com/apps/testing/ai.ankimcp.ankidroid`. Testers must open it with the
   listed Google account and tap "Become a tester".
7. **Keep it going for 14 days.** Watch Dashboard > "Closed test" progress (testers opted in,
   days). Ship at least one update during the test if anything is fixed (counts as "changes
   made based on feedback").
8. **Apply for production** (Dashboard > Apply for production). Answers to prepare: how easy
   recruiting was, whether testers used all features (they can: one tool, both modes),
   whether usage matched expected production use, a summary of feedback and how it was
   collected (forum thread + shared logs), the target audience, the value proposition,
   expected installs in year one, changes made after the test, and how readiness was judged.

## Content rating questionnaire (IARC)

Category: **All other app types** (it is a utility, not a game, not primarily
reference/news/education content).

| Question | Answer |
|---|---|
| Violence, blood, fear | No |
| Sexuality, nudity | No |
| Language (profanity, crude humour) | No |
| Controlled substances (drugs, alcohol, tobacco) | No |
| Gambling, simulated gambling | No |
| Can users interact or exchange content with each other? | No (no user-to-user features; Claude is not another user) |
| Does the app share the user's current physical location with other users? | No |
| Does the app allow users to purchase digital goods? | No (no in-app purchases; AnkiMCP plans are bought on the web) |
| Does the app contain or allow unrestricted internet access (browser/search)? | No (it only opens the AnkiMCP sign-in page) |
| Is the app primarily a news or educational product? | No |
| Does the app contain user-generated content shown to others? | No |
| Generative AI content generated in the app? (if asked) | No. The app itself generates nothing; it answers data requests from the user's assistant |

Expected result: Everyone / PEGI 3 / IARC 3+. The 18+ target audience is separate from the
rating and stays.
