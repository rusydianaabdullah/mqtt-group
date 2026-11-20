package id.larus.mqttgroup.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.larus.mqttgroup.data.db.BrokerEntity
import id.larus.mqttgroup.mqtt.Topics

/** null while untested, then the outcome of the last "Test connection". */
sealed interface TestResult {
    data object Running : TestResult
    data object Success : TestResult
    data class Failure(val message: String) : TestResult
}

/**
 * Where the user points the app at a broker. The one screen that can leave someone stuck, so
 * it tests before it saves rather than after.
 */
@Composable
fun BrokerScreen(
    initial: BrokerEntity,
    testResult: TestResult?,
    onTest: (BrokerEntity) -> Unit,
    onSave: (BrokerEntity) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var tls by remember { mutableStateOf(initial.tls) }
    var insecure by remember { mutableStateOf(initial.allowInsecureTls) }
    var username by remember { mutableStateOf(initial.username.orEmpty()) }
    var password by remember { mutableStateOf(initial.password.orEmpty()) }
    var prefix by remember { mutableStateOf(initial.topicPrefix) }

    val portNumber = port.toIntOrNull()
    val prefixValid = Topics.isValidPrefix(prefix)
    val valid = host.isNotBlank() && portNumber in 1..65535 && prefixValid

    fun edited() = initial.copy(
        host = host.trim(),
        port = portNumber ?: initial.port,
        tls = tls,
        allowInsecureTls = tls && insecure,
        username = username.trim().ifBlank { null },
        password = password.ifBlank { null },
        topicPrefix = Topics.normalise(prefix),
    )

    FormScreen(title = "Broker", onBack = onCancel) {
        Text(
            "Messages are encrypted before they reach the broker, so it never sees what you " +
                "say. It does see which groups exist and when they are busy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        SectionLabel("Server")
        SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                isError = port.isNotEmpty() && portNumber !in 1..65535,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            SettingToggle(
                title = "TLS",
                detail = if (tls) "Encrypted link to the broker." else "Plain text on the wire — only on a network you control.",
                checked = tls,
                onCheckedChange = { tls = it; if (!it) insecure = false },
            )
            AnimatedVisibility(visible = tls) {
                SettingToggle(
                    title = "Accept any certificate",
                    detail = "For a self-signed broker on your own network. Someone able to " +
                        "intercept the connection could impersonate it.",
                    checked = insecure,
                    onCheckedChange = { insecure = it },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Credentials")
        SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                placeholder = { Text("Optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                placeholder = { Text("Optional") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Topics")
        SectionCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                label = { Text("Topic prefix") },
                supportingText = {
                    Text(
                        if (prefixValid) "Groups live under ${Topics.normalise(prefix)}/<group id>"
                        else "No spaces, and no + or # — those mean something to MQTT.",
                    )
                },
                isError = !prefixValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onTest(edited()) },
                enabled = valid && testResult !is TestResult.Running,
            ) {
                Text("Test connection")
            }
            when (testResult) {
                TestResult.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                TestResult.Success -> Pill("Reached it", dot = true)
                is TestResult.Failure -> Text(
                    testResult.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                null -> Unit
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onSave(edited()) },
            enabled = valid,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Save and connect")
        }
        onCancel?.let {
            TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
