package ch.stix.android_cluster.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.stix.android_cluster.R
import ch.stix.android_cluster.engine.VmState
import ch.stix.android_cluster.ui.ClusterUiState
import ch.stix.android_cluster.ui.theme.Dimens

/**
 * VM status card: state dot, human-readable status, charging hint and the
 * start/stop controls.
 */
@Composable
fun StatusCard(
    state: ClusterUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Dimens.medium),
            verticalArrangement = Arrangement.spacedBy(Dimens.small),
        ) {
            Text(
                text = stringResource(R.string.status_card_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.small),
            ) {
                StatusDot(running = state.isRunning)
                Text(
                    text = state.statusText(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                text = stringResource(
                    if (state.charging) R.string.charging_hint_plugged
                    else R.string.charging_hint_unplugged
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.small)) {
                Button(onClick = onStart, enabled = state.canStart) {
                    Text(stringResource(R.string.action_start))
                }
                OutlinedButton(onClick = onStop, enabled = state.isRunning) {
                    Text(stringResource(R.string.action_stop))
                }
            }
        }
    }
}

@Composable
private fun StatusDot(running: Boolean) {
    val color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .size(Dimens.statusDot)
            .background(color = color, shape = CircleShape),
        content = {},
    )
}

@Composable
private fun ClusterUiState.statusText(): String = when {
    !engineInstalled -> stringResource(R.string.status_engine_missing)
    isRunning -> stringResource(R.string.status_running)
    !hasImage -> stringResource(R.string.status_no_image)
    vmState is VmState.Stopped ->
        stringResource(R.string.status_stopped, vmState.exitCode)
    vmState is VmState.Failed ->
        stringResource(R.string.status_failed, vmState.message)
    else -> stringResource(R.string.status_idle)
}
