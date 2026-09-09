package bes.max.bmaps.core.datastore

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first

class IosCredentialCipherTest {
    @Test
    fun platformDataStorePersistsOnlyEncryptedCredentials() = runTest {
        val store = IosPreferencesBindings.credentialsStore()
        val credentials = EncryptedProviderCredentials(store, IosCredentialCipher())
        try {
            assertEquals(CredentialWriteResult.SUCCESS, credentials.write("test.datastore", "test-only-secret"))
            val reopenedRepository = EncryptedProviderCredentials(store, IosCredentialCipher())
            assertEquals("test-only-secret", assertIs<CredentialResult.Available>(reopenedRepository.read("test.datastore")).value)
            assertFalse(store.data.first().toString().contains("test-only-secret"))
        } finally {
            credentials.remove("test.datastore")
        }
    }

    @Test
    fun keychainKeyReopensAndRejectsTamperingAndCredentialSwaps() = runTest {
        val plaintext = "test-only-api-key".encodeToByteArray()
        val cipher = IosCredentialCipher()
        val encrypted = cipher.encrypt("test.key", plaintext)
        assertFalse(encrypted.contentEquals(plaintext))
        assertFalse(cipher.encrypt("test.key", plaintext).contentEquals(encrypted))
        assertContentEquals(plaintext, IosCredentialCipher().decrypt("test.key", encrypted))
        assertFails { cipher.decrypt("another.key", encrypted) }
        val tampered = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertFails { cipher.decrypt("test.key", tampered) }
    }
}
