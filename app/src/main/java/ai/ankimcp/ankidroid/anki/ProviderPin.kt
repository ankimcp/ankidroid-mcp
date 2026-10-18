package ai.ankimcp.ankidroid.anki

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.ElapsedClock

/** A call through the held client found AnkiDroid's provider gone (a `RemoteException`). */
class ProviderDiedException(cause: Throwable) : Exception(cause.message ?: cause.javaClass.simpleName, cause)

/**
 * Holds one client of AnkiDroid's provider for the life of the process (D51). It is acquired on
 * the first query that finds none held, and a query through it is a direct binder call into
 * AnkiDroid rather than a provider lookup, which the OEM freezer refuses for a frozen app.
 *
 * [acquire] returns null (or throws) when AnkiDroid is not installed, the permission is missing
 * or the provider cannot be reached; the query then runs without a client and the next one tries
 * again. A client is dropped only by [release], a [SecurityException] from a query, a query that
 * still finds it dead after [deadRetryDelaysMs], or a swap for a fresh one after AnkiDroid
 * restarted.
 *
 * The one client is shared by concurrent queries. A client that is released or found dead while
 * queries still use it is only retired; the last of them closes it.
 */
class ProviderPin<C : AutoCloseable>(
    private val acquire: () -> C?,
    private val log: TunnelLog,
    private val clock: ElapsedClock,
    private val deadRetryDelaysMs: List<Long> = DEAD_RETRY_DELAYS_MS,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private class Held<C>(val client: C) {
        var users = 0
        var retired = false

        /** The fresh client that replaced this one after AnkiDroid restarted. */
        var replacedBy: Held<C>? = null
    }

    private val lock = Any()
    private var held: Held<C>? = null
    private var acquiring = false

    /** Bumped by every drop, so a client acquired across one is not kept. */
    private var generation = 0

    /** Null until the first acquire; then whether the last acquire failed, so repeats are logged once. */
    private var lastAcquireFailed: Boolean? = null

    val isHeld: Boolean get() = synchronized(lock) { held != null }

    /**
     * Drops the held client; it is closed now, or by the last query still using it. A client
     * being acquired right now is closed when it arrives.
     */
    fun release(reason: String) {
        val toClose = synchronized(lock) {
            lastAcquireFailed = null
            generation++
            val current = held ?: return
            dropLocked(current)
        }
        toClose?.let(::closeQuietly)
        log.debug("Released AnkiDroid's provider ($reason)")
    }

    /**
     * Runs [block] with the held client, acquiring one first if none is held. The client stays
     * open until [block] returns, so read the cursor inside it. After a [ProviderDiedException]
     * a fresh client is acquired first: one arriving means AnkiDroid died and restarted, so it
     * replaces the dead one (once per call); if another query already did that, its client is
     * used. Otherwise (the lookup is refused while AnkiDroid is
     * frozen) the same client is retried after each of [deadRetryDelaysMs]; if it is still dead,
     * or was released meanwhile, the client is dropped and [block] runs once more without a
     * client (a plain resolver call). Blocking: runs on a provider thread.
     *
     * With [retryDead] false (writes) [block] runs exactly once: a dead-object error drops the
     * client and is rethrown, because the change may already have been applied (D57).
     */
    fun <T> withClient(retryDead: Boolean = true, block: (C?) -> T?): ProviderCall<T> {
        ensureHeld()
        var current = synchronized(lock) { held?.also { it.users++ } } ?: return timed(null, false, block)
        try {
            val startMs = clock.elapsedMs()
            var retries = 0
            var swapped = false
            while (true) {
                try {
                    val call = timed(current.client, retries > 0 || swapped, block)
                    if (retries > 0 && !swapped) log.info("Thawed AnkiDroid via held client after ${clock.elapsedMs() - startMs} ms")
                    return call
                } catch (e: ProviderDiedException) {
                    if (!retryDead) {
                        if (dropIfHeld(current)) log.warn("AnkiDroid's provider client died during a write (${e.message})")
                        throw e
                    }
                    if (!swapped) {
                        val fresh = swapIfRestarted(current)
                        if (fresh != null) {
                            current = fresh
                            swapped = true
                            continue
                        }
                    }
                    if (isRetired(current)) return timed(null, true, block)
                    val delayMs = deadRetryDelaysMs.getOrNull(retries++)
                    if (delayMs == null) {
                        if (dropIfHeld(current)) log.warn("AnkiDroid's provider client died (${e.message}); reacquiring on the next query")
                        return timed(null, true, block)
                    }
                    sleep(delayMs)
                    if (isRetired(current)) return timed(null, true, block)
                } catch (e: SecurityException) {
                    if (dropIfHeld(current)) log.debug("Released AnkiDroid's provider (permission denied)")
                    throw e
                }
            }
        } finally {
            val toClose = synchronized(lock) {
                current.users--
                current.client.takeIf { current.retired && current.users == 0 }
            }
            toClose?.let(::closeQuietly)
        }
    }

    private inline fun <T> timed(client: C?, clientDied: Boolean, block: (C?) -> T?): ProviderCall<T> {
        val startMs = clock.elapsedMs()
        val value = block(client)
        return ProviderCall(value, clock.elapsedMs() - startMs, viaClient = client != null, clientDied = clientDied)
    }

    private fun isRetired(current: Held<C>): Boolean = synchronized(lock) { current.retired }

    /**
     * Moves this query off [old] after it died. If another query already swapped [old] for a
     * fresh client that is still held, that client is used. Otherwise a fresh client is acquired:
     * null (lookup refused, or [old] was released meanwhile) keeps [old]; a fresh client is held
     * and used instead of [old], which is retired and closed by its last user.
     */
    private fun swapIfRestarted(old: Held<C>): Held<C>? {
        val (startGeneration, alreadySwapped) = synchronized(lock) {
            if (old.retired && old.replacedBy == null) return null
            generation to (old.replacedBy != null)
        }
        val fresh = if (alreadySwapped) {
            null
        } else {
            try {
                acquire()
            } catch (_: Exception) {
                null
            }
        }
        var freshToClose = fresh
        var oldToClose: C? = null
        var swappedHere = false
        val next = synchronized(lock) {
            val target = if (fresh != null && held === old && generation == startGeneration) {
                dropLocked(old)
                lastAcquireFailed = false
                freshToClose = null
                swappedHere = true
                Held(fresh).also {
                    held = it
                    old.replacedBy = it
                }
            } else {
                old.replacedBy?.takeIf { it === held }
            } ?: return@synchronized null
            target.users++
            old.users--
            oldToClose = old.client.takeIf { old.retired && old.users == 0 }
            target
        }
        freshToClose?.let(::closeQuietly)
        oldToClose?.let(::closeQuietly)
        if (swappedHere) log.info("AnkiDroid restarted; took a new provider client")
        return next
    }

    /** Acquires a client if none is held or being acquired. Blocking. */
    private fun ensureHeld() {
        val startGeneration = synchronized(lock) {
            if (held != null || acquiring) return
            acquiring = true
            generation
        }
        var failure: Exception? = null
        var acquired: C? = null
        var kept = false
        var logLine: String? = null
        try {
            acquired = try {
                acquire()
            } catch (e: Exception) {
                failure = e
                null
            }
        } finally {
            synchronized(lock) {
                acquiring = false
                when {
                    acquired != null && generation == startGeneration && held == null -> {
                        held = Held(acquired)
                        lastAcquireFailed = false
                        kept = true
                        logLine = "Holding AnkiDroid's provider client"
                    }
                    acquired == null && lastAcquireFailed != true -> {
                        lastAcquireFailed = true
                        val why = failure?.javaClass?.simpleName ?: "no provider client"
                        logLine = "Could not acquire AnkiDroid's provider ($why); retrying on the next query"
                    }
                }
            }
        }
        if (!kept) acquired?.let(::closeQuietly)
        logLine?.let { if (kept) log.info(it) else log.warn(it) }
    }

    /** Drops [current] if it is still the held client; true if this call dropped it. */
    private fun dropIfHeld(current: Held<C>): Boolean {
        val toClose = synchronized(lock) {
            if (held !== current) return false
            dropLocked(current)
        }
        toClose?.let(::closeQuietly)
        return true
    }

    /** Marks [current] dropped; returns its client if nobody uses it, for the caller to close. */
    private fun dropLocked(current: Held<C>): C? {
        held = null
        generation++
        current.retired = true
        return current.client.takeIf { current.users == 0 }
    }

    private fun closeQuietly(c: C) {
        try {
            c.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Retries on the same client after a dead-object error, before it is dropped. */
        val DEAD_RETRY_DELAYS_MS = listOf(300L, 1_000L)
    }
}
