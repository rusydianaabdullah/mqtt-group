package id.larus.mqttgroup.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.larus.mqttgroup.FamilyGroupApp
import id.larus.mqttgroup.data.db.MemberEntity
import id.larus.mqttgroup.mqtt.BackgroundPolicy
import id.larus.mqttgroup.mqtt.ChatNotifier
import id.larus.mqttgroup.mqtt.ConnectionState
import id.larus.mqttgroup.mqtt.MqttService
import kotlin.system.exitProcess
import kotlinx.coroutines.launch

/**
 * Where the app can be. Small enough to hold in one `when`, and honest about the fact that a
 * phone-sized app with seven screens does not need a navigation graph to keep them straight.
 */
private sealed interface Screen {
    data object Groups : Screen
    data class Group(val groupId: String) : Screen
    /** The same group's map, reached from a button in the chat's bar and returning there. Its
     * own destination rather than a field on [Group] because it is a place, not a mode. */
    data class GroupMap(val groupId: String) : Screen
    data class GroupInfo(val groupId: String) : Screen
    data class LocationSettings(val groupId: String) : Screen
    data object CreateGroup : Screen
    data object JoinGroup : Screen
    data object Settings : Screen
    data object Profile : Screen
    /** [afterCreateGroup]: reached because creating a group needs a broker first, so saving or
     * cancelling here returns to that flow instead of Settings. */
    data class Broker(
        val afterCreateGroup: Boolean = false,
        /** Which saved broker is being edited, or null to add a new one. */
        val brokerId: String? = null,
    ) : Screen

    /** The list of brokers. Reached from Settings; the editor above is reached from here. */
    data object Brokers : Screen

    data object About : Screen
}

class MainActivity : ComponentActivity() {

    /** Set from a notification tap, read once the group it names actually exists in the
     * loaded list. A plain field rather than something scoped to the composition, because
     * onNewIntent fires outside of it — this is singleTask, so the activity is not recreated. */
    private val pendingGroupId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The default adapts the status-icon contrast to the system light/dark setting, which
        // is what we want now that the app follows the phone rather than forcing dark.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingGroupId.value = intent?.getStringExtra(ChatNotifier.EXTRA_GROUP_ID)
        setContent {
            FamilyGroupTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }

    /**
     * The foreground half of the reconnect policy — see AppContainer.appVisible. Set here rather
     * than from a Composable effect so that it follows the *activity*, not whichever screen
     * happens to be composed: the map and the chat both want the connection kept up, and so does
     * the group list behind them.
     *
     * Nothing is started here: the LifecycleStartEffect in App() already brings the service up on
     * the same ON_START, and this only has to be true before that service looks at it.
     */
    override fun onStart() {
        super.onStart()
        (application as FamilyGroupApp).container.appVisible.value = true
    }

    override fun onStop() {
        super.onStop()
        (application as FamilyGroupApp).container.appVisible.value = false
    }

    /** Opens the inquiry form in a browser. Wrapped because a device with no browser at all is
     * unusual but not impossible, and there is nothing useful to say when it happens. */
    private fun openContact() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CONTACT_URL))) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingGroupId.value = intent.getStringExtra(ChatNotifier.EXTRA_GROUP_ID)
    }

    @Composable
    private fun App() {
        val model: MainViewModel = viewModel()
        val startup by model.startup.collectAsStateWithLifecycle()
        val avatar by model.avatar.collectAsStateWithLifecycle()
        val broker by model.broker.collectAsStateWithLifecycle()
        val brokers by model.brokers.collectAsStateWithLifecycle()
        val groupsPerBroker by model.groupsPerBroker.collectAsStateWithLifecycle()
        val brokerStates by model.brokerStates.collectAsStateWithLifecycle()
        val backupState by model.backupState.collectAsStateWithLifecycle()
        val connection by model.connection.collectAsStateWithLifecycle()
        val mapLayer by model.mapLayer.collectAsStateWithLifecycle()
        val testResult by model.testResult.collectAsStateWithLifecycle()
        val groups by model.groups.collectAsStateWithLifecycle()
        val members by model.members.collectAsStateWithLifecycle()
        val messages by model.messages.collectAsStateWithLifecycle()
        val receipts by model.receipts.collectAsStateWithLifecycle()
        val unreadCounts by model.unreadCounts.collectAsStateWithLifecycle()
        val locations by model.locations.collectAsStateWithLifecycle()
        val paths by model.paths.collectAsStateWithLifecycle()
        val historyRange by model.historyRange.collectAsStateWithLifecycle()
        val historyVisible by model.historyVisible.collectAsStateWithLifecycle()
        val selectedMapUser by model.selectedMapUser.collectAsStateWithLifecycle()
        val inviteCode by model.inviteCode.collectAsStateWithLifecycle()
        val joinError by model.joinError.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        var screen: Screen by remember { mutableStateOf(Screen.Groups) }

        fun home() {
            model.clearTestResult()
            model.clearJoinError()
            model.openGroup(null)
            screen = Screen.Groups
        }

        // The map is the one screen with a parent that is not the groups list, so it is the one
        // the gesture has to treat differently — otherwise its own back arrow and the system's
        // disagree about where "back" is.
        BackHandler(enabled = screen != Screen.Groups) {
            when (val current = screen) {
                is Screen.GroupMap -> screen = Screen.Group(current.groupId)
                else -> home()
            }
        }

        // Android 13+ hides the foreground-service notification without this, and a background
        // connection whose notification is hidden looks like the app doing something it did not
        // disclose. Asked for once a broker exists, not on first launch.
        val notifications = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }

        /**
         * Turning on "share my location" for a group, once the OS has had its say.
         *
         * Staged deliberately, because Android 11+ refuses to grant background location in the
         * same dialog as foreground and silently denies the whole request if asked for both at
         * once. So: ask for foreground here, switch sharing on the moment it is granted — the
         * feature works immediately, with the app open — and leave the background upgrade to the
         * Settings screen's existing background-connection toggle, which is already the place
         * this app asks people to consent to running while closed.
         */
        var pendingShareGroupId by remember { mutableStateOf<String?>(null) }
        val requestLocation = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            val groupId = pendingShareGroupId
            pendingShareGroupId = null
            // Either permission is enough to put someone on a map; coarse simply puts them on it
            // less precisely, which is a perfectly reasonable thing to choose.
            val allowed = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (allowed && groupId != null) model.setShareLocation(groupId, true)
        }

        /** Switches sharing for one group, asking for the permission first when it is missing.
         * Turning sharing *off* never needs a permission and must never be blocked by one. */
        fun setShare(groupId: String, share: Boolean) {
            if (!share) {
                model.setShareLocation(groupId, false)
                return
            }
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                model.setShareLocation(groupId, true)
            } else {
                pendingShareGroupId = groupId
                requestLocation.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }

        LaunchedEffect(broker?.id) {
            if (broker == null) return@LaunchedEffect
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MqttService.start(this@MainActivity)
        }

        // Whether this phone is actually sharing its position with anyone right now: some group
        // the admin has enabled the map for, and whose own share switch this phone has turned on.
        //
        // Read through rememberUpdatedState rather than keyed into the effect below, so the stop
        // decision uses the value at the moment the app is backgrounded. Keying on it would
        // dispose and re-run the effect the instant someone flipped their share switch, which
        // means calling MqttService.stop() while the app is still open and in front of them.
        val sharingLocation = groups.any { it.locationEnabled && it.shareLocation }
        val sharingLocationNow = rememberUpdatedState(sharingLocation)

        // The connection follows the app's own foreground/background state unless the person has
        // explicitly said otherwise, and there are two ways of saying so.
        //
        // Settings' background-connection toggle is the general one: keep everything alive.
        // Without it this app does not fight Android's battery management for the sake of the
        // chat connection — messages are not lost by a socket going down, FCM wakes the app for
        // the ones that matter, and the connection comes back the next time the app is opened.
        //
        // Sharing location is the specific one, and it is consent in its own right. A person who
        // switched on "share my location" asked for their position to keep going out while they
        // are not looking at the app — that is the entire feature — and the engine taking those
        // fixes lives inside this service. Stopping it here meant sharing silently ended the
        // moment the screen locked, while the group went on seeing the last position from before
        // the lock as though it were current. So while any group is being shared with, the
        // service stays up whatever the battery toggle says; MqttService is already running as a
        // `location` foreground service in that case, which is the type that exists for exactly
        // this and is why no ACCESS_BACKGROUND_LOCATION is needed.
        //
        // ON_START/ON_STOP, not ON_RESUME/ON_PAUSE: pause fires for any transient overlay on
        // top of this activity — a permission prompt, the camera, the photo picker, even the
        // screen locking on some phones — none of which mean the app has actually been left.
        // Tearing the connection down on every one of those was the "keeps going offline while
        // I'm right here using it" bug; stop only fires once the activity is no longer visible
        // at all.
        LifecycleStartEffect(broker?.id) {
            if (broker != null) MqttService.start(this@MainActivity)
            onStopOrDispose {
                if (!sharingLocationNow.value &&
                    !BackgroundPolicy.canPersistInBackground(this@MainActivity)
                ) {
                    MqttService.stop(this@MainActivity)
                }
            }
        }

        // A notification tap arrives as an id before the groups list has necessarily loaded, so
        // this waits for that id to actually be one of them rather than racing the database.
        LaunchedEffect(pendingGroupId.value, groups) {
            val id = pendingGroupId.value ?: return@LaunchedEffect
            if (groups.any { it.groupId == id }) {
                model.openGroup(id)
                screen = Screen.Group(id)
                pendingGroupId.value = null
            }
        }

        when (val state = startup) {
            Startup.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }

            Startup.NeedsProfile -> ProfileScreen(
                initialName = "",
                initialAvatar = null,
                isOnboarding = true,
                onSave = model::saveProfile,
            )

            is Startup.Ready -> {
                when (val current = screen) {
                    Screen.Groups -> GroupsScreen(
                        groups = groups,
                        unreadCounts = unreadCounts,
                        connection = connection,
                        onReconnect = model::reconnect,
                        onOpenGroup = { model.openGroup(it.groupId); screen = Screen.Group(it.groupId) },
                        // Creating a group needs a broker to create it on; joining does not, since
                        // a scanned invite carries the broker itself. So only this path detours.
                        onCreateGroup = {
                            screen = if (broker == null) Screen.Broker(afterCreateGroup = true) else Screen.CreateGroup
                        },
                        onJoinGroup = { screen = Screen.JoinGroup },
                        onSettings = { screen = Screen.Settings },
                    )

                    is Screen.Group -> {
                        val group = groups.firstOrNull { it.groupId == current.groupId }
                        // The group can vanish under us — an admin's delete, or being kicked —
                        // and dropping back to the list is the least confusing way to say so.
                        if (group == null) {
                            LaunchedEffect(current.groupId) { home() }
                        } else {
                            // Suppresses notifications for this group while its chat is the
                            // screen actually on top — anything else (GroupInfo, Settings) still
                            // counts as "not looking at it". A plain DisposableEffect is not
                            // enough here: it only fires when this composable leaves the tree,
                            // never on the activity being backgrounded, so a chat left open and
                            // then backgrounded went right on eating its own notifications.
                            // LifecycleResumeEffect ties it to actual visibility instead.
                            androidx.lifecycle.compose.LifecycleResumeEffect(group.groupId) {
                                model.setActiveGroup(group.groupId)
                                onPauseOrDispose { model.setActiveGroup(null) }
                            }
                            // Marking read on every message-list change, not just on first open,
                            // is what keeps the blue check and the unread badge honest while a
                            // new message arrives with the chat already on screen.
                            LaunchedEffect(messages) { model.markRead(group.groupId) }
                            ChatScreen(
                                group = group,
                                messages = messages,
                                members = members,
                                receipts = receipts,
                                selfUserId = state.identity.userId,
                                connection = connection,
                                attachmentFor = model::attachmentBytes,
                                avatarFor = model::memberAvatar,
                                onSend = { body, replyToId -> model.sendMessage(group.groupId, body, replyToId) },
                                onSendImage = { bytes, mime, caption, replyToId ->
                                    model.sendImage(group.groupId, bytes, mime, caption, replyToId)
                                },
                                onSendVoice = { bytes, mime, replyToId ->
                                    model.sendVoiceNote(group.groupId, bytes, mime, replyToId)
                                },
                                onReconnect = model::reconnect,
                                onOpenInfo = { screen = Screen.GroupInfo(group.groupId) },
                                // Same rule as the group screen's buttons: null when there is
                                // nothing to publish through, so the card greys it rather than
                                // offering a buzz that goes nowhere.
                                onBuzz = { member: MemberEntity -> model.call(group.groupId, member) }
                                    .takeIf { connection is ConnectionState.Connected },
                                onBack = { home() },
                                onOpenMap = { screen = Screen.GroupMap(group.groupId) },
                            )
                        }
                    }

                    is Screen.GroupMap -> {
                        val group = groups.firstOrNull { it.groupId == current.groupId }
                        if (group == null) {
                            LaunchedEffect(current.groupId) { home() }
                        } else {
                            MapScreen(
                                group = group,
                                members = members,
                                locations = locations,
                                paths = paths,
                                historyVisible = historyVisible,
                                onToggleHistory = model::setHistoryVisible,
                                range = historyRange,
                                onRangeChange = model::setHistoryRange,
                                onDeleteHistory = { userId ->
                                    model.deleteLocationHistory(group.groupId, userId)
                                },
                                selfUserId = state.identity.userId,
                                selectedUserId = selectedMapUser,
                                avatarFor = model::memberAvatar,
                                onSelectUser = model::selectMapUser,
                                layer = mapLayer,
                                onLayerChange = model::setMapLayer,
                                onOpenSettings = { screen = Screen.LocationSettings(group.groupId) },
                                // Back to the chat it was opened from, not to the groups list.
                                // With the tab row gone the map is one level deeper than the
                                // chat, and back has to agree with the way in.
                                onBack = { screen = Screen.Group(group.groupId) },
                                connection = connection,
                                onReconnect = model::reconnect,
                            )
                        }
                    }

                    is Screen.LocationSettings -> {
                        val group = groups.firstOrNull { it.groupId == current.groupId }
                        if (group == null) {
                            LaunchedEffect(current.groupId) { home() }
                        } else {
                            LocationSettingsScreen(
                                group = group,
                                onToggleShare = { setShare(group.groupId, it) },
                                onSave = { model.saveLocationSettings(group.groupId, it) },
                                onDeleteHistory = { model.deleteLocationHistory(group.groupId) },
                                onBack = { screen = Screen.GroupMap(group.groupId) },
                            )
                        }
                    }

                    is Screen.GroupInfo -> {
                        val group = groups.firstOrNull { it.groupId == current.groupId }
                        if (group == null) {
                            LaunchedEffect(current.groupId) { home() }
                        } else {
                            GroupDetailScreen(
                                group = group,
                                members = members,
                                selfUserId = state.identity.userId,
                                inviteCode = inviteCode,
                                avatarFor = model::memberAvatar,
                                onKick = { model.kick(group.groupId, it) },
                                // Null while there is no broker connection: an offer lives 45
                                // seconds, so placing one with nothing to publish it through is
                                // a call that was never going to happen. Better a greyed-out
                                // button than a ring nobody hears.
                                onCall = { member: MemberEntity -> model.call(group.groupId, member) }
                                    .takeIf { connection is ConnectionState.Connected },
                                onCallGroup = { model.call(group.groupId, null) }
                                    .takeIf { connection is ConnectionState.Connected },
                                onLeave = { model.leaveGroup(group.groupId); home() },
                                onDeleteForEveryone = { model.deleteGroupForEveryone(group.groupId); home() },
                                onBack = { screen = Screen.Group(group.groupId) },
                            )
                        }
                    }

                    Screen.CreateGroup -> CreateGroupScreen(
                        brokers = brokers,
                        groupsPerBroker = groupsPerBroker,
                        onAddBroker = { screen = Screen.Broker(afterCreateGroup = true) },
                        onCreate = { name, brokerId ->
                            model.createGroup(name, brokerId) { groupId ->
                                model.openGroup(groupId)
                                screen = Screen.Group(groupId)
                            }
                        },
                        onCancel = { home() },
                    )

                    Screen.JoinGroup -> JoinGroupScreen(
                        error = joinError,
                        onScanned = { scanned ->
                            model.joinFromScan(scanned) { groupId ->
                                model.openGroup(groupId)
                                screen = Screen.Group(groupId)
                            }
                        },
                        onCancel = { home() },
                    )

                    Screen.Settings -> SettingsScreen(
                        identity = state.identity,
                        avatar = avatar,
                        broker = broker,
                        brokerCount = brokers.size,
                        connection = connection,
                        backupState = backupState,
                        onExport = model::exportBackup,
                        onImport = model::importBackup,
                        onClearBackupState = model::clearBackupState,
                        mapLayer = mapLayer,
                        onMapLayerChange = model::setMapLayer,
                        onEditProfile = { screen = Screen.Profile },
                        onEditBroker = { screen = Screen.Brokers },
                        onAbout = { screen = Screen.About },
                        onDeleteEverything = {
                            scope.launch {
                                model.deleteEverything()
                                // The container still holds a handle to a database that is now
                                // gone, so restarting is the honest way back to a clean state.
                                finishAndRemoveTask()
                                exitProcess(0)
                            }
                        },
                        onBack = { home() },
                    )

                    Screen.Profile -> ProfileScreen(
                        initialName = state.identity.displayName,
                        initialAvatar = avatar,
                        isOnboarding = false,
                        onSave = { name, bytes ->
                            model.saveProfile(name, bytes)
                            screen = Screen.Settings
                        },
                        onCancel = { screen = Screen.Settings },
                    )

                    Screen.About -> AboutScreen(
                        onBack = { screen = Screen.Settings },
                        onContact = ::openContact,
                    )

                    Screen.Brokers -> BrokersScreen(
                        brokers = brokers,
                        groupsPerBroker = groupsPerBroker,
                        states = brokerStates,
                        onEdit = { screen = Screen.Broker(brokerId = it) },
                        onAdd = { screen = Screen.Broker() },
                        onDelete = { model.deleteBroker(it) },
                        onBack = { screen = Screen.Settings },
                    )

                    is Screen.Broker -> BrokerScreen(
                        initial = remember(current.brokerId) { model.brokerDraft(current.brokerId) },
                        testResult = testResult,
                        onTest = model::testBroker,
                        onSave = {
                            model.saveBroker(it)
                            model.clearTestResult()
                            screen = when {
                                current.afterCreateGroup -> Screen.CreateGroup
                                else -> Screen.Brokers
                            }
                        },
                        onCancel = {
                            model.clearTestResult()
                            screen = when {
                                current.afterCreateGroup -> Screen.Groups
                                else -> Screen.Brokers
                            }
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val CONTACT_URL = "https://larus.id/inquiry?f=google-play&app=family-group"
    }

}
