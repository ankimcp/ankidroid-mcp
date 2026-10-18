# AnkiMCP

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
  settings/    ConnectionSettings (IdleWindow 15/30/60; drops the old connection_mode and
               keep_connected keys, D50), FreezeNotice (persisted "phone froze us"
               flag), LocalServerSettings (local server switch, port, "Wi-Fi only" sign-in
               skip), DetailedLogging (Log tab switch, auto-off after 24 h, D61),
               KeyValueStore (SharedPreferences seam)
  auth/        DeviceFlowClient, Credentials, CredentialStore (Keystore AES-GCM), TokenRefresher
  tunnel/      Protocol (messages, close codes), TunnelClient (OkHttp WS, one connection,
               freeze-aware ping watchdog), RequestDedupe (process-wide at-most-once per
               request_id, D52), ReconnectPolicy, TunnelConnection (reconnect state
               machine + idle timer + screen-off/freeze stop + push-wake wake window),
               WakeSupport (ElapsedClock, WakeLocks, WakeupScheduler interfaces)
  push/        PushMessaging (Firebase seam) + FirebasePushMessaging, PushTokenRegistrar
               (/devices/push), PushRegistrationState(+Store), PushCoordinator (when to
               register, logout order), PushRegistrationWorker (WorkManager retry + daily
               check), WakeHandler (wake message -> FGS + tunnel), AnkiMcpMessagingService (FCM)
  billing/     Play subscriptions (D62): BillingGateway (plain-Kotlin seam) + PlayBilling (the only
               Play Billing Library import), BillingApi (/billing/status, /billing/google-play/
               prepare and /purchase), BillingCoordinator (purchase state machine, restore, never
               acknowledges: the server does), RegisteredPurchases (SHA-256 of accepted tokens)
  mcp/         JsonRpc, McpServer (stateless; tools, prompts, resources), Tool + ToolRegistry
               (readOnly/writeAnnotations), PromptsAndResources (Prompt, Resource, registries)
  local/       Local Wi-Fi MCP server (D53-D56): McpHttpAdapter (Streamable HTTP over McpServer;
               Host/Origin, bearer token, 405s), HttpServerEngine + NanoHttpServerEngine (the
               only NanoHTTPD import), LocalServer (bind/rebind to the Wi-Fi address, state),
               LocalServerTokenStore (Keystore AES-GCM like CredentialStore), WifiAddress
               (bind-address selection), ClientConfig (URL, Claude Code/Desktop snippets)
  anki/        FlashCardsContract (vendored, keep header), AnkiProvider (generic query/insert/
               update/delete/bulkInsert on paths, rows copied out as plain maps; logic in
               EndpointAnkiProvider over a ProviderEndpoint seam, tested with fakes), ProviderPin
               (holds AnkiDroid's provider client for the process lifetime, D51; writes never
               retry), AnkiDroidRepository (typed reads with null retry and the empty-note-search
               probe, `write` that runs once and reports a timeout as outcome unknown, version
               gate, CallBudget shared by one tool's calls, cards read <=100 per call),
               AnkiDroidAvailability (installed, permission, AnkiDroidVersion), AnkiDroidSync
               (seam for the sync notification; Android side service/NotificationSyncRequester)
  tools/       The add-on's tools AnkiDroid can serve (D57): reads in ListDecksTool, ModelTools,
               NoteReadTools, CardReadTools (incl. get_due_cards), DeckReadTools; writes in
               NoteWriteTools (create_deck, add_note(s), update_note_fields, update_notes,
               delete_notes), ModelWriteTools, TagWriteTools, CardWriteTools (card_*, rate_card);
               SyncTool (D58). ToolSupport (AnkiTool base, ToolArgs, ToolFailure/fail with the
               add-on's error text, search/cloze helpers), WriteSupport (20-item batch limit,
               WriteProgress "did N of M"), PatchHelpers (exactly-once patch), AnkiTags (rslib
               tag rules). Registered in AppGraph.ankiTools
  prompts/     AddonPrompts: review_session, twenty_rules, anki://query-syntax (texts under
               src/main/resources/ai/ankimcp/ankidroid/prompts/)
  service/     TunnelForegroundService (specialUse FGS; also runs while the local server is on),
               NetworkMonitor, WifiMonitor (Wi-Fi IPv4 address for the local server),
               ScreenMonitor (screen off/on broadcasts), FreezingOem (OEM skins that freeze apps),
               AndroidWakeSupport (elapsedRealtime, uptime, wake locks, while-idle alarms + receiver),
               SignInNotifications ("Sign in again" notification), NotificationPermission
               (permission, app switch and channel check per notification, logs dropped ones)
  ui/          MainActivity (Status/Log/About bottom navigation), MainViewModel, SignInCard, Onboarding/
               Status/Log/About screens, LocalServerCard, PlanCard (+ planCardUi derivation),
               Clipboard, Theme (brand colours +
               StatusColors)
  log/         TunnelLog (100-entry ring buffer shown in the UI, single entry point, feeds
               LogSinks), FileLogSink (always-on rotating file log, 2 x 1 MB, redacted, own
               thread, D61), LogReport (shared-log text and redaction), LogcatReader (the app's own
               logcat lines, all its processes, behind the CommandRunner seam), DetailedReport + SharedLogFiles ("Share
               detailed log" text and the cacheDir/shared_logs file behind the FileProvider)
app/src/test/java/...          JVM unit tests (JUnit 4, coroutines-test, MockWebServer)
```

## Rules

- The tunnel protocol must stay byte-compatible with the desktop add-on
  (`../anki-mcp-server-addon/anki_mcp_server/tunnel/`) and the relay
  (`../anki-mcp-saas/apps/tunnel/`). Any intentional difference goes into the "Deviations"
  section of docs/PROTOCOL.md. The push registration contract is in the same file.
- Never log tokens (access, refresh, FCM, Play purchase or the local server's bearer token). `TunnelLog` is
  rendered in the UI and mirrored to logcat.
- Only `push/FirebasePushMessaging.kt` and `push/AnkiMcpMessagingService.kt` may import
  Firebase; everything else goes through `PushMessaging` so tests never touch Firebase and the
  app runs without `google-services.json`.
- Tool failures are `ToolResult.error(...)` (`isError: true`) with a human-readable message, not
  exceptions or JSON-RPC errors.
- All ContentProvider access goes through `AnkiProvider` + `AnkiDroidRepository` (bounded
  thread pool, timeout). Keep Android types out of tools so they stay unit-testable.
- New tools: extend `tools.AnkiTool` with a `JsonObject` input schema, register in
  `AppGraph.ankiTools`. Names, argument names and output keys follow the add-on; arguments
  AnkiDroid cannot honour are left out of the schema and keys it cannot supply are omitted,
  never estimated (D57). Writes go through `AnkiDroidRepository.write`.
- No instrumented tests yet; keep logic in plain Kotlin and test it on the JVM.
