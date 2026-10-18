package ai.ankimcp.ankidroid.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The always-on file log (D61): info, warn and error entries, plus debug ones while
 * [isDetailed] is true, appended to `ankimcp.log` in [dir]. When the file would pass
 * [maxFileBytes] it becomes `ankimcp.log.1` (replacing the older one), so the log never takes
 * more than two files of [maxFileBytes].
 *
 * Every line is redacted ([LogReport.redact] with [secrets]) before it reaches the disk. All
 * file work runs on [executor], which must run tasks one at a time and in order (one thread in
 * the app); [append] only enqueues, so the tunnel and tool paths never wait for the disk. Lines
 * are buffered and flushed on warn and error, by [flush] (every few seconds in the app, see
 * [startPeriodicFlush]) and before [read].
 */
class FileLogSink(
    private val dir: File,
    private val executor: Executor,
    private val isDetailed: () -> Boolean,
    private val secrets: () -> Collection<String> = { emptyList() },
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    timeZone: TimeZone = TimeZone.getDefault(),
) : LogSink {
    private val current = File(dir, FILE_NAME)
    private val older = File(dir, OLDER_FILE_NAME)
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).apply { this.timeZone = timeZone }

    // Touched on the executor only.
    private var writer: Writer? = null
    private var currentBytes = 0L
    private var dirty = false

    override fun append(entry: LogEntry) {
        if (entry.level == LogEntry.Level.DEBUG && !isDetailed()) return
        submit { write(entry) }
    }

    /** Writes buffered lines to the file. */
    fun flush() = submit { flushQuietly() }

    /**
     * The file log, oldest line first (the rotated file, then the current one), after flushing.
     * Blocks up to [timeoutMs] for the writer; call it off the main thread.
     */
    fun read(timeoutMs: Long = READ_TIMEOUT_MS): String {
        val task = FutureTask {
            flushQuietly()
            listOf(older, current).filter { it.exists() }.joinToString("") { readQuietly(it) }
        }
        executor.execute(task)
        return task.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun submit(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Shutting down; the line is lost, nothing else to do.
        }
    }

    private fun write(entry: LogEntry) {
        val line = format(entry)
        val size = line.toByteArray(Charsets.UTF_8).size.toLong()
        try {
            val out = writer ?: open()
            val target = if (currentBytes > 0 && currentBytes + size > maxFileBytes) rotate() else out
            target.write(line)
            currentBytes += size
            if (entry.level == LogEntry.Level.WARN || entry.level == LogEntry.Level.ERROR) {
                target.flush()
                dirty = false
            } else {
                dirty = true
            }
        } catch (_: IOException) {
            // A full or unwritable disk must not break the app; the next line tries a fresh file.
            closeQuietly()
        }
    }

    private fun format(entry: LogEntry): String {
        // Redact before cutting: a secret cut in half would no longer match and survive as a fragment.
        val message = LogReport.redact(entry.message.replace("\r\n", "\n").replace('\n', ' '), secrets())
            .take(MAX_MESSAGE_CHARS)
        return "${time.format(Date(entry.timestampMs))} ${entry.level.name.first()} $message\n"
    }

    private fun open(): Writer {
        dir.mkdirs()
        currentBytes = current.length()
        return BufferedWriter(OutputStreamWriter(FileOutputStream(current, true), Charsets.UTF_8), BUFFER_CHARS)
            .also { writer = it }
    }

    private fun rotate(): Writer {
        closeQuietly()
        older.delete()
        if (!current.renameTo(older)) current.delete()
        return open()
    }

    private fun flushQuietly() {
        if (!dirty) return
        try {
            writer?.flush()
            dirty = false
        } catch (_: IOException) {
            closeQuietly()
        }
    }

    private fun closeQuietly() {
        try {
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
        dirty = false
    }

    private fun readQuietly(file: File): String = try {
        file.readText(Charsets.UTF_8)
    } catch (_: IOException) {
        "(could not read ${file.name})\n"
    }

    companion object {
        const val FILE_NAME = "ankimcp.log"
        const val OLDER_FILE_NAME = "ankimcp.log.1"
        const val DEFAULT_MAX_FILE_BYTES = 1_000_000L
        const val FLUSH_INTERVAL_MS = 10_000L
        private const val READ_TIMEOUT_MS = 5_000L
        private const val BUFFER_CHARS = 8 * 1024

        /** Longer messages are cut; one line must never fill the file. */
        const val MAX_MESSAGE_CHARS = 4_000

        /** One low-priority daemon thread for all file work. */
        fun newExecutor(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ankimcp-file-log").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }
}

/** Flushes [sink] every [FileLogSink.FLUSH_INTERVAL_MS] on [scheduler] (the sink's own executor in the app). */
fun startPeriodicFlush(sink: FileLogSink, scheduler: ScheduledExecutorService) {
    scheduler.scheduleWithFixedDelay(
        { sink.flush() },
        FileLogSink.FLUSH_INTERVAL_MS,
        FileLogSink.FLUSH_INTERVAL_MS,
        TimeUnit.MILLISECONDS,
    )
}
