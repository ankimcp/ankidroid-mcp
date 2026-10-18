package ai.ankimcp.ankidroid.anki

import ai.ankimcp.ankidroid.log.LogEntry
import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch

class ProviderPinTest {
    private class FakeClient(val id: Int) : AutoCloseable {
        var closed = false
        var closeCount = 0

        /** Queries that still fail with a dead object before this client answers. */
        var deadFor = 0
        var queries = 0

        fun query(): String {
            queries++
            if (deadFor > 0) {
                deadFor--
                throw ProviderDiedException(RuntimeException("DeadObjectException"))
            }
            return "rows from $id"
        }

        override fun close() {
            closed = true
            closeCount++
        }
    }

    private val log = TunnelLog(mirrorToLogcat = false)
    private val acquired = mutableListOf<FakeClient>()

    /** False models a frozen AnkiDroid: the provider lookup is refused. */
    private var available = true
    private var onAcquire: () -> Unit = {}
    private var onSleep: () -> Unit = {}
    private var nowMs = 0L
    private val sleeps = mutableListOf<Long>()

    private val pin = ProviderPin(
        acquire = {
            onAcquire()
            if (available) FakeClient(acquired.size + 1).also { acquired += it } else null
        },
        log = log,
        clock = ElapsedClock { nowMs },
        sleep = { ms ->
            sleeps += ms
            nowMs += ms
            onSleep()
        },
    )

    private fun call(): ProviderCall<String> = pin.withClient { client -> client?.query() ?: "rows from resolver" }

    private fun query(): String? = call().value

    /** Takes the client, then freezes AnkiDroid so that lookups are refused. */
    private fun holdThenFreeze() {
        query()
        available = false
    }

    private fun messages(level: LogEntry.Level? = null) =
        log.entries.value.filter { level == null || it.level == level }.map { it.message }

    @Test
    fun `a write never retries a dead client, it drops it and rethrows`() {
        query()
        acquired[0].deadFor = 1

        try {
            pin.withClient(retryDead = false) { client -> client?.query() }
            fail("expected ProviderDiedException")
        } catch (_: ProviderDiedException) {
        }

        assertEquals("one write attempt after the first query", 2, acquired[0].queries)
        assertTrue(sleeps.isEmpty())
        assertEquals("no fresh acquire for a swap", 1, acquired.size)
        assertFalse(pin.isHeld)
        assertTrue(acquired[0].closed)
        assertEquals(
            listOf("AnkiDroid's provider client died during a write (DeadObjectException)"),
            messages(LogEntry.Level.WARN),
        )
    }

    @Test
    fun `the first query acquires the client and later queries reuse it`() {
        assertFalse(pin.isHeld)
        assertEquals("rows from 1", query())
        assertEquals("rows from 1", query())

        assertEquals(1, acquired.size)
        assertTrue(pin.isHeld)
        assertFalse(acquired[0].closed)
        assertEquals(listOf("Holding AnkiDroid's provider client"), messages(LogEntry.Level.INFO))
    }

    @Test
    fun `the call reports the query's own time and path`() {
        val call = pin.withClient { client ->
            nowMs += 42
            client?.query()
        }

        assertEquals(ProviderCall("rows from 1", tookMs = 42, viaClient = true, clientDied = false), call)
    }

    @Test
    fun `a dead-object error is retried on the same client and a thaw is logged`() {
        holdThenFreeze()
        acquired[0].deadFor = 2

        val call = call()

        assertEquals(ProviderCall("rows from 1", tookMs = 0, viaClient = true, clientDied = true), call)
        assertEquals(listOf(300L, 1_000L), sleeps)
        assertEquals(1, acquired.size)
        assertFalse(acquired[0].closed)
        assertTrue(pin.isHeld)
        assertTrue(messages().toString(), "Thawed AnkiDroid via held client after 1300 ms" in messages(LogEntry.Level.INFO))
    }

    @Test
    fun `a client still dead after the retries is dropped, the query falls back, and the next query re-acquires`() {
        holdThenFreeze()
        acquired[0].deadFor = 3

        assertEquals("rows from resolver", query())

        assertEquals("three attempts on the same client", 4, acquired[0].queries)
        assertEquals(listOf(300L, 1_000L), sleeps)
        assertTrue(acquired[0].closed)
        assertFalse(pin.isHeld)
        assertEquals(
            listOf("AnkiDroid's provider client died (DeadObjectException); reacquiring on the next query"),
            messages(LogEntry.Level.WARN),
        )
        assertFalse(messages().any { it.startsWith("Thawed") })

        available = true
        assertEquals("rows from 2", query())
        assertEquals(2, acquired.size)
    }

    @Test
    fun `a fast null from the fallback after a dead client is timed without the retry sleeps`() {
        holdThenFreeze()
        acquired[0].deadFor = 3

        val call = pin.withClient<String> { client -> client?.query() }

        assertEquals(ProviderCall<String>(null, tookMs = 0, viaClient = false, clientDied = true), call)
        assertEquals(1_300L, nowMs)
    }

    @Test
    fun `a dead client is swapped for a fresh one when AnkiDroid restarted`() {
        query()
        acquired[0].deadFor = 1

        val call = call()

        assertEquals(ProviderCall("rows from 2", tookMs = 0, viaClient = true, clientDied = true), call)
        assertTrue("no sleep before the fresh acquire", sleeps.isEmpty())
        assertEquals(1, acquired[0].closeCount)
        assertFalse(acquired[1].closed)
        assertTrue(pin.isHeld)
        assertTrue(messages().toString(), "AnkiDroid restarted; took a new provider client" in messages(LogEntry.Level.INFO))
        assertFalse(messages().any { it.startsWith("Thawed") })
        assertEquals("rows from 2", query())
        assertEquals(2, acquired.size)
    }

    @Test
    fun `a query whose dead client another query already swapped uses the new client at once`() {
        query()
        acquired[0].deadFor = 2
        var innerStarted = false
        var inner: ProviderCall<String>? = null

        val outer = pin.withClient { client ->
            if (!innerStarted) {
                innerStarted = true
                inner = call()
            }
            client?.query() ?: "rows from resolver"
        }

        assertEquals(ProviderCall("rows from 2", tookMs = 0, viaClient = true, clientDied = true), inner)
        assertEquals(ProviderCall("rows from 2", tookMs = 0, viaClient = true, clientDied = true), outer)
        assertTrue("no same-client retry sleeps", sleeps.isEmpty())
        assertEquals(2, acquired.size)
        assertEquals(1, acquired[0].closeCount)
        assertFalse(acquired[1].closed)
        assertTrue(pin.isHeld)
        assertEquals(1, messages().count { it == "AnkiDroid restarted; took a new provider client" })
    }

    @Test
    fun `a query still on a client swapped twice since falls back to the resolver`() {
        query()
        acquired[0].deadFor = 2
        var nestedRan = false
        var first: ProviderCall<String>? = null
        var second: ProviderCall<String>? = null

        val third = pin.withClient { client ->
            if (!nestedRan) {
                nestedRan = true
                first = call()
                acquired[1].deadFor = 1
                second = call()
            }
            client?.query() ?: "rows from resolver"
        }

        assertEquals(ProviderCall("rows from 2", tookMs = 0, viaClient = true, clientDied = true), first)
        assertEquals(ProviderCall("rows from 3", tookMs = 0, viaClient = true, clientDied = true), second)
        assertEquals(ProviderCall("rows from resolver", tookMs = 0, viaClient = false, clientDied = true), third)
        assertEquals(3, acquired.size)
        assertTrue("no same-client retry sleeps", sleeps.isEmpty())
        assertEquals(1, acquired[0].closeCount)
        assertEquals(1, acquired[1].closeCount)
        assertFalse(acquired[2].closed)
        assertTrue(pin.isHeld)
        assertEquals(2, messages().count { it == "AnkiDroid restarted; took a new provider client" })

        pin.release("test")
        assertTrue(acquired.all { it.closeCount == 1 })
    }

    @Test
    fun `a swapped client that dies too falls back to the retries, without another acquire`() {
        query()
        acquired[0].deadFor = 1
        onAcquire = { if (acquired.size == 1) Unit else fail("acquired a third client") }
        val swapped = pin.withClient { client ->
            if (acquired.size == 2 && acquired[1].queries == 0) acquired[1].deadFor = 3
            client?.query() ?: "rows from resolver"
        }

        assertEquals("rows from resolver", swapped.value)
        assertEquals(2, acquired.size)
        assertEquals(listOf(300L, 1_000L), sleeps)
        assertTrue(acquired.all { it.closeCount == 1 })
        assertFalse(pin.isHeld)
    }

    @Test
    fun `a release while a dead client sleeps in the retry goes straight to the resolver and closes it once`() {
        holdThenFreeze()
        acquired[0].deadFor = 3
        onSleep = { pin.release("sign-out") }

        assertEquals("rows from resolver", query())

        assertEquals("no retry on a released client", 2, acquired[0].queries)
        assertEquals(listOf(300L), sleeps)
        assertEquals(1, acquired[0].closeCount)
        assertFalse(pin.isHeld)
    }

    @Test
    fun `a release from another thread during the retry sleep closes the client once`() {
        holdThenFreeze()
        acquired[0].deadFor = 3
        val sleeping = CountDownLatch(1)
        val released = CountDownLatch(1)
        onSleep = {
            sleeping.countDown()
            released.await()
        }
        val releaser = Thread {
            sleeping.await()
            pin.release("sign-out")
            released.countDown()
        }.apply { start() }

        assertEquals("rows from resolver", query())
        releaser.join(5_000)

        assertEquals(2, acquired[0].queries)
        assertEquals(1, acquired[0].closeCount)
        assertFalse(pin.isHeld)
    }

    @Test
    fun `an ordinary exception from the query keeps the client and frees its use`() {
        query()

        try {
            pin.withClient<String> { throw IllegalStateException("Cursor window allocation failed") }
            fail("expected the IllegalStateException")
        } catch (_: IllegalStateException) {
        }

        assertTrue(pin.isHeld)
        assertFalse(acquired[0].closed)
        pin.release("test")
        assertEquals("nobody uses it any more, so the release closes it", 1, acquired[0].closeCount)
    }

    @Test
    fun `a SecurityException releases the client and is rethrown`() {
        query()
        val denied = SecurityException("Permission Denial")

        try {
            pin.withClient<String> { throw denied }
            fail("expected the SecurityException")
        } catch (e: SecurityException) {
            assertSame(denied, e)
        }

        assertTrue(acquired[0].closed)
        assertFalse(pin.isHeld)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `a SecurityException from the resolver fallback is rethrown after the dead client is closed`() {
        holdThenFreeze()
        acquired[0].deadFor = 3
        val denied = SecurityException("Permission Denial")

        try {
            pin.withClient<String> { client -> client?.query() ?: throw denied }
            fail("expected the SecurityException")
        } catch (e: SecurityException) {
            assertSame(denied, e)
        }

        assertEquals(1, acquired[0].closeCount)
        assertFalse(pin.isHeld)
    }

    @Test
    fun `release closes the client and the next query acquires a new one`() {
        query()

        pin.release("sign-out")

        assertTrue(acquired[0].closed)
        assertFalse(pin.isHeld)
        assertTrue("Released AnkiDroid's provider (sign-out)" in messages(LogEntry.Level.DEBUG))
        assertEquals("rows from 2", query())
    }

    @Test
    fun `release with nothing held logs nothing`() {
        pin.release("sign-out")
        assertTrue(messages().isEmpty())
    }

    @Test
    fun `no client (not installed, no permission, unreachable) is not an error`() {
        available = false

        assertEquals(ProviderCall("rows from resolver", tookMs = 0, viaClient = false, clientDied = false), call())
        assertEquals("rows from resolver", query())

        assertFalse(pin.isHeld)
        assertEquals("repeated failures are logged once", 1, messages().count { it.startsWith("Could not acquire") })
    }

    @Test
    fun `an acquire that throws is no client, logged with the exception class`() {
        onAcquire = { throw SecurityException("Permission Denial") }

        assertEquals("rows from resolver", query())

        assertFalse(pin.isHeld)
        assertEquals(
            listOf("Could not acquire AnkiDroid's provider (SecurityException); retrying on the next query"),
            messages(LogEntry.Level.WARN),
        )
    }

    @Test
    fun `an acquire that throws an Error does not block later acquires`() {
        onAcquire = { throw OutOfMemoryError("test") }
        try {
            query()
            fail("expected the Error")
        } catch (_: OutOfMemoryError) {
        }

        onAcquire = {}
        assertEquals("rows from 1", query())
        assertTrue(pin.isHeld)
    }

    @Test
    fun `releasing during a query closes the client only after the query is done`() {
        query()
        val result = pin.withClient { client ->
            pin.release("test")
            assertFalse("still reading the cursor", acquired[0].closed)
            client?.query()
        }

        assertEquals("rows from 1", result.value)
        assertEquals(1, acquired[0].closeCount)
        assertFalse(pin.isHeld)
    }

    @Test
    fun `a client shared by two queries is closed once, when the last one finishes`() {
        query()
        pin.withClient { _ ->
            pin.withClient { _ -> pin.release("test") }
            assertFalse("the outer query still uses it", acquired[0].closed)
        }
        assertEquals(1, acquired[0].closeCount)
    }

    @Test
    fun `a client that arrives after a release is closed at once`() {
        onAcquire = { pin.release("test") }

        assertEquals("rows from resolver", query())

        assertEquals(1, acquired.size)
        assertTrue(acquired[0].closed)
        assertFalse(pin.isHeld)
    }

    @Test
    fun `a query made from inside the acquire does not acquire again`() {
        var nested: String? = null
        onAcquire = {
            onAcquire = {}
            nested = query()
        }

        assertEquals("rows from 1", query())

        assertEquals("rows from resolver", nested)
        assertEquals(1, acquired.size)
        assertTrue(pin.isHeld)
    }
}
