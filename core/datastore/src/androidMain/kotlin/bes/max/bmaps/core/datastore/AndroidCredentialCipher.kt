package bes.max.bmaps.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidCredentialCipher : CredentialCipher {
    private val lock = Mutex()

    override suspend fun encrypt(identifier: String, plaintext: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        lock.withLock {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(identifier.toByteArray(Charsets.UTF_8))
            byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
        }
    }

    override suspend fun decrypt(identifier: String, ciphertext: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        lock.withLock {
            require(ciphertext.size >= 29 && ciphertext[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, ciphertext.copyOfRange(1, 13)))
            cipher.updateAAD(identifier.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext, 13, ciphertext.size - 13)
        }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object { const val ALIAS = "bes.max.bmaps.provider-credentials.v1" }
}
