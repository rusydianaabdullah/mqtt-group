package id.larus.mqttgroup

import id.larus.mqttgroup.crypto.Envelope
import id.larus.mqttgroup.crypto.Invite
import id.larus.mqttgroup.mqtt.AudioPacket
import id.larus.mqttgroup.mqtt.Call
import id.larus.mqttgroup.mqtt.ChatPayload
import id.larus.mqttgroup.mqtt.Control
import id.larus.mqttgroup.mqtt.LocationPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.Base64

/**
 * The gate that keeps this app and the web app on the same wire.
 *
 * `family-group-pwa` generates `vectors/wire-vectors.json` with its own TypeScript codecs and
 * commits it. This test opens those bytes with the Kotlin ones. Nothing else proves the two
 * agree: both implementations round-trip their own output perfectly while disagreeing about
 * UUID byte order, counter endianness or what the AAD covers, and the first sign of it would be
 * a family member whose messages arrive as nothing at all.
 *
 * Sealed payloads are compared by opening rather than by byte equality on purpose — GCM nonces
 * are random, so two implementations sealing the same plaintext never produce the same bytes.
 * Agreement can only be shown by one side reading what the other wrote.
 *
 * Skipped rather than failed when the web project is not checked out beside this one, since
 * that is a perfectly ordinary way to have this repository.
 */
class WireVectorsTest {

    /**
     * Found by walking up from wherever the test happens to run, rather than by a fixed number
     * of `..` segments.
     *
     * This used to be the literal `../family-group-pwa/vectors/wire-vectors.json`, which reads
     * correctly against the project directory and is wrong against the one Gradle actually
     * runs unit tests in — the `app` module. It therefore resolved to
     * `family-group/family-group-pwa/...`, never matched, and [assumeTrue] turned every test in
     * this class into a skip. A green build, seven skips, and the cross-implementation gate not
     * running at all. Skipping on a missing file is still right — this project is perfectly
     * usable checked out on its own — but it has to be a fact about the checkout rather than
     * about the working directory.
     */
    private val file: File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "family-group-pwa/vectors/wire-vectors.json") }
        .firstOrNull { it.isFile }

    private fun vectors(): JSONObject {
        assumeTrue("family-group-pwa is not checked out beside this project", file != null)
        return JSONObject(file!!.readText())
    }

    private val decoder: Base64.Decoder = Base64.getDecoder()

    @Test
    fun `opens every envelope the web app sealed`() {
        val v = vectors()
        val key = hex(v.getString("keyHex"))
        val envelopes = v.getJSONArray("envelopes")
        for (i in 0 until envelopes.length()) {
            val e = envelopes.getJSONObject(i)
            val payload = decoder.decode(e.getString("sealed"))

            val header = requireNotNull(Envelope.peek(payload)) { "header ${e.getString("plaintextUtf8")}" }
            assertEquals(e.getInt("epoch"), header.epoch)
            assertEquals(e.getString("senderId"), header.senderId.toString())
            // Counters past Long.MAX_VALUE are still eight bytes; compare unsigned so the
            // largest one this format holds does not read as -1.
            assertEquals(
                BigInteger(e.getString("counter")),
                BigInteger(java.lang.Long.toUnsignedString(header.counter)),
            )

            val opened = requireNotNull(Envelope.open(key, payload)) { "open ${e.getString("plaintextUtf8")}" }
            assertEquals(e.getString("plaintextUtf8"), String(opened.plaintext))
        }
    }

    @Test
    fun `builds the same header bytes the web app recorded`() {
        val v = vectors()
        val envelopes = v.getJSONArray("envelopes")
        for (i in 0 until envelopes.length()) {
            val e = envelopes.getJSONObject(i)
            // Sealing with a zero-length plaintext is not the point; the header is, and it is
            // the first HEADER_BYTES of whatever gets sealed.
            val sealed = Envelope.seal(
                key = ByteArray(32),
                epoch = e.getInt("epoch"),
                senderId = java.util.UUID.fromString(e.getString("senderId")),
                counter = BigInteger(e.getString("counter")).toLong(),
                plaintext = ByteArray(0),
            )
            assertEquals(
                e.getString("header"),
                sealed.copyOfRange(0, Envelope.HEADER_BYTES).joinToString("") { "%02x".format(it) },
            )
        }
    }

    @Test
    fun `decodes the web app's invite`() {
        val invite = requireNotNull(Invite.decode(vectors().getString("invite")))
        assertEquals("Keluarga", invite.groupName)
        assertEquals("broker.example", invite.host)
        assertEquals(8883, invite.port)
        assertEquals(true, invite.tls)
        assertEquals("ayah", invite.username)
        assertEquals("rahasia", invite.password)
        assertEquals("familygroup", invite.topicPrefix)
        assertEquals(32, invite.groupKey.size)
    }

    @Test
    fun `decodes the web app's chat payloads`() {
        val chat = vectors().getJSONObject("chat")
        val text = requireNotNull(ChatPayload.decode(decoder.decode(chat.getString("text"))))
        assertEquals(ChatPayload.Text("m-1", "Sudah sampai?"), text)

        val att = ChatPayload.decode(decoder.decode(chat.getString("attachment")))
        assertEquals(
            ChatPayload.Attachment(
                id = "m-2",
                kind = "image",
                blobId = "b-1",
                mime = "image/jpeg",
                sizeBytes = 51234,
                sha256 = "a".repeat(64),
                caption = "di depan rumah",
                replyToId = "m-1",
            ),
            att,
        )
    }

    @Test
    fun `decodes the web app's control payloads`() {
        val control = vectors().getJSONObject("control")
        val profile = Control.decode(decoder.decode(control.getString("profile")))
        assertEquals(
            Control.Profile(
                userId = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                displayName = "Ayah",
                avatar = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
            ),
            profile,
        )
        assertEquals(
            Control.Kick("3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
            Control.decode(decoder.decode(control.getString("kick"))),
        )
        assertEquals(
            Control.LocationSettings(true, 60, 300, 25, 168),
            Control.decode(decoder.decode(control.getString("locset"))),
        )
    }

    @Test
    fun `decodes the web app's call payloads`() {
        val call = vectors().getJSONObject("call")
        assertEquals(
            Call.Offer(
                callId = "c-1",
                toUserId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
                mode = Call.Mode.AUDIO,
                ttlSec = 45,
            ),
            Call.decode(decoder.decode(call.getString("offer"))),
        )
        // The group offer, whose whole point is an absent target rather than an empty one.
        assertEquals(
            Call.Offer("c-2", null, Call.Mode.AUDIO, 45),
            Call.decode(decoder.decode(call.getString("groupOffer"))),
        )
        // A buzz: same shape, different mode. An older build must drop this rather than ring
        // for it as though it were a voice call.
        assertEquals(
            Call.Offer("c-3", null, Call.Mode.BUZZ, 90),
            Call.decode(decoder.decode(call.getString("buzzOffer"))),
        )
        assertEquals(Call.Ring("c-1"), Call.decode(decoder.decode(call.getString("ring"))))
        assertEquals(Call.Accept("c-1"), Call.decode(decoder.decode(call.getString("accept"))))
        assertEquals(
            Call.Decline("c-1", Call.Reason.BUSY),
            Call.decode(decoder.decode(call.getString("declineBusy"))),
        )
        assertEquals(Call.Cancel("c-1"), Call.decode(decoder.decode(call.getString("cancel"))))
        assertEquals(Call.End("c-1"), Call.decode(decoder.decode(call.getString("end"))))
    }

    /**
     * The one binary codec in this app, so this vector proves byte order as well as field order.
     * The sequence number is deliberately past the top of a signed int: the web app writes
     * 4294967294 and this must read the same bits as -2, because a disagreement there would put
     * the two jitter buffers two packets apart with nothing to show for it.
     */
    @Test
    fun `decodes the web app's audio packet`() {
        val packet = requireNotNull(AudioPacket.decode(decoder.decode(vectors().getString("audio"))))
        assertEquals(0x01020304, packet.streamId)
        assertEquals(-2, packet.seq)
        assertEquals(listOf(4, 1, 8), packet.frames.map { it.size })
        assertEquals(
            "cafebabe",
            packet.frames[0].joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `decodes the web app's location payload`() {
        val p = requireNotNull(LocationPayload.decode(decoder.decode(vectors().getString("location"))))
        assertEquals(-6.914744, p.lat, 1e-9)
        assertEquals(107.60981, p.lon, 1e-9)
        assertEquals(12.5f, p.accuracy, 1e-6f)
        assertEquals(1.25f, p.speed, 1e-6f)
        assertEquals(180f, p.bearing, 1e-6f)
        assertEquals(1757404800000L, p.capturedAt)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
