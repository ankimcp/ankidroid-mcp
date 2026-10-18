package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiProvider
import ai.ankimcp.ankidroid.anki.AnkiDroidVersion
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.DeckRow
import ai.ankimcp.ankidroid.anki.FakeAvailability
import ai.ankimcp.ankidroid.anki.ProviderCall
import ai.ankimcp.ankidroid.anki.ProviderRow
import ai.ankimcp.ankidroid.anki.toRows
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ListDecksToolTest {
    /** Every call reports [tookMs] as the time inside the query, through the resolver. */
    private class FakeProvider(private val tookMs: Long, private val block: () -> List<DeckRow>?) : AnkiProvider {
        override fun query(
            path: String,
            projection: List<String>?,
            selection: String?,
            selectionArgs: List<String>?,
            sortOrder: String?,
        ): ProviderCall<List<ProviderRow>> = ProviderCall(block()?.toRows(), tookMs, viaClient = false, clientDied = false)
    }

    private val executors = mutableListOf<ThreadPoolExecutor>()
    private val release = CountDownLatch(1)

    /**
     * Virtual clock for the retry budget and log lines. It stands still: these tests use real
     * provider threads and short explicit retry delays, so only the delay list limits retries.
     */
    private val clock = ElapsedClock { 0L }

    @After
    fun tearDown() {
        release.countDown()
        executors.forEach { it.shutdownNow() }
    }

    private fun repository(
        availability: AnkiDroidAvailability = FakeAvailability(),
        timeoutMs: Long = 5_000,
        maxThreads: Int = AnkiDroidRepository.MAX_PROVIDER_THREADS,
        queueCapacity: Int = AnkiDroidRepository.PROVIDER_QUEUE_CAPACITY,
        nullRetryDelaysMs: List<Long> = listOf(1L, 1L),
        tookMs: Long = 0,
        freezingOemName: String? = null,
        provider: () -> List<DeckRow>?,
    ): AnkiDroidRepository {
        val executor = AnkiDroidRepository.newProviderExecutor(maxThreads, queueCapacity).also { executors += it }
        return AnkiDroidRepository(
            FakeProvider(tookMs, provider),
            availability,
            executor,
            TunnelLog(mirrorToLogcat = false),
            clock,
            timeoutMs,
            nullRetryDelaysMs,
            freezingOemName = freezingOemName,
        )
    }

    private fun tool(
        availability: AnkiDroidAvailability = FakeAvailability(),
        timeoutMs: Long = 5_000,
        tookMs: Long = 0,
        freezingOemName: String? = null,
        provider: () -> List<DeckRow>?,
    ) = ListDecksTool(repository(availability, timeoutMs, tookMs = tookMs, freezingOemName = freezingOemName, provider = provider))

    private fun callTool(tool: ListDecksTool, includeStats: Boolean? = null) = runBlocking {
        tool.call(JsonObject(includeStats?.let { mapOf("include_stats" to JsonPrimitive(it)) } ?: emptyMap()))
    }

    private fun errorText(tool: ListDecksTool): String {
        val result = callTool(tool)
        assertTrue(result.isError)
        return result.content[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    /** Blocks the calling (provider) thread until the test ends, like a binder call that never returns. */
    private fun hang(): Nothing {
        release.await()
        throw IllegalStateException("released")
    }

    private val threeDecks = listOf(
        DeckRow(1, "Default", "[1,2,3]", "false"),
        DeckRow(1700000000000, "Japanese::Kanji", "[4, 5, 6]", "false"),
        DeckRow(1700000000001, "Filtered Deck 1", "[0,7,0]", "true"),
    )

    @Test
    fun `include_stats maps deck rows to the add-on's keys, reordering DECK_COUNTS learn-review-new`() {
        val result = callTool(tool { threeDecks }, includeStats = true)

        assertFalse(result.isError)
        val structured = result.structuredContent!!
        assertEquals(3, structured["total"]!!.jsonPrimitive.int)
        val decks = structured["decks"]!!.jsonArray.map { it.jsonObject }
        val default = decks.first { it["name"]!!.jsonPrimitive.content == "Default" }
        assertEquals(1L, default["deck_id"]!!.jsonPrimitive.long)
        val stats = default["stats"]!!.jsonObject
        assertEquals(setOf("new_count", "learn_count", "review_count"), stats.keys)
        assertEquals(3, stats["new_count"]!!.jsonPrimitive.int)
        assertEquals(1, stats["learn_count"]!!.jsonPrimitive.int)
        assertEquals(2, stats["review_count"]!!.jsonPrimitive.int)
        assertFalse(default["is_filtered"]!!.jsonPrimitive.boolean)
        assertTrue(decks.first { it["deck_id"]!!.jsonPrimitive.long == 1700000000001 }["is_filtered"]!!.jsonPrimitive.boolean)
        assertEquals(1700000000000, decks.first { it["name"]!!.jsonPrimitive.content == "Japanese::Kanji" }["deck_id"]!!.jsonPrimitive.long)
        val summary = structured["summary"]!!.jsonObject
        assertEquals(setOf("new_cards", "learning_cards", "review_cards"), summary.keys)
        assertEquals(9, summary["new_cards"]!!.jsonPrimitive.int)
        assertEquals(5, summary["learning_cards"]!!.jsonPrimitive.int)
        assertEquals(14, summary["review_cards"]!!.jsonPrimitive.int)
    }

    @Test
    fun `without include_stats there are no stats and no summary`() {
        val result = callTool(tool { threeDecks })

        val structured = result.structuredContent!!
        assertEquals(setOf("decks", "total"), structured.keys)
        structured["decks"]!!.jsonArray.forEach { assertEquals(setOf("deck_id", "name", "is_filtered"), it.jsonObject.keys) }
    }

    @Test
    fun `works below AnkiDroid 2_24 and rejects unknown arguments`() {
        val old = FakeAvailability(version = AnkiDroidVersion(22303300, "2.23.3"))
        assertFalse(callTool(tool(old) { threeDecks }).isError)

        val result = runBlocking { tool { threeDecks }.call(JsonObject(mapOf("verbose" to JsonPrimitive(true)))) }
        assertTrue(result.isError)
        assertTrue(result.content.toString(), result.content.toString().contains("Unknown argument(s) for list_decks: verbose"))
    }

    @Test
    fun `declares an output schema matching the structured result`() {
        val schema = tool { emptyList() }.outputSchema
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("decks", "total"), required)
        val deckProps = schema["properties"]!!.jsonObject["decks"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(setOf("deck_id", "name", "is_filtered", "stats"), deckProps.keys)
    }

    @Test
    fun `skips the root node and omits unparseable counts`() {
        val result = callTool(
            tool { listOf(DeckRow(0, "", "[0,0,0]", null), DeckRow(5, "A", "garbage", null)) },
            includeStats = true,
        )
        val decks = result.structuredContent!!["decks"]!!.jsonArray
        assertEquals(1, decks.size)
        assertNull(decks[0].jsonObject["stats"])
    }

    @Test
    fun `empty collection returns a message`() {
        val result = callTool(tool { emptyList() })
        assertFalse(result.isError)
        assertEquals(0, result.structuredContent!!["total"]!!.jsonPrimitive.int)
        assertTrue(result.structuredContent!!.containsKey("message"))
    }

    @Test
    fun `AnkiDroid not installed is a tool error`() {
        val text = errorText(tool(FakeAvailability(installed = false)) { error("must not be called") })
        assertTrue(text, text.contains("not installed"))
    }

    @Test
    fun `missing permission is a tool error`() {
        val text = errorText(tool(FakeAvailability(permission = false)) { error("must not be called") })
        assertTrue(text, text.contains("permission"))
    }

    @Test
    fun `SecurityException from the provider is a permission error`() {
        val text = errorText(tool { throw SecurityException("Permission not granted for: query") })
        assertTrue(text, text.contains("permission"))
    }

    @Test
    fun `IllegalStateException about storage means AnkiDroid is not configured`() {
        val text = errorText(tool { throw IllegalStateException("AnkiDroid storage is not configured") })
        assertTrue(text, text.contains("not ready"))
    }

    @Test
    fun `other IllegalStateExceptions are generic provider errors`() {
        val text = errorText(tool { throw IllegalStateException("Cursor window allocation failed") })
        assertFalse(text, text.contains("not ready"))
        assertTrue(text, text.contains("AnkiDroid returned an error: Cursor window allocation failed"))
    }

    @Test
    fun `fast null cursors every time are a frozen AnkiDroid after three attempts`() {
        val calls = AtomicInteger()
        val text = errorText(
            tool(tookMs = 13, freezingOemName = "Xiaomi, Redmi and Poco") {
                calls.incrementAndGet()
                null
            },
        )
        assertEquals(3, calls.get())
        assertEquals(AnkiResult.Frozen("Xiaomi, Redmi and Poco").message, text)
        assertTrue(text, text.contains("(Xiaomi, Redmi and Poco phones do this"))
        assertFalse(text, text.contains("Allow background activity"))
    }

    @Test
    fun `slow null cursors every time are no answer, not frozen`() {
        val text = errorText(tool(tookMs = AnkiDroidRepository.FAST_NULL_MS) { null })
        assertTrue(text, text.startsWith("AnkiDroid did not answer the deck query"))
    }

    @Test
    fun `null cursor from a frozen AnkiDroid is retried until data arrives`() = runBlocking {
        val calls = AtomicInteger()
        val repo = repository {
            if (calls.incrementAndGet() < 3) null else listOf(DeckRow(1, "Default", "[0,0,0]", "false"))
        }

        val result = repo.listDecks()

        assertEquals(3, calls.get())
        assertEquals(1, (result as AnkiResult.Success).value.size)
    }

    @Test
    fun `null cursor retries stay inside the timeout budget`() = runBlocking {
        val calls = AtomicInteger()
        val repo = repository(timeoutMs = 300, nullRetryDelaysMs = listOf(200L, 200L)) {
            calls.incrementAndGet()
            null
        }

        assertEquals(AnkiResult.NotResponding(300), repo.listDecks())
        assertEquals(2, calls.get())
    }

    @Test
    fun `slow provider times out as not responding`() {
        val text = errorText(
            tool(timeoutMs = 100) {
                Thread.sleep(2_000)
                emptyList()
            },
        )
        assertTrue(text, text.contains("AnkiDroid is not responding"))
        assertTrue(text, text.contains("Restart AnkiDroid"))
    }

    @Test
    fun `a stuck call does not block the next one`() = runBlocking {
        val calls = AtomicInteger()
        val repo = repository(timeoutMs = 200, maxThreads = 2) {
            if (calls.incrementAndGet() == 1) hang() else listOf(DeckRow(1, "Default", "[0,0,0]", "false"))
        }

        val first = repo.listDecks()
        val second = repo.listDecks()

        assertEquals(AnkiResult.NotResponding(200), first)
        assertEquals(1, (second as AnkiResult.Success).value.size)
    }

    @Test
    fun `a burst of five calls is queued, not rejected`() = runBlocking {
        val repo = repository {
            Thread.sleep(100)
            listOf(DeckRow(1, "Default", "[0,0,0]", "false"))
        }

        val results = List(5) { async(start = CoroutineStart.UNDISPATCHED) { repo.listDecks() } }.awaitAll()

        assertTrue(results.toString(), results.all { it is AnkiResult.Success })
    }

    @Test
    fun `a call queued behind stuck threads times out as not responding`() = runBlocking {
        val repo = repository(timeoutMs = 200, maxThreads = 1) { hang() }

        val stuck = async(start = CoroutineStart.UNDISPATCHED) { repo.listDecks() }
        val queued = repo.listDecks()

        assertEquals(AnkiResult.NotResponding(200), queued)
        assertEquals(AnkiResult.NotResponding(200), stuck.await())
    }

    @Test
    fun `a full queue returns busy immediately`() = runBlocking {
        val repo = repository(timeoutMs = 2_000, maxThreads = 1, queueCapacity = 1) { hang() }

        // One call occupies the only thread, one waits in the queue.
        val running = async(start = CoroutineStart.UNDISPATCHED) { repo.listDecks() }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { repo.listDecks() }
        val start = System.nanoTime()
        val third = repo.listDecks()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(AnkiResult.Busy, third)
        assertTrue((third as AnkiResult.Failure).message, third.message.contains("Try again"))
        assertFalse(third.message, third.message.contains("not responding"))
        assertTrue("took $elapsedMs ms", elapsedMs < 150)
        running.cancel()
        waiting.cancel()
    }

    @Test
    fun `idle provider threads are not kept around`() {
        val executor = AnkiDroidRepository.newProviderExecutor().also { executors += it }
        assertEquals(AnkiDroidRepository.MAX_PROVIDER_THREADS, executor.corePoolSize)
        assertEquals(AnkiDroidRepository.MAX_PROVIDER_THREADS, executor.maximumPoolSize)
        assertTrue(executor.allowsCoreThreadTimeOut())
        assertEquals(60L, executor.getKeepAliveTime(TimeUnit.SECONDS))
        assertEquals(AnkiDroidRepository.PROVIDER_QUEUE_CAPACITY, executor.queue.remainingCapacity())
    }

    @Test
    fun `parseCounts handles malformed input`() {
        assertNull(AnkiDroidRepository.parseCounts("[1,2]"))
        assertNull(AnkiDroidRepository.parseCounts("[1,\"x\",3]"))
        assertNull(AnkiDroidRepository.parseCounts("{}"))
        assertEquals(3L, AnkiDroidRepository.parseCounts("[1,2,3]")!!.new)
    }
}
