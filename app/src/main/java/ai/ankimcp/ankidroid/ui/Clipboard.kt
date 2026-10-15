package ai.ankimcp.ankidroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/**
 * Copies [text]. Returns true when the app should confirm the copy itself: Android 13+ shows
 * its own confirmation, and the platform guidance is not to add a second one (D47).
 */
internal fun copyToClipboard(context: Context, label: String, text: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    return Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
}
