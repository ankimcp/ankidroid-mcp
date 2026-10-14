package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.auth.CredentialStore
import ai.ankimcp.ankidroid.auth.Credentials
import ai.ankimcp.ankidroid.auth.DeviceFlowClient
import ai.ankimcp.ankidroid.auth.RefreshOutcome
import ai.ankimcp.ankidroid.auth.TokenRefresher
import ai.ankimcp.ankidroid.auth.await
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.ProtocolConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Clock

/** Outcome of one `/devices/push` call. */
sealed interface PushResult {
    data object Ok : PushResult

    /** No credentials, or the refresh token is dead: nothing to do until the user signs in. */
    data object SignedOut : PushResult

    /** The server refused (400, a second 401, other 4xx). Not retried until the next trigger. */
    data class Rejected(val message: String) : PushResult

    /** Network error, 429 or 5xx: worth retrying later. */
    data class RetryLater(val message: String) : PushResult
}

/** Registers and removes this device's FCM token with the AnkiMCP server. */
interface PushRegistrar {
    suspend fun register(token: String): PushResult

    suspend fun unregister(token: String): PushResult
}

/**
 * `PUT` / `DELETE <https base>/devices/push` with `Authorization: Bearer <access token>`; the
 * base is derived from the tunnel WebSocket URL like `/auth/device`. An expired access token is
 * refreshed first; a 401 refreshes once and retries once. Never logs the FCM token.
 */
class PushTokenRegistrar(
    tunnelWsUrl: String,
    private val http: OkHttpClient,
    private val credentialStore: CredentialStore,
    private val refresher: TokenRefresher,
    private val appVersion: String,
    private val log: TunnelLog,
    private val clock: Clock = Clock.systemUTC(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PushRegistrar {
    private val endpoint = DeviceFlowClient.wsUrlToHttp(tunnelWsUrl).trimEnd('/') + "/devices/push"

    override suspend fun register(token: String): PushResult = send(
        method = "PUT",
        body = buildJsonObject {
            put("clientType", ProtocolConstants.CLIENT_TYPE)
            put("fcmToken", token)
            put("platform", "android")
            put("appVersion", appVersion)
        },
    )

    override suspend fun unregister(token: String): PushResult = send(
        method = "DELETE",
        body = buildJsonObject { put("fcmToken", token) },
    )

    private suspend fun send(method: String, body: JsonObject): PushResult {
        var credentials = withContext(ioDispatcher) { credentialStore.load() } ?: return PushResult.SignedOut
        if (credentials.isExpired(clock)) {
            credentials = when (val outcome = refresh(credentials)) {
                is Refreshed -> outcome.credentials
                is Done -> return outcome.result
            }
        }
        var refreshedAfter401 = false
        while (true) {
            val (code, errorText) = try {
                execute(method, body, credentials.accessToken)
            } catch (e: IOException) {
                val message = "Network error: ${e.message ?: e.javaClass.simpleName}"
                log.warn("Push $method failed: $message")
                return PushResult.RetryLater(message)
            }
            when {
                code in 200..299 -> return PushResult.Ok
                code == 401 && !refreshedAfter401 -> {
                    refreshedAfter401 = true
                    credentials = when (val outcome = refresh(credentials)) {
                        is Refreshed -> outcome.credentials
                        is Done -> return outcome.result
                    }
                }
                code == 401 -> {
                    log.warn("Push $method rejected: HTTP 401 after a token refresh")
                    return PushResult.Rejected("Unauthorized (HTTP 401)")
                }
                code == 429 || code in 500..599 -> {
                    log.warn("Push $method failed: HTTP $code")
                    return PushResult.RetryLater("Server error (HTTP $code)")
                }
                else -> {
                    val detail = errorText?.let { ": $it" } ?: ""
                    log.warn("Push $method rejected: HTTP $code$detail")
                    return PushResult.Rejected("HTTP $code$detail")
                }
            }
        }
    }

    private sealed interface RefreshStep

    private class Refreshed(val credentials: Credentials) : RefreshStep

    private class Done(val result: PushResult) : RefreshStep

    private suspend fun refresh(credentials: Credentials): RefreshStep = when (val outcome = refresher.refresh(credentials)) {
        is RefreshOutcome.Refreshed -> Refreshed(outcome.credentials)
        RefreshOutcome.ReloginRequired, RefreshOutcome.SignedOut -> Done(PushResult.SignedOut)
        is RefreshOutcome.Failed -> {
            log.warn("Push registration: token refresh failed: ${outcome.message}")
            Done(PushResult.RetryLater("Token refresh failed: ${outcome.message}"))
        }
    }

    /** Returns the status code and, for errors, the server's `message`/`error` text if it sent JSON. */
    private suspend fun execute(method: String, body: JsonObject, accessToken: String): Pair<Int, String?> {
        val request = Request.Builder()
            .url(endpoint)
            .method(method, body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()
        return withContext(ioDispatcher) {
            http.newCall(request).await().use { response ->
                val text = if (response.isSuccessful) null else response.body?.string()
                response.code to text?.let(::errorText)
            }
        }
    }

    private fun errorText(raw: String): String? = try {
        val json = Json.parseToJsonElement(raw) as? JsonObject
        val field = (json?.get("message") ?: json?.get("error")) as? JsonPrimitive
        field?.takeIf { it.isString }?.content?.take(MAX_ERROR_TEXT)
    } catch (_: Exception) {
        null
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val MAX_ERROR_TEXT = 200
    }
}
