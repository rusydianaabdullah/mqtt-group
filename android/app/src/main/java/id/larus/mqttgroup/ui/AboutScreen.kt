package id.larus.mqttgroup.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.larus.mqttgroup.R

/** One thing the app does, for the list under the hero. */
private data class Highlight(val icon: ImageVector, val label: Int)

/**
 * App identity, what it does, where it is going, and a way to reach out — the same shape as this
 * org's other listings, in this app's own blue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onContact: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    val highlights = listOf(
        Highlight(LockIcon, R.string.about_highlight_encrypted),
        Highlight(ServerIcon, R.string.about_highlight_broker),
        Highlight(MapPinIcon, R.string.about_highlight_map),
        Highlight(PersonOffIcon, R.string.about_highlight_noaccount),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.action_about), fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(BackArrow, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Hero(version = version)
            HighlightsCard(highlights)
            NextCard()
            ContactCard(onContact = onContact)
        }
    }
}

/** The identity block: the mark, the name, a version pill, and the one-line promise. */
@Composable
private fun Hero(version: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // A gradient rather than the flat primary the other apps use: this app's mark is
                // a ring on deep navy, and reproducing that here makes the screen read as the
                // same object as the launcher icon the user just tapped.
                .background(
                    Brush.linearGradient(listOf(Color(0xFF13254E), Color(0xFF0A1734))),
                )
                .padding(vertical = 32.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The launcher mark, redrawn at size rather than scaled from the adaptive icon:
            // that icon carries its own safe-zone padding, which at 96dp shows as a mark
            // floating small in the middle of nothing.
            Box(
                Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppMark,
                    contentDescription = null,
                    modifier = Modifier.size(76.dp),
                    tint = Color(0xFF4FA8FF),
                )
            }
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Pill(stringResource(R.string.about_version_format, version))
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
    )
}

/** What the app actually does, at a glance — four marks, not four paragraphs. */
@Composable
private fun HighlightsCard(highlights: List<Highlight>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            highlights.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        stringResource(item.label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Where this is going. Given its own card, and a heavier one than the highlights, because it is
 * the reason a stranger reading this screen might want to make contact — and because saying
 * "prototype" quietly at the bottom of a paragraph is how it gets missed.
 */
@Composable
private fun NextCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    SiteIcon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.about_next_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                stringResource(R.string.about_next_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
            )
        }
    }
}

/** The invitation to get in touch. The address itself stays behind the button. */
@Composable
private fun ContactCard(onContact: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.about_contact_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.about_contact_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onContact,
                modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    stringResource(R.string.action_visit_us),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun appVersion(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "—"

// Drawn from path data like the rest of the app's glyphs — see Components.simpleIcon.

/**
 * The launcher mark: the ring, the pin, the bubble punched out of it and the three dots inside
 * that. One path with even-odd fill, so it tints as a single icon — the four-contour structure
 * is what makes the holes, and drawing it as separate shapes would need four colours the
 * surrounding Icon() cannot give it.
 */
private val AppMark by lazy {
    simpleIcon(
        // Ring.
        "M12,1.8 A10.2,10.2 0 1,0 12,22.2 A10.2,10.2 0 1,0 12,1.8 Z" +
            "M12,3.6 A8.4,8.4 0 1,1 12,20.4 A8.4,8.4 0 1,1 12,3.6 Z" +
            // Pin, stopping just short of the ring's inner edge at the bottom.
            "M12,5.8 C9.3,5.8 7.2,7.9 7.2,10.6 C7.2,12.6 8.5,14.9 12,19.9" +
            "C15.5,14.9 16.8,12.6 16.8,10.6 C16.8,7.9 14.7,5.8 12,5.8 Z" +
            // Bubble, punched out of the pin.
            "M9.6,8.2 h4.8 a1.1,1.1 0 0,1 1.1,1.1 v2.6 a1.1,1.1 0 0,1 -1.1,1.1 h-2.6 l-1.7,1.5 v-1.5 h-0.5" +
            "a1.1,1.1 0 0,1 -1.1,-1.1 v-2.6 a1.1,1.1 0 0,1 1.1,-1.1 z" +
            // Three dots, filled again inside that hole.
            "M10.3,10.6 m-0.62,0 a0.62,0.62 0 1,0 1.24,0 a0.62,0.62 0 1,0 -1.24,0 z" +
            "M12,10.6 m-0.62,0 a0.62,0.62 0 1,0 1.24,0 a0.62,0.62 0 1,0 -1.24,0 z" +
            "M13.7,10.6 m-0.62,0 a0.62,0.62 0 1,0 1.24,0 a0.62,0.62 0 1,0 -1.24,0 z",
        evenOdd = true,
    )
}

private val LockIcon by lazy {
    simpleIcon(
        "M18,8h-1L17,6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2L6,8c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12" +
            "c1.1,0 2,-0.9 2,-2L20,10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2" +
            " -0.9,2 -2,2zM15.1,8L8.9,8L8.9,6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
    )
}

private val ServerIcon by lazy {
    simpleIcon(
        "M4,1h16c1.1,0 2,0.9 2,2v4c0,1.1 -0.9,2 -2,2L4,9c-1.1,0 -2,-0.9 -2,-2L2,3c0,-1.1 0.9,-2 2,-2z" +
            "M6,4v2h2L8,4L6,4z" +
            "M4,11h16c1.1,0 2,0.9 2,2v4c0,1.1 -0.9,2 -2,2L4,19c-1.1,0 -2,-0.9 -2,-2v-4c0,-1.1 0.9,-2 2,-2z" +
            "M6,14v2h2v-2L6,14z",
        evenOdd = true,
    )
}

private val MapPinIcon by lazy {
    simpleIcon(
        "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7z" +
            "M12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z",
    )
}

private val PersonOffIcon by lazy {
    simpleIcon(
        "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4z" +
            "M12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z" +
            "M3.4,2L2,3.4L20.6,22L22,20.6z",
    )
}

/** A hard hat: the shorthand for a site, and the one glyph that says "this is not just family". */
private val SiteIcon by lazy {
    simpleIcon(
        "M12,3c-0.9,0 -1.7,0.2 -2.4,0.6v4.1h-1.6c-1.9,1.1 -3.2,3.1 -3.4,5.4h14.8" +
            "c-0.2,-2.3 -1.5,-4.3 -3.4,-5.4h-1.6L14.4,3.6C13.7,3.2 12.9,3 12,3z" +
            "M11.1,3.9h1.8v3.8h-1.8L11.1,3.9z" +
            "M3,14.4v1.8c0,0.5 0.4,0.9 0.9,0.9h16.2c0.5,0 0.9,-0.4 0.9,-0.9v-1.8L3,14.4z",
    )
}
