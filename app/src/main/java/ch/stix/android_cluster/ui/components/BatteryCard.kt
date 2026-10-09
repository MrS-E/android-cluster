package ch.stix.android_cluster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.stix.android_cluster.R
import ch.stix.android_cluster.ui.ClusterUiState
import ch.stix.android_cluster.ui.theme.Dimens

/**
 * Shows the battery-optimization exemption state and offers the system dialog
 * to request the exemption.
 */
@Composable
fun BatteryCard(
    state: ClusterUiState,
    onRequestExemption: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Dimens.medium),
            verticalArrangement = Arrangement.spacedBy(Dimens.small),
        ) {
            Text(
                text = stringResource(R.string.battery_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    if (state.batteryExempt) R.string.battery_exempt
                    else R.string.battery_not_exempt
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.batteryExempt) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            )
            if (!state.batteryExempt) {
                Button(onClick = onRequestExemption) {
                    Text(stringResource(R.string.action_request_exemption))
                }
            }
        }
    }
}
