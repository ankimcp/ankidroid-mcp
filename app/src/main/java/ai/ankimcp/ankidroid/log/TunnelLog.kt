package ai.ankimcp.ankidroid.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One line in the in-app tunnel log. [timestampMs] is wall-clock epoch millis. */
data class LogEntry(
    val timestampMs: Long,
    val level: Level,
    val message: String,
) {
    enum class Level { DEBUG, INFO, WARN, ERROR }
}

/** A second destination for every [TunnelLog] entry. Called under the log's lock: must not block. */
fun interface LogSink {
    fun append(entry: LogEntry)
}

/**
 * Fixed-size ring buffer of recent tunnel events, shown on the Log tab, and the single entry
 * point for the app's log: every entry also goes to [sinks] (the rotating file log, D61).
 *
 * Never pass tokens or other secrets to this log: it is rendered verbatim in the UI,
 * mirrored to logcat, written to the file log and shared by the user (redacted, see [LogReport]).
 */
class TunnelLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mirrorToLogcat: Boolean = true,
    private val sinks: List<LogSink> = emptyList(),
) {
    private val buffer = ArrayDeque<LogEntry>(capacity)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Snapshot of the buffer, oldest first. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun debug(message: String) = append(LogEntry.Level.DEBUG, message)

    fun info(message: String) = append(LogEntry.Level.INFO, message)

    fun warn(message: String) = append(LogEntry.Level.WARN, message)

    fun error(message: String) = append(LogEntry.Level.ERROR, message)

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    private fun append(level: LogEntry.Level, message: String) {
        if (mirrorToLogcat) {
            val priority = when (level) {
                LogEntry.Level.DEBUG -> Log.DEBUG
                LogEntry.Level.INFO -> Log.INFO
                LogEntry.Level.WARN -> Log.WARN
                LogEntry.Level.ERROR -> Log.ERROR
            }
            Log.println(priority, TAG, message)
        }
        synchronized(buffer) {
            val entry = LogEntry(clock(), level, message)
            if (buffer.size == capacity) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
            // Inside the lock so the file keeps the buffer's order.
            sinks.forEach { it.append(entry) }
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 100
        private const val TAG = "AnkiMcpTunnel"
    }
}
