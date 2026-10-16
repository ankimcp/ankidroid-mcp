package ai.ankimcp.ankidroid.anki

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class DeckCounts(val new: Long, val learn: Long, val review: Long)

data class Deck(
    val id: Long,
    val name: String,
    val isFiltered: Boolean,
    /** Null if AnkiDroid did not return DECK_COUNTS for this deck. */
    val counts: DeckCounts?,
)

/** Outcome of a provider call; every failure carries a user-readable message. */
sealed interface AnkiResult<out T> {
    data class Success<T>(val value: T) : AnkiResult<T>

    sealed interface Failure : AnkiResult<Nothing> {
        val message: String
    }

    data object NotInstalled : Failure {
        override val message = "AnkiDroid is not installed on this phone. Install AnkiDroid from Google Play " +
            "and open it once, then try again."
    }

    data object PermissionDenied : Failure {
        override val message = "AnkiDroid MCP does not have permission to access AnkiDroid. Open the AnkiDroid " +
            "MCP app on the phone and grant the AnkiDroid database permission."
    }

    data class NotReady(val detail: String?) : Failure {
        override val message = "AnkiDroid is not ready: its collection storage is not configured. Open " +
            "AnkiDroid on the phone and finish its first-run setup, then try again." +
            (detail?.let { " ($it)" } ?: "")
    }

    /** A call got no answer within the timeout. */
    data class NotResponding(val timeoutMs: Long) : Failure {
        override val message = "AnkiDroid is not responding (no answer within ${timeoutMs / 1000} seconds). " +
            "Restart AnkiDroid on the phone (close it from recent apps or force-stop it, then open it again) " +
            "and try again."
    }

    /** Every provider thread is busy and the wait queue is full. */
    data object Busy : Failure {
        override val message = "AnkiDroid is busy with other requests from AnkiDroid MCP. Try again in a moment."
    }

    data class Unavailable(override val message: String) : Failure
}

/**
 * Reads from AnkiDroid's ContentProvider. Each provider call runs on [providerExecutor] (a small
 * bounded pool with a short queue, see [newProviderExecutor]) and is abandoned after [timeoutMs],
 * counted from submission. A binder call that never returns keeps its thread, but only that one.
 * A full queue fails fast with [AnkiResult.Busy]; only a timeout reports
 * [AnkiResult.NotResponding].
 *
 * A null cursor usually means the OS had frozen AnkiDroid's process; acquiring the provider
 * thaws it, so the query is retried after each of [nullRetryDelaysMs] before giving up. The
 * retries share the [timeoutMs] budget.
 */
class AnkiDroidRepository(
    private val provider: AnkiProvider,
    private val availability: AnkiDroidAvailability,
    private val providerExecutor: Executor,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val nullRetryDelaysMs: List<Long> = NULL_RETRY_DELAYS_MS,
) {
    suspend fun listDecks(): AnkiResult<List<Deck>> {
        val rows = when (val result = queryRetryingNull { provider.queryDecks() }) {
            is AnkiResult.Success -> result.value
                ?: return AnkiResult.Unavailable(
                    "AnkiDroid did not answer the deck query: the phone put AnkiDroid to sleep in the " +
                        "background. Quick fix: open AnkiDroid once on the phone and try again. To stop it " +
                        "happening, allow AnkiDroid to run in the background (on Realme/Oppo/OnePlus: App " +
                        "info > Battery usage > \"Allow background activity\"); the AnkiDroid MCP Status tab " +
                        "names the setting for this phone.",
                )
            is AnkiResult.Failure -> return result
        }
        return AnkiResult.Success(
            rows.filter { it.id != 0L && it.name.isNotEmpty() }
                .map { row ->
                    Deck(
                        id = row.id,
                        name = row.name,
                        isFiltered = parseBoolean(row.dynamicRaw),
                        counts = row.countsJson?.let(::parseCounts),
                    )
                },
        )
    }

    /** Runs [query] until it returns non-null or the retries run out; still null after that is a success with null. */
    private suspend fun <T : Any> queryRetryingNull(query: () -> T?): AnkiResult<T?> {
        if (!availability.isInstalled()) return AnkiResult.NotInstalled
        if (!availability.hasPermission()) return AnkiResult.PermissionDenied
        return withTimeoutOrNull(timeoutMs) {
            var result = submit { AnkiResult.Success(query()) }
            for (delayMs in nullRetryDelaysMs) {
                if (result !is AnkiResult.Success || result.value != null) break
                delay(delayMs)
                result = submit { AnkiResult.Success(query()) }
            }
            result
        } ?: AnkiResult.NotResponding(timeoutMs)
    }

    private suspend fun <T> submit(block: () -> AnkiResult<T>): AnkiResult<T> {
        val result = CompletableDeferred<AnkiResult<T>>()
        try {
            providerExecutor.execute {
                // Still queued when the caller gave up: don't send AnkiDroid a query nobody waits for.
                if (result.isActive) result.complete(runProviderCall(block))
            }
        } catch (_: RejectedExecutionException) {
            return AnkiResult.Busy
        }
        try {
            return result.await()
        } finally {
            result.cancel()
        }
    }

    private fun <T> runProviderCall(block: () -> AnkiResult<T>): AnkiResult<T> = try {
        block()
    } catch (e: SecurityException) {
        AnkiResult.PermissionDenied
    } catch (e: IllegalStateException) {
        if (isStorageNotConfigured(e)) {
            AnkiResult.NotReady(e.message)
        } else {
            AnkiResult.Unavailable(providerError(e))
        }
    } catch (e: Exception) {
        AnkiResult.Unavailable(providerError(e))
    }

    companion object {
        /**
         * Below the 25 s tunnel request timeout so the user gets this readable tool error
         * rather than a generic 504.
         */
        const val DEFAULT_TIMEOUT_MS = 20_000L

        /** Three attempts over about 2 s. */
        val NULL_RETRY_DELAYS_MS = listOf(500L, 1_500L)

        /** Upper bound on threads that a hung AnkiDroid can pin. */
        const val MAX_PROVIDER_THREADS = 4

        /** Calls that may wait for a free provider thread before new ones are rejected as busy. */
        const val PROVIDER_QUEUE_CAPACITY = 8

        /**
         * Up to [maxThreads] threads (started per call until the limit, idle ones exit after 60 s),
         * then a FIFO queue of [queueCapacity]. When that is full too, `execute` throws
         * [RejectedExecutionException], which [AnkiDroidRepository] reports as [AnkiResult.Busy].
         */
        fun newProviderExecutor(
            maxThreads: Int = MAX_PROVIDER_THREADS,
            queueCapacity: Int = PROVIDER_QUEUE_CAPACITY,
        ): ThreadPoolExecutor {
            val count = AtomicInteger()
            // core == max: with a bounded queue, threads above the core size would only start once
            // the queue is full, so calls would wait behind a stuck one while threads were free.
            return ThreadPoolExecutor(
                maxThreads,
                maxThreads,
                60L,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(queueCapacity),
                { r -> Thread(r, "anki-provider-${count.incrementAndGet()}").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            ).apply { allowCoreThreadTimeOut(true) }
        }

        private val json = Json { ignoreUnknownKeys = true }

        private fun isStorageNotConfigured(e: IllegalStateException): Boolean {
            val message = e.message ?: return false
            return message.contains("storage", ignoreCase = true) || message.contains("not configured", ignoreCase = true)
        }

        private fun providerError(e: Exception): String =
            "AnkiDroid returned an error: ${e.message ?: e.javaClass.simpleName}"

        /** DECK_COUNTS is `[learn, review, new]`. */
        internal fun parseCounts(raw: String): DeckCounts? = try {
            val array = json.parseToJsonElement(raw) as? JsonArray
            val values = array?.map { (it as? JsonPrimitive)?.longOrNull }
            if (values == null || values.size < 3 || values.any { it == null }) {
                null
            } else {
                DeckCounts(learn = values[0]!!, review = values[1]!!, new = values[2]!!)
            }
        } catch (_: Exception) {
            null
        }

        internal fun parseBoolean(raw: String?): Boolean =
            raw != null && (raw.equals("true", ignoreCase = true) || raw == "1")
    }
}
