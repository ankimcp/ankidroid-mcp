# Decisions (phase 1)

Decisions made while building phase 1 without a chance to ask. Each one is cheap to revisit.

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
  - JobScheduler quota changes: no jobs are used.
  Not verified on an Android 16 device or emulator yet.

## Build and stack

- **D2. AGP 8.13.2 + Gradle 8.14.3 + Kotlin 2.2.21**, not AGP 9.x. AGP 9 turns on built-in
  Kotlin and the new DSL by default; that migration is a separate task. AGP 8.13.2 is the
  newest 8.x and supports API 36. JDK 17 bytecode; builds with JDK 21.
- **D3. androidx pins** are the newest releases whose AAR metadata allows compileSdk 36 with
  AGP 8.13 (checked against `minCompileSdk` / `minAndroidGradlePluginVersion` on Google Maven):
  core-ktx 1.18.0, activity-compose 1.13.0, lifecycle 2.10.0, browser 1.10.0, Compose BOM
  2026.06.01 (ui 1.11.4, material3 1.4.0). core 1.19, lifecycle-*-compose 2.11 and Compose UI
  1.12 (BOM 2026.08+) require compileSdk 37 and AGP 9.1. `lifecycle-process` (same 2.10.0) is
  declared explicitly for `ProcessLifecycleOwner` (D20); it was already on the runtime
  classpath transitively, so it adds nothing to the APK.
- **D4. OkHttp 4.12.0** as the brief asked (5.x exists). MockWebServer 4.12.0 for tests.
- **D5. JUnit 4 + kotlinx-coroutines-test + MockWebServer.** No Robolectric: everything
  under test is plain Kotlin. `unitTests.isReturnDefaultValues = true` covers `android.util.Log`.
  Android-only pieces (Keystore cipher, wake locks, alarms, `elapsedRealtime`) sit behind small
  interfaces so the logic around them is tested on the JVM.
- **D6. Manual DI** (`AppGraph`, created in `AnkiMcpApp`). One module, about 20 classes: Hilt
  would add a kapt/ksp step and annotation noise for no real benefit yet.
- **D7. Detekt, ktlint and explicit API mode are not configured.** Explicit API mode is for
  libraries; this is a single app module. Adding detekt/ktlint is a small follow-up.
- **D8. Tunnel URL**: `BuildConfig.TUNNEL_URL`. Debug builds read the Gradle property
  `ankimcp.tunnelUrl` (default `wss://tunnel.ankimcp.ai`); release is hard-coded to the
  production URL so a stray property can't ship. `network_security_config.xml` allows
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
  deep-links to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; the app does not declare
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play restricts it).

## Auth and storage

- **D15. Credentials** are stored as the add-on's JSON, encrypted with AES-256-GCM under a
  non-exportable AndroidKeyStore key (`ankimcp_credentials_v1`), in a private
  SharedPreferences file. No user-authentication requirement on the key, since the tunnel must
  reconnect in the background. The blob is read lazily on first `load()`, never on the main
  thread: `AnkiMcpApp` triggers it on `Dispatchers.IO` and the UI shows a spinner until it
  finishes; `BootReceiver` uses `goAsync()`, reads on `Dispatchers.IO` and then finishes the
  broadcast. Only errors that mean the blob can never be decrypted (`AEADBadTagException`,
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
  the in-app log is shown verbatim in the UI and mirrored to logcat.

## Tunnel and service

- **D18. Deviations from the add-on's reconnect behaviour** (offline waiting, handshake close
  codes, auth-loop guard, network-change reconnect, keep-connected retries, device-flow retries)
  are listed in docs/PROTOCOL.md under "Deviations".
- **D19. Foreground service** type `specialUse` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  explanation. The type is passed to `startForeground` only on API 34+; on 26-33 the plain
  overload is used (passing an unknown type bit there can throw). The service only owns the
  process lifetime and the notification; the tunnel state machine lives in `AppGraph` so the
  UI can observe it directly.
- **D20. Connect is always user-initiated** on first use. "Keep connected" (default off) makes
  the app auto-connect on launch, after boot and after an app update (`BootReceiver`), and keeps
  retrying every 30 s after the 10-attempt budget is used up (D28). The service is
  `START_NOT_STICKY`, so a process kill does not silently resurrect a tunnel. Disconnect, in the
  app or from the notification action, also turns "Keep connected" off: an explicit disconnect
  should not be undone by the next launch or boot.
  - Android 12+ refuses `startForegroundService` from the background. Every start goes through
    `TunnelForegroundService.start`, which catches the refusal
    (`ForegroundServiceStartNotAllowedException`, an `IllegalStateException`, which is also
    what 26-30 throw), logs it to the tunnel log and shows the tunnel as stopped with "Could not
    start in the background. Open the app to connect." (unless a tunnel is already running).
  - Auto-connect on launch waits for the credential decrypt *and* for the process to be in the
    foreground (`ProcessLifecycleOwner` at least `STARTED`). If the user left the app before
    the decrypt finished, it connects the next time the app comes to the foreground (while the
    ViewModel is alive). The conditions (keep connected, signed in, AnkiDroid ready, not
    already running) are checked at that moment. `ProcessLifecycleOwner` dispatches `ON_STOP`
    700 ms after the last activity stops; if Android refuses a start made in that window, the
    catch above handles it.
- **D21. POST_NOTIFICATIONS** is requested when the user taps Connect (API 33+). The tunnel
  starts whatever the answer is; without the permission the FGS runs with a hidden notification.
- **D22. MCP URL** shown on the Status screen is the `url` from `tunnel_established` when
  connected, otherwise `https://<tunnel host>/mcp` derived from the configured WebSocket URL.
- **D27. Doze / CPU suspend.** A foreground service keeps the process and its network access,
  but the CPU still suspends, `System.nanoTime` stops, and coroutine timers do not fire. So:
  - All liveness timing (last server ping, health check) uses `SystemClock.elapsedRealtime()`
    via the `ElapsedClock` interface (tests inject a fake).
  - A `PARTIAL_WAKE_LOCK` (always with a timeout) is held during each connection attempt
    (refresh + open + handshake, 45 s cap, released on `tunnel_established`), during backoffs
    of up to 5 s, around a token refresh after an auth close, and around each relay request
    (25 s budget + 5 s). Requires `WAKE_LOCK`. The request lock is released from the request
    job's completion handler, so it is also released when the request is cancelled before its
    coroutine ever ran (socket dropped right after the request arrived).
  - Backoffs longer than 5 s release the CPU and schedule an alarm (D29); when it fires,
    `TunnelAlarmReceiver` takes a 10 s bridging wake lock and cuts the backoff short.
  - While connected, the same alarm fires every 60 s and re-runs the health check, so a socket
    that died silently (no more pings, so nothing wakes the CPU) is still detected in Doze.
    The alarm is shared with the backoff, so it is re-armed only if, after the check, the
    session that was checked is still the active one and connected (checked under the same
    lock as the generation guard). If the session died in between, the loop's backoff alarm
    stays armed. `stop()` cancels the alarm itself, since the cancelled loop no longer owns it.
  - The network monitor only reports a network once Android has validated it
    (`NET_CAPABILITY_VALIDATED`), so captive portals don't burn attempts. Risk: networks where
    validation never succeeds (blocked connectivity check) look offline, and the tunnel waits.
  **Pending:** on-device Doze testing (`adb shell dumpsys deviceidle force-idle`, then
  `unforce`) has not been done; no device or emulator was available.
- **D28. Keep-connected retries.** Without "Keep connected" the add-on's rule stands: stop
  after 10 consecutive failures. With it on, the loop keeps going at the 30 s cap (state
  `Reconnecting(persistent = true)`, shown as "Retrying every 30s"). In Doze the alarm limit
  (D29) stretches that to whatever the system allows.
- **D29. `setAndAllowWhileIdle`, not exact alarms.** `setExactAndAllowWhileIdle` needs
  `SCHEDULE_EXACT_ALARM` (denied by default since Android 14, user must grant it in settings)
  or `USE_EXACT_ALARM` (Play only allows it for alarm-clock/calendar apps). The inexact
  while-idle alarm needs no permission and still fires in Doze, but the system dispatches these
  at most about once a minute, and in deep Doze about every 15 minutes, so retries and liveness
  checks are that coarse while the phone sleeps.

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

## Not done in phase 1

- On-device verification: no emulator or device was available when this was built. This
  includes the Doze behaviour (D27) and Android 16 (D1).
- No end-to-end run against the production relay.
- Placeholder launcher icon; no Play listing assets, privacy policy or data-safety form.
- The relay's `ClientTypeSchema` does not know `ankidroid` yet (harmless, see PROTOCOL.md).
