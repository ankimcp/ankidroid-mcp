# Releasing

Same model as the desktop add-on: the version lives in the repo, and a tag only says "release
what is already there".

## Version

`version.properties` at the repo root is the single source of truth:

```properties
versionName=0.1.0
```

`app/build.gradle.kts` reads it and derives `versionCode = major*10000 + minor*100 + patch`
(0.1.0 -> 100, 1.2.3 -> 10203). Minor and patch must stay in 0..99. The code never comes from
the CI run number, so rebuilding a tag gives the same `versionCode`, and every version bump
raises it, as Play requires.

Check what the repo is at (CI prints this on every run too):

```sh
.github/scripts/resolve_version.py                       # 0.1.0
.github/scripts/resolve_version.py --expect-tag v0.1.0   # fails unless the tag matches
```

## Cut a release

1. Bump `versionName` in `version.properties` in its own commit (nothing else changes in code).
2. Merge it to `main`.
3. Tag that commit and push the tag:

   ```sh
   git tag v0.2.0
   git push origin v0.2.0
   ```

The tag must be exactly `v<versionName>`. Anything else fails the first job and nothing is built.

## What the workflow does

`.github/workflows/release.yml`, on any pushed `v*` tag, one run per tag:

1. **version-gate**: runs `resolve_version.py --expect-tag <tag>`. Seconds, no build.
2. **release** (`contents: write`): re-checks the tag itself (so re-running this job alone is
   still gated), decodes the keystore into `$RUNNER_TEMP`, decodes `google-services.json` if
   that secret is set (otherwise the release ships without push wake-ups), runs the unit tests,
   builds `:app:bundleRelease` and `:app:assembleRelease` signed, verifies the signatures,
   renames the outputs to `ankidroid-mcp-<tag>.apk` / `.aab`, writes `SHA256SUMS.txt`, and
   creates the GitHub Release with auto-generated notes. Re-runs never overwrite files already
   attached to the release.
3. **play** (optional, `contents: read`): if `PLAY_SERVICE_ACCOUNT_JSON` is set, re-checks the
   tag and uploads the AAB plus the R8 mapping file to the Play **internal** track with status
   `completed` (testers get it immediately; internal releases are not reviewed). Release notes
   come from `distribution/whatsnew/whatsnew-<locale>` files if that directory exists.

## Secrets

Repository Settings > Secrets and variables > Actions:

| Secret | Required | What |
| --- | --- | --- |
| `ANKIMCP_KEYSTORE_BASE64` | yes | Upload/release keystore, base64 |
| `ANKIMCP_KEYSTORE_PASSWORD` | yes | Keystore password |
| `ANKIMCP_KEY_ALIAS` | yes | Key alias inside the keystore |
| `ANKIMCP_KEY_PASSWORD` | yes | Key password (same as the store password for PKCS12) |
| `GOOGLE_SERVICES_JSON_BASE64` | no | `app/google-services.json`, base64; without it no push |
| `PLAY_SERVICE_ACCOUNT_JSON` | no | Service account JSON key, plain text; without it no Play upload |

Locally, the same four `ANKIMCP_KEYSTORE_PATH` / `ANKIMCP_KEYSTORE_PASSWORD` /
`ANKIMCP_KEY_ALIAS` / `ANKIMCP_KEY_PASSWORD` environment variables sign `assembleRelease`;
without all four Gradle prints a note and the release build stays unsigned.

### Keystore

Create it once and keep it (and its passwords) in Bitwarden. Losing it means no more updates
for existing installs outside Play; with Play App Signing it is the *upload* key and can be
reset through Play support.

```sh
keytool -genkeypair -v \
  -keystore ankimcp-release.jks -storetype PKCS12 \
  -alias ankimcp -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=AnkiMCP, O=AnkiMCP"
```

PKCS12 uses one password for store and key, so `ANKIMCP_KEY_PASSWORD` equals
`ANKIMCP_KEYSTORE_PASSWORD`.

### Base64

```sh
base64 -i ankimcp-release.jks | tr -d '\n' | pbcopy            # macOS
base64 -w0 ankimcp-release.jks                                  # Linux
base64 -i app/google-services.json | tr -d '\n' | pbcopy       # GOOGLE_SERVICES_JSON_BASE64
```

Or set them straight from the shell with the GitHub CLI (nothing lands in shell history or the
clipboard):

```sh
base64 -i ankimcp-release.jks | tr -d '\n' | gh secret set ANKIMCP_KEYSTORE_BASE64
base64 -i app/google-services.json | tr -d '\n' | gh secret set GOOGLE_SERVICES_JSON_BASE64
gh secret set PLAY_SERVICE_ACCOUNT_JSON < play-service-account.json
```

### Play service account

Following Google's [Getting started](https://developers.google.com/android-publisher/getting_started)
guide. The old Play Console "API access" page (linking a Cloud project) is gone; the account is
now invited like a user:

1. In a Google Cloud project, enable the
   [Google Play Android Developer API](https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com).
2. Cloud Console > IAM & Admin > Service Accounts > Create service account (no Cloud roles
   needed), then Keys > Add key > JSON. That file is `PLAY_SERVICE_ACCOUNT_JSON`.
3. [Play Console](https://play.google.com/console) > Users and permissions > Invite new users:
   the service account's email, app permissions for AnkiDroid MCP only, with "Release apps to
   testing tracks" (plus the view permissions it implies).

### First upload is manual

The Play API cannot create an app or its first release. Create the app in Play Console with
package `ai.ankimcp.ankidroid`, enroll in Play App Signing, then upload the first AAB from the
GitHub Release by hand (Test and release > Testing > Internal testing > Create new release) and
roll it out. Until then the play job fails with "Package not found". While the app is still a
draft app (nothing rolled out on any track) the API also refuses `status: completed`; rolling
out that first manual release fixes it.

## Verify a release APK

```sh
"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --verbose --print-certs ankidroid-mcp-v0.2.0.apk
shasum -a 256 -c SHA256SUMS.txt
```

The certificate SHA-256 must match the release keystore's:

```sh
keytool -list -v -keystore ankimcp-release.jks -alias ankimcp | grep SHA256
```

APKs installed from Play are re-signed with the Play App Signing key, so their certificate
differs from the GitHub Release APK; the two cannot be installed over each other.
