@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package bes.max.bmaps.core.datastore

import bes.max.bmaps.core.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreFoundation.*
import platform.Security.*

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosCredentialCipher : CredentialCipher {
    private val lock = Mutex()
    private val algorithm = kSecKeyAlgorithmECIESEncryptionCofactorX963SHA256AESGCM

    override suspend fun encrypt(identifier: String, plaintext: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        lock.withLock {
            val privateKey = key(create = true)
            try {
                val publicKey = checkNotNull(SecKeyCopyPublicKey(privateKey))
                try {
                    val payload = identifier.encodeToByteArray() + byteArrayOf(0) + plaintext
                    try {
                        byteArrayOf(1) + transform(payload) { SecKeyCreateEncryptedData(publicKey, algorithm, it, null) }
                    } finally { payload.fill(0) }
                } finally { CFRelease(publicKey) }
            } finally { CFRelease(privateKey) }
        }
    }

    override suspend fun decrypt(identifier: String, ciphertext: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        lock.withLock {
            require(ciphertext.size > 1 && ciphertext[0] == 1.toByte())
            val privateKey = key(create = false)
            try {
                val payload = transform(ciphertext.copyOfRange(1, ciphertext.size)) {
                    SecKeyCreateDecryptedData(privateKey, algorithm, it, null)
                }
                try {
                    val prefix = identifier.encodeToByteArray() + byteArrayOf(0)
                    check(payload.size >= prefix.size && payload.copyOfRange(0, prefix.size).contentEquals(prefix))
                    payload.copyOfRange(prefix.size, payload.size)
                } finally { payload.fill(0) }
            } finally { CFRelease(privateKey) }
        }
    }

    private fun key(create: Boolean): SecKeyRef = memScoped {
        val tag = "bes.max.bmaps.provider-credentials.v1".encodeToByteArray().asData()
        try {
            val result = alloc<CFTypeRefVar>()
            val query = dictionary(
                kSecClass to kSecClassKey,
                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                kSecAttrApplicationTag to tag,
                kSecReturnRef to kCFBooleanTrue,
            )
            val status = try { SecItemCopyMatching(query, result.ptr) } finally { CFRelease(query) }
            if (status == errSecSuccess) return@memScoped checkNotNull(result.value).reinterpret()
            check(status == errSecItemNotFound && create) { "Credential key unavailable (OSStatus $status)" }
            val size = alloc<IntVar> { value = 256 }
            val bits = checkNotNull(CFNumberCreate(null, kCFNumberIntType, size.ptr))
            val privateAttributes = dictionary(
                kSecAttrIsPermanent to kCFBooleanTrue,
                kSecAttrApplicationTag to tag,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            )
            try {
                val attributes = dictionary(
                    kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                    kSecAttrKeySizeInBits to bits,
                    kSecPrivateKeyAttrs to privateAttributes,
                )
                try { checkNotNull(SecKeyCreateRandomKey(attributes, null)) { "Credential key unavailable" } }
                finally { CFRelease(attributes) }
            } finally {
                CFRelease(bits)
                CFRelease(privateAttributes)
            }
        } finally { CFRelease(tag) }
    }

    private fun transform(bytes: ByteArray, action: (CFDataRef) -> CFDataRef?): ByteArray {
        val input = bytes.asData()
        try {
            val output = checkNotNull(action(input)) { "Credential encryption unavailable" }
            try { return CFDataGetBytePtr(output)!!.readBytes(CFDataGetLength(output).toInt()) }
            finally { CFRelease(output) }
        } finally { CFRelease(input) }
    }
}

private fun ByteArray.asData(): CFDataRef = usePinned {
    checkNotNull(CFDataCreate(null, it.addressOf(0).reinterpret(), size.toLong()))
}

private fun dictionary(vararg entries: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef {
    val result = checkNotNull(CFDictionaryCreateMutable(null, 0,
        kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
    entries.forEach { (key, value) -> CFDictionarySetValue(result, key, value) }
    return result
}
