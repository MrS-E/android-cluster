package ch.stix.android_cluster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import ch.stix.android_cluster.R
import ch.stix.android_cluster.engine.PortMapping
import ch.stix.android_cluster.ui.theme.Dimens

/**
 * Port-mapping editor: add or remove host → guest mappings applied as QEMU
 * `hostfwd` rules. Invalid or duplicate ports are rejected by the repository
 * and surfaced through [mappingError].
 */
@Composable
fun PortMappingCard(
    mappings: List<PortMapping>,
    mappingError: String?,
    onAdd: (Int, Int) -> Unit,
    onRemove: (PortMapping) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hostPort by remember { mutableStateOf("") }
    var guestPort by remember { mutableStateOf("") }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Dimens.medium),
            verticalArrangement = Arrangement.spacedBy(Dimens.small),
        ) {
            Text(
                text = stringResource(R.string.ports_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.ports_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (mappings.isEmpty()) {
                Text(
                    text = stringResource(R.string.ports_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                mappings.forEach { mapping ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.mapping_arrow,
                                mapping.hostPort,
                                mapping.guestPort,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemove(mapping) }) {
                            Text(stringResource(R.string.action_delete_image))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = hostPort,
                    onValueChange = { hostPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.ports_host_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = guestPort,
                    onValueChange = { guestPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.ports_guest_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            if (mappingError != null) {
                Text(
                    text = mappingError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TextButton(
                onClick = {
                    val host = hostPort.toIntOrNull()
                    val guest = guestPort.toIntOrNull()
                    if (host != null && guest != null) {
                        onAdd(host, guest)
                        hostPort = ""
                        guestPort = ""
                    }
                },
                enabled = hostPort.isNotEmpty() && guestPort.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_add_mapping))
            }
        }
    }
}
