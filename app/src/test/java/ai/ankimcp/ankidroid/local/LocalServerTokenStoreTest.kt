package ai.ankimcp.ankidroid.local

import ai.ankimcp.ankidroid.auth.BlobStorage
import ai.ankimcp.ankidroid.auth.CredentialCipher
import ai.ankimcp.ankidroid.auth.EncryptedBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64

class LocalServerTokenStoreTest {
    private class FakeStorage : BlobStorage {
        var blob: EncryptedBlob? = null
        var clears = 0
        override fun read() = blob
        override fun write(blob: EncryptedBlob) {
            this.blob = blob
        }
        override fun clear() {
            clears++
            blob = null
        }
    }

    /** XOR "cipher", so the stored bytes are not the plaintext token. */
    private class FakeCipher(var failWith: Exception? = null) : CredentialCipher {
        override fun encrypt(plaintext: ByteArray) = EncryptedBlob(byteArrayOf(1), plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
        override fun decrypt(blob: EncryptedBlob): ByteArray {
            failWith?.let { throw it }
            return blob.ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val storage = FakeStorage()
    private val cipher = FakeCipher()
    private val warnings = mutableListOf<String>()

    private fun store(c: CredentialCipher = cipher) = LocalServerTokenStore(storage, c, { warnings += it })

    @Test
    fun `token is 32 random bytes as base64url without padding`() {
        val token = LocalServerTokenStore.generate(SecureRandom())

        assertEquals(43, token.length)
        assertTrue(token, token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(32, Base64.getUrlDecoder().decode(token).size)
        assertNotEquals(token, LocalServerTokenStore.generate(SecureRandom()))
    }

    @Test
    fun `created on first use, stored encrypted, read back by a new process`() {
        assertNull(store().token.value)
        val token = store().getOrCreate()!!

        val stored = String(storage.blob!!.ciphertext, Charsets.UTF_8)
        assertFalse(stored.contains(token))

        val reread = store()
        assertNull(reread.token.value)
        assertEquals(token, reread.getOrCreate())
        assertEquals(token, reread.token.value)
    }

    @Test
    fun `regenerate replaces the stored token`() {
        val first = store().getOrCreate()
        val second = store().regenerate()

        assertNotEquals(first, second)
        assertEquals(second, store().getOrCreate())
    }

    @Test
    fun `an undecryptable token is replaced`() {
        val old = store().getOrCreate()
        cipher.failWith = UnrecoverableKeyException("Keystore key missing")

        val replaced = store().getOrCreate()

        assertNotNull(replaced)
        assertNotEquals(old, replaced)
        assertEquals(1, storage.clears)
        assertNotNull(storage.blob)
        assertTrue(warnings.single().contains("UnrecoverableKeyException"))
    }

    @Test
    fun `a Keystore failure while storing is reported, not thrown`() {
        val failing = object : CredentialCipher {
            override fun encrypt(plaintext: ByteArray): EncryptedBlob = throw java.security.ProviderException("Keystore operation failed")
            override fun decrypt(blob: EncryptedBlob): ByteArray = blob.ciphertext
        }
        val store = store(failing)

        assertNull(store.getOrCreate())
        assertNull(store.regenerate())
        assertNull(store.token.value)
        assertNotNull(store.saveError.value)
        assertTrue(warnings.all { it == "Could not store a new local server token: ProviderException" })
    }

    @Test
    fun `a transient read failure keeps the stored token and retries`() {
        val token = store().getOrCreate()
        cipher.failWith = IllegalStateException("Keystore busy")
        val flaky = store()

        assertNull(flaky.getOrCreate())
        assertEquals(0, storage.clears)
        assertTrue(warnings.single().contains("will retry"))
        // Surfaced for the card by reason only, never the token or the exception text.
        val readError = flaky.readError.value!!
        assertTrue(readError.contains("Could not read the access token"))
        assertFalse(readError.contains(token!!))
        assertFalse(readError.contains("Keystore busy"))

        cipher.failWith = null
        assertEquals(token, flaky.getOrCreate())
        assertNull(flaky.readError.value)
    }
}
