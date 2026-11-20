package id.larus.mqttgroup.crypto

import java.nio.ByteBuffer
import java.util.UUID

/**
 * The single wire format. Every byte this app publishes to every topic looks like this, so
 * there is exactly one decrypt path to get right:
 *
 * ```
 * "FG1" | epoch:u8 | senderId:16 | counter:u64 | nonce:12 | ciphertext+tag
 * └──────────── header, authenticated as AAD ────────────┘
 * ```
 *
 * The header travels in the clear because a receiver needs it to pick a key and to reject a
 * replay before it can decrypt anything. Binding it as AAD means a broker that rewrites the
 * sender or rewinds the counter produces a payload that no longer opens.
 *
 * [epoch] is the group-key generation. It is 0 for every group created today; it exists so
 * that rotating a key on kick becomes a new epoch rather than a new wire format.
 */
data class Envelope(
    val epoch: Int,
    val senderId: UUID,
    val counter: Long,
    val plaintext: ByteArray,
) {
    // Data classes compare ByteArray by identity, which would make two equal payloads unequal.
    override fun equals(other: Any?): Boolean = other is Envelope &&
        epoch == other.epoch && senderId == other.senderId && counter == other.counter &&
        plaintext.contentEquals(other.plaintext)

    override fun hashCode(): Int =
        ((epoch * 31 + senderId.hashCode()) * 31 + counter.hashCode()) * 31 + plaintext.contentHashCode()

    companion object {
        private val MAGIC = byteArrayOf('F'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
        const val HEADER_BYTES = 3 + 1 + 16 + 8

        fun seal(key: ByteArray, epoch: Int, senderId: UUID, counter: Long, plaintext: ByteArray): ByteArray {
            require(epoch in 0..255) { "epoch must fit in a byte, got $epoch" }
            val header = ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC)
                .put(epoch.toByte())
                .putLong(senderId.mostSignificantBits)
                .putLong(senderId.leastSignificantBits)
                .putLong(counter)
                .array()
            return header + Aead.seal(key, plaintext, aad = header)
        }

        /**
         * Reads the header without decrypting, so a receiver can look up the right key for the
         * epoch — and drop a replayed counter — before spending any work on the body.
         */
        fun peek(payload: ByteArray): Header? {
            if (payload.size < HEADER_BYTES + Aead.NONCE_BYTES + Aead.TAG_BYTES) return null
            if (!payload.copyOfRange(0, 3).contentEquals(MAGIC)) return null
            val buffer = ByteBuffer.wrap(payload, 3, HEADER_BYTES - 3)
            val epoch = buffer.get().toInt() and 0xFF
            val sender = UUID(buffer.long, buffer.long)
            return Header(epoch, sender, buffer.long)
        }

        /** Returns null for anything that is not a well-formed envelope this key can open. */
        fun open(key: ByteArray, payload: ByteArray): Envelope? {
            val header = peek(payload) ?: return null
            val plaintext = try {
                Aead.open(
                    key,
                    payload.copyOfRange(HEADER_BYTES, payload.size),
                    aad = payload.copyOfRange(0, HEADER_BYTES),
                )
            } catch (_: java.security.GeneralSecurityException) {
                // Wrong key, wrong epoch, or a tampered payload. All the same to us: drop it.
                return null
            }
            return Envelope(header.epoch, header.senderId, header.counter, plaintext)
        }
    }

    data class Header(val epoch: Int, val senderId: UUID, val counter: Long)
}
