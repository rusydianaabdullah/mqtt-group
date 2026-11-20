package id.larus.mqttgroup.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import id.larus.mqttgroup.data.db.BrokerEntity

@Composable
fun CreateGroupScreen(
    /** Every saved broker. Picking one here is how a new group reuses another group's connection
     * settings rather than having them typed in again. */
    brokers: List<BrokerEntity>,
    /** Broker id -> the names of the groups already on it, so the choice can say what it means
     * in terms of groups rather than of hostnames. */
    groupsPerBroker: Map<String, List<String>>,
    onAddBroker: () -> Unit,
    onCreate: (name: String, brokerId: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var brokerId by remember { mutableStateOf<String?>(null) }

    // Brokers come back oldest first, so the newest is the last one — and after "Add another
    // broker" the newest is the one just typed in, which is certainly the one that was meant.
    // Keyed on the list so that adding one moves the selection to it, but written only when the
    // current choice has gone (or was never made), so an explicit pick is not overruled by an
    // unrelated change to the list.
    LaunchedEffect(brokers) {
        if (brokers.none { it.id == brokerId }) brokerId = brokers.lastOrNull()?.id
    }

    FormScreen(title = "New group", onBack = onCancel) {
        Text(
            "A key for this group is generated on your phone. It leaves only inside the invite " +
                "QR code, so show that code in person — anyone who photographs it can read the " +
                "group.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(48) },
            label = { Text("Group name") },
            placeholder = { Text("Keluarga") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        SectionLabel("Broker")
        SectionCard(padding = PaddingValues(16.dp)) {
            Text(
                "Which broker this group's messages travel through. Groups on the same broker " +
                    "share one connection; a group on its own broker is invisible to the others' " +
                    "servers entirely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            brokers.forEachIndexed { index, option ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { brokerId = option.id },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${option.host}:${option.port}", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = groupsPerBroker[option.id]
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { "Already used by ${it.joinToString(", ")}" }
                                ?: "Not used by any group yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RadioButton(selected = option.id == brokerId, onClick = { brokerId = option.id })
                }
            }
            if (brokers.isNotEmpty()) Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAddBroker) {
                Text(if (brokers.isEmpty()) "Add a broker" else "Add another broker")
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { brokerId?.let { onCreate(name, it) } },
            enabled = name.isNotBlank() && brokerId != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Create")
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
