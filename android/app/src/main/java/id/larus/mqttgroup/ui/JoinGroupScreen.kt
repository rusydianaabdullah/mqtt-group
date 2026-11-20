package id.larus.mqttgroup.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Point the camera at someone's invite. The camera permission is asked for here, at the moment
 * it is obviously needed, rather than at startup.
 */
@Composable
fun JoinGroupScreen(
    error: String?,
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    var pasted by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    FormScreen(title = "Join a group", onBack = onCancel) {
        Text(
            "Ask the group's admin to show you their QR code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Square, so the viewfinder matches the shape of what it is looking for.
                .aspectRatio(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                QrScanner(onScanned = onScanned, modifier = Modifier.fillMaxSize())
                ScannerFrame()
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        "The camera is needed to read a QR code, and is used for nothing else.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { request.launch(Manifest.permission.CAMERA) },
                    ) {
                        Text("Allow camera")
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(20.dp))
        Text("Or paste an invite code", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "For when the admin sent it another way instead of showing the QR code in person.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Invite code") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { if (pasted.isNotBlank()) onScanned(pasted.trim()) },
            ),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onScanned(pasted.trim()) },
            enabled = pasted.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Join with this code")
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

/** Four accent corners over the preview — where to aim, without covering what is behind. */
@Composable
private fun ScannerFrame() {
    val accent = LocalAccent.current
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(36.dp),
    ) {
        val arm = size.minDimension * 0.16f
        val stroke = 5f * density
        val corners = listOf(
            androidx.compose.ui.geometry.Offset(0f, 0f) to listOf(1f to 0f, 0f to 1f),
            androidx.compose.ui.geometry.Offset(size.width, 0f) to listOf(-1f to 0f, 0f to 1f),
            androidx.compose.ui.geometry.Offset(0f, size.height) to listOf(1f to 0f, 0f to -1f),
            androidx.compose.ui.geometry.Offset(size.width, size.height) to listOf(-1f to 0f, 0f to -1f),
        )
        corners.forEach { (origin, directions) ->
            directions.forEach { (dx, dy) ->
                drawLine(
                    color = accent,
                    start = origin,
                    end = androidx.compose.ui.geometry.Offset(origin.x + dx * arm, origin.y + dy * arm),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}
