package ai.ankimcp.ankidroid.billing

import ai.ankimcp.ankidroid.auth.CredentialStore
import ai.ankimcp.ankidroid.auth.Credentials
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.auth.RefreshOutcome
import ai.ankimcp.ankidroid.auth.TokenRefresher
import ai.ankimcp.ankidroid.auth.await
import ai.ankimcp.ankidroid.log.TunnelLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Clock
import java.time.Instant

/** Outcome of one billing HTTP call; the coordinator decides what each means for the user. */
sealed interface BillingApiResult<out T> {
    data class Ok<T>(val value: T) : BillingApiResult<T>

    /** No credentials, or the refresh token is dead. */
    data object SignedOut : BillingApiResult<Nothing>

    /** 403: the purchase's obfuscated account id is not this account's (`PURCHASE_NOT_OWNED`). */
    data object NotOwned : BillingApiResult<Nothing>

    /** 409: the account already has a subscription (`ALREADY_SUBSCRIBED`). */
    data object AlreadySubscribed : BillingApiResult<Nothing>

    /** 503 from a google-play call: Play billing is switched off on this environment. */
    data object BillingDisabled : BillingApiResult<Nothing>

    /** 400, a second 401, other 4xx, or a 200 the app cannot parse. Not retried automatically. */
    data class Rejected(val message: String) : BillingApiResult<Nothing>

    /** Network error, 429 or 5xx: worth retrying. */
    data class RetryLater(val message: String) : BillingApiResult<Nothing>
}

enum class PrepareMode { New, Replace, Blocked }

data class PrepareResponse(
    /** 64 hex characters; passed to `setObfuscatedAccountId`. */
    val obfuscatedAccountId: String,
    val mode: PrepareMode,
    /** The live Play subscription's product when [mode] is [PrepareMode.Replace]. */
    val currentProductId: String?,
)

enum class PlanStatusKind { Free, Active, Grace, Canceled, Unknown }

enum class PlanProvider { None, GooglePlay, Polar, Paddle, Admin, Unknown }

/** `GET /billing/status` (docs/PROTOCOL.md). */
data class PlanStatus(
    val tier: String,
    val status: PlanStatusKind,
    val provider: PlanProvider,
    /** Null when the server sent null or a value that does not parse. */
    val currentPeriodEndsAt: Instant?,
    val cancelAtPeriodEnd: Boolean,
    /** Set only for google_play with billing enabled on that environment. */
    val productId: String?,
)

/** The two google-play calls the purchase flow needs. */
interface BillingBackend {
    suspend fun prepare(): BillingApiResult<PrepareResponse>

    /** Returns the account's tier after the server verified and acknowledged the purchase. */
    suspend fun registerPurchase(purchaseToken: String): BillingApiResult<String>
}

/** Where the Plan card's state comes from; [BillingApi] in the app, a fake in tests. */
interface BillingStatusSource {
    suspend fun status(): BillingApiResult<PlanStatus>
}

/**
 * `POST /billing/google-play/prepare`, `POST /billing/google-play/purchase` and
 * `GET /billing/status` on the HTTPS base derived from the tunnel URL, with the device-flow
 * bearer token: refreshed first when expired, and once after a 401 (like `/devices/push`).
 * Never logs the access or purchase token.
 */
class BillingApi(
    tunnelWsUrl: String,
    private val http: OkHttpClient,
    private val credentialStore: CredentialStore,
    private val refresher: TokenRefresher,
    private val log: TunnelLog,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BillingBackend, BillingStatusSource {
    private val base = DeviceFlowClient.wsUrlToHttp(tunnelWsUrl).trimEnd('/')

    override suspend fun prepare(): BillingApiResult<PrepareResponse> =
        call("prepare", "$base/billing/google-play/prepare", EMPTY_BODY, playCall = true) { parsePrepare(it) }

    override suspend fun registerPurchase(purchaseToken: String): BillingApiResult<String> {
        val body = buildJsonObject { put("purchaseToken", purchaseToken) }.toString().toRequestBody(JSON_MEDIA_TYPE)
        return call("purchase", "$base/billing/google-play/purchase", body, playCall = true) { json ->
            json.string("tier")?.takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun status(): BillingApiResult<PlanStatus> =
        call("status", "$base/billing/status", body = null, playCall = false) { parseStatus(it) }

    private suspend fun <T> call(
        name: String,
        url: String,
        body: RequestBody?,
        playCall: Boolean,
        parse: (JsonObject) -> T?,
    ): BillingApiResult<T> {
        var credentials = withContext(ioDispatcher) { credentialStore.load() } ?: return BillingApiResult.SignedOut
        if (credentials.isExpired(clock)) {
            credentials = when (val step = refresh(name, credentials)) {
                is Refreshed -> step.credentials
                is Stop -> return step.result
            }
        }
        var refreshedAfter401 = false
        while (true) {
            val (code, text) = try {
                execute(url, body, credentials.accessToken)
            } catch (e: IOException) {
                val message = "Network error: ${e.message ?: e.javaClass.simpleName}"
                log.warn("Billing $name failed: $message")
                return BillingApiResult.RetryLater(message)
            }
            when {
                code in 200..299 -> {
                    val parsed = text?.let(::parseObject)?.let(parse)
                    if (parsed == null) {
                        log.warn("Billing $name: unexpected response (HTTP $code)")
                        return BillingApiResult.Rejected("Unexpected response from the server")
                    }
                    return BillingApiResult.Ok(parsed)
                }
                code == 401 && !refreshedAfter401 -> {
                    refreshedAfter401 = true
                    credentials = when (val step = refresh(name, credentials)) {
                        is Refreshed -> step.credentials
                        is Stop -> return step.result
                    }
                }
                code == 401 -> {
                    log.warn("Billing $name rejected: HTTP 401 after a token refresh")
                    return BillingApiResult.Rejected("Unauthorized (HTTP 401)")
                }
                code == 403 && playCall -> {
                    log.warn("Billing $name rejected: purchase belongs to another account (HTTP 403)")
                    return BillingApiResult.NotOwned
                }
                code == 409 && playCall -> {
                    log.warn("Billing $name rejected: already subscribed (HTTP 409)")
                    return BillingApiResult.AlreadySubscribed
                }
                code == 503 && playCall -> {
                    log.warn("Billing $name: Google Play billing is disabled on the server (HTTP 503)")
                    return BillingApiResult.BillingDisabled
                }
                code == 429 || code in 500..599 -> {
                    log.warn("Billing $name failed: HTTP $code")
                    return BillingApiResult.RetryLater("Server error (HTTP $code)")
                }
                else -> {
                    val detail = text?.let(::errorText)?.let { ": $it" } ?: ""
                    log.warn("Billing $name rejected: HTTP $code$detail")
                    return BillingApiResult.Rejected("HTTP $code$detail")
                }
            }
        }
    }

    private sealed interface RefreshStep

    private class Refreshed(val credentials: Credentials) : RefreshStep

    private class Stop(val result: BillingApiResult<Nothing>) : RefreshStep

    private suspend fun refresh(name: String, credentials: Credentials): RefreshStep =
        when (val outcome = refresher.refresh(credentials)) {
            is RefreshOutcome.Refreshed -> Refreshed(outcome.credentials)
            RefreshOutcome.ReloginRequired, RefreshOutcome.SignedOut -> Stop(BillingApiResult.SignedOut)
            is RefreshOutcome.Failed -> {
                log.warn("Billing $name: token refresh failed: ${outcome.message}")
                Stop(BillingApiResult.RetryLater("Token refresh failed: ${outcome.message}"))
            }
        }

    /** Status code and body text. */
    private suspend fun execute(url: String, body: RequestBody?, accessToken: String): Pair<Int, String?> {
        val request = Request.Builder()
            .url(url)
            .apply { if (body == null) get() else post(body) }
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        return withContext(ioDispatcher) {
            http.newCall(request).await().use { response -> response.code to response.body?.string() }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
        private const val MAX_ERROR_TEXT = 200
        private val HEX_64 = Regex("[0-9a-fA-F]{64}")

        internal fun parsePrepare(json: JsonObject): PrepareResponse? {
            val id = json.string("obfuscatedAccountId")?.takeIf { HEX_64.matches(it) } ?: return null
            val mode = when (json.string("mode")) {
                "new" -> PrepareMode.New
                "replace" -> PrepareMode.Replace
                "blocked" -> PrepareMode.Blocked
                else -> return null
            }
            return PrepareResponse(id, mode, json.string("currentProductId")?.takeIf { it.isNotEmpty() })
        }

        /** Unknown enum values parse as `Unknown` instead of failing the whole card. */
        internal fun parseStatus(json: JsonObject): PlanStatus? {
            val tier = json.string("tier")?.takeIf { it.isNotEmpty() } ?: return null
            return PlanStatus(
                tier = tier,
                status = when (json.string("status")) {
                    "free" -> PlanStatusKind.Free
                    "active" -> PlanStatusKind.Active
                    "grace" -> PlanStatusKind.Grace
                    "canceled" -> PlanStatusKind.Canceled
                    else -> PlanStatusKind.Unknown
                },
                provider = when (json.string("provider")) {
                    "none" -> PlanProvider.None
                    "google_play" -> PlanProvider.GooglePlay
                    "polar" -> PlanProvider.Polar
                    "paddle" -> PlanProvider.Paddle
                    "admin" -> PlanProvider.Admin
                    else -> PlanProvider.Unknown
                },
                currentPeriodEndsAt = json.string("currentPeriodEndsAt")?.let(Credentials::parseIso8601),
                cancelAtPeriodEnd = (json["cancelAtPeriodEnd"] as? JsonPrimitive)?.booleanOrNull ?: false,
                productId = json.string("productId")?.takeIf { it.isNotEmpty() },
            )
        }

        private fun parseObject(raw: String): JsonObject? = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        }

        private fun errorText(raw: String): String? {
            val json = parseObject(raw) ?: return null
            return (json.string("message") ?: json.string("error"))?.take(MAX_ERROR_TEXT)
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString && it !is JsonNull }?.content
    }
}
