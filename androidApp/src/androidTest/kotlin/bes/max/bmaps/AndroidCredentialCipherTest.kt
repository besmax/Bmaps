package bes.max.bmaps

import androidx.test.ext.junit.runners.AndroidJUnit4
import bes.max.bmaps.core.datastore.AndroidCredentialCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCredentialCipherTest {
    @Test
    fun keystoreKeyReopensAndRejectsTamperingAndCredentialSwaps() = runBlocking {
        val plaintext = "test-only-api-key".toByteArray()
        val cipher = AndroidCredentialCipher()
        val encrypted = cipher.encrypt("test.key", plaintext)
        assertFalse(encrypted.contentEquals(plaintext))
        assertFalse(cipher.encrypt("test.key", plaintext).contentEquals(encrypted))
        assertArrayEquals(plaintext, AndroidCredentialCipher().decrypt("test.key", encrypted))
        suspend fun fails(block: suspend () -> Unit) {
            var failed = false
            try { block() } catch (_: Exception) { failed = true }
            assertTrue(failed)
        }
        fails { cipher.decrypt("another.key", encrypted) }
        val tampered = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        fails { cipher.decrypt("test.key", tampered) }
    }
}
