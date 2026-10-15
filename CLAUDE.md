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
./gradlew :app:assembleDebug -Pankimcp.tunnelUrl=ws://10.0.2.2:3004   # dev relay
```

Needs JDK 17+ and Android SDK platform 36. Versions live in `gradle/libs.versions.toml`;
androidx pins are capped at releases that compile against SDK 36 with AGP 8.13
(docs/DECISIONS.md D1-D3).

## Layout

```
app/src/main/java/ai/ankimcp/ankidroid/
  AnkiMcpApp.kt, AppGraph.kt   manual DI; AppGraph owns the long-lived objects
  config/      AppConfig (BuildConfig.TUNNEL_URL, version), AppSettings ("keep connected")
  auth/        DeviceFlowClient, Credentials, CredentialStore (Keystore AES-GCM), TokenRefresher
  tunnel/      Protocol (messages, close codes), TunnelClient (OkHttp WS, one connection),
               ReconnectPolicy, TunnelConnection (reconnect state machine),
               WakeSupport (ElapsedClock, WakeLocks, WakeupScheduler interfaces)
  mcp/         JsonRpc, McpServer (stateless), Tool + ToolRegistry
  anki/        FlashCardsContract (vendored, keep header), AnkiProvider (ContentResolver seam),
               AnkiDroidRepository, AnkiDroidAvailability
  tools/       ListDecksTool
  service/     TunnelForegroundService (specialUse FGS), BootReceiver, NetworkMonitor,
               AndroidWakeSupport (elapsedRealtime, wake locks, while-idle alarm + receiver)
  ui/          MainActivity, MainViewModel, Onboarding/SignIn/Status screens, Theme
  log/         TunnelLog (100-entry ring buffer shown in the UI)
app/src/test/java/...          JVM unit tests (JUnit 4, coroutines-test, MockWebServer)
```

## Rules

- The tunnel protocol must stay byte-compatible with the desktop add-on
  (`../anki-mcp-server-addon/anki_mcp_server/tunnel/`) and the relay
  (`../anki-mcp-saas/apps/tunnel/`). Any intentional difference goes into the "Deviations"
  section of docs/PROTOCOL.md.
- Never log tokens. `TunnelLog` is rendered in the UI and mirrored to logcat.
- Tool failures are `ToolResult.error(...)` (`isError: true`) with a human-readable message, not
  exceptions or JSON-RPC errors.
- All ContentProvider access goes through `AnkiProvider` + `AnkiDroidRepository` (bounded
  thread pool, timeout). Keep Android types out of tools so they stay unit-testable.
- New tools: implement `mcp.Tool` with a `JsonObject` input schema, register in `AppGraph`.
- No instrumented tests yet; keep logic in plain Kotlin and test it on the JVM.
