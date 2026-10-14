package ai.ankimcp.ankidroid.anki

import android.content.ContentResolver

/** One row of `content://com.ichi2.anki.flashcards/decks`, columns kept raw. */
data class DeckRow(
    val id: Long,
    val name: String,
    /** DECK_COUNTS: JSON array string `[learn, review, new]`, or null if the column is absent. */
    val countsJson: String?,
    /**
     * DECK_DYN as delivered over the binder. AnkiDroid puts a Boolean into a MatrixCursor,
     * which crosses process boundaries as the string "true"/"false", so read it as a string.
     */
    val dynamicRaw: String?,
)

/**
 * The slice of AnkiDroid's ContentProvider we use. Implementations are blocking and may throw
 * [SecurityException] (permission missing) or [IllegalStateException] (AnkiDroid storage not
 * set up). Kept as an interface so tools are unit-testable without Android.
 */
interface AnkiProvider {
    /** @return all decks, or null if the provider is unavailable (cursor was null). */
    fun queryDecks(): List<DeckRow>?
}

class ContentResolverAnkiProvider(private val resolver: ContentResolver) : AnkiProvider {
    override fun queryDecks(): List<DeckRow>? {
        val cursor = resolver.query(
            FlashCardsContract.Deck.CONTENT_ALL_URI,
            arrayOf(
                FlashCardsContract.Deck.DECK_ID,
                FlashCardsContract.Deck.DECK_NAME,
                FlashCardsContract.Deck.DECK_COUNTS,
                FlashCardsContract.Deck.DECK_DYN,
            ),
            null,
            null,
            null,
        ) ?: return null
        return cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_ID)
            val nameIdx = c.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_NAME)
            val countsIdx = c.getColumnIndex(FlashCardsContract.Deck.DECK_COUNTS)
            val dynIdx = c.getColumnIndex(FlashCardsContract.Deck.DECK_DYN)
            buildList {
                while (c.moveToNext()) {
                    add(
                        DeckRow(
                            id = c.getLong(idIdx),
                            name = c.getString(nameIdx) ?: "",
                            countsJson = if (countsIdx >= 0 && !c.isNull(countsIdx)) c.getString(countsIdx) else null,
                            dynamicRaw = if (dynIdx >= 0 && !c.isNull(dynIdx)) c.getString(dynIdx) else null,
                        ),
                    )
                }
            }
        }
    }
}
