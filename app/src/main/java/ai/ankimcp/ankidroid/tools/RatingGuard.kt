package ai.ankimcp.ankidroid.tools

/**
 * Stops rate_card from recording a second review of a card the scheduler has not handed out again
 * since it was rated (D57). Remembers the last card rated in this process and when; get_due_cards
 * returning that card clears it, so a card that comes back (learning steps, learn ahead) can be
 * rated again. present_card does not clear it: an unconfirmed rating sends Claude there to check,
 * which is exactly when a second rating must stay refused. A process that has rated nothing yet
 * (just started) has no memory, so it falls back to the card's last review time: under
 * [FALLBACK_WINDOW_S] ago is refused. One instance is shared by get_due_cards and rate_card.
 */
class RatingGuard {
    private var last: Pair<Long, Long>? = null
    private var ratedAny = false

    /** [cardId]'s rating was sent at [atSeconds] (epoch seconds). */
    @Synchronized
    fun rated(cardId: Long, atSeconds: Long) {
        last = cardId to atSeconds
        ratedAny = true
    }

    /** get_due_cards returned [cardId]: it may be rated again. Also used when a rating was not recorded. */
    @Synchronized
    fun shown(cardId: Long) {
        if (last?.first == cardId) last = null
    }

    /**
     * How many seconds ago [cardId] was rated, when rating it now would repeat that rating; null
     * when it may be rated. [lastReviewSecs] is the card's last review time, used only without memory.
     */
    @Synchronized
    fun repeatedAfter(cardId: Long, lastReviewSecs: Long?, nowSeconds: Long): Long? {
        if (ratedAny) {
            val (id, at) = last ?: return null
            return if (id == cardId) (nowSeconds - at).coerceAtLeast(0) else null
        }
        val ago = nowSeconds - (lastReviewSecs ?: return null)
        return if (lastReviewSecs > 0 && ago in 0 until FALLBACK_WINDOW_S) ago else null
    }

    companion object {
        /** Without memory of this process's ratings, a review this recent is taken as a repeat. */
        const val FALLBACK_WINDOW_S = 10L
    }
}
