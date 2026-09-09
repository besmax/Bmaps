package bes.max.bmaps.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class EncryptedProviderCredentials(
    @CredentialStorage private val store: DataStore<Preferences>,
    private val cipher: CredentialCipher,
) : ProviderCredentials {
    override suspend fun read(identifier: String): CredentialResult = try {
        val encoded = store.data.first()[key(identifier)]
        if (encoded == null) CredentialResult.Missing else {
            val plaintext = cipher.decrypt(identifier, Base64.decode(encoded))
            try {
                CredentialResult.Available(plaintext.decodeToString(throwOnInvalidSequence = true))
            } finally {
                plaintext.fill(0)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CredentialResult.Unavailable
    }

    override suspend fun write(identifier: String, value: String): CredentialWriteResult = mutate {
        val preferenceKey = key(identifier)
        require(value.isNotBlank() && value.length <= 8192)
        val plaintext = value.encodeToByteArray()
        val encrypted = try { cipher.encrypt(identifier, plaintext) } finally { plaintext.fill(0) }
        store.edit { it[preferenceKey] = Base64.encode(encrypted) }
    }

    override suspend fun remove(identifier: String): CredentialWriteResult = mutate {
        store.edit { it.remove(key(identifier)) }
    }

    private suspend fun mutate(block: suspend () -> Unit): CredentialWriteResult = try {
        block()
        CredentialWriteResult.SUCCESS
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CredentialWriteResult.UNAVAILABLE
    }

    private fun key(identifier: String): Preferences.Key<String> {
        require(identifier.length in 1..128 && identifier.all { it.isLetterOrDigit() || it in "._-" })
        return stringPreferencesKey("provider_credential_v1.$identifier")
    }
}

@dev.zacsweers.metro.Qualifier
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
annotation class CredentialStorage
