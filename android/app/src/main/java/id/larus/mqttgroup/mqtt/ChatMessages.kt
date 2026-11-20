package id.larus.mqttgroup.mqtt

import org.json.JSONObject

/**
 * The plaintext inside a `/msg` envelope. A message carries its own id rather than relying on
 * the envelope's (sender, counter) pair to name it, because that pair is meaningless to the
 * sender's own copy — the id is what lets a future edit or delete, or a reply, refer back to a
 * specific message from any phone.
 *
 * An attachment message is a reference, not the bytes: those go separately on the group's
 * `/att/<blobId>` topic, sealed the same way, so a phone that only wants to show "so-and-so sent
 * a photo" does not have to download every image and voice note to render the chat list. [kind]
 * is "image" or "audio" — what [id.larus.mqttgroup.data.db.MessageEntity.type] wants directly,
 * so nothing downstream has to translate one vocabulary into another.
 */
sealed interface ChatPayload {
    val id: String

    /** The message this one replies to, by id. A reply to something the receiving phone never
     * got — a different epoch, a message still in flight — is still worth showing; the id is
     * carried regardless of whether this phone can resolve it to anything. */
    val replyToId: String?

    data class Text(
        override val id: String,
        val body: String,
        override val replyToId: String? = null,
    ) : ChatPayload

    data class Attachment(
        override val id: String,
        val kind: String,
        val blobId: String,
        val mime: String,
        val sizeBytes: Long,
        val sha256: String,
        /** A photo's caption, typed before sending. Voice notes never have one. */
        val caption: String?,
        override val replyToId: String? = null,
    ) : ChatPayload

    fun encode(): ByteArray {
        val json = JSONObject().put(ID, id).put(TYPE, typeName())
        replyToId?.let { json.put(REPLY, it) }
        when (this) {
            is Text -> json.put(BODY, body)
            is Attachment -> {
                json.put(KIND, kind).put(BLOB, blobId).put(MIME, mime).put(SIZE, sizeBytes).put(SHA, sha256)
                caption?.let { json.put(CAPTION, it) }
            }
        }
        return json.toString().toByteArray()
    }

    private fun typeName(): String = when (this) {
        is Text -> "text"
        is Attachment -> "attachment"
    }

    companion object {
        private const val ID = "id"
        private const val TYPE = "t"
        private const val BODY = "b"
        private const val KIND = "k"
        private const val BLOB = "blob"
        private const val MIME = "mime"
        private const val SIZE = "size"
        private const val SHA = "sha"
        private const val CAPTION = "cap"
        private const val REPLY = "r"

        /** Returns null for anything malformed: a message no phone can parse is one to drop. */
        fun decode(payload: ByteArray): ChatPayload? = try {
            val json = JSONObject(String(payload))
            val id = json.getString(ID)
            val replyToId = json.optString(REPLY).ifEmpty { null }
            when (json.getString(TYPE)) {
                "text" -> Text(id, json.getString(BODY), replyToId)
                "attachment" -> Attachment(
                    id = id,
                    kind = json.getString(KIND),
                    blobId = json.getString(BLOB),
                    mime = json.getString(MIME),
                    sizeBytes = json.getLong(SIZE),
                    sha256 = json.getString(SHA),
                    caption = json.optString(CAPTION).ifEmpty { null },
                    replyToId = replyToId,
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
