# Tunnel wire protocol (as implemented by AnkiDroid MCP)

This is the protocol between the app and the AnkiMCP tunnel relay
(`anki-mcp-saas/apps/tunnel`). It is meant to be byte-compatible with the desktop
add-on (`anki-mcp-server-addon/anki_mcp_server/tunnel/`). Everything below matches the
add-on unless it is listed under [Deviations from the add-on](#deviations-from-the-add-on).
The push wake-up and device registration (an app-only addition, no tunnel frames involved) are
described in [Push wake-up and device registration](#push-wake-up-and-device-registration).

Code: `app/src/main/java/ai/ankimcp/ankidroid/tunnel/Protocol.kt`, `TunnelClient.kt`,
`TunnelConnection.kt`, `ReconnectPolicy.kt`, `auth/DeviceFlowClient.kt`, `mcp/McpServer.kt`,
`push/PushTokenRegistrar.kt`, `push/WakeHandler.kt`.

## Endpoints

| What | URL |
|---|---|
| WebSocket | `wss://ankidroid.ankimcp.ai` (root path) |
| Device code | `POST https://ankidroid.ankimcp.ai/auth/device` |
| Token / refresh | `POST https://ankidroid.ankimcp.ai/auth/token` |
| Push token | `PUT` / `DELETE https://ankidroid.ankimcp.ai/devices/push` |
| MCP URL for Claude | `https://ankidroid.ankimcp.ai/mcp` (sent by the relay in `tunnel_established`) |

The app has its own relay deployment: `wss://ankidroid.ankimcp.ai` in production,
`wss://ankidroid.ankimcp.uk` for development, with the same paths as the desktop add-on's
`wss://tunnel.ankimcp.ai`. Once that deployment ships, the shared `tunnel.ankimcp.*` hosts
refuse the phone with close code 4011 (see Close codes).

The HTTPS base is derived from the WebSocket URL by swapping the scheme only
(`wss://` -> `https://`, `ws://` -> `http://`), exactly like the add-on's `_ws_url_to_http`.

## Auth: OAuth 2.0 device flow (RFC 8628)

All requests are `application/x-www-form-urlencoded`; `client_id=ankimcp-cli`.

1. `POST /auth/device` with `client_id`, `scope=openid offline_access`.
   Response: `device_code`, `user_code`, `verification_uri`, optional
   `verification_uri_complete`, `expires_in`, optional `interval` (default 5).
2. The app shows `user_code` and opens `verification_uri_complete` (falls back to
   `verification_uri`) in a Custom Tab, only if it is an `https` URL.
3. Poll `POST /auth/token` with `grant_type=urn:ietf:params:oauth:grant-type:device_code`,
   `device_code`, `client_id`. Sleep `interval` seconds *before* each poll.
   - `400 {"error":"authorization_pending"}`: keep polling.
   - `400 {"error":"slow_down"}`: interval += 5 s, keep polling.
   - HTTP 429 (any body), or 5xx without an `error` field: interval += 5 s, and the next
     sleep is at least `Retry-After` (seconds or HTTP-date) if present; keep polling.
   - Transport errors, non-JSON bodies and `temporarily_unavailable`: keep polling.
   - Any other `error`: terminal.
   - Deadline `expires_in` (monotonic clock) reached, or the next sleep (interval or
     `Retry-After`) would end after it: terminal `expired_token`, raised right away instead of
     after the sleep.
4. Success body: `access_token`, `refresh_token`, `expires_in`, `token_type`, and the
   non-standard `user {id, email, tier}`. The app stores
   `{access_token, refresh_token, expires_at, user}` where
   `expires_at = now + expires_in` as an ISO-8601 instant (same JSON shape as the add-on and
   CLI).
5. Refresh: `POST /auth/token` with `grant_type=refresh_token`, `refresh_token`, `client_id`.
   Same response shape. A token is considered expired 60 s before `expires_at`.

Error parsing: the body is parsed as JSON first (non-JSON -> error with no code); any
non-2xx status raises with `error` (default `unknown_error`) and `error_description`
(default `HTTP <status>`). HTTP timeout 15 s.

## WebSocket connection

Upgrade request headers:

```
Authorization: Bearer <access_token>
x-ankimcp-client-type: ankidroid
x-ankimcp-client-version: <MAJOR.MINOR.PATCH extracted from versionName>   (omitted if none)
```

No WebSocket-level ping frames are sent (OkHttp `pingInterval = 0`); liveness is the
application-level ping/pong below.

### Handshake

Two timeouts, as in the add-on (`open_timeout` + the `tunnel_established` wait):

1. The socket must open (TCP + TLS + HTTP upgrade) within **10 s**.
2. The first text frame from the relay must be `tunnel_established` and must arrive within
   **10 s** of the socket opening.

Otherwise the attempt fails.

### Server -> client messages

All frames are JSON objects with a `type` discriminator. Unknown extra fields are ignored.

```json
{"type":"tunnel_established","url":"https://ankidroid.ankimcp.ai/mcp"}
{"type":"request","requestId":"r1","method":"POST","path":"/mcp","headers":{...},"body":"<JSON-RPC message as a string>"}
{"type":"ping","timestamp":1704063600000}
{"type":"error","code":"invalid_message","message":"...","details":...}
```

`body` and `details` are optional. Frames that fail to parse, or whose `type` is not one of
the four above, are logged and skipped after the handshake. `request.headers` is passed to the
MCP server, which reads `mcp-protocol-version` from it.

### Client -> server messages

```json
{"type":"pong","timestamp":1704063600000}
{"type":"response","requestId":"r1","statusCode":200,"headers":{"content-type":"application/json"},"body":"<JSON-RPC response as a string>"}
```

`body` is omitted (not `null`) when there is nothing to send.

### Request handling

Each `request` is handled concurrently; pings are answered inline.

| Case | statusCode | body |
|---|---|---|
| `body` absent or empty | 200 | omitted |
| JSON-RPC notification / client response | 200 | omitted |
| JSON-RPC request | 200 | JSON-RPC response |
| Handler exceeded **25 s** | 504 | `{"jsonrpc":"2.0","id":<inner id or null>,"error":{"code":-32004,"message":"Request timed out"}}` |
| Handler threw | 500 | `{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"Internal error: ..."}}` |

Headers are always `{"content-type":"application/json"}`. The relay itself answers GET/DELETE
with 405, so in practice only POSTs arrive.

### Liveness

The relay pings every 30 s. Every 30 s the client checks the time since the last ping; if it
exceeds **60 s** the socket is dropped and treated as close code 1001. The time is measured
with `SystemClock.elapsedRealtime()`, which keeps counting while the phone sleeps. Because the
30 s timer does not run while the CPU is suspended, an alarm also re-runs the check every 60 s
while connected (at most every ~15 min in deep Doze; see docs/DECISIONS.md D27/D29).

### Close codes

| Code | Name | Client action |
|---|---|---|
| 1000 | NORMAL | stop |
| 1001 | GOING_AWAY | reconnect with backoff |
| 4001 | AUTH_FAILED | refresh token, reconnect |
| 4002 | TOKEN_REVOKED | stop, sign-in required |
| 4003 | TUNNEL_AUTH_FAILED | refresh token, reconnect |
| 4004 | ACCOUNT_DELETED | stop, sign-in required |
| 4005 | SESSION_REPLACED | stop ("another device connected") |
| 4006 | URL_REGENERATED | reconnect with backoff |
| 4008 | SERVICE_UNAVAILABLE | reconnect with backoff |
| 4009 | SHUTDOWN | reconnect with backoff |
| 4010 | IDLE_TIMEOUT | stop |
| 4011 | CLIENT_KIND_MISMATCH | stop ("This relay does not accept the AnkiDroid app. Check the app is up to date.") |
| other | | reconnect with backoff |

Socket failures without a close frame are treated as 1001.

4011 is sent as a close frame right after a successful 101 upgrade, with the reason
`Client type not served by this host`, when the client connects to a host that does not serve
its client type (the phone on `tunnel.ankimcp.*`). It arrives before `tunnel_established`, so
the handshake close-code rule (Deviations 2) applies; the stop needs no new sign-in and clears
nothing.

### Reconnect

- `delay = min(1 s * 2^attempt + U(0, 0.3 * 1 s * 2^attempt), 30 s)`
- `attempt` counts consecutive failures; it is incremented *before* the delay is computed
  (so the first retry waits about 2 s, as in the add-on) and reset to 0 on every
  `tunnel_established`.
- Give up after 10 consecutive failures, as the add-on does; the next wake-up or Connect starts
  again.
- Before each attempt, refresh the access token if it expires within 60 s. A refresh that
  fails with `invalid_grant` stops the tunnel and requires sign-in; any other refresh failure
  is logged and the connection is attempted with the old token.
- Backoffs up to 5 s keep the CPU awake with a wake lock; longer ones are woken by an alarm,
  so in Doze they can take longer than computed (docs/DECISIONS.md D27/D29).

## Push wake-up and device registration

Not part of the tunnel protocol: the WebSocket frames above are unchanged. The phone does not
hold the socket between sessions; when Claude sends a request for an account whose device is
not connected, the server sends an FCM wake-up and the app connects. Design choices around it
are in docs/DECISIONS.md D31-D39.

### Device registration

Base URL: the HTTPS base derived from the tunnel WebSocket URL (same derivation as
`/auth/device`). Every call carries `Authorization: Bearer <access token>`; an expired access
token is refreshed first (shared `TokenRefresher`). JSON bodies, `Content-Type:
application/json`.

Register (or update) this device's FCM token:

```
PUT /devices/push
{"clientType":"ankidroid","fcmToken":"<FCM registration token>","platform":"android","appVersion":"<versionName>"}
-> 204
```

Remove it:

```
DELETE /devices/push
{"fcmToken":"<FCM registration token>"}
-> 204
```

| Response | Client action |
|---|---|
| 2xx (204 expected) | done |
| 401 | refresh the access token once and retry once; a second 401 gives up until the next trigger |
| 400, other 4xx | log (with the body's `message` or `error` text if JSON), no retry until the next trigger |
| 429, 5xx, network error | retry later with WorkManager (exponential backoff from 1 min, network required) |
| refresh fails with `invalid_grant` | nothing more; the user has to sign in |

When: after sign-in; in `FirebaseMessagingService.onNewToken`; on app start and in a daily
background check, if the last attempt failed, the token or app version changed since the last
successful registration, or that registration is 7 days old or more. The app has no FCM token
before the first sign-in (auto-init is off until then). Unregister: on logout, before the
credentials are cleared (they authenticate the DELETE).

### Wake message

High-priority FCM **data** message (no `notification` payload):

```json
{"type":"wake","reason":"mcp_request"}
```

- Tunnel looks connected: the socket is dropped and reconnected, unless it shows recent activity
  (Deviations 16). Tunnel running but between reconnect attempts: retry now.
- Otherwise: start the foreground service (its notification is shown if the user allowed
  notifications) and connect. The session then ends after the idle window (15 / 30 / 60 min)
  without a `request` frame, or when the screen turns off.
- With the screen off, the phone stays awake (a wake lock) from the wake
  until 60 s after its last response has been written to the socket, so OEM freezers cannot
  stop it mid-conversation; a request that arrives later than that may find the app frozen
  and go through a new wake-up (docs/DECISIONS.md D49).
- Signed out, or signing out: no connection, only a log line.
- The refresh token is dead (`invalid_grant` when refreshing before the connection) or access
  was revoked: no connection; a local notification "Sign in again to AnkiMCP" is posted.
- Other `type` values are ignored; `reason` is only logged.

## MCP over the tunnel

Stateless: each `request.body` is exactly one JSON-RPC 2.0 message; there is no
`mcp-session-id`, no SSE and no server-to-client messages. The protocol version of a request
is its `mcp-protocol-version` header, or `2025-03-26` if absent (MCP 2025-06-18 transports
spec).

| Method | Result |
|---|---|
| `initialize` | `protocolVersion` = the client's if it is one of `2024-11-05`, `2025-03-26`, `2025-06-18`, else `2025-06-18` (the latest supported); `capabilities: {tools: {}}`; `serverInfo: {name: "ankidroid-mcp", title, version}`; `instructions` |
| `ping` | `{}` |
| `tools/list` | `{tools: [{name, title, description, inputSchema, outputSchema?, annotations}]}`; `outputSchema` only for protocol >= `2025-06-18` |
| `tools/call` | `{content: [{type:"text",text}], structuredContent?, isError}`; `structuredContent` only for protocol >= `2025-06-18` |
| `resources/list`, `resources/templates/list`, `prompts/list` | empty lists |
| anything else | error -32601 |

Errors: invalid JSON -32700; JSON array (batch) -32600; missing/invalid `jsonrpc`/`id`
-32600; non-object `params` or unknown tool -32602. Tool failures (AnkiDroid missing,
permission missing, storage not configured, AnkiDroid not responding, timeouts, exceptions)
are `tools/call` results with `isError: true` and a human-readable text, never JSON-RPC errors.

### `list_decks`

Input: `{}`. Output (`structuredContent` on protocol >= 2025-06-18, always also serialised
into the text content), described by the tool's `outputSchema`:

```json
{
  "decks": [
    {"id": 1, "name": "Default", "is_filtered": false, "counts": {"new": 20, "learn": 3, "review": 41}}
  ],
  "total": 1
}
```

`counts` are AnkiDroid's due counts for today (`DECK_COUNTS` is `[learn, review, new]` on the
wire and is reordered here). `counts` is omitted for a deck if AnkiDroid returned no or
unparseable counts. Decks are sorted by name; the root node (id 0) is skipped. An empty
collection adds `"message": "No decks found in AnkiDroid"`.

## Deviations from the add-on

1. **Client type** is `ankidroid`. The relay accepts `x-ankimcp-client-type: ankidroid`
   (`ClientTypeSchema` in shared-types 0.18.1, on dev since 2026-10-15), keys connection slots
   by client type, and serves this client on the dedicated host (`ankidroid.ankimcp.*`, see
   Endpoints), not the add-on's `tunnel.ankimcp.*`.
2. **Close during handshake**: if the relay closes the socket with a code before
   `tunnel_established` (e.g. 4003 on a bad token, 4008), the add-on treats it as a generic
   connection failure. This app applies the close-code table instead, so 4003 at handshake
   triggers a token refresh and 4002/4004 stop.
3. **Auth-close loop guard**: after a 4001/4003 the add-on refreshes and reconnects
   immediately with `attempt = 0`, every time. This app reconnects immediately only once per
   successful session; a second auth close before `tunnel_established` goes through normal
   backoff, so a relay that keeps rejecting fresh tokens cannot cause a tight loop.
4. **Offline handling**: while Android reports no validated default network
   (`NET_CAPABILITY_VALIDATED`), the loop waits for one instead of consuming attempts. When a
   validated default network appears, any pending backoff is cut short; if the default network
   *changed* while connected, the socket is dropped (1001) and reconnected immediately. Network
   callbacks that arrive while connected and don't change the network are ignored, and a
   pending cut-short signal is cleared on `tunnel_established`, so the next disconnect still
   backs off normally.
5. **Device-flow polling** also retries transport errors, `temporarily_unavailable`, HTTP 429
   and 5xx without an `error` (the last two with `slow_down`-style backoff and `Retry-After`)
   until the deadline; the add-on aborts. Phones switch networks while the user is in the
   browser.
6. **Credential clearing**: `invalid_grant` on refresh, 4002 and 4004 clear the stored
   credentials and return the app to the sign-in screen. The add-on stops but keeps its file.
   For `invalid_grant` the clear is a compare-and-set: credentials saved by a sign-in while
   the failing refresh was in flight are kept.
7. **Malformed JSON-RPC**: the add-on's in-memory transport raises on invalid JSON and the
   tunnel answers 500/-32603. This app answers 200 with a proper JSON-RPC error (-32700 for
   invalid JSON, -32600 for batches/invalid requests).
8. **Unknown tool** returns JSON-RPC error -32602 (per MCP spec) rather than an `isError`
   result.
9. **`list_decks` shape** differs from the add-on (`deck_id`/`stats.new_count` there,
   `id`/`counts.new` here, no `include_stats` switch, no `total_in_deck`). See
   docs/DECISIONS.md.
10. **AnkiDroid provider timeout** is 20 s, inside the 25 s request budget, so a hung
    AnkiDroid produces a readable tool error instead of a 504.
11. (Removed: the "Always connected" mode and its retry-every-30-s loop were dropped, D50.
    The app now stops after 10 failures like the add-on. The number is kept so later
    references stay valid.)
12. **MCP protocol versions**: `2025-11-25` is not offered (the add-on's SDK lists it); a client
    asking for it gets `2025-06-18`. `structuredContent`/`outputSchema` follow the request's
    `mcp-protocol-version`.
13. **Client-side idle timeout**: with push wake-ups available, the app
    closes the socket with 1000 after 15 / 30 / 60 minutes without a `request` frame and does
    not reconnect until the next wake-up or Connect. The add-on has no such timer. The relay's
    own 4010 IDLE_TIMEOUT is unrelated and handled as in the table above.
14. **Screen-off and freeze close**: under the same condition the app also closes with 1000
    when the screen turns off or when it detects that the OS froze its process, and does not
    reconnect until the next wake-up or Connect (docs/DECISIONS.md D40, D41). Frames are
    unchanged.
15. **Ping watchdog after a freeze**: if the 60 s ping watchdog finds pings overdue on the first
    check that runs more than 40 s (one ping interval plus 10 s, on a clock that stops in CPU
    suspend) after the previous check, i.e. after the process was frozen, it waits one more
    ping interval (30 s) before dropping the socket, once per received ping (D43). The add-on
    has no such case.
16. **Push wake-up while connected**: a wake-up that finds the tunnel looking connected drops
    the socket locally (`ws.cancel()`, nothing is sent on the wire; 1001 is only the local close
    result) and reconnects at once, since the relay only sends one when it has no connection
    for the account (D40). A connection that connected or got a `request` frame in the last
    10 s, or has a request in flight, is kept: the wake-up crossed it.
