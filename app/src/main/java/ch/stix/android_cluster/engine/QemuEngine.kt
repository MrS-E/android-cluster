package ch.stix.android_cluster.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a prebuilt static QEMU binary (packaged in `jniLibs`, extracted to
 * `nativeLibraryDir`) as a child process and exposes its serial console via
 * Flows. ARM is implemented; other architectures slot in via [VmArch].
 *
 * Constructed in [ch.stix.android_cluster.di.AppModule] (needs the
 * context-derived `nativeLibraryDir`), so no `@Inject` constructor here.
 */
class QemuEngine(
    private val nativeLibraryDir: String,
    private val ioDispatcher: CoroutineDispatcher,
) : VmEngine {

    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()

    private val _serialOutput = MutableSharedFlow<String>(replay = CONSOLE_HISTORY, extraBufferCapacity = 256)
    override val serialOutput: SharedFlow<String> = _serialOutput.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    @Volatile
    private var process: Process? = null

    /** Set by [stop] so an intentional shutdown is reported as Stopped, not Failed. */
    @Volatile
    private var stopRequested = false

    override fun isEngineInstalled(arch: VmArch): Boolean =
        File(nativeLibraryDir, arch.binaryName).canExecute()

    override fun start(config: VmConfig) {
        if (_state.value is VmState.Running) return
        stopRequested = false

        val binary = File(nativeLibraryDir, config.arch.binaryName)
        if (!binary.canExecute()) {
            _state.value = VmState.Failed(
                "Engine binary not installed: ${config.arch.binaryName} missing in $nativeLibraryDir"
            )
            return
        }

        _state.value = VmState.Running
        scope.launch {
            try {
                val proc = withContext(ioDispatcher) {
                    ProcessBuilder(buildCommand(config))
                        .redirectErrorStream(true)
                        .start()
                }
                process = proc

                // Pump serial output (stdout + stderr merged) into the flow.
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        _serialOutput.tryEmit(line)
                    }
                }

                val exitCode = proc.waitFor()
                _state.value = if (exitCode == 0 || stopRequested) {
                    VmState.Stopped(exitCode)
                } else {
                    VmState.Failed("QEMU exited with code $exitCode")
                }
            } catch (e: Exception) {
                _state.value = if (stopRequested) {
                    VmState.Stopped(-1)
                } else {
                    VmState.Failed(e.message ?: e.javaClass.simpleName)
                }
            } finally {
                process = null
            }
        }
    }

    override fun sendInput(text: String) {
        val proc = process ?: return
        scope.launch {
            try {
                proc.outputStream.apply {
                    write(text.toByteArray())
                    flush()
                }
            } catch (e: Exception) {
                _serialOutput.tryEmit("sendInput failed: ${e.message}")
            }
        }
    }

    override fun stop() {
        stopRequested = true
        process?.let { proc ->
            scope.launch {
                proc.destroy()
                // Give QEMU a moment to exit cleanly, then force-kill.
                repeat(GRACEFUL_STOP_ATTEMPTS) {
                    if (!proc.isAlive) return@launch
                    delay(GRACEFUL_STOP_INTERVAL_MS)
                }
                proc.destroyForcibly()
            }
        }
    }

    /**
     * Builds the QEMU command line for the configured architecture.
     * Pure function — unit-tested without launching anything.
     */
    fun buildCommand(config: VmConfig): List<String> {
        val binary = File(nativeLibraryDir, config.arch.binaryName).absolutePath
        return when (config.arch) {
            VmArch.ARM -> buildArmCommand(binary, config)
        }
    }

    private fun buildArmCommand(binary: String, config: VmConfig): List<String> = buildList {
        add(binary)
        add("-M"); add("virt")
        add("-cpu"); add("cortex-a15")
        add("-m"); add("${config.memoryMb}")
        add("-nographic")
        add("-serial"); add("mon:stdio")
        add("-kernel"); add(config.imagePath)
        add("-append"); add("console=ttyAMA0")
        if (config.portMappings.isNotEmpty()) {
            val hostfwd = config.portMappings.joinToString(",") {
                "hostfwd=tcp::${it.hostPort}-:${it.guestPort}"
            }
            add("-netdev"); add("user,id=n0,$hostfwd")
            add("-device"); add("virtio-net-pci,netdev=n0")
        }
    }

    companion object {
        private const val CONSOLE_HISTORY = 500
        private const val GRACEFUL_STOP_ATTEMPTS = 10
        private const val GRACEFUL_STOP_INTERVAL_MS = 200L
    }
}
