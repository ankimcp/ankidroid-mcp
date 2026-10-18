package ai.ankimcp.ankidroid.log

import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a finished command printed (stdout and stderr together), or why it could not run. */
data class CommandOutput(
    /** The last lines it printed, at most the requested number. */
    val lines: List<String>,
    /** Lines dropped from the front to stay within the limit. */
    val droppedLines: Int = 0,
    /** Null when it did not finish in time or could not start. */
    val exitCode: Int? = null,
    val error: String? = null,
)

/** Runs a command and collects its output; the seam that keeps [LogcatReader] JVM-testable. */
fun interface CommandRunner {
    fun run(command: List<String>, maxLines: Int, timeoutMs: Long): CommandOutput
}

/**
 * This app's own lines from the system log, for the detailed log (D61).
 *
 * Since Android 4.1 an app without `READ_LOGS` (a signature/privileged permission) only gets
 * its own uid's entries from `logd`, so no permission is needed and nothing from other apps or
 * the system (e.g. an OEM freezer's "freeze uid" lines) can be read. The Android 13 log-access
 * consent dialog is for apps that hold `READ_LOGS`; this app does not, so it never sees it.
 * No `--pid`: the uid limit already keeps other apps out, and earlier processes of this app
 * (the ones the phone killed overnight) are what a report most needs. A line marks each
 * change of process. Only the newest [maxLines] lines and [maxBytes] are kept.
 */
class LogcatReader(
    private val runner: CommandRunner,
    private val pid: () -> Int,
    private val maxLines: Int = MAX_LINES,
    private val maxBytes: Int = MAX_BYTES,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** `-t` makes logcat itself print only the newest [maxLines]; the runner's cap is a backstop. */
    fun command(): List<String> = listOf("logcat", "-d", "-t", maxLines.toString(), "-v", "time")

    /** The dump as text, with a note in place of output that could not be read. */
    fun dump(): String {
        val out = runner.run(command(), maxLines, timeoutMs)
        val kept = newestWithin(out.lines, maxBytes)
        val dropped = out.droppedLines + out.lines.size - kept.size
        val current = pid()
        return buildString {
            if (dropped > 0) appendLine("($dropped older lines left out)")
            var shownPid: Int? = null
            kept.forEach { line ->
                val linePid = pidOf(line)
                if (linePid != null && linePid != shownPid) {
                    appendLine(
                        if (linePid == current) "--- process $linePid (the current one) ---"
                        else "--- process $linePid (an earlier or other process of this app) ---",
                    )
                    shownPid = linePid
                }
                appendLine(line)
            }
            when {
                out.error != null -> appendLine("(logcat could not be read: ${out.error})")
                out.exitCode == null -> appendLine("(logcat did not finish within ${timeoutMs / 1000} s; output may be cut)")
                out.exitCode != 0 -> appendLine("(logcat exited with ${out.exitCode})")
                out.lines.isEmpty() -> appendLine("(logcat returned no lines for this app)")
            }
        }
    }

    companion object {
        const val MAX_LINES = 2_000
        const val MAX_BYTES = 512 * 1024
        const val TIMEOUT_MS = 5_000L

        // `-v time`: "10-18 01:31:30.896 I/Tag( 1234): message".
        private val TIME_FORMAT_PID = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+ [VDIWEFS]/.*?\(\s*(\d+)\): """)

        /** The pid of a `-v time` line, or null for other lines ("--------- beginning of main"). */
        internal fun pidOf(line: String): Int? = TIME_FORMAT_PID.find(line)?.groupValues?.get(1)?.toIntOrNull()

        /** The newest lines whose UTF-8 size (with newlines) fits in [maxBytes]. */
        internal fun newestWithin(lines: List<String>, maxBytes: Int): List<String> {
            var total = 0
            var start = lines.size
            while (start > 0) {
                val size = lines[start - 1].toByteArray(Charsets.UTF_8).size + 1
                if (total + size > maxBytes) break
                total += size
                start--
            }
            return lines.subList(start, lines.size)
        }
    }
}

/** [CommandRunner] over a real process; the reading thread is a daemon, so a stuck command cannot keep us. */
class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, maxLines: Int, timeoutMs: Long): CommandOutput {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return CommandOutput(emptyList(), error = e.javaClass.simpleName)
        } catch (e: SecurityException) {
            return CommandOutput(emptyList(), error = e.javaClass.simpleName)
        }
        val lines = ArrayDeque<String>()
        var dropped = 0
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        synchronized(lines) {
                            if (lines.size == maxLines) {
                                lines.removeFirst()
                                dropped++
                            }
                            lines.addLast(line)
                        }
                    }
                }
            } catch (_: IOException) {
                // Stream closed by destroy() after a timeout.
            }
        }, "ankimcp-logcat").apply { isDaemon = true }
        reader.start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroy()
        reader.join(READER_GRACE_MS)
        return synchronized(lines) {
            CommandOutput(lines.toList(), dropped, if (finished) process.exitValue() else null)
        }
    }

    private companion object {
        const val READER_GRACE_MS = 1_000L
    }
}
