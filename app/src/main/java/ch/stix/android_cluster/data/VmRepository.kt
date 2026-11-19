package ch.stix.android_cluster.data

import android.util.Log
import ch.stix.android_cluster.engine.VmArch
import ch.stix.android_cluster.engine.VmConfig
import ch.stix.android_cluster.engine.VmEngine
import ch.stix.android_cluster.engine.VmState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for the VM. Owns the [VmEngine] instance so the
 * process survives UI exit: ViewModels and the foreground service both talk
 * to this repository, never to each other.
 */
@Singleton
class VmRepository internal constructor(
    private val engine: VmEngine,
    private val storageRepository: StorageRepository?,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        engine: VmEngine,
        storageRepository: StorageRepository,
    ) : this(
        engine = engine,
        storageRepository = storageRepository,
        ioDispatcher = Dispatchers.IO,
    )

    constructor(engine: VmEngine) : this(
        engine = engine,
        storageRepository = null,
        ioDispatcher = Dispatchers.IO,
    )

    // App-scoped singleton: a SupervisorJob scope is acceptable here because
    // the repository lives as long as the process.
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val lifecycleMutex = Mutex()
    private val startInProgress = AtomicBoolean(false)

    @Volatile
    private var stopRequestedDuringPrepare = false

    private val activeImageLock = Any()
    private var activeImageName: String? = null

    private val _state = MutableStateFlow<VmState>(engine.state.value)
    val state: StateFlow<VmState> = _state.asStateFlow()
    val serialOutput: SharedFlow<String> get() = engine.serialOutput

    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    init {
        scope.launch {
            engine.serialOutput.collect { line ->
                _consoleLines.value = (_consoleLines.value + line).takeLast(MAX_CONSOLE_LINES)
            }
        }
        scope.launch {
            engine.state.collect { vmState ->
                if (!startInProgress.get() || vmState is VmState.Running) {
                    _state.value = vmState
                }
                when (vmState) {
                    is VmState.Idle, is VmState.Running -> Unit
                    is VmState.Stopped, is VmState.Failed -> {
                        val imageToSync = consumeActiveImage()
                        if (imageToSync != null) {
                            lifecycleMutex.withLock {
                                try {
                                    storageRepository?.syncAfterStop(imageToSync)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (e: Exception) {
                                    logW("Failed to sync storage after VM stop for '$imageToSync'", e)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun start(config: VmConfig) {
        if (_state.value is VmState.Running || engine.state.value is VmState.Running) return
        if (!startInProgress.compareAndSet(false, true)) return

        stopRequestedDuringPrepare = false
        storageRepository?.setVmStarting(true)
        _state.value = VmState.Running
        val imageName = config.imagePath.takeIf { it.isNotBlank() }?.let { File(it).name }.orEmpty()

        scope.launch {
            lifecycleMutex.withLock {
                var engineStarted = false
                try {
                    if (stopRequestedDuringPrepare || engine.state.value is VmState.Running) {
                        if (stopRequestedDuringPrepare && engine.state.value !is VmState.Running) {
                            _state.value = VmState.Stopped(-1)
                        }
                        return@withLock
                    }
                    val localStorageDir = imageName.takeIf { it.isNotEmpty() }
                        ?.let { name ->
                            runCatching { storageRepository?.storageDirectoryFor(name) }.getOrNull()
                        }
                    if (imageName.isNotEmpty() && storageRepository != null) {
                        val prepared = storageRepository.prepareForStart(imageName)
                        if (!prepared) {
                            val canFallbackToLocal = localStorageDir != null &&
                                localStorageDir.isDirectory &&
                                localStorageDir.canRead() &&
                                localStorageDir.canWrite()
                            if (!canFallbackToLocal) {
                                _state.value = VmState.Failed(
                                    "Failed to prepare VM storage directory for $imageName",
                                )
                                return@withLock
                            }
                            logW(
                                "Host folder sync failed for '$imageName'; " +
                                    "falling back to local per-image storage directory",
                            )
                        }
                    }
                    if (stopRequestedDuringPrepare) {
                        _state.value = VmState.Stopped(-1)
                        return@withLock
                    }
                    val resolvedStoragePath = config.storagePath?.takeIf { it.isNotBlank() }
                        ?: localStorageDir?.absolutePath
                    val effectiveConfig = if (resolvedStoragePath != null && config.storagePath.isNullOrBlank()) {
                        config.copy(storagePath = resolvedStoragePath)
                    } else {
                        config
                    }
                    setActiveImage(imageName.takeIf { it.isNotEmpty() })
                    engineStarted = true
                    engine.start(effectiveConfig)
                    val postStartState = engine.state.value
                    _state.value = postStartState
                    if (postStartState is VmState.Stopped || postStartState is VmState.Failed) {
                        val imageToSync = consumeActiveImage()
                        if (imageToSync != null) {
                            storageRepository?.syncAfterStop(imageToSync)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    val msg = "Failed to start VM: ${e.message ?: e.javaClass.simpleName}"
                    logW(msg, e)
                    _state.value = VmState.Failed(msg)
                } finally {
                    storageRepository?.setVmStarting(false)
                    startInProgress.set(false)
                    if (engineStarted) {
                        _state.value = engine.state.value
                    }
                }
            }
        }
    }

    fun isEngineInstalled(arch: VmArch): Boolean = engine.isEngineInstalled(arch)

    fun sendInput(text: String) = engine.sendInput(text)

    fun stop() {
        stopRequestedDuringPrepare = true
        engine.stop()
    }

    private fun setActiveImage(imageName: String?) {
        synchronized(activeImageLock) {
            activeImageName = imageName
        }
    }

    private fun consumeActiveImage(): String? =
        synchronized(activeImageLock) {
            val current = activeImageName
            activeImageName = null
            current
        }

    private fun logW(message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
        }
    }

    companion object {
        private const val TAG = "VmRepository"
        private const val MAX_CONSOLE_LINES = 1000
    }
}
