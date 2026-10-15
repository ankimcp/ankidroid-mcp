package ai.ankimcp.ankidroid.push

import ai.ankimcp.ankidroid.settings.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the server last accepted, and how the last attempt went.
 *
 * [token], [registeredAtMs] and [registeredAppVersion] describe the last *successful*
 * registration (wall-clock millis); [lastResult], [lastMessage] and [lastAttemptAtMs] the latest
 * attempt.
 */
data class PushRegistrationState(
    val token: String? = null,
    val registeredAtMs: Long? = null,
    val registeredAppVersion: String? = null,
    val lastResult: LastResult? = null,
    val lastMessage: String? = null,
    val lastAttemptAtMs: Long? = null,
) {
    enum class LastResult(val key: String) {
        Registered("registered"),

        /** Failed transiently; a WorkManager retry is scheduled. */
        Retrying("retrying"),

        /**
         * Failed permanently, or the WorkManager retries gave up; retried on the next trigger
         * (sign-in, new token, app start, the daily check).
         */
        Failed("failed"),
        ;

        companion object {
            fun fromKey(key: String?): LastResult? = entries.firstOrNull { it.key == key }
        }
    }

    /** Never print the token. */
    override fun toString(): String =
        "PushRegistrationState(registeredAtMs=$registeredAtMs, registeredAppVersion=$registeredAppVersion, lastResult=$lastResult)"
}

/**
 * Removal of an FCM token that sign-out started but could not finish (offline, timed out, or a
 * registration that completed after sign-out began). [serverDelete]: `DELETE /devices/push` for
 * [token] is still owed; [localDelete]: `FirebaseMessaging.deleteToken()` is. At least one is
 * true while the record exists.
 */
data class PendingTokenDelete(
    val token: String?,
    val serverDelete: Boolean,
    val localDelete: Boolean,
) {
    /** Never print the token. */
    override fun toString(): String = "PendingTokenDelete(serverDelete=$serverDelete, localDelete=$localDelete)"
}

/**
 * Persists [PushRegistrationState] in its own SharedPreferences file, and next to it the
 * [PendingTokenDelete] record, which [clear] leaves alone (it outlives the registration).
 */
class PushRegistrationStore(private val store: KeyValueStore) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<PushRegistrationState> = _state.asStateFlow()

    private var _pendingDelete: PendingTokenDelete? = readPendingDelete()

    val pendingDelete: PendingTokenDelete? @Synchronized get() = _pendingDelete

    fun saveRegistered(token: String, atMs: Long, appVersion: String) {
        write(
            _state.value.copy(
                token = token,
                registeredAtMs = atMs,
                registeredAppVersion = appVersion,
                lastResult = PushRegistrationState.LastResult.Registered,
                lastMessage = null,
                lastAttemptAtMs = atMs,
            ),
        )
    }

    fun saveFailure(result: PushRegistrationState.LastResult, message: String, atMs: Long) {
        write(_state.value.copy(lastResult = result, lastMessage = message, lastAttemptAtMs = atMs))
    }

    /** Forgets the registration, e.g. on logout or when another account signs in. */
    fun clear() {
        write(PushRegistrationState())
    }

    /**
     * Replaces the pending delete with [transform]'s result, atomically. A record with nothing
     * left to do is removed. Returns the new record.
     */
    @Synchronized
    fun updatePendingDelete(transform: (PendingTokenDelete?) -> PendingTokenDelete?): PendingTokenDelete? {
        val next = transform(_pendingDelete)?.takeIf { it.serverDelete || it.localDelete }
        store.edit(
            mapOf(
                KEY_PENDING_DELETE_TOKEN to next?.token,
                KEY_PENDING_DELETE_SERVER to next?.serverDelete,
                KEY_PENDING_DELETE_LOCAL to next?.localDelete,
            ),
        )
        _pendingDelete = next
        return next
    }

    private fun read() = PushRegistrationState(
        token = store.getString(KEY_TOKEN),
        registeredAtMs = store.getLong(KEY_REGISTERED_AT),
        registeredAppVersion = store.getString(KEY_REGISTERED_APP_VERSION),
        lastResult = PushRegistrationState.LastResult.fromKey(store.getString(KEY_LAST_RESULT)),
        lastMessage = store.getString(KEY_LAST_MESSAGE),
        lastAttemptAtMs = store.getLong(KEY_LAST_ATTEMPT_AT),
    )

    private fun readPendingDelete(): PendingTokenDelete? = PendingTokenDelete(
        token = store.getString(KEY_PENDING_DELETE_TOKEN),
        serverDelete = store.getBoolean(KEY_PENDING_DELETE_SERVER) == true,
        localDelete = store.getBoolean(KEY_PENDING_DELETE_LOCAL) == true,
    ).takeIf { it.serverDelete || it.localDelete }

    private fun write(state: PushRegistrationState) {
        store.edit(
            mapOf(
                KEY_TOKEN to state.token,
                KEY_REGISTERED_AT to state.registeredAtMs,
                KEY_REGISTERED_APP_VERSION to state.registeredAppVersion,
                KEY_LAST_RESULT to state.lastResult?.key,
                KEY_LAST_MESSAGE to state.lastMessage,
                KEY_LAST_ATTEMPT_AT to state.lastAttemptAtMs,
            ),
        )
        _state.value = state
    }

    companion object {
        const val PREFS_NAME = "push_registration"
        private const val KEY_TOKEN = "fcm_token"
        private const val KEY_REGISTERED_AT = "registered_at"
        private const val KEY_REGISTERED_APP_VERSION = "registered_app_version"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
        private const val KEY_PENDING_DELETE_TOKEN = "pending_delete_token"
        private const val KEY_PENDING_DELETE_SERVER = "pending_delete_server"
        private const val KEY_PENDING_DELETE_LOCAL = "pending_delete_local"
    }
}
