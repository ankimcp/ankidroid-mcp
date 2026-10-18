package ai.ankimcp.ankidroid.local

import ai.ankimcp.ankidroid.auth.BlobStorage
import ai.ankimcp.ankidroid.auth.CredentialCipher
import ai.ankimcp.ankidroid.auth.isUndecryptable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.Base64

/**
 * The local server's bearer token: 32 random bytes, base64url without padding (43 characters).
 * Stored like the account credentials (D15): AES-256-GCM under the AndroidKeyStore key, in its
 * own private SharedPreferences file. Read lazily; call [getOrCreate] off the main thread.
 *
 * A token that can never be decrypted is replaced (the user has to paste the new one into the
 * client). A transient read failure keeps the ciphertext and leaves [token] null, so the
 * server refuses to start instead of silently changing the token.
 */
class LocalServerTokenStore(
    private val storage: BlobStorage,
    private val cipher: CredentialCipher,
    private val warn: (String) -> Unit,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private var needsRead = true
    private val _token = MutableStateFlow<String?>(null)

    private val _saveError = MutableStateFlow<String?>(null)
    private val _readError = MutableStateFlow<String?>(null)

    /** The current token, or null until [getOrCreate] has read it. Never log it. */
    val token: StateFlow<String?> = _token.asStateFlow()

    /** User-readable reason the last create/regenerate failed (Keystore or storage error), else null. */
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    /**
     * User-readable reason the stored token could not be read this time (transient Keystore
     * failure; the ciphertext is kept), else null. Cleared by the next successful read or save.
     */
    val readError: StateFlow<String?> = _readError.asStateFlow()

    /**
     * The stored token, created on first use. Null when the stored one could not be read this
     * time, or a new one could not be stored.
     */
    fun getOrCreate(): String? = synchronized(lock) {
        if (needsRead) read()
        _token.value ?: if (needsRead) null else save(generate(random))
    }

    /**
     * Replaces the token; clients configured with the old one are refused from now on. Null
     * (and the old token kept) when the new one could not be stored.
     */
    fun regenerate(): String? = synchronized(lock) { save(generate(random)) }

    private fun read() {
        try {
            _token.value = storage.read()?.let { String(cipher.decrypt(it), Charsets.UTF_8) }
            needsRead = false
            _readError.value = null
        } catch (e: Exception) {
            if (isUndecryptable(e)) {
                warn("Local server token could not be decrypted (${e.javaClass.simpleName}); a new one was created")
                runCatching { storage.clear() }
                _token.value = null
                needsRead = false
                _readError.value = null
            } else {
                warn("Could not read the local server token, will retry: ${e.javaClass.simpleName}")
                _readError.value = "Could not read the access token (the phone's key store failed), so every " +
                    "request is refused. Turn \"Require access token\" off and on to try again."
            }
        }
    }

    /** Keystore (`ProviderException`, `KeyStoreException`) and storage failures are reported, not thrown. */
    private fun save(token: String): String? {
        try {
            storage.write(cipher.encrypt(token.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            warn("Could not store a new local server token: ${e.javaClass.simpleName}")
            _saveError.value = "Could not create an access token (the phone's key store failed). Try again."
            return null
        }
        needsRead = false
        _saveError.value = null
        _readError.value = null
        _token.value = token
        return token
    }

    companion object {
        const val PREFS_NAME = "local_server_v1"
        const val TOKEN_BYTES = 32

        fun generate(random: SecureRandom): String {
            val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
