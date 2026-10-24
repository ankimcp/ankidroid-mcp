# Decisions

Decisions made while building phase 1, the push wake-up feature after it (D31-D39), the
fixes after the on-device trials (D40-D44, D49, D51), the branding and UI round (D45-D48), the
removal of "Always connected" (D50), request dedupe (D52) and the local Wi-Fi server (D53-D56), without a chance to ask. Each one is cheap to revisit. D57 (the tool set), D58 (sync), D59 (Status tabs), D60 (app name), D61 (detailed log) and D63 (the Remote access switch) record owner decisions. D62 (Google Play Billing) follows
the server contract given by the coordinator.

## Target SDK

- **D1. compileSdk and targetSdk are 36 (Android 16)**, as Google Play requires for new apps
  and updates (Play target-API page, checked 2026-10-14). Phase 1 started on 35; moving to 36
  meant bumping the androidx pins (D3). Android 16 behaviour changes checked against this app:
  - Foreground services and alarms: no Android 16 change. The `specialUse` FGS is not one of
    the types Android 15 forbids from `BOOT_COMPLETED`; inexact while-idle alarms need no
    permission (D29).
  - Edge-to-edge opt-out removed: the app already calls `enableEdgeToEdge()`.
  - Predictive back on by default: the app has no custom back handling, so the system default
    (finish the single activity) applies.
  - Large screens (sw >= 600dp) ignore orientation/resizability restrictions: none are declared.
  - Local network permission: opt-in only so far; it may later matter for a dev relay on the LAN
    (not for `10.0.2.2`/loopback).
  - JobScheduler quota changes: the only jobs are WorkManager's push-registration retry and
    daily check (D34); tighter quotas can only delay them.
  Not verified on an Android 16 device or emulator yet.

## Build and stack

- **D2. AGP 8.13.2 + Gradle 8.14.3 + Kotlin 2.2.21**, not AGP 9.x. AGP 9 turns on built-in
  Kotlin and the new DSL by default; that migration is a separate task. AGP 8.13.2 is the
  newest 8.x and supports API 36. JDK 17 bytecode; builds with JDK 21.
- **D3. androidx pins** are the newest releases whose AAR metadata allows compileSdk 36 with
  AGP 8.13 (checked against `minCompileSdk` / `minAndroidGradlePluginVersion` on Google Maven):
  core-ktx 1.18.0, activity-compose 1.13.0, lifecycle 2.10.0, browser 1.10.0, Compose BOM
  2026.06.01 (ui 1.11.4, material3 1.4.0). core 1.19, lifecycle-*-compose 2.11 and Compose UI
  1.12 (BOM 2026.08+) require compileSdk 37 and AGP 9.1. `lifecycle-process` was declared for
  the auto-connect's `ProcessLifecycleOwner` (D20) and removed with it (D50). Firebase and
  WorkManager pins: D33.
- **D4. OkHttp 4.12.0** as the brief asked (5.x exists). MockWebServer 4.12.0 for tests.
- **D5. JUnit 4 + kotlinx-coroutines-test + MockWebServer.** No Robolectric: everything
  under test is plain Kotlin. `unitTests.isReturnDefaultValues = true` covers `android.util.Log`.
  Android-only pieces (Keystore cipher, wake locks, alarms, `elapsedRealtime`, SharedPreferences,
  Firebase, WorkManager, notifications) sit behind small interfaces so the logic around them is
  tested on the JVM.
- **D6. Manual DI** (`AppGraph`, created in `AnkiMcpApp`). One module, about 30 classes: Hilt
  would add a kapt/ksp step and annotation noise for no real benefit yet.
- **D7. Detekt, ktlint and explicit API mode are not configured.** Explicit API mode is for
  libraries; this is a single app module. Adding detekt/ktlint is a small follow-up.
- **D8. Tunnel URL**: `BuildConfig.TUNNEL_URL`. Debug builds read the Gradle property
  `ankimcp.tunnelUrl` (default `wss://ankidroid.ankimcp.ai`, the app's own relay deployment;
  dev is `wss://ankidroid.ankimcp.uk`); release is hard-coded to the production URL so a
  stray property can't ship. The desktop add-on stays on `wss://tunnel.ankimcp.ai`, which
  refuses the phone with 4011 once the AnkiDroid deployment ships (docs/PROTOCOL.md). `network_security_config.xml` allows
  cleartext only to `10.0.2.2`, `localhost` and `127.0.0.1` (dev relay over `ws://`); it is in
  the main source set, so release builds permit it too (harmless: release never uses those hosts).
- **D9. Release** has R8 + resource shrinking enabled and builds unsigned; signing config and
  Play App Signing setup are not part of phase 1.

## AnkiDroid access

- **D10. FlashCardsContract is vendored** from upstream commit `9b4bd952` (2026-10-03). The
  brief said it was LGPL; the file's header is actually an all-permissive notice ("Copying and
  distribution of this file ... are permitted in any medium without royalty"). The header is
  kept verbatim and NOTICE records the source. Changes: package renamed, `BuildConfig.AUTHORITY`
  / `READ_WRITE_PERMISSION` inlined with the release values (the debug AnkiDroid build uses
  `com.ichi2.anki.debug.flashcards` and is not supported).
- **D11. Provider quirks found by reading AnkiDroid's `CardContentProvider`:**
  - `DECK_COUNTS` is a JSON array `[learn, review, new]`; the tool reorders it.
  - `DECK_DYN` is put into a `MatrixCursor` as a Boolean, which crosses the binder as the
    string `"true"`/`"false"`. `getInt` would always read 0, so it's read as a string.
  - The deck query walks `deckDueTree()`; rows with id 0 or an empty name are skipped in case
    the root node is included.
  - Storage not configured surfaces as `IllegalStateException` whose message mentions storage /
    "not configured"; only those map to the "not ready" message, other `IllegalStateException`s
    are reported as a generic provider error with their message. Missing permission is
    `SecurityException`. All become `isError` tool results.
- **D12. `list_decks` output** (superseded by D57: the add-on's keys) follows the brief's shape `{id, name, counts:{new,learn,review}}`,
  wrapped in `{decks, total}` because MCP `structuredContent` must be an object. I added
  `is_filtered` (the add-on has it and `DECK_DYN` is free). It is *not* the add-on's shape
  (`deck_id`, `stats.new_count`, `include_stats`): the brief's explicit shape won over
  "copy the add-on as closely as possible". The description text is adapted from the add-on.
- **D13. Provider calls** run on a small bounded pool: up to 4 threads (core = max = 4, idle
  threads exit after 60 s via `allowCoreThreadTimeOut`) plus a FIFO queue of 8. Each call is
  awaited with a **20 s** timeout counted from submission, so time spent queued counts too (not
  25 s: the tunnel's own request budget is 25 s, and if both were 25 s the 504 would usually
  win and the user would see "Request timed out" instead of the readable tool error). Core =
  max because with a bounded queue `ThreadPoolExecutor` only starts threads above the core size
  once the queue is full, so calls would wait behind a stuck one while threads were free.
  - A burst of up to 12 concurrent calls is accepted (5 is the common case for a client
    firing tool calls in parallel); beyond that the call fails at once with "AnkiDroid is busy
    ... Try again in a moment".
  - Only a call that times out reports "AnkiDroid is not responding ... Restart AnkiDroid"
    (restarting AnkiDroid ends stuck binder transactions with `DeadObjectException`, which
    frees the threads). A queued call whose caller already timed out is skipped when it
    reaches a thread instead of querying AnkiDroid for nobody.
  - A binder call that never returns pins only its own thread. Calls are not serialised;
    AnkiDroid's provider is designed for concurrent clients, and only read-only queries exist
    so far.
- **D14. Onboarding gates the app** on "AnkiDroid installed" and "permission granted". You
  can't request the permission without AnkiDroid installed (the permission is defined by
  AnkiDroid), so sign-in is only offered afterwards. Battery optimisation is optional and only
  deep-links to settings; the app does not declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (D42;
  briefly declared for the local server, then removed again, D56). Sign-in is optional: past onboarding the
  Status screen shows both tabs, with Sign in on the Remote tab (D59).

## Auth and storage

- **D15. Credentials** are stored as the add-on's JSON, encrypted with AES-256-GCM under a
  non-exportable AndroidKeyStore key (`ankimcp_credentials_v1`), in a private
  SharedPreferences file. No user-authentication requirement on the key, since the tunnel must
  reconnect in the background. The blob is read lazily on first `load()`, never on the main
  thread: `AnkiMcpApp` triggers it on `Dispatchers.IO` and the UI shows a spinner until it
  finishes (the boot receiver that also read it is gone, D50). Only errors that mean the blob
  can never be decrypted (`AEADBadTagException`,
  `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, including a missing key;
  also when wrapped as a cause) delete it. Anything else (Keystore busy, I/O) is logged, the
  ciphertext is kept, the user looks signed out for now, and the next `load()` retries. Base64
  uses `java.util.Base64` (same output as Android's `NO_WRAP`). `allowBackup=false` plus
  data-extraction rules exclude everything from backup and device transfer (a restored blob
  could not be decrypted anyway). `commit()` (not `apply()`) is used deliberately so a rotated
  refresh token is on disk before it is used. Besides `replace` (compare-and-set save) the
  store has `clearIf` (compare-and-set clear), both under the store's lock.
- **D16. Token refresh** is serialised by a mutex in `TokenRefresher`; if another caller has
  already refreshed, the stored token is reused. Both outcomes of the HTTP call use a
  compare-and-set against the credentials the refresh started from: new tokens are saved with
  `CredentialStore.replace`, and `invalid_grant` clears with `CredentialStore.clearIf`. If the
  user logged out or signed in again while the call was in flight, nothing is saved or cleared
  and the result is whatever is stored now (`Refreshed` with the new sign-in, or `SignedOut`).
  A tunnel that finds no credentials stops with "Not signed in" without clearing anything, so
  a transient decrypt failure never escalates into deleting the blob.
- **D17. `Credentials.toString()` is redacted**, and nothing logs tokens or the account email;
  the in-app log is shown verbatim in the UI and mirrored to logcat. The same goes for the FCM
  token (`PushRegistrationState.toString()` is redacted too).

## Tunnel and service

- **D18. Deviations from the add-on's reconnect behaviour** (offline waiting, handshake close
  codes, auth-loop guard, network-change reconnect, device-flow retries, idle timeout) are listed in docs/PROTOCOL.md under "Deviations".
- **D19. Foreground service** type `specialUse` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  explanation. The type is passed to `startForeground` only on API 34+; on 26-33 the plain
  overload is used (passing an unknown type bit there can throw). The service only owns the
  process lifetime and the notification; the tunnel state machine lives in `AppGraph` so the
  UI can observe it directly. The service stops itself whenever the tunnel stops, including
  after an idle timeout (D32), unless the local Wi-Fi server is on (D56).
- **D20. Connect is user-initiated or a push wake-up**, never automatic. (The "Always
  connected" mode with auto-connect on launch, boot and app update, `BootReceiver` and the
  retry-every-30-s loop was removed in D50.) The service is `START_NOT_STICKY`, so a process
  kill does not silently resurrect a tunnel. Disconnect, in the app or from the notification
  action, ends the current session; the next wake-up connects again.
  - Android 12+ refuses `startForegroundService` from the background. Every start goes through
    `TunnelForegroundService.start`, which catches the refusal
    (`ForegroundServiceStartNotAllowedException`, an `IllegalStateException`, which is also
    what 26-30 throw), logs it to the tunnel log and shows the tunnel as stopped with "Could not
    start in the background. Open the app to connect." (unless a tunnel is already running).
    A high-priority FCM message is one of the allowed exemptions (D36).
- **D21. POST_NOTIFICATIONS** (API 33+) is requested right after sign-in succeeds and when the
  user taps Connect. Sign-in asks only if the permission is missing, and the request waits
  until the activity is started again (sign-in usually completes while the browser is in
  front). With push wake-ups nobody taps Connect, so
  asking only there left a woken session's notification invisible, silently dropped the "Sign
  in again" notification (D36), and FCM deprioritises high-priority messages that produce no
  visible notification. The tunnel starts whatever the answer is; without the permission the
  FGS runs with a hidden notification.
  - The Status screen shows whether notifications can be seen: the permission, the app's
    notification switch (`NotificationManagerCompat.areNotificationsEnabled()`) and the
    `tunnel` and `account` channels (importance not `IMPORTANCE_NONE`; a channel not created
    yet counts as on). When any is off, it says notifications are off and a button opens the
    app's notification settings (`ACTION_APP_NOTIFICATION_SETTINGS`), since Android stops
    showing the prompt after two denials.
  - Every notification that would not be seen leaves a tunnel-log line with the reason
    ("Notification permission is off", "Notifications are off for this app" or "This
    notification category is off", then ": ... not shown"): the "Sign in again" notification,
    and the tunnel status notification each time the service starts (`NotificationPermissionGate`).
- **D22. MCP URL** shown on the Status screen is the `url` from `tunnel_established` when
  connected, otherwise `https://<tunnel host>/mcp` derived from the configured WebSocket URL.
- **D27. Doze / CPU suspend.** A foreground service keeps the process and its network access,
  but the CPU still suspends, `System.nanoTime` stops, and coroutine timers do not fire. So:
  - All liveness timing (last server ping, health check) and the idle deadline (D32) use
    `SystemClock.elapsedRealtime()` via the `ElapsedClock` interface (tests inject a fake).
  - A `PARTIAL_WAKE_LOCK` (always with a timeout) is held during each connection attempt
    (refresh + open + handshake, 45 s cap, released on `tunnel_established`), during backoffs
    of up to 5 s, around a token refresh after an auth close, and around each relay request
    (25 s budget + 5 s). Requires `WAKE_LOCK`. The request lock is released from the request
    job's completion handler, so it is also released when the request is cancelled before its
    coroutine ever ran (socket dropped right after the request arrived).
  - Backoffs longer than 5 s release the CPU and schedule an alarm (D29); when it fires,
    `TunnelAlarmReceiver` takes a 10 s bridging wake lock and cuts the backoff short.
  - While connected, the same alarm fires every 60 s and re-runs the health check, so a socket
    that died silently (no more pings, so nothing wakes the CPU) is still detected in Doze. A
    check after a process freeze (not a CPU suspend) gets a grace period first (D43).
    The alarm is shared with the backoff, so it is re-armed only if, after the check, the
    session that was checked is still the active one and connected (checked under the same
    lock as the generation guard). If the session died in between, the loop's backoff alarm
    stays armed. `stop()` cancels the alarm itself, since the cancelled loop no longer owns it.
  - The network monitor only reports a network once Android has validated it
    (`NET_CAPABILITY_VALIDATED`), so captive portals don't burn attempts. Risk: networks where
    validation never succeeds (blocked connectivity check) look offline, and the tunnel waits.
  **Pending:** on-device Doze testing (`adb shell dumpsys deviceidle force-idle`, then
  `unforce`) has not been done; no device or emulator was available.
- **D28. Retry budget.** The add-on's rule: stop after 10 consecutive failures; the next
  wake-up or Connect starts again. (The "Always connected" loop that kept retrying every 30 s
  after that was removed in D50.)
- **D29. `setAndAllowWhileIdle`, not exact alarms.** `setExactAndAllowWhileIdle` needs
  `SCHEDULE_EXACT_ALARM` (denied by default since Android 14, user must grant it in settings)
  or `USE_EXACT_ALARM` (Play only allows it for alarm-clock/calendar apps). The inexact
  while-idle alarm needs no permission and still fires in Doze, but the system dispatches these
  at most about once a minute, and in deep Doze about every 15 minutes, so retries, liveness
  checks and the idle deadline are that coarse while the phone sleeps.

## Push wake-up and connection modes

- **D31. Two connection modes** (superseded by D50: only on demand is left; kept here for the
  history), persisted in the existing `settings` SharedPreferences file
  (`settings/ConnectionSettings`): **On demand** (default) and **Always connected**.
  - On demand: the server sends a high-priority FCM data message when Claude makes a request
    and the phone is not connected; the app connects and disconnects again after the idle
    window (D32). Manual Connect starts the same kind of session. No auto-connect on launch or
    boot.
  - Always connected: exactly the phase-1 "Keep connected" behaviour (D20, D28), no idle timer.
  - Migration: on first read, `keep_connected = true` becomes Always connected, `false` or
    absent becomes On demand, and the old key is removed. It runs only while `connection_mode`
    is absent, so it can never override a chosen mode.
  - Disconnect leaves Always connected (as it turned off "Keep connected"). In on-demand mode a
    Disconnect ends the current session only: the next wake-up connects again. To stop wake-ups
    altogether the user signs out.
- **D32. Idle window: 15, 30 (default) or 60 minutes**, picked on the Status screen.
  - Counted on `elapsedRealtime` from `TunnelConnection.start()` and from every `request` frame
    (`TunnelClient` reports them through `onRequest`; pings and reconnects do not count). It
    keeps counting while the tunnel reconnects or waits for a network, so a woken session can
    never retry forever.
  - Two timers watch the deadline: a coroutine `delay` (precise while the CPU is awake) and an
    inexact while-idle alarm in its own `PendingIntent` slot (request code 1, action
    `TUNNEL_IDLE_DEADLINE`), so it does not replace the reconnect/liveness alarm. A request does
    not re-arm them; when one fires early it re-arms for the remaining time. In deep Doze the
    stop can come up to ~15 minutes late (D29), which is acceptable for a battery saver.
  - Expiry stops the run like a user disconnect (socket closed with 1000), publishes
    `Stopped(code = 1000, reason = "No requests for N min", idleTimeout = true)`, logs
    "Idle timeout: no requests for N min, disconnecting", and the foreground service stops.
    The decision is made under the tunnel lock and the stop takes the lock again; if a request
    frame arrived in between (its activity time differs from the one the decision saw), the stop
    is called off, "A request arrived, staying connected" is logged and the timer re-arms from
    that request. A settings change in between (below) calls it off the same way and logs
    "Idle settings changed, staying connected".
  - Changing the mode or window while connected restarts the countdown from now.
  - The idle timer only runs when a wake-up can bring the tunnel back
    (`PushCoordinator.wakeUpsReady`): Firebase configured, the last registration attempt
    succeeded, and the token it registered is the current FCM token. A token that was accepted
    once is not enough: after `onNewToken` with a failed PUT, a 400 or a second 401, the server
    can no longer reach the phone. The current token is only known once it has been fetched
    (app start) or delivered (`onNewToken`), so a fresh process is "not ready" until its first
    sync. Otherwise on demand is a manual session that stays up until Disconnect, and the Status
    screen says so. Disconnecting idle sessions without a way back would silently make Claude
    lose access. The same condition gates the screen-off and freeze stops (D40).
  - The timer reads `wakeUpsReady` only when it is armed, so `AppGraph` calls
    `tunnel.onIdleSettingsChanged()` whenever `wakeUpsReady` changes: a registration that
    succeeds mid-session arms it (counting from then), one that breaks disarms it.
- **D33. Firebase is optional at build time.** `google-services.json` is not in the repo (it is
  in Bitwarden; README has the command) and is gitignored. `app/build.gradle.kts` applies
  `com.google.gms.google-services` only if `app/google-services.json` exists. Without it,
  Firebase's `FirebaseInitProvider` finds no options and creates no default app;
  `FirebasePushMessaging.isAvailable` checks `FirebaseApp.getApps()` before anything calls
  `FirebaseMessaging.getInstance()` (which would throw). Push is then "not configured in this
  build" on the Status screen, no registration happens, and on demand falls back to manual
  connect (D32). Nothing outside `push/FirebasePushMessaging.kt` and
  `push/AnkiMcpMessagingService.kt` imports Firebase. No API key or Firebase id is hard-coded.
  Versions (Google Maven, 2026-10-15): Firebase BoM 35.0.1 (firebase-messaging 26.0.0, minSdk
  24, no `minCompileSdk`), google-services plugin 4.5.0, work-runtime-ktx 2.12.0
  (`minCompileSdk` 35, AGP 8.6+). Their transitive androidx pins (core 1.12, lifecycle 2.6.2)
  are below D3's, so nothing is bumped. `Task.await()` is a 10-line helper instead of
  `kotlinx-coroutines-play-services`.
- **D34. Registration cadence** (`push/PushCoordinator`). The token is (re-)registered with
  `PUT /devices/push` when signed in and any of: the last attempt did not succeed, the FCM
  token differs from the registered one, the app version differs from the registered one
  (`appVersion` is in the body), or the last success is 7 days old or more. Checked after
  sign-in (which first forgets the previous registration, since it may belong to another
  account), in `onNewToken`, on every app start, and by a daily WorkManager check. An unchanged
  token registered by this version less than 7 days ago causes no request.
  - The daily check is unique periodic work `push-registration-daily`, every 24 h, needs a
    network, enqueued with `KEEP` on every app start and sign-in (so it keeps its schedule) and
    cancelled at logout. It was added for the Always connected mode (removed, D50), where the
    process could live for weeks; it still renews the registration for a process that lives
    long, and costs one job a day.
  - The connection mode is no longer a trigger (it was in the first version): the mode is not
    in the request body, so a re-PUT on a mode change told the server nothing. (There is no
    mode any more, D50.)
  - Network errors, 429 and 5xx (and a token Firebase can't produce) schedule a WorkManager
    retry: unique work `push-registration`, `KEEP`, needs a network, exponential backoff from
    1 minute, gives up after 10 runs (~13 h with WorkManager's 5 h cap) until the next trigger.
    The last run that fails records the result as failed ("Gave up retrying: ..."), so the
    Status screen no longer says "will retry". A 401 refreshes the access token once and
    retries once; a second 401, a 400 or any other 4xx is logged and not retried.
  - The registered token, time, app version and last result live in their own
    SharedPreferences file (`push_registration`), unencrypted: the FCM token is not a credential
    (Firebase itself keeps it in plain prefs).
- **D35. Logout order.** The coordinator is marked as signing out first (the ViewModel does it
  before the disconnect, `logout` again at its start), so wake-ups (D36) and registrations stop
  at once. Then, under the registration lock: record a pending token delete (below),
  `DELETE /devices/push` while the credentials still exist, forget the registration, clear the
  credentials, and cancel the pending retry and the daily check. Then turn FCM auto-init off
  (D39) and call `FirebaseMessaging.deleteToken()`, capped at 5 s.
  - The lock is held across the DELETE and the clearing, and every registration checks
    "signing out / signed in" under the same lock when it starts, again after fetching the
    token, and after the PUT. A sync queued behind the logout (e.g. from `onNewToken`) finds
    nobody signed in and does nothing; one that was already past its first check when the
    logout began does not PUT; one whose PUT was in flight is not recorded (but see below).
    The cancels sit inside the locked part, after the clear, so a sync that held the lock
    cannot re-enqueue the retry or the daily check, and a token fetch that fails after the
    logout began schedules no retry.
  - The 10 s cap covers the wait for the lock and the DELETE together. Whatever happens
    (timeout, an exception from the token or the DELETE, cancellation), the push registration
    and the credentials are cleared.
  - Offline logout. Before the DELETE, a pending-delete record (the token, "server DELETE
    owed", "`deleteToken` owed") is written to the `push_registration` prefs next to the
    registration; forgetting the registration leaves it alone. A DELETE that fails with a
    network error, 429 or 5xx, throws, or runs into the 10 s cap leaves "server DELETE owed"
    set (a 4xx clears it: retrying would not help). A `deleteToken` that fails or takes longer
    than 5 s leaves "`deleteToken` owed". A PUT that completes after the logout began marks its
    token as owing both. The record is finished on the next app start, before the next
    `getToken` (every sync does it first, and does not register while it still fails; that is
    recorded as a transient failure, so the retry worker runs it again), and by unique
    WorkManager work `push-token-delete` (needs a network, `KEEP`, exponential backoff from
    1 minute, no attempt cap), enqueued whenever a record is left. Finishing means: the DELETE
    with the old token, using the current credentials if someone is signed in (skipped
    otherwise: there is nobody to authenticate as), then `deleteToken()`, then the record is
    removed. "On network available" is WorkManager's network constraint rather than a
    `NetworkMonitor` callback, because that monitor only runs while the tunnel service does,
    which it no longer does after sign-out; the work also survives the process.
  - Auto-init goes off before `deleteToken`, so Firebase does not mint a new token right away.
    Logout runs in the app scope so leaving the screen does not cancel it; the button shows
    "Signing out…" meanwhile. If starting the logout throws (the disconnect), the ViewModel's
    and the coordinator's "signing out" flags are reset and nothing is cleared. A sign-in that
    completes while the logout is still deleting the token waits for it before registering.
  - Wake-up racing the logout. A wake-up can pass the signing-out check just before
    `beginLogout()` and start the tunnel after the ViewModel's disconnect, while the
    credentials still exist. Three guards: the wake handler checks "signing out" again right
    after starting the tunnel and stops it ("Signing out"); the ViewModel stops the tunnel
    again once `logout` returns; and `TunnelConnection` watches the stored credentials and
    stops a running tunnel as `Stopped(signedOut)` ("Not signed in") when they become null. A
    run that clears them itself (refresh rejected with `invalid_grant`, access revoked) keeps
    its own reason. Only a change to "no credentials" counts, so the not-yet-decrypted null at
    process start does not stop anything. The sign-in card does not show the reason of a
    `signedOut` stop.
- **D36. Wake handling** (`push/WakeHandler`, called from `AnkiMcpMessagingService` on the FCM
  thread, no blocking work):
  - `type` other than `wake`: ignored.
  - Signing out (D35): ignored, logged.
  - Tunnel looks connected: the relay only wakes a phone it has no connection for, so the
    socket is dead; it is dropped and the run reconnects at once (D40). Tunnel running but
    between attempts: the backoff is cut short.
  - Not signed in: logged, no connection and no notification. The user signed out on purpose
    (or never signed in), so "your session has expired" would be wrong.
  - Otherwise: start the tunnel directly so this wake owns the run, then the foreground service
    through `startForegroundServiceSafely` (a high-priority FCM message is an allowed
    background-start exemption on Android 12+); if Android refuses the service, the run is
    stopped again with "Could not start in the background". Tunnel first: the service shuts
    down when `onStartCommand` finds the tunnel stopped (D40), and in the other order it could
    run between the two calls and see the previous run's Stopped state. The service is started with an extra
    that tells it not to start the tunnel itself: otherwise a run that had already ended (e.g.
    no credentials) would be started a second time when `onStartCommand` arrives. The tunnel
    refreshes an expired token before connecting; if that ends in a session that needs a new
    sign-in (`Stopped(reloginRequired)` from `invalid_grant` or a revoked token, not the
    `signedOut` "Not signed in" stop) within 2 minutes, "Sign in again to AnkiMCP" is posted. The
    refresh is not done before starting the service because the exemption window is short and a
    refresh can take up to 15 s.
  - FCM can deliver a high-priority message at normal priority (it deprioritises apps that
    don't show anything); that is logged, and if Android refuses the start the usual "Could not
    start in the background" state appears.
  - The notification uses its own channel `account` (default importance), opens the app, and
    is dismissed when the user signs in again. Without the notification permission it is not
    shown and the tunnel log says so (D21).
- **D37. FGS special-use description** in the manifest now says the service is also started by
  a push message from the AnkiMCP server and stops after the idle period, since Play reviews
  that text against actual behaviour. It also says that it stops when the screen turns off
  (D40), and no longer mentions an always-on mode (D50).
- **D38. Registration tokens, not Firebase Installation IDs.** firebase-messaging 25.1.0
  (firebase-android-sdk PR #8087, "FCM V1 API Migration") deprecated `getToken`, `deleteToken`
  and `onNewToken` in favour of an opt-in FID registration: `FirebaseMessaging.register()` /
  `unregister()`, `FirebaseMessagingService.onRegistered(fid)`, enabled with the manifest flag
  `firebase_messaging_installation_id_enabled`. The server then targets the FID instead of a
  token. The agreed server contract (`fcmToken`, PROTOCOL.md) is built on tokens, so the app
  keeps the token API: it is deprecated, not removed, and keeps working as long as that flag is
  absent. The deprecation warnings are suppressed at the three call sites. Moving to FIDs is a
  coordinated client + server change (new field or a `kind` discriminator in `PUT
  /devices/push`, server sends to the FID) and should happen before Firebase removes the token
  API.
- **D39. No FCM token before sign-in.** The manifest sets `firebase_messaging_auto_init_enabled`
  to `false`, so Firebase does not create a token (or start its token sync) at first launch.
  The coordinator turns auto-init on (`FirebaseMessaging.isAutoInitEnabled = true`, through
  `PushMessaging.setAutoInitEnabled`) on sign-in and on every app start while signed in (which
  also covers installs signed in before this change), and the app only calls `getToken()` when
  signed in. At logout it turns auto-init off again before `deleteToken()` (D35). Checked in
  firebase-messaging 26.0.0: the manifest key is read by `FirebaseMessaging.AutoInit`, the
  setter persists (`auto_init` in Firebase's prefs) and starts a token sync when turned on, and
  `getToken()` does not check the flag.
  - Play data safety: the FCM registration token (a device identifier tied to the Firebase
    installation) is collected only after the user signs in, sent to the AnkiMCP server for
    the account's wake-ups, and deleted on sign-out, or at the next opportunity if the device
    is offline (DELETE plus `deleteToken`, D35). The data-safety form should declare it as a
    device ID collected for app functionality, not shared.

## OEM background freezers (first on-device trial)

Found on a Realme RMX3741 (Android 15, ColorOS): ColorOS's "Hans" freezer freezes the process
(cgroup `frozen 1`) about a minute after the screen goes off, although the `specialUse` FGS is
running and Doze is not active. Frames queue in the kernel, the relay misses the pong and drops
the socket at its next 30 s tick, and requests in that window reach the app late or never.
Stock Android does not freeze foreground services; Oppo/Realme/OnePlus (ColorOS/OxygenOS),
Xiaomi (MIUI/HyperOS), Vivo (Funtouch/OriginOS) and Huawei/Honor (EMUI/MagicOS) do. AnkiDroid
gets frozen too.

- **D40. A session ends when the phone goes to sleep.** While the idle timer runs (wake-ups
  registered, D32), on every phone (not only the freezing makers; decided, D50), two more events end the session through the idle
  timer's path (`stopRun`: socket closed with 1000, alarms and wake locks released, generation
  bumped, the service stops on the `Stopped` state), published as
  `Stopped(1000, reason, phoneAsleep = true)`. On a screen-off stop the relay then drops its KV
  entry at once and routes the next request to a push wake-up instead of a socket that is about
  to be frozen.
  - Screen off: `ACTION_SCREEN_OFF` (and `ACTION_SCREEN_ON`), from a receiver the service
    registers in `onStartCommand` and unregisters in `onDestroy` (`service/ScreenMonitor`). Chosen over
    `DisplayManager.DisplayListener`: it needs no `Handler`, reports exactly the interactive
    state change we care about (not every display or brightness change, and not secondary
    displays), and is delivered to context-registered receivers in a process with a running
    FGS (Android 14 only defers it for cached processes). It is a protected broadcast, so it is
    registered with `RECEIVER_EXPORTED` as the broadcasts guide asks for system broadcasts; no
    other app can send it. Requests in flight finish first: the stop waits until the session
    handles no relay request (`TunnelSession.awaitRequestsIdle`), at most the 25 s request
    budget, under a `screen-off` wake lock. Screen on during that wait calls the stop off;
    otherwise screen on does nothing (D41).
  - Freeze: `TunnelConnection` ticks every 30 s of coroutine time for as long as a run lasts
    and compares each tick with `SystemClock.uptimeMillis()`. Both stop while the CPU sleeps,
    so ordinary Doze/suspend never looks like a freeze; a tick more than 60 s late on uptime
    means the process was not scheduled while the CPU ran, i.e. frozen. (`elapsedRealtime`
    would count every CPU suspend as a gap.) By then the relay has already terminated the
    socket for the missed pong and removed its KV entry, so the 1000 close goes nowhere; the
    stop's value here is the local cleanup (service, notification, alarms, wake locks) instead of
    a reconnect loop on a phone that will freeze again. Only a session with wake-ups ends;
    a manual one (no wake-ups) keeps its reconnect loop.
  - Battery note (D42): a freeze is reported to `FreezeNotice` for a manual session (no
    wake-ups), and otherwise when the screen was on at the previous tick and at the detecting
    one (`PowerManager.isInteractive`). The expected case, a session a wake-up started with the
    screen off that then freezes, ends quietly: that is the design working, not something for
    the user to fix.
  - A push wake-up while the run still looks connected (D36) means the relay has no connection
    for us, typically a socket that died while frozen, with the wake-up's thaw arriving before
    the freeze tick. `onPushWake` drops the session and reconnects at once, and restarts the
    freeze watch so its overdue tick cannot end the run the wake-up is reviving. Before this, the
    wake-up was a no-op, the freeze tick then ended the run, and the relay's 15 s per-user wake
    throttle made Claude's retry fail. A session with signs of life is kept: it connected or
    received a `request` frame less than 10 s ago, or a request is in flight (the wake-up
    crossed a fresh connection; FCM delivery alone can take several seconds, and Claude's retry
    may already be using the new connection). A socket the relay terminated while the app was
    frozen ends with EOF once the backlog is read, so a request frame from that backlog cannot
    keep it alive for long.
  - The freeze tick and the wake-up race at thaw, so both sleep stops pass a check to `stopRun`
    that runs under the tunnel lock with the generation check: the freeze stop only goes ahead
    if its watch is still the current one (a wake-up restarts it), the screen-off stop only if
    it is still the pending screen-off job (a screen-on clears it). If the freeze stop wins
    instead, `onPushWake` finds no run and returns false, and the wake handler starts a new run
    as if nothing had been running.
  - The service's state collector stops the service only if the tunnel is still stopped when
    the main thread gets there (a wake-up may have started a new run meanwhile), and stops with
    `stopSelfResult(lastStartId)`, which Android refuses while a newer start (a wake-up's
    `startForegroundService`) is queued. Otherwise the service could tear down, with its screen
    and network monitors, under a running tunnel. If that queued start then begins no run (its
    run had already ended), no new Stopped arrives, so `onStartCommand` also shuts down when it
    ends with the tunnel stopped.
  - Without working wake-ups (D32) neither event ends the session, since nothing could bring it
    back; a frozen socket then recovers through the normal reconnect loop.
  - A session started by a wake-up while the screen is already off gets no screen-off event; if
    the phone freezes it, the freeze rule ends it.
- **D41. No reconnect when the screen turns on.** The brief allowed reconnecting on screen on if
  the session had ended only because of the screen and the idle window had not expired. Not
  done: after the screen-off stop the service is gone, and starting a foreground service from a
  `SCREEN_ON` broadcast in the background is refused on Android 12+ (not an allowed exemption),
  so it would fail exactly when it matters. It would also reconnect on every glance at the
  lock screen. Claude's next request arrives as a push wake-up, which is an allowed exemption
  and already starts the service and the tunnel (D36). Opening the app does not reconnect
  either (the app never auto-connects, D20). The relay is to hold the first request after a
  screen-off stop while it wakes the phone, so that request does not fail (relay side, not
  built yet). The Status screen's Connection card says: "Claude wakes your phone when it needs
  your decks; the connection closes when the screen turns off or after N min without
  requests."
- **D42. Battery card: settings links, no direct exemption request.** The card stays optional
  and blocks nothing.
  - No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission and no
    `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog. Google's Doze acceptable-use table
    (developer.android.com, "Optimize for Doze and App Standby") marks the exemption "Not
    acceptable" for apps that can use FCM high-priority messages, and this app does (push
    wake-ups, D31-D36); Play policy forbids the direct request unless Doze/App Standby break the
    core function. It would also not fix the problem found on the trial phone: the exemption
    only covers Doze and App Standby, not OEM freezers such as ColorOS's Hans, where the maker's
    own "Allow background activity" switch is what matters. (A first version of D42 used the
    dialog; it was dropped before release.) This holds for the whole app, the local Wi-Fi
    server included: a local-mode variant with the dialog (D56) was built and removed again
    by the owner, so the same D42 card serves both.
  - "Open battery settings" opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the system
    list; App info if that does not resolve). `PowerManager.isIgnoringBatteryOptimizations`
    drives the tick.
  - `service/FreezingOem` matches `Build.MANUFACTURER`/`Build.BRAND` (lower-cased, exact string)
    against oppo, realme, oneplus, xiaomi, redmi, poco, vivo, iqoo, huawei, honor. On those
    makers the card names the maker's own switches (ColorOS: "Allow background activity" and
    "Don't optimise") and has two buttons, "AnkiMCP info" and "AnkiDroid info", which
    open each app's own App info page (`ACTION_APPLICATION_DETAILS_SETTINGS`), where those
    makers put the per-app battery page;
    each maker's text says where to go from there (ColorOS: "In App info > Battery usage, ...").
    No maker publishes an intent for that sub-page, and
    hard-coded component names of OEM settings activities change between skin versions, so the
    app does not guess them. The card stays after the exemption is granted, since the maker's
    switches cannot be read. (Positions and visibility on the Status screen are now D59's: the
    card is always shown below the tabs, with "✓ Battery optimisation is off for AnkiMCP"
    once exempt.)
  - A reported freeze (D40 says which ones) sets a persisted flag (`settings/FreezeNotice`,
    keys `freeze_detected` and `freeze_detected_at` in the `settings` file). The card then
    moves to the top with "Your phone froze the connection in the background" and a Dismiss
    button. The flag is cleared by Dismiss, when battery optimisation goes from on to off while
    the app is open (the user acted on it), or when the app is exempt and the last freeze is 7
    days old (an exemption granted while the app was not running would otherwise never clear
    it). A freeze seen while already exempt sets it again and restarts the 7 days, pointing at
    the maker's switches.
- **D43. The ping watchdog does not count frozen time.** `TunnelClient` also reads uptime. A
  health check (heartbeat tick or alarm-driven `checkHealth`) that comes more than one
  heartbeat interval plus 10 s (40 s) after the previous one on uptime means the process was
  frozen while the CPU ran, so pings may still be queued behind the check. Ticks run every 30 s
  on a clock that also stops in CPU suspend, so on-time checks are at most ~30 s apart; the
  first version used 60 s, which missed about half of the trial's cases (pauses of 46-76 s,
  depending on when the last check ran). If pings are then overdue,
  the check grants one heartbeat interval (30 s) of grace instead of dropping the socket, and
  logs "App was frozen for Ns, waiting 30s for a server ping". The grace is granted once per
  server ping: a second paused check without a ping in between is judged normally, so a socket
  that really died while the app was frozen is still dropped (one check later). A late check
  caused by CPU suspend (uptime did not move) is judged at once as before (D27), so silent
  sockets are still found in Doze. On the trial phone this had aborted a live connection with
  "No server ping in 76s" while draining the backlog that held the ping.
- **D44. A null cursor from AnkiDroid is retried briefly; the real fix is AnkiDroid's own
  background setting.** (D51 withdrew the second half: with the setting on, Hans still froze a
  running AnkiDroid.) A frozen AnkiDroid answered the deck query with a null cursor in
  13 ms. The first version assumed acquiring the provider thaws AnkiDroid's process. The push
  trial on 2026-10-16 (Realme, ColorOS) disproved that: Hans freezes AnkiDroid about 6 s after
  the screen goes off, and its binder calls are then proxied (`OplusBinderProxy proxy: true`
  in logcat) instead of thawing it, so `AnkiProvider` keeps getting a null cursor. With
  "Allow background activity" on for both apps the same query works with the screen off (36
  decks in 342 ms, AnkiDroid cold-started by the query).
  - The retry stays (after 0.5 s and 1.5 s, three attempts over about 2 s, each a separate
    submission to the provider pool, inside the 20 s budget of D13), because it is cheap and
    still helps when AnkiDroid is merely slow to start; it does not help against a freezer.
    (Reshaped to four attempts over about 2 s and summarised in one log line in D51.)
  - When it fails, the tool error says AnkiDroid was put to sleep in the background, gives
    "open AnkiDroid once and try again" as the quick fix, and points at allowing AnkiDroid to
    run in the background (with the ColorOS path as the example) and at the Status tab.
    (Replaced by the D51 texts, which no longer recommend the setting.)
  - The battery card (D42) and README now say the setting is needed for both apps, and on
    freezing makers the card has one App info button per app.

- **D49. Wake-window wake lock after a push wake with the screen off.** Push-wake trial on dev,
  2026-10-16 (Realme, ColorOS, `OplusHansManager` in logcat): the FCM wake arrived at
  06:12:24Z and the tunnel was up 0.5 s later with the screen off; the relayed request arrived
  at 06:12:42.239Z, the tool answered at 06:12:44.266Z, the `ankimcp:request` wake lock was
  released at 06:12:44.271Z, and Hans froze the process at 06:12:44.266Z ("freeze uid ...
  scene LcdOff"). The response frame was still in OkHttp's send queue; the relay timed the
  request out at 30 s. Hans keeps an app runnable only while it holds a wake lock and freezes
  it as soon as the last one is released.
  - The per-request lock is now released only after the response has left OkHttp's queue:
    after `send`, `TunnelClient` waits until `WebSocket.queueSize()` is 0, polling every
    20 ms, at most 2 s (inside the request lock's 25 s + 5 s timeout; the queue size is an
    injectable seam so tests can hold it non-empty). That only proves the
    frame was written to the socket, not that the relay received it, which is why the window
    below adds a grace period.
  - `TunnelConnection` holds a separate `ankimcp:wake-window` partial wake lock for runs
    started (`start(wokenByPush = true)`) or revived (`onPushWake`) by a push wake while the
    screen is off. It lasts until 60 s after the last response was flushed (or after the wake, if no
    request comes); every request frame and every finished request restarts the 60 s, and
    while a request is in flight only the cap can end it. Cap: one idle window (30 min when
    push is not registered yet) after the last request frame (or the wake, before any
    request), counted like the idle timer so a screen-off conversation in continuous use keeps
    its lock. Every request frame re-takes the lock with that cap as its own timeout (the new
    lock before the old one is released), so even without the timer it cannot outlive the
    session. A wake-up into an open window only restarts the grace period, with no new lock.
    Released early on screen on (Hans leaves a foreground phone alone) and whenever the run
    ends (`stopRun`/`finish`).
    Sessions started with Connect, or with the screen on, never take it.
  - Battery: the lock is only held while Claude is actively using the phone, plus 60 s.
    Opening and release are logged at debug level ("Wake window opened (screen off, at most
    N min / N s without a request)", "Wake window released (reason)"); the per-request re-take
    is not logged.
  - Requests are counted per run in `TunnelConnection` from the new `onRequestDone` callback
    (`TunnelSessionFactory.create` gained a fourth parameter), which `TunnelClient` calls once
    per request after the flush, or when the request is cancelled.
  - Not a substitute for "Allow background activity": without it Hans still freezes the app
    once the window closes, and the relay's next request goes to a push wake again (the
    freeze watch, D40, ends the stale run).

- **D50. "Always connected" is gone; on demand is the only mode.** Decided by Anatoly on
  2026-10-16. Push wake-ups cover the use case (D31-D36, D49), the free tier is disconnected
  by the relay after an hour anyway, and the always-on socket was the only reason the
  battery-exemption question (D42) kept coming back. The screen-off stop (D40) stays on every
  phone; the relay is to hold the first request after it while it wakes the phone (D41).
  - Removed: `ConnectionMode`; the Status screen's mode radio group (the Connection card is
    now the idle-window chips with one line of explanation); `BootReceiver`, its manifest
    entry and the app's own `RECEIVE_BOOT_COMPLETED`; auto-connect on launch
    (`autoConnectWhenForeground`) and the `lifecycle-process` dependency it needed; the
    `keepRetrying` retry-every-30-s loop and `Reconnecting.persistent`; the Always branches of
    the wake window (D49) and the battery note (D40); "leave Always connected" on Disconnect.
  - `RECEIVE_BOOT_COMPLETED` is still in the merged manifest: WorkManager declares it for its
    `RescheduleReceiver`, which puts the push-registration retry, daily check and token-delete
    jobs (D34, D35) back after a reboot. Removing it with `tools:node="remove"` would lose
    those jobs at every reboot, so it stays, attributed to WorkManager in
    distribution/play/permissions.md. The app does nothing itself at boot.
  - Migration: `ConnectionSettings` deletes the stored `connection_mode` ("always" or
    "on_demand") and the first builds' `keep_connected` flag on first load, so every install
    is on demand; the idle window is kept.
  - Disconnect ends the current session, nothing more; the next wake-up connects again. To
    stop wake-ups altogether the user signs out.
  - The idle timer still needs working wake-ups (D32): without them a session is a manual
    one that stays up until Disconnect, and the Connection card says so.
  - The shared log's header says "Idle window: N min" instead of the connection mode (D48).
  - A process the system killed is not resurrected; the next wake-up starts it, as before.

- **D51. AnkiDroid's provider client is held for the process lifetime; the null-cursor retry is
  about 2 s and, on freezing OEMs, a fast refusal is reported as "AnkiDroid is paused".** Trial
  on 2026-10-17 (Realme RMX3741, ColorOS, Android 15): Hans freezes a *running* AnkiDroid
  (uid 10512) about 1 s after the screen goes off (`OplusHansManager: freeze uid: 10512
  com.ichi2.anki ... scene: LcdOff`), even with "Allow background activity" and the Doze
  whitelist. A query to the frozen provider fails at once: `ActivityThread.acquireProvider`
  logs "Failed to find provider info for com.ichi2.anki.flashcards", the repository sees a null
  cursor, and retrying never recovers. A query to an AnkiDroid that is *not running*
  cold-starts it and answers in about 0.5 s with the screen off.
  - Negative result: holding a client does not keep Hans away from AnkiDroid. The first version
    of this decision held a `ContentProviderClient` for each tunnel session, on the reading
    that a client held by our foreground-service process binds AnkiDroid. The phone test froze
    AnkiDroid while it was pinned (2026-10-18 00:05:19). The earlier "cannot transition from R
    to M, importance=bound-fg-service" line came from our own activity being on screen, not
    from the pin. The provider lookup refuses a frozen app without thawing it; that is
    Oplus-specific (AOSP would `unfreezeTemporarily` the provider's process). In that test the
    session-scoped pin was also released before the query that mattered ran, so no query ever
    went through an already-held client while AnkiDroid was frozen.
  - Mechanism (confirmed by the phone test below): a query through an already-held client skips
    the lookup and is a direct binder call into AnkiDroid, and Hans thaws an app on a
    synchronous binder transaction into it ("unfreeze uid: 10512 ... reason: SyncBinder" within
    about 10 ms). So `anki/ProviderPin` holds one `ContentProviderClient` from
    `ContentResolver.acquireUnstableContentProviderClient(FlashCardsContract.AUTHORITY)` for
    the life of the process. It is taken lazily by the first query that finds none held and is
    dropped only on sign-out (`AnkiDroidRepository.releaseProviderClient`, called from
    `MainViewModel.logout` after the tunnel stopped), on a `SecurityException` from a query
    (permission revoked; rethrown, so the tool still reports PermissionDenied), or when a query
    still finds it dead after the retries below. A `RemoteException` from the client
    (`DeadObjectException`, which is also what a binder call into a frozen process gives) first
    triggers a fresh acquire. A client arriving means AnkiDroid died and cold-started: in AOSP
    `removeDyingProviderLocked` tells us `unstableProviderDied` and our old proxy is dead for
    good, so the fresh client replaces it (the old one is retired and closed by its last user),
    the query runs on it, and "AnkiDroid restarted; took a new provider client" is logged (info).
    At most one swap per query; a query whose dead client another query already swapped uses
    that new client at once instead of retrying the dead one. A null acquire (the lookup is
    refused: AnkiDroid is frozen) keeps the old client, which is retried after 300 ms and 1 s,
    each retry preceded by the same fresh acquire; a success after that logs "Thawed AnkiDroid
    via held client after N ms". If the third attempt fails too, or the client was released
    during a sleep (checked after each sleep, so a sign-out is not retried), the client is
    dropped, the same query runs once more through `ContentResolver.query`, and the next query
    acquires a new one.
    A null client (not installed, no permission, provider unreachable, or AnkiDroid already
    frozen at the first acquire) or an acquire that throws is "no client", not an error: the
    query uses the resolver. The one client is shared by concurrent queries; one that is
    released or found dead while queries use it (cursor reading included) is only marked, and
    the last query to finish closes it. A client that arrives after a release is closed at
    once. Logged: "Holding AnkiDroid's provider client" (info), a failed acquire once per run of
    failures with the exception class (e.g. "SecurityException") or "no provider client", a dead
    client (warn), and the release with its reason (debug). `AnkiProvider` has a single seam
    for this, `releaseClient(reason)`; the session wiring in `AppGraph` is gone.
  - Unstable, not stable. In AOSP, `OomAdjuster.computeProviderHostOomAdjLSP` raises the
    provider's host for every connection with references and never reads the stable count, so
    an unstable client binds AnkiDroid just as well. The stable count only matters in
    `ContentProviderHelper.removeDyingProviderLocked`, which kills every client holding a
    stable reference when the provider's process dies ("depends on provider ... in dying
    proc"): with a stable pin, AnkiDroid crashing or being killed mid-session would take our
    process with it. The Hans observation does not argue for stable either:
    `ContentResolver.query` makes the provider call under an unstable reference
    (`acquireUnstableProvider`) and only wraps the returned cursor with a stable one, which is
    held until about 1 s after the cursor is closed. With unstable, a dead AnkiDroid shows up
    as a `DeadObjectException`, which is the same-client retry and drop path above.
  - Null-cursor retry: after a null cursor the query is retried after 250 ms, 750 ms and 1 s,
    four attempts over about 2 s; no retry starts later than 2.5 s after the first attempt (the
    margin covers the attempts' own time). It is short because Hans also freezes our own
    process 5-8 s after each thaw: a 10 s retry (the first version) never got to send its
    answer, it just died with the process.
  - Per-attempt timeout, on freezing OEMs only (the phones `FreezingOem.current()` detects, the
    same check the battery card uses, passed to the repository as `freezingOemName` from
    `AppGraph`): each attempt through the held client waits at most 2.5 s
    (`ATTEMPT_TIMEOUT_MS`). Elsewhere there is no per-attempt limit, only the 20 s overall
    one: stock Android does not freeze a running AnkiDroid, so a slow held-client attempt there
    is a slow AnkiDroid, not a held binder call. A binder call through the held client into a
    frozen AnkiDroid may be held by Hans (`OplusBinderProxy proxy: true`) until AnkiDroid thaws,
    and the 20 s overall timeout (D13) never fires usefully, because Hans refreezes our own
    process 5-8 s after a thaw. An attempt through the plain resolver gets no per-attempt limit,
    only the 20 s overall one: it may cold-start AnkiDroid, which with a big collection on a
    slow phone can legitimately take longer than 2.5 s. The repository checks
    `AnkiProvider.isClientHeld()` right before each attempt, so the rare attempt that acquires
    the client itself is not bounded that time. An attempt that times out ends the call (no
    further retries) as a hang. The stuck provider thread is left to finish on its own; the
    pool is 4 wide, so it takes four hung calls at once to fill it, and then new calls queue
    and time out. A `CancellationSignal` would not help: creating one for a remote query is
    itself a binder call into AnkiDroid. The 20 s overall timeout stays as the outer bound.
  - A call that saw null cursors or a hang ends with one summary line from the repository,
    "AnkiDroid null cursor x4 at [0, 263, 1021, 2034] ms (took [13c, 9c, 11r, 12r] ms), gave
    up" (warn), "..., recovered on attempt 4 after 2000 ms" (info), or "..., then attempt N
    failed after T ms" / "..., then attempt N hung for 2500 ms" / "..., then timed out after T
    ms" (warn); without nulls before it, "AnkiDroid query hung for 2500 ms" / "AnkiDroid query
    timed out after T ms". "at" is each attempt's start, counted from the first; "took" is the
    time of the query call that produced the result, measured on the provider thread (no
    queue wait, none of the pin's sleeps, no earlier attempts on a dead client), with `c` for
    the held client and `r` for the resolver; ", client died on attempts [...]" lists attempts
    whose held client threw a dead-object error. The pin's own lines ("Thawed ...", "AnkiDroid
    restarted ...", "... client died ...") are separate entries, so a call can log more than
    one line.
  - Errors: on a freezing OEM, when every attempt was a null cursor whose query took under
    100 ms (`FAST_NULL_MS`; the frozen refusal took 13 ms in D44; for a dead held client that
    is the resolver fallback's own time), or the last attempt hung after such nulls (a hang on
    the first attempt included), and AnkiDroid is installed with the permission granted, the
    result is `AnkiResult.Frozen`. A hang uses the same result and text, not a separate one:
    "paused it in the background ... can't answer right now" describes a held binder call as
    well as a refused lookup. Slower nulls, and on other phones every null, are `Unavailable`.
    The texts:
    - `Frozen`: names the phone's maker from `FreezingOem.displayName`; on ColorOS: "AnkiDroid
      is running, but the phone has paused it in the background (Realme, Oppo and OnePlus
      phones do this to apps you aren't using), so it can't answer right now. Open AnkiDroid
      on the phone, then ask again. No setting reliably stops this on these phones."
    - `Unavailable`: "AnkiDroid did not answer the deck query. Open AnkiDroid on the phone
      once, then ask again."

    "Allow background activity" did not stop Hans freezing AnkiDroid in the trial, so the tool
    error no longer recommends it. The Status card and README still mention it for our own app;
    whether to change them is a separate decision.
  - Phone test 2026-10-18 01:31-01:37 (Realme RMX3741, ColorOS, Android 15, "Allow background
    activity" off for both apps, dev relay). AnkiDroid opened, one `list_decks` took the client
    ("Holding AnkiDroid's provider client", 195 ms), home, screen off. Hans froze AnkiDroid at
    01:31:30.896 (`freeze uid: 10512 ... scene: LcdOff`) while our client was held, which
    confirms that a held unstable client does not keep AnkiDroid out of the freezer. Two queries
    against the frozen AnkiDroid then succeeded through the held client:
    - 01:33:14.225 `tools/call list_decks` -> 01:33:14.237 `unfreeze uid: 10512 com.ichi2.anki
      ... reason: SyncBinder scene: LcdOff` (frozen 103 s) -> `POST / -> 200 (109 ms)`. This
      one came over the frozen-but-connected path: the relay forwarded onto our frozen socket,
      our liveness alarm thawed us 34 s later and the buffered frame was answered on the old
      socket (the case the relay's stall wake, D52, will shorten to about 3 s).
    - 01:36:29.232 `tools/call list_decks` (after an FCM wake at 01:36:26.675 and a new
      connection) -> 01:36:29.239 `unfreeze uid: 10512 ... reason: SyncBinder` (frozen 190 s)
      -> `POST / -> 200 (97 ms)`.

    No "Failed to find provider info", no `early provider death` and no "died, but we don't
    agree" in the log, so the unstable client did not get AnkiDroid killed. Hans's reason is
    `SyncBinder` (a synchronous binder transaction into the frozen app), not `TransBinder` as
    guessed; same mechanism. Hans refroze AnkiDroid 5 s after each thaw, as it does to us. The
    design stands: the held client is the fix for a frozen-but-running AnkiDroid on ColorOS.
  - What the pin cannot do: rescue the very first query of a process when AnkiDroid is already
    frozen. With no client held yet the query goes through the resolver lookup, which still
    fails fast against a frozen AnkiDroid; that query gets the Frozen error above. The first
    query after process start must find AnkiDroid either running and unfrozen (it takes the
    client) or not running (it cold-starts it and takes the client).
  - Trade-off: a held client keeps a reference on AnkiDroid's provider while our process lives.
    Being unstable, it never takes our process down with AnkiDroid's, and it costs nothing
    when AnkiDroid is not running beyond the next query's reacquire.
  - Not protocol-visible; PROTOCOL.md is unchanged.

## MCP

- **D23. Supported protocol versions** are `2024-11-05`, `2025-03-26` and `2025-06-18`, kept
  in one list (`McpServer.SUPPORTED_PROTOCOL_VERSIONS`); the last entry is the latest. An
  unknown, newer (e.g. `2025-11-25`) or missing version in `initialize` is answered with
  `2025-06-18`, as the MCP lifecycle spec prescribes (reply with the latest version you support).
  Later requests carry the negotiated version in the `mcp-protocol-version` header, which the
  relay forwards; without it the server assumes `2025-03-26` (MCP 2025-06-18, transports).
- **D24. Responses are always 200** for JSON-RPC level errors (including -32700 and -32600),
  matching the brief's "respond 200" rule; 504/500 are reserved for timeouts and unexpected
  exceptions. Unknown tool is -32602 (MCP spec), tool failures are `isError` results.
- **D25. Tool annotations** mirror the add-on for read-only tools: `readOnlyHint: true`,
  `destructiveHint: false`, `openWorldHint: false`. `list_decks` declares an `outputSchema`.
  `outputSchema` (in `tools/list`) and `structuredContent` (in `tools/call`) are only sent when
  the request's protocol version is `2025-06-18` or later; older clients get the same JSON as
  text content only.
- **D26. Server instructions** were adapted from the add-on's `SERVER_INSTRUCTIONS`: GUI
  review and sync-tool guidance were removed (neither exists here); the "no tool call syncs
  with AnkiWeb" and "only change what the user asked for" rules were kept.

## Sign-in UI

- **D30. The verification URL is opened only if it is `https`.** Anything else aborts the
  sign-in with an error instead of opening the browser. A dev relay on `http://` still works
  for the WebSocket and token calls, but its verification URL must be https to be opened.

## Branding and UI

- **D45. The launcher icon is the ankimcp.ai favicon owl.** The paths of
  `ankimcp.ai/static/favicon.svg` (120-unit viewBox: heart-shaped face `#f0e4c5` with a 3-unit
  `#d4a84a` stroke, `#1a2550` eyes, gold beak) are copied unchanged into
  `drawable/ic_launcher_foreground.xml` inside a group scaled 0.66 and centred, which keeps the
  whole face (stroke included) inside the 66 dp safe circle of the 108 dp adaptive canvas; the
  site's navy tile becomes the `#1A2550` background layer. `ic_launcher_monochrome.xml` is the
  same face as one even-odd shape with eyes and beak cut out, for Android 13 themed icons. The
  notification small icon (`ic_stat_tunnel`) is that silhouette in white, fitted into 20 of
  24 dp. No PNG fallbacks: minSdk is 26, so the adaptive icon in `mipmap-anydpi-v26` covers
  every device. The site's hexagonal `logo.svg` was not used: the owl is what users already see
  in the browser tab, and it survives circular masks better. The navigation and button icons
  are Material icon paths (Apache 2.0) vendored as vector drawables, since
  `material-icons-core` is not a dependency and Material3 1.4 no longer pulls it in.
- **D46. Fixed brand colours, dynamic colour off.** Material You would recolour the app per
  wallpaper; the brand should look the same everywhere. Ratios are WCAG 2.x, checked with the
  standard relative-luminance formula:
  - Light: primary is navy `#1A2550` (white on it 14.7:1; navy on the cream card 12.4:1), not
    the site blue `#0A72E6`: white on that blue is only 4.6:1 and the blue as text on cream is
    3.6:1, which fails AA. The site blue, darkened to `#0A5BB8`, is the secondary colour (5.2:1
    on cream, white on it 6.6:1). Gold `#D4A84A` is the tertiary container with navy on it
    (6.7:1); as text it is darkened to `#6B4E0E` (6.1:1 on cream). Background `#FBF8F0`, cards
    `#F3EBD6` and surface variant `#F0E4C5` are tints of the owl cream; text is the site's
    `#1E293B`, secondary text `#334155` (8.2:1 on the darkest cream).
  - Dark: surfaces start at the site's `#152136` and step up to `#26344F` for cards; primary is
    a light blue `#9EC2FF` (6.9:1 on cards), tertiary is the gold itself (5.6:1 on cards), text
    `#E3E7EF` (10:1).
  - Status colours (`StatusColors`, provided next to the colour scheme): connected
    `#1B6E2A`/`#7BD88F`, warning `#8A5300`/`#FFB95C`, error from the scheme (`#BA1A1A`/
    `#FFB4AB`); each is at least 5:1 on the card colour of its theme. The previous connected green
    `#2E7D32` would have been 4.3:1 on the cream cards.
  The XML window theme is unchanged (`Theme.Material.Light.NoActionBar`), so dark mode can show
  a light window for a frame before Compose draws.
- **D47. Three bottom-navigation destinations past onboarding: Status, Log, About.** Material's
  navigation bar is the component for 3-5 top-level destinations; tabs are for related views
  inside one screen. The selected destination is a `rememberSaveable` enum in `AppRoot`, not
  Navigation Compose: three flat screens with no back stack do not need the dependency. Back
  from Log or About returns to Status (a `BackHandler`; Android's "Principles of navigation":
  the start destination is the last screen the user sees before leaving); Back on Status
  leaves the app. The selection resets to Status when a signed-in user becomes signed out or
  AnkiDroid is no longer ready, but not while credentials are still loading, so a tab restored
  after process death survives. Only onboarding keeps a single screen without the bar; signed
  out, the bar and the tabbed Status screen show too, with Sign in on its Remote tab. The
  Status layout (Documentation card, Remote/Local tabs, then the shared freeze notice, Battery
  and Notifications cards) is D59's. Copy feedback
  follows the platform guidance (developer.android.com, "Copy and paste": Android 13+ shows its
  own confirmation, apps should not add a toast or snackbar there): on Android 12L and lower a
  snackbar confirms the copy; on 13+ the button reads "Copied" for 2 s next to the system's
  confirmation. Never both.
- **D48. Shared logs carry device facts, no account data, and are redacted.** "Share log"
  sends `ACTION_SEND` `text/plain` through a chooser, "Copy log" puts the same text on the
  clipboard. `log/LogReport` builds it: app version, Android release and API level,
  `Build.MANUFACTURER`/`MODEL`, idle window (the connection mode until D50), "Push wake-ups registered: yes/no" (the
  current FCM token is registered; never the token), then the entries oldest first with a
  full date and the UTC offset. The email is never in the header, and the signed-in email
  (and the user id when it is 8+ characters) is removed verbatim if a message contains it.
  Then, although the log should hold no secrets (CLAUDE.md), regexes remove `Bearer` values,
  `token=`/`access_token:`-style values of 8+ characters or with a digit (so "Invalid token:
  expired" stays), JWT-shaped strings (three dot-separated base64url parts of 10+ characters),
  FCM tokens (`<id>:APA91...`), email addresses, standard base64 runs (with `+`, `/`, `=`) of
  32+ characters that mix upper case, lower case and digits, and base64url runs of 32+
  characters that contain a digit. The digit and case requirements keep exception class names
  and lower-case URL paths readable. The UI log list itself is unchanged (newest first,
  unredacted), since it only shows what is already on the phone.
- **D59. The Status screen has two tabs, "Remote" and "Local", each with a status dot.**
  Owner decisions (2026-10-18, three rounds). With the local server (D53-D56) the screen stacked
  two independent ways to reach Claude in one long column; tabs are Material's component for
  related views inside one screen (D47), so the bottom bar keeps Status/Log/About and the
  Status screen gets a `SecondaryTabRow` (secondary: it sits inside the content, below a
  shared card, not under the app bar; `TabRow` is deprecated in Material3 1.4). Both sides are
  AnkiMCP, so the tabs are named by where Claude is: "Remote" (the AnkiMCP tunnel, Claude
  anywhere) and "Local" (the server on the phone's Wi-Fi). Earlier names in this round were
  "AnkiMCP"/"Wi-Fi" and "Tunnel"/"Wi-Fi".
  - Order: the Documentation card (the same `DocumentationCard` as on About), the tabs, the
    selected tab's content, then the shared cards that show on either tab: the freeze notice
    (after a freeze only), then Battery and Notifications, which are always shown in every
    state (owner decision, fourth round).
  - "Remote": account card (Sign out), Tunnel card, Connection card (idle window, push line).
    "Local": the Local server card (switch, status, URL with Copy URL and Share, token
    section, port).
  - Signed out (third round): the tabs are always shown, signed in or out; there is no
    separate sign-in screen and no "Use on my Wi-Fi only" button. Past onboarding (first
    launch included) the app shows Status/Log/About and the tabbed Status screen. On Remote
    the account card becomes the sign-in card (`SignInCard`): "Sign in to connect through the
    AnkiMCP tunnel; works from Claude anywhere, including claude.ai", the reason of an ended
    session (`sessionEndedReason`, none after a deliberate sign-out), and Sign in; the whole
    device-code flow runs inside it (requesting, code with Copy code, the verification URL,
    Open sign-in page, waiting, Cancel, errors with Try again). Tunnel card, Connection card
    and MCP URL are hidden while signed out. Local is unchanged and fully usable without an
    account. The Documentation card above and the freeze notice, Battery and Notifications
    below stay as on the signed-in screen. The old persisted `sign_in_skipped` flag is removed on
    first read (`LocalServerSettings`). Sign-out keeps its confirmation flow (D35), and the
    "Sign in again" notification (D36) still opens the app, now on the sign-in card.
  - Battery (`BatteryCard`, buttons from `batteryAction`): one card for the whole screen,
    since it is no longer on a tab, always shown and always the same D42 card, signed in or
    out, server on or off, exempt or not. One wording covers both modes ("Android may pause
    the app when the phone is idle, which cuts the connection or the local server once you
    use it"); on freezing makers the maker's guidance with "AnkiMCP info" and "AnkiDroid
    info", elsewhere "Open battery settings"; never the direct request. Once exempt it shows
    "✓ Battery optimisation is off for AnkiMCP", mirroring Notifications' "✓ Notifications
    are allowed", still with the maker's guidance and buttons on freezing makers (those
    switches cannot be read), and after a freeze "The phone still paused the app; check its
    background settings in App info". Earlier rounds hid the card when nothing was needed
    and, signed out with the server off, unless a freeze was seen, and showed a local-mode
    card with a direct "Allow" request while the server was on; the owner reversed all three
    (the Allow button on the Remote tab was confusing; D56). The D42 Top/Bottom positions are
    gone with the per-tab layout.
  - Notifications (`notificationsUse`): always shown too, with "✓ Notifications are allowed"
    when the permission, the app's switch and every channel are on, and the settings button
    otherwise. The "off" text depends on what would be missed: signed in, the tunnel text
    (wake-ups, "Sign in again"); signed out with the server on, the local-server text (its
    notification and Stop button); signed out with the server off, a generic text (nothing
    runs yet). The freeze notice (D42
    `FreezeNotice`) is its own card, "The phone paused the app", with the freeze line and
    Dismiss, right above the Battery card, so no screen shows two cards titled "Battery".
  - The Local server card no longer carries the Claude Code and Claude Desktop snippets, their
    Copy buttons or the macOS Local Network hint (D55): client setup is in the documentation
    (https://ankimcp.ai/docs/ankidroid-mcp) and the README. Share sends the URL, and the token
    when one is required, without the Claude Code command (`ClientConfig.shareText`).
  - Dot (`statusTabDot`, `TabDot`): a small circle left of the title, always drawn, with the
    add-on's three states (`_derive_state` and `_STATE_COLORS` in
    `anki-mcp-server-addon/anki_mcp_server/tunnel/ui/toolbar_indicator.py`, the "● AnkiMCP"
    item in Anki's top toolbar next to Sync): green (`StatusColors.connected`) when
    connected, amber (`StatusColors.connectingDot`) while a run is going on but not
    connected, grey (`StatusColors.off`, the scheme's `outline`) otherwise. The add-on has no
    error colour, so errors are grey here too. Remote: `Connected` green; `WaitingForNetwork`,
    `Connecting`, `Reconnecting` amber; `Idle`, `Stopped` grey. Local: switch on and `Running`
    green; switch on and starting (`Off`) or `NoWifi` amber; switch off, `PublicAddress` or
    `Failed` grey. The dot is a graphic, so it needs 3:1 (WCAG 1.4.11), not the 4.5:1 of the
    amber text colour `warning`, which is too dark to read as amber at 8 dp. The add-on's
    `#E0A800` is used in dark mode (7.5:1 on `#152136`); on the light surface `#FBF8F0` it is
    only 2.0:1, so light mode uses `#B88300` (3.15:1), the lightest value of that hue that
    passes. Green and grey pass 3:1 in both themes (light 6.0:1 and 4.4:1, dark 9.3:1 and
    5.3:1). TalkBack reads the title and a state word ("Remote, Connecting", "Local,
    Running"). The labels are plain text, one line, ellipsised if a large font makes them too
    wide. (An emoji in each label was tried and dropped by the owner; the About links got
    emoji instead: 📖 Documentation, 🌐 Website, 📊 Dashboard, 💬 Forum, 📡 Service status, a
    neutral glyph rather than a coloured one that would look like a live status, and 💻 Source
    code. TalkBack reads the plain title there.)
  - Selection (`StatusTabState`): the user's pick, and the account state (`user != null`) last
    seen once credentials are loaded. Whenever that flips (sign-in, sign-out, a session that
    ended) the pick is dropped, so a sign-out and sign-in round trip lands on the default
    again: Remote, except Local when signed out with the local server on. A flip from signed
    in to signed out (sign-out, or an ended session that needs a new sign-in) switches the
    bottom bar to Status and pins Remote, so the sign-in card and the session-ended reason are
    on screen even with the local server on. Otherwise the default on screen is recorded as
    the pick once the account state is seen (`StatusTabState.settle`), so stopping the server
    from its notification does not switch a signed-out user from Local to Remote. The state lives in
    `rememberSaveable` in `AppRoot`, which already covers rotation, Log/About and back, and
    process death; the process-wide `StatusTabMemory` only seeds it when the activity is
    re-created without saved state (Back left the app, then it was opened again in the same
    process).
- **D60. The app is called "AnkiMCP".** Owner decision (2026-10-18). "AnkiDroid MCP" could read
  as a product of AnkiDroid, an independent open-source project, and Play's metadata and
  impersonation policy looks at other apps' names in titles. The user-visible name is plain
  "AnkiMCP" everywhere: `app_name` (launcher label, notification title), the Play listing title
  (7 characters), in-app texts, tool error messages, share and log texts, and the MCP
  `serverInfo.title`. "for AnkiDroid" appears only in descriptive text (Play descriptions,
  README intro, the non-affiliation line) where it says which app it works with. Sentences that
  must tell the two apps apart name both ("Do this for both AnkiMCP and AnkiDroid"). Machine
  identifiers do not change: package and applicationId `ai.ankimcp.ankidroid`, the repo
  `ankimcp/ankidroid-mcp`, `serverInfo.name` "ankidroid-mcp", notification channel ids,
  signing and CI secret names, and URLs (including `ankimcp.ai/docs/ankidroid-mcp`).

## Request dedupe

- **D52. Each relay `requestId` runs at most once per process** (`tunnel/RequestDedupe.kt`,
  PROTOCOL.md Deviations 17).
  - The race: the relay is adding a rule that a request forwarded to a connected phone with no
    reply after 3 s triggers an FCM wake, and when the phone reconnects on a new socket the
    relay re-forwards the same request with the same `requestId`. On ColorOS the phone is often
    frozen with the socket open. On thaw two things race in our process: OkHttp delivers the
    buffered `request` frame to the old session (handled within ~30 ms), and the push wake
    calls `TunnelConnection.onPushWake()`, which drops the old socket when it shows no recent
    activity (Deviations 16). If the drop lands after the tool started, `requestScope` is
    cancelled when `messageLoop` returns, no response is sent, and the re-forwarded copy would
    run the tool a second time. Read-only tools make that harmless; the coming write tools
    (add/update notes) do not.
  - Rule: at most once per `requestId` within the process. The id is recorded at receipt, in
    `messageLoop` before the handler is launched, not when the tool finishes, so a copy that
    arrives while the original runs cannot start a second run. That can only happen on the same
    connection: across connections the original has always finished or been cut off first,
    because the old session's `run()` waits for its request coroutines before the next
    connection starts.
    The table is one `RequestDedupe` created in `AppGraph` and passed to every `TunnelClient`
    through `TunnelSessionFactory`; `begin` is synchronized because frames from an old and a
    new session can be handled on different threads.
  - The original stores its response inside the request timeout block, right after the handler
    returns and before trying to send it, so a response built just as the socket died is
    replayed as is on the new connection. If the handler coroutine ends without a stored
    response (cancelled), the entry is completed with "cut off" when the handler had started
    (a flag set right before `handler.handle`) and with "never ran" otherwise; a copy that finds
    "never ran" re-claims the id inside the table's lock and runs it as a first sighting (the
    common case: a socket dropped right after receipt cancels the request before it is
    dispatched), and further copies wait on that run. A duplicate waits for the original (up to
    the 25 s request budget), replays its response, or, for a cut-off original or a wait that
    ran out, answers 200 with JSON-RPC error -32001 "The phone was interrupted while handling
    this request, so its result is unknown. Check AnkiDroid before repeating a change." A
    duplicate takes the request wake lock and counts in `requestsInFlight`/`onRequest`/
    `onRequestDone` like any request, so its reply is flushed before the lock goes (D49).
  - Why 200 + JSON-RPC error and not 5xx: the relay forwards the body, and Claude shows a
    JSON-RPC error's message, so the user learns what we know (the change may or may not have
    happened) rather than seeing a transport failure. Same choice as D24. -32001 is in the
    implementation-defined server error range, next to our -32004 timeout.
  - Why 2 min / 64 entries: a re-forward can only come while the relay still waits for a
    reply (~30 s reply timeout) and claude.ai still waits for the relay (~40 s); 2 min covers
    both with margin. Measured on `elapsedRealtime`, which keeps running while we are frozen,
    so a long freeze ages entries out as it should. 64 is far above the requests one
    conversation can have pending; past it the oldest entry goes first. Expired entries are
    pruned on every receipt and every completion, so an idle process does not keep old
    responses until the next request.
  - Why in memory: a request cut off by process death and re-forwarded to the restarted
    process runs again. That window is small (death within the pending window of a request),
    and persisting the table would add disk I/O on the request path for little gain. Write
    tools should be idempotent by design where that is cheap (e.g. check for an identical
    note before adding).
  - Read-only tools cut off mid-run lose nothing: the duplicate gets the -32001 error, and if
    the user asks again Claude sends a new request with a new id, which runs normally.
  - Not a wire change; the relay contract it relies on (same `requestId` on re-forward, reply
    accepted from whichever connection carries it, a second reply for an answered id ignored)
    is in PROTOCOL.md Deviations 17.
  - Testing: the dedupe paths are covered by JVM tests only; the end-to-end duplicate cannot be
    exercised until the relay re-forwards, so the phone run of 2026-10-18 only verified that
    normal requests still flow through the dedupe (two successful `list_decks`, no "Duplicate
    request" lines).

## Local Wi-Fi server (issue #3)

MCP served straight from the phone over HTTP on the local network, without an account or the
relay, like the desktop add-on's local mode. Clients: Claude Code or Claude Desktop on a
computer on the same Wi-Fi; claude.ai cannot reach a LAN address. Code in `local/`, plus
`service/WifiMonitor.kt`, `settings/LocalServerSettings.kt` and `ui/LocalServerCard.kt`.

- **D53. Stateless Streamable HTTP on NanoHTTPD 2.3.1.**
  - Transport, checked against the MCP spec 2025-06-18 "Transports" page (the newest version
    `McpServer` implements, D23; also compared with 2025-11-25 and 2026-07-28 via context7 on
    2026-10-18): `POST /mcp` carries one JSON-RPC message and gets `application/json` back (a
    server may answer JSON instead of SSE); a notification (or client response) gets 202 with
    no body; GET (no server-initiated SSE stream) and DELETE (no client-terminated session)
    get 405, which 2025-06-18 allows and 2026-07-28 prescribes for both. No `Mcp-Session-Id`:
    assigning one is a MAY, so none is issued and one sent by a client is ignored. A request
    whose `MCP-Protocol-Version` header is not one we support gets 400 (spec MUST), except on
    `initialize`, where the body's version negotiation (D23) decides. Missing header: the spec's
    `2025-03-26` default, as on the tunnel. `Accept` must admit JSON when present (406
    otherwise) and `Content-Type` must be JSON when present (415); both are lenient when
    absent so curl works. Transport errors are JSON-RPC -32000 without id, as the MCP
    TypeScript SDK sends them.
  - `McpHttpAdapter` is the thin adapter, like `TunnelClient` for the relay: it calls the same
    `McpServer` (`JsonRpcHandler`), so tools, the `AnkiDroidRepository` pool and its 20 s
    timeout (D13) are shared with the tunnel, and both run at once without special handling.
    Like a relayed request it has a 25 s budget (504 with -32004 and the request's id) and
    holds a `local-request` partial wake lock while it runs (D27, D49); JSON-RPC errors are
    200 (D24), an exception is 500 with -32603.
  - Library: the issue allowed Ktor CIO or NanoHTTPD, the smaller one. NanoHTTPD 2.3.1 is one
    51 KB jar with no runtime dependencies. Ktor 3.6.0 (`ktor-server-cio`, newest on Maven
    Central 2026-10-18) pulls ktor-server-core (1 MB jar), ktor-http, -http-cio, -network,
    -utils, -io, -events, -websockets, -serialization, kotlin-reflect, Typesafe Config and
    slf4j-api, and is built against Kotlin stdlib 2.3.21 and coroutines 1.11.0, i.e. it would
    pull this project past its Kotlin 2.2.21 / coroutines 1.10.2 pins (D2). Ktor's docs list
    Android only for its client engines; the server is documented for JVM and Native. Ktor's
    coroutine model is the better fit in principle, but one blocking call per connection on a
    small pool, bridged with `runBlocking`, is enough for one or two clients.
  - The cost of NanoHTTPD: unmaintained since 2016. Its published CVEs (CVE-2020-13697,
    CVE-2022-21230) are in temporary-file handling for multipart uploads, so the engine
    installs a temp-file manager that refuses to create files and never calls `parseBody`.
    Its default runner starts an unbounded thread per connection; ours is a pool of 8 (more
    connections are closed at once). Keep-alive connections idle out after 5 s. The body is
    read by us, at most 1 MiB by `Content-Length` (413 above that, 411 for a chunked body; the
    connection is closed when the body was not read, since NanoHTTPD would parse it as the next
    request). gzip is off. NanoHTTPD sits behind `HttpServerEngine`, so replacing it (Ktor, or
    a hand-written HTTP/1.1 parser) touches one file.
  - Host, Origin and the token are checked on the headers alone (`McpHttpAdapter.screen`,
    called by the engine before it reads the body); a refused request is answered at once, its
    body is not kept and the connection is closed, so an unauthenticated peer cannot make us
    allocate up to 1 MiB. Before closing, up to 64 KiB of a body that is already arriving is
    read and discarded for at most 500 ms, because closing a socket with unread data sends a
    TCP RST that can make the client lose the 403. What remains: 8 connection slots with a 5 s read timeout, so a peer
    on the LAN can still hold all slots by opening connections and sending headers slowly. That
    is accepted under the trusted-Wi-Fi assumption (D54, README).
  - Unparseable JSON (including an empty body) is 400 with the -32700 body, as the spec's
    "cannot accept" rule and the TypeScript SDK do; other JSON-RPC errors stay 200 (D24).
  - Log hygiene: the method and path in the per-request debug line, and a protocol version
    echoed in an error, are stripped of control characters and cut to 64 characters. A refused
    request (Host, Origin, token) writes no per-request debug line; its only trace is the
    rejection warning, logged at most once per reason per 10 s with "(N more not logged)" on
    the next one, so an unauthenticated scanner adds at most a few lines per 10 s and cannot
    flush the 100-entry log. Authenticated requests keep their debug line.
  - No R8 rules needed: NanoHTTPD uses no reflection; `assembleRelease` passes.
- **D54. Bound to the Wi-Fi IPv4 address only, Host and Origin checked.**
  - `service/WifiMonitor` registers a `TRANSPORT_WIFI` network callback (not the default
    network: with a VPN up or mobile data preferred, Wi-Fi is still there) without
    `NET_CAPABILITY_INTERNET`, so an offline LAN works; the request keeps its default
    `NOT_VPN`. `local/selectBindAddress` picks an IPv4 address of a network that is Wi-Fi and
    not cellular or VPN, and only from 10/8, 172.16/12, 192.168/16 (preferred) or carrier-grade
    NAT 100.64/10. A Wi-Fi that only hands out other (public) IPv4 addresses gets no socket
    and the card says "This Wi-Fi gives the phone a public internet address, so the server
    stays off" (state `PublicAddress`): binding there would put the server on the internet. No
    Wi-Fi: no socket ("Connect to Wi-Fi"). Never 0.0.0.0, so the server is never reachable over
    mobile data. Wi-Fi hotspot (the phone as access point) is not a Wi-Fi network in this
    sense and is not supported.
  - `LocalServer` rebinds whenever the address or the port setting changes (old socket closed
    first). Engine calls run one at a time on a one-thread view of `Dispatchers.IO`. A port in
    use (EADDRINUSE) is shown as "Port N is in use. Pick another port."; it is retried on the
    next address or port change. An address that vanished between the callback and the bind
    (EADDRNOTAVAIL, "Cannot assign requested address", told apart by the OS message) is
    treated as no Wi-Fi: the state goes back to waiting and the monitor's next address binds.
    Since the monitor emits nothing when the same address is still (or again) there, the
    server also re-reads the current address after 2 s, 10 s and 10 s (three tries at most)
    and binds if it is usable; a new monitor or port value, or stop, ends those retries.
  - Port: default 8765 (AnkiConnect Android's, so existing instructions transfer), 1024-65535
    (no privileged ports for apps), on the Local server card.
  - DNS rebinding (spec MUST on Origin): `Host` must be exactly `<bound ip>:<port>` (ports
    are 1024+, so it always carries the port), and `Origin`, when present,
    `http://<bound ip>:<port>`; anything else
    is 403 with a JSON-RPC error body. Checked before the token, so a rebinding page learns
    nothing. These checks run whether or not a token is required (D55): they are the
    rebinding defence and do not depend on the token. A browser on the LAN that visits the IP
    directly is same-origin, so with the open default it is served like any other LAN client.
- **D55. Open by default, optional bearer token (same storage as the credentials), and the
  client snippets.** (D59 later removed the Claude Code and Claude Desktop snippets and the
  macOS hint from the Local server card; client setup lives in the documentation.)
  - Owner decision, 2026-10-18 (after the device test): the server is open by default. A
    "Require access token" switch on the Local server card (`LocalServerSettings.requireToken`,
    key `local_server_require_token`, default off) turns the token on. Off: no Authorization
    check at all; Host and Origin are still checked (D54). The card hides the token section
    and says "Anyone on this Wi-Fi can use the server and read your decks (and change your
    collection, once tools that write exist)"; the Claude Code snippet has no `--header`, the
    Desktop snippet no header argument and no `env`, and Share sends no token. On: the
    behaviour below. The token is created the first time the switch is turned on (not when the
    server starts; a start with the switch on only reads it, and recreates it only if it can
    never be decrypted). The adapter reads the switch per request, so a change applies to the
    next request without a restart. Reasoning: the issue's threat model is trusted home
    Wi-Fi with read-only tools, where a token mostly costs setup friction; anyone who wants it
    keeps it one switch away. Revisit when write tools land.
  - 32 bytes from `SecureRandom`, base64url without padding (43 characters), required (when
    the switch is on) on every request as `Authorization: Bearer` (scheme case-insensitive,
    constant-time compare).
    Stored by `LocalServerTokenStore` with the D15 mechanism: `KeystoreCredentialCipher`
    (AES-256-GCM, the same AndroidKeyStore key) and `SharedPreferencesBlobStorage` in its own
    `local_server_v1` file, excluded from backup like everything else. An undecryptable token
    is replaced (clients need the new one); a transient Keystore failure keeps it and the
    server reports "Could not read the access token" instead of starting with a different one.
    A failure to store a new token (Keystore `ProviderException`/`KeyStoreException`, storage)
    is caught, logged by exception class only, and shown on the card (`saveError`). When it
    happens as the switch is turned on, usually while the server already runs, the server
    keeps running and refuses every request (there is no token to match) while the card shows
    "Could not create an access token ... Try again"; turning the switch off and on retries. A
    server that starts with the switch on and cannot create or read its token does not start
    (state Failed with that message, Wi-Fi watch stopped). On Regenerate the old token stays
    valid and the card shows the message. A token that exists but cannot be read this time
    (transient Keystore failure) is shown the same way (`readError`, reason only: "Could not
    read the access token ... every request is refused. Turn "Require access token" off and
    on to try again"), instead of "Reading the token…". Nothing on the app scope throws. Regenerate is behind a confirmation dialog. Key creation in
    `KeystoreCredentialCipher` is serialised by one lock shared by all instances, since both
    stores use the alias and a first sign-in and a first token at the same moment must not
    generate the key twice.
  - Missing or wrong token: 403 with a JSON-RPC -32000 body ("Forbidden: send the access
    token ...") and no `WWW-Authenticate`, like the desktop add-on (`http_auth.py`). Not 401
    as the issue first said: the MCP TypeScript SDK, which Claude Code and mcp-remote use,
    starts OAuth discovery on any 401, with or without a challenge header, and then fails with
    a confusing "needs authentication" against a server that has no OAuth.
  - The token never reaches TunnelLog or logcat: rejected requests are logged by reason only
    ("Local server rejected a request: missing or wrong access token"); the token is also in
    the shared log's verbatim-redaction list (D48), copied as a sensitive clip (hidden in the
    Android 13+ clipboard preview), and masked on screen until "Show".
  - Client config, checked via context7 on 2026-10-18 against the Claude Code docs and the
    mcp-remote README: Claude Code takes `claude mcp add --transport http ankidroid <url>
    --header "Authorization: Bearer <token>"`. Claude Desktop's `claude_desktop_config.json`
    starts local stdio servers (remote "custom connectors" are for servers with a public URL,
    per Anthropic's "When to use desktop and web connectors"), so the snippet runs `npx -y
    mcp-remote <url> --allow-http --transport http-only --header Authorization:${AUTH_HEADER}`
    with `AUTH_HEADER` in `env` (mcp-remote's workaround for Desktop on Windows not escaping
    spaces in args). It needs Node.js on the computer; the card says so. Share sends URL,
    token (when required) and the Claude Code command through the Android share sheet; no QR
    code (the client is a computer).
  - macOS Local Network permission, verified on the owner's Mac on 2026-10-18: Node-based
    clients (Claude Code, mcp-remote) get `EHOSTUNREACH` to the phone until the app running
    them (Terminal, iTerm, the Claude app) is allowed under System Settings > Privacy &
    Security > Local Network; Apple's `curl` is exempt, which is why curl reached the phone
    while Claude Code did not. The card (under the snippets) and the README say so.
  - End to end, 2026-10-18: over Wi-Fi to the phone, `initialize`, `tools/list` and
    `list_decks` returned 200 (`list_decks` in 0.2 s); a wrong or missing token, a bad Host
    and a bad Origin got 403; GET 405, an unsupported protocol version 400 and a notification
    202, as designed. Claude Code reached the phone once the Local Network permission above
    was the only blocker left.
- **D56. Lifecycle, battery exemption and the signed-out path.**
  - The server runs exactly while `TunnelForegroundService` runs and the switch is on: the
    service calls `LocalServer.start()` on every start and `stop()` in `onDestroy`, and follows
    the switch. A tunnel `Stopped` no longer stops the service while the switch is on;
    turning the switch off stops it only when no tunnel run is going on (Idle or Stopped;
    the tunnel collector still never treats Idle as ended, D40). Disconnect (app or
    notification) and sign-out stop only the tunnel then. The notification shows both lines
    ("Connected: ..." while a tunnel run is going on, "Local MCP server on 192.168.x.x:8765"),
    with Disconnect while the tunnel runs and "Stop local server" while the server is on. A
    start for the local server alone passes `EXTRA_LOCAL_SERVER_ONLY` so the tunnel is not
    started. The FGS special-use description in the manifest and in
    distribution/play/permissions.md mentions the local server.
  - Restart: the switch is persisted. The app restarts the service when it comes to the
    front and the server is on but not running (`ensureLocalServer`), and a push wake-up's
    service start brings it up too. Not at boot (no boot receiver, D50) and not from the
    background (Android 12+ refuses it).
  - Battery: local mode has no push wake-up, so nothing can bring a dozing or frozen phone's
    server back, and the exemption matters more here (device evidence below). The first
    version declared `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` again and offered the
    `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog ("Allow") on a local-mode battery card
    while the switch was on, arguing that this mode cannot use FCM. Removed by the owner
    (2026-10-18): with the battery card shared below the Status tabs (D59), an "Allow" button
    showed on the Remote tab too, which was confusing, and the owner prefers the one D42 card
    with its settings links ("AnkiMCP info"/"AnkiDroid info" on freezing makers, "Open
    battery settings" elsewhere) in every state. It also drops the Play-review risk that the
    app as a whole uses FCM. The permission is gone from the manifest; the user turns the
    exemption on in settings, as for the tunnel.
  - Device evidence, Realme RMX3741 / ColorOS 15, 2026-10-18 13:17-13:20: without the
    exemption, Hans froze the app about 30 s after the screen went off and a request to the
    local server hung (client 20 s timeout, no thaw). With the exemption (on the deviceidle
    whitelist) Hans still froze the app, but an incoming packet thawed it ("OplusHansManager
    unfreeze ... reason: Packet") and `list_decks` answered in 0.6 s, AnkiDroid being thawed
    through the held provider client ("reason: SyncBinder") as in D51. Two more clean runs with
    the exemption granted before screen-off: frozen about 30 s after screen-off, then a query
    70 s after the freeze ("unfreeze ... reason: Packet", 200 in 0.6 s) and one 15 s after the
    freeze (Packet thaw, 200 in 0.2 s). One failed run explains the nuance: with the exemption
    granted while the app was already frozen, the next request hung with no Packet thaw. So
    on this OEM the exemption makes incoming packets thaw the app; ColorOS applies it from the
    next freeze on (in practice at once for a user who grants it once and leaves it on); and
    without it the server stops answering about 30 s after the screen turns off. (An earlier version of this entry guessed the
    exemption would not help against Hans; the device runs show packet thaw needs it.)
  - One battery card per screen, the D42 card, the same with the server on or off, always shown
    below the Status tabs (D59). A "phone froze the connection" notice (D42 `FreezeNotice`) is
    its own card right above it.
  - Switching the server off always calls `LocalServer.stop()` (idempotent) on an on-to-off
    change, also when a failed start (token) already ended the run, and then stops the service
    if no tunnel run is going on; the collector's first value never stops anything
    (`localSwitchAction`).
  - Signed out: the Status screen with both tabs, Sign in on Remote, the Local tab fully
    usable (D59 third round; the first version had a sign-in screen with "Use on my Wi-Fi
    only" and a persisted `sign_in_skipped`, both gone). Sign-out leaves the server as it was,
    and a running server stays visible and stoppable on the Local tab.
  - Notifications: turning the server on asks for
    POST_NOTIFICATIONS on Android 13+ when it is missing, since the server's notification
    carries "Stop local server" and a signed-out user was never asked (D21 asks after
    sign-in). The Notifications card is always shown below the Status tabs (D59), with a
    local-server text when signed out with the server on.
  - Logged: switch on/off, port changes, "Local server listening on <ip>:<port>", "Local
    server stopped", "no Wi-Fi, waiting", bind failures, token regenerated, rejected requests
    by reason, and each request at debug level ("Local POST /mcp -> 200 (N ms)").

## AnkiDroid tool set

- **D57. The add-on's tool set, as far as AnkiDroid's official API allows, behind a 2.24 gate.**
  First half (plumbing and the reads) built 2026-10-18 and passed the phone battery; the second
  half (the writes, below "Writes") built the same day. Owner decisions, binding:
  - Same tool names and argument names as the desktop add-on
    (`anki-mcp-server-addon/anki_mcp_server/primitives/`). Each tool's input schema lists only
    the arguments AnkiDroid can honour; the others are absent, not "accepted and ignored".
    Output keys match the add-on where the data exists; a key the provider cannot supply is
    omitted, never estimated (no `dueToday` approximation).
  - The 30 add-on tools AnkiDroid cannot serve (field remove/rename/reposition,
    change_note_type, tag_clear_unused, unbury, unsuspend, forget, reposition, set due date,
    filtered deck create/delete/empty/rebuild, set/optimise FSRS params, media listing and
    delete, sync, the 11 GUI tools) are not registered, not even as stubs. No media tool, no
    `will_force_full_sync` anywhere. (A later owner decision added a phone-only `sync` that
    only asks the user to sync, D58; the add-on's sync contract is still not offered.)
  - Official provider API only: never the `notes_v2` raw-SQL URI, never knowledge of
    AnkiDroid's database schema. Everything goes through `notes`, `cards`, `models`,
    `models/<id>/templates`, `decks` and `schedule`.
  - AnkiDroid 2.24.0 or newer for every tool except `list_decks` (the `cards` URI and the
    scheduling/FSRS card columns arrived in 2.24); below it a tool returns "This needs AnkiDroid
    2.24 or newer; the phone has AnkiDroid X. Update AnkiDroid to 2.24 or newer, then try
    again." before asking AnkiDroid anything. Card flags need 2.25.0 (`RequiredVersion.CARD_FLAGS`,
    used by `card_set_flag` in the second half).
  - Batch write tools (second half) take at most 20 notes per call and report partial progress
    if time runs out.
  - The add-on's prompts (`review_session`, `twenty_rules`) and the `anki://query-syntax`
    resource are served as static text.
- **Version gate.** `AnkiDroidAvailability.version()` reads AnkiDroid's `versionCode` and
  `versionName` from the package manager. AnkiDroid's per-ABI builds add `abi * 100000000` to
  the code, so the code is taken modulo 1e8; 2.24.0 is 22400300 (major, two-digit minor,
  two-digit patch, build type with 3 = release, two-digit build number; checked against
  AnkiDroid's `build.gradle` at v2.24.0 and v2.25.3). 2.24 alphas and betas (224001xx,
  224002xx) are below the gate, deliberately: the columns were still moving then. The gate sits
  in `AnkiDroidRepository` (every read and `write` takes a minimum version, default 2.24), so a
  tool cannot forget it; `listDecks(minVersion = null)` is the one exception, used only by
  `list_decks`, and every other tool's first deck lookup keeps the gate so an unknown deck on an
  old AnkiDroid still reports the version, not "deck not found". Install and permission are
  checked first.
- **Provider surface.** `AnkiProvider` is generic: `query`, `insert`, `update`, `delete`,
  `bulkInsert` on authority-relative paths (`notes`, `models/123/templates`, `notes?deckId=1`),
  with plain maps in and out. Rows are copied out of the cursor inside the Android
  implementation, typed by `Cursor.getType` (null, Long, Double, String, ByteArray); a Boolean
  from a MatrixCursor still arrives as "true"/"false" (D11). Tools never see column names:
  `AnkiDroidRepository` has typed methods (`listDecks`, `listDeckOptions`, `listNoteTypes`,
  `listTemplates`, `searchNotes`, `searchCardIds`, `searchCards`, `nextScheduledCard`) and
  enum projections (`NoteField`, `CardField`). Column names and availability were checked
  against `CardContentProvider` at v2.24.1 and v2.25.3.
  - The Android side is split into `EndpointAnkiProvider` (all interpretation) over a
    `ProviderEndpoint` seam with two thin real endpoints (held client, resolver), because
    `ContentResolver`'s calls are final and `ContentProviderClient`'s constructor is hidden, so
    neither can be faked on the JVM without Robolectric (D5). `EndpointAnkiProviderTest` covers
    row types, resolver results and short reads through fake endpoints and a fake `Cursor`; the
    two endpoints themselves are only type translation and are checked on the device.
  - Resolver results (AOSP `ContentResolver`): a write that hit a `RemoteException` returns null
    (insert), -1 (update, delete) or 0 (bulkInsert); these become a null value, which `write`
    reports as outcome unknown. Through the held client the numbers are taken as they are (it
    throws instead). A resolver write that found no provider at all throws "Unknown URL"/"Unknown
    URI"; that becomes `ProviderUnreachableException` and is reported as Frozen (freezing OEM) or
    "AnkiDroid could not be reached, so nothing was changed", not as rejected.
  - Short reads: a cursor from another process fetches rows window by window, and a failed
    fetch only ends the iteration. Fewer rows than `getCount()` throws `ProviderDiedException`:
    through the held client the pin treats it as a dead client (D51), through the resolver it
    is Unavailable ("AnkiDroid stopped answering while sending the results"), never a silently
    truncated list. This relies on `getCount()` being exact for AnkiDroid's cursors; the phone
    test of 2026-10-18 matched it for `notes` searches (`deck:Dev` total 15 paged 3 rows at a
    time, and a 20-note `notes_info`).
  - How a query error is reported (`runProviderCall`): `SecurityException` -> PermissionDenied;
    `IllegalStateException` whose message mentions storage or "not configured" -> NotReady, any
    other `IllegalStateException` -> Unavailable ("AnkiDroid returned an error: ..."); a short
    read through the resolver (`ProviderDiedException`) -> Unavailable; the schedule lock taken
    by a still-running call -> Busy; everything else (`IllegalArgumentException`, and the
    `RuntimeException` the binder raises for an exception AnkiDroid cannot send back) ->
    Rejected. A `RemoteException` never gets here for a read: the pin handles it as a dead
    client (D51).
- **An empty note search is not a frozen AnkiDroid.** The `notes` search returns a null cursor
  when nothing matches, which the null retry (D44, D51) would report as Frozen/Unavailable.
  Worse, an invalid search there throws rslib's `BackendSearchException`, which is not one of
  the exception types a ContentProvider can send back; `Parcel.writeException` rethrows it in
  AnkiDroid's binder thread, and what reaches us is unverified (most likely a
  `RuntimeException` from the binder layer, possibly something that ends in a null cursor). So
  `searchNotes` runs the same search on `cards` first, projecting only `_id` (AnkiDroid's fast
  path: no card objects, no rendering): that cursor is never null while AnkiDroid answers, an
  invalid search comes back as an `IllegalArgumentException` ("Invalid Anki search query: ...";
  only the message crosses the binder, so rslib's own explanation is lost on AnkiDroid's side
  and cannot be shown), and no matching card means no matching note. Only a non-empty match
  goes on to the `notes` search, as a separate read, where a null cursor is then retried as
  frozen. This was chosen over the mapping's "probe `models` after a null" because that probe
  cannot tell an invalid search from a frozen AnkiDroid. Errors a search can raise past
  parsing (a bad `re:` regex fails inside the database, not as a search error, so AnkiDroid
  does not wrap it) take the same unclean path on the `cards` probe; therefore such errors are
  reported as a rejected query (the mapping is listed above), and only a `RemoteException` is
  treated as a dead client. Cost: one extra binder call, carrying every
  matching card id (a whole-collection search sends all of them).
  A note with no cards would be missed; Anki's Check Database removes such notes.
  `nid:1,2,3` (comma list) is valid rslib syntax (`check_id_list` in `rslib/src/search/
  parser.rs`, `^(\d+,)*\d+$`, used by `nid:` and `cid:`; `nid:` searches also worked on the
  phone on 2026-10-18), so `notes_info` reads up to 100 notes, and their card ids, in one
  search each.
- **Writes run once.** `AnkiDroidRepository.write(operation, minVersion) { provider.update(...) }`
  is the only write path (used by the second half): no null retry, no per-attempt cap, no
  dead-client retry (`ProviderPin.withClient(retryDead = false)` drops the dead client and
  rethrows). A write still queued when the 20 s timeout (or the tool's budget) runs out, or
  whose caller is cancelled while it is queued, is never sent (a queued/running/abandoned state
  is claimed atomically, also in the cancellation path) and reports NotResponding; a write that
  finished between the timeout and that check returns its real result; one that started
  and got no answer reports `OutcomeUnknown`: "AnkiDroid did not answer within 20 seconds, so
  it is unknown whether adding the note happened. Check AnkiDroid on the phone before
  repeating it." The seconds are the time actually waited for that write; when the tool's
  budget cut it short the reason says so ("... within 3 seconds, when this request's
  12-second limit ran out"). A dead client during a write, a null from the resolver path (which swallows
  `RemoteException`) and any unexpected exception are `OutcomeUnknown` too;
  `IllegalArgumentException`/`UnsupportedOperationException` (AnkiDroid's validation before it
  writes) are `Rejected`, `SecurityException` is PermissionDenied. A held client to a frozen
  AnkiDroid thaws it (D51), so write tools should still read first (they all do, to resolve
  names), which also keeps a dead-object error on a frozen app from turning a write into
  "outcome unknown".
- **Server instructions** no longer say "Only read-only tools are available in this version."
  (removed with the write tools). They name the query-syntax resource and the two prompts, add
  the add-on's review loop (get_due_cards -> present_card -> rate_card, the user confirming the
  rating) with "never repeat a rate_card call that may have gone through", and say the sync tool
  only posts a notification.
- **Tool conventions.** Tools extend `tools.AnkiTool`: an argument not in the schema is a tool
  error naming the accepted ones (owner decision; rather than silently ignored, which for `recompute` or
  `skip_images` would mislead), schemas set `additionalProperties: false`. Arguments are
  coerced like pydantic's lax mode (5.0 and "5" are integers; booleans are JSON true/false, 1/0,
  or "true"/"false", "yes"/"no", "on"/"off", "t"/"f", "y"/"n", "1"/"0" in any case). Errors
  use the add-on's `HandlerError` wording, `[code] message (hint: ...) (context: {...})` with
  the context printed as a Python dict, but without FastMCP's "Error executing tool X: "
  prefix. Lengths and excerpts count code points like Python. Annotations: all tools here are
  read-only (`readOnlyAnnotations`) except `get_due_cards`; `writeAnnotations(title,
  destructive, idempotent)` mirrors the add-on's `_build_tool_annotations`. Structured results
  stay compact JSON in the text content (FastMCP indents by 2). `AnkiTool.call` runs, in order:
  the unknown-argument check, the install/permission/2.24 check (`checkToolSet`, skipped only
  by `list_decks`; before any argument-only answer such as "card not found" for a negative id),
  then the tool under one `CallBudget`.
- **One budget per tool call.** A tool makes up to about ten provider calls, each with the
  repository's 20 s timeout, while the relay holds a request about 15 s
  and the tunnel 25 s. `AnkiTool` runs every tool under `CallBudget(12 s)`, a coroutine-context
  element the repository reads: each read or write gets the smaller of 20 s and what is left of
  the budget, a call that would start with nothing left is not sent, and running out is
  `AnkiResult.TooSlow`: "AnkiDroid is taking too long: this request did not finish within 12
  seconds. Try a smaller request (for example a lower limit), or open AnkiDroid on the phone and
  ask again." The budget's clock starts at the tool's first repository call.
- **A slow call is not a frozen AnkiDroid (replaces D51's hard 2.5 s cap for reads).** D51 ended
  a held-client attempt on a freezing OEM after 2.5 s as Frozen. That cap was there to tell a
  frozen AnkiDroid from a working one, but legitimately heavy calls (`tag_list` without a deck,
  a broad `find_notes`, 100-card batches on a 30k-card collection) can take longer on a big
  collection and would be misreported as "paused". Now (`awaitUnlessFrozen`): the attempt may
  run until the tool's budget or the 20 s timeout; if it has not answered after 2.5 s, a cheap
  probe runs alongside it, the deck list with ids only (normally about 10 ms), capped at 1 s,
  with no cap or schedule lock of its own. No answer to the probe: AnkiDroid is frozen, the
  call reports Frozen at about 3.5 s and is abandoned. The probe answered (or could not even
  get a pool thread): AnkiDroid is working, the call continues, and if it then runs out of
  budget it is TooSlow, not Frozen. The attempt's 2.5 s and the probe's 1 s both count from
  when the call starts on a pool thread: time queued for a thread says nothing about AnkiDroid.
  Threads held by calls abandoned as frozen are counted until those calls return; once they
  fill the pool, a call (or probe) that cannot start is Frozen at once ("could not start: every
  provider thread is held by a call AnkiDroid never answered"). Every repository
  read still makes one provider call per attempt (`searchNotes` is two reads), and card
  details are still read at most 100 cards per `cid:` call (`cardsByIds`,
  `MAX_CARDS_PER_CALL`); `get_card_memory_state` takes at most 100 card ids. A binder call
  cannot be cancelled (AnkiDroid's provider ignores `CancellationSignal`, and creating one is
  itself a binder call), so an abandoned attempt keeps its pool thread until AnkiDroid answers
  and its result is discarded; a still-queued one is skipped.
- **`get_due_cards` is not read-only.** AnkiDroid's `schedule` query selects the deck, reads the
  queue and selects the previous deck again, which is not atomic. Our schedule queries are
  serialised in two layers. Callers first wait for their turn on a coroutine `Mutex` for up
  to 3 s, outside the read and so outside the freeze watch: a second concurrent
  `get_due_cards` reports Busy after 3 s, never Frozen. The provider thread then holds a
  `ReentrantLock` for the whole binder call (so a call whose caller gave up still holds it
  until AnkiDroid answers); if such a call is still running, the next one's `tryLock` fails
  at once and reports Busy too. With the skip flags back (second half) the tool's annotations
  are the add-on's (`readOnlyHint: false`, `destructiveHint: true`, no `idempotentHint`); the
  first half had `destructiveHint: false`, `idempotentHint: true` for the deck selection alone.
  It cannot serialise against the user changing decks in AnkiDroid at the same moment.
- **Rendering.** `present_card`/`get_due_cards` use `question_simple`/`answer_simple`, which in
  2.24/2.25 are `renderOutput(col).questionText`/`answerText`, the same rslib render (with
  `[anki:play:...]` tags) as desktop's `question_text`/`answer_text`; the `question`/`answer`
  columns rewrite sound tags and are not used. The note type CSS from `models` is prepended as
  `<style>css</style>` where the add-on's `card.question()`/`card.answer()` include it, and
  `data-cloze="..."` is stripped from questions with the add-on's regex. Not yet seen on a
  device.
- **Per-tool differences from the add-on** (beyond the omitted arguments and keys above):
  - `list_decks`: `stats` has no `total_in_deck`, `summary` no `total_cards` (AnkiDroid's deck
    list has no per-deck total). Decks sorted by name.
  - `model_field_names`, `notes_info`: no field `description` (not in the provider).
  - `model_styling`: no `latex_svg` argument or key.
  - `notes_info`: at most 100 notes (the add-on's default `max_notes_per_batch`; no config on
    the phone, so the hint does not mention one); `models` keeps first-seen order. `cards` is in
    template order like the add-on: the card ids of all the notes come from one id-only
    `nid:a,b,c` search, then `note_id` and `ord` for those cards from `cid:` calls of at most
    100 cards. Those calls render the cards; an id-only answer cannot say which note a card
    belongs to, and one search per note (the previous version) cost ~100 sequential calls.
  - `find_notes`: searches ids only and reads first fields for the returned page alone.
  - `tag_list` without a deck: tags found on notes, not Anki's tag registry (not exposed), so a
    registered tag no note uses is missing; ordered case-insensitively like the registry.
  - `get_due_cards`: `skip_images`/`skip_audio` bury each skipped card through the `schedule`
    update and confirm it by the card's queue before looking at the next one (a bury AnkiDroid
    swallowed would otherwise loop on the same card); `skipped` counts only confirmed buries.
    At most 5 cards are buried per call (add-on: up to 100 checked), since each bury is a write
    plus a confirming read inside the 12 s budget; a call that reaches 5 returns no card and
    says to call again. The reply lists the buried card ids (`buried_card_ids`, phone-only key),
    and once a card has been buried any failure later in the call (a read, a write, a bury not
    kept) ends with "Stopped while skipping cards with media: buried N card(s) so far (images,
    audio; card ids ...)". The texts say to unbury in AnkiDroid (no card_unbury).
    Annotations as above. The next card comes from the `schedule` URI,
    which restores the previously selected deck (the add-on leaves the deck selected).
    `counts`/`total` are the deck's `DECK_COUNTS` from the due tree (subdecks included), not
    `get_queued_cards`' counts; they can differ slightly for learning cards.
  - `get_due_cards`, `cards_stats`: AnkiDroid's deck list is rslib's deck tree, which leaves
    out deck 1 while it is empty and has no subdecks (`hide_default_deck`, `decks/tree.rs`), and
    `decks/1` looks in the same tree. The add-on finds that deck by name, so a "Default" that is
    not in the list is taken to be that empty deck (no cards, zero counts) instead of "Deck not
    found". A renamed deck 1 hidden this way cannot be recognised. `find_notes` has no deck
    lookup and already answers `deck:Default` with zero notes.
  - `cards_stats`: no `dueToday` (needs `sched.today` and the day cutoff). Owner decision:
    `limit` defaults to and is capped at 200 (add-on: 1000), details read 100 cards per call.
    On AnkiDroid 2.25 each card also has `flag` (0-7; phone-only key, as the flag column only
    exists from 2.25); on 2.24 the key is left out.
  - `get_card_memory_state`: no `recompute`, no "FSRS is not enabled" error (the collection's
    `fsrs` switch is not exposed; a card without memory state has null stability and
    difficulty), no `elapsed_days`. `retrievability` is computed as Anki's card info computes it
    (rslib `stats/card.rs`: seconds since `last_review_time`, the card's decay, defaulting to
    FSRS-5's 0.5, `fsrs::current_retrievability`), under the add-on's conditions (review card,
    interval and stability > 0) plus a known last review time (omitted without one), rounded
    to 4 places; the add-on uses a day-based FSRS-4 curve, so the numbers differ. Owner
    decision: kept, as a value derived from what was read with rslib's own formula. At most
    100 card ids per call (the add-on has no limit).
  - `get_fsrs_params`: no `fsrs_enabled`/`fsrs_version`; `desired_retention` is the preset's
    (per-deck overrides are not exposed); only presets used by at least one normal deck are
    listed (presets come from each deck's `options`); `max_interval` is the preset's
    `rev.maxIvl`, whereas the add-on reads a top-level `maxIvl` that schema11 does not have
    and so always reports 36500. A filtered deck has no preset: asking for one by name is an
    error ("Deck 'X' is a filtered deck, which has no FSRS preset"), and filtered decks never
    contribute a preset to the full list (the add-on would return the deck's own dict as a
    pseudo-preset).
  - `filtered_deck_get_info`: no `search_terms`/`reschedule` (not exposed). `card_count`
    counts the deck's own cards via `did:N` (filtered) or `did:N -deck:filtered` (normal; plain
    `did:` also matches cards whose home deck it is while they sit in a filtered deck).
- **Prompts and resources.** `mcp.Prompt`/`mcp.Resource` with registries; `McpServer` serves
  `prompts/list`, `prompts/get`, `resources/list` and `resources/read` in FastMCP's shapes
  (checked against the add-on's vendored SDK) and advertises `prompts` and `resources`
  capabilities when any are registered. `twenty_rules_content.md` is copied byte for byte into
  `src/main/resources/ai/ankimcp/ankidroid/prompts/` and loaded by the absolute path
  `/ai/ankimcp/ankidroid/prompts/...`: R8 renames and repackages `AddonPrompts` while Java
  resources keep their path, so a name relative to the class's package would be missing in
  release builds (a JVM test loads the files through a class in another package);
  `query_syntax.json` holds the add-on's dict as `json.dumps(indent=2)`
  prints it (verified identical). `review_session` is adapted for the phone (owner decision):
  same arguments and structure; "offer to sync" became "suggest syncing in AnkiDroid"; the
  rating step asks the user to rate the card in AnkiDroid (the second half switches it to
  rate_card when that ships); the voice style lost skip_images/skip_audio and the unbury lines
  (owner decision, kept with writes: it tells Claude to say when a card depends on an image or
  audio, ask the user whether to skip it, and if they agree card_bury that one card);
  there is no "gui" style, which like any unknown style gets the interactive text. It is pinned
  by the phone's own golden files for six argument sets. Unknown prompt or unusable argument:
  -32602; unknown resource: -32002 (FastMCP sends error code 0).
- **Cost on AnkiDroid 2.24/2.25.** Any `cards` query projecting more than `_id` builds every
  matching card and renders its question and answer, whatever columns are asked for (lazy
  columns only arrive in 2.26). Hence the id-only searches and 100-card batches above:
  `cards_stats` renders at most 200 cards per call of the tool (two provider calls),
  `get_card_memory_state` 100, `notes_info` the cards of its notes 100 per call,
  `present_card` one, `get_due_cards` the cards of one note, and `find_notes` none. Not timed
  on a phone yet; the freeze probe keeps a slow one from being reported as frozen.
- **Freeze probe that never started.** A probe still waiting in the pool's queue has not
  reached AnkiDroid, so it says nothing about a freeze: the call keeps waiting, logged as "a
  probe is still waiting for a free thread", and the probe's 1 s starts only when it gets a
  thread. Only a probe that started and got no answer within 1 s means Frozen, or a pool held
  entirely by calls already abandoned as frozen (above).
- **Writes (second half).** Owner decisions, binding: same names and argument names as the
  add-on with schemas trimmed to what AnkiDroid supports; at most 20 notes (or cards, or note
  ids across tag_batch operations) per call for every batch tool; a call that has to stop
  part-way (the 12 s budget runs out, AnkiDroid stops answering, an outcome becomes unknown)
  ends with "Did N of M <unit> before stopping. Done: <ids>. [Unchanged (nothing to do):
  <ids>.] [Unknown whether <id> was changed.] Not done: <ids>. Reason: ..." (`WriteProgress`;
  items are ids, or template names for update_model_templates; tag_batch lists the notes of
  later operations as not done too), never a silent truncation; every
  write is confirmed by re-reading what it changed, because AnkiDroid's `schedule` update
  catches errors from answer/bury/suspend and still returns 1; delete_notes and rate_card ship;
  no media tool; official provider URIs only; 2.24 for everything, card_set_flag 2.25; every
  write goes through `AnkiDroidRepository.write` (one call, no retry, unknown outcome reported
  as such); annotations as the add-on declares them.
  - **Three outcomes per write.** Not sent (nothing changed: "Did not send ... ask again", or a
    Busy/Frozen/too-slow failure before the call), sent with an unknown outcome, or sent and
    accepted. After an accepted write, a failed confirming read says "<what> was sent to
    AnkiDroid and accepted, but could not be confirmed: ... Check before repeating it."
    (`confirmedOr`), never "failed"; rate_card's says "Do not rate this card again; check it
    with present_card." Under the tool's 12 s budget a write is not started with less than 3 s
    left (`MIN_WRITE_BUDGET_MS`, AnkiResult.NotSent), and its timeout keeps 2 s back for the
    confirming read (`CONFIRM_RESERVE_MS`).
  - **Field text.** Values (add_note, add_notes, update_note_fields, update_notes) and field
    names (create_model, model_field_add) containing U+001F are refused before anything is
    written: Anki joins fields with it, so it would shift every later field. Matching new notes
    and confirming field writes compare NFC-normalised text, as rslib stores fields NFC.
  - Repository: typed writes `createDeck`, `addNotesToDeck` (`bulkInsert` on
    `notes?deckId=`), `updateNote` (all fields and/or the whole tag string), `deleteNote`,
    `moveCard`, `setCardFlag` (2.25), `buryCard`, `suspendCard`, `answerCard`
    (`schedule` update with `note_id`/`ord` plus `buried`/`suspended`/`answer_ease`+`time_taken`,
    the latter always 0),
    `createNoteType`, `updateNoteType`, `updateTemplate`, `addField`; `cardsByIds` reads `flags`
    on 2.25. Checked against `CardContentProvider` v2.24.1 and v2.25.3.
  - Shared helpers, unit-tested: `PatchHelpers` (the add-on's `_patch_helpers.py`: exactly-once
    non-overlapping match, excerpt anchored on the longest of 60/30/15-character prefixes,
    mode selection, the same error texts; the JSON-literal transport caveat is FastMCP's and is
    left out) and `AnkiTags` (rslib: whitespace split, case-insensitive add that leaves a note
    alone when it has every tag, removal of the tag or its `tag::` children with no wildcards,
    the add-on's same_tag/child_tag replace conflicts). `ToolFailure`/`fail()` raise the
    add-on's `HandlerError` wording from anywhere in a tool.
  - **add_note/add_notes and the deck.** AnkiDroid's single-note insert puts the note in the
    note type's default deck and ignores any deck; `bulkInsert` on `notes?deckId=` adds the
    notes and sets every card's deck, but returns only a count. The new ids are recovered:
    a note id is its creation time in ms on the phone's clock (ours too), and rslib gives notes
    added in one call increasing ids, so they are the `added:2 mid:<type>` notes with an id not
    below the time taken just before the call, matched to the requests by NFC-normalised
    fields, taking found notes in id order. add_notes reads back after any sent call, so a
    `bulkInsert` that fails part-way (AnkiDroid adds row by row) still reports the notes it did
    add as created. A note added but not found again is reported as added with an unknown id,
    not as failed: add_notes gives it status `created`, `note_id: null` and a `note` saying so;
    add_note returns an error saying it was added and to find_notes before adding it again.
    After an insert with no answer (OutcomeUnknown), a note not found is status `unknown`
    (`note_id: null`, may still have been added; phone-only `unknown` count), never `failed`.
    Duplicate check: the add-on's
    `Field:"value"` search on the first field (a failing search is ignored, as there);
    duplicates within one add_notes call are not detected (as the add-on).
  - **Read-modify-write.** AnkiDroid takes a note's fields only all at once and its tags only
    as the whole string, so update_note_fields, update_notes and the tag tools read the note,
    change it and write it back; an edit made in AnkiDroid between the read and the write is
    lost (or overwrites ours). tag_replace adds and removes in one write per note, so there is
    no window with neither tag (the add-on makes two bulk calls).
  - **Per-tool differences:** create_deck reports an existing deck without writing (AnkiDroid
    refuses existing names); update_notes writes one note at a time (no single undo step);
    delete_notes deletes one note per call and confirms by searching for them again
    (2.24 returns 1 regardless); card_bury/card_suspend/card_set_flag/card_change_deck count
    only cards confirmed changed and add a `failed: [{card_id, error}]` list when some were not
    (phone-only key); card_change_deck does not move cards that are in a filtered deck (the add-on
    takes them out first, the provider cannot) and refuses a filtered target; card_unbury does
    not exist, so the skip-flag and bury texts say to unbury in AnkiDroid; update_model_styling
    has no `latex_svg`; model_field_add has no `index` (append only); no
    `will_force_full_sync` anywhere (model_field_add keeps the add-on's full-sync `warning`);
    create_model creates the type with AnkiDroid's default templates, overwrites each, and
    switches a cloze type to cloze last, so a run cut short leaves a standard type rather than
    a cloze type with default templates (which would make no cards); a cut short run says the
    type may still have default templates. AnkiDroid saves note types with rslib's checks
    skipped, so nothing on its side would refuse bad templates: create_model and
    update_model_templates run the checks of rslib 25.09's `check_templates` first
    (`TemplateChecks`, `notetype/mod.rs` + `template.rs`) and refuse with `[validation_error]`:
    two identical fronts, unless they contain `{{Card}}`; a conditional not opened or not
    closed; a front that references nothing (special fields such as `{{Tags}}` count, so does
    an empty name); a non-empty name on the front or back that is neither a field nor a special
    field (`{{text:}}`, `{{tts-voices:}}` have empty names and pass; `{{#cN}}` is not a field);
    for a cloze type, no `{{cloze:...}}` on the front or on the back of the first template.
    Templates are tokenised as rslib does (`<!-- -->` comments skipped, `{{` to the next `}}`,
    the field after the last `:`). A template starting with the legacy `{{=<% %>=}}` directive
    is not parsed (only the identical-front check applies). The add-on's own `warnings` stay
    (it reads `{{text:A}}` as a field called "text:A" and warns where rslib accepts).
    model_field_add re-reads the type and reports the field name AnkiDroid stored (it can differ
    from the one asked for). card_bury refuses a suspended card in `failed` ("card is suspended;
    Anki does not bury suspended cards") without writing. The tag tools' "tags must be a
    space-separated string, e.g. \"tag1 tag2\"" answers a list given for `tags`. tag_batch caps
    note ids across operations at 20 besides the add-on's 50 operations.
  - **rate_card** (owner decisions). No `time_taken` argument: 0 ms is always sent (AnkiDroid's
    -1 means "time it with the reviewer's timer", which was never started), and the argument is
    rejected as unknown. Confirmed by any change in reps, queue, due, type or deck (a preview in
    a filtered deck does not add a rep). Output by the card's queue after the answer: review
    (2) gives `new_interval` and "Next review in N days" without `next_review` (needs the
    collection's creation day, not exposed); intraday learning and preview (1, 4) give
    `next_review` in the phone's time zone and the real time until it is due (due minus now);
    day learning (3) gives neither, since its due is a day number; the interval is never
    printed as seconds. `card_type` uses the add-on's names (new, learning, review,
    relearning). A repeat of the same rating is refused before anything is sent (`RatingGuard`,
    plain Kotlin, one instance shared by rate_card and get_due_cards): the process remembers
    the last card it rated (a confirmed or unknown-outcome answer) and when; only get_due_cards
    returning that card clears it, so a card the scheduler hands out again (short learning
    steps, learn-ahead) can be rated at once, while rating it again otherwise is refused.
    present_card does not clear it: the "sent and accepted, but could not be confirmed" error
    sends Claude to present_card to check, the one case where a second rating must stay
    refused. A process that has rated nothing yet (just started, no
    memory) refuses a card whose `last_review_time` is under 10 s old. This replaced a 60 s
    last-review rule, which blocked such legitimate re-ratings.
  - **rate_card is not idempotent.** A repeated call records a second review. D52 stops only a
    relay re-forward of the same request id; the tool description, the server instructions and
    review_session say never to repeat a rating that may have gone through.
  - review_session uses rate_card again (step 7 and the session rules, plus "never call
    rate_card twice for the same answer"); its golden files changed accordingly.

## AnkiDroid sync

- **D58. `sync` asks the user to sync AnkiDroid through a notification.** Owner decision.
  AnkiDroid's provider has no sync. Its `IntentHandler` activity (exported, category DEFAULT)
  takes the action `com.ichi2.anki.DO_SYNC` (manifest at v2.25.3; the AnkiDroid API wiki's
  "Sync Intent", no extras) and turns it into a normal sync, at most one every 2 minutes
  (`INTENT_SYNC_MIN_INTERVAL` in `IntentHandler.kt` v2.25.3, which shows a notification when
  it skips one). A background app may not start an activity, so the tool posts "Tap to sync
  AnkiDroid" on a new "Sync requests" channel (`NotificationChannels.SYNC`, high importance,
  not in `NotificationChannels.ALL`, so a turned-off sync channel does not raise the Status
  card's notification warning: the tool reports it instead); the tap's `PendingIntent` starts
  that activity in AnkiDroid's package. The tool says the sync was requested, that the user
  must tap the notification, that no result comes back, and that AnkiDroid must be signed in to
  AnkiWeb. A notification that would not be seen (permission, app switch, channel; via
  `NotificationPermissionGate.blockReason`) or an AnkiDroid that does not resolve the intent
  is a tool error saying so. The add-on's `job_id`/`resolve` polling has no equivalent and is
  not offered. Annotations as the add-on's sync: write, destructive, `openWorldHint: true`.
  - Seam: `anki.AnkiDroidSync` (plain Kotlin, `requestSync(): SyncRequest`) with the Android
    `service.NotificationSyncRequester`; the tool is tested on the JVM through the seam. Firing
    the intent directly when our app is in the foreground was considered and left out: the
    notification works in every state and keeps one code path.

## Logs

- **D61. An always-on file log, a 24 h "Detailed logging" switch, and "Share detailed log"
  through the share sheet only.** Owner decision (2026-10-18).
  - Why a file log: the Log tab's ring buffer (100 entries, memory only) is gone after process
    death and scrolls away within minutes of reconnects, while the failures worth reporting
    (overnight freezer trouble, D40-D51; the phone killing the app) happen hours before the
    user looks. `log/FileLogSink` is a second sink of `TunnelLog`, which stays the single entry
    point (`TunnelLog(sinks = ...)`, called inside the log's lock so the file keeps its
    order). It writes info, warn and error always, debug only while detailed logging is on, to
    `filesDir/logs/ankimcp.log`; at 1 MB the file becomes `ankimcp.log.1` (the older one is
    deleted), so at most about 2 MB. `filesDir` is excluded from backup and device transfer
    (`data_extraction_rules.xml`, `allowBackup="false"`).
  - Cost: `append` only enqueues onto one low-priority daemon thread; nothing on the tunnel or
    tool paths waits for the disk. Lines go through an 8 KB buffered writer, flushed at once
    for warn and error, every 10 s (only when something is buffered) and before a share.
    Messages are redacted first and then cut at 4000 characters (cutting first could leave
    half a secret that no longer matches), and newlines are flattened, so one line cannot
    fill the file. A disk error drops whatever the writer had buffered (up to about 8 KB, at
    most 10 s of info lines), closes the file, and the next line opens it again; it never
    throws into the caller. A process death loses the same: the info lines buffered since
    the last flush. A "Process started (pid N)" line goes to the file only (not the Log tab),
    appended right after the sink is built and before anything else in the process can log,
    so a share shows where the phone killed and restarted the app.
  - Privacy: every line is redacted (`LogReport.redact`, the D48 rules plus the account email,
    the user id and the local server's access token, `AppGraph.redactionSecrets`) before it
    reaches the disk, on the writer thread. The CLAUDE.md rule (no tokens in TunnelLog) still
    holds; the redaction is the second line of defence, tested at the sink ("Bearer x" never
    hits the disk). The D48 rules gained two. `Authorization`, `Proxy-Authorization`,
    `Cookie`, `Set-Cookie` and `X-API-Key` values are removed to the end of the line, also
    JSON- or quote-wrapped (`"Authorization":"Basic ..."`); a Bearer value keeps the existing
    "Bearer [redacted]" form. And base64url runs of 40+ characters mixing upper and lower
    case are removed even without a digit (a random 43-character token, like the local
    server's, has none about one time in 20), unless they are a plain CamelCase identifier
    such as an exception class name.
  - The switch (`settings/DetailedLogging`, Log tab, default off): stores the wall-clock end,
    `detailed_logging_until` in the `settings` file, 24 h after it was turned on. Checked at
    app start (a past end is cleared and logged "Detailed logging turned off: 24 hours
    passed"), on every debug line and every share, when the app comes to the front, and by a
    coroutine that re-reads the wall clock at least every 10 min (a coroutine delay does not
    count deep sleep). A clock set back more than 24 h before the end ends it too. Turning it
    on or off and the expiry take the same lock, and the expiry re-checks the stored end
    inside it, so a toggle at the moment of expiry is never undone or reported as an
    expiry. Why 24 h:
    long enough to catch one night, short enough that nobody leaves debug logging and the
    logcat dump on for weeks by accident.
  - What detailed logging adds: debug lines in the file log (per-request lines such as "Local
    POST /mcp -> 200", provider client details), and at share time a dump of this app's own
    logcat: `logcat -d -t 2000 -v time` through `ProcessBuilder` (`log/LogcatReader` behind
    the `CommandRunner` seam), 5 s timeout, daemon reader thread. `-t 2000` makes logcat
    print only the newest 2000 lines itself (AOSP `logcat --help`: "print only the most
    recent <count> lines (implies -d)"; developer.android.com/tools/logcat defers to
    `--help` and does not list it); the reader also keeps at most 2000 lines and then the
    newest 512 KiB, with "(N older lines left out)" above, so a phone that has run for days
    cannot produce a huge share file.
  - No `--pid` (coordinator decision, 2026-10-18, revising the first build): logd already
    limits an app without `READ_LOGS` to its own uid's lines, and the earlier processes of
    this app, the ones the phone killed overnight, are what a report most needs; `--pid`
    would have left them out. Each change of pid in the dump gets a line "--- process N (the
    current one) ---" or "--- process N (an earlier or other process of this app) ---"
    (parsed from the `-v time` prefix `I/Tag( N):`; lines without one, such as "---------
    beginning of main", pass through).
  - Own logcat without a permission, checked 2026-10-18: `READ_LOGS` is a
    signature/privileged permission; Android's security guide (developer.android.com
    "Security tips", via context7) describes logs as available to apps holding it. Since
    Android 4.1 an app without it gets only its own uid's entries from logd (the 4.1 change,
    documented in CommonsWare's "READ_LOGS Regression" and the CatLog author's notes; no
    current developer.android.com page found states it directly, so this rests on those and
    on logd's behaviour). Android 13's log-access consent dialog (Google's "Manage your
    device logs" help page, `LogcatManagerService`) is for apps that hold `READ_LOGS` and ask
    for all device logs; this app does not hold it, so no dialog. The logcat docs
    (developer.android.com/tools/logcat, via context7) note that `liblog` drops lines below a minimum priority set by `log.tag.*` properties;
    some OEM builds raise it, so debug lines can be missing from the dump on some phones (the
    file log has them either way).
  - What is not captured, and cannot be: other apps' and the system's lines, including the
    OEM freezer's (`OplusHansManager: freeze uid` / `unfreeze ... reason: SyncBinder`, which
    D51 relied on, read over adb). The switch's description says so, and the README asks
    users to mention their phone model so we know which freezer to suspect. Lines of an
    earlier process are in the dump only while logd's ring buffer still holds them (a few
    hundred KB per buffer by default, shared by all apps); the file log covers longer gaps.
  - "Share detailed log" (Log tab, next to Share/Copy/Clear, which are unchanged; enabled
    with an empty Log tab, since the file reaches further back): builds one text file
    (`log/DetailedReport`): a header (app version and build, Android version, device, ROM
    build `Build.DISPLAY`, the freezing skin from `FreezingOem`, AnkiDroid version, idle
    window, push registered, local server on/off and port, token required, detailed logging
    and its end, battery exemption, notification permission and whether all notifications
    show), the file log oldest first, then the logcat dump when detailed logging is on; the
    whole text is redacted once more. No account data in the header (D48).
  - Sharing: written to `cacheDir/shared_logs/ankimcp-log-<time>.txt` and handed to the
    `ACTION_SEND` chooser as a `content://` URI from androidx `FileProvider` (authority
    `<applicationId>.logs`, not exported, `grantUriPermissions`, paths limited to
    `cache-path shared_logs/` in `res/xml/file_paths.xml`; the file log in `filesDir` is never
    exposed), with `FLAG_GRANT_READ_URI_PERMISSION` and `ClipData` so the grant reaches the
    picked app (setup checked against the androidx FileProvider reference via context7). Only
    one such file exists: a new share replaces it, and at process start the app deletes it
    only if it is an hour old or more (`SharedLogFiles.clearStale`). A younger one stays
    because the receiving app may be what started the process: a mail app reading the
    attachment through the FileProvider after the phone killed us mid-compose (ColorOS does)
    would otherwise find it gone. It is not deleted when the sheet closes either, because
    Android does not say when the receiving app has read it. R8 needs no rule: the
    provider is a manifest component, which AAPT keeps, and nothing here uses reflection.
  - Why no automatic upload or "send to us": the log holds collection-adjacent detail and
    device facts, and the app's data-safety answers say nothing leaves the device except
    through the relay, push registration and Firebase. Sharing stays a user-initiated
    transfer to a recipient the user picks (Play's exemption), so the data-safety answers do
    not change; no network code, no endpoint, no crash SDK.
  - Tests (JVM): the sink (rotation, size cap, append across restarts, level filtering,
    buffering and flush on warn/error, redaction before disk and before the length cut,
    enqueue-only append, an unwritable directory, TunnelLog feeding it, the read timeout,
    four threads writing through the real executor across rotations), the switch (24 h,
    persistence, start-up expiry, clock set back, a toggle racing the expiry, the timer with
    a fake clock), the report (header order and fields, redaction of each secret type in the
    file log and logcat, one shared file, the one-hour startup clear), the redaction rules
    (JSON-quoted headers, digit-less 43-character tokens, CamelCase kept), the logcat reader
    through a fake runner (`-t 2000`, no `--pid`, process markers, line and byte caps), and
    `ProcessCommandRunner` against `sh` (line cap, timeout, a command that cannot start).

## Billing

- **D62. Google Play Billing: Plus and Pro as Play subscriptions, the server verifies and
  acknowledges.** Server contract from the SaaS repo (coordinator, 2026-10-18); the wire
  format is in docs/PROTOCOL.md "Plans and Google Play billing".
  - Library: `com.android.billingclient:billing-ktx` **9.1.0** (released 2026-06-18, the
    newest on Google Maven's `maven-metadata.xml` on 2026-10-18; Play requires a recent major
    for new apps and updates). Checked via context7 (developer.android.com Play Billing docs
    and release notes) and against the 9.1.0 AAR's classes with `javap`: `BillingClient.
    newBuilder().setListener().enablePendingPurchases(PendingPurchasesParams.newBuilder().
    enableOneTimeProducts().build()).enableAutoServiceReconnection()` (the no-argument
    `enablePendingPurchases()` was removed in 8.0; auto reconnection since 8.0), the ktx
    `queryProductDetails` and `queryPurchasesAsync(QueryPurchasesParams SUBS)` suspend
    functions, `launchBillingFlow` with `setObfuscatedAccountId`, `SubscriptionUpdateParams.
    setOldPurchaseToken`, and the per-product `SubscriptionProductReplacementParams`
    (`setOldProductId`, `setReplacementMode`) that 8.1 introduced; `SubscriptionUpdateParams.
    setSubscriptionReplacementMode` is deprecated since 8.1 and has no effect once product-level
    params are set, so it is not used. Its POM pulls in no androidx pin above D3's (activity
    1.2.3, core 1.15 are resolved up to ours), but it does bring play-services-base, -location
    19.0.0, -tasks and datatransport. Its consumer R8 rules ship in the AAR
    (`proguard.txt`), so `proguard-rules.pro` gains nothing; the release build is the check.
    The AAR's manifest declares `com.android.vending.BILLING`, the `<queries>` for the billing
    service and `ProxyBillingActivity`; the app manifest declares the permission again so it is
    visible in review.
  - Layering: `billing/BillingGateway` is a plain-Kotlin interface (products, purchases,
    launch, an updates flow); `PlayBilling` is the only file importing the library, builds the
    client lazily (a process woken by push never binds to Play), and retries the first
    connection twice (1 s, 3 s) for transient codes. `BillingCoordinator` is plain Kotlin and
    unit-tested with fakes. There is no acknowledge method anywhere in the app: the **server**
    acknowledges after verifying with Google (a test checks the gateway has no such method and
    that only products, purchases and launch are called).
  - Products: `ankimcp_plus` (tier plus) and `ankimcp_pro` (tier pro), base plan `monthly`,
    package `ai.ankimcp.ankidroid`, no trial or intro offer. Only the `monthly` base plan's own
    offer (the one without an `offerId`) is used, never another offer instead; a product Play
    does not return, or returns without that offer, is listed as missing and shown "Not
    available in Google Play" with no price and no button (none available at all: "Plans are
    not available yet"). Prices are only Play's `formattedPrice` of the recurring
    phase, shown as "<price> / month" when the period is `P1M`; nothing in the app or its
    strings holds a price.
  - Purchase: `POST /billing/google-play/prepare` first. `blocked` (web or admin entitlement)
    never opens Play. `new` launches with the obfuscated account id from the response; the
    server refuses a purchase without it (403), which ties every Play purchase to one AnkiMCP
    account. `replace` with another product is a plan change: the old purchase token comes
    from `queryPurchasesAsync` (if the live subscription is not on this Play account the card
    says so and points to Manage subscription), passed in `SubscriptionUpdateParams`, plus
    `SubscriptionProductReplacementParams(oldProductId, mode)`. Replacement mode: Plus to Pro
    (upgrade) is `CHARGE_PRORATED_PRICE`, as asked and as Play's "Replacement
    recommendations" table recommends for "Upgrading to a more expensive tier" (immediate, same
    billing date, the difference charged now). Pro to Plus is `DEFERRED`, not
    `CHARGE_PRORATED_PRICE`: Play's docs say that mode "is available only for a subscription
    item upgrade, where the price per unit of time increases", and recommend `DEFERRED` for
    downgrades (the user keeps Pro to the end of the paid period, Plus starts at renewal). Up
    or down is decided by the fixed tier order (Plus < Pro), not by comparing prices.
  - The Activity: `subscribe` takes a provider, asked only when Play's sheet is about to open
    (after `/prepare` and the replace lookup), so no Activity is held across network calls.
    `CurrentActivity` holds the resumed `MainActivity` weakly and returns null when it is
    finishing or destroyed; then the card shows "The app was closed before Google Play could
    open" with Try again (`NoScreen`), and `PlayBilling.launch` checks once more on the main
    thread right before `launchBillingFlow` (after `connect()`, which can take seconds).
  - After the sheet: Play's `PurchasesUpdatedListener` feeds the coordinator. PURCHASED is
    checked like restore does (its `obfuscatedAccountId` must be this account's, from
    `/prepare`, cached per account) and then goes to `POST /billing/google-play/purchase` with
    only `{"purchaseToken"}`; PENDING shows "Payment pending" and sends nothing (the server
    would apply nothing); USER_CANCELED goes back to idle; ITEM_ALREADY_OWNED runs a restore.
    A `DEFERRED` downgrade returns OK with a new PURCHASED token that still lists the old
    product until the renewal. Play's subscriptions guide ("Handle deferred replacement") says
    this for `queryPurchasesAsync`: the new token "remains associated with the old product
    until the deferred replacement takes place". That the listener delivers the same shape was
    inferred on 2026-10-24 from a phone (the card stayed on "Opening Google Play" with no
    register line) and the server (a new token whose line item was still Pro); the app does
    not log the listener's purchases. A purchase that arrives while Play's sheet is up waits
    for the sheet unless it is the sheet's result, and for a plan change the old product
    counts as part of that result, so the downgrade's token is registered like any other
    (before this it waited for itself). OK with a null or empty
    purchase list runs an interactive restore, which finds a new purchase in
    `queryPurchasesAsync` and registers it, or goes back to idle; the card never stays on
    "Opening Google Play".
  - State machine (`PurchaseFlow`): Idle, Preparing, Launching, Pending, Registering, Done,
    Failed. Errors map to: SignedOut, ManagedElsewhere (blocked), NotOwned (403, or a purchase
    of another AnkiMCP account or without an obfuscated id, i.e. bought outside the app),
    AlreadySubscribed (409; the text says Google refunds the duplicate, since the server does
    not acknowledge it), BillingDisabled (503), Server (400, other 4xx, an unreadable 200),
    Network (network, 429, 5xx after retries), PlayUnavailable, ProductUnavailable,
    ReplaceOtherAccount, NoScreen, Play. `/purchase` network errors, 429 and 5xx other than 503
    are retried after 2, 4 and 8 s; after that the card shows the error with Try again and the
    token stays unregistered, so the next refresh sends it again. 403 and 409 tokens are not
    sent again in the same process. No purchase can start while the flow is busy (Preparing,
    Launching, Checking, Registering) or Pending; the buttons are disabled then too. A restore
    sets Checking (no line on the card) before it asks Play for purchases and Registering only
    while it sends tokens, so a Subscribe tap cannot slip in between; at the end it moves only
    its own Checking/Registering on and never overwrites what another path set meanwhile (a
    background restore with nothing to report leaves the card as it was). Checking instead of
    Registering for the Play query: "Activating your plan…" on every resume would be wrong.
    A PURCHASED listener update waits while the user is preparing a purchase, or has Play's
    sheet open for another product, and runs once that flow ends, instead of overwriting it. Try again (on errors and on Pending) cancels a refresh
    that is still running and starts a new one, so the tap is never dropped; a cancelled
    restore never leaves the card on Registering.
  - Never twice: a token being sent is in an in-flight set, so the listener and a restore
    racing on one purchase send it once; an accepted token is stored as its SHA-256 (never the
    token) in the `billing` prefs file (`RegisteredPurchases`, newest 50, cleared when the
    account ends). The server is idempotent per token anyway, so a resend after a reinstall is
    harmless.
  - Account changes: billing state is per account. `AppGraph` watches the credential store's
    user id and calls `onAccountChanged`; any move from an account to null or to another
    account resets everything (`onSignedOut`): the generation is bumped so in-flight status,
    `/prepare` and `/purchase` results of the old account are dropped (no tier of A written
    into B's credentials, no A plan shown), jobs are cancelled, the loaded plan, catalog and
    flow go back to loading, and the registered hashes, the refused and skipped sets and the
    cached obfuscated id are cleared. This covers sessions that end without Sign out:
    `TokenRefresher` clearing credentials on `invalid_grant` and `TunnelConnection` clearing
    them on revoked access. The first value after start (null while decrypting, then the
    account) does not reset, so the persisted hashes survive restarts. `onSignedIn` resets too
    before its forced refresh, and a direct A-to-B change (the state flow can conflate A, null,
    B) refreshes for B after the reset. An empty `user.id` (a token response without it) is
    logged as a warning and treated as the same account, since it cannot tell accounts apart
    and a reset would drop a running purchase.
  - Restore: on sign-in, when the app comes to the front signed in (also covering app start,
    since credentials are decrypted after the first resume), on Refresh and on Try again, the
    coordinator reads `queryPurchasesAsync(SUBS)` and sends every PURCHASED Plus/Pro purchase
    that is not acknowledged yet or not registered from this install, after checking its
    `obfuscatedAccountId` against `/prepare`'s. A purchase of another AnkiMCP account on the
    same Google account, or one without an obfuscated id (bought outside the app), is skipped
    and logged, and its token is remembered for the process so later refreshes neither send it
    nor call `/prepare` for it again; on interactive paths (Try again, ITEM_ALREADY_OWNED,
    the listener) the card shows NotOwned: "belongs to a different AnkiMCP account or was
    bought outside the app ... If it was never activated, Google refunds it automatically
    within 3 days". This picks up purchases completed while the
    app was closed and pending ones that went through, as Play's "Process purchases" guide
    asks (query on start/resume). `/prepare` is only called when there is something to send.
    While a restore sends tokens the flow is Registering, so no purchase can start in between;
    a background restore then goes back to idle without a banner, unless the user was waiting
    on a pending payment (its outcome is shown). A pending payment that Play no longer lists
    (declined, cancelled) clears back to idle on any refresh, background ones included; the
    Pending line has Try again, which checks Play at once.
    Not from `Application.onCreate`: most process starts are push wake-ups (D31) with no UI,
    and binding to Play there costs time for nothing. Refreshes without a reason are throttled
    to one a minute.
  - Plan status: `GET /billing/status` (shape final, coordinator 2026-10-18), behind the
    `BillingStatusSource` seam (`BillingApi` implements it; tests use a fake). Read on sign-in,
    app start and front, after a successful `/purchase`, and on Refresh. A failed reload keeps
    the last loaded status; only a first failure shows "Could not load your plan" with Try
    again. 404, 405 and 501 from `/billing/status` mean the relay has no billing (yet): on the
    phone against dev, where the route is not deployed, it answered 405 and the card looked
    broken. These are `NotAvailable`, logged once per call as a warning (so a broken status route in production stands out in shared logs) "Billing status not
    available on this relay (HTTP 405)", and the card shows only the quiet "Plans are not
    available yet" (the same note as when Play has no products): no error, no Try again, no
    products. 401 keeps the refresh-and-retry handling (a dead refresh token is signed out);
    5xx and network errors stay "Could not load your plan" with Try again. The google-play
    calls do not get this mapping: a 404 there is still an error. The tier it reports is copied into the stored credentials (compare-and-set, so a
    sign-out in between wins), so the account card's "Plan: <tier>" line matches until the
    next token refresh brings the server's own value.
  - Plan card (Status > Remote, signed in, right under the account card, which keeps its
    "Plan:" line): `planCardUi` derives a plain `PlanCardUi` from the coordinator state and is
    JVM-tested. provider none: "Current plan: Free" and both products with Subscribe.
    google_play: the tier, "Renews on <date>", "Canceled; ends on <date>" (status canceled or
    `cancelAtPeriodEnd`), or "Payment problem: renew it in Google Play" (grace, which wins),
    the current product as a disabled "Current plan" and the other as "Switch to <tier>", and
    Manage subscription opening
    `https://play.google.com/store/account/subscriptions?sku=<productId>&package=ai.ankimcp.ankidroid`
    (`ACTION_VIEW`, so the Play Store app handles it). google_play without a `productId`
    (Play billing off on that server) shows the tier and dates only. admin: "Granted to your
    account", no buttons. polar, paddle or an unknown provider: "managed on the web", no
    buttons, no Play link. While preparing, launching, registering or pending every button is
    disabled; Pending (with Try again), Done (Dismiss) and Failed (Try again and Dismiss) show
    a line. Strings are in
    `strings.xml` (`plan_*`). Dates use the locale's medium date format.
  - Logging: product ids, states and HTTP codes only; `PlayPurchase` and `ReplaceRequest`
    redact tokens in `toString`; tests check that neither the purchase token nor the access
    token reaches `TunnelLog`.
  - Waits on Play Console (not doable from this repo): creating `ankimcp_plus` and
    `ankimcp_pro` with the `monthly` base plan and prices, activating them, license testers,
    and an internal-testing upload signed with the upload key (Play only serves products to a
    Play-signed install of this package). Until then `queryProductDetails` returns nothing
    and the card says "Plans are not available yet". Real-time developer
    notifications (RTDN) for renewals, grace and cancellations are the server's job.
  - Tests (JVM): `BillingApiTest` (MockWebServer: prepare's empty POST, new/replace/blocked,
    malformed id or mode, signed out, 401 refresh-and-retry; purchase body exactly
    `{"purchaseToken":...}`, 403/409/503/400/500/network mapping, no tier; status GET, every
    provider and status the server can send, null and unparsable dates, unknown values, 500,
    503 not treated as billing-disabled, 401 twice; no tokens in the log),
    `BillingCoordinatorTest` (new purchase with the obfuscated id, upgrade and downgrade
    modes, same-product replace, missing old purchase, blocked, pending to purchased, pending
    found at restore then completed while closed, 403/409/503, a refused token not resent,
    5xx backoff timing, retries running out then Try again, restore filters and idempotency,
    no `/prepare` without candidates, listener and restore racing on one token, cancel,
    already owned, Play unavailable, status failures, throttling, sign-out reset and a late
    registration after sign-out, no acknowledge; after review: OK without purchases (deferred
    downgrade) leaving Launching through a restore, and back to idle when Play has nothing
    new; account A ending without Sign out while its status is in flight, then B signing in
    (no A tier, no A plan, A's hashes gone); a direct A-to-B switch refreshing for B; the first
    account after start keeping the hashes; Subscribe refused during a background restore and
    while pending; a vanished pending clearing on a background refresh; Try again on a pending
    that still waits; another account's purchase via ITEM_ALREADY_OWNED shown as NotOwned and
    never prepared again; a purchase without an obfuscated id on Try again; the listener's
    account check; the screen asked for only after `/prepare` and a gone screen failing with
    NoScreen; Try again restarting a running refresh; missing products; Subscribe refused
    while Play is asked (Checking); a background restore with nothing to send keeping a Done
    line; a listener purchase waiting for the user's Preparing/Launching flow; an empty user id
    as the same account; a relay without billing as NotAvailable with no error, a 5xx still
    Failed), `BillingApiTest` also maps status 404/405/501 to NotAvailable with one warning
    line per call, and keeps a google-play 404 an error, `PlanCardTest`
    (each provider and status, renewal lines, buttons, catalog notes, busy, registering and
    pending disabling the buttons, a product without the base plan shown unavailable, none
    available as the empty note).

## Remote access

- **D63. A persisted "Remote access" switch: off is a real off.** Owner decision (2026-10-19).
  Before it, Disconnect only ended the session: the relay kept sending an FCM wake-up for
  every request of an MCP client (clients poll on their own), and `WakeHandler` reconnected
  on each one as long as the user was signed in. There was no way to stop the phone from
  answering Claude short of signing out.
  - Setting: `settings/RemoteAccessSettings` (`remote_enabled` in the "settings" prefs file,
    `KeyValueStore` + StateFlow like `LocalServerSettings`). Default on; an absent key counts
    as on, so an update changes nothing. It survives sign-out and sign-in: it is a property of
    the phone, not of the account.
  - Off: `MainViewModel.setRemoteEnabled(false)` writes the setting, stops the tunnel through
    `TunnelForegroundService.stop(context, "Remote access off")` (the reason shows as the
    stopped state; the service stops unless the local server is on, as after Disconnect), and
    calls `PushCoordinator.onRemoteDisabled()` on the app scope. That removes the registered
    token with `DELETE /devices/push` the way logout does it, under the registration lock:
    the `PendingTokenDelete` record is written *before* the registration is cleared, so a
    process death in between, or a DELETE that does not get through (network, 5xx), leaves a
    record the network-bound delete worker and the next app start finish. `SignedOut` (the
    credentials are gone) keeps the record too, but without a worker: only credentials can send
    it, so `finishPendingDeleteLocked` leaves a Remote-off record (no local delete owed) alone
    while signed out, and a sign-in with the switch off runs the pending-delete path instead
    of `sync` (a logout takes it over as well). Unlike
    logout it keeps the credentials and the FCM token (no `deleteToken`, auto-init untouched),
    and sets no signing-out state; the registration record is cleared, so `wakeUpsReady` is
    false. The switch is read again under the lock: off and on are two independent coroutines,
    and an "off" that runs after the user's final "on" must not remove what that "on"
    registered (symmetrically, `sync` bails when the switch is off). A logout already in
    progress removes the token itself and the switch leaves it to it. While off, `sync`
    returns early for every trigger (`onNewToken`, the daily check, the retry worker,
    sign-in), so nothing registers again; `onAppStart` runs the disable path instead, which
    also catches a token left registered by a process that died mid-switch (the stored token,
    or the current FCM token when the last attempt was still retrying without one, as logout
    does). `WakeHandler.onMessage` answers `RemoteOff` with an info line before any other
    check: no service start, no tunnel start, no nudge; and checks the switch once more after
    the service start, stopping the run it just started with "Remote access off" (the
    switch's own stop found nothing running yet). `TunnelForegroundService` does not start the
    tunnel while the switch is off (a Connect queued before the flip) and ends itself if
    nothing else runs; `connect()` is a no-op. Belt and braces: with the switch off, a run
    that still got through keeps a Disconnect button under the off line.
  - On: `onRemoteEnabled()` registers like a sign-in (`store.clear()`, then the sign-in
    trigger, which re-registers even an up-to-date token and makes sure the daily check is
    enqueued). Nothing connects by itself: the user taps Connect, or the next wake-up
    connects as before. Signing in with the switch off registers nothing; turning it on
    later registers.
  - Relay side (SaaS, coordinator 2026-10-19): the DELETE is effective at once. With no
    connected tunnel and no registered device the relay answers the MCP request itself with
    JSON-RPC error `-32000`, message "AnkiMCP on your phone is turned off or not signed in.
    Open the app, sign in and turn Remote access on, then ask me again." (HTTP 200; a
    notification gets HTTP 503 with the same body; `initialize` and `tools/list` get the same
    error, there is no cached tools list). Relays before the next tunnel roll still send the
    old text "Your phone's AnkiDroid MCP is not connected. Open the app and tap Connect, then
    ask me again." A push already in flight when the token was removed (up to 5 s to send,
    FCM ttl 30 s) may still arrive; the app ignores it (`RemoteOff`).
  - UI (owner correction after the phone test): the switch row is the first card of the
    Remote tab, signed in or out, laid out like the Local tab's switch row (title + Switch,
    same card, padding and spacing). Off, one line under it says "Remote access is off. Claude
    can't reach this phone through ankimcp.ai until you turn Remote access on." (wording
    matches the relay's error), with the Disconnect fallback for a run that got past the
    switch. Below it the sign-in card (signed out) or the account card and the Plan card
    (signed in) as before, then, only while the switch is on, the Tunnel card (status,
    Connect/Disconnect, MCP URL) and the Connection card (idle window, push status), both
    unchanged; off hides them, since no registration exists. The Remote dot is grey while
    off, whatever the tunnel state still says (`statusTabDot` takes `remoteEnabled`);
    TalkBack's word is "Not connected".
  - Tests (JVM): `RemoteAccessSettingsTest` (default, absent key, persistence both ways),
    `WakeHandlerTest` (wake while off: `RemoteOff`, no service or tunnel start, no nudge even
    with a stale connected run, the log line), `PushCoordinatorTest` (off removes the token
    with the credentials still there and keeps the FCM token and auto-init; a failed DELETE
    is pending, retried at app start and finished by the worker while app start, new token,
    daily check, retry worker and sign-in register nothing; on registers again like a sign-in;
    sign-in while off registers nothing; nothing registered sends no DELETE; off during a
    logout leaves the removal to the logout; an off running after the final on removes
    nothing, and the reverse order ends unregistered; off, on, off; app start with the switch
    off and a stored token, or a retrying attempt without one, sends the DELETE and is
    idempotent; a `SignedOut` DELETE stays owed through the worker and app starts while signed
    out and is sent by the next sign-in with the switch off; on while the off's
    DELETE is in flight registers after it; on with a DELETE still owed sends it before the
    PUT, or registers nothing and schedules a retry when it fails again; logout takes over a
    DELETE Remote-off left owed), `LocalServerServiceRulesTest` (no tunnel start while off),
    `WakeHandlerTest` (the switch flipped after the entry check stops the run),
    `StatusTabsTest` (grey Remote dot when off).

## Not done

- D62 (Google Play Billing): JVM-tested only, with fake Play and MockWebServer. Not yet run
  against Play or the dev server: the products do not exist in Play Console yet, and an
  install has to come from Play (internal testing) to see them. To check on a device: the
  purchase sheet with the obfuscated id, the server's acknowledge (Play refunds unacknowledged
  purchases after 3 days), a test-card "slow" pending purchase, the Plus/Pro upgrade charge
  and the deferred downgrade, restore after reinstall, and the Manage subscription link. The
  server's handling of a deferred replacement's new token (it carries both line items until
  renewal) should be checked on the server side.
- On-device verification: no emulator or device was available when this was built. This
  includes the Doze behaviour (D27), Android 16 (D1), and the whole push path (D31-D39).
- No end-to-end run against the production relay, and `/devices/push` was built to the agreed
  contract without a server to test against.
- No build with a real `google-services.json` was made (the file was not available), so the
  google-services plugin path is untested.
- FID-based FCM registration (D38) is not adopted yet.
- D40-D44 were built before the push trial; the trial (D44, D49) confirmed the push wake and
  the screen-off connect on dev. D49 is unit-tested only and not yet re-run on the phone.
- D51 (process-lifetime provider client, short retry, Frozen error) was verified on the phone
  on 2026-10-18 (see D51); D52 (request dedupe) is unit-tested only until the relay
  re-forwards.
- D45-D48 were built and unit-tested without running on a device; the icon and colours have
  not been checked on a launcher or in dark mode yet.
- No Play listing assets, privacy policy or data-safety form (the
  data-safety form will need to declare the FCM token, see D39).
- The relay accepts `x-ankimcp-client-type: ankidroid` (shared-types 0.18.1) and serves it on
  the dedicated `ankidroid.ankimcp.*` host; deployed on dev, production pending (PROTOCOL.md).
- D53-D56 (local Wi-Fi server): verified on the phone on 2026-10-18 over Wi-Fi (the
  endpoints, status codes and Claude Code end to end, D55, with the macOS Local Network
  caveat) and screen-off on ColorOS with and without the battery exemption (D56). Not run on
  a device yet: rebinding on a Wi-Fi change, and the open default from the owner decision
  (D55), which landed after the device test (the test ran with a token).
- Pre-submit checklist for the local server: data-safety.md's "encrypted in transit" answer
  is qualified for the plain-HTTP local mode and must be re-checked against Play's
  definitions; the battery-exemption justification (D56) must be re-read against Google's
  current Doze table, with the option of dropping the request from the first submission.
- D57 (tool set, reads): JVM-tested against a fake provider only. Not yet run on a device: the
  rendered question/answer and `data-cloze` stripping, what an invalid `notes` search really
  does over the binder, the `options` JSON keys, `did:`/`nid:` list searches on AnkiDroid's
  rslib, and the time `cards_stats`/`notes_info` take with AnkiDroid rendering every card.
  (The reads passed the phone battery on 2026-10-18.)
- D57 writes and D58: JVM-tested against a fake provider only. Not yet run on a device: id
  recovery after `bulkInsert`, the `schedule` answer/bury/suspend confirmation, create_model's
  cloze switch, model_field_add's schema change, the 2.24 delete count, and the sync
  notification starting AnkiDroid's sync.
- D61 (detailed log): JVM-tested only. Not yet run on a device: the logcat dump without
  `READ_LOGS` (expected: our uid's lines only, earlier processes included, no dialog),
  how long a killed process's lines survive in logd, whether ColorOS drops our debug lines
  from logcat, the share sheet with the `content://` URI in mail, Telegram and Drive, and
  the file log's behaviour across a Hans freeze and a process kill.
