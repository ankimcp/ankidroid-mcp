package ai.ankimcp.ankidroid.settings

import android.content.SharedPreferences

/** The few SharedPreferences operations the settings need, so they can be tested on the JVM. */
interface KeyValueStore {
    fun contains(key: String): Boolean

    fun getString(key: String): String?

    fun getLong(key: String): Long?

    fun getBoolean(key: String): Boolean?

    /** Applies all changes at once; a null value removes the key. */
    fun edit(changes: Map<String, Any?>)
}

class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    override fun getBoolean(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    override fun edit(changes: Map<String, Any?>) {
        val editor = prefs.edit()
        for ((key, value) in changes) {
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                else -> throw IllegalArgumentException("Unsupported value type for $key")
            }
        }
        editor.apply()
    }
}
