package ai.ankimcp.ankidroid.auth

import ai.ankimcp.ankidroid.config.AppConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Raised when an auth call fails. [errorCode] is the OAuth `error` value from the server
 * (`invalid_grant`, `expired_token`, ...) or null for transport-level failures.
 * [transient] marks server overload (HTTP 429, or 5xx without an OAuth `error`), which should
 * be retried after a backoff of at least [retryAfterSeconds] when the server sent `Retry-After`.
 */
class AuthException(
    message: String,
    val errorCode: String? = null,
    cause: Throwable? = null,
    val transient: Boolean = false,
    val retryAfterSeconds: Long? = null,
) : Exception(message, cause)

/** Response from `POST /auth/device`. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

/**
 * OAuth 2.0 Device Authorization Grant client against the relay's `/auth/device` and `/auth/token`,
 * a port of `tunnel/auth.py` from the desktop add-on.
 */
class DeviceFlowClient(
    tunnelWsUrl: String,
    private val http: OkHttpClient,
    private val clientId: String = AppConfig.OAUTH_CLIENT_ID,
    private val clock: Clock = Clock.systemUTC(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val httpBase = wsUrlToHttp(tunnelWsUrl)
    private val deviceEndpoint = "$httpBase/auth/device"
    private val tokenEndpoint = "$httpBase/auth/token"

    suspend fun requestDeviceCode(): DeviceCode {
        val data = post(
            deviceEndpoint,
            mapOf(
                "client_id" to clientId,
                // Must match the add-on and CLI verbatim: offline_access yields a
                // long-lived offline refresh token.
                "scope" to "openid offline_access",
            ),
        )
        return try {
            DeviceCode(
                deviceCode = data.requireString("device_code"),
                userCode = data.requireString("user_code"),
                verificationUri = data.requireString("verification_uri"),
                verificationUriComplete = data.optString("verification_uri_complete"),
                expiresInSeconds = data.requireLong("expires_in"),
                intervalSeconds = data.optLong("interval") ?: DEFAULT_INTERVAL_SECONDS,
            )
        } catch (e: IllegalArgumentException) {
            throw AuthException("Malformed device code response: ${e.message}", "invalid_response", e)
        }
    }

    /**
     * Polls until the user approves, the code expires, or a terminal error occurs.
     *
     * Sleeps before each poll (RFC 8628). `authorization_pending` keeps polling,
     * `slow_down` adds 5 s to the interval. HTTP 429 and 5xx without an OAuth error are treated
     * like `slow_down`, and the next poll also waits at least `Retry-After`. Unlike the add-on,
     * transport failures and `temporarily_unavailable` are retried until the deadline, because
     * phones change networks while the user is in the browser.
     *
     * No sleep runs past the device-code deadline: if the next wait (interval or `Retry-After`)
     * would end after it, this fails with `expired_token` right away instead of sleeping first.
     */
    suspend fun pollForToken(deviceCode: String, intervalSeconds: Long, expiresInSeconds: Long): Credentials {
        var interval = intervalSeconds
        var retryAfter = 0L
        val deadline = timeSource.markNow() + expiresInSeconds.seconds
        while (true) {
            val wait = maxOf(interval, retryAfter).seconds
            val remaining = -deadline.elapsedNow()
            if (!remaining.isPositive() || wait > remaining) break
            sleep(wait)
            retryAfter = 0L
            val data = try {
                post(
                    tokenEndpoint,
                    mapOf(
                        "grant_type" to DEVICE_CODE_GRANT,
                        "device_code" to deviceCode,
                        "client_id" to clientId,
                    ),
                )
            } catch (e: AuthException) {
                when {
                    e.transient -> {
                        interval += SLOW_DOWN_INCREMENT_SECONDS
                        retryAfter = e.retryAfterSeconds ?: 0L
                        continue
                    }
                    e.errorCode == "authorization_pending" -> continue
                    e.errorCode == "slow_down" -> {
                        interval += SLOW_DOWN_INCREMENT_SECONDS
                        continue
                    }
                    e.errorCode == null || e.errorCode == "temporarily_unavailable" -> continue
                    else -> throw e
                }
            }
            return buildCredentials(data)
        }
        throw AuthException("Device code expired before authorization. Please restart login.", "expired_token")
    }

    suspend fun refresh(refreshToken: String): Credentials {
        val data = post(
            tokenEndpoint,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            ),
        )
        return buildCredentials(data)
    }

    private fun buildCredentials(data: JsonObject): Credentials = try {
        val expiresIn = data.requireLong("expires_in")
        val user = data["user"] as? JsonObject ?: throw IllegalArgumentException("missing 'user'")
        Credentials(
            accessToken = data.requireString("access_token"),
            refreshToken = data.requireString("refresh_token"),
            expiresAt = Instant.now(clock).plusSeconds(expiresIn).toString(),
            user = CredentialsJson.decodeFromJsonElement(UserInfo.serializer(), user),
        )
    } catch (e: IllegalArgumentException) {
        throw AuthException("Malformed token response: ${e.message}", "invalid_response", e)
    }

    private suspend fun post(url: String, form: Map<String, String>): JsonObject {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        val (code, retryAfterHeader, text) = try {
            withContext(ioDispatcher) {
                http.newCall(request).await().use { Triple(it.code, it.header("Retry-After"), it.body?.string() ?: "") }
            }
        } catch (e: IOException) {
            throw AuthException("HTTP request failed: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
        val data = try {
            CredentialsJson.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val serverError = data?.optString("error")
        val transient = code == 429 || (code in 500..599 && serverError == null)
        val retryAfter = if (transient) parseRetryAfter(retryAfterHeader, Instant.now(clock)) else null

        if (data == null) {
            throw AuthException(
                "Non-JSON response (HTTP $code): ${text.take(200)}",
                transient = transient,
                retryAfterSeconds = retryAfter,
            )
        }
        if (code !in 200..299) {
            val errorCode = serverError ?: "unknown_error"
            val description = data.optString("error_description") ?: "HTTP $code"
            throw AuthException(description, errorCode, transient = transient, retryAfterSeconds = retryAfter)
        }
        return data
    }

    companion object {
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val SLOW_DOWN_INCREMENT_SECONDS = 5L

        /** `wss://host/path` -> `https://host/path`, `ws://` -> `http://`, same as the add-on. */
        fun wsUrlToHttp(wsUrl: String): String = when {
            wsUrl.startsWith("wss://", ignoreCase = true) -> "https://" + wsUrl.substring(6)
            wsUrl.startsWith("ws://", ignoreCase = true) -> "http://" + wsUrl.substring(5)
            else -> throw IllegalArgumentException("Expected ws:// or wss:// URL, got '$wsUrl'")
        }

        /** `Retry-After` as delta-seconds or an HTTP-date (RFC 9110), or null if absent/invalid. */
        internal fun parseRetryAfter(value: String?, now: Instant): Long? {
            val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            raw.toLongOrNull()?.let { return it.coerceAtLeast(0) }
            return try {
                val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                java.time.Duration.between(now, at).seconds.coerceAtLeast(0)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

private fun JsonObject.optString(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.requireString(key: String): String =
    optString(key) ?: throw IllegalArgumentException("missing '$key'")

private fun JsonObject.optLong(key: String): Long? {
    val primitive = this[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.intOrNull?.toLong() ?: primitive.contentOrNull?.toDoubleOrNull()?.toLong()
}

private fun JsonObject.requireLong(key: String): Long =
    optLong(key) ?: throw IllegalArgumentException("missing or non-numeric '$key'")

/** Suspends on an OkHttp call; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
}
