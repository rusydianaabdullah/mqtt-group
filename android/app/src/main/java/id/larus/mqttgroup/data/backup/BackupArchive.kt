package id.larus.mqttgroup.data.backup

import id.larus.mqttgroup.crypto.Aead
import id.larus.mqttgroup.crypto.PassphraseKey
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * The backup file: a header nobody has to keep secret, and one sealed blob that is everything
 * else.
 *
 * ```
 * "FGBK"        4 bytes, so a file picked by mistake fails immediately rather than as a
 *               decryption error the user reads as a wrong passphrase
 * version       1 byte
 * iterations    4 bytes, big-endian — carried rather than assumed, so a file written today
 *               still opens after the cost is raised for new ones
 * salt          16 bytes
 * sealed        nonce || ciphertext || tag, from Aead
 * ```
 *
 * The header is passed to AES-GCM as additional authenticated data. Without that, the salt and
 * the iteration count sit in the clear and unprotected, and an attacker who can rewrite the file
 * can set iterations to 1 and hand it back — the victim's own app would then do the cheap
 * derivation for them.
 *
 * No Android types anywhere: this is where the format is decided, and it is testable on the JVM.
 */
object BackupArchive {

    const val VERSION = 1
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'G'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val HEADER_BYTES = 4 + 1 + 4 + PassphraseKey.SALT_BYTES

    /** Why a file could not be opened. Separated from "wrong passphrase" because they call for
     * completely different things from the user, and a single "failed" would leave them retyping
     * a correct passphrase at a file that was never a backup. */
    sealed interface Failure {
        data object NotABackup : Failure
        data class UnsupportedVersion(val version: Int) : Failure
        data object WrongPassphrase : Failure
    }

    fun seal(payload: JSONObject, passphrase: CharArray): ByteArray {
        val salt = PassphraseKey.newSalt()
        val header = header(PassphraseKey.ITERATIONS, salt)
        val key = PassphraseKey.derive(passphrase, salt)
        return try {
            header + Aead.seal(key, payload.toString().toByteArray(Charsets.UTF_8), aad = header)
        } finally {
            key.fill(0)
        }
    }

    fun open(bytes: ByteArray, passphrase: CharArray): Result<JSONObject> {
        if (bytes.size <= HEADER_BYTES) return Result.failure(BackupException(Failure.NotABackup))
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            return Result.failure(BackupException(Failure.NotABackup))
        }
        val version = bytes[4].toInt()
        if (version != VERSION) {
            return Result.failure(BackupException(Failure.UnsupportedVersion(version)))
        }
        val iterations = ByteBuffer.wrap(bytes, 5, 4).int
        // A hostile file could name a number that would hang the phone for a week. The ceiling
        // is generous enough for any real future increase and still finite.
        if (iterations <= 0 || iterations > MAX_ITERATIONS) {
            return Result.failure(BackupException(Failure.NotABackup))
        }
        val salt = bytes.copyOfRange(9, HEADER_BYTES)
        val header = bytes.copyOfRange(0, HEADER_BYTES)
        val key = PassphraseKey.derive(passphrase, salt, iterations)
        return try {
            val json = Aead.open(key, bytes.copyOfRange(HEADER_BYTES, bytes.size), aad = header)
            Result.success(JSONObject(String(json, Charsets.UTF_8)))
        } catch (_: Exception) {
            // Every failure past this point is the tag not verifying, which means a wrong
            // passphrase or a damaged file — indistinguishable by design, and neither one is
            // worth telling an attacker apart.
            Result.failure(BackupException(Failure.WrongPassphrase))
        } finally {
            key.fill(0)
        }
    }

    private fun header(iterations: Int, salt: ByteArray): ByteArray =
        MAGIC + byteArrayOf(VERSION.toByte()) +
            ByteBuffer.allocate(4).putInt(iterations).array() + salt

    private const val MAX_ITERATIONS = 10_000_000

    class BackupException(val failure: Failure) : Exception(failure.toString())
}
