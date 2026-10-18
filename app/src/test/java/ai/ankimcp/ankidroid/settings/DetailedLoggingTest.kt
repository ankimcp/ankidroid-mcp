package ai.ankimcp.ankidroid.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailedLoggingTest {
    private val store = InMemoryKeyValueStore()
    private var now = 1_000_000_000L
    private var expiries = 0
    private val hour = 60 * 60 * 1000L

    private fun logging() = DetailedLogging(store, { now }, onExpired = { expiries++ })

    @Test
    fun `off by default`() {
        val logging = logging()

        assertFalse(logging.isEnabled())
        assertNull(logging.until.value)
        assertFalse(logging.expiredAtStart)
    }

    @Test
    fun `turning it on persists the end 24 h ahead`() {
        logging().setEnabled(true)

        assertEquals(now + DetailedLogging.DURATION_MS, store.getLong(DetailedLogging.KEY_UNTIL))
        val reloaded = logging()
        assertTrue(reloaded.isEnabled())
        assertEquals(now + 24 * hour, reloaded.until.value)
    }

    @Test
    fun `turning it off clears the stored end and is not an expiry`() {
        val logging = logging().apply { setEnabled(true) }
        logging.setEnabled(false)

        assertFalse(logging.isEnabled())
        assertFalse(store.contains(DetailedLogging.KEY_UNTIL))
        assertEquals(0, expiries)
    }

    @Test
    fun `switches itself off after 24 h on the next check`() {
        val logging = logging().apply { setEnabled(true) }

        now += 24 * hour - 1
        assertTrue(logging.isEnabled())
        now += 1
        assertFalse(logging.isEnabled())
        assertFalse(logging.enabled.value)
        assertFalse(store.contains(DetailedLogging.KEY_UNTIL))
        assertEquals(1, expiries)
        assertFalse("already off: no second expiry", logging.checkExpiry())
        assertEquals(1, expiries)
    }

    @Test
    fun `an app start after the 24 h finds it off without calling back`() {
        logging().setEnabled(true)
        now += 25 * hour

        val restarted = logging()

        assertFalse(restarted.enabled.value)
        assertTrue(restarted.expiredAtStart)
        assertEquals(0, expiries)
        assertFalse(store.contains(DetailedLogging.KEY_UNTIL))
    }

    @Test
    fun `a clock set far back ends it instead of extending it`() {
        val logging = logging().apply { setEnabled(true) }
        now -= 2 * hour

        assertFalse(logging.isEnabled())
    }

    /**
     * The expiry reads the clock outside the lock; [hook] runs the user's toggle exactly there,
     * i.e. after the expiry decided the period is over and before it takes the lock.
     */
    private fun raceAtExpiry(hook: (DetailedLogging) -> Unit): DetailedLogging {
        var armed = false
        lateinit var logging: DetailedLogging
        logging = DetailedLogging(store, {
            if (armed) {
                armed = false
                hook(logging)
            }
            now
        }, onExpired = { expiries++ })
        logging.setEnabled(true)
        now += 25 * hour
        armed = true
        logging.checkExpiry()
        return logging
    }

    @Test
    fun `turning it on again at the moment it expires keeps the new period`() {
        val logging = raceAtExpiry { it.setEnabled(true) }

        assertTrue(logging.enabled.value)
        assertEquals(now + DetailedLogging.DURATION_MS, logging.until.value)
        assertEquals(now + DetailedLogging.DURATION_MS, store.getLong(DetailedLogging.KEY_UNTIL))
        assertEquals("the stale expiry must not fire", 0, expiries)
    }

    @Test
    fun `turning it off at the moment it expires is a plain turn-off`() {
        val logging = raceAtExpiry { it.setEnabled(false) }

        assertFalse(logging.enabled.value)
        assertNull(logging.until.value)
        assertEquals(0, expiries)
    }

    @Test
    fun `the timer turns it off at the end without any other check`() = runTest {
        // The fake wall clock follows virtual time.
        val start = now
        val logging = DetailedLogging(store, { start + testScheduler.currentTime }, onExpired = { expiries++ })
        val job = logging.launchAutoOff(this)
        logging.setEnabled(true)
        runCurrent()

        advanceTimeBy(24 * hour - 1)
        assertTrue(logging.enabled.value)
        advanceTimeBy(2)
        assertFalse(logging.enabled.value)
        assertEquals(1, expiries)
        job.cancel()
    }

    @Test
    fun `the timer re-checks the wall clock at least every 10 min`() {
        val scope = TestScope()
        var wall = now
        val logging = DetailedLogging(store, { wall }, onExpired = { expiries++ })
        val job = logging.launchAutoOff(scope)
        logging.setEnabled(true)
        scope.runCurrent()

        // The phone slept: the wall clock jumped past the end, the coroutine clock did not.
        wall += 25 * hour
        scope.advanceTimeBy(DetailedLogging.CHECK_INTERVAL_MS + 1)

        assertFalse(logging.enabled.value)
        job.cancel()
    }
}
