package id.larus.mqttgroup.crypto

import org.json.JSONObject
import java.util.Base64

/**
 * What a QR code contains: everything a phone needs to join a group and nothing it could get
 * anywhere else.
 *
 * This *is* the group secret. Whoever photographs this code can read the group — there is no
 * server to also authenticate against, which is the whole point and also the whole risk. It
 * should be shown in person, not messaged.
 *
 * The broker settings ride along so a joiner is not asked to type a host and password they
 * would have to be told separately. The encoding is base64url of compact JSON behind a version
 * tag, so a scanner can reject a code from a future format instead of misreading it.
 */
data class Invite(
    val groupId: String,
    val groupName: String,
    val adminId: String,
    val epoch: Int,
    val groupKey: ByteArray,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val allowInsecureTls: Boolean,
    val username: String?,
    val password: String?,
    val topicPrefix: String,
) {
    fun encode(): String {
        val json = JSONObject()
            .put(GROUP_ID, groupId)
            .put(NAME, groupName)
            .put(ADMIN, adminId)
            .put(EPOCH, epoch)
            .put(KEY, encoder.encodeToString(groupKey))
            .put(HOST, host)
            .put(PORT, port)
            .put(TLS, tls)
            .put(INSECURE, allowInsecureTls)
            .put(PREFIX, topicPrefix)
        username?.let { json.put(USER, it) }
        password?.let { json.put(PASS, it) }
        return PREAMBLE + encoder.encodeToString(json.toString().toByteArray())
    }

    override fun equals(other: Any?): Boolean = other is Invite &&
        groupId == other.groupId && groupName == other.groupName && adminId == other.adminId &&
        epoch == other.epoch && groupKey.contentEquals(other.groupKey) && host == other.host &&
        port == other.port && tls == other.tls && allowInsecureTls == other.allowInsecureTls &&
        username == other.username && password == other.password && topicPrefix == other.topicPrefix

    override fun hashCode(): Int = groupId.hashCode() * 31 + groupKey.contentHashCode()

    companion object {
        const val PREAMBLE = "FG1:"

        private const val GROUP_ID = "g"
        private const val NAME = "n"
        private const val ADMIN = "a"
        private const val EPOCH = "e"
        private const val KEY = "k"
        private const val HOST = "h"
        private const val PORT = "p"
        private const val TLS = "t"
        private const val INSECURE = "i"
        private const val USER = "u"
        private const val PASS = "w"
        private const val PREFIX = "x"

        private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder: Base64.Decoder = Base64.getUrlDecoder()

        /**
         * Returns null for anything that is not one of our invites — a URL, a Wi-Fi QR, a
         * truncated scan, a code from a newer version. The scanner points at whatever the user
         * happens to aim it at, so this is the common path, not the error path.
         */
        fun decode(scanned: String): Invite? {
            val trimmed = scanned.trim()
            if (!trimmed.startsWith(PREAMBLE)) return null
            return try {
                val json = JSONObject(String(decoder.decode(trimmed.removePrefix(PREAMBLE))))
                val key = decoder.decode(json.getString(KEY))
                // A short key would mean a corrupt or hostile code, and letting it through
                // would fail later somewhere far less obvious.
                if (key.size != Aead.KEY_BYTES) return null
                Invite(
                    groupId = json.getString(GROUP_ID),
                    groupName = json.getString(NAME),
                    adminId = json.getString(ADMIN),
                    epoch = json.getInt(EPOCH),
                    groupKey = key,
                    host = json.getString(HOST),
                    port = json.getInt(PORT),
                    tls = json.getBoolean(TLS),
                    allowInsecureTls = json.optBoolean(INSECURE, false),
                    username = json.optString(USER).ifEmpty { null },
                    password = json.optString(PASS).ifEmpty { null },
                    topicPrefix = json.getString(PREFIX),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
