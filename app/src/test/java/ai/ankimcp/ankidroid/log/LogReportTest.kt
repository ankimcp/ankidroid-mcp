package ai.ankimcp.ankidroid.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LogReportTest {
    private val header = LogReportHeader(
        appVersion = "0.1.0",
        androidVersion = "16 (API 36)",
        device = "Google Pixel 8",
        idleWindowMinutes = 30,
        pushRegistered = true,
    )
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-15 10:00:00 UTC
    private val t0 = 1_792_058_400_000L

    @Test
    fun `formats header and entries oldest first`() {
        val entries = listOf(
            LogEntry(t0, LogEntry.Level.INFO, "Tunnel established"),
            LogEntry(t0 + 61_000, LogEntry.Level.WARN, "Reconnecting in 2.0s (attempt 1/5)"),
            LogEntry(t0 + 62_000, LogEntry.Level.ERROR, "Tunnel connection failed: timeout"),
        )

        val text = LogReport.format(header, entries, timeZone = utc)

        assertEquals(
            """
            AnkiMCP 0.1.0
            Android 16 (API 36), Google Pixel 8
            Idle window: 30 min
            Push wake-ups registered: yes
            Log: 3 entries, oldest first, times in UTC+00:00

            2026-10-15 10:00:00 I Tunnel established
            2026-10-15 10:01:01 W Reconnecting in 2.0s (attempt 1/5)
            2026-10-15 10:01:02 E Tunnel connection failed: timeout

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `push not registered prints no and times use the given zone`() {
        val text = LogReport.format(
            header.copy(pushRegistered = false),
            listOf(LogEntry(t0, LogEntry.Level.DEBUG, "x")),
            timeZone = TimeZone.getTimeZone("Asia/Kolkata"),
        )

        assertTrue(text.contains("Push wake-ups registered: no\n"))
        assertTrue(text.contains("times in UTC+05:30"))
        assertTrue(text.contains("2026-10-15 15:30:00 D x"))
    }

    @Test
    fun `empty log still has the header`() {
        val text = LogReport.format(header, emptyList(), timeZone = utc)

        assertTrue(text.startsWith("AnkiMCP 0.1.0\n"))
        assertTrue(text.contains("Log: 0 entries"))
    }

    @Test
    fun `signed-in email is removed even in odd case, and other emails too`() {
        val text = LogReport.format(
            header,
            listOf(
                LogEntry(t0, LogEntry.Level.INFO, "Signed in as Me@Example.com"),
                LogEntry(t0, LogEntry.Level.WARN, "Relay error 4001: user other.person+x@mail.example.org unknown"),
            ),
            secrets = listOf("me@example.com"),
            timeZone = utc,
        )

        assertFalse(text.contains("example.com", ignoreCase = true))
        assertFalse(text.contains("other.person"))
        assertTrue(text.contains("Signed in as [redacted]"))
        assertTrue(text.contains("user [email] unknown"))
    }

    @Test
    fun `redacts JWTs`() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVlLWhlcmU"

        assertEquals("closed with [redacted] attached", LogReport.redact("closed with $jwt attached"))
    }

    @Test
    fun `redacts bearer headers and key-value tokens`() {
        assertEquals("Authorization: Bearer [redacted]", LogReport.redact("Authorization: Bearer abc.def-123"))
        assertEquals("refresh_token=[redacted]&x=1", LogReport.redact("refresh_token=r0tat3d&x=1"))
        assertEquals("{\"access_token\": \"[redacted]\"}", LogReport.redact("{\"access_token\": \"abc123\"}"))
        assertEquals("token: [redacted]", LogReport.redact("token: s3krit"))
        assertEquals("password=[redacted]", LogReport.redact("password=longenough"))
    }

    @Test
    fun `redacts other credential headers to the end of the line`() {
        assertEquals("Authorization: [redacted]", LogReport.redact("Authorization: Basic dXNlcjpwYXNz"))
        assertEquals("Proxy-Authorization: [redacted]", LogReport.redact("Proxy-Authorization: Digest x=1"))
        assertEquals("Cookie: [redacted]\nnext", LogReport.redact("Cookie: a=1; b=2\nnext"))
        assertEquals("x-api-key=[redacted]", LogReport.redact("x-api-key=abc"))
        assertEquals("Authorization: Bearer [redacted] sent", LogReport.redact("Authorization: Bearer t0k sent"))
    }

    @Test
    fun `redacts JSON-quoted credential headers`() {
        assertEquals("""{"Authorization":[redacted]""", LogReport.redact("""{"Authorization":"Basic dXNlcjpwYXNz"}"""))
        assertEquals("""headers {'cookie': [redacted]""", LogReport.redact("""headers {'cookie': 'sid=abc; x=1'}"""))
        assertEquals(
            """{"Authorization":"Bearer [redacted]"}""",
            LogReport.redact("""{"Authorization":"Bearer abc.def-123"}"""),
        )
    }

    @Test
    fun `redacts a 43-character base64url token without a digit`() {
        val token = "aBcDeFgHiJkLmNoPqRsTuVwXyZ_aBcDeFgHiJkLmNoP"
        assertEquals(43, token.length)

        assertEquals("token [redacted] end", LogReport.redact("token $token end"))
        assertEquals("[redacted]", LogReport.redact("qwertyQWERTYasdfghASDFGHzxcvbnZXCVBNpoiuyt"))
    }

    @Test
    fun `long CamelCase class names stay readable`() {
        val line = "Failed: ForegroundServiceStartNotAllowedExceptionWrapperForTests"

        assertEquals(line, LogReport.redact(line))
    }

    @Test
    fun `short key-value words without digits stay readable`() {
        assertEquals("Invalid token: expired", LogReport.redact("Invalid token: expired"))
        assertEquals("token=missing", LogReport.redact("token=missing"))
    }

    @Test
    fun `redacts standard base64 with plus, slash and padding`() {
        assertEquals(
            "key [redacted] end",
            LogReport.redact("key q1Zx+9/AbCdEfGhIjKlMnOpQrStUvWxYz0123+/== end"),
        )
    }

    @Test
    fun `lower-case URL paths with slashes stay readable`() {
        val line = "Push POST /api/v1/devices/push/registrations/current failed: HTTP 503"

        assertEquals(line, LogReport.redact(line))
    }

    @Test
    fun `redacts FCM tokens including the short instance id half`() {
        val fcm = "cR5dX1yZT2u-3vQ:APA91bH8xk2Lq9Zs0Vd7nW4mE6pR1tY3uI5oA8sD2fG4hJ6kL0zX"

        assertEquals("registered [redacted] ok", LogReport.redact("registered $fcm ok"))
    }

    @Test
    fun `redacts long opaque strings with digits`() {
        assertEquals("id [redacted] end", LogReport.redact("id Zx9_kq2LmN4pR7sT1vW3yA5bC8dE0fGh end"))
    }

    @Test
    fun `leaves ordinary log lines alone`() {
        val lines = listOf(
            "Tunnel established: https://tunnel.ankimcp.ai/mcp",
            "Push token removed from the server",
            "Access token expired, refreshing",
            "Token refresh failed (will retry): HTTP 503",
            "POST /mcp -> 200 (42 ms)",
            "Could not start the tunnel service: ForegroundServiceStartNotAllowedException",
            "Idle timeout: no requests for 15 min, disconnecting",
        )

        lines.forEach { assertEquals(it, LogReport.redact(it)) }
    }

    @Test
    fun `short secrets are not used for verbatim removal`() {
        assertEquals("a b", LogReport.redact("a b", secrets = listOf("", "a")))
    }

    @Test
    fun `device name does not repeat the maker`() {
        assertEquals("Google Pixel 8", LogReport.deviceName("Google", "Pixel 8"))
        assertEquals("Google Pixel 8", LogReport.deviceName("Google", "Google Pixel 8"))
        assertEquals("realme RMX3700", LogReport.deviceName("realme", "RMX3700"))
        assertEquals("samsung SM-S911B", LogReport.deviceName("samsung", "SM-S911B"))
    }
}
