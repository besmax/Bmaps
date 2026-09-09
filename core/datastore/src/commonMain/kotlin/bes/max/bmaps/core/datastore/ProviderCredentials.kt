package bes.max.bmaps.core.datastore

interface ProviderCredentials {
    suspend fun read(identifier: String): CredentialResult
    suspend fun write(identifier: String, value: String): CredentialWriteResult
    suspend fun remove(identifier: String): CredentialWriteResult
}

sealed interface CredentialResult {
    class Available(val value: String) : CredentialResult {
        override fun toString(): String = "CredentialResult.Available(<redacted>)"
    }
    data object Missing : CredentialResult
    data object Unavailable : CredentialResult
}

enum class CredentialWriteResult { SUCCESS, UNAVAILABLE }

interface CredentialCipher {
    suspend fun encrypt(identifier: String, plaintext: ByteArray): ByteArray
    suspend fun decrypt(identifier: String, ciphertext: ByteArray): ByteArray
}
