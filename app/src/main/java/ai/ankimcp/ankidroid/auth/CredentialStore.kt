package ai.ankimcp.ankidroid.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Persists the signed-in user's [Credentials]. */
interface CredentialStore {
    /** Current credentials, or null when signed out (or not read from disk yet). */
    val current: StateFlow<Credentials?>

    /** False until the store has tried to read its persisted state once. */
    val loaded: StateFlow<Boolean> get() = ALWAYS_LOADED

    fun load(): Credentials? = current.value

    fun save(credentials: Credentials)

    fun clear()

    /**
     * Saves [credentials] only if the stored credentials still equal [expected] (nobody logged
     * out or saved in between). Returns false and changes nothing otherwise.
     */
    fun replace(expected: Credentials, credentials: Credentials): Boolean = synchronized(this) {
        if (load() != expected) return false
        save(credentials)
        true
    }

    /**
     * Clears only if the stored credentials still equal [expected], so a sign-in that happened
     * in the meantime is kept. Returns false and changes nothing otherwise.
     */
    fun clearIf(expected: Credentials): Boolean = synchronized(this) {
        if (load() != expected) return false
        clear()
        true
    }

    private companion object {
        val ALWAYS_LOADED: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    }
}

/** IV + ciphertext of the encrypted credentials JSON. */
class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray)

/** Encrypts the credentials JSON. Kept behind an interface so the store is testable on the JVM. */
interface CredentialCipher {
    fun encrypt(plaintext: ByteArray): EncryptedBlob

    /**
     * @throws AEADBadTagException, KeyPermanentlyInvalidatedException or UnrecoverableKeyException
     *   if [blob] can never be decrypted; any other exception is treated as transient.
     */
    fun decrypt(blob: EncryptedBlob): ByteArray
}

/** Where the encrypted blob lives. */
interface BlobStorage {
    fun read(): EncryptedBlob?

    fun write(blob: EncryptedBlob)

    fun clear()
}

/**
 * Stores credentials JSON encrypted with AES-256-GCM under a non-exportable AndroidKeyStore
 * key. Ciphertext and IV live in a private SharedPreferences file that is excluded from
 * backup (a restored copy could never be decrypted anyway).
 *
 * Nothing is read at construction: the first [load] decrypts (call it off the main thread).
 * Only errors that mean the blob can never be decrypted (bad tag, invalidated or missing key)
 * discard it. Other failures (Keystore busy, I/O) keep the ciphertext, leave the user signed
 * out for now, and the next [load] tries again.
 */
class KeystoreCredentialStore(
    private val storage: BlobStorage,
    private val cipher: CredentialCipher,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) : CredentialStore {
    constructor(context: Context) : this(
        SharedPreferencesBlobStorage(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        KeystoreCredentialCipher(),
    )

    private val lock = Any()
    private val _current = MutableStateFlow<Credentials?>(null)
    private val _loaded = MutableStateFlow(false)

    /** True until the persisted state was read successfully or found absent/unrecoverable. */
    private var needsRead = true

    override val current: StateFlow<Credentials?> = _current.asStateFlow()
    override val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    override fun load(): Credentials? = synchronized(lock) {
        if (needsRead) readFromDisk()
        _current.value
    }

    override fun save(credentials: Credentials) {
        synchronized(lock) {
            storage.write(cipher.encrypt(credentials.toJson().toByteArray(Charsets.UTF_8)))
            needsRead = false
            _current.value = credentials
            _loaded.value = true
        }
    }

    override fun clear() {
        synchronized(lock) {
            storage.clear()
            needsRead = false
            _current.value = null
            _loaded.value = true
        }
    }

    override fun replace(expected: Credentials, credentials: Credentials): Boolean = synchronized(lock) {
        if (load() != expected) return false
        save(credentials)
        true
    }

    override fun clearIf(expected: Credentials): Boolean = synchronized(lock) {
        if (load() != expected) return false
        clear()
        true
    }

    private fun readFromDisk() {
        try {
            val blob = storage.read()
            _current.value = blob?.let { Credentials.fromJson(String(cipher.decrypt(it), Charsets.UTF_8)) }
            needsRead = false
        } catch (e: Exception) {
            if (isPermanent(e)) {
                warn("Discarding undecryptable credentials: ${e.javaClass.simpleName}")
                storage.clear()
                _current.value = null
                needsRead = false
            } else {
                warn("Could not read credentials, will retry: ${e.javaClass.simpleName}")
            }
        }
        _loaded.value = true
    }

    private companion object {
        const val TAG = "CredentialStore"
        const val PREFS_NAME = "credentials_v1"

        fun isPermanent(e: Throwable): Boolean = isUndecryptable(e)
    }
}

/** The blob can never be decrypted (bad tag, invalidated or missing key), also when wrapped as a cause. */
internal fun isUndecryptable(e: Throwable): Boolean = generateSequence(e) { it.cause }.any {
    it is AEADBadTagException || it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException
}

/** IV and ciphertext as Base64 strings (same format as Android's `Base64.NO_WRAP`). */
class SharedPreferencesBlobStorage(private val prefs: SharedPreferences) : BlobStorage {
    override fun read(): EncryptedBlob? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return EncryptedBlob(Base64.getDecoder().decode(iv), Base64.getDecoder().decode(blob))
    }

    // commit(), not apply(): a rotated refresh token must be on disk before it is used.
    override fun write(blob: EncryptedBlob) {
        prefs.edit()
            .putString(KEY_IV, Base64.getEncoder().encodeToString(blob.iv))
            .putString(KEY_BLOB, Base64.getEncoder().encodeToString(blob.ciphertext))
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_IV = "iv"
        const val KEY_BLOB = "blob"
    }
}

/** AES-256-GCM under a non-exportable AndroidKeyStore key. */
class KeystoreCredentialCipher : CredentialCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedBlob(cipher.iv, ciphertext)
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        // A missing key means the blob can never be decrypted again.
        val key = existingKey() ?: throw UnrecoverableKeyException("Keystore key missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
        return cipher.doFinal(blob.ciphertext)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    // One lock for every instance: the credential store and the local server token share the
    // alias, and two first saves at once must not generate the key twice.
    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) { existingKey() ?: generateKey() }

    private fun generateKey(): SecretKey = KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ankimcp_credentials_v1"
        val KEY_LOCK = Any()
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
