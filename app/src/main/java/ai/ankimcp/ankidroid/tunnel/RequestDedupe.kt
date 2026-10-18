package ai.ankimcp.ankidroid.tunnel

import kotlinx.coroutines.CompletableDeferred

/**
 * At-most-once execution of relay `request` frames per `requestId`, shared by every
 * [TunnelClient] of the process (D52).
 *
 * The relay re-forwards a request it got no reply for on the next connection with the same
 * `requestId`. [begin] records the id at receipt, before anything runs; a repeat gets the
 * original's [Entry] instead of a second execution, unless the original was cancelled before
 * its handler started ([Outcome.NeverRan]), in which case the repeat claims the id afresh.
 * Entries live [ttlMs] on [clock] (which keeps counting while the process is frozen) and at
 * most [maxEntries] are kept, oldest dropped first; expired entries are pruned on every [begin]
 * and every completion. In memory only: a process restart forgets them.
 */
class RequestDedupe(
    private val clock: ElapsedClock,
    private val ttlMs: Long = TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    /** How the run that claimed an [Entry] ended. */
    sealed interface Outcome {
        data class Answered(val response: ClientMessage.Response) : Outcome

        /** The handler started and was cut off before it had a response: the result is unknown. */
        data object CutOff : Outcome

        /** Cancelled before the handler started: nothing ran, so the id may be claimed again. */
        data object NeverRan : Outcome
    }

    class Entry internal constructor(
        val requestId: String,
        val receivedAtMs: Long,
        private val onDone: () -> Unit,
    ) {
        /** Completed once with the run's [Outcome]; later completions are ignored. */
        val result = CompletableDeferred<Outcome>()

        /** Set before [result] completes, so [begin] sees it as soon as any waiter could. */
        @Volatile
        var outcome: Outcome? = null
            private set

        @Volatile
        private var started = false

        /** Called right before the handler runs; from then on an abandoned run is [Outcome.CutOff]. */
        fun markStarted() {
            started = true
        }

        fun complete(response: ClientMessage.Response) = finish(Outcome.Answered(response))

        /** The run ended without a response. No-op once completed. */
        fun abandon() = finish(if (started) Outcome.CutOff else Outcome.NeverRan)

        private fun finish(value: Outcome) {
            synchronized(this) {
                if (outcome != null) return
                outcome = value
            }
            result.complete(value)
            onDone()
        }
    }

    sealed interface Claim {
        val entry: Entry

        /** First sighting (or a reclaimed [Outcome.NeverRan]): run the request and complete the entry. */
        data class Fresh(override val entry: Entry) : Claim

        /** Seen before: answer from [Entry.result], never run it again. */
        data class Duplicate(override val entry: Entry) : Claim
    }

    // Insertion order is receipt order, so the head is always the oldest entry.
    private val entries = LinkedHashMap<String, Entry>()

    fun begin(requestId: String): Claim = synchronized(entries) {
        val now = clock.elapsedMs()
        pruneLocked(now)
        entries[requestId]?.let { existing ->
            if (existing.outcome != Outcome.NeverRan) return Claim.Duplicate(existing)
            entries.remove(requestId)
        }
        val entry = Entry(requestId, now, ::prune)
        entries[requestId] = entry
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        Claim.Fresh(entry)
    }

    internal val size: Int get() = synchronized(entries) { entries.size }

    private fun prune() = synchronized(entries) { pruneLocked(clock.elapsedMs()) }

    private fun pruneLocked(now: Long) {
        val iterator = entries.values.iterator()
        while (iterator.hasNext() && now - iterator.next().receivedAtMs > ttlMs) iterator.remove()
    }

    companion object {
        /** Covers the relay's ~30 s reply timeout plus claude.ai's ~40 s, with margin. */
        const val TTL_MS = 120_000L
        const val MAX_ENTRIES = 64
    }
}
