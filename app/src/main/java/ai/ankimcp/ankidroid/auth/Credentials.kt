package ai.ankimcp.ankidroid.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** The non-standard `user` object the relay adds to token responses. */
@Serializable
data class UserInfo(
    val id: String = "",
    val email: String = "",
    val tier: String = "free",
)

/**
 * OAuth credentials, same JSON shape as the CLI and the desktop add-on:
 *
 * ```
 * {"access_token": "...", "refresh_token": "...", "expires_at": "2024-01-15T12:00:00Z",
 *  "user": {"id": "...", "email": "...", "tier": "free"}}
 * ```
 */
@Serializable
data class Credentials(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    /** ISO-8601 instant; computed client-side from `expires_in`. */
    @SerialName("expires_at") val expiresAt: String,
    val user: UserInfo,
) {
    /**
     * True if the access token expires within [bufferSeconds] or [expiresAt] cannot be
     * parsed (mirrors `CredentialsManager.is_token_expired` in the add-on).
     */
    fun isExpired(clock: Clock = Clock.systemUTC(), bufferSeconds: Long = EXPIRY_BUFFER_SECONDS): Boolean {
        val expiry = parseIso8601(expiresAt) ?: return true
        return !Instant.now(clock).plusSeconds(bufferSeconds).isBefore(expiry)
    }

    /** Redacted form so a stray log line never leaks tokens. */
    override fun toString(): String = "Credentials(user=$user, expiresAt=$expiresAt)"

    fun toJson(): String = CredentialsJson.encodeToString(serializer(), this)

    companion object {
        const val EXPIRY_BUFFER_SECONDS = 60L

        fun fromJson(raw: String): Credentials = CredentialsJson.decodeFromString(serializer(), raw)

        /**
         * Accepts `...Z`, `...+00:00` and offset-less timestamps (assumed UTC), which covers
         * what the TypeScript CLI and the Python add-on write.
         */
        fun parseIso8601(value: String): Instant? {
            val cleaned = value.trim()
            return try {
                OffsetDateTime.parse(cleaned).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(cleaned).toInstant(ZoneOffset.UTC)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }
}

internal val CredentialsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
