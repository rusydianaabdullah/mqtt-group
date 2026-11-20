package id.larus.mqttgroup.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.larus.mqttgroup.AppContainer
import id.larus.mqttgroup.FamilyGroupApp
import id.larus.mqttgroup.crypto.Invite
import id.larus.mqttgroup.data.HistoryRange
import id.larus.mqttgroup.data.backup.BackupArchive
import id.larus.mqttgroup.data.backup.BackupRepository
import id.larus.mqttgroup.data.db.BrokerEntity
import id.larus.mqttgroup.data.db.GroupEntity
import id.larus.mqttgroup.data.db.IdentityEntity
import id.larus.mqttgroup.data.db.LocationEntity
import id.larus.mqttgroup.data.db.MemberEntity
import id.larus.mqttgroup.data.db.MessageEntity
import id.larus.mqttgroup.data.db.MessageReceiptEntity
import id.larus.mqttgroup.data.repo.BrokerRepository
import id.larus.mqttgroup.map.MapLayer
import id.larus.mqttgroup.mqtt.Call
import id.larus.mqttgroup.mqtt.ConnectionState
import id.larus.mqttgroup.mqtt.Control
import id.larus.mqttgroup.mqtt.MqttService
import id.larus.mqttgroup.push.PushSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the shell needs to decide which screen to show, and to run the destructive actions. */
/** What the Settings screen's backup card is showing. */
sealed interface BackupState {
    data object Idle : BackupState
    data object Working : BackupState
    data class Exported(val bytes: Int) : BackupState
    data class Imported(val summary: BackupRepository.Restored) : BackupState
    data class Failed(val reason: String) : BackupState
}

sealed interface Startup {
    data object Loading : Startup
    /** No profile yet: first launch, or the moment after a wipe. */
    data object NeedsProfile : Startup
    data class Ready(val identity: IdentityEntity) : Startup
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as FamilyGroupApp).container

    val startup: StateFlow<Startup> = container.identity.observe()
        .map { identity ->
            when {
                identity == null || identity.displayName.isBlank() -> Startup.NeedsProfile
                else -> Startup.Ready(identity)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Startup.Loading)

    /** Every saved broker. A group names one of these; several groups may name the same one. */
    val brokers: StateFlow<List<BrokerEntity>> = container.brokers.observeAll()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The first saved broker, which is all the pre-phase-7 screens ever needed. Still the
     * sensible default to offer, and still what "is any broker configured at all" means. */
    val broker: StateFlow<BrokerEntity?> = container.brokers.observeActive()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One state for a status line that has room for one — pessimistic across every broker in
     * use. Which broker is which is a per-group question; see [brokerStates]. */
    val connection: StateFlow<ConnectionState> = container.connections.aggregate

    /** Each broker's own state, so a group screen can say that *its* broker is the one down. */
    val brokerStates: StateFlow<Map<String, ConnectionState>> = container.connections.states

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult

    val groups: StateFlow<List<GroupEntity>> = container.groups.observeAll()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val openGroupId = MutableStateFlow<String?>(null)

    /** Members of whichever group is open, or empty when none is. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val members: StateFlow<List<MemberEntity>> = openGroupId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else container.groups.observeMembers(id) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Messages of whichever group is open, or empty when none is. Newest first. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<MessageEntity>> = openGroupId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else container.groups.observeMessages(id) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Delivery/read receipts for whichever group is open, or empty when none is. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val receipts: StateFlow<List<MessageReceiptEntity>> = openGroupId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else container.groups.observeReceipts(id) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** groupId -> how many messages in it are unread, for the badge on the groups list. A group
     * with nothing unread is simply absent from the map. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val unreadCounts: StateFlow<Map<String, Int>> = startup
        .flatMapLatest { state ->
            if (state is Startup.Ready) container.groups.observeUnreadCounts(state.identity.userId) else flowOf(emptyMap())
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // --- Location ------------------------------------------------------------------------------

    /** Whose pin is selected on the map, or null. Held here rather than in the composable so it
     * survives a rotation and, more usefully, so the trail query below can key off it. */
    private val _selectedMapUser = MutableStateFlow<String?>(null)
    val selectedMapUser: StateFlow<String?> = _selectedMapUser

    /** Everyone's latest position in whichever group is open. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val locations: StateFlow<List<LocationEntity>> = openGroupId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else container.groups.observeLatestLocations(id)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether the map is showing history at all.
     *
     * Off by default, and the map is a live one: where everyone is *now* is the question people
     * open it to answer, and paths drawn underneath that permanently would be clutter obscuring
     * the answer. Turning it on is a deliberate act, which also means the range query below only
     * runs for someone who asked for it.
     */
    private val _historyVisible = MutableStateFlow(false)
    val historyVisible: StateFlow<Boolean> = _historyVisible

    fun setHistoryVisible(visible: Boolean) {
        _historyVisible.value = visible
    }

    /** Which slice of the past the map is drawing. See [HistoryRange]. */
    private val _historyRange = MutableStateFlow(HistoryRange.DEFAULT)
    val historyRange: StateFlow<HistoryRange> = _historyRange

    /**
     * Every member's track inside the chosen range, keyed by user, each oldest-first.
     *
     * One query for the whole group rather than one per person: the map draws all of them at
     * once, and separate flows would redraw it once per member for what is a single change. The
     * selected person's path is picked out of this map rather than fetched again.
     *
     * "Now" is re-read once a minute rather than once when the range was chosen. Without that,
     * a relative range is resolved to fixed milliseconds at the moment it is tapped and then
     * silently grows: leave the map open for an hour on "Last hour" and it is showing two. A
     * minute is finer than any of the presets' own resolution and coarse enough that the requery
     * is invisible; a fixed range like a picked date ignores the tick entirely, since re-running
     * its query would produce byte-identical rows.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val paths: StateFlow<Map<String, List<LocationEntity>>> =
        combine(openGroupId, _historyRange, _historyVisible, minuteTicks()) { groupId, range, visible, _ ->
            Triple(groupId, range, visible)
        }
            .distinctUntilChanged { old, new ->
                // Re-resolve only when something, or the window it produces, actually moved. A
                // Custom range produces the same window on every tick, so this drops the tick
                // entirely for it — and while history is hidden the window is never consulted,
                // so the ticks stop mattering at all.
                old == new && (!old.third || old.second.window() == new.second.window())
            }
            .flatMapLatest { (groupId, range, visible) ->
                if (groupId == null || !visible) {
                    flowOf(emptyMap())
                } else {
                    val window = range.window()
                    container.groups
                        .observeHistoryRange(groupId, window.first, window.last)
                        // groupBy, not sortedBy: the query already returns them ordered by user
                        // and then by time, so this only has to split the list.
                        .map { rows -> rows.groupBy { it.userId }.mapValues { decimate(it.value) } }
                }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Thins a path to at most [MAX_PATH_POINTS], keeping the first and last.
     *
     * A week at the shortest push interval this app allows is tens of thousands of fixes per
     * person, and the map draws every member at once — geometry enough to make panning stutter,
     * for a line whose shape stops changing long before that. Evenly spaced sampling rather than
     * a distance-based simplification: it is one pass with no allocation per point, and at these
     * ratios the difference is invisible at any zoom a phone screen offers.
     *
     * Deliberately not applied to the database or to what is drawn as *positions* — the pins and
     * the selected person's detail card still come from the real rows. This only decides how
     * many vertices a line is worth.
     */
    private fun decimate(fixes: List<LocationEntity>): List<LocationEntity> {
        if (fixes.size <= MAX_PATH_POINTS) return fixes
        val step = fixes.size.toDouble() / (MAX_PATH_POINTS - 1)
        val thinned = ArrayList<LocationEntity>(MAX_PATH_POINTS)
        var i = 0.0
        while (i < fixes.size - 1) {
            thinned.add(fixes[i.toInt()])
            i += step
        }
        thinned.add(fixes.last())
        return thinned
    }

    /** A bare heartbeat for [paths], so a relative range keeps meaning what it says. Emits
     * immediately so the first query does not wait a minute for its window. */
    private fun minuteTicks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    fun setHistoryRange(range: HistoryRange) {
        _historyRange.value = range
    }

    fun selectMapUser(userId: String?) {
        _selectedMapUser.value = userId
    }

    /** The admin's rules for the group, applied here and published to everyone else. */
    fun saveLocationSettings(groupId: String, settings: Control.LocationSettings) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.setLocationSettings(groupId, settings)
            MqttService.wake(getApplication())
        }
    }

    /**
     * This phone's own consent. Starting the service matters as much as the flag: sharing that
     * only worked while the app was open would be a promise the map visibly breaks the moment
     * anyone locks their screen.
     */
    fun setShareLocation(groupId: String, share: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.setShareLocation(groupId, share)
            if (share) MqttService.start(getApplication())
        }
    }

    /** Erases stored positions. [userId] null means the whole group's, otherwise one person's —
     * and only from this phone, since everyone holds their own copy of everyone's history. */
    fun deleteLocationHistory(groupId: String, userId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.deleteLocationHistory(groupId, userId)
        }
    }

    private val _inviteCode = MutableStateFlow<String?>(null)
    val inviteCode: StateFlow<String?> = _inviteCode

    private val _joinError = MutableStateFlow<String?>(null)
    val joinError: StateFlow<String?> = _joinError

    private val _avatar = MutableStateFlow<ByteArray?>(null)
    /** The avatar as raw bytes, decrypted on demand — blobs never sit decrypted on disk. */
    val avatar: StateFlow<ByteArray?> = _avatar

    init {
        // Generating the user id is the first thing that happens on a new install.
        viewModelScope.launch(Dispatchers.IO) {
            val identity = container.identity.ensure()
            identity.avatarBlobId?.let { _avatar.value = container.identity.avatar(it) }
        }
    }

    /**
     * Changes this phone's name or picture, and tells every group about it.
     *
     * The announcement is the part that was missing: writing the identity row locally changed
     * what *this* phone displayed and nothing else, so a rename was invisible to everyone else
     * until some unrelated event happened to trigger an announcement — in practice, until the
     * other person reinstalled. Found by renaming a profile on one phone and watching the other
     * keep showing the old name indefinitely.
     */
    fun saveProfile(name: String, avatarBytes: ByteArray?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (avatarBytes != null) {
                container.identity.setAvatar(avatarBytes)
                _avatar.value = avatarBytes
            }
            container.identity.setDisplayName(name)
            // Every group, because the profile is one identity shared across all of them. These
            // queue in the outbox, so this costs nothing while offline and goes out on reconnect.
            container.groups.observeAll().first().forEach { group ->
                container.groups.announceSelf(group.groupId)
            }
            MqttService.start(getApplication())
        }
    }

    /**
     * Opens a group: remembers which one, and prepares its invite code so the QR is ready
     * rather than appearing a moment late.
     */
    fun openGroup(groupId: String?) {
        openGroupId.value = groupId
        _inviteCode.value = null
        if (groupId == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val broker = container.brokers.observeActive().first() ?: return@launch
            _inviteCode.value = container.groups.invite(groupId, broker)?.encode()
        }
    }

    /** [brokerId] is the broker this group will live on — an existing one, which is how a group
     * "copies" another group's settings, or one just saved from the broker editor. */
    fun createGroup(name: String, brokerId: String?, onCreated: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val broker = brokerId?.let { container.brokers.get(it) }
                ?: container.brokers.observeActive().first()
                ?: return@launch
            val group = container.groups.create(name, broker)
            // Announce immediately: it queues in the outbox, so it goes out whenever the
            // connection next allows, and other members see us without us doing anything else.
            container.groups.announceSelf(group.groupId)
            MqttService.start(getApplication())
            withContext(Dispatchers.Main) { onCreated(group.groupId) }
        }
    }

    /**
     * Handles a scanned code. A scanner points at whatever the user aims it at, so an
     * unrecognised code is an ordinary outcome to explain, not an error to swallow.
     */
    fun joinFromScan(scanned: String, onJoined: (String) -> Unit) {
        val invite = Invite.decode(scanned)
        if (invite == null) {
            _joinError.value = "That is not a Family Group invite."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // The invite carries the broker, so a joiner never has to be told a host and
            // password separately. An existing matching broker is reused rather than duplicated.
            val existing = container.brokers.observeAll().first()
                .firstOrNull { it.host == invite.host && it.port == invite.port }
            val broker = existing ?: BrokerRepository.suggestion().copy(
                label = invite.host,
                host = invite.host,
                port = invite.port,
                tls = invite.tls,
                allowInsecureTls = invite.allowInsecureTls,
                username = invite.username,
                password = invite.password,
                topicPrefix = invite.topicPrefix,
            ).also { container.brokers.save(it) }

            val group = container.groups.join(invite, broker.id)
            if (group == null) {
                _joinError.value = "You are already in that group."
                return@launch
            }
            container.groups.announceSelf(group.groupId)
            MqttService.start(getApplication())
            withContext(Dispatchers.Main) {
                _joinError.value = null
                onJoined(group.groupId)
            }
        }
    }

    fun clearJoinError() {
        _joinError.value = null
    }

    fun sendMessage(groupId: String, body: String, replyToId: String? = null) {
        if (body.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.sendMessage(groupId, body, replyToId)
            MqttService.wake(getApplication())
            PushSender.notifyGroup(groupId, container.identity.ensure().userId)
        }
    }

    fun sendImage(groupId: String, bytes: ByteArray, mime: String, caption: String?, replyToId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.sendAttachment(groupId, bytes, mime, kind = "image", caption = caption, replyToId = replyToId)
            MqttService.wake(getApplication())
            PushSender.notifyGroup(groupId, container.identity.ensure().userId)
        }
    }

    fun sendVoiceNote(groupId: String, bytes: ByteArray, mime: String, replyToId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.sendAttachment(groupId, bytes, mime, kind = "audio", replyToId = replyToId)
            MqttService.wake(getApplication())
            PushSender.notifyGroup(groupId, container.identity.ensure().userId)
        }
    }

    /** An attachment's bytes, or null while they have not arrived yet. */
    fun attachmentBytes(blobId: String): ByteArray? = container.blobs.get(blobId)

    /** Marks everything currently unread in [groupId] as read and tells each sender so. Cheap to
     * call whenever the chat screen's message list changes while it is open — it is a no-op
     * when there is nothing unread. */
    fun markRead(groupId: String) {
        val userId = (startup.value as? Startup.Ready)?.identity?.userId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.markRead(groupId, userId)
            MqttService.wake(getApplication())
        }
    }

    /** Which group's chat is currently on-screen, so its notifications stay quiet. */
    fun setActiveGroup(groupId: String?) {
        container.activeGroupId.value = groupId
    }

    fun memberAvatar(member: MemberEntity): ByteArray? =
        member.avatarBlobId?.let { container.blobs.get(it) }

    /**
     * Starts a call to one member, and opens the call screen for it.
     *
     * The engine lives on the container rather than here, because a call outlives this view
     * model: it has to survive the activity being destroyed, keep ringing while the app is
     * backgrounded, and still be answerable from a notification action with no UI at all.
     */
    /**
     * [member] null rings the whole group.
     *
     * A buzz, always: this build carries no voice at all. [Call.Mode.AUDIO] survives only as a
     * word on the wire, shared with the web app, and an offer carrying it is dropped rather
     * than rung — see [id.larus.mqttgroup.call.CallMachine].
     */
    fun call(groupId: String, member: MemberEntity?, mode: Call.Mode = Call.Mode.BUZZ) {
        container.calls.dial(groupId, member?.userId, mode)
        val app = getApplication<Application>()
        // Bring the connection up if it is not already: an offer sitting in the outbox with a
        // 45-second life is not much use if nothing is draining it.
        MqttService.wake(app)
        app.startActivity(
            Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun kick(groupId: String, member: MemberEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.kick(groupId, member.userId)
            MqttService.wake(getApplication())
        }
    }

    fun leaveGroup(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.leave(groupId)
            MqttService.wake(getApplication())
        }
    }

    fun deleteGroupForEveryone(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.groups.deleteForEveryone(groupId)
            MqttService.wake(getApplication())
        }
    }


    /** A blank broker to start editing from: the public suggestion, or what is already saved. */
    /** What the broker editor opens on: an existing broker to change, or a fresh suggestion to
     * add. A null id therefore always means "a new one", never "whichever we had". */
    fun brokerDraft(brokerId: String? = null): BrokerEntity =
        brokerId?.let { id -> brokers.value.firstOrNull { it.id == id } }
            ?: BrokerRepository.suggestion()

    fun testBroker(broker: BrokerEntity) {
        _testResult.value = TestResult.Running
        viewModelScope.launch(Dispatchers.IO) {
            val identity = container.identity.ensure()
            // A distinct client id: connecting twice with the same one would kick the real
            // connection off the broker, which is a confusing way to fail a connection test.
            val failure = container.brokers.testConnection(broker, "fg-test-${identity.userId.take(8)}")
            _testResult.value = if (failure == null) TestResult.Success else TestResult.Failure(failure)
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    fun saveBroker(broker: BrokerEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            container.brokers.save(broker)
            MqttService.start(getApplication())
        }
    }

    /**
     * Forgets a saved broker. Refused while any group still lives on it — deleting it would
     * leave those groups pointing at nothing, unable to connect and with no screen that explains
     * why. The caller gets false and shows that instead.
     */
    suspend fun deleteBroker(brokerId: String): Boolean = withContext(Dispatchers.IO) {
        if (container.groups.observeAll().first().any { it.brokerId == brokerId }) return@withContext false
        container.brokers.delete(brokerId)
        true
    }

    /** Which groups use each broker, for the list to say so rather than make someone guess. */
    val groupsPerBroker: StateFlow<Map<String, List<String>>> = container.groups.observeAll()
        .map { all -> all.groupBy { it.brokerId }.mapValues { entry -> entry.value.map { it.name } } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The Groups screen's manual reconnect button — forces a fresh attempt right now rather
     * than waiting out whatever backoff the connection is currently sitting in. */
    // --- Backup ---------------------------------------------------------------------------

    /** What the backup card is doing, so the screen can show progress and outcome in one place.
     * The key derivation alone is about a second, and a button that looks dead for a second is a
     * button people press twice. */
    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState

    fun clearBackupState() {
        _backupState.value = BackupState.Idle
    }

    /** Writes the archive to [sink], which the caller has opened from the system file picker —
     * the app never chooses where a backup lands. */
    fun exportBackup(passphrase: CharArray, sink: (ByteArray) -> Unit) {
        _backupState.value = BackupState.Working
        viewModelScope.launch(Dispatchers.IO) {
            _backupState.value = try {
                val bytes = container.backups.export(passphrase)
                withContext(Dispatchers.Main) { sink(bytes) }
                BackupState.Exported(bytes.size)
            } catch (e: Exception) {
                BackupState.Failed(e.message ?: "Could not write the backup.")
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun importBackup(bytes: ByteArray, passphrase: CharArray) {
        _backupState.value = BackupState.Working
        viewModelScope.launch(Dispatchers.IO) {
            val result = container.backups.import(bytes, passphrase)
            passphrase.fill('\u0000')
            _backupState.value = result.fold(
                onSuccess = { BackupState.Imported(it) },
                onFailure = { error ->
                    BackupState.Failed(
                        when ((error as? BackupArchive.BackupException)?.failure) {
                            is BackupArchive.Failure.NotABackup ->
                                "That file is not a Family Group backup."
                            is BackupArchive.Failure.UnsupportedVersion ->
                                "That backup was written by a newer version of the app."
                            else -> "Wrong passphrase, or the file is damaged."
                        },
                    )
                },
            )
            if (result.isSuccess) MqttService.start(getApplication())
        }
    }

    /** The map layer preference, and the one place that changes it. */
    val mapLayer: StateFlow<MapLayer> = container.mapPreferences.layer

    fun setMapLayer(layer: MapLayer) = container.mapPreferences.setLayer(layer)

    fun reconnect() {
        MqttService.reconnect(getApplication())
    }

    /**
     * Erases the database, the blobs and the keys, then ends the process. Restarting into a
     * half-torn-down container is worse than a cold start, and a cold start after this looks
     * exactly like a fresh install — which is what the user asked for.
     */
    suspend fun deleteEverything() {
        MqttService.stop(getApplication())
        withContext(Dispatchers.IO) { container.wipeEverything() }
    }

    private companion object {
        /** Vertices per path, per person — see [decimate]. Comfortably more than a phone screen
         * can resolve, and small enough that a whole group at a week's range stays smooth. */
        const val MAX_PATH_POINTS = 500
    }
}
