package ch.stix.android_cluster.data

import android.content.Context
import ch.stix.android_cluster.engine.PortMapping
import ch.stix.android_cluster.engine.PortMappingValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persisted user configuration: the selected image and the port mappings. */
@Serializable
data class AppSettings(
    val selectedImage: String? = null,
    val portMappings: List<PortMapping> = emptyList(),
)

/**
 * Persists the selected VM image and the host-to-guest port mappings as JSON in
 * `filesDir`, so the configuration survives app restarts.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Human-readable results of mapping edits, surfaced to the UI. */
    private val _mappingError = MutableStateFlow<String?>(null)
    val mappingError: StateFlow<String?> = _mappingError.asStateFlow()

    init {
        _settings.value = read()
    }

    fun selectImage(name: String?) {
        update { it.copy(selectedImage = name) }
    }

    /**
     * Adds a host-to-guest mapping. Returns false (and sets [mappingError])
     * when the ports are out of range or the host port is already mapped.
     */
    fun addMapping(hostPort: Int, guestPort: Int): Boolean {
        return when (
            val result = PortMappingValidator.validate(_settings.value.portMappings, hostPort, guestPort)
        ) {
            is PortMappingValidator.Result.Invalid -> {
                _mappingError.value = result.message
                false
            }
            is PortMappingValidator.Result.Valid -> {
                _mappingError.value = null
                update { it.copy(portMappings = it.portMappings + result.mapping) }
                true
            }
        }
    }

    fun removeMapping(mapping: PortMapping) {
        _mappingError.value = null
        update { it.copy(portMappings = it.portMappings - mapping) }
    }

    fun clearMappingError() {
        _mappingError.value = null
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        scope.launch { write(updated) }
    }

    private fun read(): AppSettings {
        val file = settingsFile()
        if (!file.exists()) return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(file.readText()) }
            .getOrDefault(AppSettings())
    }

    private fun write(settings: AppSettings) {
        runCatching { settingsFile().writeText(json.encodeToString(settings)) }
    }

    private fun settingsFile(): File = File(context.filesDir, SETTINGS_FILE)

    private companion object {
        const val SETTINGS_FILE = "settings.json"
    }
}
