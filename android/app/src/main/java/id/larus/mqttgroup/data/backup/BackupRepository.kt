package id.larus.mqttgroup.data.backup

import android.util.Base64
import id.larus.mqttgroup.crypto.KeyVault
import id.larus.mqttgroup.data.db.AppDatabase
import id.larus.mqttgroup.data.db.BrokerEntity
import id.larus.mqttgroup.data.db.GroupEntity
import id.larus.mqttgroup.data.db.GroupKeyEntity
import id.larus.mqttgroup.data.db.IdentityEntity
import id.larus.mqttgroup.data.db.MemberEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes and reads the encrypted backup.
 *
 * ### What is in it, and what is not
 *
 * Identity, brokers, groups with their settings, every epoch of every group key, and the member
 * lists. That is what it takes to *be* you on a new phone: rejoin the same groups on the same
 * brokers and read what arrives.
 *
 * Not messages, not location history, not attachment blobs. Those are the bulk — blobs alone can
 * be hundreds of megabytes — and unlike the keys they are not irreplaceable: a broker holding
 * a session replays what was queued, and the group carries on. A backup nobody makes because it
 * takes ten minutes and fills the phone protects nothing.
 *
 * ### Why the keys are unwrapped on the way out
 *
 * Group keys live in the database wrapped by [KeyVault], whose master key is in this phone's
 * hardware Keystore and cannot leave it. A backup of the wrapped bytes would restore to a phone
 * that cannot unwrap them — it would look like a backup, right up until the moment it was
 * needed. So they are unwrapped here and re-wrapped on the way in, which means the plaintext
 * group keys really are inside that file, and the passphrase really is the only thing protecting
 * them. The UI says so.
 */
class BackupRepository(
    private val db: AppDatabase,
    private val vault: KeyVault,
) {

    /** What a restore did, for a screen that should say more than "done". */
    data class Restored(val groups: Int, val brokers: Int, val keys: Int, val identityReplaced: Boolean)

    suspend fun export(passphrase: CharArray): ByteArray {
        val payload = JSONObject().apply {
            put(VERSION, BackupArchive.VERSION)
            put(CREATED_AT, System.currentTimeMillis())
            db.identity().get()?.let { put(IDENTITY, identityJson(it)) }
            put(BROKERS, JSONArray(db.brokers().all().map(::brokerJson)))
            put(GROUPS, JSONArray(db.groups().all().map(::groupJson)))
            put(MEMBERS, JSONArray(db.members().all().map(::memberJson)))
            put(KEYS, JSONArray(db.groups().allKeys().mapNotNull(::keyJson)))
        }
        return BackupArchive.seal(payload, passphrase)
    }

    /**
     * Merges a backup into whatever is already here.
     *
     * Merge, not replace: someone restoring onto a phone that is already in a group should not
     * lose it, and the ids are UUIDs, so a collision means the same thing rather than a
     * different one. The identity is the exception — there can only be one, and a restore is
     * being asked for precisely because it is the identity that was lost.
     */
    suspend fun import(bytes: ByteArray, passphrase: CharArray): Result<Restored> {
        val payload = BackupArchive.open(bytes, passphrase).getOrElse { return Result.failure(it) }

        var identityReplaced = false
        payload.optJSONObject(IDENTITY)?.let { json ->
            db.identity().put(
                IdentityEntity(
                    userId = json.getString("userId"),
                    displayName = json.getString("displayName"),
                    // Avatars live in the blob store, which this backup does not carry. Left
                    // null rather than pointing at a blob id that is not there, which would show
                    // as a permanently broken image instead of the initial the app falls back to.
                    avatarBlobId = null,
                    createdAt = json.getLong("createdAt"),
                ),
            )
            identityReplaced = true
        }

        val brokers = payload.optJSONArray(BROKERS).objects()
        brokers.forEach { db.brokers().put(broker(it)) }

        val groups = payload.optJSONArray(GROUPS).objects()
        groups.forEach { db.groups().put(group(it)) }

        payload.optJSONArray(MEMBERS).objects().forEach { db.members().put(member(it)) }

        val keys = payload.optJSONArray(KEYS).objects()
        keys.forEach { json ->
            db.groups().putKey(
                GroupKeyEntity(
                    groupId = json.getString("groupId"),
                    epoch = json.getInt("epoch"),
                    // Re-wrapped under *this* phone's Keystore master key, which is the whole
                    // reason the export unwrapped them.
                    wrappedKey = vault.wrap(json.getString("key").decodeBase64()),
                    createdAt = json.getLong("createdAt"),
                ),
            )
        }

        return Result.success(
            Restored(
                groups = groups.size,
                brokers = brokers.size,
                keys = keys.size,
                identityReplaced = identityReplaced,
            ),
        )
    }

    // --- Row <-> JSON -------------------------------------------------------------------------

    private fun identityJson(row: IdentityEntity) = JSONObject().apply {
        put("userId", row.userId)
        put("displayName", row.displayName)
        put("createdAt", row.createdAt)
    }

    private fun brokerJson(row: BrokerEntity) = JSONObject().apply {
        put("id", row.id)
        put("label", row.label)
        put("host", row.host)
        put("port", row.port)
        put("tls", row.tls)
        put("allowInsecureTls", row.allowInsecureTls)
        put("username", row.username ?: JSONObject.NULL)
        put("password", row.password ?: JSONObject.NULL)
        put("topicPrefix", row.topicPrefix)
        put("createdAt", row.createdAt)
    }

    private fun broker(json: JSONObject) = BrokerEntity(
        id = json.getString("id"),
        label = json.getString("label"),
        host = json.getString("host"),
        port = json.getInt("port"),
        tls = json.getBoolean("tls"),
        allowInsecureTls = json.getBoolean("allowInsecureTls"),
        username = json.optStringOrNull("username"),
        password = json.optStringOrNull("password"),
        topicPrefix = json.getString("topicPrefix"),
        createdAt = json.getLong("createdAt"),
    )

    private fun groupJson(row: GroupEntity) = JSONObject().apply {
        put("groupId", row.groupId)
        put("name", row.name)
        put("brokerId", row.brokerId)
        put("topicPrefix", row.topicPrefix)
        put("keyEpoch", row.keyEpoch)
        put("isAdmin", row.isAdmin)
        put("createdAt", row.createdAt)
        // The send counter travels. Restoring to zero would make every message this phone sends
        // look like a replay of one already applied, and every other member would drop them —
        // silently, and for as long as it took to catch up to where the counter had been.
        put("sendCounter", row.sendCounter)
        put("locationEnabled", row.locationEnabled)
        put("locSampleSec", row.locSampleSec)
        put("locPushSec", row.locPushSec)
        put("locMinDistanceM", row.locMinDistanceM)
        put("locRetentionHours", row.locRetentionHours)
        put("shareLocation", row.shareLocation)
    }

    private fun group(json: JSONObject) = GroupEntity(
        groupId = json.getString("groupId"),
        name = json.getString("name"),
        brokerId = json.getString("brokerId"),
        topicPrefix = json.getString("topicPrefix"),
        keyEpoch = json.getInt("keyEpoch"),
        isAdmin = json.getBoolean("isAdmin"),
        createdAt = json.getLong("createdAt"),
        sendCounter = json.optLong("sendCounter", 0),
        locationEnabled = json.optBoolean("locationEnabled", false),
        locSampleSec = json.optInt("locSampleSec", 60),
        locPushSec = json.optInt("locPushSec", 300),
        locMinDistanceM = json.optInt("locMinDistanceM", 25),
        locRetentionHours = json.optInt("locRetentionHours", 168),
        shareLocation = json.optBoolean("shareLocation", false),
    )

    private fun memberJson(row: MemberEntity) = JSONObject().apply {
        put("groupId", row.groupId)
        put("userId", row.userId)
        put("displayName", row.displayName)
        put("isAdmin", row.isAdmin)
        put("lastSeenAt", row.lastSeenAt)
        put("isRemoved", row.isRemoved)
    }

    private fun member(json: JSONObject) = MemberEntity(
        groupId = json.getString("groupId"),
        userId = json.getString("userId"),
        displayName = json.getString("displayName"),
        avatarBlobId = null,
        isAdmin = json.getBoolean("isAdmin"),
        lastSeenAt = json.getLong("lastSeenAt"),
        isRemoved = json.optBoolean("isRemoved", false),
    )

    /** Null for a key the vault cannot open, rather than a crash: one unreadable row should cost
     * that group's history, not the whole backup. */
    private fun keyJson(row: GroupKeyEntity): JSONObject? {
        val plain = runCatching { vault.unwrap(row.wrappedKey) }.getOrNull() ?: return null
        return JSONObject().apply {
            put("groupId", row.groupId)
            put("epoch", row.epoch)
            put("key", plain.encodeBase64())
            put("createdAt", row.createdAt)
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val VERSION = "version"
        const val CREATED_AT = "createdAt"
        const val IDENTITY = "identity"
        const val BROKERS = "brokers"
        const val GROUPS = "groups"
        const val MEMBERS = "members"
        const val KEYS = "keys"
    }
}
