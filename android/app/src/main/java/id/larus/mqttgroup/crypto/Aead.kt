package id.larus.mqttgroup.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM, the one symmetric primitive this app uses — for MQTT payloads, for attachment
 * blobs on disk, and for wrapping keys. Deliberately free of Android types so it can be tested
 * on the JVM.
 *
 * Nonces are 12 random bytes. That is the standard GCM size and the only one AES-GCM accepts
 * without an extra derivation step; 96 random bits means a repeat is not a practical concern
 * at the message rates a family group produces.
 */
object Aead {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun randomKey(): ByteArray = randomBytes(KEY_BYTES)

    /**
     * Encrypts [plaintext], returning `nonce || ciphertext || tag`. [aad] is authenticated but
     * not encrypted: pass the framing bytes that must not be tampered with, such as the sender
     * and counter of an envelope.
     */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BYTES * 8, nonce))
            aad?.let(::updateAAD)
        }
        val body = cipher.doFinal(plaintext)
        return nonce + body
    }

    /** Reverses [seal]. Throws [javax.crypto.AEADBadTagException] on a wrong key or tampering. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        require(sealed.size >= NONCE_BYTES + TAG_BYTES) { "sealed payload is too short to be valid" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                keySpec(key),
                GCMParameterSpec(TAG_BYTES * 8, sealed, 0, NONCE_BYTES),
            )
            aad?.let(::updateAAD)
        }
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    private fun keySpec(key: ByteArray): SecretKeySpec {
        require(key.size == KEY_BYTES) { "expected a ${KEY_BYTES * 8}-bit key, got ${key.size * 8}" }
        return SecretKeySpec(key, "AES")
    }
}
