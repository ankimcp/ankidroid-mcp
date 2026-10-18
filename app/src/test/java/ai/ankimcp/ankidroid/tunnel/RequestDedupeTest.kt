package ai.ankimcp.ankidroid.tunnel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class RequestDedupeTest {
    private val now = AtomicLong(1_000)
    private val dedupe = RequestDedupe(clock = { now.get() }, ttlMs = 120_000, maxEntries = 3)

    private fun response(id: String) = ClientMessage.Response(id, 200, emptyMap(), """{"id":1}""")

    @Test
    fun `first sighting is fresh and records the receipt time`() {
        val claim = dedupe.begin("a")

        assertTrue(claim is RequestDedupe.Claim.Fresh)
        assertEquals("a", claim.entry.requestId)
        assertEquals(1_000, claim.entry.receivedAtMs)
        assertFalse(claim.entry.result.isCompleted)
    }

    @Test
    fun `a repeat is a duplicate of the same pending entry`() {
        val fresh = dedupe.begin("a")
        val dup = dedupe.begin("a")

        assertTrue(dup is RequestDedupe.Claim.Duplicate)
        assertSame(fresh.entry, dup.entry)
        assertFalse(dup.entry.result.isCompleted)
    }

    @Test
    fun `a duplicate after completion sees the stored response`() {
        val stored = response("a")
        dedupe.begin("a").entry.complete(stored)

        val dup = dedupe.begin("a")

        assertTrue(dup is RequestDedupe.Claim.Duplicate)
        assertEquals(RequestDedupe.Outcome.Answered(stored), dup.entry.result.getCompleted())
    }

    @Test
    fun `an original cut off after it started is final and a later complete is ignored`() {
        val entry = dedupe.begin("a").entry
        entry.markStarted()
        entry.abandon()
        entry.complete(response("a"))

        val dup = dedupe.begin("a")

        assertTrue(dup is RequestDedupe.Claim.Duplicate)
        assertEquals(RequestDedupe.Outcome.CutOff, dup.entry.result.getCompleted())
    }

    @Test
    fun `abandon after a response keeps the response`() {
        val stored = response("a")
        val entry = dedupe.begin("a").entry
        entry.markStarted()
        entry.complete(stored)
        entry.abandon()

        assertEquals(RequestDedupe.Outcome.Answered(stored), entry.result.getCompleted())
    }

    @Test
    fun `an original that never started leaves the id claimable once`() {
        val original = dedupe.begin("a").entry
        original.abandon()
        assertEquals(RequestDedupe.Outcome.NeverRan, original.result.getCompleted())

        val reclaim = dedupe.begin("a")
        val waiter = dedupe.begin("a")

        assertTrue(reclaim is RequestDedupe.Claim.Fresh)
        assertNotSame(original, reclaim.entry)
        assertTrue(waiter is RequestDedupe.Claim.Duplicate)
        assertSame(reclaim.entry, waiter.entry)
        assertFalse(waiter.entry.result.isCompleted)
    }

    @Test
    fun `a reclaim cancelled before it starts is never-ran again and leaves the id claimable`() {
        dedupe.begin("a").entry.abandon()
        val reclaim = dedupe.begin("a")
        assertTrue(reclaim is RequestDedupe.Claim.Fresh)

        reclaim.entry.abandon()

        assertEquals(RequestDedupe.Outcome.NeverRan, reclaim.entry.result.getCompleted())
        val next = dedupe.begin("a")
        assertTrue(next is RequestDedupe.Claim.Fresh)
        assertNotSame(reclaim.entry, next.entry)
    }

    @Test
    fun `concurrent reclaims of a never-run id yield exactly one fresh claim`() {
        val shared = RequestDedupe(ElapsedClock.Monotonic)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(500) { round ->
                shared.begin("req-$round").entry.abandon()
                val barrier = CyclicBarrier(2)
                val claims = List(2) {
                    pool.submit<RequestDedupe.Claim> {
                        barrier.await(5, TimeUnit.SECONDS)
                        shared.begin("req-$round")
                    }
                }.map { it.get(5, TimeUnit.SECONDS) }

                assertEquals(1, claims.count { it is RequestDedupe.Claim.Fresh })
                assertSame(claims[0].entry, claims[1].entry)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `completing an entry prunes expired ones`() {
        dedupe.begin("a")
        now.addAndGet(1)
        val b = dedupe.begin("b").entry
        assertEquals(2, dedupe.size)

        now.addAndGet(120_000)
        b.complete(response("b"))

        assertEquals("a is past the ttl, b exactly at it", 1, dedupe.size)
    }

    @Test
    fun `entries expire after the ttl on the injected clock`() {
        dedupe.begin("a")
        now.addAndGet(120_000)
        assertTrue(dedupe.begin("a") is RequestDedupe.Claim.Duplicate)

        now.addAndGet(1)
        assertTrue(dedupe.begin("a") is RequestDedupe.Claim.Fresh)
    }

    @Test
    fun `over the cap the oldest entry is dropped`() {
        dedupe.begin("a")
        dedupe.begin("b")
        dedupe.begin("c")
        dedupe.begin("d")

        assertTrue(dedupe.begin("d") is RequestDedupe.Claim.Duplicate)
        assertTrue(dedupe.begin("c") is RequestDedupe.Claim.Duplicate)
        assertTrue(dedupe.begin("b") is RequestDedupe.Claim.Duplicate)
        assertTrue(dedupe.begin("a") is RequestDedupe.Claim.Fresh)
    }

    @Test
    fun `concurrent begin for one id yields exactly one fresh claim`() {
        val shared = RequestDedupe(ElapsedClock.Monotonic)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(500) { round ->
                val barrier = CyclicBarrier(2)
                val claims = List(2) {
                    pool.submit<RequestDedupe.Claim> {
                        barrier.await(5, TimeUnit.SECONDS)
                        shared.begin("req-$round")
                    }
                }.map { it.get(5, TimeUnit.SECONDS) }

                assertEquals(1, claims.count { it is RequestDedupe.Claim.Fresh })
                assertSame(claims[0].entry, claims[1].entry)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
