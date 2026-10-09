package ch.stix.android_cluster.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.stix.android_cluster.R
import ch.stix.android_cluster.service.QemuForegroundService
import ch.stix.android_cluster.ui.components.BatteryCard
import ch.stix.android_cluster.ui.components.ImageCard
import ch.stix.android_cluster.ui.components.PortMappingCard
import ch.stix.android_cluster.ui.components.StatusCard
import ch.stix.android_cluster.ui.theme.AndroidclusterTheme
import ch.stix.android_cluster.ui.theme.Dimens

/**
 * Single screen of the app: VM status, image selection, port mappings,
 * battery-optimization state and the serial console.
 */
@Composable
fun ClusterScreen(
    viewModel: ClusterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { viewModel.importImage(it) }
    }

    val exemptionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        // The user may have changed the exemption; re-read the real state.
        viewModel.refreshBatteryState()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* The VM still starts; the notification is simply not shown. */ }

    /** Starts the VM and hosts it in the foreground service. */
    fun startVm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.startVm()
        QemuForegroundService.start(context)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(Dimens.medium),
            verticalArrangement = Arrangement.spacedBy(Dimens.medium),
        ) {
            Header()

            StatusCard(
                state = state,
                onStart = { startVm() },
                onStop = viewModel::stopVm,
            )

            BatteryCard(
                state = state,
                onRequestExemption = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        exemptionLauncher.launch(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                },
            )

            ImageCard(
                images = state.images,
                selectedImage = state.selectedImage,
                onSelect = viewModel::selectImage,
                onDelete = viewModel::deleteImage,
                onImport = {
                    imagePicker.launch(arrayOf("*/*"))
                },
            )

            PortMappingCard(
                mappings = state.portMappings,
                mappingError = state.mappingError,
                onAdd = viewModel::addMapping,
                onRemove = viewModel::removeMapping,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(Dimens.medium),
                    verticalArrangement = Arrangement.spacedBy(Dimens.small),
                ) {
                    Text(
                        text = stringResource(R.string.console_card_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ConsolePanel(
                        lines = state.consoleLines,
                        onSend = viewModel::sendConsoleInput,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.extraSmall)) {
        Text(
            text = stringResource(R.string.screen_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.screen_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ClusterScreenPreview() {
    AndroidclusterTheme {
        ClusterScreenPreviewContent()
    }
}

@Composable
private fun ClusterScreenPreviewContent() {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(Dimens.medium),
            verticalArrangement = Arrangement.spacedBy(Dimens.medium),
        ) {
            Header()
            StatusCard(state = ClusterUiState(), onStart = {}, onStop = {})
            BatteryCard(state = ClusterUiState(), onRequestExemption = {})
            ImageCard(
                images = emptyList(),
                selectedImage = null,
                onSelect = {},
                onDelete = {},
                onImport = {},
            )
            PortMappingCard(
                mappings = emptyList(),
                mappingError = null,
                onAdd = { _, _ -> },
                onRemove = {},
            )
        }
    }
}
