package ai.ankimcp.ankidroid.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Facts printed above a shared log. Deliberately nothing about the account (D48). */
data class LogReportHeader(
    val appVersion: String,
    /** e.g. "16 (API 36)". */
    val androidVersion: String,
    val device: String,
    val idleWindowMinutes: Int,
    val pushRegistered: Boolean,
)

/** Plain-text log for "Share log" and "Copy log". */
object LogReport {
    /**
     * The header, then [entries] oldest first, then [redact] over all of it. [secrets] are
     * removed verbatim (case-insensitive) before the patterns run, e.g. the signed-in email.
     */
    fun format(
        header: LogReportHeader,
        entries: List<LogEntry>,
        secrets: Collection<String> = emptyList(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }
        val text = buildString {
            appendLine("AnkiDroid MCP ${header.appVersion}")
            appendLine("Android ${header.androidVersion}, ${header.device}")
            appendLine("Idle window: ${header.idleWindowMinutes} min")
            appendLine("Push wake-ups registered: ${if (header.pushRegistered) "yes" else "no"}")
            val offset = utcOffset(timeZone, entries.lastOrNull()?.timestampMs ?: 0L)
            appendLine("Log: ${entries.size} entries, oldest first, times in UTC$offset")
            appendLine()
            entries.forEach { entry ->
                append(time.format(Date(entry.timestampMs)))
                append(' ')
                append(entry.level.name.first())
                append(' ')
                appendLine(entry.message)
            }
        }
        return redact(text, secrets)
    }

    /** Removes [secrets] verbatim, then anything shaped like a token or an email address. */
    fun redact(text: String, secrets: Collection<String> = emptyList()): String {
        var out = text
        secrets.filter { it.length >= MIN_SECRET_LENGTH }.forEach { out = out.replace(it, REDACTED, ignoreCase = true) }
        out = BEARER.replace(out) { "${it.groupValues[1]} $REDACTED" }
        out = KEY_VALUE.replace(out) { match ->
            val (key, separator, value) = match.destructured
            if (value.length >= 8 || value.any(Char::isDigit)) "$key$separator$REDACTED" else match.value
        }
        out = JWT.replace(out, REDACTED)
        out = FCM.replace(out, REDACTED)
        out = EMAIL.replace(out, REDACTED_EMAIL)
        out = LONG_BASE64.replace(out, REDACTED)
        out = LONG_TOKEN.replace(out, REDACTED)
        return out
    }

    /** "Google Pixel 8", without repeating the maker when the model already starts with it. */
    fun deviceName(manufacturer: String, model: String): String =
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()

    private fun utcOffset(zone: TimeZone, atMs: Long): String {
        val minutes = zone.getOffset(atMs) / 60_000
        val sign = if (minutes < 0) '-' else '+'
        return "%c%02d:%02d".format(Locale.US, sign, Math.abs(minutes) / 60, Math.abs(minutes) % 60)
    }

    const val REDACTED = "[redacted]"
    const val REDACTED_EMAIL = "[email]"
    private const val MIN_SECRET_LENGTH = 3

    private val BEARER = Regex("""(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]+""")
    // The value is redacted only if it has 8+ characters or a digit, so "Invalid token: expired" stays readable.
    private val KEY_VALUE = Regex(
        """(?i)\b(access_token|refresh_token|id_token|device_code|token|password|secret|api_key)(["']?\s*[:=]\s*["']?)([^\s"',;&]+)""",
    )

    // Three dot-separated base64url segments, as in a JWT ("eyJ...").
    private val JWT = Regex("""\b[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")
    // FCM registration token: "<instance id>:APA91..."; the id half alone is too short for LONG_TOKEN.
    private val FCM = Regex("""[A-Za-z0-9_-]{8,}:APA91[A-Za-z0-9_-]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    // 32+ standard base64 characters (with + and /) mixing upper case, lower case and digits.
    // The mix keeps lower-case URL paths such as "/api/v1/devices/push/..." readable.
    private val LONG_BASE64 = Regex(
        """(?<![A-Za-z0-9+/_-])(?=[A-Za-z0-9+/_-]*[0-9])(?=[A-Za-z0-9+/_-]*[a-z])(?=[A-Za-z0-9+/_-]*[A-Z])""" +
            """[A-Za-z0-9+/_-]{32,}(={1,2}|(?![A-Za-z0-9+/_-]))""",
    )

    // 32+ base64url characters with at least one digit: opaque tokens and FCM token halves, not words.
    private val LONG_TOKEN = Regex("""(?<![A-Za-z0-9_-])(?=[A-Za-z_-]*[0-9])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_-])""")
}
