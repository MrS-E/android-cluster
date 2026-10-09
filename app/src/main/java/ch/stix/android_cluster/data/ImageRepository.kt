package ch.stix.android_cluster.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
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
class ImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
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
    suspend fun import(uri: Uri): VmImage? = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: "image-${System.currentTimeMillis()}.img"
        val target = uniqueFile(name)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
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

    /** Deletes an imported image. Returns true when the file is gone. */
    suspend fun delete(image: VmImage): Boolean = withContext(Dispatchers.IO) {
        val deleted = image.file.delete()
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

    private fun imagesDir(): File = File(context.filesDir, IMAGES_DIR).apply { mkdirs() }

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
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    private companion object {
        const val IMAGES_DIR = "vm-images"
    }
}
