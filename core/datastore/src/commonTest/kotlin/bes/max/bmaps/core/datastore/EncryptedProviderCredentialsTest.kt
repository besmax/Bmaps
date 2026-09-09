package bes.max.bmaps.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class EncryptedProviderCredentialsTest {
    @Test
    fun onlyCiphertextIsStoredAndValuesCanBeReopenedAndRemoved() = runTest {
        val store = MemoryStore()
        val cipher = TestCipher()
        val repository = EncryptedProviderCredentials(store, cipher)
        assertEquals(CredentialResult.Missing, repository.read("key"))
        assertEquals(CredentialWriteResult.SUCCESS, repository.write("key", "secret-value"))
        assertFalse(store.data.first().toString().contains("secret-value"))
        val reopened = EncryptedProviderCredentials(store, cipher)
        val value = assertIs<CredentialResult.Available>(reopened.read("key"))
        assertEquals("secret-value", value.value)
        assertFalse(value.toString().contains("secret-value"))
        assertEquals(CredentialWriteResult.SUCCESS, reopened.remove("key"))
        assertEquals(CredentialResult.Missing, reopened.read("key"))
    }

    @Test
    fun cipherFailureNeverOverwritesExistingCiphertextAndMissingKeyIsNotMissingCredential() = runTest {
        val store = MemoryStore()
        val cipher = TestCipher()
        val repository = EncryptedProviderCredentials(store, cipher)
        repository.write("key", "original")
        val original = store.data.first()
        cipher.fail = true
        assertEquals(CredentialWriteResult.UNAVAILABLE, repository.write("key", "replacement"))
        assertEquals(original, store.data.first())
        assertEquals(CredentialResult.Unavailable, repository.read("key"))
        cipher.fail = false
        assertEquals("original", assertIs<CredentialResult.Available>(repository.read("key")).value)
    }

    @Test
    fun malformedEnvelopeAndCancellationRemainDistinct() = runTest {
        val store = MemoryStore()
        store.updateData { mutablePreferencesOf(stringPreferencesKey("provider_credential_v1.key") to "not base64!") }
        val repository = EncryptedProviderCredentials(store, TestCipher())
        assertEquals(CredentialResult.Unavailable, repository.read("key"))
        val cancelled = EncryptedProviderCredentials(store, object : CredentialCipher {
            override suspend fun encrypt(identifier: String, plaintext: ByteArray): ByteArray = throw CancellationException()
            override suspend fun decrypt(identifier: String, ciphertext: ByteArray): ByteArray = throw CancellationException()
        })
        assertFailsWith<CancellationException> { cancelled.write("key", "value") }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class TestCipher : CredentialCipher {
        var fail = false
        override suspend fun encrypt(identifier: String, plaintext: ByteArray): ByteArray {
            check(!fail)
            return plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
        override suspend fun decrypt(identifier: String, ciphertext: ByteArray): ByteArray = encrypt(identifier, ciphertext)
    }
}
