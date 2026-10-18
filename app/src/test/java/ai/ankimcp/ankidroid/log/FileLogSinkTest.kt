package ai.ankimcp.ankidroid.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException

class FileLogSinkTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-15 10:00:00 UTC
    private val t0 = 1_792_058_400_000L
    private var detailed = false

    /** Runs file work inline, so each test sees the disk right after the call. */
    private val inline = Executor { it.run() }

    private fun sink(
        dir: File = temp.root,
        maxFileBytes: Long = FileLogSink.DEFAULT_MAX_FILE_BYTES,
        secrets: Collection<String> = emptyList(),
        executor: Executor = inline,
    ) = FileLogSink(dir, executor, isDetailed = { detailed }, secrets = { secrets }, maxFileBytes = maxFileBytes, timeZone = utc)

    private fun entry(level: LogEntry.Level, message: String, at: Long = t0) = LogEntry(at, level, message)

    private val current get() = File(temp.root, FileLogSink.FILE_NAME)
    private val older get() = File(temp.root, FileLogSink.OLDER_FILE_NAME)

    @Test
    fun `writes info, warn and error lines with date, offset and level`() {
        val sink = sink()
        sink.append(entry(LogEntry.Level.INFO, "Tunnel established"))
        sink.append(entry(LogEntry.Level.WARN, "Reconnecting", t0 + 1_500))
        sink.append(entry(LogEntry.Level.ERROR, "Failed"))

        assertEquals(
            "2026-10-15 10:00:00.000+0000 I Tunnel established\n" +
                "2026-10-15 10:00:01.500+0000 W Reconnecting\n" +
                "2026-10-15 10:00:00.000+0000 E Failed\n",
            sink.read(),
        )
    }

    @Test
    fun `debug lines only while detailed logging is on`() {
        val sink = sink()
        sink.append(entry(LogEntry.Level.DEBUG, "hidden"))
        detailed = true
        sink.append(entry(LogEntry.Level.DEBUG, "shown"))
        detailed = false
        sink.append(entry(LogEntry.Level.DEBUG, "hidden again"))

        val text = sink.read()
        assertFalse(text.contains("hidden"))
        assertTrue(text.contains(" D shown\n"))
    }

    @Test
    fun `info is buffered until a warn, a flush or a read`() {
        val sink = sink()
        sink.append(entry(LogEntry.Level.INFO, "buffered"))
        assertEquals("info stays in the buffer", 0L, current.length())

        sink.append(entry(LogEntry.Level.WARN, "warned"))
        val onDisk = current.readText()
        assertTrue(onDisk.contains("buffered"))
        assertTrue(onDisk.contains("warned"))

        sink.append(entry(LogEntry.Level.INFO, "later"))
        assertFalse(current.readText().contains("later"))
        sink.flush()
        assertTrue(current.readText().contains("later"))
    }

    @Test
    fun `error flushes at once`() {
        val sink = sink()
        sink.append(entry(LogEntry.Level.ERROR, "boom"))

        assertTrue(current.readText().contains(" E boom"))
    }

    @Test
    fun `rotates into one older file and never exceeds two files of the cap`() {
        val cap = 2_000L
        val sink = sink(maxFileBytes = cap)
        repeat(200) { i -> sink.append(entry(LogEntry.Level.INFO, "line %03d padding padding padding".format(i))) }
        sink.flush()

        assertTrue(current.length() <= cap)
        assertTrue(older.exists())
        assertTrue(older.length() <= cap)
        assertEquals(listOf(FileLogSink.FILE_NAME, FileLogSink.OLDER_FILE_NAME).sorted(), temp.root.list()!!.sorted())

        val text = sink.read()
        val numbers = Regex("""line (\d{3})""").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertEquals("oldest first, no gaps, ends with the newest", (numbers.first()..199).toList(), numbers)
        assertTrue("the oldest lines were dropped", numbers.first() > 0)
    }

    @Test
    fun `a new sink appends to the existing file and keeps counting its size`() {
        val cap = 500L
        sink(maxFileBytes = cap).apply {
            repeat(5) { append(entry(LogEntry.Level.WARN, "first process $it")) }
        }
        val second = sink(maxFileBytes = cap)
        repeat(5) { second.append(entry(LogEntry.Level.WARN, "second process $it")) }

        val text = second.read()
        assertTrue(text.contains("first process 4"))
        assertTrue(text.contains("second process 4"))
        assertTrue(current.length() <= cap)
    }

    @Test
    fun `an overlong message is cut and newlines are flattened`() {
        val sink = sink()
        sink.append(entry(LogEntry.Level.INFO, "a\nb\r\nc"))
        sink.append(entry(LogEntry.Level.INFO, "x".repeat(10_000)))

        val lines = sink.read().lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith(" I a b c"))
        assertEquals(FileLogSink.MAX_MESSAGE_CHARS, lines[1].substringAfter(" I ").length)
    }

    @Test
    fun `secrets are redacted before the line reaches the disk`() {
        val sink = sink(secrets = listOf("me@example.com", "LocalTok3nValueThatIsLongEnough"))
        sink.append(entry(LogEntry.Level.WARN, "Authorization: Bearer x"))
        sink.append(entry(LogEntry.Level.WARN, "refresh_token=r0tat3d access_token: abcdefgh123"))
        sink.append(entry(LogEntry.Level.WARN, "Signed in as Me@Example.com"))
        sink.append(entry(LogEntry.Level.WARN, "local token LocalTok3nValueThatIsLongEnough used"))
        sink.append(entry(LogEntry.Level.WARN, "fcm cR5dX1yZT2u-3vQ:APA91bH8xk2Lq9Zs0Vd7nW4mE6pR1tY3uI5oA8sD2fG4hJ6kL0zX"))
        sink.append(entry(LogEntry.Level.WARN, "jwt eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVlLWhlcmU"))

        val disk = current.readText()
        listOf("Bearer x", "r0tat3d", "abcdefgh123", "example.com", "LocalTok3n", "APA91", "eyJhbGci").forEach {
            assertFalse("$it reached the disk", disk.contains(it, ignoreCase = true))
        }
        assertTrue(disk.contains("Authorization: Bearer [redacted]"))
    }

    @Test
    fun `a secret spanning the length cut leaves no fragment`() {
        val secret = "LocalTok3nValueThatIsLongEnough"
        val sink = sink(secrets = listOf(secret))
        // The secret starts 10 characters before the cut.
        sink.append(entry(LogEntry.Level.WARN, "x".repeat(FileLogSink.MAX_MESSAGE_CHARS - 10) + secret + " tail"))

        val disk = current.readText()
        assertFalse(disk.contains(secret.take(10)))
        assertTrue(disk.trimEnd().endsWith("[redacted]"))
    }

    @Test
    fun `read gives up after its timeout when the writer is stuck`() {
        val sink = sink(executor = { /* never runs */ })

        try {
            sink.read(timeoutMs = 50)
            fail("expected a timeout")
        } catch (_: TimeoutException) {
        }
    }

    @Test
    fun `concurrent writers on the real executor keep the caps and whole lines`() {
        val executor = FileLogSink.newExecutor()
        try {
            val cap = 1_500L
            val sink = sink(maxFileBytes = cap, executor = executor)
            val threads = (1..4).map { t ->
                Thread { repeat(300) { i -> sink.append(entry(LogEntry.Level.INFO, "thread $t line $i")) } }
            }
            threads.forEach(Thread::start)
            threads.forEach { it.join(10_000) }

            val text = sink.read()
            assertTrue(current.length() <= cap)
            assertTrue(older.length() <= cap)
            val lines = text.lines().filter { it.isNotEmpty() }
            assertTrue(lines.isNotEmpty())
            val shape = Regex("""^2026-10-15 10:00:00\.000\+0000 I thread [1-4] line \d+$""")
            lines.forEach { assertTrue("torn line: $it", shape.matches(it)) }
            assertTrue("the very last lines written are kept", lines.any { it.endsWith(" line 299") })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `append only enqueues work on the executor`() {
        val queued = mutableListOf<Runnable>()
        val sink = sink(executor = { queued += it })

        sink.append(entry(LogEntry.Level.ERROR, "later"))

        assertEquals(1, queued.size)
        assertFalse(current.exists())
        queued.single().run()
        assertTrue(current.readText().contains("later"))
    }

    @Test
    fun `an unwritable directory loses lines without throwing`() {
        val blocker = temp.newFile("not-a-dir")
        val sink = sink(dir = File(blocker, "logs"))

        sink.append(entry(LogEntry.Level.ERROR, "lost"))

        assertEquals("", sink.read())
    }

    @Test
    fun `TunnelLog feeds its sinks in order`() {
        val sink = sink()
        val log = TunnelLog(clock = { t0 }, mirrorToLogcat = false, sinks = listOf(sink))
        log.info("one")
        log.warn("two")
        log.debug("three")

        assertEquals(
            "2026-10-15 10:00:00.000+0000 I one\n2026-10-15 10:00:00.000+0000 W two\n",
            sink.read(),
        )
        assertEquals(3, log.entries.value.size)
    }
}
