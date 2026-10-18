package ai.ankimcp.ankidroid.log

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Facts printed above a detailed log (D61). Like [LogReportHeader], nothing about the account. */
data class DetailedReportInfo(
    val appVersion: String,
    /** e.g. "10203 release". */
    val build: String,
    /** e.g. "16 (API 36)". */
    val androidVersion: String,
    val device: String,
    /** `Build.DISPLAY`, the ROM build the maker shows, e.g. "RMX3741_15.0.0.800(EX01)". */
    val romBuild: String,
    /** The freezing skin detected from the maker ([ai.ankimcp.ankidroid.service.FreezingOem]), or null. */
    val oemSkin: String?,
    /** Null when AnkiDroid is not installed. */
    val ankiDroidVersion: String?,
    val idleWindowMinutes: Int,
    val pushRegistered: Boolean,
    val localServerEnabled: Boolean,
    val localServerPort: Int,
    val localTokenRequired: Boolean,
    /** Wall-clock end of detailed logging, or null when it is off. */
    val detailedLoggingUntil: Long?,
    val batteryUnrestricted: Boolean,
    val notificationsGranted: Boolean,
    val notificationsShown: Boolean,
    val generatedAtMs: Long,
)

/** The text of "Share detailed log": header, file log, then this app's own logcat lines when detailed logging is on. */
object DetailedReport {
    /**
     * [fileLog] is [FileLogSink.read]'s text (already redacted line by line); [logcat] is
     * [LogcatReader.dump]'s, or null when detailed logging is off. Everything is redacted again
     * at the end with [secrets], since logcat lines never went through the file sink.
     */
    fun format(
        info: DetailedReportInfo,
        fileLog: String,
        logcat: String?,
        secrets: Collection<String> = emptyList(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).apply { this.timeZone = timeZone }
        val text = buildString {
            appendLine("AnkiMCP detailed log")
            appendLine("Generated: ${time.format(Date(info.generatedAtMs))}")
            appendLine()
            appendLine("App: AnkiMCP ${info.appVersion} (build ${info.build})")
            appendLine("Android: ${info.androidVersion}")
            appendLine("Device: ${info.device}")
            appendLine("ROM build: ${info.romBuild}")
            appendLine("Background freezer skin: ${info.oemSkin ?: "none detected"}")
            appendLine("AnkiDroid: ${info.ankiDroidVersion ?: "not installed"}")
            appendLine()
            appendLine("Idle window: ${info.idleWindowMinutes} min")
            appendLine("Push wake-ups registered: ${yesNo(info.pushRegistered)}")
            appendLine("Local server: ${if (info.localServerEnabled) "on" else "off"}, port ${info.localServerPort}")
            appendLine("Local server access token required: ${yesNo(info.localTokenRequired)}")
            appendLine(
                "Detailed logging: " +
                    (info.detailedLoggingUntil?.let { "on until ${time.format(Date(it))}" } ?: "off"),
            )
            appendLine("Battery optimisation off for AnkiMCP: ${yesNo(info.batteryUnrestricted)}")
            appendLine("Notification permission: ${if (info.notificationsGranted) "granted" else "not granted"}")
            appendLine("All notifications shown: ${yesNo(info.notificationsShown)}")
            appendLine()
            appendLine("== App log (oldest first) ==")
            if (fileLog.isEmpty()) appendLine("(empty)") else append(fileLog.ensureTrailingNewline())
            appendLine()
            appendLine("== This app's system log (logcat) ==")
            if (logcat == null) {
                appendLine("(not included: detailed logging is off)")
            } else {
                appendLine("(only this app's own lines; the phone's system messages are not available to apps)")
                appendLine("(at most the newest ${LogcatReader.MAX_LINES} lines)")
                append(logcat.ensureTrailingNewline())
            }
        }
        return LogReport.redact(text, secrets)
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    private fun String.ensureTrailingNewline() = if (isEmpty() || endsWith('\n')) this else "$this\n"
}

/**
 * The shared detailed-log files in `cacheDir/shared_logs/` (the only directory the FileProvider
 * exposes). There is at most one: a new share replaces it, and at app start [clearStale]
 * removes one older than [STALE_AFTER_MS]. A younger one stays: the receiving app may be what
 * started this process (reading the attachment through the FileProvider after the phone killed
 * us mid-compose). It is not deleted right after the share sheet closes either, because Android
 * does not say when the receiving app is done.
 */
class SharedLogFiles(private val dir: File) {
    /** Deletes files last modified [STALE_AFTER_MS] or more before [nowMs]. */
    fun clearStale(nowMs: Long) {
        dir.listFiles()?.filter { nowMs - it.lastModified() >= STALE_AFTER_MS }?.forEach { it.delete() }
    }

    fun write(text: String, nowMs: Long): File {
        clear()
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create ${dir.name}")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        return File(dir, "ankimcp-log-$stamp.txt").apply {
            writeText(text, Charsets.UTF_8)
            // The same clock as clearStale's.
            setLastModified(nowMs)
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Must match `res/xml/file_paths.xml`. */
        const val DIR_NAME = "shared_logs"
        const val STALE_AFTER_MS = 60 * 60 * 1000L
    }
}
