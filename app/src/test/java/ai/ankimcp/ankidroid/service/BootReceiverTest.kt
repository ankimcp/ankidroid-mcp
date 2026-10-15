package ai.ankimcp.ankidroid.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BootReceiverTest {
    private lateinit var ioThread: Thread
    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-io").also { ioThread = it } }
    private val io = ioExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob())
    private val finishes = AtomicInteger()

    @After
    fun tearDown() {
        ioExecutor.shutdownNow()
    }

    @Test
    fun `credentials are read off the receiving thread and the broadcast is finished`() = runBlocking {
        val caller = Thread.currentThread()
        var readOn: Thread? = null
        var startedOn: Thread? = null

        BootReceiver.startInBackground(
            scope = scope,
            io = io,
            shouldStart = {
                readOn = Thread.currentThread()
                true
            },
            start = { startedOn = Thread.currentThread() },
            finish = { finishes.incrementAndGet() },
        ).join()

        assertNotSame(caller, readOn)
        assertSame(ioThread, readOn)
        assertSame(ioThread, startedOn)
        assertEquals(1, finishes.get())
    }

    @Test
    fun `a refused service start still finishes the broadcast`() = runBlocking {
        BootReceiver.startInBackground(
            scope = scope,
            io = io,
            shouldStart = { true },
            start = { throw IllegalStateException("not allowed") },
            finish = { finishes.incrementAndGet() },
        ).join()

        assertEquals(1, finishes.get())
    }

    @Test
    fun `nothing is started when keep connected is off or signed out`() = runBlocking {
        var starts = 0

        BootReceiver.startInBackground(
            scope = scope,
            io = io,
            shouldStart = { false },
            start = { starts++ },
            finish = { finishes.incrementAndGet() },
        ).join()

        assertEquals(0, starts)
        assertEquals(1, finishes.get())
    }
}
