package id.larus.mqttgroup.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import id.larus.mqttgroup.FamilyGroupApp
import id.larus.mqttgroup.R
import id.larus.mqttgroup.data.db.BrokerEntity
import id.larus.mqttgroup.data.db.IdentityEntity
import id.larus.mqttgroup.map.MapLayer
import id.larus.mqttgroup.mqtt.BackgroundPolicy
import id.larus.mqttgroup.mqtt.ConnectionState
import id.larus.mqttgroup.mqtt.MqttService

/**
 * Who you are, where you connect, and the way out. The group list is the app's home; this is
 * everything that is not a group.
 */
@Composable
fun SettingsScreen(
    identity: IdentityEntity,
    avatar: ByteArray?,
    broker: BrokerEntity?,
    /** How many brokers are saved in total. The card names the first one and counts the rest —
     * a list of hosts here would duplicate the Brokers screen this row leads to. */
    brokerCount: Int,
    connection: ConnectionState,
    /** The map layer, app-wide. The same preference the map's own layers button writes, so the
     * two never disagree. */
    mapLayer: MapLayer,
    onMapLayerChange: (MapLayer) -> Unit,
    backupState: BackupState,
    /** Given a passphrase and a place to put the bytes. The screen owns the file picker; the
     * caller owns the encryption. */
    onExport: (CharArray, (ByteArray) -> Unit) -> Unit,
    onImport: (ByteArray, CharArray) -> Unit,
    onClearBackupState: () -> Unit,
    onEditProfile: () -> Unit,
    onEditBroker: () -> Unit,
    onAbout: () -> Unit,
    onDeleteEverything: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Bumped on return from any system settings screen below, so every permission's status is
    // re-read from scratch rather than trusting whatever it was when this screen first opened —
    // the user might have said no, or toggled something unrelated on the same App Info page.
    var permissionRevision by remember { mutableIntStateOf(0) }
    val recheckPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { permissionRevision++ }

    // canPersistInBackground also governs more than this card: MainActivity checks the same flag
    // to decide whether the connection is allowed to keep running once the app is backgrounded.
    val batteryExempt = remember(permissionRevision) { BackgroundPolicy.canPersistInBackground(context) }
    val permissions = remember(permissionRevision) { permissionRows(context, batteryExempt) }

    // Re-read on the same revision as the rest, so coming back from the Settings trip below
    // makes this card disappear rather than sit there having already been dealt with.
    val fullScreenCallsAllowed = remember(permissionRevision) {
        (context.applicationContext as FamilyGroupApp).container.callNotifier.canUseFullScreen()
    }

    FormScreen(title = "Settings", onBack = onBack) {
        SectionCard(padding = PaddingValues(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditProfile),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(bytes = avatar, name = identity.displayName, size = 60.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(identity.displayName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // The first block of the UUID is enough for two people to check they
                        // mean the same person, without printing 36 characters at them.
                        "ID ${identity.userId.take(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Edit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionCard(padding = PaddingValues(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onAbout),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.action_about),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Open",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Connection")
        SectionCard(padding = PaddingValues(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEditBroker),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            broker == null -> "No broker yet"
                            brokerCount == 1 -> "${broker.host}:${broker.port}"
                            else -> "${broker.host} and ${brokerCount - 1} more"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    ConnectionLine(connection)
                }
                Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (broker != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill(if (broker.tls) "TLS" else "No TLS")
                    if (broker.allowInsecureTls) Pill("Any certificate")
                    Pill(broker.topicPrefix)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            // The background connection is a foreground service, and Android requires one of
            // those to show a permanent notification — the app cannot hide it, and deliberately
            // re-posts it when swiped away (see MqttService). What the *phone owner* can do is
            // turn its channel off, which keeps the service running and takes the entry out of
            // the shade. This is the link to that switch, because nobody finds it otherwise.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, MqttService.CHANNEL_ID)
                        // Some OEM builds have no per-channel screen to open; the app's own
                        // notification settings is the next best landing place, and the app
                        // details page after that.
                        runCatching { context.startActivity(intent) }.recoverCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Connection notification", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Android requires it while the background connection runs. Turn its " +
                            "channel off to hide it from the shade — messages keep arriving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Show or hide",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Permissions")
        SectionCard(padding = PaddingValues(16.dp)) {
            permissions.forEachIndexed { index, permission ->
                if (index > 0) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                }
                val isBackgroundConnection = permission.title == BACKGROUND_CONNECTION_TITLE
                // The one-shot ask dialog. Correct only while not yet granted — re-showing it to
                // someone who already said yes is exactly the "link" that was broken: some OEMs
                // have nothing to display for an already-exempt app and either no-op or throw
                // instead of opening anything.
                val grantIntent = if (isBackgroundConnection) {
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                }
                // The full battery-optimization list, for revisiting a choice already made — this
                // one takes no package data at all; the ask dialog above is the only one that does.
                val reviewIntent = if (isBackgroundConnection) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    grantIntent
                }
                fun openSettings(intent: Intent) {
                    runCatching { recheckPermissions.launch(intent) }.onFailure {
                        runCatching {
                            recheckPermissions.launch(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(permission.title, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            permission.why,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        // Colour, not just the word, is what makes a row that needs attention
                        // findable in a five-row list at a glance rather than read one by one.
                        Pill(
                            text = if (permission.granted) "Granted" else "Not granted",
                            container = (if (permission.granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error).copy(alpha = 0.14f),
                            content = if (permission.granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        if (permission.granted) {
                            // Already granted: the settings link sits right under the pill it
                            // explains, small, rather than a full-width call to action competing
                            // with rows that still need attention.
                            TextButton(
                                onClick = { openSettings(reviewIntent) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Text("Settings", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    OpenInNewIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
                if (!permission.granted) {
                    Spacer(Modifier.height(10.dp))
                    // Not granted yet: this is the whole point of the row, so it gets the full
                    // width and the plainer "go do this" wording.
                    OutlinedButton(
                        onClick = { openSettings(grantIntent) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant in Settings")
                        Spacer(Modifier.width(6.dp))
                        Icon(OpenInNewIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Only when it is actually missing. A permission row that says "granted" for something
        // most people never had to think about is noise, and this one has no "review" story
        // worth offering: nothing here depends on it being revocable.
        if (!fullScreenCallsAllowed) {
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.call_fullscreen_title))
            SectionCard(padding = PaddingValues(16.dp)) {
                Text(
                    stringResource(R.string.call_fullscreen_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        // API 34+ only, which is the only place this section is ever shown.
                        runCatching {
                            recheckPermissions.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }.onFailure {
                            runCatching {
                                recheckPermissions.launch(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.call_fullscreen_action))
                    Spacer(Modifier.width(6.dp))
                    Icon(OpenInNewIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Map")
        SectionCard(padding = PaddingValues(16.dp)) {
            Text(
                "Which tiles every group's map draws. Satellite and hybrid pull noticeably more " +
                    "data than street does.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            MapLayer.entries.forEachIndexed { index, option ->
                if (index > 0) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMapLayerChange(option) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            option.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(selected = option == mapLayer, onClick = { onMapLayerChange(option) })
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Backup")
        BackupCard(
            state = backupState,
            onExport = onExport,
            onImport = onImport,
            onClearState = onClearBackupState,
        )

        Spacer(Modifier.height(28.dp))
        SectionLabel("This phone")
        SectionCard(padding = PaddingValues(16.dp)) {
            Text(
                "Everything the app stores — messages, locations, files and keys — lives only " +
                    "here, encrypted. There is no backup and no server that could restore it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Delete all data", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete everything?") },
            text = {
                Text(
                    "This erases your profile, your groups and their keys, every message and " +
                        "location, and every file. The app restarts as if freshly installed. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDeleteEverything() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private const val BACKGROUND_CONNECTION_TITLE = "Allow background connection"

/** One permission this app can work without, but works better with. */
private data class PermissionRow(val title: String, val why: String, val granted: Boolean)

/**
 * Every permission worth showing someone, not just the ones this screen used to single out.
 * Notification is skipped below API 33: there is no such runtime permission on older Android,
 * where notifications are always allowed unless the user finds the app's own channel switch.
 */
private fun permissionRows(context: android.content.Context, batteryExempt: Boolean): List<PermissionRow> =
    buildList {
        add(
            PermissionRow(
                title = "Location",
                why = "Lets this phone share its position with a group that has location turned on.",
                granted = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                    isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                PermissionRow(
                    title = "Notifications",
                    why = "Needed to show a new message, whether decrypted from MQTT or a push telling you one arrived.",
                    granted = isGranted(context, Manifest.permission.POST_NOTIFICATIONS),
                ),
            )
        }
        add(
            PermissionRow(
                title = "Camera",
                why = "Scans an invite QR code and takes a photo to send in chat.",
                granted = isGranted(context, Manifest.permission.CAMERA),
            ),
        )
        add(
            PermissionRow(
                title = "Microphone",
                why = "Records a voice note while the mic button is held down.",
                granted = isGranted(context, Manifest.permission.RECORD_AUDIO),
            ),
        )
        add(
            PermissionRow(
                title = BACKGROUND_CONNECTION_TITLE,
                why = "The battery-optimization exemption. Without it, the connection drops the " +
                    "moment the app is backgrounded — a push notification still gets a message " +
                    "here, but opening the app is what actually fetches it. Location sharing " +
                    "does not wait for this: switching it on keeps the app running on its own.",
                granted = batteryExempt,
            ),
        )
    }

private fun isGranted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Export and restore, both behind a passphrase prompt.
 *
 * The file is written wherever the system picker is pointed and never anywhere the app chooses:
 * a backup the app filed away by itself would sit in app storage, which is exactly the storage a
 * lost or wiped phone takes with it.
 */
@Composable
private fun BackupCard(
    state: BackupState,
    onExport: (CharArray, (ByteArray) -> Unit) -> Unit,
    onImport: (ByteArray, CharArray) -> Unit,
    onClearState: () -> Unit,
) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf<Ask?>(null) }
    /** Held between "the user typed a passphrase" and "the system picker came back with a
     * destination", because those are two round trips and the bytes cannot be made until both
     * have happened. */
    var pending by remember { mutableStateOf<CharArray?>(null) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val passphrase = pending
        pending = null
        if (uri == null || passphrase == null) return@rememberLauncherForActivityResult
        onExport(passphrase) { bytes ->
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val passphrase = pending
        pending = null
        if (uri == null || passphrase == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes != null) onImport(bytes, passphrase)
    }

    SectionCard(padding = PaddingValues(16.dp)) {
        Text(
            "A backup carries your identity, your groups and their keys, and your broker " +
                "settings — enough to be you again on a new phone. It does not carry messages, " +
                "photos or location history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // The one sentence that has to be there. The group keys are inside that file in the
            // clear behind the passphrase, so the passphrase is not a formality.
            "The file is encrypted with a passphrase you choose, and the group keys are inside " +
                "it. Anyone who has both the file and the passphrase can read your groups, so " +
                "make it a long one and keep it somewhere else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { asking = Ask.Export },
                enabled = state !is BackupState.Working,
            ) { Text("Export") }
            OutlinedButton(
                onClick = { asking = Ask.Import },
                enabled = state !is BackupState.Working,
            ) { Text("Restore") }
        }

        val message = when (state) {
            BackupState.Idle -> null
            BackupState.Working -> "Working…"
            is BackupState.Exported -> "Backup written, ${state.bytes / 1024 + 1} KB."
            is BackupState.Imported -> with(state.summary) {
                buildString {
                    append("Restored $groups group")
                    if (groups != 1) append("s")
                    append(", $keys key")
                    if (keys != 1) append("s")
                    append(", $brokers broker")
                    if (brokers != 1) append("s")
                    if (identityReplaced) append(", and your profile")
                    append(". Messages and photos are not part of a backup; new ones will arrive.")
                }
            }
            is BackupState.Failed -> state.reason
        }
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is BackupState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    asking?.let { ask ->
        PassphraseDialog(
            exporting = ask == Ask.Export,
            onDismiss = { asking = null },
            onConfirm = { passphrase ->
                asking = null
                onClearState()
                pending = passphrase
                if (ask == Ask.Export) {
                    createFile.launch("family-group-${System.currentTimeMillis() / 1000}.fgbk")
                } else {
                    // Not filtered to a MIME type: a .fgbk written by this app has no registered
                    // type, and several file providers hand such files back as
                    // application/octet-stream, text/plain or nothing at all. A filter would
                    // grey out the very file the user is looking for.
                    openFile.launch(arrayOf("*/*"))
                }
            },
        )
    }
}

private enum class Ask { Export, Import }

@Composable
private fun PassphraseDialog(
    exporting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    // Long, not complex: length is what actually costs an attacker anything against a KDF, and
    // a character-class rule would only push people towards a short unmemorable one.
    val tooShort = passphrase.length < MIN_PASSPHRASE
    val mismatch = exporting && repeat != passphrase

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (exporting) "Choose a passphrase" else "Enter the passphrase") },
        text = {
            Column {
                if (exporting) {
                    Text(
                        "There is no way to open the file without it — not by us, not by anyone. " +
                            "Write it down somewhere that is not this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (exporting) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text("Again") },
                        singleLine = true,
                        isError = repeat.isNotEmpty() && mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (tooShort) {
                            "At least $MIN_PASSPHRASE characters."
                        } else {
                            "Longer is what makes this strong."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = passphrase.isNotEmpty() && !mismatch && (!exporting || !tooShort),
                onClick = { onConfirm(passphrase.toCharArray()) },
            ) { Text(if (exporting) "Export" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MIN_PASSPHRASE = 12
