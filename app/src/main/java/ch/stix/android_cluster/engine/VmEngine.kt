package ch.stix.android_cluster.engine

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle state of the VM engine.
 */
sealed interface VmState {
    data object Idle : VmState
    data object Running : VmState
    data class Stopped(val exitCode: Int) : VmState
    data class Failed(val message: String) : VmState
}

/**
 * Abstract VM engine so the UI and service never depend on QEMU specifics.
 * ARM ships now; another architecture slots in later without redesign.
 */
interface VmEngine {
    val state: StateFlow<VmState>
    val serialOutput: SharedFlow<String>

    /** True when the prebuilt binary for [arch] is present and executable. */
    fun isEngineInstalled(arch: VmArch): Boolean

    fun start(config: VmConfig)
    fun sendInput(text: String)
    fun stop()
}
