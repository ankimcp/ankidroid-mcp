package ai.ankimcp.ankidroid.settings

/** [KeyValueStore] over a map; values keep their type like SharedPreferences does. */
class InMemoryKeyValueStore(initial: Map<String, Any> = emptyMap()) : KeyValueStore {
    val values = initial.toMutableMap()

    override fun contains(key: String): Boolean = key in values

    override fun getString(key: String): String? = values[key] as? String

    override fun getLong(key: String): Long? = values[key] as? Long

    override fun getBoolean(key: String): Boolean? = values[key] as? Boolean

    override fun edit(changes: Map<String, Any?>) {
        for ((key, value) in changes) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }
}
