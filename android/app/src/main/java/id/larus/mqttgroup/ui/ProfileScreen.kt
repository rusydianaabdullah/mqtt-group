package id.larus.mqttgroup.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.larus.mqttgroup.data.blob.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Name and photo, the only thing the app asks for before it is usable. Both stay on this phone
 * until a group is joined, and even then they travel encrypted like everything else.
 */
@Composable
fun ProfileScreen(
    initialName: String,
    initialAvatar: ByteArray?,
    isOnboarding: Boolean,
    onSave: (name: String, avatar: ByteArray?) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialName) }
    var avatar by remember { mutableStateOf(initialAvatar) }
    var avatarError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Decode and shrink off the main thread: the picker hands back full-size camera
            // photos, and the whole point is that we never hold one longer than it takes to
            // make a 256px square out of it.
            val shrunk = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()?.let(Images::avatar)
            }
            if (shrunk == null) avatarError = "That image could not be read." else avatar = shrunk
        }
    }

    fun pick() {
        avatarError = null
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    FormScreen(
        title = if (isOnboarding) "Set up" else "Profile",
        onBack = onCancel,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isOnboarding) "Who are you?" else "Your profile",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Shown to the people in your groups. Nothing is sent anywhere until " +
                    "you join one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))
            Box(
                contentAlignment = Alignment.BottomEnd,
                modifier = Modifier.clickable(onClick = ::pick),
            ) {
                Avatar(bytes = avatar, name = name, size = 120.dp)
                // A small accent badge instead of a caption: it says "this is editable"
                // without adding a line of text under every avatar in the app.
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = ::pick) {
                Text(if (avatar == null) "Choose a photo" else "Change photo")
            }
            avatarError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onSave(name, avatar.takeIf { it !== initialAvatar }) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (isOnboarding) "Continue" else "Save")
            }
            onCancel?.let {
                TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            Spacer(Modifier.padding(bottom = 8.dp))
        }
    }
}
