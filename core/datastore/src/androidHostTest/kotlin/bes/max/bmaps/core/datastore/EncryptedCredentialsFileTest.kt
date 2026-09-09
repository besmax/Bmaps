package bes.max.bmaps.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class EncryptedCredentialsFileTest {
    @Test
    fun ciphertextSurvivesFileReopenAndRejectsTheWrongKey() = runTest {
        val directory = Files.createTempDirectory("bmaps-credential-test").toFile()
        val file = directory.resolve("test.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cipher = JvmTestCipher()
        try {
            var store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repository = EncryptedProviderCredentials(store, cipher)
            assertEquals(CredentialWriteResult.SUCCESS, repository.write("test.key", "test-only-secret"))
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            assertFalse(file.readText().contains("test-only-secret"))
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            store = PreferenceDataStoreFactory.create(scope = scope) { file }
            assertEquals("test-only-secret", assertIs<CredentialResult.Available>(
                EncryptedProviderCredentials(store, cipher).read("test.key")).value)
            assertEquals(CredentialResult.Unavailable, EncryptedProviderCredentials(store, JvmTestCipher()).read("test.key"))
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            directory.deleteRecursively()
        }
    }

    private class JvmTestCipher : CredentialCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override suspend fun encrypt(identifier: String, plaintext: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(identifier.toByteArray())
                iv + doFinal(plaintext)
            }
        override suspend fun decrypt(identifier: String, ciphertext: ByteArray): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
                updateAAD(identifier.toByteArray())
                doFinal(ciphertext, 12, ciphertext.size - 12)
            }
    }
}
