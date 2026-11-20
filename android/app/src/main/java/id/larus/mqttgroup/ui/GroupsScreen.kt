package id.larus.mqttgroup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.larus.mqttgroup.data.db.GroupEntity
import id.larus.mqttgroup.mqtt.ConnectionState

/** The app's home once there is a broker: the groups you are in, and the two ways to get one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    groups: List<GroupEntity>,
    unreadCounts: Map<String, Int>,
    connection: ConnectionState,
    onReconnect: () -> Unit,
    onOpenGroup: (GroupEntity) -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Groups")
                        ConnectionLine(connection)
                    }
                },
                actions = {
                    ConnectionButton(connection = connection, onReconnect = onReconnect)
                    IconButton(onClick = onSettings) {
                        Icon(GearIcon, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            if (groups.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onCreateGroup,
                    text = { Text("New group") },
                    icon = { Icon(PlusIcon, contentDescription = null) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { insets ->
        if (groups.isEmpty()) {
            EmptyGroups(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
                onCreateGroup = onCreateGroup,
                onJoinGroup = onJoinGroup,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(groups, key = { it.groupId }) { group ->
                    GroupRow(
                        group = group,
                        unread = unreadCounts[group.groupId] ?: 0,
                        onClick = { onOpenGroup(group) },
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onJoinGroup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text("Scan an invite")
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: GroupEntity, unread: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A monogram rather than a generic icon: at a glance the list is scanned by shape and
        // letter, not by reading every name.
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                group.name.trim().take(1).uppercase().ifEmpty { "#" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (group.isAdmin) "You are the admin" else "Member",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .background(LocalAccent.current, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (group.isAdmin) Pill("Admin", dot = true)
    }
}

@Composable
private fun EmptyGroups(
    modifier: Modifier,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AccentDot(size = 14.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text("No groups yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Create one and show the QR code to the people you want in it, or scan the code " +
                "someone else is showing you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        ExtendedFloatingActionButton(
            onClick = onCreateGroup,
            text = { Text("Create a group") },
            icon = { Icon(PlusIcon, contentDescription = null) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onJoinGroup) { Text("Scan an invite") }
    }
}

/**
 * A single line under the title. Connected shows an accent dot and nothing else — a working
 * connection should not spend a whole banner saying so.
 *
 * Pass [onReconnect] on a screen with no separate connection button, and the line becomes the
 * reconnect control while it is showing anything other than "Connected". A status word someone
 * is already reading is a better button than an icon they have to notice exists, and it costs no
 * room in the bar — which is the whole reason chat and map show the state here rather than as a
 * pill among the actions.
 */
@Composable
fun ConnectionLine(connection: ConnectionState, onReconnect: (() -> Unit)? = null) {
    val (text, showDot) = when (connection) {
        ConnectionState.Connected -> "Connected" to true
        ConnectionState.Connecting -> "Connecting…" to false
        ConnectionState.Idle -> "Not connected" to false
        is ConnectionState.Waiting -> "Offline — messages will send later" to false
    }
    val retry = onReconnect?.takeIf { connection != ConnectionState.Connected }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (retry == null) Modifier else Modifier.clickable(onClick = retry),
    ) {
        if (showDot) {
            AccentDot(size = 7.dp)
        } else {
            AccentDot(size = 7.dp, color = MaterialTheme.colorScheme.outline)
        }
        Text(
            if (retry == null) text else "$text · tap to retry",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Online" is a plain label — there is nothing to tap when it is already true. Anything else
 * reads "Offline" and doubles as the reconnect button, since a status word someone already has
 * to look at is a better button than a separate icon they have to notice exists.
 */
@Composable
fun ConnectionButton(connection: ConnectionState, onReconnect: () -> Unit) {
    val online = connection == ConnectionState.Connected
    Row(
        modifier = Modifier
            .padding(end = 4.dp)
            .background(
                if (online) Color.Transparent else MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(50),
            )
            .let { if (online) it else it.clickable(onClick = onReconnect) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AccentDot(size = 7.dp, color = if (online) LocalAccent.current else MaterialTheme.colorScheme.error)
        Text(
            if (online) "Online" else "Offline",
            style = MaterialTheme.typography.labelMedium,
            color = if (online) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private val PlusIcon by lazy { simpleIcon("M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }

private val GearIcon by lazy {
    simpleIcon(
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
            "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22" +
            "l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41" +
            "h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33" +
            "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58" +
            "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61" +
            "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54" +
            "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54" +
            "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32" +
            "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94z M12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6" +
            "s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
    )
}

/**
 * Builds a 24dp icon from a path string. Cheaper than the material-icons-extended artifact.
 *
 * [evenOdd] for paths written here rather than lifted from Material. Material's own paths are
 * authored with the winding that makes non-zero fill produce their holes; a hand-drawn shape
 * whose inner contour happens to run the same way as its outer one fills solid under that rule,
 * which shows up as a blob where a ring was meant to be.
 */
internal fun simpleIcon(pathData: String, evenOdd: Boolean = false) =
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(pathData).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
            pathFillType = if (evenOdd) {
                androidx.compose.ui.graphics.PathFillType.EvenOdd
            } else {
                androidx.compose.ui.graphics.PathFillType.NonZero
            },
        )
    }.build()
