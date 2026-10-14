package ai.ankimcp.ankidroid.push

/**
 * The FCM client, behind an interface so nothing outside [FirebasePushMessaging] touches
 * Firebase classes (and tests never do).
 */
interface PushMessaging {
    /**
     * False when the build has no `google-services.json`, so no default FirebaseApp exists.
     * Push wake-ups are then unavailable and on-demand mode falls back to manual connect.
     */
    val isAvailable: Boolean

    /** The current FCM registration token. Only call when [isAvailable]. */
    suspend fun token(): String

    /** Invalidates the current token on the FCM side. Only call when [isAvailable]. */
    suspend fun deleteToken()

    /**
     * Turns FCM's automatic token creation and refresh on (after sign-in) or off (after
     * logout). The manifest turns it off until the first sign-in. Only call when [isAvailable].
     */
    fun setAutoInitEnabled(enabled: Boolean)
}
