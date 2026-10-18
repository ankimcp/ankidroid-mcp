package ai.ankimcp.ankidroid.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatReaderTest {
    private var lastCommand: List<String>? = null

    private var lastMaxLines: Int? = null

    private fun reader(output: CommandOutput, maxBytes: Int = LogcatReader.MAX_BYTES) = LogcatReader(
        runner = { command, maxLines, _ ->
            lastCommand = command
            lastMaxLines = maxLines
            output
        },
        pid = { 4242 },
        maxBytes = maxBytes,
    )

    private fun line(pid: Int, message: String) = "10-18 01:31:30.896 I/AnkiMcpTunnel( $pid): $message"

    @Test
    fun `dumps all of this app's processes with times, newest 2000 lines`() {
        reader(CommandOutput(listOf("a"), exitCode = 0)).dump()

        assertEquals(
            "logcat does the tail; no --pid: earlier processes count",
            listOf("logcat", "-d", "-t", "2000", "-v", "time"),
            lastCommand,
        )
        assertEquals(2_000, lastMaxLines)
    }

    @Test
    fun `prints the lines`() {
        val text = reader(CommandOutput(listOf("line 1", "line 2"), exitCode = 0)).dump()

        assertEquals("line 1\nline 2\n", text)
    }

    @Test
    fun `marks each change of process`() {
        val text = reader(
            CommandOutput(
                listOf(
                    "--------- beginning of main",
                    line(1111, "old one"),
                    line(1111, "old two"),
                    "10-18 01:31:31.000 W/Tag(with parens)(  999): odd tag",
                    line(4242, "new one"),
                ),
                exitCode = 0,
            ),
        ).dump()

        assertEquals(
            "--------- beginning of main\n" +
                "--- process 1111 (an earlier or other process of this app) ---\n" +
                line(1111, "old one") + "\n" +
                line(1111, "old two") + "\n" +
                "--- process 999 (an earlier or other process of this app) ---\n" +
                "10-18 01:31:31.000 W/Tag(with parens)(  999): odd tag\n" +
                "--- process 4242 (the current one) ---\n" +
                line(4242, "new one") + "\n",
            text,
        )
    }

    @Test
    fun `keeps the newest lines within the byte cap`() {
        val lines = (1..100).map { line(4242, "message %03d".format(it)) }
        val lineBytes = lines.first().length + 1

        val text = reader(CommandOutput(lines, droppedLines = 5, exitCode = 0), maxBytes = lineBytes * 10).dump()

        assertTrue(text.startsWith("(95 older lines left out)\n--- process 4242 (the current one) ---\n"))
        assertTrue(text.contains("message 091"))
        assertTrue(text.endsWith("message 100\n"))
        assertTrue(!text.contains("message 090"))
    }

    @Test
    fun `notes dropped lines, timeouts, failures and empty output`() {
        assertTrue(reader(CommandOutput(listOf("x"), droppedLines = 7, exitCode = 0)).dump().startsWith("(7 older lines left out)\nx\n"))
        assertTrue(reader(CommandOutput(listOf("x"), exitCode = null)).dump().endsWith("(logcat did not finish within 5 s; output may be cut)\n"))
        assertEquals("(logcat could not be read: IOException)\n", reader(CommandOutput(emptyList(), error = "IOException")).dump())
        assertEquals("(logcat exited with 1)\n", reader(CommandOutput(emptyList(), exitCode = 1)).dump())
        assertEquals("(logcat returned no lines for this app)\n", reader(CommandOutput(emptyList(), exitCode = 0)).dump())
    }

    @Test
    fun `process runner keeps the last lines of a real command`() {
        val out = ProcessCommandRunner().run(listOf("sh", "-c", "for i in 1 2 3 4 5; do echo line\$i; done"), maxLines = 3, timeoutMs = 5_000)

        assertEquals(listOf("line3", "line4", "line5"), out.lines)
        assertEquals(2, out.droppedLines)
        assertEquals(0, out.exitCode)
    }

    @Test
    fun `process runner stops a command that does not finish`() {
        val out = ProcessCommandRunner().run(listOf("sh", "-c", "echo started; exec sleep 30"), maxLines = 10, timeoutMs = 500)

        assertEquals(null, out.exitCode)
        assertEquals(listOf("started"), out.lines)
    }

    @Test
    fun `process runner reports a command that cannot start`() {
        val out = ProcessCommandRunner().run(listOf("/nonexistent/logcat-for-test"), maxLines = 10, timeoutMs = 1_000)

        assertEquals("IOException", out.error)
    }
}
