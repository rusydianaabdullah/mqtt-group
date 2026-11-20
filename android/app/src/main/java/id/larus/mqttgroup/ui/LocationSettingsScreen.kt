package id.larus.mqttgroup.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.larus.mqttgroup.data.db.GroupEntity
import id.larus.mqttgroup.mqtt.BackgroundPolicy
import id.larus.mqttgroup.mqtt.Control

/**
 * The admin's location rules for one group.
 *
 * Everything here is published to every member the moment it is saved, so the screen is written
 * as what it is: a person setting a policy for other people's phones and batteries. That is why
 * each choice says what it costs rather than only what it does, and why the screen states plainly
 * that it cannot make anyone share.
 *
 * Presets rather than free text. "Every 90 seconds" is not a thing anyone needs, and a text field
 * accepting it is a text field that also accepts 0.
 */
@Composable
fun LocationSettingsScreen(
    group: GroupEntity,
    /** This phone's own consent for this group. Applied the moment it is switched, with no Save
     * — unlike the group rules below it, it is nobody else's setting to agree to, and a consent
     * you have to confirm twice is a consent that gets left on by accident. */
    onToggleShare: (Boolean) -> Unit,
    onSave: (Control.LocationSettings) -> Unit,
    onDeleteHistory: () -> Unit,
    onBack: () -> Unit,
) {
    var enabled by remember(group.groupId) { mutableStateOf(group.locationEnabled) }
    var sampleSec by remember(group.groupId) { mutableStateOf(group.locSampleSec) }
    var pushSec by remember(group.groupId) { mutableStateOf(group.locPushSec) }
    var minDistance by remember(group.groupId) { mutableStateOf(group.locMinDistanceM) }
    var retention by remember(group.groupId) { mutableStateOf(group.locRetentionHours) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // Re-read on return from the system dialog rather than trusting what it was when the screen
    // opened, the same way the Settings screen's permission rows do.
    var permissionRevision by remember { mutableIntStateOf(0) }
    val recheckBackground = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { permissionRevision++ }
    val canPersist = remember(permissionRevision) { BackgroundPolicy.canPersistInBackground(context) }

    val dirty = enabled != group.locationEnabled ||
        sampleSec != group.locSampleSec ||
        pushSec != group.locPushSec ||
        minDistance != group.locMinDistanceM ||
        retention != group.locRetentionHours

    FormScreen(title = "Location", onBack = onBack) {
        // First, and for everyone: the only switch on this screen that a non-admin can touch,
        // and the one they came here for.
        SectionLabel("My sharing")
        SectionCard {
            SettingToggle(
                title = "Share my location",
                detail = if (!group.locationEnabled) {
                    "The admin has not turned the map on for this group yet. Switching this on " +
                        "now means you start sharing the moment they do."
                } else {
                    "Sends your position every ${humanInterval(group.locPushSec)} once you have " +
                        "moved ${group.locMinDistanceM} m. Off, you still see everyone else."
                },
                checked = group.shareLocation,
                onCheckedChange = onToggleShare,
            )
        }

        // Sharing keeps running with the screen locked — MainActivity leaves the service up
        // while any group is being shared with, precisely because that switch is the consent.
        // What it cannot promise is a phone whose manufacturer kills backgrounded apps anyway,
        // or one that has been restarted: the boot receiver stays behind the battery toggle,
        // since a service started from boot cannot claim the location type without
        // ACCESS_BACKGROUND_LOCATION, which this app does not ask for. Both are worth saying
        // once, here, to someone about to rely on this.
        if (group.shareLocation && !canPersist) {
            Spacer(Modifier.height(12.dp))
            NoticeCard(
                text = "Sharing keeps going while your screen is locked. Two things can still " +
                    "stop it: some phones shut backgrounded apps down on their own, and after " +
                    "a restart it waits until you next open the app. Allowing this app to run " +
                    "in the background settles both.",
                actionLabel = "Allow background connection",
                onAction = {
                    // The one-shot ask dialog, exactly as the Settings screen does it. Falling
                    // back to App Info covers OEMs that have nothing to show for this action.
                    val ask = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { recheckBackground.launch(ask) }.onFailure {
                        runCatching {
                            recheckBackground.launch(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }
                },
            )
        }

        // Everything below belongs to the group, not to this phone, so it is the admin's alone.
        if (!group.isAdmin) return@FormScreen

        Spacer(Modifier.height(20.dp))
        SectionLabel("Group rules")
        SectionCard {
            SettingToggle(
                title = "Location sharing for this group",
                detail = "Turns the map on for everyone. It does not start sharing anyone's " +
                    "position — each person still has their own switch.",
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
        }

        if (enabled) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("How often each phone looks")
            SectionCard(padding = PaddingValues(16.dp)) {
                Text(
                    "Taking a fix is cheap and stays on the phone. A shorter interval draws a " +
                        "smoother trail and notices movement sooner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                ChoiceRow(
                    options = SAMPLE_OPTIONS,
                    selected = sampleSec,
                    label = ::humanInterval,
                    onSelect = {
                        sampleSec = it
                        // Publishing faster than a fix is taken can only resend the same
                        // position, so the second slider follows the first up rather than
                        // silently meaning something other than what it says.
                        if (pushSec < it) pushSec = it
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("How often it tells the group")
            SectionCard(padding = PaddingValues(16.dp)) {
                Text(
                    "This is the one that costs battery and data: each push wakes the radio on " +
                        "the sending phone and every other phone in the group.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                ChoiceRow(
                    options = PUSH_OPTIONS.filter { it >= sampleSec }.ifEmpty { listOf(sampleSec) },
                    selected = pushSec,
                    label = ::humanInterval,
                    onSelect = { pushSec = it },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Ignore small movements")
            SectionCard(padding = PaddingValues(16.dp)) {
                Text(
                    "A position is only sent once the phone has moved this far. A phone sitting " +
                        "still still checks in about every ${humanInterval(pushSec * 3)}, so the " +
                        "map can tell \"at home\" from \"battery dead\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                ChoiceRow(
                    options = DISTANCE_OPTIONS,
                    selected = minDistance,
                    label = { if (it == 0) "Always send" else "$it m" },
                    onSelect = { minDistance = it },
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Keep history for")
            SectionCard(padding = PaddingValues(16.dp)) {
                ChoiceRow(
                    options = RETENTION_OPTIONS,
                    selected = retention,
                    label = ::humanRetention,
                    onSelect = { retention = it },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Everyone in the group holds everyone's history — that is what having no " +
                        "server means. This setting is what each phone applies to its own copy, " +
                        "so a member who chose a longer window still has your track after yours " +
                        "is gone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionCard(padding = PaddingValues(16.dp)) {
            Text("History on this phone", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Deletes every stored position for this group here. It cannot reach the copies " +
                    "on anyone else's phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { confirmingDelete = true }) {
                Text("Delete history", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                onSave(
                    Control.LocationSettings(
                        enabled = enabled,
                        sampleSec = sampleSec,
                        pushSec = maxOf(pushSec, sampleSec),
                        minDistanceM = minDistance,
                        retentionHours = retention,
                    ),
                )
                onBack()
            },
            enabled = dirty,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save for everyone")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Saved settings reach the others the next time their phones are online.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete location history?") },
            text = {
                Text(
                    "Every position stored for this group on this phone will be removed, " +
                        "including your own. Copies on other members' phones are not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteHistory()
                    confirmingDelete = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** A wrapping row of choices. Cheaper than a slider to read and impossible to set to a value
 * that does not exist, which is the whole reason these are presets. */
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (option in options) {
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        RoundedCornerShape(20.dp),
                    )
                    .border(
                        1.dp,
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        RoundedCornerShape(20.dp),
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

private fun humanRetention(hours: Int): String = when {
    hours <= 0 -> "Forever"
    hours < 24 -> if (hours == 1) "1 hour" else "$hours hours"
    hours % (24 * 7) == 0 && hours >= 24 * 7 -> {
        val weeks = hours / (24 * 7)
        if (weeks == 1) "1 week" else "$weeks weeks"
    }
    else -> {
        val days = hours / 24
        if (days == 1) "1 day" else "$days days"
    }
}

// The offered values. Nothing below Control.MIN_SAMPLE_SEC appears, since the decoder would
// clamp it on arrival anyway and a control that silently means something else is worse than one
// that does not exist.
private val SAMPLE_OPTIONS = listOf(10, 30, 60, 120, 300, 900)
private val PUSH_OPTIONS = listOf(30, 60, 300, 900, 1_800, 3_600)
private val DISTANCE_OPTIONS = listOf(0, 10, 25, 50, 100, 250)
private val RETENTION_OPTIONS = listOf(1, 6, 24, 24 * 7, 24 * 30, 0)
