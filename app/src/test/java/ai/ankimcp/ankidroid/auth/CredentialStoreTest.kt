package ai.ankimcp.ankidroid.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

class CredentialStoreTest {
    private class FakeStorage(var blob: EncryptedBlob? = null) : BlobStorage {
        var clears = 0
        override fun read(): EncryptedBlob? = blob
        override fun write(blob: EncryptedBlob) {
            this.blob = blob
        }

        override fun clear() {
            clears++
            blob = null
        }
    }

    /** XOR "cipher" whose decrypt can be told to fail. */
    private class FakeCipher : CredentialCipher {
        var failWith: Exception? = null
        var decrypts = 0

        override fun encrypt(plaintext: ByteArray) = EncryptedBlob(byteArrayOf(1), plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())

        override fun decrypt(blob: EncryptedBlob): ByteArray {
            decrypts++
            failWith?.let { throw it }
            return blob.ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val credentials = Credentials("at", "rt", "2099-01-01T00:00:00Z", UserInfo("u", "a@b.com", "free"))
    private val warnings = mutableListOf<String>()

    private fun storeWithSavedCredentials(cipher: FakeCipher = FakeCipher()): Triple<KeystoreCredentialStore, FakeStorage, FakeCipher> {
        val storage = FakeStorage()
        KeystoreCredentialStore(storage, cipher) { warnings += it }.save(credentials)
        return Triple(KeystoreCredentialStore(storage, cipher) { warnings += it }, storage, cipher)
    }

    @Test
    fun `nothing is read until load, then credentials round-trip`() {
        val (store, _, cipher) = storeWithSavedCredentials()

        assertFalse(store.loaded.value)
        assertNull(store.current.value)
        assertEquals(0, cipher.decrypts)

        assertEquals(credentials, store.load())
        assertTrue(store.loaded.value)
        assertEquals(credentials, store.current.value)
        store.load()
        assertEquals(1, cipher.decrypts)
    }

    @Test
    fun `transient decrypt failure keeps the ciphertext and retries on the next load`() {
        val (store, storage, cipher) = storeWithSavedCredentials()
        cipher.failWith = ProviderException("Keystore operation failed")

        assertNull(store.load())
        assertTrue(store.loaded.value)
        assertNotNull(storage.blob)
        assertEquals(0, storage.clears)
        assertTrue(warnings.single(), warnings.single().contains("will retry"))

        cipher.failWith = null
        assertEquals(credentials, store.load())
        assertEquals(credentials, store.current.value)
    }

    @Test
    fun `bad GCM tag clears the stored credentials`() {
        val (store, storage, cipher) = storeWithSavedCredentials()
        cipher.failWith = AEADBadTagException("Tag mismatch")

        assertNull(store.load())
        assertNull(storage.blob)
        assertEquals(1, storage.clears)

        // Not retried: the blob is gone for good.
        store.load()
        assertEquals(1, cipher.decrypts)
    }

    @Test
    fun `missing or unrecoverable key clears, also when wrapped`() {
        val (store, storage, cipher) = storeWithSavedCredentials()
        cipher.failWith = IllegalStateException("decrypt failed", UnrecoverableKeyException("Keystore key missing"))

        assertNull(store.load())
        assertNull(storage.blob)
    }

    @Test
    fun `replace saves only if the stored credentials are unchanged`() {
        val (store, _, _) = storeWithSavedCredentials()
        val fresh = credentials.copy(accessToken = "at-2")

        assertTrue(store.replace(credentials, fresh))
        assertEquals(fresh, store.load())

        store.clear()
        assertFalse(store.replace(fresh, credentials.copy(accessToken = "at-3")))
        assertNull(store.load())
    }

    @Test
    fun `clearIf clears only if the stored credentials are unchanged`() {
        val (store, storage, _) = storeWithSavedCredentials()
        val fresh = credentials.copy(accessToken = "at-2")
        store.save(fresh)

        assertFalse(store.clearIf(credentials))
        assertEquals(fresh, store.load())
        assertNotNull(storage.blob)

        assertTrue(store.clearIf(fresh))
        assertNull(store.load())
        assertNull(storage.blob)
    }

    @Test
    fun `empty storage loads as signed out`() {
        val store = KeystoreCredentialStore(FakeStorage(), FakeCipher()) { warnings += it }

        assertNull(store.load())
        assertTrue(store.loaded.value)
        assertTrue(warnings.isEmpty())
    }
}
