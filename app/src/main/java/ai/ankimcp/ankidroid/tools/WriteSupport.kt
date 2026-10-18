package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.NoteType
import ai.ankimcp.ankidroid.anki.RequiredVersion
import ai.ankimcp.ankidroid.mcp.ToolResult
import java.text.Normalizer

/** Owner decision (D57): every batch write tool takes at most this many notes or cards per call. */
internal const val MAX_WRITE_BATCH = 20

/** The add-on's batch-size error, for a phone limit that has no config option. */
internal fun tooMany(count: Int, unit: String = "notes"): Nothing = fail(
    "Too many $unit: $count (maximum is $MAX_WRITE_BATCH)",
    hint = "Split your request into batches of $MAX_WRITE_BATCH or fewer.",
    code = "limit_exceeded",
    context = mapOf("requested" to count, "maximum" to MAX_WRITE_BATCH),
)

/** A write failure that means AnkiDroid was never asked (so nothing changed). */
internal fun AnkiResult.Failure.notSent(): Boolean =
    this is AnkiResult.NotSent || this is AnkiResult.TooSlow || this is AnkiResult.NotResponding ||
        this is AnkiResult.Busy || this is AnkiResult.Frozen || this is AnkiResult.Outdated ||
        this is AnkiResult.NotInstalled || this is AnkiResult.PermissionDenied

/**
 * The value of a read that confirms a write AnkiDroid accepted. A failure here ends the call
 * with "[what] was sent to AnkiDroid and accepted, but could not be confirmed", so the reply
 * never reads as "not done" for a change that may have happened (D57).
 */
internal fun <T> AnkiResult<T>.confirmedOr(what: String): T = when (this) {
    is AnkiResult.Success -> value
    is AnkiResult.Failure -> throw ToolFailure(
        ToolResult.error("$what was sent to AnkiDroid and accepted, but could not be confirmed: $message Check before repeating it."),
    )
}

/**
 * Tracks a write that goes item by item, so a call that has to stop (the tool's 12 s budget ran
 * out, AnkiDroid stopped answering, an outcome became unknown) says exactly what was done,
 * what needed no change and what was not done, instead of truncating silently (D57).
 */
internal class WriteProgress(private val total: Int, private val unit: String) {
    val done = mutableListOf<String>()
    val unchanged = mutableListOf<String>()

    /**
     * Ends the tool call after [failure] on item [at]: not sent (nothing written for it), unknown
     * (sent, no answer) or refused; everything in [remaining] was not done.
     */
    fun stop(failure: AnkiResult.Failure, at: String, remaining: List<String>): Nothing {
        val unknown = failure is AnkiResult.OutcomeUnknown
        val notDone = (if (unknown) emptyList() else listOf(at)) + remaining
        val text = buildString {
            append("Did ${done.size} of $total $unit before stopping.")
            append(" Done: ${list(done)}.")
            if (unchanged.isNotEmpty()) append(" Unchanged (nothing to do): ${list(unchanged)}.")
            if (unknown) append(" Unknown whether $at was changed.")
            append(" Not done: ${list(notDone)}.")
            append(" Reason: ${failure.message}")
            if (!failure.notSent() && !unknown) append(" (AnkiDroid refused $at, nothing was written for it)")
        }
        throw ToolFailure(ToolResult.error(text))
    }

    private fun list(items: List<String>) = if (items.isEmpty()) "none" else items.joinToString(", ")
}

internal fun List<Long>.labels() = map { it.toString() }

/** Anki stores fields joined by U+001F, so a value containing it would shift every later field (D57). */
internal fun rejectFieldSeparator(values: Collection<String>, what: String = "Field values") {
    if (values.any { '\u001f' in it }) {
        fail(
            "$what cannot contain the character U+001F, which Anki uses to separate fields",
            hint = "Remove the U+001F characters and retry.",
            code = "validation_error",
        )
    }
}

/** NFC, as rslib normalises note text when it saves it. */
internal fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

internal fun nfc(values: List<String>?): List<String>? = values?.map(::nfc)

/** Raises add-on-style "not found" for a note type looked up by name (`col.models.by_name`). */
internal suspend fun AnkiDroidRepository.noteTypeNamed(name: String): NoteType? =
    listNoteTypes().orFail().byName(name) { it.name }

/** The CARD_FLAGS (2.25) gate for card_set_flag, run before any read. */
internal fun AnkiDroidRepository.requireVersion(required: RequiredVersion) {
    checkVersion(required)?.let { throw ToolFailure(ToolResult.error(it.message)) }
}
