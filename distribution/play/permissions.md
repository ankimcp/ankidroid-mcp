# Permissions and Play declarations

Permissions in the merged release manifest
(`app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`,
built 2026-10-15, rechecked 2026-10-18 after the local Wi-Fi server;
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` was added for it and removed again the same day, D56).
Eight are declared in
`app/src/main/AndroidManifest.xml`; `RECEIVE_BOOT_COMPLETED` and the last two come from
libraries.

| Permission | Kind | Why | Play declaration |
|---|---|---|---|
| `INTERNET` | normal | WebSocket to the AnkiMCP relay, sign-in, push registration; the local Wi-Fi server's listening socket | none |
| `ACCESS_NETWORK_STATE` | normal | `NetworkMonitor`: wait for a validated network instead of burning reconnect attempts; reconnect when the default network changes | none |
| `FOREGROUND_SERVICE` | normal | Required for any foreground service | none (covered by the type below) |
| `FOREGROUND_SERVICE_SPECIAL_USE` | normal, Play-reviewed | `TunnelForegroundService`, type `specialUse` | **Foreground service declaration** (below) |
| `POST_NOTIFICATIONS` | runtime (Android 13+) | The foreground service notification (with Disconnect) and "Sign in again to AnkiMCP". Asked after sign-in; the app works without it, the user just does not see when Claude wakes the phone | none |
| `RECEIVE_BOOT_COMPLETED` | normal (from androidx.work) | Not declared by the app. WorkManager's `RescheduleReceiver` re-schedules the push-registration retry, daily check and token-delete jobs after a reboot. The app itself does nothing at boot and never starts the tunnel then | none |
| `WAKE_LOCK` | normal | Short partial wake locks around reconnects, liveness checks from while-idle alarms, and finishing in-flight requests on screen off (`AndroidWakeLocks`); also used by FCM and WorkManager | none |
| `com.ichi2.anki.permission.READ_WRITE_DATABASE` | runtime (AnkiDroid's own, dangerous level) | Read decks through AnkiDroid's ContentProvider | none in Play Console; explain in the listing (done) |
| `com.android.vending.BILLING` | normal (declared by the app and by the Play Billing Library) | Plus and Pro subscriptions through Google Play (D62). The library also adds `<queries>` for Play's billing service and its own non-exported `ProxyBillingActivity`; no location or other permission comes with it (merged release manifest checked) | none; the subscriptions are set up under Monetize in Play Console |
| `com.google.android.c2dm.permission.RECEIVE` | normal (from firebase-messaging) | Receive FCM wake-up messages | none |
| `ai.ankimcp.ankidroid.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | signature (from androidx.core) | Internal guard for non-exported runtime receivers; no user impact | none |

Not requested, on purpose: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (D42: Play forbids the
direct request for apps that can use high-priority FCM; the battery card only links to the
system settings, for the local Wi-Fi server too, D56), `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` (alarms are
inexact while-idle), any location, storage or contacts permission.

"Share detailed log" (D61) needs no permission: the app reads its own logcat lines (no
`READ_LOGS`), writes the file to its private cache and hands it to the app the user picks
through a non-exported androidx `FileProvider` with a one-time read grant for that one file.

## FOREGROUND_SERVICE_SPECIAL_USE declaration

Play Console > Policy > App content > Foreground service permissions. Select
"Special use" and fill in the fields below. The manifest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
already carries the same description (D37); reviewers compare the two and the app's behaviour,
so keep them in sync.

**Use case (free text):**

```
Persistent outbound connection that lets the user's AI assistant read their local AnkiDroid flashcard collection on request.
```

**Description of the app functionality that uses this foreground service:**

```
The AnkiMCP app lets the user's AI assistant (Claude) read and edit the user's AnkiDroid flashcard collection on the phone. AnkiDroid only exposes the collection through a local ContentProvider, so the data cannot be fetched from a server: the phone has to answer each request itself. TunnelForegroundService keeps one outbound WebSocket open to the AnkiMCP relay (wss://ankidroid.ankimcp.ai). The relay forwards the assistant's requests over this connection; the app reads or edits AnkiDroid and sends the answer back on the same socket. No ports are opened on the phone.

The service starts in two ways: (1) the user taps Connect in the app, or (2) the AnkiMCP relay server sends a high-priority FCM data message when the user's assistant makes a request and the phone is not connected. While it runs, a notification shows the connection state and a Disconnect action.

The service stops when the user taps Disconnect or signs out. When push wake-ups are set up (the normal case), it also stops as soon as the screen turns off and after a user-chosen idle period (15, 30 or 60 minutes) without requests; without them, a session the user started by tapping Connect stays up until Disconnect. It is never started at boot.

Optional local mode: when the user turns on "Local server (Wi-Fi)" (no account needed), the same service also answers the assistant's requests directly over HTTP on the phone's Wi-Fi address, for Claude Desktop or Claude Code on a computer on the same network. It then keeps running, with a notification showing the server's address and a "Stop local server" action, until the user turns the local server off. With the local server switched on, the server is started whenever the service starts: from the app in the foreground (turning the switch on, or opening the app after the process was killed), and also when the service is started by a push wake-up for the tunnel (path 2 above). It is never started at boot.

None of the other foreground service types fit: this is not data sync (nothing is uploaded or downloaded in bulk; the connection waits for requests that the user makes through the assistant), not media, location, connected device or phone call, and the work cannot be deferred to WorkManager or a job because each request must be answered within the assistant's ~25 second timeout.
```

**User impact if the task is deferred or interrupted by the system:**

```
The user's request to their assistant fails: Claude reports that it cannot reach the phone, or the request times out after about 25 seconds, and the user has to repeat it. If the connection is killed while idle, the next request wakes the phone again through a push message.
```

**Video link (required):** record a 30-60 s screen video (unlisted YouTube or Drive link)
showing, in order: open the app, signed in; tap Connect, the notification appears; ask
Claude "list my Anki decks" on another device, the answer comes back; tap Disconnect in the
notification, the notification goes away. If push is enabled server side by then, also show
the phone with the app disconnected, a Claude request, and the notification appearing on its
own (the push wake-up).

Review risk: Play's FGS policy favours user-initiated work. Start path (1) is plainly user
initiated. Path (2) is started by a server push, but only in response to a request the same
user made in Claude, and it is user-visible. Say exactly that if review pushes back; the
description above already does.

## AnkiDroid READ_WRITE_DATABASE

This is AnkiDroid's own permission (`com.ichi2.anki.permission.READ_WRITE_DATABASE`, declared
by AnkiDroid with the dangerous protection level), so Android shows a runtime prompt the first
time the app asks, worded by AnkiDroid's own permission label. It is
the only way a third-party app can use AnkiDroid's official API (`FlashCardsContract`), and
it does not exist unless AnkiDroid is installed (hence `<queries>` for `com.ichi2.anki`).

Text for anyone who asks (support, review notes):

```
AnkiMCP reads and edits your collection through AnkiDroid's official API, which requires AnkiDroid's own "read and write the AnkiDroid database" permission. Android only offers it as one read-and-write permission. Through it, Claude can read and edit your collection (add, update and delete notes; tags, flags and scheduling), only when you ask it to while the app is connected. Every change is confirmed by reading it back from AnkiDroid. You can revoke the permission in Android Settings > Apps > AnkiMCP > Permissions at any time.
```

No Play Console declaration exists for it (it is not a platform-restricted permission).
