# Decisions

Decisions made while building phase 1, the push wake-up feature after it (D31-D39), the
fixes after the on-device trials (D40-D44, D49), the branding and UI round (D45-D48) and the
removal of "Always connected" (D50), without a chance to ask. Each one is cheap to revisit.

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
- **D12. `list_decks` output** follows the brief's shape `{id, name, counts:{new,learn,review}}`,
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
  deep-links to settings; the app does not declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (D42).

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
  after an idle timeout (D32).
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
    process start does not stop anything. The sign-in screen does not show the reason of a
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
    dialog; it was dropped before release.)
  - "Open battery settings" opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the system
    list; App info if that does not resolve). `PowerManager.isIgnoringBatteryOptimizations`
    drives the tick.
  - `service/FreezingOem` matches `Build.MANUFACTURER`/`Build.BRAND` (lower-cased, exact string)
    against oppo, realme, oneplus, xiaomi, redmi, poco, vivo, iqoo, huawei, honor. On those
    makers the card sits right below the tunnel card until the exemption is granted, names the
    maker's own switches (ColorOS: "Allow background activity" and "Don't optimise"), and its
    button is labelled "Open app info" and opens the app's own App info page
    (`ACTION_APPLICATION_DETAILS_SETTINGS`), where those makers put the per-app battery page;
    each maker's text says where to go from there (ColorOS: "In App info > Battery usage, ...").
    No maker publishes an intent for that sub-page, and
    hard-coded component names of OEM settings activities change between skin versions, so the
    app does not guess them. The card stays lower down after the exemption is granted, since
    the maker's switches cannot be read.
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
  background setting.** A frozen AnkiDroid answered the deck query with a null cursor in
  13 ms. The first version assumed acquiring the provider thaws AnkiDroid's process. The push
  trial on 2026-10-16 (Realme, ColorOS) disproved that: Hans freezes AnkiDroid about 6 s after
  the screen goes off, and its binder calls are then proxied (`OplusBinderProxy proxy: true`
  in logcat) instead of thawing it, so `AnkiProvider` keeps getting a null cursor. With
  "Allow background activity" on for both apps the same query works with the screen off (36
  decks in 342 ms, AnkiDroid cold-started by the query).
  - The retry stays (after 0.5 s and 1.5 s, three attempts over about 2 s, each a separate
    submission to the provider pool, inside the 20 s budget of D13), because it is cheap and
    still helps when AnkiDroid is merely slow to start; it does not help against a freezer.
  - When it fails, the tool error says AnkiDroid was put to sleep in the background, gives
    "open AnkiDroid once and try again" as the quick fix, and points at allowing AnkiDroid to
    run in the background (with the ColorOS path as the example) and at the Status tab.
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
- **D47. Three bottom-navigation destinations once signed in: Status, Log, About.** Material's
  navigation bar is the component for 3-5 top-level destinations; tabs are for related views
  inside one screen. The selected destination is a `rememberSaveable` enum in `AppRoot`, not
  Navigation Compose: three flat screens with no back stack do not need the dependency. Back
  from Log or About returns to Status (a `BackHandler`; Android's "Principles of navigation":
  the start destination is the last screen the user sees before leaving); Back on Status
  leaves the app. The selection resets to Status when the user is
  signed out or AnkiDroid is no longer ready, but not while credentials are still loading, so a
  tab restored after process death survives. Onboarding and sign-in keep the old single
  screen without the bar. Status keeps the account card (with Sign out), the tunnel card
  (state dot and text, Connect/Disconnect, the MCP URL in a field with a "Copy URL" button), the
  connection card, notifications and the battery card in the D42 positions. Copy feedback
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

## Not done

- On-device verification: no emulator or device was available when this was built. This
  includes the Doze behaviour (D27), Android 16 (D1), and the whole push path (D31-D39).
- No end-to-end run against the production relay, and `/devices/push` was built to the agreed
  contract without a server to test against.
- No build with a real `google-services.json` was made (the file was not available), so the
  google-services plugin path is untested.
- FID-based FCM registration (D38) is not adopted yet.
- D40-D44 were built before the push trial; the trial (D44, D49) confirmed the push wake and
  the screen-off connect on dev. D49 is unit-tested only and not yet re-run on the phone.
- D45-D48 were built and unit-tested without running on a device; the icon and colours have
  not been checked on a launcher or in dark mode yet.
- No Play listing assets, privacy policy or data-safety form (the
  data-safety form will need to declare the FCM token, see D39).
- The relay accepts `x-ankimcp-client-type: ankidroid` (shared-types 0.18.1) and serves it on
  the dedicated `ankidroid.ankimcp.*` host; deployed on dev, production pending (PROTOCOL.md).
