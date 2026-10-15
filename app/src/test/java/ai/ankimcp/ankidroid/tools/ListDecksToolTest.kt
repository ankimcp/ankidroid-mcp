package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidAvailability
import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiProvider
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.DeckRow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
    private class FakeAvailability(var installed: Boolean = true, var permission: Boolean = true) : AnkiDroidAvailability {
        override fun isInstalled() = installed
        override fun hasPermission() = permission
    }

    private class FakeProvider(private val block: () -> List<DeckRow>?) : AnkiProvider {
        override fun queryDecks(): List<DeckRow>? = block()
    }

    private val executors = mutableListOf<ThreadPoolExecutor>()
    private val release = CountDownLatch(1)

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
        provider: () -> List<DeckRow>?,
    ): AnkiDroidRepository {
        val executor = AnkiDroidRepository.newProviderExecutor(maxThreads, queueCapacity).also { executors += it }
        return AnkiDroidRepository(FakeProvider(provider), availability, executor, timeoutMs)
    }

    private fun tool(
        availability: AnkiDroidAvailability = FakeAvailability(),
        timeoutMs: Long = 5_000,
        provider: () -> List<DeckRow>?,
    ) = ListDecksTool(repository(availability, timeoutMs, provider = provider))

    private fun callTool(tool: ListDecksTool) = runBlocking { tool.call(JsonObject(emptyMap())) }

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

    @Test
    fun `maps deck rows, reordering DECK_COUNTS learn-review-new`() {
        val result = callTool(
            tool {
                listOf(
                    DeckRow(1, "Default", "[1,2,3]", "false"),
                    DeckRow(1700000000000, "Japanese::Kanji", "[4, 5, 6]", "false"),
                    DeckRow(1700000000001, "Filtered Deck 1", "[0,7,0]", "true"),
                )
            },
        )

        assertFalse(result.isError)
        val structured = result.structuredContent!!
        assertEquals(3, structured["total"]!!.jsonPrimitive.int)
        val decks = structured["decks"]!!.jsonArray.map { it.jsonObject }
        val default = decks.first { it["name"]!!.jsonPrimitive.content == "Default" }
        assertEquals(1L, default["id"]!!.jsonPrimitive.long)
        val counts = default["counts"]!!.jsonObject
        assertEquals(3, counts["new"]!!.jsonPrimitive.int)
        assertEquals(1, counts["learn"]!!.jsonPrimitive.int)
        assertEquals(2, counts["review"]!!.jsonPrimitive.int)
        assertFalse(default["is_filtered"]!!.jsonPrimitive.boolean)
        assertTrue(decks.first { it["id"]!!.jsonPrimitive.long == 1700000000001 }["is_filtered"]!!.jsonPrimitive.boolean)
        assertEquals(1700000000000, decks.first { it["name"]!!.jsonPrimitive.content == "Japanese::Kanji" }["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun `declares an output schema matching the structured result`() {
        val schema = tool { emptyList() }.outputSchema
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("decks", "total"), required)
        val deckProps = schema["properties"]!!.jsonObject["decks"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(setOf("id", "name", "is_filtered", "counts"), deckProps.keys)
    }

    @Test
    fun `skips the root node and omits unparseable counts`() {
        val result = callTool(
            tool { listOf(DeckRow(0, "", "[0,0,0]", null), DeckRow(5, "A", "garbage", null)) },
        )
        val decks = result.structuredContent!!["decks"]!!.jsonArray
        assertEquals(1, decks.size)
        assertNull(decks[0].jsonObject["counts"])
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
    fun `null cursor is a tool error`() {
        val text = errorText(tool { null })
        assertTrue(text, text.contains("did not answer"))
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
