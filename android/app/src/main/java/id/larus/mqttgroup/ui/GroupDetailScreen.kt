package id.larus.mqttgroup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.larus.mqttgroup.R
import id.larus.mqttgroup.data.db.GroupEntity
import id.larus.mqttgroup.data.db.MemberEntity

/**
 * One group: who is in it, the invite code, and the ways to leave or end it.
 *
 * The wording around removing someone is deliberate. It tells every other phone to stop
 * showing them, but the person still holds the group key, so it is a request honoured by
 * honest clients rather than a lock. Saying that here is better than letting someone believe
 * they have cut off access they have not.
 */
@Composable
fun GroupDetailScreen(
    group: GroupEntity,
    members: List<MemberEntity>,
    selfUserId: String,
    inviteCode: String?,
    avatarFor: (MemberEntity) -> ByteArray?,
    onKick: (MemberEntity) -> Unit,
    /** Dial one member. Null while there is no connection to place a call over, which is what
     * greys the button out rather than letting someone press it into the void. */
    onCall: ((MemberEntity) -> Unit)?,
    /** Rings every member at once. Null for the same reason [onCall] is: nothing to publish
     * through. */
    onCallGroup: (() -> Unit)?,
    onLeave: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onBack: () -> Unit,
) {
    var showingInvite by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var kicking by remember { mutableStateOf<MemberEntity?>(null) }
    var confirmingExit by remember { mutableStateOf(false) }

    val active = members.count { !it.isRemoved }

    FormScreen(title = group.name, onBack = onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("$active ${if (active == 1) "member" else "members"}")
            Spacer(Modifier.width(8.dp))
            if (group.isAdmin) Pill("Admin", dot = true)
        }

        if (group.isAdmin && inviteCode != null) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Invite")
            SectionCard {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (showingInvite) {
                        // The code sits on its own white plate whatever the theme: an inverted
                        // QR is legible to a person and unreadable to half the scanners.
                        Box(
                            modifier = Modifier
                                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(20.dp))
                                .padding(16.dp),
                        ) {
                            QrCode(inviteCode)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Anyone who photographs this code, or gets a copy of the text " +
                                "version, can read the group — treat it like a password.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(inviteCode)) }) {
                                Text("Copy as text")
                            }
                            TextButton(onClick = { showingInvite = false }) { Text("Hide code") }
                        }
                    } else {
                        Text("Add someone", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Show the QR code to them in person.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { showingInvite = true },
                        ) {
                            Text("Show QR code")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        // Two entry points, because they are two different intentions: this rings everyone and
        // opens a room, the per-member button below rings one person.
        onCallGroup?.let { callGroup ->
            OutlinedButton(onClick = callGroup, modifier = Modifier.fillMaxWidth()) {
                Icon(BuzzIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.buzz_group))
            }
            Spacer(Modifier.height(6.dp))
            // Said before anybody presses it, not discovered afterwards: a family member who
            // can override your silent switch should not be a surprise.
            Text(
                stringResource(R.string.buzz_explains_group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }

        SectionLabel("Members")
        SectionCard(padding = PaddingValues(vertical = 8.dp, horizontal = 16.dp)) {
            members.forEachIndexed { index, member ->
                if (index > 0) {
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                MemberRow(
                    member = member,
                    isSelf = member.userId == selfUserId,
                    avatar = avatarFor(member),
                    canKick = group.isAdmin && member.userId != selfUserId && !member.isRemoved,
                    onKick = { kicking = member },
                    // Nobody calls themselves, and nobody calls a member who has been removed:
                    // they would not be listening on this group's topic any more.
                    onCall = onCall
                        ?.takeIf { member.userId != selfUserId && !member.isRemoved }
                        ?.let { call -> { call(member) } },
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("Danger zone")
        OutlinedButton(
            onClick = { confirmingExit = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                if (group.isAdmin) "Delete group for everyone" else "Leave group",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    kicking?.let { member ->
        AlertDialog(
            onDismissRequest = { kicking = null },
            title = { Text("Remove ${member.displayName.ifBlank { "this person" }}?") },
            text = {
                Text(
                    "Everyone's app will stop showing them and they will stop receiving new " +
                        "messages here. They still hold this group's key, though, so someone " +
                        "determined could keep reading what is published. To be certain, " +
                        "delete this group and start a new one.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onKick(member); kicking = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { kicking = null }) { Text("Cancel") } },
        )
    }

    if (confirmingExit) {
        AlertDialog(
            onDismissRequest = { confirmingExit = false },
            title = { Text(if (group.isAdmin) "Delete this group?" else "Leave this group?") },
            text = {
                Text(
                    if (group.isAdmin) {
                        "Every member's app deletes the group, its messages and its key. This " +
                            "cannot be undone."
                    } else {
                        "Your copy of the messages and the group key is deleted from this phone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingExit = false
                    if (group.isAdmin) onDeleteForEveryone() else onLeave()
                }) {
                    Text(if (group.isAdmin) "Delete" else "Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingExit = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemberRow(
    member: MemberEntity,
    isSelf: Boolean,
    avatar: ByteArray?,
    canKick: Boolean,
    onKick: () -> Unit,
    onCall: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Avatar(bytes = avatar, name = member.displayName, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append(member.displayName.ifBlank { "Waiting for their name…" })
                        if (isSelf) append(" (you)")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                val note = when {
                    member.isRemoved -> "Removed"
                    member.isAdmin -> "Admin"
                    member.lastSeenAt == 0L -> "Not seen yet"
                    else -> null
                }
                note?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        onCall?.let {
            IconButton(onClick = it) {
                Icon(
                    BuzzIcon,
                    contentDescription = stringResource(R.string.buzz_action),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (canKick) {
            TextButton(onClick = onKick) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
