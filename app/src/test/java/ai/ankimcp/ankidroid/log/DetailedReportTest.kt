package ai.ankimcp.ankidroid.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

class DetailedReportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-15 10:00:00 UTC
    private val t0 = 1_792_058_400_000L

    private val info = DetailedReportInfo(
        appVersion = "0.3.0",
        build = "300 release",
        androidVersion = "15 (API 35)",
        device = "realme RMX3741",
        romBuild = "RMX3741_15.0.0.800(EX01)",
        oemSkin = "ColorOs (Realme, Oppo and OnePlus)",
        ankiDroidVersion = "2.24.0 (22400300)",
        idleWindowMinutes = 30,
        pushRegistered = true,
        localServerEnabled = true,
        localServerPort = 8765,
        localTokenRequired = false,
        detailedLoggingUntil = t0 + 3_600_000,
        batteryUnrestricted = false,
        notificationsGranted = true,
        notificationsShown = false,
        generatedAtMs = t0,
    )

    @Test
    fun `header, then the file log, then logcat`() {
        val text = DetailedReport.format(
            info,
            fileLog = "2026-10-15 09:00:00.000+0000 I first\n2026-10-15 09:30:00.000+0000 W second\n",
            logcat = "10-15 10:00:00.000 I/AnkiMcpTunnel( 123): hello",
            timeZone = utc,
        )

        assertEquals(
            """
            AnkiMCP detailed log
            Generated: 2026-10-15 10:00:00 +0000

            App: AnkiMCP 0.3.0 (build 300 release)
            Android: 15 (API 35)
            Device: realme RMX3741
            ROM build: RMX3741_15.0.0.800(EX01)
            Background freezer skin: ColorOs (Realme, Oppo and OnePlus)
            AnkiDroid: 2.24.0 (22400300)

            Idle window: 30 min
            Push wake-ups registered: yes
            Local server: on, port 8765
            Local server access token required: no
            Detailed logging: on until 2026-10-15 11:00:00 +0000
            Battery optimisation off for AnkiMCP: no
            Notification permission: granted
            All notifications shown: no

            == App log (oldest first) ==
            2026-10-15 09:00:00.000+0000 I first
            2026-10-15 09:30:00.000+0000 W second

            == This app's system log (logcat) ==
            (only this app's own lines; the phone's system messages are not available to apps)
            (at most the newest 2000 lines)
            10-15 10:00:00.000 I/AnkiMcpTunnel( 123): hello

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `detailed logging off says so and has no logcat`() {
        val text = DetailedReport.format(
            info.copy(detailedLoggingUntil = null, oemSkin = null, ankiDroidVersion = null),
            fileLog = "",
            logcat = null,
            timeZone = utc,
        )

        assertTrue(text.contains("Detailed logging: off\n"))
        assertTrue(text.contains("Background freezer skin: none detected\n"))
        assertTrue(text.contains("AnkiDroid: not installed\n"))
        assertTrue(text.contains("== App log (oldest first) ==\n(empty)\n"))
        assertTrue(text.endsWith("(not included: detailed logging is off)\n"))
    }

    @Test
    fun `every secret type is redacted, in the file log and in logcat`() {
        val email = "me@example.com"
        val localToken = "q9W-8rT_7yU6iO5pA4sD3fG2hJ1kL0zXcVbNm1234"
        val fileLog = """
            2026-10-15 09:00:00.000+0000 I Signed in as Me@Example.com
            2026-10-15 09:00:01.000+0000 W Local token $localToken
            """.trimIndent()
        val logcat = """
            10-15 10:00:00.000 D/OkHttp( 123): Authorization: Bearer abc.def-123
            10-15 10:00:00.001 D/OkHttp( 123): authorization: Basic dXNlcjpwYXNz
            10-15 10:00:00.002 D/OkHttp( 123): Cookie: session=s3cr3t-cookie
            10-15 10:00:00.003 D/OkHttp( 123): {"access_token":"at-123456","refresh_token":"rt-abcdefgh9"}
            10-15 10:00:00.004 I/FirebaseMessaging( 123): token cR5dX1yZT2u-3vQ:APA91bH8xk2Lq9Zs0Vd7nW4mE6pR1tY3uI5oA8sD2fG4hJ6kL0zX
            10-15 10:00:00.005 I/Auth( 123): id_token eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVlLWhlcmU
            10-15 10:00:00.006 I/Auth( 123): user other.person@mail.example.org
            """.trimIndent()

        val text = DetailedReport.format(info, fileLog, logcat, secrets = listOf(email, localToken), timeZone = utc)

        listOf(
            "example.com", "example.org", "q9W-8rT", "abc.def-123", "dXNlcjpwYXNz", "s3cr3t", "at-123456",
            "rt-abcdefgh9", "APA91", "eyJhbGci", "other.person",
        ).forEach { assertFalse("$it was not redacted", text.contains(it, ignoreCase = true)) }
        assertTrue(text.contains("Authorization: Bearer [redacted]"))
        assertTrue(text.contains("authorization: [redacted]"))
        assertTrue(text.contains("Cookie: [redacted]"))
        assertTrue("the header survives redaction", text.contains("ROM build: RMX3741_15.0.0.800(EX01)"))
    }

    @Test
    fun `shared log files keep only the newest one`() {
        val files = SharedLogFiles(File(temp.root, SharedLogFiles.DIR_NAME))

        val first = files.write("one", t0)
        val second = files.write("two", t0 + 1_000)

        assertFalse(first.exists())
        assertEquals("two", second.readText())
        assertTrue(second.name.startsWith("ankimcp-log-") && second.name.endsWith(".txt"))

        files.clear()
        assertFalse(second.exists())
    }

    @Test
    fun `app start removes only a shared log older than an hour`() {
        val files = SharedLogFiles(File(temp.root, SharedLogFiles.DIR_NAME))
        val shared = files.write("fresh", t0)

        files.clearStale(t0 + SharedLogFiles.STALE_AFTER_MS - 1)
        assertTrue("the receiving app may still be reading it", shared.exists())

        files.clearStale(t0 + SharedLogFiles.STALE_AFTER_MS)
        assertFalse(shared.exists())
    }
}
