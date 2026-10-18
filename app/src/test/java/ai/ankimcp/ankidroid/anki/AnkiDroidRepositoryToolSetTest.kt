package ai.ankimcp.ankidroid.anki

import ai.ankimcp.ankidroid.log.LogEntry
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Version gate, row mapping, the empty note search (D57) and the write path, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnkiDroidRepositoryToolSetTest {
    private val log = TunnelLog(mirrorToLogcat = false)
    private val sameThread = Executor { it.run() }

    private fun TestScope.repository(
        provider: AnkiProvider,
        availability: AnkiDroidAvailability = FakeAvailability(),
        executor: Executor = sameThread,
        freezingOemName: String? = "Realme, Oppo and OnePlus",
        poolSize: Int = AnkiDroidRepository.MAX_PROVIDER_THREADS,
    ) = AnkiDroidRepository(
        provider = provider,
        availability = availability,
        providerExecutor = executor,
        log = log,
        clock = ElapsedClock { currentTime },
        timeoutMs = 20_000,
        freezingOemName = freezingOemName,
        poolSize = poolSize,
    )

    private val cardRow: ProviderRow = mapOf(FlashCardsContract.Card._ID to 5L)

    @Test
    fun `versions drop the ABI prefix and compare by code`() {
        val arm64 = AnkiDroidVersion.of(322503300, "2.25.3")
        assertEquals(22503300, arm64.code)
        assertTrue(arm64.atLeast(RequiredVersion.TOOL_SET))
        assertTrue(arm64.atLeast(RequiredVersion.CARD_FLAGS))
        val v24 = AnkiDroidVersion.of(22400300, "2.24.0")
        assertTrue(v24.atLeast(RequiredVersion.TOOL_SET))
        assertFalse(v24.atLeast(RequiredVersion.CARD_FLAGS))
        assertFalse(AnkiDroidVersion.of(22400110, "2.24.0beta10").atLeast(RequiredVersion.TOOL_SET))
        assertTrue(AnkiDroidVersion.of(22600005, "2.26.0alpha5").atLeast(RequiredVersion.CARD_FLAGS))
        assertEquals("22303300", AnkiDroidVersion.of(22303300, null).name)
    }

    @Test
    fun `below 2_24 every gated read is Outdated and AnkiDroid is not asked`() = runTest {
        val provider = RecordingProvider { listOf(cardRow) }
        val repo = repository(provider, FakeAvailability(version = AnkiDroidVersion(22303300, "2.23.3")))

        val result = repo.searchCardIds("")

        assertEquals(AnkiResult.Outdated("2.23.3", "2.24"), result)
        assertEquals(
            "This needs AnkiDroid 2.24 or newer; the phone has AnkiDroid 2.23.3. Update AnkiDroid to 2.24 or newer, then try again.",
            (result as AnkiResult.Failure).message,
        )
        assertTrue(repo.listDecks() is AnkiResult.Outdated)
        assertTrue(repo.listDecks(minVersion = null) is AnkiResult.Success)
        assertEquals(1, provider.queries.size)
    }

    @Test
    fun `install and permission are checked before the version`() = runTest {
        val provider = RecordingProvider { emptyList() }
        assertEquals(AnkiResult.NotInstalled, repository(provider, FakeAvailability(installed = false, version = null)).searchCardIds(""))
        assertEquals(
            AnkiResult.PermissionDenied,
            repository(provider, FakeAvailability(permission = false, version = AnkiDroidVersion(1, "1.0"))).searchCardIds(""),
        )
    }

    @Test
    fun `a note search with no matching card is empty without a notes query`() = runTest {
        val provider = RecordingProvider { q -> if (q.path == "cards") emptyList() else null }

        val result = repository(provider).searchNotes("deck:Nothing")

        assertEquals(AnkiResult.Success(emptyList<NoteData>()), result)
        assertEquals(listOf(Query("cards", listOf("_id"), "deck:Nothing", null)), provider.queries)
        assertTrue(log.entries.value.toString(), log.entries.value.isEmpty())
    }

    @Test
    fun `a note search splits fields and tags and sorts by id`() = runTest {
        val provider = RecordingProvider { q ->
            if (q.path == "cards") {
                listOf(cardRow)
            } else {
                listOf(
                    mapOf("_id" to 9L, "mid" to 3L, "flds" to "a\u001f\u001fc", "tags" to " x  y ", "mod" to 7L),
                    mapOf("_id" to 4L, "mid" to 3L, "flds" to "", "tags" to "", "mod" to 8L),
                )
            }
        }

        val notes = (repository(provider).searchNotes("tag:x", NoteField.entries.toSet()) as AnkiResult.Success).value

        assertEquals(listOf(4L, 9L), notes.map { it.id })
        assertEquals(listOf("a", "", "c"), notes[1].fields)
        assertEquals(listOf("x", "y"), notes[1].tags)
        assertEquals(emptyList<String>(), notes[0].tags)
        assertEquals(listOf("_id", "mid", "flds", "tags", "mod"), provider.queries.last().projection)
    }

    @Test
    fun `an invalid search is Rejected by the card probe, not retried as frozen`() = runTest {
        val provider = RecordingProvider { throw IllegalArgumentException("Invalid Anki search query: \"(\"") }

        val result = repository(provider).searchNotes("(")

        assertEquals(AnkiResult.Rejected("Invalid Anki search query: \"(\""), result)
        assertEquals(1, provider.queries.size)
    }

    @Test
    fun `fast null cursors from the card probe are still a frozen AnkiDroid`() = runTest {
        val provider = RecordingProvider { null }

        val result = repository(provider).searchNotes("deck:X")

        assertEquals(AnkiResult.Frozen("Realme, Oppo and OnePlus"), result)
        assertEquals(4, provider.queries.size)
        assertTrue(provider.queries.all { it.path == "cards" })
    }

    @Test
    fun `a null notes cursor after matching cards is retried, not reported as empty`() = runTest {
        var notesCalls = 0
        val provider = RecordingProvider { q ->
            when {
                q.path == "cards" -> listOf(cardRow)
                ++notesCalls < 2 -> null
                else -> listOf(mapOf("_id" to 1L))
            }
        }

        val result = repository(provider).searchNotes("deck:X")

        assertEquals(listOf(1L), (result as AnkiResult.Success).value.map { it.id })
        assertEquals(2, notesCalls)
        assertTrue(log.entries.value.single().message.contains("recovered on attempt 2"))
    }

    @Test
    fun `card rows map typed columns and FSRS nulls`() = runTest {
        val provider = RecordingProvider {
            listOf(
                mapOf(
                    "_id" to 2L, "note_id" to 1L, "ord" to 1L, "queue" to -1L, "type" to 2L, "due" to 50L,
                    "fsrs_stability" to 3.5, "fsrs_difficulty" to null, "last_review_time_secs" to 100L,
                ),
                mapOf("_id" to 1L),
            )
        }

        val fields = setOf(
            CardField.QUEUE, CardField.NOTE_ID, CardField.ORD, CardField.TYPE, CardField.DUE,
            CardField.STABILITY, CardField.DIFFICULTY, CardField.LAST_REVIEW_SECS,
        )
        val cards = (repository(provider).searchCards("cid:1,2", fields) as AnkiResult.Success).value

        assertEquals(listOf(1L, 2L), cards.map { it.id })
        assertEquals(CardData(2, noteId = 1, ord = 1, queue = -1, type = 2, due = 50, stability = 3.5, lastReviewSecs = 100), cards[1])
        assertEquals(CardData(1), cards[0])
        assertEquals(
            listOf("_id", "note_id", "ord", "type", "queue", "due", "fsrs_stability", "fsrs_difficulty", "last_review_time_secs"),
            provider.queries.single().projection,
        )
    }

    @Test
    fun `the next scheduled card is asked for with limit 1 and the deck id`() = runTest {
        val provider = RecordingProvider { listOf(mapOf("note_id" to 7L, "ord" to 2L)) }

        val result = repository(provider).nextScheduledCard(42)

        assertEquals(AnkiResult.Success(listOf(ScheduledCard(7, 2))), result)
        assertEquals(Query("schedule", listOf("note_id", "ord"), "limit=?,deckID=?", listOf("1", "42")), provider.queries.single())
    }

    // --- writes ---

    private fun written(value: Int?, viaClient: Boolean = true) = ProviderCall(value, 3, viaClient, clientDied = false)

    @Test
    fun `a write runs exactly once and returns its value`() = runTest {
        var calls = 0
        val result = repository(RecordingProvider { null }).write("updating the note") {
            calls++
            written(1)
        }
        assertEquals(AnkiResult.Success(1), result)
        assertEquals(1, calls)
    }

    @Test
    fun `a write that started and is unanswered at the timeout is outcome unknown, with no retry`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        // The provider thread has entered the write before the caller starts waiting.
        val repo = repository(
            RecordingProvider { null },
            executor = { r ->
                Thread(r).start()
                entered.await()
            },
        )

        val result = repo.write("adding the note") {
            calls.incrementAndGet()
            entered.countDown()
            release.await()
            written(1)
        }
        release.countDown()

        assertEquals(AnkiResult.OutcomeUnknown("adding the note", "AnkiDroid did not answer within 20 seconds"), result)
        assertEquals(1, calls.get())
        assertEquals(
            listOf("AnkiDroid write (adding the note) unanswered after 20000 ms; outcome unknown"),
            log.entries.value.map { it.message },
        )
    }

    @Test
    fun `a write still queued at the timeout is never sent and is not responding`() = runTest {
        var calls = 0
        val queued = mutableListOf<Runnable>()
        val repo = repository(RecordingProvider { null }, executor = { r -> queued += r })

        val result = repo.write("adding the note") {
            calls++
            written(1)
        }
        queued.single().run()

        assertEquals(AnkiResult.NotResponding(20_000), result)
        assertEquals("the abandoned write must not run when its thread frees up", 0, calls)
        assertEquals(listOf("AnkiDroid write (adding the note) still queued after 20000 ms; not sent"), log.entries.value.map { it.message })
    }

    @Test
    fun `write failures map to rejected, permission, outcome unknown`() = runTest {
        val repo = repository(RecordingProvider { null })
        assertEquals(
            AnkiResult.Rejected("Incorrect flds argument"),
            repo.write<Int>("updating the note") { throw IllegalArgumentException("Incorrect flds argument") },
        )
        assertEquals(AnkiResult.Rejected("Not yet implemented"), repo.write<Int>("x") { throw UnsupportedOperationException("Not yet implemented") })
        assertEquals(AnkiResult.PermissionDenied, repo.write<Int>("x") { throw SecurityException("no") })
        val died = repo.write<Int>("deleting the note") { throw ProviderDiedException(RuntimeException("DeadObjectException")) }
        assertEquals(AnkiResult.OutcomeUnknown("deleting the note", "AnkiDroid stopped while handling it"), died)
        assertEquals(
            "AnkiDroid stopped while handling it, so it is unknown whether deleting the note happened. " +
                "Check AnkiDroid on the phone before repeating it.",
            (died as AnkiResult.Failure).message,
        )
        assertTrue(repo.write<Int>("x") { throw RuntimeException("Unknown binder error") } is AnkiResult.OutcomeUnknown)
    }

    @Test
    fun `a null write result is a refusal through the client and unknown through the resolver`() = runTest {
        val repo = repository(RecordingProvider { null })
        assertEquals(AnkiResult.Unavailable("AnkiDroid returned no result for adding the note."), repo.write("adding the note") { written(null) })
        assertEquals(
            AnkiResult.OutcomeUnknown("adding the note", "AnkiDroid returned no result"),
            repo.write("adding the note") { written(null, viaClient = false) },
        )
    }

    @Test
    fun `a write cancelled while queued is never sent`() = runTest {
        var calls = 0
        val queued = mutableListOf<Runnable>()
        val repo = repository(RecordingProvider { null }, executor = { r -> queued += r })

        val job = launch {
            repo.write("adding the note") {
                calls++
                written(1)
            }
        }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        queued.single().run()

        assertEquals("a cancelled queued write must not run when its thread frees up", 0, calls)
    }

    @Test
    fun `a resolver write with no provider found changed nothing and reads as frozen or unavailable`() = runTest {
        val unreachable: () -> ProviderCall<Int> = { throw ProviderUnreachableException("Unknown URI content://x") }
        assertEquals(AnkiResult.Frozen("Realme, Oppo and OnePlus"), repository(RecordingProvider { null }).write("x", call = unreachable))
        val stock = repository(RecordingProvider { null }, freezingOemName = null).write("x", call = unreachable)
        assertTrue(stock.toString(), (stock as AnkiResult.Unavailable).message.contains("nothing was changed"))
    }

    // --- per-call cap, budget, schedule lock, query errors ---

    @Test
    fun `the per-attempt cap bounds each provider call, not a whole multi-call read`() = runTest {
        val provider = object : RecordingProvider({ q -> if (q.path == "cards") listOf(cardRow) else listOf(mapOf("_id" to 1L)) }) {
            override fun isClientHeld() = true
        }
        // Each provider call takes 2 s: under the 2.5 s cap, but 4 s for the two calls of a note search.
        val slow = Executor { r ->
            launch {
                delay(2_000)
                r.run()
            }
        }

        val result = repository(provider, executor = slow).searchNotes("deck:X")

        assertEquals(listOf(1L), (result as AnkiResult.Success).value.map { it.id })
        assertEquals(4_000L, currentTime)
        assertEquals(listOf("cards", "notes"), provider.queries.map { it.path })
    }

    /** Opens when the test ends, so calls that [Hangs] left inside AnkiDroid return. */
    private val release = CountDownLatch(1)

    @org.junit.After
    fun releaseHungCalls() {
        release.countDown()
    }

    /** On a thread whose provider call waits inside AnkiDroid: counted down once in, then the latch it waits for. */
    private val heldIn = ThreadLocal<Pair<CountDownLatch, CountDownLatch>?>()

    /** A held-client provider: the freeze watch applies. */
    private fun heldProvider(answer: (Query) -> List<ProviderRow>?) = object : RecordingProvider({ q ->
        heldIn.get()?.let { (entered, answers) ->
            entered.countDown()
            answers.await()
        }
        answer(q)
    }) {
        override fun isClientHeld() = true
    }

    /** How a test executor runs one provider call. */
    private sealed interface Run

    /** Starts on a thread at once (inside AnkiDroid) and answers after [ms] of virtual time. */
    private data class Answers(val ms: Long) : Run

    /** Waits [ms] of virtual time for a free thread, then answers at once. */
    private data class Queued(val ms: Long) : Run

    /** Never gets a thread. */
    private data object NeverStarts : Run

    /** Starts on a thread and never answers. */
    private data object Hangs : Run

    /** Runs the n-th submitted provider call as `runs[n]` says; calls past the end never start. */
    private fun TestScope.calls(vararg runs: Run): Executor {
        var n = 0
        return Executor { r ->
            when (val run = runs.getOrNull(n++) ?: NeverStarts) {
                NeverStarts -> Unit
                Hangs -> onThread(r, release)
                is Queued -> launch {
                    delay(run.ms)
                    r.run()
                }
                is Answers -> {
                    val answers = CountDownLatch(1)
                    val done = onThread(r, answers)
                    launch {
                        delay(run.ms)
                        answers.countDown()
                        // Virtual time must not move on before the answer is back.
                        done.await()
                    }
                }
            }
        }
    }

    /** Starts [r] on a thread whose provider call waits for [answers]; returns once it is inside AnkiDroid. */
    private fun onThread(r: Runnable, answers: CountDownLatch): CountDownLatch {
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        Thread {
            heldIn.set(entered to answers)
            try {
                r.run()
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true }.start()
        entered.await()
        return done
    }

    @Test
    fun `a slow call whose probe answers is left to finish, not reported as frozen`() = runTest {
        val provider = heldProvider { q -> if (q.path == "decks") listOf(mapOf("deck_id" to 1L)) else listOf(cardRow) }

        val result = repository(provider, executor = calls(Answers(6_000), Answers(10))).searchCardIds("")

        assertEquals(AnkiResult.Success(listOf(5L)), result)
        assertEquals(6_000L, currentTime)
        assertEquals(listOf("cards", "decks"), provider.queries.map { it.path })
        assertEquals(listOf("deck_id"), provider.queries[1].projection)
        assertTrue(log.entries.value.single().message.contains("AnkiDroid answered a probe, so waiting"))
    }

    @Test
    fun `a probe that waited for a thread gets its second from when it starts`() = runTest {
        val provider = heldProvider { q -> if (q.path == "decks") listOf(mapOf("deck_id" to 1L)) else listOf(cardRow) }

        // The probe is queued at 2.5 s and starts at 4 s; timed from queueing it would have been frozen at 3.5 s.
        val result = repository(provider, executor = calls(Answers(6_000), Queued(1_500))).searchCardIds("")

        assertEquals(AnkiResult.Success(listOf(5L)), result)
        assertEquals(6_000L, currentTime)
        assertTrue(log.entries.value.any { it.message.contains("answered a probe (the probe first waited for a free thread)") })
    }

    @Test
    fun `an attempt queued for a thread is timed from when it starts`() = runTest {
        val provider = heldProvider { listOf(cardRow) }

        // Queued 3 s for a thread, then answered at once: AnkiDroid never had it for 2.5 s, so no probe.
        val result = repository(provider, executor = calls(Queued(3_000))).searchCardIds("")

        assertEquals(AnkiResult.Success(listOf(5L)), result)
        assertEquals(3_000L, currentTime)
        assertEquals(listOf("cards"), provider.queries.map { it.path })
    }

    @Test
    fun `when every thread is held by calls abandoned as frozen, the next call is frozen at once`() = runTest {
        val provider = heldProvider { listOf(cardRow) }
        val repo = repository(provider, executor = calls(Hangs, Hangs, NeverStarts), poolSize = 2)

        assertEquals(AnkiResult.Frozen("Realme, Oppo and OnePlus"), repo.searchCardIds(""))
        assertEquals(3_500L, currentTime)
        assertEquals(AnkiResult.Frozen("Realme, Oppo and OnePlus"), repo.searchCardIds(""))
        assertEquals(3_500L, currentTime)
        assertTrue(log.entries.value.last().message.contains("could not start: every provider thread is held"))
    }

    @Test
    fun `a slow call whose probe answers but that outlasts the budget is too slow, not frozen`() = runTest {
        val provider = heldProvider { q -> if (q.path == "decks") listOf(mapOf("deck_id" to 1L)) else listOf(cardRow) }

        val result = withContext(CallBudget(12_000)) {
            repository(provider, executor = calls(Answers(30_000), Answers(10))).searchCardIds("")
        }

        assertEquals(AnkiResult.TooSlow(12_000), result)
        assertEquals(12_000L, currentTime)
    }

    @Test
    fun `a slow call whose probe hangs is a frozen AnkiDroid at 3_5 s`() = runTest {
        val provider = heldProvider { listOf(cardRow) }

        val result = repository(provider, executor = calls(Answers(6_000), Hangs)).searchCardIds("")

        assertEquals(AnkiResult.Frozen("Realme, Oppo and OnePlus"), result)
        assertEquals(3_500L, currentTime)
    }

    @Test
    fun `a probe that never left the pool queue is a busy pool, not a frozen AnkiDroid`() = runTest {
        val provider = heldProvider { listOf(cardRow) }

        // The call answers at 6 s; its probe stays queued behind busy threads.
        val result = repository(provider, executor = calls(Answers(6_000), NeverStarts)).searchCardIds("")

        assertEquals(AnkiResult.Success(listOf(5L)), result)
        assertEquals(6_000L, currentTime)
        assertTrue(log.entries.value.any { it.message.contains("a probe is still waiting for a free thread") })
    }

    @Test
    fun `the probe for a slow schedule query does not wait for the schedule lock`() = runTest {
        val provider = heldProvider { q ->
            if (q.path == "decks") listOf(mapOf("deck_id" to 1L)) else listOf(mapOf("note_id" to 7L, "ord" to 0L))
        }

        val result = repository(provider, executor = calls(Answers(4_000), Answers(10))).nextScheduledCard(3)

        assertEquals(AnkiResult.Success(listOf(ScheduledCard(7, 0))), result)
        assertEquals(listOf("schedule", "decks"), provider.queries.map { it.path })
    }

    @Test
    fun `a second schedule query waiting behind a hung one is busy after 3 s, not frozen`() = runTest {
        val provider = heldProvider { listOf(mapOf("note_id" to 7L, "ord" to 0L)) }
        // First call: its query and its probe hang inside AnkiDroid (a frozen AnkiDroid).
        val repo = repository(provider, executor = calls(Hangs, Hangs))

        val first = async { repo.nextScheduledCard(1) }
        testScheduler.runCurrent()
        val second = repo.nextScheduledCard(2)

        assertEquals(AnkiResult.Busy, second)
        assertEquals(AnkiDroidRepository.SCHEDULE_LOCK_WAIT_MS, currentTime)
        assertEquals(AnkiResult.Frozen("Realme, Oppo and OnePlus"), first.await())
    }

    @Test
    fun `a write that finishes between the timeout and the state check returns its result`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val clockCalls = AtomicInteger()
        // The second clock reading is the one right after the timeout: let the write finish there.
        val clock = ElapsedClock {
            if (clockCalls.incrementAndGet() == 2) {
                release.countDown()
                done.await()
            }
            currentTime
        }
        val repo = AnkiDroidRepository(
            provider = RecordingProvider { null },
            availability = FakeAvailability(),
            providerExecutor = { r ->
                Thread {
                    r.run()
                    done.countDown()
                }.start()
                entered.await()
            },
            log = log,
            clock = clock,
            timeoutMs = 20_000,
        )

        val result = repo.write("adding the note") {
            entered.countDown()
            release.await()
            written(1)
        }

        assertEquals(AnkiResult.Success(1), result)
        assertTrue(log.entries.value.none { it.message.contains("outcome unknown") })
    }

    @Test
    fun `an unanswered write under a budget says how long it waited and the request's limit`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repo = repository(
            RecordingProvider { null },
            executor = { r ->
                Thread(r).start()
                entered.await()
            },
        )

        val result = withContext(CallBudget(12_000)) {
            repo.write("adding the note") {
                entered.countDown()
                release.await()
                written(1)
            }
        }
        release.countDown()

        assertEquals(
            AnkiResult.OutcomeUnknown(
                "adding the note",
                "AnkiDroid did not answer within 10 seconds, when this request's 12-second limit was down to the 2 " +
                    "seconds kept for checking the result",
            ),
            result,
        )
    }

    @Test
    fun `cardsByIds reads at most 100 cards per provider call`() = runTest {
        val provider = RecordingProvider { q ->
            q.selection!!.removePrefix("cid:").split(",").map { mapOf("_id" to it.toLong()) }
        }

        val result = repository(provider).cardsByIds((1L..250L).toList() + listOf(0L, -3L, 5L), setOf(CardField.QUEUE))

        assertEquals((1L..250L).toList(), (result as AnkiResult.Success).value.map { it.id })
        assertEquals(listOf(100, 100, 50), provider.queries.map { it.selection!!.split(",").size })
    }

    @Test
    fun `calls share one budget and the one that would run past it is too slow`() = runTest {
        var clockMs = 0L
        val provider = RecordingProvider { q ->
            clockMs += 7_000
            if (q.path == "cards") listOf(cardRow) else listOf(mapOf("_id" to 1L))
        }
        val repo = AnkiDroidRepository(provider, FakeAvailability(), sameThread, log, ElapsedClock { clockMs })

        val results = withContext(CallBudget(12_000)) {
            listOf(repo.searchCardIds(""), repo.searchCardIds(""), repo.searchCardIds(""))
        }

        assertTrue(results[0] is AnkiResult.Success)
        assertTrue(results[1] is AnkiResult.Success)
        assertEquals(AnkiResult.TooSlow(12_000), results[2])
        assertEquals(2, provider.queries.size)
        assertTrue((results[2] as AnkiResult.Failure).message.startsWith("AnkiDroid is taking too long"))
    }

    @Test
    fun `a hung call under a budget ends when the budget does, as too slow`() = runTest {
        val repo = repository(RecordingProvider { emptyList() }, executor = {}, freezingOemName = null)

        val result = withContext(CallBudget(12_000)) { repo.searchCardIds("") }

        assertEquals(AnkiResult.TooSlow(12_000), result)
        assertEquals(12_000L, currentTime)
    }

    @Test
    fun `a tool runs under a 12 s budget`() = runTest {
        val repo = repository(RecordingProvider { emptyList() }, executor = {}, freezingOemName = null)

        val result = ai.ankimcp.ankidroid.tools.ModelNamesTool(repo).call(kotlinx.serialization.json.JsonObject(emptyMap()))

        assertTrue(result.isError)
        assertEquals(AnkiResult.TooSlow(12_000).message, result.content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(12_000L, currentTime)
    }

    @Test
    fun `schedule queries never overlap`() {
        val inSchedule = AtomicInteger()
        val maxInSchedule = AtomicInteger()
        val provider = RecordingProvider { q ->
            if (q.path == "schedule") {
                maxInSchedule.accumulateAndGet(inSchedule.incrementAndGet(), ::maxOf)
                Thread.sleep(50)
                inSchedule.decrementAndGet()
            }
            listOf(mapOf("note_id" to 1L, "ord" to 0L))
        }
        val executor = AnkiDroidRepository.newProviderExecutor()
        try {
            val repo = AnkiDroidRepository(provider, FakeAvailability(), executor, log, ElapsedClock { 0L })
            kotlinx.coroutines.runBlocking {
                List(4) { i -> async(kotlinx.coroutines.Dispatchers.Default) { repo.nextScheduledCard(i.toLong()) } }
                    .map { it.await() }
                    .forEach { assertTrue(it.toString(), it is AnkiResult.Success) }
            }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, maxInSchedule.get())
        assertEquals(4, provider.queries.size)
    }

    @Test
    fun `a query error other than a dead client is a rejected query`() = runTest {
        val provider = RecordingProvider { throw RuntimeException("Unknown transaction code") }

        assertEquals(AnkiResult.Rejected("Unknown transaction code"), repository(provider).searchNotes("front:re:("))
        assertEquals(1, provider.queries.size)
    }

    @Test
    fun `a short read through the resolver is unavailable`() = runTest {
        val provider = RecordingProvider { throw ProviderDiedException(IllegalStateException("cursor delivered 2 of 3 rows")) }

        val result = repository(provider).searchCardIds("")

        assertEquals(AnkiResult.Unavailable("AnkiDroid stopped answering while sending the results. Ask again."), result)
    }

    @Test
    fun `writes are gated by version and a full pool is busy`() = runTest {
        val old = repository(RecordingProvider { null }, FakeAvailability(version = AnkiDroidVersion(22400300, "2.24.0")))
        assertEquals(AnkiResult.Outdated("2.24.0", "2.25"), old.write("flagging", RequiredVersion.CARD_FLAGS) { written(1) })
        assertEquals(AnkiResult.Success(1), old.write("x") { written(1) })

        val full = repository(RecordingProvider { null }, executor = { throw java.util.concurrent.RejectedExecutionException() })
        assertEquals(AnkiResult.Busy, full.write("x") { written(1) })
        assertTrue(log.entries.value.none { it.level == LogEntry.Level.WARN })
    }
}
