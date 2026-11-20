package id.larus.mqttgroup.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Turns a passphrase into an AES key for [Aead].
 *
 * PBKDF2-HMAC-SHA256, because it is the only password KDF the Android platform ships. Argon2id
 * or scrypt would be the better answer — both are memory-hard, and PBKDF2 is not, which is
 * exactly the property that matters against someone cracking a stolen backup file on a GPU — but
 * neither is available without adding a native library to an app whose whole argument is that it
 * carries no more code than it must. [ITERATIONS] is set to the OWASP figure for this
 * construction, and the honest mitigation is the one the UI has to state: the file is only as
 * strong as the passphrase typed into it.
 *
 * No Android types, so it can be tested on the JVM.
 */
object PassphraseKey {

    const val SALT_BYTES = 16

    /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. Roughly a second on a mid-range
     * phone, which is a fair price paid twice in the life of a backup. */
    const val ITERATIONS = 210_000

    fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(salt.size >= SALT_BYTES) { "salt is too short to be useful" }
        require(iterations > 0) { "iterations must be positive" }
        val spec = PBEKeySpec(passphrase, salt, iterations, Aead.KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            // The spec holds its own copy of the passphrase; clearing it is the only part of
            // this we can actually control. The caller owns the array it passed in.
            spec.clearPassword()
        }
    }

    fun newSalt(): ByteArray = Aead.randomBytes(SALT_BYTES)

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
}
