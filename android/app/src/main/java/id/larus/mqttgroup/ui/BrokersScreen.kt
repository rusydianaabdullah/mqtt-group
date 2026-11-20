package id.larus.mqttgroup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.larus.mqttgroup.data.db.BrokerEntity
import id.larus.mqttgroup.mqtt.ConnectionState
import kotlinx.coroutines.launch

/**
 * Every broker this phone knows, and which groups are on each.
 *
 * A list rather than the single editor the app had until now, because a group names its own
 * broker: one family on a home Mosquitto, a group of friends on a public one, and nothing about
 * either arrangement visible to the other's server. Groups are the unit that matters, so each
 * row is labelled by the groups using it rather than by its own name — "the one Keluarga is on"
 * is how anyone actually refers to a broker.
 */
@Composable
fun BrokersScreen(
    brokers: List<BrokerEntity>,
    groupsPerBroker: Map<String, List<String>>,
    states: Map<String, ConnectionState>,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    /** Returns false when the broker still has groups on it, which is the one refusal this
     * screen has to explain. */
    onDelete: suspend (String) -> Boolean,
    onBack: () -> Unit,
) {
    var refused by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<BrokerEntity?>(null) }

    FormScreen(title = "Brokers", onBack = onBack) {
        Text(
            "Each group travels through one broker. Several groups can share one, and adding a " +
                "second lets a group live somewhere the others cannot be seen from.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        brokers.forEach { broker ->
            val users = groupsPerBroker[broker.id].orEmpty()
            SectionCard(padding = PaddingValues(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onEdit(broker.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${broker.host}:${broker.port}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        // The per-broker state, which is the thing the single aggregate in the
                        // app bar cannot tell you: with two brokers and one down, "Offline" up
                        // there does not say which half of your groups is stuck.
                        ConnectionLine(states[broker.id] ?: ConnectionState.Idle)
                    }
                    Text(
                        "Change",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (broker.tls) "TLS" else "No TLS")
                    if (broker.allowInsecureTls) Pill("Any certificate")
                    Pill(broker.topicPrefix)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (users.isEmpty()) {
                        "No groups on this broker"
                    } else {
                        users.joinToString(", ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (users.isEmpty()) {
                    // Offered only where it can succeed. A delete button that always refuses is
                    // worse than one that is not there.
                    TextButton(onClick = { confirming = broker }) { Text("Forget this broker") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Add a broker")
        }
    }

    confirming?.let { broker ->
        ConfirmForget(
            broker = broker,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                if (!onDelete(broker.id)) refused = broker.host
            },
        )
    }

    refused?.let { host ->
        AlertDialog(
            onDismissRequest = { refused = null },
            title = { Text("Still in use") },
            text = {
                Text(
                    "A group joined since this screen opened is using $host. Leave or delete " +
                        "that group first — forgetting the broker would leave it unable to " +
                        "connect, with nothing on screen to say why.",
                )
            },
            confirmButton = { TextButton(onClick = { refused = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ConfirmForget(
    broker: BrokerEntity,
    onDismiss: () -> Unit,
    onConfirm: suspend () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget ${broker.host}?") },
        text = {
            Text(
                "Removes the host, port and password from this phone. No group is using it, so " +
                    "nothing stops working — but you will have to type it in again to use it.",
            )
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { onConfirm() } }) { Text("Forget") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
