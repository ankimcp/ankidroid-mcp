package ai.ankimcp.ankidroid.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

sealed interface RefreshOutcome {
    data class Refreshed(val credentials: Credentials) : RefreshOutcome

    /** Refresh token rejected (`invalid_grant`); stored credentials were cleared. */
    data object ReloginRequired : RefreshOutcome

    /** No stored credentials (logged out, possibly during the refresh); nothing was cleared. */
    data object SignedOut : RefreshOutcome

    /** Transient failure; the caller may keep using [previous]. */
    data class Failed(val message: String, val previous: Credentials) : RefreshOutcome
}

/** Serialises token refreshes so concurrent callers never burn the same refresh token twice. */
class TokenRefresher(
    private val store: CredentialStore,
    private val client: DeviceFlowClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    suspend fun refresh(current: Credentials): RefreshOutcome = mutex.withLock {
        // Another caller may have refreshed while we waited for the lock.
        val stored = store.load() ?: return RefreshOutcome.SignedOut
        if (stored.accessToken != current.accessToken && !stored.isExpired(clock)) {
            return RefreshOutcome.Refreshed(stored)
        }
        try {
            val fresh = client.refresh(stored.refreshToken)
            // Logout (or a new sign-in) during the HTTP call wins; never resurrect old credentials.
            if (store.replace(stored, fresh)) {
                RefreshOutcome.Refreshed(fresh)
            } else {
                currentOutcome()
            }
        } catch (e: AuthException) {
            if (e.errorCode == "invalid_grant") {
                // Same rule as above: only the credentials that were rejected get cleared.
                if (store.clearIf(stored)) RefreshOutcome.ReloginRequired else currentOutcome()
            } else {
                RefreshOutcome.Failed(e.message ?: "Token refresh failed", stored)
            }
        }
    }

    private fun currentOutcome(): RefreshOutcome =
        store.load()?.let { RefreshOutcome.Refreshed(it) } ?: RefreshOutcome.SignedOut
}
