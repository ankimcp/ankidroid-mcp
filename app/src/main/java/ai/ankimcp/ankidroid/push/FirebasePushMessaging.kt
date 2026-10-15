package ai.ankimcp.ankidroid.push

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PushMessaging] over Firebase Cloud Messaging. Without `google-services.json` the
 * `FirebaseInitProvider` finds no options and creates no default app; every
 * `FirebaseMessaging.getInstance()` would then throw, so [isAvailable] is checked first.
 *
 * Uses the registration-token API (`getToken`/`deleteToken`), deprecated since
 * firebase-messaging 25.1.0 in favour of FID-based `register()`; the server contract is built on
 * tokens, see docs/DECISIONS.md D38.
 */
class FirebasePushMessaging(context: Context) : PushMessaging {
    private val appContext = context.applicationContext

    override val isAvailable: Boolean by lazy {
        try {
            FirebaseApp.getApps(appContext).isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Firebase unavailable: ${e.javaClass.simpleName}")
            false
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun token(): String {
        check(isAvailable) { "Push not configured in this build" }
        return FirebaseMessaging.getInstance().token.await()
    }

    @Suppress("DEPRECATION")
    override suspend fun deleteToken() {
        check(isAvailable) { "Push not configured in this build" }
        FirebaseMessaging.getInstance().deleteToken().await()
    }

    override fun setAutoInitEnabled(enabled: Boolean) {
        check(isAvailable) { "Push not configured in this build" }
        // Persisted by Firebase; overrides the manifest's firebase_messaging_auto_init_enabled.
        FirebaseMessaging.getInstance().isAutoInitEnabled = enabled
    }

    private companion object {
        const val TAG = "FirebasePush"
    }
}

/** Minimal `Task.await()`, instead of pulling in kotlinx-coroutines-play-services for two calls. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            task.isSuccessful -> cont.resume(task.result)
            error != null -> cont.resumeWithException(error)
            else -> cont.resumeWithException(IllegalStateException("Firebase task was cancelled"))
        }
    }
}
