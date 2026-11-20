package id.larus.mqttgroup.mqtt

/**
 * The topic layout, in one place because both ends of every feature depend on agreeing about it.
 *
 * ```
 * <prefix>/<groupId>/msg                chat, QoS 1
 * <prefix>/<groupId>/ctl                join, leave, kick, profile — QoS 1
 * <prefix>/<groupId>/set                the group's location settings, QoS 1, retained
 * <prefix>/<groupId>/call               call signalling, QoS 1, never retained
 * <prefix>/<groupId>/aud/<userId>       voice, QoS 0, never retained
 * <prefix>/<groupId>/att/<blobId>       attachment chunks, QoS 1
 * <prefix>/<groupId>/loc/<userId>       latest position, QoS 0, retained
 * <prefix>/<groupId>/presence/<userId>  online flag, QoS 1, retained, cleared by the will
 * ```
 *
 * Retaining the settings alongside presence and location is what removes the need for a server: a phone that has been
 * offline for a week learns who is around and where they were the moment it subscribes, instead
 * of waiting for everyone else to publish again.
 *
 * Topic names are visible to the broker. That is the one thing this design leaks — who talks to
 * whom and when, though never what — and it is why the prefix is the user's to choose.
 */
object Topics {

    /**
     * The last level of a topic that says what kind of thing is on it.
     *
     * Named constants rather than literals at each call site, because the receive side has to
     * recognise exactly the set the send side produces — and when those were two independent
     * lists, adding `call` to one and not the other silently dropped every call message between
     * two phones. Nothing rang, nothing failed, and nothing was logged.
     */
    const val KIND_MESSAGES = "msg"
    const val KIND_CONTROL = "ctl"
    const val KIND_SETTINGS = "set"
    const val KIND_CALL = "call"
    const val KIND_AUDIO = "aud"
    const val KIND_ATTACHMENT = "att"
    const val KIND_LOCATION = "loc"
    const val KIND_PRESENCE = "presence"

    /**
     * Every kind this app publishes, and the only thing [id.larus.mqttgroup.mqtt.GroupSync] will
     * route. A topic whose kind is missing here is not an error anywhere — it is simply never
     * delivered, which is why this lives beside the builders that produce them and is covered by
     * a test that walks every one of them.
     */
    val KINDS = setOf(
        KIND_MESSAGES,
        KIND_CONTROL,
        KIND_SETTINGS,
        KIND_CALL,
        KIND_AUDIO,
        KIND_ATTACHMENT,
        KIND_LOCATION,
        KIND_PRESENCE,
    )

    /** The kind of a topic, or null for one this build does not recognise. */
    fun kindOf(topic: String): String? = topic.split('/').firstOrNull { it in KINDS }

    /**
     * The group a topic belongs to. The level before the kind, because a prefix may itself
     * contain slashes and counting from the front would misread `home/family/<id>/msg`.
     */
    fun groupIdOf(topic: String): String? {
        val parts = topic.split('/')
        val at = parts.indexOfFirst { it in KINDS }
        return if (at > 0) parts[at - 1] else null
    }

    fun group(prefix: String, groupId: String) = "${normalise(prefix)}/$groupId"

    fun messages(prefix: String, groupId: String) = "${group(prefix, groupId)}/$KIND_MESSAGES"

    fun control(prefix: String, groupId: String) = "${group(prefix, groupId)}/$KIND_CONTROL"

    /**
     * The group's settings, retained, so that they are a *state* and not an event. On /ctl alone
     * they were an announcement that only reached whoever was subscribed at the moment the admin
     * made it — which is never the member who joins tomorrow, and that member then sat looking at
     * "location sharing is off for this group" while everyone else was on the map.
     */
    fun settings(prefix: String, groupId: String) = "${group(prefix, groupId)}/$KIND_SETTINGS"

    /**
     * Call signalling, and the one topic here that must **never** be retained.
     *
     * Everything else on this list is either an event worth replaying or a state worth keeping,
     * which is why `set`, `loc` and `presence` are retained: a phone that has been offline for a
     * week learns the group's settings and everyone's last position the moment it subscribes. A
     * retained ring is that same mechanism producing the worst possible result — a phone that
     * rings on every resubscribe for a call that ended last Tuesday.
     *
     * Separate from `ctl` rather than more [Control] cases because these expire and those do
     * not. Nothing new is subscribed for it: clients already take [everything].
     */
    fun call(prefix: String, groupId: String) = "${group(prefix, groupId)}/$KIND_CALL"

    /**
     * Voice, at QoS 0 — and the zero is the point.
     *
     * QoS 1 would have the broker redeliver a packet that was late, and a late audio packet is
     * not one that was missed: the jitter buffer has already played silence over its moment.
     * Redelivering it spends radio to deliver noise. Everything else in this app wants delivery
     * guaranteed; this wants it abandoned.
     *
     * Per sender, like [location], so that two people talking at once are two streams rather
     * than one interleaved mess — and so the group case needs no new format when it comes.
     */
    fun audio(prefix: String, groupId: String, userId: String) =
        "${group(prefix, groupId)}/$KIND_AUDIO/$userId"

    fun attachment(prefix: String, groupId: String, blobId: String) =
        "${group(prefix, groupId)}/$KIND_ATTACHMENT/$blobId"

    fun location(prefix: String, groupId: String, userId: String) =
        "${group(prefix, groupId)}/$KIND_LOCATION/$userId"

    fun presence(prefix: String, groupId: String, userId: String) =
        "${group(prefix, groupId)}/$KIND_PRESENCE/$userId"

    /** Everything for one group, which is the only subscription a client ever needs. */
    fun everything(prefix: String, groupId: String) = "${group(prefix, groupId)}/#"

    /**
     * MQTT forbids a leading or trailing slash from meaning what people expect, and an empty
     * level is legal but confusing, so a prefix is trimmed to its useful middle.
     */
    fun normalise(prefix: String): String =
        prefix.trim().trim('/').ifEmpty { DEFAULT_PREFIX }

    /**
     * A prefix has to survive being pasted into a topic filter, so the wildcards and the level
     * separator are out, along with whitespace.
     */
    fun isValidPrefix(prefix: String): Boolean {
        val trimmed = prefix.trim().trim('/')
        return trimmed.isNotEmpty() &&
            trimmed.none { it == '#' || it == '+' || it.isWhitespace() } &&
            !trimmed.contains("//")
    }

    const val DEFAULT_PREFIX = "familygroup"
}
