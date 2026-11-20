package id.larus.mqttgroup.data.blob

import id.larus.mqttgroup.crypto.Aead
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Attachments on disk: avatars, photos, voice notes, files. Each blob is one file holding
 * `nonce || ciphertext || tag` under the vault's blob key, so the app's private directory
 * contains nothing readable even to a process that gets past the sandbox.
 *
 * Blobs are read and written whole. That is fine for a 1 MB cap and it keeps the invariant
 * simple: a blob either decrypts and matches its digest, or it does not exist.
 *
 * Takes a plain [File] and a key rather than a Context, so it runs — and is tested — on the JVM.
 */
class BlobStore(private val directory: File, private val key: ByteArray) {

    init {
        directory.mkdirs()
    }

    /** Stores [bytes] and returns the id to reference it by. */
    fun put(bytes: ByteArray): String {
        val blobId = UUID.randomUUID().toString()
        put(blobId, bytes)
        return blobId
    }

    /**
     * Stores [bytes] under a caller-chosen id. For an attachment received over MQTT, the id is
     * not ours to pick — it came from whoever sent it, and has to match so the chat message that
     * references it and the bytes that satisfy it agree.
     */
    fun put(blobId: String, bytes: ByteArray) {
        // Write to a temp name first: a half-written blob that later fails to decrypt would be
        // indistinguishable from tampering.
        val temp = File(directory, "$blobId.tmp")
        temp.writeBytes(Aead.seal(key, bytes))
        check(temp.renameTo(fileFor(blobId))) { "could not commit blob $blobId" }
    }

    /** Returns the plaintext, or null if the blob is missing, truncated or not ours. */
    fun get(blobId: String): ByteArray? {
        val file = fileFor(blobId)
        if (!file.isFile) return null
        return try {
            Aead.open(key, file.readBytes())
        } catch (_: java.security.GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun exists(blobId: String): Boolean = fileFor(blobId).isFile

    fun delete(blobId: String): Boolean = fileFor(blobId).delete()

    /** Drops every blob. The caller is responsible for the rows that referenced them. */
    fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(blobId: String) = File(directory, blobId)

    companion object {
        /** Digest of the *plaintext*, so a receiver can verify what it reassembled. */
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
