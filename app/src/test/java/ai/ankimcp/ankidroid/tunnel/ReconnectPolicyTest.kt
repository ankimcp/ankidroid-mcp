package ai.ankimcp.ankidroid.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {
    /** Random whose nextDouble(from, until) returns from + fraction * (until - from). */
    private class FixedRandom(private val fraction: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(from: Double, until: Double): Double = from + fraction * (until - from)
    }

    @Test
    fun `delays double from one second and cap at thirty without jitter`() {
        val policy = ReconnectPolicy(random = FixedRandom(0.0))
        val delays = (0..6).map(policy::delayFor)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `jitter adds up to thirty percent of the base delay, still capped`() {
        val policy = ReconnectPolicy(random = FixedRandom(1.0))
        assertEquals(1_300L, policy.delayFor(0))
        assertEquals(2_600L, policy.delayFor(1))
        assertEquals(20_800L, policy.delayFor(4))
        assertEquals(30_000L, policy.delayFor(5))
    }

    @Test
    fun `random delays stay within bounds`() {
        val policy = ReconnectPolicy(random = Random(42))
        repeat(1_000) { i ->
            val attempt = i % 8
            val base = 1_000L shl attempt
            val delay = policy.delayFor(attempt)
            assertTrue("attempt $attempt delay $delay", delay >= minOf(base, 30_000L))
            assertTrue("attempt $attempt delay $delay", delay <= minOf((base * 1.3).toLong(), 30_000L))
        }
    }

    @Test
    fun `gives up after ten attempts`() {
        val policy = ReconnectPolicy()
        assertFalse(policy.isExhausted(9))
        assertTrue(policy.isExhausted(10))
    }

    @Test
    fun `permanent close codes stop`() {
        val policy = ReconnectPolicy()
        listOf(1000, 4002, 4004, 4005, 4010, 4011).forEach {
            assertEquals("code $it", CloseDecision.STOP, policy.decide(it))
        }
    }

    @Test
    fun `a client kind mismatch stops`() {
        assertEquals(CloseDecision.STOP, ReconnectPolicy().decide(CloseCodes.CLIENT_KIND_MISMATCH))
    }

    @Test
    fun `auth close codes refresh the token first`() {
        val policy = ReconnectPolicy()
        listOf(4001, 4003).forEach {
            assertEquals("code $it", CloseDecision.REFRESH_TOKEN_THEN_RECONNECT, policy.decide(it))
        }
    }

    @Test
    fun `transient and unknown close codes reconnect`() {
        val policy = ReconnectPolicy()
        listOf(1001, 1006, 1011, 4006, 4008, 4009, 4999).forEach {
            assertEquals("code $it", CloseDecision.RECONNECT, policy.decide(it))
        }
    }
}
