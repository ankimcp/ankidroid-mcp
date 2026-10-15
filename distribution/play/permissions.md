# Permissions and Play declarations

Permissions in the merged release manifest
(`app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`,
built 2026-10-15). The first eight are declared in `app/src/main/AndroidManifest.xml`; the last
two come from libraries.

| Permission | Kind | Why | Play declaration |
|---|---|---|---|
| `INTERNET` | normal | WebSocket to the AnkiMCP relay, sign-in, push registration | none |
| `ACCESS_NETWORK_STATE` | normal | `NetworkMonitor`: wait for a validated network instead of burning reconnect attempts; reconnect when the default network changes | none |
| `FOREGROUND_SERVICE` | normal | Required for any foreground service | none (covered by the type below) |
| `FOREGROUND_SERVICE_SPECIAL_USE` | normal, Play-reviewed | `TunnelForegroundService`, type `specialUse` | **Foreground service declaration** (below) |
| `POST_NOTIFICATIONS` | runtime (Android 13+) | The foreground service notification (with Disconnect) and "Sign in again to AnkiMCP". Asked after sign-in; the app works without it, the user just does not see when Claude wakes the phone | none |
| `RECEIVE_BOOT_COMPLETED` | normal | `BootReceiver`: in Always connected mode only, restart the tunnel after a reboot or app update (also WorkManager re-schedules its jobs after boot) | none |
| `WAKE_LOCK` | normal | Short partial wake locks around reconnects, liveness checks from while-idle alarms, and finishing in-flight requests on screen off (`AndroidWakeLocks`); also used by FCM and WorkManager | none |
| `com.ichi2.anki.permission.READ_WRITE_DATABASE` | runtime (AnkiDroid's own, dangerous level) | Read decks through AnkiDroid's ContentProvider | none in Play Console; explain in the listing (done) |
| `com.google.android.c2dm.permission.RECEIVE` | normal (from firebase-messaging) | Receive FCM wake-up messages | none |
| `ai.ankimcp.ankidroid.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | signature (from androidx.core) | Internal guard for non-exported runtime receivers; no user impact | none |

Not requested, on purpose: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (D42: Play forbids it for
apps that can use high-priority FCM), `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` (alarms are
inexact while-idle), any location, storage or contacts permission.

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
AnkiDroid MCP lets the user's AI assistant (Claude) read the user's AnkiDroid flashcard decks on the phone. AnkiDroid only exposes the collection through a local ContentProvider, so the data cannot be fetched from a server: the phone has to answer each request itself. TunnelForegroundService keeps one outbound WebSocket open to the AnkiMCP relay (wss://ankidroid.ankimcp.ai). The relay forwards the assistant's requests over this connection; the app reads AnkiDroid and sends the answer back on the same socket. No ports are opened on the phone.

The service starts in two ways: (1) the user taps Connect in the app, or (2) in the default "On demand" mode, the AnkiMCP server sends a high-priority FCM data message when the user's assistant makes a request and the phone is not connected. While it runs, a notification shows the connection state and a Disconnect action.

The service stops when the user taps Disconnect or signs out; in On demand mode also after a user-chosen idle period (15, 30 or 60 minutes) without requests, and as soon as the screen turns off. Only in the user-selected "Always connected" mode does it stay up (and restart after reboot) until the user disconnects.

None of the other foreground service types fit: this is not data sync (nothing is uploaded or downloaded in bulk; the connection waits for requests that the user makes through the assistant), not media, location, connected device or phone call, and the work cannot be deferred to WorkManager or a job because each request must be answered within the assistant's ~25 second timeout.
```

**User impact if the task is deferred or interrupted by the system:**

```
The user's request to their assistant fails: Claude reports that it cannot reach the phone, or the request times out after about 25 seconds, and the user has to repeat it. If the connection is killed while idle, in On demand mode the next request wakes the phone again through a push message; in Always connected mode the assistant loses access until the app reconnects.
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
AnkiDroid MCP reads your decks through AnkiDroid's official API, which requires AnkiDroid's own "read and write the AnkiDroid database" permission. Android only offers it as one read-and-write permission. This version only reads: it lists your deck names and today's due counts and never changes your collection. Every read happens because your assistant asked for it while the app is connected. You can revoke the permission in Android Settings > Apps > AnkiDroid MCP > Permissions at any time.
```

No Play Console declaration exists for it (it is not a platform-restricted permission).
