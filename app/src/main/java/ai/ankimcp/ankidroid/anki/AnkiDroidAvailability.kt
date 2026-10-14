package ai.ankimcp.ankidroid.anki

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Whether AnkiDroid is installed and we hold its database permission. */
interface AnkiDroidAvailability {
    fun isInstalled(): Boolean

    fun hasPermission(): Boolean

    companion object {
        const val PACKAGE_NAME = "com.ichi2.anki"
        const val PERMISSION = FlashCardsContract.READ_WRITE_PERMISSION
    }
}

class AndroidAnkiDroidAvailability(private val context: Context) : AnkiDroidAvailability {
    override fun isInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(AnkiDroidAvailability.PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(AnkiDroidAvailability.PACKAGE_NAME, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, AnkiDroidAvailability.PERMISSION) == PackageManager.PERMISSION_GRANTED
}
