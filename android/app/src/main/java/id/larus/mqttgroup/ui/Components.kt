package id.larus.mqttgroup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The pieces every screen is built from. Having them here rather than repeated per screen is
 * what keeps the app looking like one app — and it is where the two layout rules live that
 * were being got wrong: a row's trailing control is laid out by weight so it can never be
 * pushed off the edge, and any screen that can raise the keyboard pads for it.
 */

/**
 * A form screen: title, optional back, and a scrolling body that stays clear of the keyboard.
 *
 * `imePadding` on the scroll container is the part that matters. Without it the IME covers
 * whatever is at the bottom — usually the button the user is reaching for — and no amount of
 * scrolling reveals it, because the scroll area itself still believes it is full height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    onBack?.let {
                        IconButton(onClick = it) {
                            Icon(BackArrow, contentDescription = "Back")
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            content()
        }
    }
}

/** A grouped block, the way settings read on a modern phone. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(padding),
    ) {
        Column { content() }
    }
}

/**
 * A card for a caveat attached to something the user has switched on — not an error, and not
 * something they have to act on, but a limit worth knowing about before they rely on it, with
 * the one action that lifts it.
 *
 * Toned as a notice rather than an alarm: tertiaryContainer, not errorContainer. The thing it
 * describes is working; it is the edges that are not guaranteed.
 */
@Composable
fun NoticeCard(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Column {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/** Small all-caps label that separates one block of a screen from the next. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/**
 * A switch row. The text takes the leftover width via `weight`, so however long the
 * explanation is, the switch keeps its place at the trailing edge instead of being pushed
 * off-screen.
 */
@Composable
fun SettingToggle(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** The accent dot: live, unread, connected. The one coloured thing on an otherwise grey row. */
@Composable
fun AccentDot(size: Dp = 8.dp, color: Color = LocalAccent.current, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape),
    )
}

/** A compact status chip. Used for connection state and member roles. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
    dotColor: Color = LocalAccent.current,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .background(container, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) AccentDot(size = 7.dp, color = dotColor)
        Text(text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/**
 * Drawn rather than pulled from material-icons-extended, which is a several-megabyte
 * dependency for the handful of glyphs this app needs.
 */
val BackArrow: ImageVector by lazy {
    simpleIcon("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
}

/** The handset. Drawn rather than pulled from material-icons-extended, same as [BackArrow]. */
val CallIcon: ImageVector by lazy {
    simpleIcon(
        "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24" +
            "c1.12,0.37 2.33,0.57 3.57,0.57c0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1" +
            "c-9.39,0 -17,-7.61 -17,-17c0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1" +
            "c0,1.25 0.2,2.45 0.57,3.57c0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z",
    )
}

val CallEndIcon: ImageVector by lazy {
    simpleIcon(
        "M12,9c-1.6,0 -3.15,0.25 -4.6,0.72v3.1c0,0.39 -0.23,0.74 -0.56,0.9" +
            "c-0.98,0.49 -1.87,1.12 -2.66,1.85c-0.18,0.18 -0.43,0.29 -0.7,0.29" +
            "c-0.28,0 -0.53,-0.11 -0.71,-0.29L0.29,13.08C0.11,12.9 0,12.65 0,12.37" +
            "c0,-0.28 0.11,-0.53 0.29,-0.71C3.34,8.78 7.46,7 12,7s8.66,1.78 11.71,4.66" +
            "c0.18,0.18 0.29,0.43 0.29,0.71c0,0.28 -0.11,0.53 -0.29,0.71l-2.48,2.49" +
            "c-0.18,0.18 -0.43,0.29 -0.71,0.29c-0.27,0 -0.52,-0.11 -0.7,-0.28" +
            "c-0.79,-0.74 -1.69,-1.36 -2.67,-1.85c-0.33,-0.17 -0.56,-0.51 -0.56,-0.9" +
            "v-3.1C15.15,9.25 13.6,9 12,9z",
    )
}

/**
 * A phone shaking, which is what a buzz is.
 *
 * Replaces the handset everywhere the action used to be a call. A handset means "we are going to
 * talk"; this one means "your phone is about to make a noise", and the difference is the entire
 * feature.
 */
val BuzzIcon: ImageVector by lazy {
    simpleIcon(
        "M0,15h2V9H0V15z M3,17h2V7H3V17z M22,9v6h2V9H22z M19,7v10h2V7H19z " +
            "M16.5,3h-9C6.67,3 6,3.67 6,4.5v15C6,20.33 6.67,21 7.5,21h9c0.83,0 1.5,-0.67 1.5,-1.5" +
            "v-15C18,3.67 17.33,3 16.5,3z M16,19H8V5h8V19z",
    )
}

/**
 * The microphone, and the microphone with a line through it.
 *
 * The plain one was private to ChatScreen until the call screen needed the same glyph. Shared
 * here rather than copied, which is the whole reason this file exists.
 */
val MicIcon: ImageVector by lazy {
    simpleIcon(
        "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
            "M17,11c0,2.76 -2.24,5 -5,5s-5,-2.24 -5,-5H5c0,3.53 2.61,6.43 6,6.92V21h2v-3.08" +
            "c3.39,-0.49 6,-3.39 6,-6.92H17z",
    )
}

val MicOffIcon: ImageVector by lazy {
    simpleIcon(
        "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23C18.68,13.24 19,12.16 19,11z " +
            "M15,11.16V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18L15,11.16z " +
            "M4.27,3L3,4.27l6,6V11c0,1.66 1.34,3 3,3c0.23,0 0.44,-0.03 0.65,-0.08" +
            "l1.66,1.66C13.6,15.85 12.81,16 12,16c-2.76,0 -5,-2.24 -5,-5H5" +
            "c0,3.53 2.61,6.43 6,6.92V21h2v-3.08c0.91,-0.13 1.77,-0.45 2.52,-0.9" +
            "L19.73,21L21,19.73L4.27,3z",
    )
}

/** The little box-with-an-arrow that marks a button as leaving this app for a system screen. */
val OpenInNewIcon: ImageVector by lazy {
    simpleIcon(
        "M19,19H5V5h7V3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2V19z " +
            "M14,3v2h3.59l-9.83,9.83l1.41,1.41L19,6.41V10h2V3H14z",
    )
}
