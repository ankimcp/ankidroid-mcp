# AnkiDroid MCP

Android app (Kotlin, Compose) that serves MCP over the AnkiMCP tunnel relay and reads AnkiDroid
through its ContentProvider. Single `app` module, package `ai.ankimcp.ankidroid`.

## Commands

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests 'ai.ankimcp.ankidroid.tunnel.TunnelClientTest'
./gradlew :app:lintDebug
./gradlew :app:assembleRelease                                   # R8, unsigned
./gradlew :app:assembleDebug -Pankimcp.tunnelUrl=wss://ankidroid.ankimcp.uk   # dev relay
```

Needs JDK 17+ and Android SDK platform 36. Versions live in `gradle/libs.versions.toml`;
androidx pins are capped at releases that compile against SDK 36 with AGP 8.13
(docs/DECISIONS.md D1-D3). `app/google-services.json` is optional and gitignored (Bitwarden,
see README); without it the google-services plugin is skipped and push is unavailable (D33).

## Layout

```
app/src/main/java/ai/ankimcp/ankidroid/
  AnkiMcpApp.kt, AppGraph.kt   manual DI; AppGraph owns the long-lived objects
  config/      AppConfig (BuildConfig.TUNNEL_URL, version)
  settings/    ConnectionSettings (ConnectionMode OnDemand/Always, IdleWindow 15/30/60,
               migrates the old keep_connected flag), FreezeNotice (persisted "phone froze us"
               flag), KeyValueStore (SharedPreferences seam)
  auth/        DeviceFlowClient, Credentials, CredentialStore (Keystore AES-GCM), TokenRefresher
  tunnel/      Protocol (messages, close codes), TunnelClient (OkHttp WS, one connection,
               freeze-aware ping watchdog), ReconnectPolicy, TunnelConnection (reconnect state
               machine + idle timer + screen-off/freeze stop + push-wake wake window),
               WakeSupport (ElapsedClock, WakeLocks, WakeupScheduler interfaces)
  push/        PushMessaging (Firebase seam) + FirebasePushMessaging, PushTokenRegistrar
               (/devices/push), PushRegistrationState(+Store), PushCoordinator (when to
               register, logout order), PushRegistrationWorker (WorkManager retry + daily
               check), WakeHandler (wake message -> FGS + tunnel), AnkiMcpMessagingService (FCM)
  mcp/         JsonRpc, McpServer (stateless), Tool + ToolRegistry
  anki/        FlashCardsContract (vendored, keep header), AnkiProvider (ContentResolver seam),
               AnkiDroidRepository, AnkiDroidAvailability
  tools/       ListDecksTool
  service/     TunnelForegroundService (specialUse FGS), BootReceiver, NetworkMonitor,
               ScreenMonitor (screen off/on broadcasts), FreezingOem (OEM skins that freeze apps),
               AndroidWakeSupport (elapsedRealtime, uptime, wake locks, while-idle alarms + receiver),
               SignInNotifications ("Sign in again" notification), NotificationPermission
               (permission, app switch and channel check per notification, logs dropped ones)
  ui/          MainActivity (Status/Log/About bottom navigation), MainViewModel, Onboarding/SignIn/
               Status/Log/About screens, Clipboard, Theme (brand colours + StatusColors)
  log/         TunnelLog (100-entry ring buffer shown in the UI), LogReport (shared-log text and
               redaction)
app/src/test/java/...          JVM unit tests (JUnit 4, coroutines-test, MockWebServer)
```

## Rules

- The tunnel protocol must stay byte-compatible with the desktop add-on
  (`../anki-mcp-server-addon/anki_mcp_server/tunnel/`) and the relay
  (`../anki-mcp-saas/apps/tunnel/`). Any intentional difference goes into the "Deviations"
  section of docs/PROTOCOL.md. The push registration contract is in the same file.
- Never log tokens (access, refresh or FCM). `TunnelLog` is rendered in the UI and mirrored to
  logcat.
- Only `push/FirebasePushMessaging.kt` and `push/AnkiMcpMessagingService.kt` may import
  Firebase; everything else goes through `PushMessaging` so tests never touch Firebase and the
  app runs without `google-services.json`.
- Tool failures are `ToolResult.error(...)` (`isError: true`) with a human-readable message, not
  exceptions or JSON-RPC errors.
- All ContentProvider access goes through `AnkiProvider` + `AnkiDroidRepository` (bounded
  thread pool, timeout). Keep Android types out of tools so they stay unit-testable.
- New tools: implement `mcp.Tool` with a `JsonObject` input schema, register in `AppGraph`.
- No instrumented tests yet; keep logic in plain Kotlin and test it on the JVM.
