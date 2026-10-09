package ch.stix.android_cluster.engine

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
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
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Runs a prebuilt static QEMU binary (packaged in `jniLibs`, extracted to
 * `nativeLibraryDir`) as a child process and exposes its serial console via
 * Flows. AArch64 is implemented; other architectures slot in via [VmArch].
 *
 * Constructed in [ch.stix.android_cluster.di.AppModule] (needs the
 * context-derived `nativeLibraryDir`), so no `@Inject` constructor here.
 */
class QemuEngine(
    private val nativeLibraryDir: String,
    private val qemuDataDirectory: File,
    private val assetManager: AssetManager,
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
        if (_state.value is VmState.Running) {
            Log.w(TAG, "Ignoring start request; QEMU is already running")
            return
        }
        stopRequested = false

        val binary = File(nativeLibraryDir, config.arch.binaryName)
        if (!binary.canExecute()) {
            val message = "Engine binary not installed: ${config.arch.binaryName} missing in $nativeLibraryDir"
            Log.e(TAG, message)
            _serialOutput.tryEmit(message)
            _state.value = VmState.Failed(message)
            return
        }

        _state.value = VmState.Running
        scope.launch {
            var launchedProcess: Process? = null
            try {
                val image = File(config.imagePath)
                Log.i(
                    TAG,
                    "Start requested: arch=${config.arch}, image=${image.absolutePath}, " +
                        "exists=${image.exists()}, readable=${image.canRead()}, sizeBytes=${image.length()}, " +
                        "requestedMemoryMb=${config.memoryMb}, effectiveMemoryMb=${memoryMbFor(config)}, " +
                        "portMappings=${config.portMappings}",
                )
                if (!image.isFile || !image.canRead()) {
                    val message = "VM image is missing or unreadable: ${image.absolutePath}"
                    Log.e(TAG, message)
                    _serialOutput.tryEmit(message)
                    _state.value = VmState.Failed(message)
                    return@launch
                }

                val runtimeAssets = requiredRuntimeAssets(config)
                if (runtimeAssets.isNotEmpty()) {
                    installRuntimeAssets(runtimeAssets)
                }
                if (stopRequested) {
                    _state.value = VmState.Stopped(-1)
                    return@launch
                }

                val command = buildCommand(config)
                Log.i(TAG, "Launching QEMU: ${formatCommand(command)}")
                val proc = ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
                launchedProcess = proc
                process = proc
                Log.i(TAG, "QEMU process started")
                if (stopRequested) {
                    Log.i(TAG, "Stop was requested during process startup")
                    proc.destroy()
                }

                val stderrTail = mutableListOf<String>()
                val exitCode = coroutineScope {
                    val stdoutJob = launch {
                        proc.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line -> _serialOutput.tryEmit(line) }
                        }
                    }
                    val stderrJob = launch {
                        proc.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                _serialOutput.tryEmit(line)
                                if (stderrTail.size == MAX_STDERR_LINES) stderrTail.removeAt(0)
                                stderrTail.add(line)
                                Log.e(TAG, "QEMU stderr: ${line.take(MAX_LOG_LINE_LENGTH)}")
                            }
                        }
                    }
                    val code = proc.waitFor()
                    stdoutJob.join()
                    stderrJob.join()
                    code
                }

                _state.value = if (exitCode == 0 || stopRequested) {
                    Log.i(TAG, "QEMU exited with code $exitCode${if (stopRequested) " after stop" else ""}")
                    VmState.Stopped(exitCode)
                } else {
                    val message = formatQemuExitFailure(exitCode, stderrTail)
                    Log.e(TAG, message)
                    _serialOutput.tryEmit(message)
                    VmState.Failed(message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (stopRequested) {
                    Log.i(TAG, "QEMU monitoring ended after requested stop: ${e.message ?: e.javaClass.simpleName}")
                    _state.value = VmState.Stopped(-1)
                } else {
                    val message = "Failed to start or monitor QEMU: ${e.message ?: e.javaClass.simpleName}"
                    Log.e(TAG, message, e)
                    _serialOutput.tryEmit(message)
                    _state.value = VmState.Failed(message)
                }
            } finally {
                process = null
                launchedProcess?.let { proc ->
                    if (proc.isAlive) {
                        Log.w(TAG, "QEMU process was still alive during cleanup; forcing shutdown")
                        proc.destroyForcibly()
                    }
                }
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val message = "Failed to send input to QEMU: ${e.message ?: e.javaClass.simpleName}"
                Log.e(TAG, message, e)
                _serialOutput.tryEmit(message)
            }
        }
    }

    override fun stop() {
        Log.i(TAG, "Stop requested")
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
        val isQcow2 = isQcow2Image(config.imagePath)
        add(binary)
        add("-M"); add("virt")
        add("-cpu"); add("cortex-a57")
        add("-m"); add("${memoryMbFor(config)}")
        add("-nographic")
        add("-serial"); add("mon:stdio")
        if (isQcow2) {
            add("-L"); add(qemuDataDirectory.absolutePath)
            add("-drive")
            add(
                "if=pflash,unit=0,format=raw,readonly=on," +
                    "file=${File(qemuDataDirectory, EDK2_CODE_FILE).absolutePath}",
            )
            add("-drive")
            add(
                "if=pflash,unit=1,format=raw," +
                    "file=${File(qemuDataDirectory, EDK2_VARIABLES_FILE).absolutePath}",
            )
            add("-drive")
            add("if=none,file=${escapeQemuOption(config.imagePath)},format=qcow2,id=hd0")
            add("-device"); add("virtio-blk-pci,drive=hd0,bootindex=0")
        } else {
            if (config.portMappings.isNotEmpty()) {
                add("-L"); add(qemuDataDirectory.absolutePath)
            }
            add("-kernel"); add(config.imagePath)
            add("-append"); add("console=ttyAMA0")
        }
        if (config.portMappings.isNotEmpty()) {
            val hostfwd = config.portMappings.joinToString(",") {
                "hostfwd=tcp::${it.hostPort}-:${it.guestPort}"
            }
            add("-netdev"); add("user,id=n0,$hostfwd")
            add("-device"); add("virtio-net-pci,netdev=n0")
        }
    }

    private suspend fun installRuntimeAssets(assetPaths: List<String>) = withContext(ioDispatcher) {
        if (!qemuDataDirectory.isDirectory && !qemuDataDirectory.mkdirs()) {
            throw IOException("Could not create QEMU data directory: ${qemuDataDirectory.absolutePath}")
        }

        assetPaths.forEach { assetPath ->
            val fileName = assetPath.substringAfterLast('/').removeSuffix(".qemugz")
            val target = File(qemuDataDirectory, fileName)
            val fingerprintFile = File(qemuDataDirectory, "$fileName.sha256")
            val preserveVariableStore = assetPath == EDK2_VARIABLES_ASSET
            if (preserveVariableStore && target.isFile && isUefiFlashImageSizeValid(target.length())) {
                Log.i(TAG, "Reusing UEFI variable store: ${target.absolutePath}")
                return@forEach
            }
            val assetFingerprint = if (assetPath == EDK2_CODE_ASSET) sha256Asset(assetPath) else null
            val storedFingerprint = fingerprintFile.takeIf { it.isFile }?.readText()
            if (
                assetFingerprint != null &&
                target.isFile &&
                canReuseUefiCodeImage(target.length(), storedFingerprint, assetFingerprint)
            ) {
                Log.i(TAG, "Reusing unchanged UEFI code image: ${target.absolutePath}")
                return@forEach
            }

            val temporary = File(qemuDataDirectory, "$fileName.tmp")
            try {
                assetManager.open(assetPath).use { assetInput ->
                    val input = if (assetPath.endsWith(".qemugz")) GZIPInputStream(assetInput) else assetInput
                    input.use {
                        temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                }
                if (temporary.length() == 0L) {
                    throw IOException("Bundled QEMU runtime asset is empty: $assetPath")
                }
                if (target.exists() && !target.delete()) {
                    throw IOException("Could not replace QEMU runtime file: ${target.absolutePath}")
                }
                if (!temporary.renameTo(target)) {
                    throw IOException("Could not install QEMU runtime file: ${target.absolutePath}")
                }
                assetFingerprint?.let { fingerprint -> fingerprintFile.writeText(fingerprint) }
                Log.i(TAG, "Installed QEMU runtime file: ${target.absolutePath} (${target.length()} bytes)")
            } catch (e: Exception) {
                temporary.delete()
                throw e
            }
        }
    }

    private fun sha256Asset(assetPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        assetManager.open(assetPath).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun requiredRuntimeAssets(config: VmConfig): List<String> = buildList {
        if (isQcow2Image(config.imagePath)) {
            add(EDK2_CODE_ASSET)
            add(EDK2_VARIABLES_ASSET)
        }
        if (isQcow2Image(config.imagePath) || config.portMappings.isNotEmpty()) {
            add(EFI_VIRTIO_ROM_ASSET)
        }
    }

    private fun formatCommand(command: List<String>): String =
        command.joinToString(" ") { argument -> "\"${argument.replace("\"", "\\\"")}\"" }

    private fun isQcow2Image(imagePath: String): Boolean =
        File(imagePath).extension.equals("qcow2", ignoreCase = true)

    private fun memoryMbFor(config: VmConfig): Int =
        if (isQcow2Image(config.imagePath)) config.memoryMb.coerceAtLeast(MIN_QCOW2_MEMORY_MB) else config.memoryMb

    private fun escapeQemuOption(value: String): String = value.replace(",", ",,")

    companion object {
        private const val TAG = "QemuEngine"
        private const val CONSOLE_HISTORY = 500
        private const val GRACEFUL_STOP_ATTEMPTS = 10
        private const val GRACEFUL_STOP_INTERVAL_MS = 200L
        private const val MAX_STDERR_LINES = 40
        private const val MAX_LOG_LINE_LENGTH = 4_000
        private const val MIN_QCOW2_MEMORY_MB = 1_024
        private const val EDK2_FLASH_SIZE_BYTES = 64L * 1024 * 1024
        private const val EDK2_CODE_FILE = "edk2-aarch64-code.fd"
        private const val EDK2_VARIABLES_FILE = "edk2-arm-vars.fd"
        private const val EDK2_CODE_ASSET = "qemu/$EDK2_CODE_FILE.qemugz"
        private const val EDK2_VARIABLES_ASSET = "qemu/$EDK2_VARIABLES_FILE.qemugz"
        private const val EFI_VIRTIO_ROM_ASSET = "qemu/efi-virtio.rom"

        internal fun isUefiFlashImageSizeValid(fileSizeBytes: Long): Boolean =
            fileSizeBytes == EDK2_FLASH_SIZE_BYTES

        internal fun canReuseUefiCodeImage(
            fileSizeBytes: Long,
            storedFingerprint: String?,
            assetFingerprint: String,
        ): Boolean =
            isUefiFlashImageSizeValid(fileSizeBytes) && storedFingerprint == assetFingerprint
    }
}

internal fun formatQemuExitFailure(exitCode: Int, errorOutput: List<String>): String {
    val details = errorOutput.takeLast(4).joinToString("; ").take(500)
    return if (details.isBlank()) {
        "QEMU exited with code $exitCode"
    } else {
        "QEMU exited with code $exitCode: $details"
    }
}
