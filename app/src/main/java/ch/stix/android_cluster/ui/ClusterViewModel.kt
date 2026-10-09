package ch.stix.android_cluster.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.stix.android_cluster.data.BatteryOptimizationManager
import ch.stix.android_cluster.data.ImageRepository
import ch.stix.android_cluster.data.SettingsRepository
import ch.stix.android_cluster.data.VmImage
import ch.stix.android_cluster.data.VmRepository
import ch.stix.android_cluster.engine.PortMapping
import ch.stix.android_cluster.engine.VmArch
import ch.stix.android_cluster.engine.VmConfig
import ch.stix.android_cluster.engine.VmState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the cluster screen renders. */
data class ClusterUiState(
    val vmState: VmState = VmState.Idle,
    val engineInstalled: Boolean = true,
    val images: List<VmImage> = emptyList(),
    val selectedImage: String? = null,
    val portMappings: List<PortMapping> = emptyList(),
    val mappingError: String? = null,
    val consoleLines: List<String> = emptyList(),
    val batteryExempt: Boolean = false,
    val charging: Boolean = false,
) {
    val selectedImageResolved: VmImage? get() = images.firstOrNull { it.name == selectedImage }
    val hasImage: Boolean get() = selectedImageResolved != null
    val isRunning: Boolean get() = vmState is VmState.Running
    val canStart: Boolean get() = engineInstalled && hasImage && !isRunning
}

/**
 * Drives the cluster screen. Never touches the service directly: all VM
 * interaction goes through [VmRepository], which is hosted by the foreground
 * service, so the VM keeps running while the UI is gone.
 */
@HiltViewModel
class ClusterViewModel @Inject constructor(
    private val vmRepository: VmRepository,
    private val imageRepository: ImageRepository,
    private val settingsRepository: SettingsRepository,
    private val batteryManager: BatteryOptimizationManager,
) : ViewModel() {

    private val _transientState = MutableStateFlow(Transient())

    val uiState: StateFlow<ClusterUiState> = combine(
        combine(vmRepository.state, vmRepository.consoleLines) { state, lines -> state to lines },
        imageRepository.images,
        settingsRepository.settings,
        settingsRepository.mappingError,
        _transientState,
    ) { (vmState, consoleLines), images, settings, mappingError, transient ->
        ClusterUiState(
            vmState = vmState,
            engineInstalled = transient.engineInstalled,
            images = images,
            selectedImage = settings.selectedImage,
            portMappings = settings.portMappings,
            mappingError = mappingError,
            consoleLines = consoleLines,
            batteryExempt = transient.batteryExempt,
            charging = transient.charging,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ClusterUiState(),
    )

    init {
        refreshEnvironment()
        imageRepository.refresh()
    }

    /** Re-reads battery exemption and charging state, e.g. after returning from a dialog. */
    fun refreshEnvironment() {
        _transientState.value = _transientState.value.copy(
            engineInstalled = vmRepository.isEngineInstalled(VmArch.ARM),
            batteryExempt = batteryManager.isIgnoringBatteryOptimizations(),
            charging = batteryManager.isCharging(),
        )
    }

    fun startVm() {
        val image = uiState.value.selectedImageResolved ?: return
        val config = VmConfig(
            arch = VmArch.ARM,
            imagePath = image.path,
            portMappings = uiState.value.portMappings,
        )
        vmRepository.start(config)
    }

    fun stopVm() = vmRepository.stop()

    fun selectImage(image: VmImage) = settingsRepository.selectImage(image.name)

    fun importImage(uri: Uri) {
        viewModelScope.launch {
            val imported = imageRepository.import(uri)
            if (imported != null) {
                settingsRepository.selectImage(imported.name)
            }
        }
    }

    fun deleteImage(image: VmImage) {
        viewModelScope.launch {
            if (imageRepository.delete(image) && uiState.value.selectedImage == image.name) {
                settingsRepository.selectImage(null)
            }
        }
    }

    fun addMapping(hostPort: Int, guestPort: Int) {
        settingsRepository.addMapping(hostPort, guestPort)
    }

    fun removeMapping(mapping: PortMapping) = settingsRepository.removeMapping(mapping)

    fun clearMappingError() = settingsRepository.clearMappingError()

    fun sendConsoleInput(text: String) {
        if (text.isEmpty()) return
        vmRepository.sendInput("$text\n")
    }

    /** Called after the user returns from the system exemption dialog. */
    fun refreshBatteryState() = refreshEnvironment()

    private data class Transient(
        val engineInstalled: Boolean = true,
        val batteryExempt: Boolean = false,
        val charging: Boolean = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
