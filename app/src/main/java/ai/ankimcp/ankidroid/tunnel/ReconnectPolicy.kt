package ai.ankimcp.ankidroid.tunnel

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** What to do after the socket closed with a given code. */
enum class CloseDecision {
    /** Permanent: 1000, 4002, 4004, 4005, 4010, 4011. */
    STOP,

    /** 4001 / 4003: refresh the access token, then reconnect. */
    REFRESH_TOKEN_THEN_RECONNECT,

    /** Everything else, including unknown codes. */
    RECONNECT,
}

/**
 * Backoff and close-code policy, identical to `tunnel/reconnect.py`:
 * `delay = min(initial * 2^attempt + U(0, jitter * initial * 2^attempt), max)`,
 * at most [maxAttempts] consecutive failures before giving up.
 */
class ReconnectPolicy(
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
    val maxAttempts: Int = 10,
    val jitterFactor: Double = 0.3,
    private val random: Random = Random.Default,
) {
    fun delayFor(attempt: Int): Long {
        val base = initialDelayMs * 2.0.pow(attempt.coerceAtLeast(0))
        val jitter = if (jitterFactor > 0) random.nextDouble(0.0, jitterFactor * base) else 0.0
        return min(base + jitter, maxDelayMs.toDouble()).toLong()
    }

    fun isExhausted(attempt: Int): Boolean = attempt >= maxAttempts

    fun decide(closeCode: Int): CloseDecision = when {
        !CloseCodes.shouldReconnect(closeCode) -> CloseDecision.STOP
        CloseCodes.shouldRefreshToken(closeCode) -> CloseDecision.REFRESH_TOKEN_THEN_RECONNECT
        else -> CloseDecision.RECONNECT
    }
}
