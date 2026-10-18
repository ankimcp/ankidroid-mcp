package ai.ankimcp.ankidroid.anki

/** One `decks` row as AnkiDroid sends it: counts as `[learn, review, new]` JSON, DECK_DYN as "true"/"false". */
data class DeckRow(val id: Long, val name: String, val countsJson: String?, val dynamicRaw: String?) {
    fun toRow(): ProviderRow = mapOf(
        FlashCardsContract.Deck.DECK_ID to id,
        FlashCardsContract.Deck.DECK_NAME to name,
        FlashCardsContract.Deck.DECK_COUNTS to countsJson,
        FlashCardsContract.Deck.DECK_DYN to dynamicRaw,
    )
}

fun List<DeckRow>.toRows(): List<ProviderRow> = map { it.toRow() }

/** Installed, permitted, and AnkiDroid 2.25.3 unless told otherwise. */
class FakeAvailability(
    var installed: Boolean = true,
    var permission: Boolean = true,
    var version: AnkiDroidVersion? = AnkiDroidVersion(22503300, "2.25.3"),
) : AnkiDroidAvailability {
    override fun isInstalled() = installed
    override fun hasPermission() = permission
    override fun version() = version
}

/** A provider query as the repository sent it. */
data class Query(val path: String, val projection: List<String>?, val selection: String?, val selectionArgs: List<String>?)

/**
 * Answers each query with [answer] (null for a null cursor) and records it. Rows keep only the
 * projected columns, as a real cursor would; a null answer from [answer] is a null cursor.
 */
open class RecordingProvider(private val answer: (Query) -> List<ProviderRow>?) : AnkiProvider {
    val queries = mutableListOf<Query>()

    override fun query(
        path: String,
        projection: List<String>?,
        selection: String?,
        selectionArgs: List<String>?,
        sortOrder: String?,
    ): ProviderCall<List<ProviderRow>> {
        val query = Query(path, projection, selection, selectionArgs)
        synchronized(queries) { queries += query }
        val rows = answer(query)?.map { row -> if (projection == null) row else row.filterKeys { it in projection } }
        return ProviderCall(rows, 0, viaClient = true, clientDied = false)
    }
}
