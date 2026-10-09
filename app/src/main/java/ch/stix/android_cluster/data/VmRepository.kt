package ch.stix.android_cluster.data

import ch.stix.android_cluster.engine.VmConfig
import ch.stix.android_cluster.engine.VmEngine
import ch.stix.android_cluster.engine.VmArch
import ch.stix.android_cluster.engine.VmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the VM. Owns the [VmEngine] instance so the
 * process survives UI exit: ViewModels and the foreground service both talk
 * to this repository, never to each other.
 */
@Singleton
class VmRepository @Inject constructor(
    private val engine: VmEngine,
) {
    // App-scoped singleton: a SupervisorJob scope is acceptable here because
    // the repository lives as long as the process.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val state: StateFlow<VmState> get() = engine.state
    val serialOutput: SharedFlow<String> get() = engine.serialOutput

    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    init {
        scope.launch {
            engine.serialOutput.collect { line ->
                _consoleLines.value = (_consoleLines.value + line).takeLast(MAX_CONSOLE_LINES)
            }
        }
    }

    fun start(config: VmConfig) = engine.start(config)

    fun isEngineInstalled(arch: VmArch): Boolean = engine.isEngineInstalled(arch)

    fun sendInput(text: String) = engine.sendInput(text)

    fun stop() = engine.stop()

    companion object {
        private const val MAX_CONSOLE_LINES = 1000
    }
}
