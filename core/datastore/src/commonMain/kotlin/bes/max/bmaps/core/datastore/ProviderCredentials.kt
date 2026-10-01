/*
SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
Required Notice: Copyright (c) 2026 Maksim Bespalov.
Required Notice: Bmaps — https://github.com/besmax/Bmaps
License: https://polyformproject.org/licenses/noncommercial/1.0.0
Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.
*/

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
