package ch.stix.android_cluster.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** An imported VM image living in app-private storage. */
data class VmImage(
    val file: File,
    val name: String,
    val sizeBytes: Long,
) {
    val path: String get() = file.absolutePath
}

/**
 * Manages VM images in `filesDir/vm-images`. Images picked through the system
 * file picker (SAF) are copied here once, so QEMU always boots from a stable
 * path that survives picker-permission revocation.
 */
@Singleton
class ImageRepository internal constructor(
    private val filesDir: File,
    private val contentResolver: ContentResolver?,
    private val storageRepository: StorageRepository?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        storageRepository: StorageRepository,
    ) : this(
        filesDir = context.filesDir,
        contentResolver = context.contentResolver,
        storageRepository = storageRepository,
        ioDispatcher = Dispatchers.IO,
    )

    constructor(context: Context) : this(
        filesDir = context.filesDir,
        contentResolver = context.contentResolver,
        storageRepository = null,
        ioDispatcher = Dispatchers.IO,
    )

    private val _images = MutableStateFlow<List<VmImage>>(emptyList())
    val images: StateFlow<List<VmImage>> = _images.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the images directory into [images]. */
    fun refresh() {
        _images.value = readImages()
    }

    /**
     * Copies the document behind [uri] into app-private storage and returns the
     * imported image, or null when the document cannot be opened.
     */
    suspend fun import(uri: Uri): VmImage? = withContext(ioDispatcher) {
        val resolver = contentResolver ?: return@withContext null
        val name = queryDisplayName(uri) ?: "image-${System.currentTimeMillis()}.img"
        val target = uniqueFile(name)
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
        }.getOrDefault(false)

        if (!copied) {
            target.delete()
            return@withContext null
        }
        refresh()
        VmImage(target, target.name, target.length())
    }

    /**
     * Deletes an imported image and its private per-image storage directory.
     * Returns true when both the private storage directory and the image file
     * are gone. Refuses to delete while the VM is running.
     */
    suspend fun delete(image: VmImage): Boolean = withContext(ioDispatcher) {
        if (storageRepository?.isVmRunning() == true) {
            return@withContext false
        }
        val storageDeleted = storageRepository?.deleteImageStorage(image.name) ?: true
        if (!storageDeleted) {
            return@withContext false
        }
        val deleted = !image.file.exists() || image.file.delete()
        refresh()
        deleted
    }

    /** Resolves a stored image name back to its file. */
    fun fileFor(name: String): File = File(imagesDir(), name)

    private fun readImages(): List<VmImage> =
        imagesDir().listFiles()
            ?.filter { it.isFile }
            ?.map { VmImage(it, it.name, it.length()) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    private fun imagesDir(): File = File(filesDir, IMAGES_DIR).apply { mkdirs() }

    private fun uniqueFile(name: String): File {
        var candidate = File(imagesDir(), name)
        if (!candidate.exists()) return candidate

        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var index = 1
        while (candidate.exists()) {
            candidate = File(imagesDir(), "$base-$index$suffix")
            index++
        }
        return candidate
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            contentResolver
                ?.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    internal companion object {
        const val IMAGES_DIR = "vm-images"
    }
}
