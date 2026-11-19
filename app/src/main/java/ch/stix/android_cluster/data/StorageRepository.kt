package ch.stix.android_cluster.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import ch.stix.android_cluster.engine.VmConfig
import ch.stix.android_cluster.engine.VmEngine
import ch.stix.android_cluster.engine.VmState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Snapshot of persistent storage status for a single VM image.
 */
data class VmStorageInfo(
    val imageName: String,
    val localDirectory: File,
    val customTreeUri: String? = null,
    val customFolderName: String? = null,
    val sizeBytes: Long = 0L,
    val fileCount: Int = 0,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
) {
    val isCustomFolder: Boolean get() = !customTreeUri.isNullOrBlank()
    val mountTag: String get() = VmConfig.DEFAULT_STORAGE_MOUNT_TAG
}

/**
 * Metadata for a single regular file inside a SAF DocumentTree.
 */
data class SafFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Testable boundary over Android's Storage Access Framework (`ContentResolver`
 * + `DocumentsContract`) so [StorageRepository] sync semantics can be verified
 * deterministically in JVM unit tests.
 */
interface SafTreeDataSource {
    /** Takes persistable read/write URI permission for [treeUri]. Throws [SecurityException] on failure. */
    fun takePersistablePermission(treeUri: String)

    /** Returns the user-visible display name of the folder at [treeUri], or null if unavailable. */
    fun getDisplayName(treeUri: String): String?

    /**
     * Recursively lists all regular files in the document tree at [treeUri],
     * keyed by normalized relative path (e.g. `"subdir/file.txt"`).
     *
     * MUST throw [IOException] or [SecurityException] if the root or any child
     * directory cannot be queried (never returns an empty list on query failure).
     */
    fun listFiles(treeUri: String): List<SafFileEntry>

    /** Opens an [InputStream] for reading the file at [relativePath] inside [treeUri]. */
    fun openInputStream(treeUri: String, relativePath: String): InputStream

    /**
     * Writes [sourceFile] to [relativePath] inside [treeUri], creating parent
     * directories if needed, and returns the updated [SafFileEntry].
     */
    fun writeFile(treeUri: String, relativePath: String, sourceFile: File): SafFileEntry

    /** Deletes the file at [relativePath] inside [treeUri]. Returns true if deleted or already absent. */
    fun deleteFile(treeUri: String, relativePath: String): Boolean
}

/**
 * Production [SafTreeDataSource] backed by Android's [ android.content.ContentResolver ]
 * and [DocumentsContract].
 */
@Singleton
class AndroidSafTreeDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SafTreeDataSource {

    override fun takePersistablePermission(treeUri: String) {
        val uri = Uri.parse(treeUri)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }

    override fun getDisplayName(treeUri: String): String? {
        return runCatching {
            val uri = Uri.parse(treeUri)
            val treeDocId = DocumentsContract.getTreeDocumentId(uri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
            val queried = runCatching {
                context.contentResolver.query(
                    docUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
            }.getOrNull()
            queried?.takeIf { it.isNotBlank() }
                ?: treeDocId.substringAfterLast(':').substringAfterLast('/').takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    override fun listFiles(treeUri: String): List<SafFileEntry> {
        val uri = Uri.parse(treeUri)
        val rootDocId = DocumentsContract.getTreeDocumentId(uri)
        val results = mutableListOf<SafFileEntry>()
        val visitedDirs = mutableSetOf<String>()
        listDirectoryRecursive(
            treeUri = uri,
            parentDocId = rootDocId,
            relativePrefix = "",
            depth = 0,
            visitedDirs = visitedDirs,
            out = results,
        )
        return results
    }

    private fun listDirectoryRecursive(
        treeUri: Uri,
        parentDocId: String,
        relativePrefix: String,
        depth: Int,
        visitedDirs: MutableSet<String>,
        out: MutableList<SafFileEntry>,
    ) {
        if (depth > MAX_DIRECTORY_DEPTH) {
            throw IOException("Maximum SAF directory nesting depth ($MAX_DIRECTORY_DEPTH) exceeded")
        }
        if (!visitedDirs.add(parentDocId)) {
            return
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = context.contentResolver.query(
            childrenUri,
            CHILD_PROJECTION,
            null,
            null,
            null,
        ) ?: throw IOException("Unable to query SAF directory children for $parentDocId")

        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (c.moveToNext()) {
                val docId = c.getString(idIdx) ?: continue
                val displayName = c.getString(nameIdx) ?: continue
                if (!StorageRepository.isSafePathSegment(displayName)) {
                    throw SecurityException("Unsafe SAF document name: $displayName")
                }
                val mimeType = if (c.isNull(mimeIdx)) "" else c.getString(mimeIdx).orEmpty()
                val relPath = if (relativePrefix.isEmpty()) displayName else "$relativePrefix/$displayName"
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    listDirectoryRecursive(
                        treeUri = treeUri,
                        parentDocId = docId,
                        relativePrefix = relPath,
                        depth = depth + 1,
                        visitedDirs = visitedDirs,
                        out = out,
                    )
                } else {
                    val size = if (c.isNull(sizeIdx)) 0L else c.getLong(sizeIdx).coerceAtLeast(0L)
                    val lastMod = if (c.isNull(modIdx)) 0L else c.getLong(modIdx).coerceAtLeast(0L)
                    out.add(SafFileEntry(relativePath = relPath, sizeBytes = size, lastModified = lastMod))
                }
            }
        }
    }

    override fun openInputStream(treeUri: String, relativePath: String): InputStream {
        val normalized = StorageRepository.validateRelativePath(relativePath)
        val uri = Uri.parse(treeUri)
        val docId = resolveDocumentId(uri, normalized)
            ?: throw IOException("SAF document not found: $normalized")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        return context.contentResolver.openInputStream(docUri)
            ?: throw IOException("Could not open SAF input stream for $normalized")
    }

    override fun writeFile(treeUri: String, relativePath: String, sourceFile: File): SafFileEntry {
        val normalized = StorageRepository.validateRelativePath(relativePath)
        val uri = Uri.parse(treeUri)
        val segments = normalized.split('/')
        val fileName = segments.last()
        var parentDocId = DocumentsContract.getTreeDocumentId(uri)
        for (dirSegment in segments.dropLast(1)) {
            parentDocId = findOrCreateChildDirectory(uri, parentDocId, dirSegment)
        }
        val existingDocId = findChildDocumentId(uri, parentDocId, fileName)
        val targetDocUri = if (existingDocId != null) {
            DocumentsContract.buildDocumentUriUsingTree(uri, existingDocId)
        } else {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(uri, parentDocId)
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                DEFAULT_MIME_TYPE,
                fileName,
            ) ?: throw IOException("Could not create SAF document for $normalized")
        }

        val outputStream = context.contentResolver.openOutputStream(targetDocUri, "wt")
            ?: context.contentResolver.openOutputStream(targetDocUri)
            ?: throw IOException("Could not open SAF output stream for $normalized")
        outputStream.use { out ->
            sourceFile.inputStream().buffered().use { input ->
                input.copyTo(out)
            }
        }

        val (queriedSize, queriedModified) = querySizeAndModified(targetDocUri)
        return SafFileEntry(
            relativePath = normalized,
            sizeBytes = queriedSize ?: sourceFile.length(),
            lastModified = queriedModified ?: sourceFile.lastModified(),
        )
    }

    override fun deleteFile(treeUri: String, relativePath: String): Boolean {
        val normalized = StorageRepository.validateRelativePath(relativePath)
        val uri = Uri.parse(treeUri)
        val docId = resolveDocumentId(uri, normalized) ?: return true
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
        return DocumentsContract.deleteDocument(context.contentResolver, docUri)
    }

    private fun resolveDocumentId(treeUri: Uri, normalizedRelativePath: String): String? {
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        for (segment in normalizedRelativePath.split('/')) {
            currentDocId = findChildDocumentId(treeUri, currentDocId, segment) ?: return null
        }
        return currentDocId
    }

    private fun findOrCreateChildDirectory(treeUri: Uri, parentDocId: String, dirName: String): String {
        val existing = findChildDocumentId(treeUri, parentDocId, dirName)
        if (existing != null) return existing
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val createdUri = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName,
        ) ?: throw IOException("Could not create SAF directory $dirName")
        return DocumentsContract.getDocumentId(createdUri)
    }

    private fun findChildDocumentId(treeUri: Uri, parentDocId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        ) ?: throw IOException("Unable to query SAF children for $parentDocId")
        cursor.use { c ->
            while (c.moveToNext()) {
                val displayName = c.getString(1)
                if (displayName == name) {
                    return c.getString(0)
                }
            }
        }
        return null
    }

    private fun querySizeAndModified(docUri: Uri): Pair<Long?, Long?> {
        return runCatching {
            context.contentResolver.query(
                docUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val size = if (c.isNull(0)) null else c.getLong(0)
                    val mod = if (c.isNull(1)) null else c.getLong(1)
                    size to mod
                } else {
                    null to null
                }
            } ?: (null to null)
        }.getOrDefault(null to null)
    }

    private companion object {
        const val MAX_DIRECTORY_DEPTH = 64
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

@Serializable
internal data class StorageSyncSnapshot(
    @SerialName("treeUri") val treeUri: String,
    @SerialName("files") val files: Map<String, SyncedFileEntry> = emptyMap(),
    @SerialName("hasPendingLocalChanges") val hasPendingLocalChanges: Boolean = false,
    @SerialName("lastPrepareFailed") val lastPrepareFailed: Boolean = false,
    @SerialName("baselineLocalPaths") val baselineLocalPaths: Set<String> = emptySet(),
)

@Serializable
internal data class SyncedFileEntry(
    @SerialName("sizeBytes") val sizeBytes: Long,
    @SerialName("localLastModified") val localLastModified: Long,
    @SerialName("safLastModified") val safLastModified: Long,
)

/**
 * Manages per-image persistent storage directories under `filesDir/vm-storage/<imageName>`
 * and synchronizes them with an optional user-picked SAF DocumentTree around VM
 * start and stop lifecycle transitions.
 */
@Singleton
class StorageRepository internal constructor(
    private val filesDir: File,
    private val settingsRepository: SettingsRepository,
    private val safDataSource: SafTreeDataSource,
    private val vmState: StateFlow<VmState> = MutableStateFlow(VmState.Idle),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val replaceSnapshot: (File, File) -> Boolean = { temp, target -> temp.renameTo(target) },
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        safDataSource: SafTreeDataSource,
        vmEngine: VmEngine,
    ) : this(
        filesDir = context.filesDir,
        settingsRepository = settingsRepository,
        safDataSource = safDataSource,
        vmState = vmEngine.state,
        ioDispatcher = Dispatchers.IO,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val imageMutexes = ConcurrentHashMap<String, Mutex>()
    private val syncingImages = ConcurrentHashMap.newKeySet<String>()
    private val syncErrors = ConcurrentHashMap<String, String>()
    private val isVmStarting = AtomicBoolean(false)

    private val _storageInfo = MutableStateFlow<VmStorageInfo?>(null)
    val storageInfo: StateFlow<VmStorageInfo?> = _storageInfo.asStateFlow()

    init {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                val selected = settings.selectedImage
                _storageInfo.value = if (selected != null) {
                    computeStorageInfo(selected)
                } else {
                    null
                }
            }
        }
    }

    /** True when the VM is currently running or starting. */
    fun isVmRunning(): Boolean =
        vmState.value is VmState.Running || isVmStarting.get()

    internal fun setVmStarting(starting: Boolean) {
        isVmStarting.set(starting)
    }

    /**
     * Resolves the app-private storage directory for [imageName] under
     * `filesDir/vm-storage/<sanitizedImageName>`, guaranteeing that the
     * canonical path never escapes `filesDir/vm-storage`.
     */
    fun storageDirectoryFor(imageName: String): File {
        val rootDir = storageRootDir()
        val sanitized = storageKeyFor(imageName)
        migrateLegacyStorageIfNeeded(imageName, sanitized)
        val candidate = File(rootDir, sanitized)
        val rootCanonical = rootDir.canonicalFile
        val candidateCanonical = File(rootCanonical, sanitized).canonicalFile
        if (
            !candidateCanonical.toPath().startsWith(rootCanonical.toPath()) ||
            candidateCanonical == rootCanonical
        ) {
            throw SecurityException("Storage path for '$imageName' escapes storage root")
        }
        return candidate
    }

    fun getStorageDirectory(imageName: String): File = storageDirectoryFor(imageName)

    /** Computes and returns the current [VmStorageInfo] for [imageName]. */
    fun storageInfoFor(imageName: String): VmStorageInfo {
        val info = computeStorageInfo(imageName)
        if (settingsRepository.settings.value.selectedImage == imageName) {
            _storageInfo.value = info
        }
        return info
    }

    /** Recomputes [VmStorageInfo] for [imageName] (or the currently selected image). */
    suspend fun refreshStorageInfo(
        imageName: String? = settingsRepository.settings.value.selectedImage,
    ): VmStorageInfo? = withContext(ioDispatcher) {
        if (imageName.isNullOrBlank()) {
            _storageInfo.value = null
            return@withContext null
        }
        val info = computeStorageInfo(imageName)
        if (settingsRepository.settings.value.selectedImage == imageName) {
            _storageInfo.value = info
        }
        info
    }

    fun clearSyncError(imageName: String? = settingsRepository.settings.value.selectedImage) {
        if (imageName == null) return
        syncErrors.remove(imageName)
        publishIfSelected(imageName)
    }

    /**
     * Takes persistable read/write URI permission for [treeUri] and binds it
     * as the custom host storage folder for [imageName]. Refuses to mutate
     * while the VM is running.
     */
    suspend fun setCustomStorageFolder(imageName: String, treeUri: Uri): Boolean =
        setCustomStorageFolder(imageName, treeUri.toString())

    suspend fun setCustomStorageFolder(imageName: String, treeUri: String): Boolean =
        withContext(ioDispatcher) {
            if (imageName.isBlank() || treeUri.isBlank()) return@withContext false
            if (isVmRunning()) {
                recordError(imageName, "Cannot change storage folder while the VM is running")
                return@withContext false
            }
            mutexFor(imageName).withLock {
                if (isVmRunning()) {
                    recordError(imageName, "Cannot change storage folder while the VM is running")
                    return@withContext false
                }
                val previousUri = settingsRepository.customStorageUriFor(imageName)
                if (previousUri != null && previousUri != treeUri) {
                    val prevSnapshot = try {
                        readSnapshot(imageName)?.takeIf { it.treeUri == previousUri }
                    } catch (e: Exception) {
                        val msg = "Failed to read storage metadata for $imageName: ${e.message ?: e.javaClass.simpleName}"
                        logW(msg, e)
                        recordError(imageName, msg)
                        return@withLock false
                    }
                    val localDir = storageDirectoryFor(imageName)
                    val localFiles = scanLocalDirectorySafe(localDir)
                    if (prevSnapshot == null && localFiles.isNotEmpty()) {
                        recordError(
                            imageName,
                            "Cannot switch storage folders before local data has been synced",
                        )
                        return@withLock false
                    }
                    if (
                        prevSnapshot != null &&
                        (prevSnapshot.hasPendingLocalChanges || hasUnflushedLocalDiff(localFiles, prevSnapshot))
                    ) {
                        val flushed = syncLocalToSafLocked(imageName, previousUri)
                        if (!flushed) {
                            return@withLock false
                        }
                    }
                }
                try {
                    safDataSource.takePersistablePermission(treeUri)
                } catch (e: Exception) {
                    val msg = "Failed to acquire folder permission: ${e.message ?: e.javaClass.simpleName}"
                    logW(msg, e)
                    recordError(imageName, msg)
                    return@withLock false
                }
                if (previousUri != treeUri) {
                    try {
                        deleteSnapshot(imageName)
                    } catch (e: Exception) {
                        val msg = "Failed to clear storage metadata for $imageName: ${e.message ?: e.javaClass.simpleName}"
                        logW(msg, e)
                        recordError(imageName, msg)
                        return@withLock false
                    }
                    if (previousUri != null) {
                        val localDir = storageDirectoryFor(imageName)
                        if (!deleteChildrenSafe(localDir)) {
                            val msg = "Failed to clear local storage after switching from $previousUri"
                            logW(msg)
                            recordError(imageName, msg)
                            return@withLock false
                        }
                    }
                } else {
                    try {
                        readSnapshot(imageName)
                    } catch (e: Exception) {
                        val msg = "Failed to verify storage metadata for $imageName: ${e.message ?: e.javaClass.simpleName}"
                        logW(msg, e)
                        recordError(imageName, msg)
                        return@withLock false
                    }
                }
                syncErrors.remove(imageName)
                settingsRepository.setCustomStorageUri(imageName, treeUri)
                publishIfSelected(imageName)
                true
            }
        }

    /**
     * Resets [imageName] back to its default app-private folder. If there are
     * unsynced guest changes for the previous SAF folder, flushes them first so
     * guest changes are not silently dropped. If the snapshot is corrupt, the
     * local files are retained as private data instead of attempting a flush.
     */
    suspend fun clearCustomStorageFolder(imageName: String): Boolean =
        withContext(ioDispatcher) {
            if (imageName.isBlank()) return@withContext false
            if (isVmRunning()) {
                recordError(imageName, "Cannot change storage folder while the VM is running")
                return@withContext false
            }
            mutexFor(imageName).withLock {
                if (isVmRunning()) {
                    recordError(imageName, "Cannot change storage folder while the VM is running")
                    return@withContext false
                }
                val previousUri = settingsRepository.customStorageUriFor(imageName)
                val localDir = storageDirectoryFor(imageName)
                if (!previousUri.isNullOrBlank()) {
                    val prevSnapshot = try {
                        readSnapshot(imageName)?.takeIf { it.treeUri == previousUri }
                    } catch (e: CorruptSnapshotException) {
                        try {
                            deleteSnapshot(imageName)
                        } catch (deleteError: Exception) {
                            val msg = "Failed to clear storage metadata for $imageName: ${deleteError.message ?: deleteError.javaClass.simpleName}"
                            logW(msg, deleteError)
                            recordError(imageName, msg)
                            return@withLock false
                        }
                        logW("Discarded corrupt storage metadata for $imageName; preserving local files", e)
                        settingsRepository.clearCustomStorageUri(imageName)
                        syncErrors.remove(imageName)
                        publishIfSelected(imageName)
                        return@withLock true
                    } catch (e: Exception) {
                        val msg = "Failed to read storage metadata for $imageName: ${e.message ?: e.javaClass.simpleName}"
                        logW(msg, e)
                        recordError(imageName, msg)
                        return@withLock false
                    }
                    val localFiles = scanLocalDirectorySafe(localDir)
                    if (prevSnapshot == null && localFiles.isNotEmpty()) {
                        recordError(
                            imageName,
                            "Cannot reset storage before local data has been synced",
                        )
                        return@withLock false
                    }
                    if (
                        prevSnapshot != null &&
                        (prevSnapshot.hasPendingLocalChanges || hasUnflushedLocalDiff(localFiles, prevSnapshot))
                    ) {
                        val flushed = syncLocalToSafLocked(imageName, previousUri)
                        if (!flushed) {
                            return@withLock false
                        }
                    }
                }
                try {
                    deleteSnapshot(imageName)
                } catch (e: Exception) {
                    val msg = "Failed to clear storage metadata for $imageName: ${e.message ?: e.javaClass.simpleName}"
                    logW(msg, e)
                    recordError(imageName, msg)
                    return@withLock false
                }
                if (!previousUri.isNullOrBlank() && !deleteChildrenSafe(localDir)) {
                    val msg = "Failed to clear local storage after resetting from $previousUri"
                    logW(msg)
                    recordError(imageName, msg)
                    return@withLock false
                }
                syncErrors.remove(imageName)
                settingsRepository.clearCustomStorageUri(imageName)
                publishIfSelected(imageName)
                true
            }
        }

    suspend fun resetToDefaultFolder(imageName: String): Boolean =
        clearCustomStorageFolder(imageName)

    /**
     * Clears all files inside the per-image local storage directory for [imageName]
     * without following symlinks and without deleting files in any external SAF folder.
     * Refuses to run while the VM is running.
     */
    suspend fun clearStorage(imageName: String): Boolean =
        withContext(ioDispatcher) {
            if (imageName.isBlank()) return@withContext false
            if (isVmRunning()) {
                recordError(imageName, "Cannot clear storage while the VM is running")
                return@withContext false
            }
            mutexFor(imageName).withLock {
                if (isVmRunning()) {
                    recordError(imageName, "Cannot clear storage while the VM is running")
                    return@withContext false
                }
                val dir = try {
                    ensureRealLocalDirectory(imageName)
                } catch (e: Exception) {
                    logW("Failed to clear storage for $imageName", e)
                    recordError(imageName, "Failed to clear storage for $imageName")
                    return@withLock false
                }
                try {
                    deleteSnapshot(imageName)
                } catch (e: Exception) {
                    val msg = "Failed to clear storage metadata for $imageName: ${e.message ?: e.javaClass.simpleName}"
                    logW(msg, e)
                    recordError(imageName, msg)
                    return@withLock false
                }
                val cleared = try {
                    deleteChildrenSafe(dir)
                } catch (e: Exception) {
                    logW("Failed to clear storage for $imageName", e)
                    false
                }
                if (!cleared) {
                    recordError(imageName, "Failed to clear storage for $imageName")
                    return@withLock false
                }
                syncErrors.remove(imageName)
                publishIfSelected(imageName)
                true
            }
        }

    suspend fun clearStorageData(imageName: String): Boolean = clearStorage(imageName)

    /**
     * Deletes the per-image private storage directory, sync snapshot, and custom
     * URI mapping when an imported VM image is deleted, without deleting files
     * in an external user-picked SAF folder. Refuses to run while the VM is running.
     */
    suspend fun deleteImageStorage(imageName: String): Boolean =
        withContext(ioDispatcher) {
            if (imageName.isBlank()) return@withContext false
            if (isVmRunning()) {
                recordError(imageName, "Cannot delete image storage while the VM is running")
                return@withContext false
            }
            mutexFor(imageName).withLock {
                if (isVmRunning()) {
                    recordError(imageName, "Cannot delete image storage while the VM is running")
                    return@withContext false
                }
                try {
                    deleteSnapshot(imageName)
                } catch (e: Exception) {
                    val msg = "Failed to delete storage metadata for $imageName: ${e.message ?: e.javaClass.simpleName}"
                    logW(msg, e)
                    recordError(imageName, msg)
                    return@withLock false
                }
                val deleted = try {
                    val dir = storageDirectoryFor(imageName)
                    if (dir.exists() || isSymlink(dir)) {
                        deleteRecursivelySafe(dir)
                    } else {
                        true
                    }
                } catch (e: Exception) {
                    logW("Failed to delete image storage for $imageName", e)
                    false
                }
                if (!deleted) {
                    recordError(imageName, "Failed to delete storage directory for $imageName")
                    return@withLock false
                }
                syncErrors.remove(imageName)
                settingsRepository.clearCustomStorageUri(imageName)
                publishIfSelected(imageName)
                true
            }
        }

    /**
     * Ensures the per-image local storage directory exists and, when a custom
     * SAF folder is configured for [imageName], synchronizes files from the SAF
     * DocumentTree into the local directory before QEMU starts.
     *
     * Never deletes existing local files if SAF listing or file reads fail, and
     * flushes any pending unsynced local guest changes before pulling from SAF.
     */
    suspend fun prepareForStart(imageName: String): Boolean =
        withContext(ioDispatcher) {
            if (imageName.isBlank()) return@withContext false
            mutexFor(imageName).withLock {
                val localDir = try {
                    ensureRealLocalDirectory(imageName)
                } catch (e: Exception) {
                    val msg = "Failed to prepare local storage directory: ${e.message ?: e.javaClass.simpleName}"
                    logW(msg, e)
                    recordError(imageName, msg)
                    return@withLock false
                }
                val customUri = settingsRepository.customStorageUriFor(imageName)
                if (customUri.isNullOrBlank()) {
                    syncErrors.remove(imageName)
                    publishIfSelected(imageName)
                    return@withLock true
                }
                syncSafToLocalLocked(imageName, localDir, customUri)
            }
        }

    suspend fun prepareForVmStart(imageName: String): Boolean = prepareForStart(imageName)

    suspend fun prepareStorage(imageName: String): Boolean = prepareForStart(imageName)

    /**
     * Synchronizes created, modified, and deleted files from the per-image local
     * storage directory back to the configured SAF DocumentTree after the VM stops.
     *
     * On failure, preserves local guest changes, marks pending local changes in
     * the sync snapshot so a subsequent start will not overwrite them, and surfaces
     * [VmStorageInfo.syncError].
     */
    suspend fun syncAfterStop(imageName: String): Boolean =
        withContext(ioDispatcher) {
            if (imageName.isBlank()) return@withContext false
            mutexFor(imageName).withLock {
                val customUri = settingsRepository.customStorageUriFor(imageName)
                if (customUri.isNullOrBlank()) {
                    syncErrors.remove(imageName)
                    publishIfSelected(imageName)
                    return@withLock true
                }
                syncLocalToSafLocked(imageName, customUri)
            }
        }

    suspend fun syncAfterVmStop(imageName: String): Boolean = syncAfterStop(imageName)

    suspend fun syncToHostFolder(imageName: String): Boolean = syncAfterStop(imageName)

    private fun syncSafToLocalLocked(
        imageName: String,
        localDir: File,
        treeUri: String,
    ): Boolean {
        setSyncing(imageName, true)
        try {
            ensureMetadataPathReady(imageName)
            val existingSnapshot = readSnapshot(imageName)?.takeIf { it.treeUri == treeUri }
            val preSyncLocalFiles = scanLocalDirectorySafe(localDir)

            // On a first bind (no confirmed snapshot), protect every private file:
            //  - private-only paths are seeded into SAF,
            //  - same-name paths with identical content are left alone,
            //  - same-name paths with different content keep the SAF file at the original path
            //    and the private version is preserved as a deterministic conflict copy
            //    (e.g. `shared (local).txt`) both locally and in SAF.
            // Nothing is overwritten or deleted, regardless of modification times.
            if (existingSnapshot == null && preSyncLocalFiles.isNotEmpty()) {
                val safByPath = LinkedHashMap<String, SafFileEntry>()
                for (entry in safDataSource.listFiles(treeUri)) {
                    val normalized = validateRelativePath(entry.relativePath)
                    safByPath[normalized] = entry.copy(relativePath = normalized)
                }
                val takenPaths = HashSet<String>(preSyncLocalFiles.keys).apply { addAll(safByPath.keys) }
                for ((relativePath, localStat) in preSyncLocalFiles) {
                    val safEntry = safByPath[relativePath]
                    if (safEntry == null) {
                        safDataSource.writeFile(treeUri, relativePath, localStat.file)
                        continue
                    }
                    if (hasSameContent(treeUri, relativePath, localStat, safEntry)) continue
                    preserveAsConflictCopy(treeUri, localDir, relativePath, localStat.file, takenPaths)
                }
            }

            // If a previous stop-sync failed or the local directory has unflushed changes
            // relative to the last snapshot, flush local -> SAF first so guest changes
            // are never silently overwritten or deleted.
            if (
                existingSnapshot != null &&
                (existingSnapshot.hasPendingLocalChanges || hasUnflushedLocalDiff(preSyncLocalFiles, existingSnapshot))
            ) {
                val flushed = performLocalToSafSync(
                    imageName, localDir, treeUri, existingSnapshot, preSyncLocalFiles.keys,
                )
                if (!flushed) return false
            }

            val snapshot = readSnapshot(imageName)?.takeIf { it.treeUri == treeUri }
            val currentLocalFiles = scanLocalDirectorySafe(localDir)

            val safEntryList = safDataSource.listFiles(treeUri)
            val safFiles = LinkedHashMap<String, SafFileEntry>(safEntryList.size)
            for (entry in safEntryList) {
                val normalized = validateRelativePath(entry.relativePath)
                safFiles[normalized] = entry.copy(relativePath = normalized)
            }

            // Invalidate the old baseline before changing local files. If the pull or its
            // final metadata write fails, a partial pull must not be interpreted as guest deletions.
            if (snapshot != null) deleteSnapshot(imageName)

            val updatedSnapshotEntries = LinkedHashMap<String, SyncedFileEntry>()

            // Phase 1: Copy all new or updated SAF files via atomic temporary files first.
            // If any read fails, abort before deleting any existing local files.
            for ((relPath, safEntry) in safFiles) {
                val targetFile = resolveSafeLocalFile(localDir, relPath, removeBlockingSymlinks = true)
                val localStat = currentLocalFiles[relPath]
                val snapEntry = snapshot?.files?.get(relPath)

                val isUnchanged = localStat != null &&
                    localStat.sizeBytes == safEntry.sizeBytes &&
                    (
                        (safEntry.lastModified > 0L && localStat.lastModified == safEntry.lastModified) ||
                            (
                                snapEntry != null &&
                                    safEntry.lastModified == snapEntry.safLastModified &&
                                    localStat.lastModified == snapEntry.localLastModified
                                )
                        )

                val isLocalStrictlyNewerConflict = localStat != null &&
                    safEntry.lastModified > 0L &&
                    localStat.lastModified > safEntry.lastModified &&
                    (snapEntry == null || localStat.lastModified != snapEntry.localLastModified)

                if (isUnchanged) {
                    updatedSnapshotEntries[relPath] = SyncedFileEntry(
                        sizeBytes = localStat!!.sizeBytes,
                        localLastModified = localStat.lastModified,
                        safLastModified = safEntry.lastModified,
                    )
                    continue
                }

                if (isLocalStrictlyNewerConflict) {
                    updatedSnapshotEntries[relPath] = SyncedFileEntry(
                        sizeBytes = localStat!!.sizeBytes,
                        localLastModified = localStat.lastModified,
                        safLastModified = -1L,
                    )
                    continue
                }

                val parentDir = targetFile.parentFile
                    ?: throw IOException("Missing parent directory for $relPath")
                if (!parentDir.exists() && !parentDir.mkdirs() && !parentDir.isDirectory) {
                    throw IOException("Could not create local parent directory: ${parentDir.absolutePath}")
                }
                val tempFile = File(parentDir, ".${targetFile.name}$SYNC_TMP_SUFFIX")
                try {
                    safDataSource.openInputStream(treeUri, relPath).use { input ->
                        tempFile.outputStream().buffered().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (targetFile.exists() && !targetFile.delete()) {
                        throw IOException("Could not replace local file: ${targetFile.absolutePath}")
                    }
                    if (!tempFile.renameTo(targetFile)) {
                        throw IOException("Could not move synced file into place: ${targetFile.absolutePath}")
                    }
                    if (safEntry.lastModified > 0L) {
                        targetFile.setLastModified(safEntry.lastModified)
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }

                updatedSnapshotEntries[relPath] = SyncedFileEntry(
                    sizeBytes = targetFile.length(),
                    localLastModified = targetFile.lastModified(),
                    safLastModified = safEntry.lastModified,
                )
            }

            // Phase 2: Only after all SAF reads succeeded, delete local files that are not in SAF.
            for ((relPath, localStat) in currentLocalFiles) {
                if (snapshot != null && relPath !in safFiles) {
                    if (!localStat.file.delete() && localStat.file.exists()) {
                        throw IOException("Could not delete removed file from local storage: $relPath")
                    }
                }
            }
            pruneEmptyDirectoriesSafe(localDir)

            val finalPaths = scanLocalDirectorySafe(localDir).keys
            writeSnapshot(
                imageName,
                StorageSyncSnapshot(
                    treeUri = treeUri,
                    files = updatedSnapshotEntries,
                    hasPendingLocalChanges = false,
                    lastPrepareFailed = false,
                    baselineLocalPaths = finalPaths,
                ),
            )
            syncErrors.remove(imageName)
            return true
        } catch (e: Exception) {
            val previous = runCatching { readSnapshot(imageName)?.takeIf { it.treeUri == treeUri } }.getOrNull()
            // Never fabricate a baseline for a tree that has no confirmed snapshot: a failed first
            // sync must not make private or SAF-backed paths look like synced guest state.
            if (previous != null) {
                val currentPaths = runCatching { scanLocalDirectorySafe(localDir).keys }.getOrDefault(emptySet())
                val preservedBaseline = if (previous.lastPrepareFailed || previous.hasPendingLocalChanges) {
                    previous.baselineLocalPaths + currentPaths
                } else {
                    currentPaths
                }
                runCatching {
                    writeSnapshot(
                        imageName,
                        StorageSyncSnapshot(
                            treeUri = treeUri,
                            files = previous.files,
                            hasPendingLocalChanges = previous.hasPendingLocalChanges,
                            lastPrepareFailed = true,
                            baselineLocalPaths = preservedBaseline,
                        ),
                    )
                }.onFailure { writeErr ->
                    logW("Failed to persist fallback snapshot for $imageName", writeErr)
                }
            }
            val msg = "Failed to sync from host folder: ${e.message ?: e.javaClass.simpleName}"
            logW(msg, e)
            recordError(imageName, msg)
            return false
        } finally {
            setSyncing(imageName, false)
        }
    }

    private fun syncLocalToSafLocked(
        imageName: String,
        treeUri: String,
    ): Boolean {
        setSyncing(imageName, true)
        try {
            val localDir = try {
                ensureRealLocalDirectory(imageName)
            } catch (e: Exception) {
                val msg = "Failed to access local storage directory: ${e.message ?: e.javaClass.simpleName}"
                logW(msg, e)
                recordError(imageName, msg)
                return false
            }
            val existingSnapshot = try {
                ensureMetadataPathReady(imageName)
                readSnapshot(imageName)?.takeIf { it.treeUri == treeUri }
            } catch (e: Exception) {
                val msg = "Failed to sync changes to host folder: ${e.message ?: e.javaClass.simpleName}"
                logW(msg, e)
                recordError(imageName, msg)
                return false
            }
            // Mark pending local changes before attempting SAF export so any failure
            // remembers that local guest changes still need to be flushed. Only a confirmed
            // snapshot may be marked: without one there is no baseline, and persisting an empty
            // pending snapshot would skip first-bind protection on the next start.
            if (existingSnapshot != null) {
                try {
                    writeSnapshot(imageName, existingSnapshot.copy(hasPendingLocalChanges = true))
                } catch (e: Exception) {
                    val msg = "Failed to sync changes to host folder: ${e.message ?: e.javaClass.simpleName}"
                    logW(msg, e)
                    recordError(imageName, msg)
                    return false
                }
            }

            return performLocalToSafSync(imageName, localDir, treeUri, existingSnapshot)
        } finally {
            setSyncing(imageName, false)
        }
    }

    private fun performLocalToSafSync(
        imageName: String,
        localDir: File,
        treeUri: String,
        confirmedSnapshot: StorageSyncSnapshot?,
        prepareBaseline: Set<String>? = null,
    ): Boolean {
        val snapshot = confirmedSnapshot ?: StorageSyncSnapshot(treeUri = treeUri)
        var snapshotCommitted = false
        return try {
            ensureMetadataPathReady(imageName)
            val localFiles = scanLocalDirectorySafe(localDir)
            val safEntryList = safDataSource.listFiles(treeUri)
            val safFiles = LinkedHashMap<String, SafFileEntry>(safEntryList.size)
            for (entry in safEntryList) {
                val normalized = validateRelativePath(entry.relativePath)
                safFiles[normalized] = entry.copy(relativePath = normalized)
            }

            val updatedSnapshotEntries = LinkedHashMap<String, SyncedFileEntry>()
            val takenExportPaths = HashSet<String>(localFiles.keys).apply { addAll(safFiles.keys) }
            val localRemovals = mutableListOf<LocalFileStat>()

            // Phase 1: Export only local files the guest changed since the last synced snapshot.
            // Local copies the guest did not touch never overwrite SAF, so host-side edits made while
            // the VM was running are preserved and host-side deletions are not resurrected.
            // If any upload fails, abort before deleting anything in SAF.
            for ((relPath, localStat) in localFiles) {
                val safEntry = safFiles[relPath]
                val snapEntry = snapshot.files[relPath]

                if (!localChangedSinceSnapshot(localStat, snapEntry)) {
                    if (safEntry == null) {
                        localRemovals.add(localStat)
                    } else if (snapEntry != null) {
                        // Keep the SAF version; it is pulled into local storage on the next start.
                        updatedSnapshotEntries[relPath] = snapEntry
                    }
                    continue
                }

                if (safEntry != null && snapEntry == null) {
                    // SAF-backed path without a confirmed snapshot entry: never overwrite the SAF file.
                    if (hasSameContent(treeUri, relPath, localStat, safEntry)) {
                        updatedSnapshotEntries[relPath] = SyncedFileEntry(
                            sizeBytes = localStat.sizeBytes,
                            localLastModified = localStat.lastModified,
                            safLastModified = safEntry.lastModified,
                        )
                    } else {
                        val conflictPath = preserveAsConflictCopy(
                            treeUri,
                            localDir,
                            relPath,
                            localStat.file,
                            takenExportPaths,
                            keepSource = true,
                        )
                        localRemovals.add(localStat)
                        val conflictFile = resolveSafeLocalFile(localDir, conflictPath, removeBlockingSymlinks = true)
                        val conflictSaf = safDataSource.listFiles(treeUri)
                            .firstOrNull { it.relativePath == conflictPath }
                        updatedSnapshotEntries[conflictPath] = SyncedFileEntry(
                            sizeBytes = conflictFile.length(),
                            localLastModified = conflictFile.lastModified(),
                            safLastModified = conflictSaf?.lastModified ?: -1L,
                        )
                    }
                    continue
                }

                if (safEntry != null &&
                    localStat.sizeBytes == safEntry.sizeBytes &&
                    safEntry.lastModified > 0L &&
                    localStat.lastModified == safEntry.lastModified
                ) {
                    updatedSnapshotEntries[relPath] = SyncedFileEntry(
                        sizeBytes = localStat.sizeBytes,
                        localLastModified = localStat.lastModified,
                        safLastModified = safEntry.lastModified,
                    )
                    continue
                }

                val written = safDataSource.writeFile(treeUri, relPath, localStat.file)
                updatedSnapshotEntries[relPath] = SyncedFileEntry(
                    sizeBytes = localStat.sizeBytes,
                    localLastModified = localStat.lastModified,
                    safLastModified = written.lastModified,
                )
            }

            // Phase 2: Delete SAF files that were removed locally by the guest.
            // Only files known to the synced baseline are eligible, and never when the host edited
            // the SAF file after the last sync: the host version is kept instead.
            val knownBaselinePaths = snapshot.baselineLocalPaths + snapshot.files.keys
            for ((safPath, safEntry) in safFiles) {
                if (safPath in localFiles) continue
                val snapEntry = snapshot.files[safPath]
                if (snapEntry != null && safChangedSinceSnapshot(safEntry, snapEntry)) continue
                // After a failed prepare, only files with a confirmed snapshot entry may be deleted from SAF.
                val allowDelete = if (snapshot.lastPrepareFailed) {
                    snapEntry != null
                } else {
                    safPath in knownBaselinePaths
                }
                if (allowDelete && !safDataSource.deleteFile(treeUri, safPath)) {
                    throw IOException("Could not delete removed file from SAF: $safPath")
                }
            }

            writeSnapshot(
                imageName,
                StorageSyncSnapshot(
                    treeUri = treeUri,
                    files = updatedSnapshotEntries,
                    hasPendingLocalChanges = false,
                    lastPrepareFailed = false,
                    baselineLocalPaths = scanLocalDirectorySafe(localDir).keys - localRemovals.map { it.relativePath }.toSet(),
                ),
            )
            snapshotCommitted = true
            for (localStat in localRemovals) {
                if (!localStat.file.delete() && localStat.file.exists()) {
                    throw IOException("Could not remove local file after syncing: ${localStat.relativePath}")
                }
            }
            syncErrors.remove(imageName)
            true
        } catch (e: Exception) {
            if (!snapshotCommitted && confirmedSnapshot != null) {
                runCatching {
                    val baseline = if (prepareBaseline == null) {
                        confirmedSnapshot.baselineLocalPaths
                    } else if (confirmedSnapshot.lastPrepareFailed || confirmedSnapshot.hasPendingLocalChanges) {
                        confirmedSnapshot.baselineLocalPaths + prepareBaseline
                    } else {
                        prepareBaseline
                    }
                    writeSnapshot(
                        imageName,
                        confirmedSnapshot.copy(
                            hasPendingLocalChanges = true,
                            lastPrepareFailed = confirmedSnapshot.lastPrepareFailed || prepareBaseline != null,
                            baselineLocalPaths = baseline,
                        ),
                    )
                }.onFailure { writeErr ->
                    logW("Failed to persist pending snapshot for $imageName", writeErr)
                }
            }
            val msg = "Failed to sync changes to host folder: ${e.message ?: e.javaClass.simpleName}"
            logW(msg, e)
            recordError(imageName, msg)
            false
        }
    }

    private fun hasUnflushedLocalDiff(
        localFiles: Map<String, LocalFileStat>,
        snapshot: StorageSyncSnapshot,
    ): Boolean {
        if (localFiles.keys != snapshot.files.keys) return true
        for ((path, localStat) in localFiles) {
            val snap = snapshot.files[path] ?: return true
            if (localStat.sizeBytes != snap.sizeBytes || localStat.lastModified != snap.localLastModified) {
                return true
            }
        }
        return false
    }

    private fun hasSameContent(
        treeUri: String,
        relativePath: String,
        localStat: LocalFileStat,
        safEntry: SafFileEntry,
    ): Boolean {
        if (localStat.sizeBytes != safEntry.sizeBytes) return false
        safDataSource.openInputStream(treeUri, relativePath).buffered().use { safStream ->
            localStat.file.inputStream().buffered().use { localStream ->
                val a = ByteArray(8192)
                val b = ByteArray(8192)
                while (true) {
                    val readLocal = readFully(localStream, a)
                    val readSaf = readFully(safStream, b)
                    if (readLocal != readSaf) return false
                    if (readLocal == 0) return true
                    if (!a.copyOf(readLocal).contentEquals(b.copyOf(readSaf))) return false
                }
            }
        }
    }

    private fun readFully(stream: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = stream.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Preserves [source] at a deterministic conflict name such as `shared (local).txt`
     * and uploads it to SAF so neither version is lost. Returns the conflict path.
     */
    private fun preserveAsConflictCopy(
        treeUri: String,
        localDir: File,
        relativePath: String,
        source: File,
        takenPaths: MutableSet<String>,
        keepSource: Boolean = false,
    ): String {
        val conflictPath = conflictPathFor(relativePath, takenPaths)
        val target = resolveSafeLocalFile(localDir, conflictPath, removeBlockingSymlinks = true)
        if (keepSource) {
            source.copyTo(target)
        } else if (!source.renameTo(target)) {
            throw IOException("Could not preserve conflicting local file: $relativePath")
        }
        takenPaths.add(conflictPath)
        safDataSource.writeFile(treeUri, conflictPath, target)
        return conflictPath
    }

    private fun conflictPathFor(relativePath: String, takenPaths: Set<String>): String {
        val slash = relativePath.lastIndexOf('/')
        val dir = if (slash >= 0) relativePath.substring(0, slash + 1) else ""
        val name = relativePath.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = "$dir$base (local)$ext"
        var counter = 2
        while (candidate in takenPaths) {
            candidate = "$dir$base (local $counter)$ext"
            counter++
        }
        return candidate
    }

    private fun localChangedSinceSnapshot(localStat: LocalFileStat, snapEntry: SyncedFileEntry?): Boolean =
        snapEntry == null ||
            snapEntry.safLastModified == -1L ||
            localStat.sizeBytes != snapEntry.sizeBytes ||
            localStat.lastModified != snapEntry.localLastModified

    private fun safChangedSinceSnapshot(safEntry: SafFileEntry, snapEntry: SyncedFileEntry): Boolean =
        snapEntry.safLastModified == -1L ||
            safEntry.sizeBytes != snapEntry.sizeBytes ||
            safEntry.lastModified != snapEntry.safLastModified

    private fun ensureRealLocalDirectory(imageName: String): File {
        val dir = storageDirectoryFor(imageName)
        if (isSymlink(dir)) {
            deleteSymlinkNodeOnly(dir)
        } else if (dir.exists() && !dir.isDirectory) {
            dir.delete()
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Could not create VM storage directory: ${dir.absolutePath}")
        }
        return dir
    }

    private fun computeStorageInfo(imageName: String): VmStorageInfo {
        val localDir = storageDirectoryFor(imageName)
        val customUri = settingsRepository.customStorageUriFor(imageName)
        val customName = customUri?.let { uri ->
            runCatching { safDataSource.getDisplayName(uri) }.getOrNull()
        }
        val (sizeBytes, fileCount) = computeDirectoryUsageSafe(localDir)
        return VmStorageInfo(
            imageName = imageName,
            localDirectory = localDir,
            customTreeUri = customUri,
            customFolderName = customName,
            sizeBytes = sizeBytes,
            fileCount = fileCount,
            isSyncing = syncingImages.contains(imageName),
            syncError = syncErrors[imageName],
        )
    }

    private fun setSyncing(imageName: String, syncing: Boolean) {
        if (syncing) {
            syncingImages.add(imageName)
        } else {
            syncingImages.remove(imageName)
        }
        publishIfSelected(imageName)
    }

    private fun recordError(imageName: String, message: String) {
        syncErrors[imageName] = message
        publishIfSelected(imageName)
    }

    private fun publishIfSelected(imageName: String) {
        if (settingsRepository.settings.value.selectedImage == imageName) {
            _storageInfo.value = computeStorageInfo(imageName)
        }
    }

    private fun mutexFor(imageName: String): Mutex =
        imageMutexes.getOrPut(storageKeyFor(imageName)) { Mutex() }

    private val migrationLock = Any()
    private val migrationChecked = HashSet<String>()

    /**
     * Earlier versions keyed unsafe image names by a lossy sanitized name, so several images could share one
     * folder. An unsafe name is migrated from that legacy folder only when no other known image maps to the
     * same legacy name; ambiguous folders are left untouched and never imported by any image.
     */
    private fun migrateLegacyStorageIfNeeded(imageName: String, key: String) {
        if (isSafeStorageKey(imageName)) return
        synchronized(migrationLock) {
            if (!migrationChecked.add(key)) return
            try {
                val legacyKey = legacySanitizeImageName(imageName)
                val root = File(filesDir, STORAGE_DIR)
                val legacyDir = File(root, legacyKey)
                val newDir = File(root, key)
                if (!legacyDir.isDirectory || isSymlink(legacyDir) || newDir.exists() || isSymlink(newDir)) return
                val known = LinkedHashSet<String>()
                File(filesDir, ImageRepository.IMAGES_DIR).listFiles()?.forEach { known.add(it.name) }
                val settings = settingsRepository.settings.value
                settings.selectedImage?.let { known.add(it) }
                known.addAll(settings.customStorageUris.keys)
                val ambiguous = known.any { it != imageName && legacySanitizeImageName(it) == legacyKey }
                if (ambiguous) {
                    logW("Legacy storage folder $legacyKey is shared by several images; not migrating")
                    return
                }
                // Directory first: a snapshot without its directory would look like guest deletions.
                Files.move(legacyDir.toPath(), newDir.toPath())
                val metaDir = File(filesDir, METADATA_DIR)
                val legacyMeta = File(metaDir, "$legacyKey.json")
                val newMeta = File(metaDir, "$key.json")
                if (legacyMeta.isFile && !isSymlink(legacyMeta) && !newMeta.exists()) {
                    runCatching { Files.move(legacyMeta.toPath(), newMeta.toPath()) }
                        .onFailure { logW("Could not migrate storage metadata for $imageName", it) }
                }
            } catch (e: Exception) {
                logW("Legacy storage migration failed for $imageName", e)
            }
        }
    }

    private fun storageRootDir(): File = File(filesDir, STORAGE_DIR).apply { mkdirs() }

    private fun metadataDir(): File = File(filesDir, METADATA_DIR).apply { mkdirs() }

    private fun snapshotFileFor(imageName: String): File =
        File(metadataDir(), "${storageKeyFor(imageName)}.json")

    private fun snapshotTempFileFor(imageName: String): File =
        File(metadataDir(), ".${storageKeyFor(imageName)}.json.tmp")

    private fun ensureMetadataPathReady(imageName: String) {
        val dir = metadataDir()
        if (!dir.isDirectory) {
            throw IOException("Metadata directory is not accessible: ${dir.path}")
        }
        val temp = snapshotTempFileFor(imageName)
        if (temp.exists() || isSymlink(temp)) {
            if (!temp.isFile && !isSymlink(temp)) {
                throw IOException("Temporary storage metadata is not a regular file: ${temp.path}")
            }
            Files.deleteIfExists(temp.toPath())
            logW("Discarded uncommitted storage metadata: ${temp.path}")
        }
        val file = snapshotFileFor(imageName)
        if ((file.exists() || isSymlink(file)) && !file.isFile) {
            throw IOException("Snapshot file path is not a regular file: ${file.path}")
        }
    }

    private fun readSnapshot(imageName: String): StorageSyncSnapshot? {
        ensureMetadataPathReady(imageName)
        val file = snapshotFileFor(imageName)
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            return null
        }
        if (!attributes.isRegularFile) throw IOException("Snapshot file path is not a regular file: ${file.path}")
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            InputStreamReader(file.inputStream(), decoder).use { it.readText() }
        } catch (e: CharacterCodingException) {
            throw CorruptSnapshotException("Corrupt storage metadata (invalid UTF-8): ${file.path}; use default folder to preserve local files", e)
        }
        return try {
            json.decodeFromString<StorageSyncSnapshot>(text)
        } catch (e: SerializationException) {
            throw CorruptSnapshotException("Corrupt storage metadata: ${file.path}; use default folder to preserve local files", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptSnapshotException("Corrupt storage metadata: ${file.path}; use default folder to preserve local files", e)
        }
    }

    private class CorruptSnapshotException(message: String, cause: Exception) : IOException(message, cause)

    private fun writeSnapshot(imageName: String, snapshot: StorageSyncSnapshot) {
        ensureMetadataPathReady(imageName)
        val file = snapshotFileFor(imageName)
        val temp = snapshotTempFileFor(imageName)
        if (!temp.createNewFile()) throw IOException("Could not create temporary storage metadata: ${temp.path}")
        try {
            FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Both paths are in the same private directory, so rename replaces atomically on Android.
            if (!replaceSnapshot(temp, file)) throw IOException("Could not replace storage metadata: ${file.path}")
        } finally {
            if (temp.exists() && !temp.delete()) {
                logW("Could not remove temporary storage metadata: ${temp.path}")
            }
        }
    }

    private fun deleteSnapshot(imageName: String) {
        // Never restore this snapshot after a failed cleanup: local deletion may be partial,
        // and a stale baseline would turn those missing files into SAF deletions.
        ensureMetadataPathReady(imageName)
        val file = snapshotFileFor(imageName)
        Files.deleteIfExists(file.toPath())
    }

    private fun logW(message: String, error: Throwable? = null) {
        runCatching {
            if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
        }
    }

    internal data class LocalFileStat(
        val file: File,
        val relativePath: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    companion object {
        private const val TAG = "StorageRepository"
        internal const val STORAGE_DIR = "vm-storage"
        internal const val METADATA_DIR = "vm-storage-meta"
        private const val SYNC_TMP_SUFFIX = ".sync.tmp"
        private const val MAX_WALK_DEPTH = 64

        private const val MAX_SAFE_KEY_LENGTH = 128
        private const val READABLE_PREFIX_LENGTH = 48
        private val SAFE_KEY_REGEX = Regex("[A-Za-z0-9._-]+")

        internal fun isSafeStorageKey(imageName: String): Boolean =
            imageName.length <= MAX_SAFE_KEY_LENGTH &&
                SAFE_KEY_REGEX.matches(imageName) &&
                !imageName.contains("..") &&
                !imageName.all { it == '.' }

        /**
         * Injective mapping from a raw image name to one directory/metadata/mutex key. Already-safe names
         * are used verbatim (keeping existing data); every other name becomes
         * `<readable prefix>~<SHA-256 of the raw name>`, and `~` never occurs in a safe name.
         */
        fun storageKeyFor(imageName: String): String {
            if (isSafeStorageKey(imageName)) return imageName
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(imageName.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "${legacySanitizeImageName(imageName).take(READABLE_PREFIX_LENGTH)}~$digest"
        }

        /** Lossy key used by earlier versions; kept only to locate legacy folders. */
        internal fun legacySanitizeImageName(imageName: String): String {
            val trimmed = imageName.trim()
            if (trimmed.isEmpty()) return "default"
            var sanitized = trimmed.replace(Regex("[^A-Za-z0-9._-]"), "_")
            while (sanitized.contains("..")) {
                sanitized = sanitized.replace("..", "__")
            }
            if (sanitized == "." || sanitized.all { it == '.' }) {
                sanitized = "_".repeat(sanitized.length.coerceAtLeast(1))
            }
            return sanitized.ifEmpty { "default" }
        }

        internal fun isSafePathSegment(segment: String): Boolean {
            if (segment.isEmpty() || segment == "." || segment == "..") return false
            if (segment.contains('/') || segment.contains('\\') || segment.contains('\u0000')) return false
            return true
        }

        /**
         * Validates and normalizes a relative path within a storage directory,
         * throwing [SecurityException] on any absolute or traversal path (`..`, `.`, `\`).
         */
        internal fun validateRelativePath(relativePath: String): String {
            if (relativePath.isBlank()) {
                throw SecurityException("Relative path must not be blank")
            }
            if (
                relativePath.startsWith('/') ||
                relativePath.startsWith('\\') ||
                relativePath.contains('\\') ||
                relativePath.contains('\u0000')
            ) {
                throw SecurityException("Invalid relative path: $relativePath")
            }
            val segments = relativePath.split('/')
            if (segments.isEmpty() || segments.any { !isSafePathSegment(it) }) {
                throw SecurityException("Unsafe relative path segments in: $relativePath")
            }
            return segments.joinToString("/")
        }

        internal fun isSymlink(file: File): Boolean {
            val nioCheck = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrNull()
            if (nioCheck != null) return nioCheck
            val parent = file.parentFile ?: return false
            val fileInCanonicalParent = File(parent.canonicalFile, file.name)
            return fileInCanonicalParent.canonicalFile != fileInCanonicalParent.absoluteFile
        }

        internal fun deleteSymlinkNodeOnly(file: File): Boolean =
            runCatching { Files.deleteIfExists(file.toPath()) }.getOrElse { file.delete() }

        /**
         * Resolves [relativePath] inside [localDir], verifying every intermediate
         * component against symlinks and canonical containment.
         */
        internal fun resolveSafeLocalFile(
            localDir: File,
            relativePath: String,
            removeBlockingSymlinks: Boolean,
        ): File {
            if (isSymlink(localDir)) {
                throw SecurityException("Storage root directory is a symbolic link: ${localDir.path}")
            }
            val normalized = validateRelativePath(relativePath)
            val segments = normalized.split('/')
            val rootCanonical = localDir.canonicalFile
            var current = localDir

            for ((index, segment) in segments.withIndex()) {
                current = File(current, segment)
                val isLast = index == segments.lastIndex
                if (isSymlink(current)) {
                    if (removeBlockingSymlinks) {
                        deleteSymlinkNodeOnly(current)
                    } else {
                        throw SecurityException("Symbolic link encountered in storage path: ${current.path}")
                    }
                } else if (!isLast && current.exists() && !current.isDirectory && removeBlockingSymlinks) {
                    current.delete()
                }
                val currentCanonical = current.canonicalFile
                if (
                    !currentCanonical.toPath().startsWith(rootCanonical.toPath()) ||
                    currentCanonical == rootCanonical
                ) {
                    throw SecurityException("Resolved storage file escapes root directory: $relativePath")
                }
            }
            return current
        }

        /**
         * Scans [localDir] without following symbolic links, returning all regular
         * files keyed by relative `/` path.
         */
        internal fun scanLocalDirectorySafe(localDir: File): Map<String, LocalFileStat> {
            if (!localDir.exists() || isSymlink(localDir) || !localDir.isDirectory) {
                return emptyMap()
            }
            val rootCanonical = localDir.canonicalFile
            val results = LinkedHashMap<String, LocalFileStat>()
            val visitedDirs = mutableSetOf<File>()

            fun walk(dir: File, prefix: String, depth: Int) {
                if (depth > MAX_WALK_DEPTH) return
                if (isSymlink(dir)) return
                val dirCanonical = runCatching { dir.canonicalFile }.getOrNull() ?: return
                if (!dirCanonical.toPath().startsWith(rootCanonical.toPath())) return
                if (!visitedDirs.add(dirCanonical)) return

                val children = dir.listFiles()?.sortedBy { it.name } ?: return
                for (child in children) {
                    val name = child.name
                    if (!isSafePathSegment(name)) continue
                    if (name.startsWith('.') && name.endsWith(SYNC_TMP_SUFFIX)) continue
                    if (isSymlink(child)) continue

                    val childCanonical = runCatching { child.canonicalFile }.getOrNull() ?: continue
                    if (!childCanonical.toPath().startsWith(rootCanonical.toPath())) continue

                    val relPath = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (child.isDirectory) {
                        walk(child, relPath, depth + 1)
                    } else if (child.isFile) {
                        results[relPath] = LocalFileStat(
                            file = child,
                            relativePath = relPath,
                            sizeBytes = child.length(),
                            lastModified = child.lastModified(),
                        )
                    }
                }
            }

            walk(localDir, "", 0)
            return results
        }

        internal fun computeDirectoryUsageSafe(localDir: File): Pair<Long, Int> {
            val files = scanLocalDirectorySafe(localDir)
            var totalBytes = 0L
            for (stat in files.values) {
                totalBytes += stat.sizeBytes
            }
            return totalBytes to files.size
        }

        /**
         * Deletes all children inside [dir] without following symbolic links,
         * keeping [dir] itself intact.
         */
        internal fun deleteChildrenSafe(dir: File): Boolean {
            if (!dir.exists()) return true
            if (isSymlink(dir)) return deleteSymlinkNodeOnly(dir)
            val children = dir.listFiles() ?: return false
            var allDeleted = true
            for (child in children) {
                if (!deleteRecursivelySafe(child)) {
                    allDeleted = false
                }
            }
            return allDeleted
        }

        /**
         * Deletes [target] recursively without ever following symbolic links into
         * external directories.
         */
        internal fun deleteRecursivelySafe(target: File): Boolean {
            if (isSymlink(target)) {
                return deleteSymlinkNodeOnly(target)
            }
            if (!target.exists()) return true
            if (target.isDirectory) {
                val children = target.listFiles()
                if (children != null) {
                    for (child in children) {
                        deleteRecursivelySafe(child)
                    }
                }
            }
            return target.delete() || !target.exists()
        }

        internal fun pruneEmptyDirectoriesSafe(rootDir: File) {
            if (!rootDir.exists() || isSymlink(rootDir) || !rootDir.isDirectory) return

            fun prune(dir: File, isRoot: Boolean): Boolean {
                if (isSymlink(dir) || !dir.isDirectory) return false
                val children = dir.listFiles() ?: return false
                for (child in children) {
                    if (!isSymlink(child) && child.isDirectory) {
                        prune(child, isRoot = false)
                    }
                }
                val remaining = dir.listFiles()
                if (!isRoot && remaining != null && remaining.isEmpty()) {
                    return dir.delete()
                }
                return false
            }

            prune(rootDir, isRoot = true)
        }
    }
}
