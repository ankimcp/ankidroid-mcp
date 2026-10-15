package ai.ankimcp.ankidroid.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Waits until the stored credentials are loaded and the app is in the foreground, then calls
 * [connect] if [shouldConnect] still holds. Android 12+ refuses to start a foreground service
 * from the background, and the Keystore decrypt can finish after the user already left the app;
 * in that case this waits for the next time the app comes to the foreground.
 */
internal suspend fun autoConnectWhenForeground(
    credentialsLoaded: Flow<Boolean>,
    inForeground: Flow<Boolean>,
    shouldConnect: () -> Boolean,
    connect: () -> Unit,
) {
    credentialsLoaded.first { it }
    inForeground.first { it }
    if (shouldConnect()) connect()
}
