package ch.stix.android_cluster.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import ch.stix.android_cluster.R
import ch.stix.android_cluster.data.VmStorageInfo
import ch.stix.android_cluster.ui.theme.AndroidclusterTheme
import ch.stix.android_cluster.ui.theme.Dimens
import java.io.File

@Composable
fun StorageCard(
    info: VmStorageInfo,
    enabled: Boolean,
    onChooseFolder: () -> Unit,
    onResetFolder: () -> Unit,
    onClearStorage: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Dimens.medium),
            verticalArrangement = Arrangement.spacedBy(Dimens.small),
        ) {
            Text(
                text = stringResource(R.string.storage_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    if (info.isCustomFolder) R.string.storage_folder_custom
                    else R.string.storage_folder_default,
                    info.customFolderName.orEmpty(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.storage_mount_point, info.mountTag),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.storage_items_summary,
                    info.fileCount,
                    Formatter.formatShortFileSize(context, info.sizeBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (info.isSyncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.storage_syncing),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            info.syncError?.let { error ->
                Text(
                    text = stringResource(R.string.storage_sync_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onClearError, enabled = enabled) {
                    Text(stringResource(R.string.action_dismiss_storage_error))
                }
            }

            TextButton(onClick = onChooseFolder, enabled = enabled) {
                Text(stringResource(R.string.action_choose_storage_folder))
            }
            if (info.isCustomFolder) {
                TextButton(onClick = onResetFolder, enabled = enabled) {
                    Text(stringResource(R.string.action_reset_storage_folder))
                }
            }
            TextButton(
                onClick = { showClearConfirmation = true },
                enabled = enabled,
            ) {
                Text(stringResource(R.string.action_clear_storage))
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.storage_clear_confirmation_title)) },
            text = { Text(stringResource(R.string.storage_clear_confirmation_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClearStorage()
                    },
                ) {
                    Text(stringResource(R.string.action_clear_storage))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StorageCardPreview() {
    AndroidclusterTheme {
        StorageCard(
            info = VmStorageInfo(
                imageName = "demo.qcow2",
                localDirectory = File("/data/vm-storage/demo.qcow2"),
                customFolderName = "Shared files",
                sizeBytes = 1_048_576,
                fileCount = 3,
            ),
            enabled = true,
            onChooseFolder = {},
            onResetFolder = {},
            onClearStorage = {},
            onClearError = {},
            modifier = Modifier.padding(Dimens.medium),
        )
    }
}