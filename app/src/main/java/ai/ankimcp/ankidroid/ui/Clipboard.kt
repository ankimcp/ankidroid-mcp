package ai.ankimcp.ankidroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copies [text]. Returns true when the app should confirm the copy itself: Android 13+ shows
 * its own confirmation, and the platform guidance is not to add a second one (D47).
 * [sensitive] text (the local server token) is hidden in that confirmation and in keyboard
 * clipboard suggestions on Android 13+.
 */
internal fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean = false): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        // ClipDescription.EXTRA_IS_SENSITIVE (API 33); older versions ignore the extra.
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    clipboard.setPrimaryClip(clip)
    return Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
}
