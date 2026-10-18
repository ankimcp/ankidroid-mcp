package ai.ankimcp.ankidroid.anki

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * AnkiDroid's installed version. [code] is the package's `versionCode` with the ABI digit that
 * AnkiDroid's per-ABI builds add (`abi * 100000000`) removed, so 2.24.0 is 22400300 in every
 * build (AnkiDroid's scheme: major, 2-digit minor, 2-digit patch, build type 0-3 with 3 =
 * release, 2-digit build number).
 */
data class AnkiDroidVersion(val code: Long, val name: String) {
    fun atLeast(required: RequiredVersion): Boolean = code >= required.code

    companion object {
        fun of(rawVersionCode: Long, versionName: String?): AnkiDroidVersion =
            AnkiDroidVersion(rawVersionCode % ABI_FACTOR, versionName ?: rawVersionCode.toString())

        private const val ABI_FACTOR = 100_000_000L
    }
}

/** A minimum AnkiDroid release: [code] in [AnkiDroidVersion.code]'s scheme, [label] for messages. */
data class RequiredVersion(val code: Long, val label: String) {
    companion object {
        /** `cards` search and the scheduling/FSRS card columns (D57). */
        val TOOL_SET = RequiredVersion(22400300, "2.24")

        /** Card `flags` read and write. */
        val CARD_FLAGS = RequiredVersion(22500300, "2.25")
    }
}

/** Whether AnkiDroid is installed, which version, and whether we hold its database permission. */
interface AnkiDroidAvailability {
    fun isInstalled(): Boolean

    fun hasPermission(): Boolean

    /** Null if AnkiDroid is not installed. */
    fun version(): AnkiDroidVersion?

    companion object {
        const val PACKAGE_NAME = "com.ichi2.anki"
        const val PERMISSION = FlashCardsContract.READ_WRITE_PERMISSION
    }
}

class AndroidAnkiDroidAvailability(private val context: Context) : AnkiDroidAvailability {
    override fun isInstalled(): Boolean = packageInfo() != null

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, AnkiDroidAvailability.PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun version(): AnkiDroidVersion? = packageInfo()?.let { info ->
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        AnkiDroidVersion.of(code, info.versionName)
    }

    private fun packageInfo(): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(AnkiDroidAvailability.PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(AnkiDroidAvailability.PACKAGE_NAME, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
