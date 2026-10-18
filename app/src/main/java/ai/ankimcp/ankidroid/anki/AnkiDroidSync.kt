package ai.ankimcp.ankidroid.anki

/** What asking for an AnkiDroid sync did (D58). */
sealed interface SyncRequest {
    /** The "Tap to sync AnkiDroid" notification is on the phone. */
    data object NotificationPosted : SyncRequest

    /** The notification would not be seen; [reason] says why (permission, app switch, channel). */
    data class NotificationBlocked(val reason: String) : SyncRequest

    /** No AnkiDroid activity takes the sync intent (not installed, or a build without it). */
    data object AnkiDroidMissing : SyncRequest
}

/**
 * Asks the user to start AnkiDroid's sync. Android only lets an activity start from a user action
 * when our app is in the background, so the request is a notification whose tap sends AnkiDroid's
 * `com.ichi2.anki.DO_SYNC` intent (D58). Kept as an interface so the `sync` tool is JVM-testable;
 * the Android implementation is `service.NotificationSyncRequester`.
 */
fun interface AnkiDroidSync {
    fun requestSync(): SyncRequest
}
