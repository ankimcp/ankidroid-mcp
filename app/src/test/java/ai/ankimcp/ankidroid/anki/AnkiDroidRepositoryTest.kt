package ai.ankimcp.ankidroid.anki

import ai.ankimcp.ankidroid.log.LogEntry
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

/** Null-cursor retry schedule, frozen detection and the client release, on virtual time with a same-thread executor. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnkiDroidRepositoryTest {
    /** Each answer's `tookMs` is also added to the clock, as time spent inside the query. */
    private inner class FakeProvider(private val answer: (attempt: Int) -> ProviderCall<List<DeckRow>>) : AnkiProvider {
        val attemptTimes = mutableListOf<Long>()
        val releases = mutableListOf<String>()
        var now: () -> Long = { 0L }
        var clientHeld = false

        override fun isClientHeld() = clientHeld

        override fun query(
            path: String,
            projection: List<String>?,
            selection: String?,
            selectionArgs: List<String>?,
            sortOrder: String?,
        ): ProviderCall<List<ProviderRow>> {
            hungAttempt.get()?.let { entered ->
                // A hung attempt: it reached AnkiDroid, which does not answer.
                entered.countDown()
                probeRelease.await()
                throw IllegalStateException("released")
            }
            if (projection == listOf(FlashCardsContract.Deck.DECK_ID)) {
                // The freeze probe: it reached AnkiDroid, which does not answer.
                probeEntered.countDown()
                probeRelease.await()
                throw IllegalStateException("released")
            }
            attemptTimes += now()
            val call = answer(attemptTimes.size).also { blockedMs += it.tookMs }
            return ProviderCall(call.value?.toRows(), call.tookMs, call.viaClient, call.clientDied)
        }

        override fun releaseClient(reason: String) {
            releases += reason
        }
    }

    private val log = TunnelLog(mirrorToLogcat = false)
    private val oneDeck = listOf(DeckRow(1, "Default", "[0,0,0]", "false"))
    private val probeEntered = CountDownLatch(1)
    private val probeRelease = CountDownLatch(1)

    @After
    fun releaseProbe() {
        probeRelease.countDown()
    }

    /** Set on a thread whose provider call hangs inside AnkiDroid; counted down once it is in. */
    private val hungAttempt = ThreadLocal<CountDownLatch?>()

    /**
     * Runs the provider calls numbered in [run], sends call [probeCall], the freeze probe, to a
     * thread where it enters AnkiDroid and hangs, and does the same with every other call.
     */
    private fun hangingProbe(probeCall: Int, run: Set<Int> = emptySet()): Executor {
        var calls = 0
        return Executor { r ->
            when (++calls) {
                in run -> r.run()
                probeCall -> {
                    Thread(r).apply { isDaemon = true }.start()
                    probeEntered.await()
                }
                else -> {
                    val entered = CountDownLatch(1)
                    Thread {
                        hungAttempt.set(entered)
                        r.run()
                    }.apply { isDaemon = true }.start()
                    entered.await()
                }
            }
        }
    }

    private fun nullIn(ms: Long, viaClient: Boolean = true, clientDied: Boolean = false) =
        ProviderCall<List<DeckRow>>(null, ms, viaClient, clientDied)

    private fun decks(ms: Long = 0) = ProviderCall(oneDeck, ms, viaClient = true, clientDied = false)

    /** Time spent blocked outside the coroutine (queue wait, provider calls), added to the virtual clock. */
    private var blockedMs = 0L

    private fun TestScope.repository(
        provider: FakeProvider,
        availability: AnkiDroidAvailability = FakeAvailability(),
        executor: Executor = Executor { it.run() },
        timeoutMs: Long = AnkiDroidRepository.DEFAULT_TIMEOUT_MS,
        freezingOemName: String? = COLOR_OS,
    ): AnkiDroidRepository {
        val clock = ElapsedClock { currentTime + blockedMs }
        provider.now = clock::elapsedMs
        return AnkiDroidRepository(
            provider = provider,
            availability = availability,
            providerExecutor = executor,
            log = log,
            clock = clock,
            timeoutMs = timeoutMs,
            freezingOemName = freezingOemName,
        )
    }

    /** Each provider call waits [ms] of clock time before it runs (queue wait, retry sleeps in the pin). */
    private fun waits(ms: (call: Int) -> Long): Executor {
        var calls = 0
        return Executor { r ->
            blockedMs += ms(++calls)
            r.run()
        }
    }

    private fun messages(level: LogEntry.Level? = null) =
        log.entries.value.filter { level == null || it.level == level }.map { it.message }

    private val frozen = AnkiResult.Frozen(COLOR_OS)

    private val noAnswer =
        AnkiResult.Unavailable("AnkiDroid did not answer the deck query. Open AnkiDroid on the phone once, then ask again.")

    @Test
    fun `the default schedule is four attempts over about 2 s`() {
        assertEquals(listOf(250L, 750L, 1_000L), AnkiDroidRepository.NULL_RETRY_DELAYS_MS)
        assertTrue(AnkiDroidRepository.NULL_RETRY_DELAYS_MS.sum() <= AnkiDroidRepository.NULL_RETRY_BUDGET_MS)
        assertTrue(AnkiDroidRepository.NULL_RETRY_BUDGET_MS <= 3_000)
    }

    @Test
    fun `fast nulls every time are a frozen AnkiDroid, summed up in one line`() = runTest {
        val tookMs = listOf(13L, 9L, 11L, 12L)
        val provider = FakeProvider { attempt -> nullIn(tookMs[attempt - 1], viaClient = attempt <= 2) }

        val result = repository(provider).listDecks()

        assertEquals(frozen, result)
        assertEquals(
            "AnkiDroid is running, but the phone has paused it in the background (Realme, Oppo and OnePlus " +
                "phones do this to apps you aren't using), so it can't answer right now. Open AnkiDroid on the " +
                "phone, then ask again. No setting reliably stops this on these phones.",
            (result as AnkiResult.Frozen).message,
        )
        assertEquals(listOf(0L, 263L, 1_022L, 2_033L), provider.attemptTimes)
        assertEquals(
            listOf("AnkiDroid null cursor x4 at [0, 263, 1022, 2033] ms (took [13c, 9c, 11r, 12r] ms), gave up"),
            messages(LogEntry.Level.WARN),
        )
        assertEquals("one log entry per call", 1, messages().size)
    }

    @Test
    fun `slow nulls are reported as no answer, not as frozen`() = runTest {
        val provider = FakeProvider { nullIn(120) }

        val result = repository(provider).listDecks()

        assertEquals(noAnswer, result)
        assertEquals(listOf(0L, 370L, 1_240L, 2_360L), provider.attemptTimes)
        assertEquals("AnkiDroid null cursor x4 at [0, 370, 1240, 2360] ms (took [120c, 120c, 120c, 120c] ms), gave up", messages().last())
    }

    @Test
    fun `one slow null among fast ones is not the frozen signature`() = runTest {
        val provider = FakeProvider { attempt -> nullIn(if (attempt == 2) 100 else 5) }

        val result = repository(provider).listDecks()

        assertTrue(result.toString(), result is AnkiResult.Unavailable)
        assertEquals(4, provider.attemptTimes.size)
    }

    @Test
    fun `time waiting for a provider thread does not make a fast null slow`() = runTest {
        val provider = FakeProvider { nullIn(13) }

        val result = repository(provider, executor = waits { 500 }).listDecks()

        assertEquals(frozen, result)
        assertEquals(3, provider.attemptTimes.size)
    }

    @Test
    fun `a dead held client whose fallback is a fast null is a frozen AnkiDroid`() = runTest {
        // The pin's same-client retries sleep 1.3 s before the fallback; only the fallback is timed.
        val provider = FakeProvider { nullIn(13, viaClient = false, clientDied = true) }

        val result = repository(provider, executor = waits { 1_300 }).listDecks()

        assertEquals(frozen, result)
        assertEquals(
            listOf("AnkiDroid null cursor x2 at [0, 1563] ms (took [13r, 13r] ms), client died on attempts [1, 2], gave up"),
            messages(LogEntry.Level.WARN),
        )
    }

    @Test
    fun `a thaw during the retries is logged as recovered`() = runTest {
        val provider = FakeProvider { attempt -> if (attempt < 4) nullIn(0) else decks() }

        val result = repository(provider).listDecks()

        assertEquals(1, (result as AnkiResult.Success).value.size)
        assertEquals(listOf(0L, 250L, 1_000L, 2_000L), provider.attemptTimes)
        assertEquals(
            listOf("AnkiDroid null cursor x3 at [0, 250, 1000] ms (took [0c, 0c, 0c] ms), recovered on attempt 4 after 2000 ms"),
            messages(LogEntry.Level.INFO),
        )
    }

    @Test
    fun `an error after nulls is logged as not recovered`() = runTest {
        val provider = FakeProvider { attempt -> if (attempt < 3) nullIn(0) else throw IllegalStateException("boom") }

        assertTrue(repository(provider).listDecks() is AnkiResult.Unavailable)
        assertEquals(
            listOf("AnkiDroid null cursor x2 at [0, 250] ms (took [0c, 0c] ms), then attempt 3 failed after 1000 ms"),
            messages(LogEntry.Level.WARN),
        )
    }

    @Test
    fun `a retry cut off by the overall timeout is logged`() = runTest {
        val provider = FakeProvider { nullIn(0) }

        assertEquals(AnkiResult.NotResponding(900), repository(provider, timeoutMs = 900).listDecks())
        assertEquals(listOf(0L, 250L), provider.attemptTimes)
        assertEquals(
            listOf("AnkiDroid null cursor x2 at [0, 250] ms (took [0c, 0c] ms), then timed out after 900 ms"),
            messages(LogEntry.Level.WARN),
        )
    }

    @Test
    fun `a first-time answer logs nothing`() = runTest {
        val provider = FakeProvider { decks() }

        assertTrue(repository(provider).listDecks() is AnkiResult.Success)
        assertEquals(1, provider.attemptTimes.size)
        assertTrue(messages().toString(), messages().isEmpty())
    }

    @Test
    fun `a call stuck past the overall timeout is logged as a timeout`() = runTest {
        val provider = FakeProvider { decks() }

        val result = repository(provider, executor = {}, timeoutMs = 1_000).listDecks()

        assertEquals(AnkiResult.NotResponding(1_000), result)
        assertEquals(listOf("AnkiDroid query timed out after 1000 ms"), messages(LogEntry.Level.WARN))
    }

    @Test
    fun `a hung first attempt whose probe also hangs is a frozen AnkiDroid`() = runTest {
        val provider = FakeProvider { decks() }.apply { clientHeld = true }

        val result = repository(provider, executor = hangingProbe(probeCall = 2)).listDecks()

        assertEquals(frozen, result)
        assertEquals(AnkiDroidRepository.ATTEMPT_TIMEOUT_MS + AnkiDroidRepository.PROBE_TIMEOUT_MS, currentTime)
        assertEquals(
            listOf("AnkiDroid query hung for 2500 ms and a probe got no answer within 1000 ms"),
            messages(LogEntry.Level.WARN),
        )
    }

    @Test
    fun `an attempt that hangs after fast nulls ends the call as frozen`() = runTest {
        val provider = FakeProvider { nullIn(0) }.apply { clientHeld = true }
        val result = repository(provider, executor = hangingProbe(probeCall = 4, run = setOf(1, 2))).listDecks()

        assertEquals(frozen, result)
        assertEquals(listOf(0L, 250L), provider.attemptTimes)
        assertEquals(
            listOf(
                "AnkiDroid null cursor x2 at [0, 250] ms (took [0c, 0c] ms), then attempt 3 hung for 2500 ms " +
                    "and a probe got no answer within 1000 ms",
            ),
            messages(LogEntry.Level.WARN),
        )
    }

    @Test
    fun `a slow attempt through the resolver is not cut off by the attempt timeout`() = runTest {
        val provider = FakeProvider { decks() }
        val answer = CompletableDeferred<Runnable>()
        val repository = repository(provider, executor = { r -> answer.complete(r) })

        val call = async { repository.listDecks() }
        launch {
            val r = answer.await()
            delay(4_000)
            r.run()
        }

        assertTrue(call.await().toString(), call.await() is AnkiResult.Success)
        assertEquals(4_000L, currentTime)
        assertTrue(messages().toString(), messages().isEmpty())
    }

    @Test
    fun `off a freezing OEM a slow held-client attempt is not cut off`() = runTest {
        val provider = FakeProvider { decks() }.apply { clientHeld = true }
        val answer = CompletableDeferred<Runnable>()
        val repository = repository(provider, executor = { r -> answer.complete(r) }, freezingOemName = null)

        val call = async { repository.listDecks() }
        launch {
            val r = answer.await()
            delay(4_000)
            r.run()
        }

        assertTrue(call.await().toString(), call.await() is AnkiResult.Success)
        assertEquals(4_000L, currentTime)
        assertTrue(messages().toString(), messages().isEmpty())
    }

    @Test
    fun `off a freezing OEM fast nulls are no answer, not frozen`() = runTest {
        val provider = FakeProvider { nullIn(13) }

        val result = repository(provider, freezingOemName = null).listDecks()

        assertEquals(noAnswer, result)
        assertEquals(4, provider.attemptTimes.size)
    }

    @Test
    fun `off a freezing OEM slow nulls are no answer`() = runTest {
        val provider = FakeProvider { nullIn(120) }

        assertEquals(noAnswer, repository(provider, freezingOemName = null).listDecks())
    }

    @Test
    fun `slow failed attempts stop the retries at the budget`() = runTest {
        val provider = FakeProvider { nullIn(1_500) }

        val result = repository(provider).listDecks()

        assertTrue(result is AnkiResult.Unavailable)
        // 1500; +250 -> starts at 1750, ends at 3250; then 3250 + 750 > 2500: give up.
        assertEquals(listOf(0L, 1_750L), provider.attemptTimes)
        assertEquals("AnkiDroid null cursor x2 at [0, 1750] ms (took [1500c, 1500c] ms), gave up", messages().last())
    }

    @Test
    fun `releasing the provider client is passed to the provider`() = runTest {
        val provider = FakeProvider { decks() }

        repository(provider).releaseProviderClient("sign-out")

        assertEquals(listOf("sign-out"), provider.releases)
    }

    private companion object {
        const val COLOR_OS = "Realme, Oppo and OnePlus"
    }
}
