# Data safety form

Play Console > Policy > App content > Data safety. Answers derived from the code at version
0.1.0 (release build, `releaseRuntimeClasspath` checked on 2026-10-15). Where a claim rests on
the AnkiMCP server rather than on this app's code, it says so.

Play's definitions used below: **collected** = sent off the device by the app (to us or
anyone); **shared** = handed to a third party, except (a) service providers processing on our
behalf and (b) transfers the user starts and reasonably expects; **ephemeral** = held only in
memory for the request and not stored.

## What the app sends off the device (from the code)

| What | Where | Code |
|---|---|---|
| OAuth device-flow form: `client_id`, `scope`, device code | AnkiMCP `/auth/device`, `/auth/token` | `auth/DeviceFlowClient.kt` |
| Access token (identifies the AnkiMCP user), client type, app version header | AnkiMCP relay, WebSocket handshake | `tunnel/TunnelClient.kt` |
| MCP responses: deck ids, names, filtered flag, today's new/learn/review counts | AnkiMCP relay, then on to Claude | `tools/ListDecksTool.kt`, `docs/PROTOCOL.md` |
| FCM registration token, `clientType`, `platform`, `appVersion` | AnkiMCP `PUT/DELETE /devices/push` | `push/PushTokenRegistrar.kt` |
| Firebase Installation ID, FCM token request, Firebase user agent (SDK versions, OS version, device model) | Google (Firebase) | firebase-messaging 26.0.0 + firebase-installations 20.0.0 |

Not sent: the email (it arrives *from* the server in the token response, `auth/Credentials.kt`
`UserInfo`, is stored encrypted on the phone and shown on the Status screen), the log (stays
on the phone; Share log hands it to an app the user picks), crash reports (none), analytics
(none).

No analytics or crash SDK is linked. `releaseRuntimeClasspath` has firebase-messaging,
firebase-installations, firebase-datatransport and firebase-measurement-connector (an
interface only), but no firebase-analytics, play-services-measurement or crashlytics.
"Firebase Analytics disabled" is therefore stronger than needed: it is absent. FCM's optional
delivery-metrics export to BigQuery is off by default and the app never turns it on
(`delivery_metrics_exported_to_big_query_enabled` is not set).

## Overview questions

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes. `network_security_config.xml` forbids cleartext except to `10.0.2.2`/`localhost`/`127.0.0.1` (dev only); release builds use `wss://ankidroid.ankimcp.ai` and `https://` for auth and `/devices/push`; Firebase uses TLS. |
| Do you provide a way for users to request that their data is deleted? | Yes. See "Deletion" below. |
| Does your app allow users to create an account? | Answer per what the sign-in page offers. The app has no sign-up screen: sign-in opens the AnkiMCP verification page (the `verification_uri` the server returns) in
the browser (OAuth device flow). If that page lets a new user register, answer "Yes, users can create an account" and give the delete-account URL (see Deletion). |

## Data types

### Personal info > User IDs

| Field | Answer |
|---|---|
| Collected | Yes. The access token sent on every tunnel connection and `/devices/push` call identifies the AnkiMCP user. |
| Shared | No |
| Ephemeral | No (the account exists on the AnkiMCP server) |
| Required or optional | Required (the app does nothing without signing in) |
| Purposes | App functionality, Account management |

### Personal info > Email address

Strictly the app does not collect it: the email comes down from the server and is never sent
up. The account it belongs to was created on the web. Declaring it is still the safer answer:
the email is visible in the app, the privacy policy will mention it, and Play reviewers check
for consistency. Over-declaring has no penalty; under-declaring does.

| Field | Answer |
|---|---|
| Collected | Yes (conservative, see above) |
| Shared | No |
| Ephemeral | No |
| Required or optional | Required |
| Purposes | App functionality, Account management |

### Device or other IDs

The FCM registration token (and the Firebase Installation ID behind it). Matches D39.

| Field | Answer |
|---|---|
| Collected | Yes. Sent to AnkiMCP (`PUT /devices/push`) so it can send wake-ups; Firebase sends the FID to Google to issue the token. |
| Shared | No. Google is a service provider delivering our push messages. |
| Ephemeral | No. Stored by AnkiMCP until sign-out. |
| Required or optional | Required. There is no switch; the token is registered whenever the user is signed in (D34). Only created after sign-in (D39). |
| Purposes | App functionality |

Unverified: whether firebase-installations creates the FID at app start before sign-in
(Firebase initialises at process start; auto-init is off, so no FCM token is requested, but
the code does not prove no FID call happens). It does not change the answers above.

### App activity > Other user-generated content

Deck names (created by the user in AnkiDroid) and today's due counts, returned to Claude for
each `list_decks` call.

| Field | Answer |
|---|---|
| Collected | Yes. They leave the phone through the AnkiMCP relay. |
| Shared | No. The user connects Claude and asks for the data, so the transfer to Anthropic is user-initiated (Play's exemption). Still name Anthropic in the privacy policy and description (done in listing.md). |
| Ephemeral | Yes, as far as AnkiMCP goes. The app keeps nothing (`McpServer` is stateless). That the relay does not store request/response bodies is a server-side claim (README "What leaves your phone"); the app's code cannot prove it. Confirm with the relay code (`../anki-mcp-saas/apps/tunnel/`) and its logging before ticking this box. Anthropic stores conversations under its own terms; that is not our processing. |
| Required or optional | Required (it is the app's only function; each request starts with the user asking Claude) |
| Purposes | App functionality |

## Not declared, and why

| Type | Reason |
|---|---|
| App info and performance > Crash logs, Diagnostics | No crash or analytics SDK. The tunnel log stays on the phone; Share log goes only where the user sends it (a user-initiated share to a recipient they choose). |
| Device metadata in the Firebase user agent (OS version, model) | Firebase documents it as never linked to a user or device identifier and used for SDK adoption stats; Google's Firebase data-disclosure page lists it but does not map it to a Play category. Judgement call; if review asks, add App info and performance > Other app performance data, collected, not shared, App functionality. |
| Access and refresh tokens | Credentials, not a Play data type. Stored encrypted with a non-exportable Keystore key (`auth/CredentialStore.kt`), excluded from backup (`data_extraction_rules.xml`). |
| App version (`x-ankimcp-client-version`, `appVersion`) | Not a user data type. |
| Location, contacts, files, photos, financial, health, messages, audio, calendar, web history, installed apps | Not accessed. `<queries>` names only `com.ichi2.anki`. |

## Deletion

Fill in "Data deletion" with:

- Sign out (Status screen) deletes the push token from AnkiMCP (`DELETE /devices/push`),
  clears the stored credentials, and deletes the FCM token with Google (`deleteToken`). Offline
  sign-out finishes both deletes on the next app start or when a network is back (D35).
- Uninstalling the app removes everything stored on the phone (nothing is backed up).
- Account deletion: via the AnkiMCP dashboard at https://web.ankimcp.ai. **Not verified from
  this repo**: check that the dashboard really has a delete-account action and put its exact
  URL in the form ("Delete account URL" is required if Play counts the app as allowing account
  creation). If there is none, Play requires at least a web page explaining how to request
  deletion (for example an email address) before the app can go live.
