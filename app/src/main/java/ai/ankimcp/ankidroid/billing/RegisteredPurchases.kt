package ai.ankimcp.ankidroid.billing

import ai.ankimcp.ankidroid.settings.KeyValueStore
import java.security.MessageDigest

/**
 * Purchase tokens the server has accepted, kept as SHA-256 hashes (the tokens themselves never
 * hit the disk), so restore does not POST the same token on every app start. The newest
 * [MAX_ENTRIES] are kept; cleared on sign-out.
 */
class RegisteredPurchases(private val store: KeyValueStore) {
    private val lock = Any()

    fun contains(purchaseToken: String): Boolean = synchronized(lock) { hash(purchaseToken) in read() }

    fun add(purchaseToken: String) = synchronized(lock) {
        val hashes = read().filterNot { it == hash(purchaseToken) } + hash(purchaseToken)
        store.edit(mapOf(KEY to hashes.takeLast(MAX_ENTRIES).joinToString(SEPARATOR)))
    }

    fun clear() = synchronized(lock) { store.edit(mapOf(KEY to null)) }

    private fun read(): List<String> =
        store.getString(KEY)?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    companion object {
        const val PREFS_NAME = "billing"
        private const val KEY = "registered_purchase_hashes"
        private const val SEPARATOR = ","
        private const val MAX_ENTRIES = 50

        internal fun hash(purchaseToken: String): String =
            MessageDigest.getInstance("SHA-256").digest(purchaseToken.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
