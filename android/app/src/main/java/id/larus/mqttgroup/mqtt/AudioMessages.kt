package id.larus.mqttgroup.mqtt

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * The plaintext inside an audio envelope: a few Opus frames and enough to put them back in order.
 *
 * Binary rather than the JSON every other codec in this app uses, and that is not premature
 * optimisation. A control message is a few hundred bytes sent now and then; this is seventeen
 * packets a second for the length of a conversation, and base64 inside JSON would add a third to
 * every one of them for no reason a reader will ever benefit from.
 *
 * ```
 * "FGA1" | streamId:u32 | seq:u32 | frameCount:u8 | (len:u16, frame)*
 * ```
 *
 * - [streamId] is minted per call, per speaker. Its job is to make the boundary between two
 *   streams unmistakable: without it, a call that ends and another that starts moments later
 *   look like one stream whose sequence numbers jumped, and the jitter buffer would sit waiting
 *   for packets that are never coming. It is not a call id — this layer does not know about
 *   calls, and does not need to.
 * - [seq] counts packets, not frames, and wraps at 2^32, which at seventeen packets a second is
 *   about eight years of continuous talking. **It is unsigned on the wire and signed here**,
 *   because Kotlin has no unsigned `Int` in a data class worth the friction — the bits are the
 *   same ones the browser reads as a number up to 4294967295, and a packet numbered 0xFFFFFFFE
 *   arrives as -2. Nothing may compare two sequence numbers with `<`; the jitter buffer
 *   subtracts and looks at the sign, which is wrap-safe and is the only correct way to order a
 *   counter that rolls over anyway.
 * - Several frames ride in one packet. An envelope costs 56 bytes of header, nonce and tag, and
 *   one 20ms Opus frame at 24 kbps is 60 — so a packet per frame is very nearly half overhead.
 *   Three frames makes it a fifth, at the cost of losing 60ms of speech rather than 20ms when a
 *   packet goes missing. The format does not care how many there are; that is the sender's
 *   decision and can change without either side minding.
 *
 * There is no timestamp. It would only ever be compared against a different device's clock —
 * the same trap `Call.Offer.ttlSec` avoids — and the sequence number already says everything the
 * jitter buffer needs to know about order and loss.
 */
data class AudioPacket(
    val streamId: Int,
    val seq: Int,
    val frames: List<ByteArray>,
) {
    override fun equals(other: Any?): Boolean = other is AudioPacket &&
        streamId == other.streamId && seq == other.seq &&
        frames.size == other.frames.size &&
        frames.indices.all { frames[it].contentEquals(other.frames[it]) }

    override fun hashCode(): Int =
        (streamId * 31 + seq) * 31 + frames.sumOf { it.contentHashCode() }

    fun encode(): ByteArray {
        require(frames.isNotEmpty()) { "an audio packet with no frames is nothing to send" }
        require(frames.size <= MAX_FRAMES) { "too many frames in one packet: ${frames.size}" }
        frames.forEach {
            require(it.size <= MAX_FRAME_BYTES) { "frame of ${it.size} bytes is not Opus at 24 kbps" }
        }
        val size = HEADER_BYTES + frames.sumOf { 2 + it.size }
        val buffer = ByteBuffer.allocate(size)
            .put(MAGIC)
            .putInt(streamId)
            .putInt(seq)
            .put(frames.size.toByte())
        frames.forEach { buffer.putShort(it.size.toShort()).put(it) }
        return buffer.array()
    }

    companion object {
        private val MAGIC = "FGA1".toByteArray()
        private const val HEADER_BYTES = 4 + 4 + 4 + 1

        /** Generous against what the sender actually packs (three), so the limit is a sanity
         * check on a hostile payload rather than a format decision to revisit. */
        const val MAX_FRAMES = 12

        /** An Opus frame at 24 kbps mono is around 60 bytes; 1500 is absurd and still cheap to
         * refuse. Without a bound, a crafted length field makes this allocate whatever it likes. */
        const val MAX_FRAME_BYTES = 1_500

        /** Returns null for anything malformed, which is a packet to drop. At this rate, dropping
         * is what happens to bad packets anyway — there is no room to complain about one. */
        fun decode(payload: ByteArray): AudioPacket? = try {
            if (payload.size < HEADER_BYTES) {
                null
            } else if (!payload.copyOfRange(0, 4).contentEquals(MAGIC)) {
                null
            } else {
                val buffer = ByteBuffer.wrap(payload, 4, payload.size - 4)
                val streamId = buffer.int
                val seq = buffer.int
                val count = buffer.get().toInt() and 0xFF
                if (count == 0 || count > MAX_FRAMES) {
                    null
                } else {
                    val frames = ArrayList<ByteArray>(count)
                    var ok = true
                    repeat(count) {
                        if (!ok) return@repeat
                        val length = buffer.short.toInt() and 0xFFFF
                        if (length == 0 || length > MAX_FRAME_BYTES || length > buffer.remaining()) {
                            ok = false
                        } else {
                            frames.add(ByteArray(length).also(buffer::get))
                        }
                    }
                    if (ok) AudioPacket(streamId, seq, frames) else null
                }
            }
        } catch (_: BufferUnderflowException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }
}
