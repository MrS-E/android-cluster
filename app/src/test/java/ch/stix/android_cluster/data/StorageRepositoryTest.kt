package ch.stix.android_cluster.data

import ch.stix.android_cluster.engine.VmArch
import ch.stix.android_cluster.engine.VmConfig
import ch.stix.android_cluster.engine.VmEngine
import ch.stix.android_cluster.engine.VmState
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class StorageRepositoryTest {

    private class FakeSafFile(
        var bytes: ByteArray,
        var lastModified: Long,
    )

    private class FakeSafTreeDataSource : SafTreeDataSource {
        val displayNames = mutableMapOf<String, String>()
        val trees = mutableMapOf<String, MutableMap<String, FakeSafFile>>()
        val grantedUris = mutableSetOf<String>()

        var failPermissionWith: Throwable? = null
        var failListWith: Throwable? = null
        var failReadWith: Throwable? = null
        var failWriteWith: Throwable? = null
        var failDeleteFalse: Boolean = false
        var openStreamCount: Int = 0
        var writeCallCount: Int = 0
        var onList: (() -> Unit)? = null
        var onOpen: (() -> Unit)? = null

        fun putFile(
            treeUri: String,
            relativePath: String,
            content: String,
            lastModified: Long = 1_000L,
        ) {
            val map = trees.getOrPut(treeUri) { linkedMapOf() }
            map[relativePath] = FakeSafFile(content.toByteArray(), lastModified)
        }

        fun readFileText(treeUri: String, relativePath: String): String? =
            trees[treeUri]?.get(relativePath)?.bytes?.decodeToString()

        override fun takePersistablePermission(treeUri: String) {
            failPermissionWith?.let { throw it }
            grantedUris.add(treeUri)
        }

        override fun getDisplayName(treeUri: String): String? =
            displayNames[treeUri] ?: "HostFolder"

        override fun listFiles(treeUri: String): List<SafFileEntry> {
            failListWith?.let { throw it }
            onList?.invoke()
            val map = trees[treeUri] ?: emptyMap()
            return map.entries.map { (path, file) ->
                SafFileEntry(
                    relativePath = path,
                    sizeBytes = file.bytes.size.toLong(),
                    lastModified = file.lastModified,
                )
            }
        }

        override fun openInputStream(treeUri: String, relativePath: String): InputStream {
            onOpen?.invoke()
            failReadWith?.let { throw it }
            openStreamCount++
            val file = trees[treeUri]?.get(relativePath)
                ?: throw IOException("Missing SAF file: $relativePath")
            return ByteArrayInputStream(file.bytes)
        }

        override fun writeFile(treeUri: String, relativePath: String, sourceFile: File): SafFileEntry {
            failWriteWith?.let { throw it }
            writeCallCount++
            val bytes = sourceFile.readBytes()
            val mod = sourceFile.lastModified().takeIf { it > 0L } ?: 2_000L
            val map = trees.getOrPut(treeUri) { linkedMapOf() }
            map[relativePath] = FakeSafFile(bytes, mod)
            return SafFileEntry(
                relativePath = relativePath,
                sizeBytes = bytes.size.toLong(),
                lastModified = mod,
            )
        }

        override fun deleteFile(treeUri: String, relativePath: String): Boolean {
            if (failDeleteFalse) return false
            trees[treeUri]?.remove(relativePath)
            return true
        }
    }

    private class FakeVmEngine : VmEngine {
        val mutableState = MutableStateFlow<VmState>(VmState.Idle)
        override val state: StateFlow<VmState> = mutableState
        override val serialOutput: SharedFlow<String> = MutableSharedFlow()
        var lastStartedConfig: VmConfig? = null

        override fun isEngineInstalled(arch: VmArch): Boolean = true

        override fun start(config: VmConfig) {
            lastStartedConfig = config
            mutableState.value = VmState.Running
        }

        override fun sendInput(text: String) = Unit

        override fun stop() {
            mutableState.value = VmState.Stopped(0)
        }
    }

    @Test
    fun `given distinct images when resolving storage directories then resolves isolated folders under vm-storage`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)

        val alpineDir = repo.storageDirectoryFor("alpine.img")
        val debianDir = repo.storageDirectoryFor("debian.qcow2")

        assertNotEquals(alpineDir, debianDir)
        assertEquals(File(tempDir.toFile(), "vm-storage/alpine.img"), alpineDir)
        assertEquals(File(tempDir.toFile(), "vm-storage/debian.qcow2"), debianDir)
    }

    @Test
    fun `given image name with commas spaces or traversal sequences when resolving storage directory then sanitizes inside vm-storage`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val storageRoot = File(tempDir.toFile(), "vm-storage").canonicalFile

        val commaDir = repo.storageDirectoryFor("debian,v1.qcow2")
        val traversalDir = repo.storageDirectoryFor("../../etc/passwd")
        val dotDotDir = repo.storageDirectoryFor("..")

        assertFalse(commaDir.name.contains(","))
        assertTrue(commaDir.canonicalFile.toPath().startsWith(storageRoot.toPath()))
        assertTrue(traversalDir.canonicalFile.toPath().startsWith(storageRoot.toPath()))
        assertNotEquals(storageRoot, traversalDir.canonicalFile)
        assertTrue(dotDotDir.canonicalFile.toPath().startsWith(storageRoot.toPath()))
        assertNotEquals(storageRoot, dotDotDir.canonicalFile)
    }

    @Test
    fun `given nested files in image storage when computing storage info then returns total size and file count`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)

        settings.selectImage("alpine.img")
        val dir = repo.storageDirectoryFor("alpine.img").apply { mkdirs() }
        File(dir, "root.txt").writeText("12345")
        val sub = File(dir, "nested/logs").apply { mkdirs() }
        File(sub, "app.log").writeText("1234567890")

        val info = repo.storageInfoFor("alpine.img")

        assertEquals("alpine.img", info.imageName)
        assertEquals(15L, info.sizeBytes)
        assertEquals(2, info.fileCount)
        assertFalse(info.isCustomFolder)
        assertEquals(info, repo.storageInfo.value)
    }

    @Test
    fun `given stopped VM when clearing storage for one image then removes its files while leaving other images untouched`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)

        val alpineDir = repo.storageDirectoryFor("alpine.img").apply { mkdirs() }
        val debianDir = repo.storageDirectoryFor("debian.qcow2").apply { mkdirs() }
        File(alpineDir, "a.txt").writeText("alpine-data")
        File(debianDir, "d.txt").writeText("debian-data")

        assertTrue(repo.clearStorage("alpine.img"))

        assertEquals(0, repo.storageInfoFor("alpine.img").fileCount)
        assertEquals(0L, repo.storageInfoFor("alpine.img").sizeBytes)
        assertTrue(alpineDir.isDirectory)
        assertEquals(1, repo.storageInfoFor("debian.qcow2").fileCount)
        assertEquals("debian-data", File(debianDir, "d.txt").readText())
    }

    @Test
    fun `given stopped VM when setting and resetting custom storage folder then takes permission and updates settings`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AClusterShare"
        saf.displayNames[treeUri] = "ClusterShare"
        settings.selectImage("alpine.img")

        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))
        assertTrue(treeUri in saf.grantedUris)
        assertEquals(treeUri, settings.customStorageUriFor("alpine.img"))
        assertEquals("ClusterShare", repo.storageInfo.value?.customFolderName)
        assertTrue(repo.storageInfo.value?.isCustomFolder == true)

        assertTrue(repo.clearCustomStorageFolder("alpine.img"))
        assertNull(settings.customStorageUriFor("alpine.img"))
        assertFalse(repo.storageInfo.value?.isCustomFolder == true)
    }

    @Test
    fun `given permission failure when setting custom storage folder then sets syncError and does not persist uri`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource().apply {
            failPermissionWith = SecurityException("No persistable permission grant")
        }
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADenied"

        assertFalse(repo.setCustomStorageFolder("alpine.img", treeUri))
        assertNull(settings.customStorageUriFor("alpine.img"))
        assertNotNull(repo.storageInfoFor("alpine.img").syncError)
    }

    @Test
    fun `given custom SAF folder with nested files when prepareForStart and syncAfterStop then bidirectional sync succeeds`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AVmData"

        saf.putFile(treeUri, "config.env", "PORT=8080", lastModified = 1_000L)
        saf.putFile(treeUri, "sub/remove_me.txt", "bye", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))

        // Start sync: SAF -> Local
        assertTrue(repo.prepareForStart("alpine.img"))
        val localDir = repo.storageDirectoryFor("alpine.img")
        assertEquals("PORT=8080", File(localDir, "config.env").readText())
        assertEquals("bye", File(localDir, "sub/remove_me.txt").readText())

        // Second prepareForStart with no changes skips re-reading streams
        val readsAfterFirstSync = saf.openStreamCount
        assertTrue(repo.prepareForStart("alpine.img"))
        assertEquals(readsAfterFirstSync, saf.openStreamCount)

        // Simulate guest modifications during VM execution
        File(localDir, "config.env").apply {
            writeText("PORT=9090")
            setLastModified(3_000L)
        }
        File(localDir, "sub/remove_me.txt").delete()
        File(localDir, "sub/created_by_guest.txt").apply {
            parentFile?.mkdirs()
            writeText("hello from guest")
            setLastModified(4_000L)
        }

        // Stop sync: Local -> SAF
        assertTrue(repo.syncAfterStop("alpine.img"))
        assertEquals("PORT=9090", saf.readFileText(treeUri, "config.env"))
        assertNull(saf.readFileText(treeUri, "sub/remove_me.txt"))
        assertEquals("hello from guest", saf.readFileText(treeUri, "sub/created_by_guest.txt"))
    }

    @Test
    fun `given first bind where local file has newer timestamp than SAF when prepareForStart then keeps SAF file and preserves local as conflict copy`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AConflict"

        val localDir = repo.storageDirectoryFor("alpine.img").apply { mkdirs() }
        File(localDir, "state.txt").apply {
            writeText("v2")
            setLastModified(5_000L)
        }
        saf.putFile(treeUri, "state.txt", "v1", lastModified = 2_000L)
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))

        assertTrue(repo.prepareForStart("alpine.img"))
        assertEquals("v1", File(localDir, "state.txt").readText())
        assertEquals("v2", File(localDir, "state (local).txt").readText())

        assertTrue(repo.syncAfterStop("alpine.img"))
        assertEquals("v1", saf.readFileText(treeUri, "state.txt"))
        assertEquals("v2", saf.readFileText(treeUri, "state (local).txt"))
    }

    @Test
    fun `given existing local files when SAF listFiles fails on prepareForStart then preserves local files and sets syncError`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ABroken"

        saf.putFile(treeUri, "important.txt", "keep-me", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))
        assertTrue(repo.prepareForStart("alpine.img"))

        val localDir = repo.storageDirectoryFor("alpine.img")
        assertTrue(File(localDir, "important.txt").isFile)

        // Revoke permission / simulate list failure on next prepareForStart
        saf.failListWith = SecurityException("SAF permission revoked")
        assertFalse(repo.prepareForStart("alpine.img"))

        // Existing local file must NOT be destructively deleted!
        assertEquals("keep-me", File(localDir, "important.txt").readText())
        assertNotNull(repo.storageInfoFor("alpine.img").syncError)
    }

    @Test
    fun `given host edits SAF file during run and guest does not touch it when syncAfterStop then SAF keeps host edit`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AHostEdit"

        saf.putFile(treeUri, "host.txt", "v1", lastModified = 1_000L)
        saf.putFile(treeUri, "guest.txt", "g1", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))
        assertTrue(repo.prepareForStart("alpine.img"))

        val localDir = repo.storageDirectoryFor("alpine.img")
        File(localDir, "guest.txt").apply {
            writeText("g2")
            setLastModified(3_000L)
        }
        saf.putFile(treeUri, "host.txt", "v2-host", lastModified = 9_000L)

        assertTrue(repo.syncAfterStop("alpine.img"))
        assertEquals("v2-host", saf.readFileText(treeUri, "host.txt"))
        assertEquals("g2", saf.readFileText(treeUri, "guest.txt"))

        assertTrue(repo.prepareForStart("alpine.img"))
        assertEquals("v2-host", File(localDir, "host.txt").readText())
        assertEquals("g2", File(localDir, "guest.txt").readText())
    }

    @Test
    fun `given host deletes SAF file during run and guest does not touch it when syncAfterStop then file stays deleted`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AHostDelete"

        saf.putFile(treeUri, "host.txt", "v1", lastModified = 1_000L)
        saf.putFile(treeUri, "guest.txt", "g1", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))
        assertTrue(repo.prepareForStart("alpine.img"))

        val localDir = repo.storageDirectoryFor("alpine.img")
        File(localDir, "guest.txt").apply {
            writeText("g2")
            setLastModified(3_000L)
        }
        assertTrue(saf.deleteFile(treeUri, "host.txt"))

        assertTrue(repo.syncAfterStop("alpine.img"))
        assertNull(saf.readFileText(treeUri, "host.txt"))
        assertFalse(File(localDir, "host.txt").exists())
        assertEquals("g2", saf.readFileText(treeUri, "guest.txt"))

        assertTrue(repo.prepareForStart("alpine.img"))
        assertFalse(File(localDir, "host.txt").exists())
        assertNull(saf.readFileText(treeUri, "host.txt"))
    }

    @Test
    fun `given guest deletes file and host edits it during run when syncAfterStop then SAF keeps host edit`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADeleteVsEdit"

        saf.putFile(treeUri, "shared.txt", "v1", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))
        assertTrue(repo.prepareForStart("alpine.img"))

        val localDir = repo.storageDirectoryFor("alpine.img")
        assertTrue(File(localDir, "shared.txt").delete())
        saf.putFile(treeUri, "shared.txt", "v2-host", lastModified = 9_000L)

        assertTrue(repo.syncAfterStop("alpine.img"))
        assertEquals("v2-host", saf.readFileText(treeUri, "shared.txt"))

        assertTrue(repo.prepareForStart("alpine.img"))
        assertEquals("v2-host", File(localDir, "shared.txt").readText())
    }

    @Test
    fun `given failed prepareForStart when VM stops and SAF recovers then syncAfterStop does not delete pre-existing SAF files`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ARecover"

        saf.putFile(treeUri, "host_only.txt", "do-not-delete", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))

        // Start fails due to temporary SAF error; local directory is empty
        saf.failListWith = IOException("Temporary SAF query failure")
        assertFalse(repo.prepareForStart("alpine.img"))

        // Guest writes a new file to local fallback directory while running
        val localDir = repo.storageDirectoryFor("alpine.img")
        File(localDir, "guest_new.txt").writeText("written-by-guest")

        // SAF recovers before stop
        saf.failListWith = null
        assertTrue(repo.syncAfterStop("alpine.img"))

        // Both the pre-existing SAF file and the new guest file must exist in SAF
        assertEquals("do-not-delete", saf.readFileText(treeUri, "host_only.txt"))
        assertEquals("written-by-guest", saf.readFileText(treeUri, "guest_new.txt"))

        // Subsequent prepareForStart pulls host_only.txt into localDir instead of deleting it
        assertTrue(repo.prepareForStart("alpine.img"))
        assertEquals("do-not-delete", saf.readFileText(treeUri, "host_only.txt"))
        assertEquals("do-not-delete", File(localDir, "host_only.txt").readText())
        assertEquals("written-by-guest", File(localDir, "guest_new.txt").readText())
    }

    @Test
    fun `given failed syncAfterStop when prepareForStart runs next then flushes pending guest changes instead of dropping them`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AUnsynced"

        saf.putFile(treeUri, "initial.txt", "v1", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))
        assertTrue(repo.prepareForStart("alpine.img"))

        // Guest modifies initial.txt and creates guest_unsynced.txt
        val localDir = repo.storageDirectoryFor("alpine.img")
        File(localDir, "initial.txt").apply {
            writeText("v2-from-guest")
            setLastModified(2_000L)
        }
        File(localDir, "guest_unsynced.txt").apply {
            writeText("critical-guest-work")
            setLastModified(2_500L)
        }

        // Stop sync fails
        saf.failWriteWith = IOException("Disk full on SAF write")
        assertFalse(repo.syncAfterStop("alpine.img"))
        assertNotNull(repo.storageInfoFor("alpine.img").syncError)

        // If SAF is still failing on next prepareForStart, local guest files are still preserved
        assertFalse(repo.prepareForStart("alpine.img"))
        assertEquals("v2-from-guest", File(localDir, "initial.txt").readText())
        assertEquals("critical-guest-work", File(localDir, "guest_unsynced.txt").readText())

        // Once SAF recovers, next prepareForStart flushes the pending guest changes to SAF first!
        saf.failWriteWith = null
        assertTrue(repo.prepareForStart("alpine.img"))
        assertEquals("v2-from-guest", saf.readFileText(treeUri, "initial.txt"))
        assertEquals("critical-guest-work", saf.readFileText(treeUri, "guest_unsynced.txt"))
        assertEquals("v2-from-guest", File(localDir, "initial.txt").readText())
        assertEquals("critical-guest-work", File(localDir, "guest_unsynced.txt").readText())
        assertNull(repo.storageInfoFor("alpine.img").syncError)
    }

    @Test
    fun `given SAF entry with path traversal when prepareForStart then rejects sync and does not write outside storage directory`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMalicious"

        saf.putFile(treeUri, "../escaped.txt", "pwned", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))

        assertFalse(repo.prepareForStart("alpine.img"))
        assertFalse(File(tempDir.toFile(), "vm-storage/escaped.txt").exists())
        assertNotNull(repo.storageInfoFor("alpine.img").syncError)
    }

    @Test
    fun `given symlinks to outside files and directories inside storage when computing usage syncing or clearing then never follows symlinks`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ASymlinkTest"
        assertTrue(repo.setCustomStorageFolder("alpine.img", treeUri))

        val outsideSecret = File(tempDir.toFile(), "outside_secret.txt").apply {
            writeText("top-secret-settings")
        }
        val outsideDir = File(tempDir.toFile(), "outside_dir").apply { mkdirs() }
        val outsideDirFile = File(outsideDir, "precious.txt").apply {
            writeText("do-not-delete-or-exfiltrate")
        }

        val localDir = repo.storageDirectoryFor("alpine.img").apply { mkdirs() }
        File(localDir, "real.txt").writeText("legit")
        Files.createSymbolicLink(File(localDir, "secret_link.txt").toPath(), outsideSecret.toPath())
        Files.createSymbolicLink(File(localDir, "dir_link").toPath(), outsideDir.toPath())

        // Usage calculation ignores symlinks
        val info = repo.storageInfoFor("alpine.img")
        assertEquals(1, info.fileCount)
        assertEquals(5L, info.sizeBytes)

        // syncAfterStop does not exfiltrate symlinked files/dirs to SAF
        assertTrue(repo.syncAfterStop("alpine.img"))
        assertEquals("legit", saf.readFileText(treeUri, "real.txt"))
        assertNull(saf.readFileText(treeUri, "secret_link.txt"))
        assertNull(saf.readFileText(treeUri, "dir_link/precious.txt"))

        // prepareForStart with a file colliding with a symlink replaces the link node, not the target
        saf.putFile(treeUri, "secret_link.txt", "safe-from-saf", lastModified = 5_000L)
        assertTrue(repo.prepareForStart("alpine.img"))
        assertEquals("top-secret-settings", outsideSecret.readText())
        assertEquals("safe-from-saf", File(localDir, "secret_link.txt").readText())

        // Verify dir_link still exists as a symlink and clearStorage deletes the link node, not target contents
        assertTrue(Files.isSymbolicLink(File(localDir, "dir_link").toPath()))
        assertTrue(repo.clearStorage("alpine.img"))
        assertFalse(Files.exists(File(localDir, "dir_link").toPath()))
        assertTrue(outsideDirFile.exists())
        assertEquals("do-not-delete-or-exfiltrate", outsideDirFile.readText())
    }

    @Test
    fun `given imported image with local files and custom SAF folder when image is deleted then deletes local storage and settings without deleting SAF files`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val storageRepo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val imageRepo = ImageRepository(tempDir.toFile(), null, storageRepo, dispatcher)

        val imagesDir = File(tempDir.toFile(), "vm-images").apply { mkdirs() }
        val imgFile = File(imagesDir, "alpine.img").apply { writeText("fake-image-binary") }
        imageRepo.refresh()
        val vmImage = imageRepo.images.value.single()

        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AKeepSaf"
        saf.putFile(treeUri, "persisted_in_saf.txt", "external-data", lastModified = 1_000L)
        assertTrue(storageRepo.setCustomStorageFolder("alpine.img", treeUri))
        assertTrue(storageRepo.prepareForStart("alpine.img"))

        val localDir = storageRepo.storageDirectoryFor("alpine.img")
        assertTrue(File(localDir, "persisted_in_saf.txt").exists())

        assertTrue(imageRepo.delete(vmImage))

        assertFalse(imgFile.exists())
        assertFalse(localDir.exists())
        assertNull(settings.customStorageUriFor("alpine.img"))
        assertEquals("external-data", saf.readFileText(treeUri, "persisted_in_saf.txt"))
    }

    @Test
    fun `given running VM when attempting storage or image mutations then rejects mutations`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val vmState = MutableStateFlow<VmState>(VmState.Running)
        val storageRepo = StorageRepository(tempDir.toFile(), settings, saf, vmState, dispatcher)
        val imageRepo = ImageRepository(tempDir.toFile(), null, storageRepo, dispatcher)

        val localDir = storageRepo.storageDirectoryFor("alpine.img").apply { mkdirs() }
        val activeFile = File(localDir, "active.txt").apply { writeText("in-use") }
        val imagesDir = File(tempDir.toFile(), "vm-images").apply { mkdirs() }
        val imgFile = File(imagesDir, "alpine.img").apply { writeText("image") }
        val vmImage = VmImage(imgFile, "alpine.img", imgFile.length())

        assertFalse(storageRepo.clearStorage("alpine.img"))
        assertFalse(storageRepo.setCustomStorageFolder("alpine.img", "content://tree/1"))
        assertFalse(storageRepo.clearCustomStorageFolder("alpine.img"))
        assertFalse(storageRepo.deleteImageStorage("alpine.img"))
        assertFalse(imageRepo.delete(vmImage))

        assertTrue(activeFile.exists())
        assertTrue(imgFile.exists())
        assertNotNull(storageRepo.storageInfoFor("alpine.img").syncError)
    }

    @Test
    fun `given selected image changes while VM is running when VM stops then syncs the image that was originally started`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val engine = FakeVmEngine()
        val storageRepo = StorageRepository(tempDir.toFile(), settings, saf, engine.state, dispatcher)
        val vmRepo = VmRepository(engine, storageRepo, dispatcher)

        val alpineUri = "content://com.android.externalstorage.documents/tree/primary%3AAlpine"
        val debianUri = "content://com.android.externalstorage.documents/tree/primary%3ADebian"
        saf.putFile(alpineUri, "boot.txt", "alpine-start", lastModified = 1_000L)
        saf.putFile(debianUri, "boot.txt", "debian-start", lastModified = 1_000L)

        assertTrue(storageRepo.setCustomStorageFolder("alpine.img", alpineUri))
        assertTrue(storageRepo.setCustomStorageFolder("debian.qcow2", debianUri))
        settings.selectImage("alpine.img")

        // Start alpine.img
        vmRepo.start(
            VmConfig(
                arch = VmArch.ARM,
                imagePath = "/data/user/0/ch.stix.android_cluster/files/vm-images/alpine.img",
            ),
        )

        val alpineLocalDir = storageRepo.storageDirectoryFor("alpine.img")
        assertEquals(alpineLocalDir.absolutePath, engine.lastStartedConfig?.storagePath)
        assertEquals("alpine-start", File(alpineLocalDir, "boot.txt").readText())

        // Guest writes output to alpine.img storage while running
        File(alpineLocalDir, "guest_result.txt").writeText("alpine-finished")

        // User switches selected image in settings while alpine.img is still running
        settings.selectImage("debian.qcow2")

        // Stop VM -> must sync alpine.img (the running image), NOT debian.qcow2!
        vmRepo.stop()

        assertEquals("alpine-finished", saf.readFileText(alpineUri, "guest_result.txt"))
        assertNull(saf.readFileText(debianUri, "guest_result.txt"))
    }

    @Test
    fun `given SAF sync failure on start when VmRepository starts VM then falls back to local storage directory and surfaces syncError`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val engine = FakeVmEngine()
        val storageRepo = StorageRepository(tempDir.toFile(), settings, saf, engine.state, dispatcher)
        val vmRepo = VmRepository(engine, storageRepo, dispatcher)

        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ARevoked"
        saf.putFile(treeUri, "cached.txt", "cached-before-revoke", lastModified = 1_000L)
        assertTrue(storageRepo.setCustomStorageFolder("alpine.img", treeUri))
        assertTrue(storageRepo.prepareForStart("alpine.img"))

        // Revoke SAF permission before starting VM
        saf.failListWith = SecurityException("SAF permission revoked")
        vmRepo.start(
            VmConfig(
                arch = VmArch.ARM,
                imagePath = "/data/user/0/ch.stix.android_cluster/files/vm-images/alpine.img",
            ),
        )

        val localDir = storageRepo.storageDirectoryFor("alpine.img")
        assertTrue(vmRepo.state.value is VmState.Running)
        assertEquals(localDir.absolutePath, engine.lastStartedConfig?.storagePath)
        assertEquals("cached-before-revoke", File(localDir, "cached.txt").readText())
        assertNotNull(storageRepo.storageInfoFor("alpine.img").syncError)
    }

    @Test
    fun `given uncreatable local storage directory when VmRepository starts VM then blocks engine start and transitions to Failed`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val engine = FakeVmEngine()
        val storageRepo = StorageRepository(tempDir.toFile(), settings, saf, engine.state, dispatcher)
        val vmRepo = VmRepository(engine, storageRepo, dispatcher)

        // Block creation of vm-storage/alpine.img by making vm-storage a regular file inside a read-only parent
        val vmStorageRoot = File(tempDir.toFile(), "vm-storage").apply {
            deleteRecursively()
            writeText("not-a-directory")
        }
        try {
            vmRepo.start(
                VmConfig(
                    arch = VmArch.ARM,
                    imagePath = "/data/user/0/ch.stix.android_cluster/files/vm-images/alpine.img",
                ),
            )

            assertNull(engine.lastStartedConfig)
            assertTrue(vmRepo.state.value is VmState.Failed)
            assertNotNull(storageRepo.storageInfoFor("alpine.img").syncError)
        } finally {
            vmStorageRoot.delete()
        }
    }

    @Test
    fun `given private and host-newer SAF same-name file when first sync runs then both versions are preserved`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://tree/customA"

        val storageDir = repo.storageDirectoryFor("test-img").apply { mkdirs() }
        val privateFile = File(storageDir, "private.txt").apply { writeText("private-content") }
        File(storageDir, "shared.txt").apply {
            writeText("private-version")
            setLastModified(1_000L)
        }
        saf.putFile(treeUri, "shared.txt", "host-version", lastModified = 2_000L)

        assertTrue(repo.setCustomStorageFolder("test-img", treeUri))
        assertTrue(repo.prepareForStart("test-img"))

        assertEquals("private-content", privateFile.readText())
        assertEquals("private-content", saf.readFileText(treeUri, "private.txt"))
        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))
        assertEquals("host-version", File(storageDir, "shared.txt").readText())
        assertEquals("private-version", File(storageDir, "shared (local).txt").readText())
        assertEquals("private-version", saf.readFileText(treeUri, "shared (local).txt"))
        assertTrue(repo.syncAfterStop("test-img"))
        assertEquals("private-content", saf.readFileText(treeUri, "private.txt"))
        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))
        assertEquals("private-version", saf.readFileText(treeUri, "shared (local).txt"))
    }

    @Test
    fun `given private-newer and SAF same-name file when first sync runs then both versions are preserved`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://tree/customA"

        val storageDir = repo.storageDirectoryFor("test-img").apply { mkdirs() }
        File(storageDir, "shared.txt").apply {
            writeText("private-version-newer")
            setLastModified(5_000L)
        }
        saf.putFile(treeUri, "shared.txt", "host-version", lastModified = 2_000L)

        assertTrue(repo.setCustomStorageFolder("test-img", treeUri))
        assertTrue(repo.prepareForStart("test-img"))
        assertTrue(repo.syncAfterStop("test-img"))

        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))
        assertEquals("host-version", File(storageDir, "shared.txt").readText())
        assertEquals("private-version-newer", saf.readFileText(treeUri, "shared (local).txt"))
        assertEquals("private-version-newer", File(storageDir, "shared (local).txt").readText())
    }

    @Test
    fun `given identical private and SAF same-name file when first sync runs then no conflict copy is created`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://tree/customA"

        val storageDir = repo.storageDirectoryFor("test-img").apply { mkdirs() }
        File(storageDir, "same.txt").apply {
            writeText("identical")
            setLastModified(9_000L)
        }
        saf.putFile(treeUri, "same.txt", "identical", lastModified = 1_000L)

        assertTrue(repo.setCustomStorageFolder("test-img", treeUri))
        assertTrue(repo.prepareForStart("test-img"))
        assertTrue(repo.syncAfterStop("test-img"))

        assertEquals(setOf("same.txt"), saf.trees.getValue(treeUri).keys)
        assertFalse(File(storageDir, "same (local).txt").exists())
    }

    @Test
    fun `given failed first prepare read error when VM stops and recovers then SAF host version is never overwritten`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://tree/customA"

        val storageDir = repo.storageDirectoryFor("test-img").apply { mkdirs() }
        File(storageDir, "shared.txt").apply {
            writeText("private-version")
            setLastModified(1_000L)
        }
        saf.putFile(treeUri, "shared.txt", "host-version", lastModified = 9_000L)
        assertTrue(repo.setCustomStorageFolder("test-img", treeUri))

        saf.failReadWith = IOException("read failed")
        assertFalse(repo.prepareForStart("test-img"))
        assertNotNull(repo.storageInfoFor("test-img").syncError)

        // VM runs on the local fallback directory and stops (transient failure is over).
        saf.failReadWith = null
        assertTrue(repo.syncAfterStop("test-img"))
        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))
        assertEquals("private-version", saf.readFileText(treeUri, "shared (local).txt"))

        assertTrue(repo.prepareForStart("test-img"))
        assertEquals("host-version", File(storageDir, "shared.txt").readText())
        assertEquals("private-version", File(storageDir, "shared (local).txt").readText())
        assertTrue(repo.syncAfterStop("test-img"))
        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))
    }

    @Test
    fun `given failed first prepare list error when guest deletes private file and VM stops then SAF files are not deleted`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://tree/customA"

        val storageDir = repo.storageDirectoryFor("test-img").apply { mkdirs() }
        val shared = File(storageDir, "shared.txt").apply {
            writeText("private-version")
            setLastModified(1_000L)
        }
        saf.putFile(treeUri, "shared.txt", "host-version", lastModified = 9_000L)
        saf.putFile(treeUri, "host-only.txt", "host-only", lastModified = 9_000L)
        assertTrue(repo.setCustomStorageFolder("test-img", treeUri))

        saf.failListWith = IOException("list failed")
        assertFalse(repo.prepareForStart("test-img"))
        saf.failListWith = null

        // Guest deletes the private file during the fallback run.
        assertTrue(shared.delete())
        assertTrue(repo.syncAfterStop("test-img"))

        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))
        assertEquals("host-only", saf.readFileText(treeUri, "host-only.txt"))

        assertTrue(repo.prepareForStart("test-img"))
        assertEquals("host-version", File(storageDir, "shared.txt").readText())
        assertEquals("host-only", File(storageDir, "host-only.txt").readText())
    }

    @Test
    fun `given failed first prepare when switching folder then switch is refused and surfaces syncError`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeA = "content://tree/customA"
        val treeB = "content://tree/customB"

        val storageDir = repo.storageDirectoryFor("test-img").apply { mkdirs() }
        File(storageDir, "shared.txt").writeText("private-version")
        saf.putFile(treeA, "shared.txt", "host-version", lastModified = 9_000L)
        assertTrue(repo.setCustomStorageFolder("test-img", treeA))

        saf.failReadWith = IOException("read failed")
        assertFalse(repo.prepareForStart("test-img"))
        saf.failReadWith = null

        assertFalse(repo.setCustomStorageFolder("test-img", treeB))
        assertEquals(treeA, settings.customStorageUriFor("test-img"))
        assertEquals("private-version", File(storageDir, "shared (local).txt").readText())
        assertNotNull(repo.storageInfoFor("test-img").syncError)
        assertNull(saf.trees[treeB]?.get("shared.txt"))
    }

    @Test
    fun `given consecutive first-bind failures with same-name private file when guest deletes it then SAF host version is never deleted`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://tree/customA"

        val storageDir = repo.storageDirectoryFor("test-img").apply { mkdirs() }
        val shared = File(storageDir, "shared.txt").apply {
            writeText("private-version")
            setLastModified(1_000L)
        }
        saf.putFile(treeUri, "shared.txt", "host-version", lastModified = 9_000L)
        assertTrue(repo.setCustomStorageFolder("test-img", treeUri))

        // First prepare fails, then the stop-sync fails too: no confirmed snapshot may be fabricated.
        saf.failListWith = IOException("list failed")
        assertFalse(repo.prepareForStart("test-img"))
        assertFalse(repo.syncAfterStop("test-img"))
        assertFalse(repo.prepareForStart("test-img"))

        // Guest deletes the private file while the host folder is still unreachable.
        assertTrue(shared.delete())

        // SAF recovers: the stop must not delete the host's shared.txt.
        saf.failListWith = null
        assertTrue(repo.syncAfterStop("test-img"))
        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))

        assertTrue(repo.prepareForStart("test-img"))
        assertEquals("host-version", File(storageDir, "shared.txt").readText())
        assertTrue(repo.syncAfterStop("test-img"))
        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))
    }

    @Test
    fun `given first prepare seed write failure when VM stops and recovers then private file reaches SAF and no SAF file is deleted`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://tree/customA"

        val storageDir = repo.storageDirectoryFor("test-img").apply { mkdirs() }
        File(storageDir, "private-only.txt").apply {
            writeText("guest-data")
            setLastModified(1_000L)
        }
        saf.putFile(treeUri, "host.txt", "host-data", lastModified = 9_000L)
        assertTrue(repo.setCustomStorageFolder("test-img", treeUri))

        saf.failWriteWith = IOException("write failed")
        assertFalse(repo.prepareForStart("test-img"))
        assertEquals("host-data", saf.readFileText(treeUri, "host.txt"))
        assertEquals("guest-data", File(storageDir, "private-only.txt").readText())

        saf.failWriteWith = null
        assertTrue(repo.syncAfterStop("test-img"))
        assertEquals("host-data", saf.readFileText(treeUri, "host.txt"))
        assertEquals("guest-data", saf.readFileText(treeUri, "private-only.txt"))

        assertTrue(repo.prepareForStart("test-img"))
        assertEquals("host-data", File(storageDir, "host.txt").readText())
        assertEquals("guest-data", File(storageDir, "private-only.txt").readText())
    }

    @Test
    fun `given first prepare conflict copy upload failure when VM stops and recovers then both versions survive`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val treeUri = "content://tree/customA"

        val storageDir = repo.storageDirectoryFor("test-img").apply { mkdirs() }
        File(storageDir, "shared.txt").apply {
            writeText("private-version")
            setLastModified(1_000L)
        }
        saf.putFile(treeUri, "shared.txt", "host-version", lastModified = 9_000L)
        assertTrue(repo.setCustomStorageFolder("test-img", treeUri))

        // The private copy is renamed to "shared (local).txt" before the upload fails.
        saf.failWriteWith = IOException("write failed")
        assertFalse(repo.prepareForStart("test-img"))
        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))

        saf.failWriteWith = null
        assertTrue(repo.syncAfterStop("test-img"))
        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))
        assertEquals("private-version", saf.readFileText(treeUri, "shared (local).txt"))

        assertTrue(repo.prepareForStart("test-img"))
        assertEquals("host-version", File(storageDir, "shared.txt").readText())
        assertEquals("private-version", File(storageDir, "shared (local).txt").readText())
        assertTrue(repo.syncAfterStop("test-img"))
        assertEquals("host-version", saf.readFileText(treeUri, "shared.txt"))
        assertEquals("private-version", saf.readFileText(treeUri, "shared (local).txt"))
    }

    @Test
    fun `given private data and newly bound SAF folder when reset before first sync then rejects reset without losing data`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "test-img"
        val treeUri = "content://tree/customA"
        val localDir = repo.storageDirectoryFor(image).apply { mkdirs() }
        val privateFile = File(localDir, "private.txt").apply { writeText("important") }

        assertTrue(repo.setCustomStorageFolder(image, treeUri))
        assertFalse(repo.clearCustomStorageFolder(image))
        assertEquals(treeUri, settings.customStorageUriFor(image))
        assertEquals("important", privateFile.readText())

        assertTrue(repo.prepareForStart(image))
        assertTrue(repo.clearCustomStorageFolder(image))
        assertNull(settings.customStorageUriFor(image))
        assertFalse(privateFile.exists())
        assertEquals("important", saf.readFileText(treeUri, "private.txt"))
    }

    @Test
    fun `given private data and newly bound SAF folder when switching before first sync then rejects switch and later isolates the new tree`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "test-img"
        val treeA = "content://tree/customA"
        val treeB = "content://tree/customB"
        val localDir = repo.storageDirectoryFor(image).apply { mkdirs() }
        val privateFile = File(localDir, "private.txt").apply { writeText("from-private") }

        assertTrue(repo.setCustomStorageFolder(image, treeA))
        assertFalse(repo.setCustomStorageFolder(image, treeB))
        assertEquals(treeA, settings.customStorageUriFor(image))
        assertEquals("from-private", privateFile.readText())

        assertTrue(repo.prepareForStart(image))
        assertTrue(repo.setCustomStorageFolder(image, treeB))
        assertTrue(repo.prepareForStart(image))

        assertEquals("from-private", saf.readFileText(treeA, "private.txt"))
        assertNull(saf.readFileText(treeB, "private.txt"))
        assertFalse(File(localDir, "private.txt").exists())
    }

    @Test
    fun `given custom storage A with synced files when switch to B then local storage is cleared`(@TempDir tempDir: Path) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)

        val image = "test-img"
        val storageDir = repo.storageDirectoryFor(image).apply { mkdirs() }
        val localFile = File(storageDir, "file.txt").apply { writeText("fromA") }

        // Setup A and sync
        saf.putFile("content://tree/A", "file.txt", "fromA")
        assertTrue(repo.setCustomStorageFolder(image, "content://tree/A"))
        assertTrue(repo.prepareForStart(image))

        // Switch to B
        assertTrue(repo.setCustomStorageFolder(image, "content://tree/B"))

        assertFalse(localFile.exists(), "Local files from A should be cleared after switch to B")
        assertEquals(0, repo.storageInfoFor(image).fileCount)
    }

    @Test
    fun `given partial local cleanup failure when clearing storage then stale snapshot is not restored and host files survive`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "removable.txt", "keep-host")
        saf.putFile(tree, "locked/blocked.txt", "locked-host")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))

        val localDir = repo.storageDirectoryFor(image)
        val lockedDir = File(localDir, "locked")
        assertTrue(lockedDir.setWritable(false, false))
        try {
            assertFalse(repo.clearStorage(image))
            assertFalse(File(localDir, "removable.txt").exists())
            assertFalse(File(File(tempDir.toFile(), StorageRepository.METADATA_DIR), "$image.json").exists())
        } finally {
            assertTrue(lockedDir.setWritable(true, false))
        }

        assertTrue(repo.prepareForStart(image))
        assertEquals("keep-host", saf.readFileText(tree, "removable.txt"))
        assertEquals("keep-host", File(localDir, "removable.txt").readText())
        assertEquals("locked-host", saf.readFileText(tree, "locked/blocked.txt"))
    }

    @Test
    fun `given partial local cleanup failure when resetting switching or deleting image then old host files survive recovery`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        for (operation in listOf("reset", "switch", "delete")) {
            val image = "$operation.img"
            val tree = "content://tree/$operation"
            saf.putFile(tree, "removable.txt", "keep-host")
            saf.putFile(tree, "locked/blocked.txt", "locked-host")
            assertTrue(repo.setCustomStorageFolder(image, tree))
            assertTrue(repo.prepareForStart(image))
            val localDir = repo.storageDirectoryFor(image)
            val lockedDir = File(localDir, "locked")
            assertTrue(lockedDir.setWritable(false, false))
            try {
                val result = when (operation) {
                    "reset" -> repo.clearCustomStorageFolder(image)
                    "switch" -> repo.setCustomStorageFolder(image, "content://tree/new")
                    else -> repo.deleteImageStorage(image)
                }
                assertFalse(result, operation)
                assertEquals(tree, settings.customStorageUriFor(image))
                assertFalse(File(localDir, "removable.txt").exists())
                assertFalse(File(File(tempDir.toFile(), StorageRepository.METADATA_DIR), "$image.json").exists())
            } finally {
                assertTrue(lockedDir.setWritable(true, false))
            }
            assertTrue(repo.prepareForStart(image))
            assertEquals("keep-host", saf.readFileText(tree, "removable.txt"))
            assertEquals("keep-host", File(localDir, "removable.txt").readText())
            assertEquals("locked-host", saf.readFileText(tree, "locked/blocked.txt"))
        }
    }

    @Test
    fun `given metadata directory cannot be created when clearing storage then preserves local data and reports failure`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val repo = StorageRepository(tempDir.toFile(), settings, FakeSafTreeDataSource(), ioDispatcher = dispatcher)
        val image = "alpine.img"
        val file = File(repo.storageDirectoryFor(image).apply { mkdirs() }, "keep.txt").apply { writeText("keep") }
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        assertFalse(metadataDir.exists())
        assertTrue(tempDir.toFile().setWritable(false, false))
        try {
            assertFalse(repo.clearStorage(image))
            assertEquals("keep", file.readText())
            assertNotNull(repo.storageInfoFor(image).syncError)
        } finally {
            assertTrue(tempDir.toFile().setWritable(true, false))
        }
    }

    @Test
    fun `given corrupt snapshot when resetting to default and rebinding then preserves both sides`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        for ((index, corrupt) in listOf("", "{\"treeUri\":", "{\"treeUri\":42}").withIndex()) {
            val image = "alpine-$index.img"
            val tree = "content://tree/custom$index"
            saf.putFile(tree, "host.txt", "host-data")
            saf.putFile(tree, "removed-locally.txt", "host-only")
            assertTrue(repo.setCustomStorageFolder(image, tree))
            assertTrue(repo.prepareForStart(image))
            val localDir = repo.storageDirectoryFor(image)
            val localFile = File(localDir, "host.txt").apply { writeText("guest-data") }
            val localOnly = File(localDir, "local-only.txt").apply { writeText("local-only") }
            assertTrue(File(localDir, "removed-locally.txt").delete())
            val snapshot = File(File(tempDir.toFile(), StorageRepository.METADATA_DIR), "$image.json")
            snapshot.writeText(corrupt)
            assertFalse(repo.prepareForStart(image))
            assertFalse(repo.syncAfterStop(image))
            assertFalse(repo.setCustomStorageFolder(image, "content://tree/customB"))
            assertFalse(repo.setCustomStorageFolder(image, tree))
            assertTrue(repo.storageInfoFor(image).syncError?.contains("Corrupt storage metadata") == true)
            assertTrue(repo.storageInfoFor(image).syncError?.contains("default folder") == true)
            assertEquals(corrupt, snapshot.readText())
            assertEquals(tree, settings.customStorageUriFor(image))
            assertEquals("guest-data", localFile.readText())
            assertEquals("local-only", localOnly.readText())
            assertEquals("host-data", saf.readFileText(tree, "host.txt"))
            assertEquals("host-only", saf.readFileText(tree, "removed-locally.txt"))

            assertTrue(repo.clearCustomStorageFolder(image))
            assertNull(settings.customStorageUriFor(image))
            assertFalse(snapshot.exists())
            assertEquals("guest-data", localFile.readText())
            assertEquals("local-only", localOnly.readText())
            assertEquals("host-data", saf.readFileText(tree, "host.txt"))

            assertTrue(repo.setCustomStorageFolder(image, tree))
            if (index == 1) assertTrue(repo.syncAfterStop(image))
            assertTrue(repo.prepareForStart(image))
            assertTrue(repo.syncAfterStop(image))
            assertEquals("host-data", saf.readFileText(tree, "host.txt"))
            assertEquals("guest-data", saf.readFileText(tree, "host (local).txt"))
            assertEquals("local-only", saf.readFileText(tree, "local-only.txt"))
            assertEquals("host-only", saf.readFileText(tree, "removed-locally.txt"))
            assertEquals("host-only", File(localDir, "removed-locally.txt").readText())
        }
    }

    @Test
    fun `given malformed utf8 in snapshot when syncing then reports error and leaves data unchanged`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))
        val localFile = File(repo.storageDirectoryFor(image), "host.txt")
        localFile.writeText("guest-data")
        val snapshot = File(File(tempDir.toFile(), StorageRepository.METADATA_DIR), "$image.json")
        val invalid = "{\"treeUri\":\"".toByteArray() + byteArrayOf(0xc3.toByte()) + "\"}".toByteArray()
        snapshot.writeBytes(invalid)
        assertFalse(repo.prepareForStart(image))
        assertFalse(repo.syncAfterStop(image))
        assertTrue(repo.storageInfoFor(image).syncError?.contains("invalid UTF-8") == true)
        assertTrue(invalid.contentEquals(snapshot.readBytes()))
        assertEquals("guest-data", localFile.readText())
        assertEquals("host-data", saf.readFileText(tree, "host.txt"))
        assertTrue(repo.clearCustomStorageFolder(image))
        assertEquals("guest-data", localFile.readText())
        assertEquals("host-data", saf.readFileText(tree, "host.txt"))
        assertNull(settings.customStorageUriFor(image))
        assertFalse(snapshot.exists())
    }

    @Test
    fun `given corrupt snapshot when metadata cannot be deleted then reset fails without changing data`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))
        val localFile = File(repo.storageDirectoryFor(image), "host.txt").apply { writeText("guest-data") }
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        val snapshot = File(metadataDir, "$image.json").apply { writeText("{") }
        assertTrue(metadataDir.setWritable(false, false))
        try {
            assertFalse(repo.clearCustomStorageFolder(image))
            assertNotNull(repo.storageInfoFor(image).syncError)
            assertEquals(tree, settings.customStorageUriFor(image))
            assertEquals("{", snapshot.readText())
            assertEquals("guest-data", localFile.readText())
            assertEquals("host-data", saf.readFileText(tree, "host.txt"))
        } finally {
            assertTrue(metadataDir.setWritable(true, false))
        }
        assertTrue(repo.clearCustomStorageFolder(image))
        assertEquals("guest-data", localFile.readText())
        assertEquals("host-data", saf.readFileText(tree, "host.txt"))
        assertNull(settings.customStorageUriFor(image))
    }

    @Test
    fun `given corrupt snapshot when resetting while running then refuses until VM is stopped`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val state = MutableStateFlow<VmState>(VmState.Idle)
        val repo = StorageRepository(tempDir.toFile(), settings, saf, state, dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))
        val localFile = File(repo.storageDirectoryFor(image), "host.txt").apply { writeText("guest-data") }
        val snapshot = File(File(tempDir.toFile(), StorageRepository.METADATA_DIR), "$image.json")
        snapshot.writeText("{")
        state.value = VmState.Running
        assertFalse(repo.clearCustomStorageFolder(image))
        assertEquals("{", snapshot.readText())
        assertEquals(tree, settings.customStorageUriFor(image))
        assertEquals("guest-data", localFile.readText())
        state.value = VmState.Stopped(0)
        assertTrue(repo.clearCustomStorageFolder(image))
        assertEquals("guest-data", localFile.readText())
        assertEquals("host-data", saf.readFileText(tree, "host.txt"))
    }

    @Test
    fun `given interrupted snapshot write with committed baseline when syncing then discards temp and uses baseline`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))
        val localFile = File(repo.storageDirectoryFor(image), "host.txt")
        localFile.writeText("guest-data")
        saf.putFile(tree, "host-only.txt", "host-only")
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        val snapshot = File(metadataDir, "$image.json")
        val original = snapshot.readText()
        val temp = File(metadataDir, ".$image.json.tmp")
        temp.writeText("{\"treeUri\":")
        assertTrue(repo.syncAfterStop(image))
        assertFalse(temp.exists())
        assertEquals("guest-data", saf.readFileText(tree, "host.txt"))
        assertEquals("guest-data", localFile.readText())
        assertEquals("host-only", saf.readFileText(tree, "host-only.txt"))
        assertNotEquals(original, snapshot.readText())
    }

    @Test
    fun `given interrupted first snapshot write when syncing then ignores temp and preserves both versions`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "shared.txt", "host-data")
        saf.putFile(tree, "host-only.txt", "host-only")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        val localFile = File(repo.storageDirectoryFor(image).apply { mkdirs() }, "shared.txt")
        localFile.writeText("guest-data")
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        val temp = File(metadataDir, ".$image.json.tmp").apply { writeText("{\"treeUri\":") }

        assertTrue(repo.prepareForStart(image))
        assertFalse(temp.exists())
        assertTrue(repo.syncAfterStop(image))
        assertEquals("host-data", saf.readFileText(tree, "shared.txt"))
        assertEquals("guest-data", saf.readFileText(tree, "shared (local).txt"))
        assertEquals("host-only", saf.readFileText(tree, "host-only.txt"))
    }

    @Test
    fun `given valid stale temp and newer committed snapshot when syncing then applies committed deletion baseline`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))
        val localFile = File(repo.storageDirectoryFor(image), "host.txt")
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        val snapshot = File(metadataDir, "$image.json")
        val oldSnapshot = snapshot.readText()
        localFile.writeText("guest-data-changed")
        assertTrue(localFile.setLastModified(5_000L))
        assertTrue(repo.syncAfterStop(image))
        assertNotEquals(oldSnapshot, snapshot.readText())
        val temp = File(metadataDir, ".$image.json.tmp").apply { writeText(oldSnapshot) }
        assertTrue(localFile.delete())

        assertTrue(repo.syncAfterStop(image))
        assertFalse(temp.exists())
        assertNull(saf.readFileText(tree, "host.txt"))
    }

    @Test
    fun `given stale temp cannot be removed when syncing then fails safely and retries after access restored`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))
        val localFile = File(repo.storageDirectoryFor(image), "host.txt").apply { writeText("guest-data") }
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        val snapshot = File(metadataDir, "$image.json")
        val original = snapshot.readText()
        val temp = File(metadataDir, ".$image.json.tmp").apply { writeText("partial") }
        assertTrue(metadataDir.setWritable(false, false))
        try {
            assertFalse(repo.syncAfterStop(image))
            assertNotNull(repo.storageInfoFor(image).syncError)
            assertEquals(original, snapshot.readText())
            assertEquals("partial", temp.readText())
            assertEquals("guest-data", localFile.readText())
            assertEquals("host-data", saf.readFileText(tree, "host.txt"))
        } finally {
            assertTrue(metadataDir.setWritable(true, false))
        }
        assertTrue(repo.syncAfterStop(image))
        assertFalse(temp.exists())
        assertEquals("guest-data", saf.readFileText(tree, "host.txt"))
    }

    @Test
    fun `given failed atomic replacement when stopping then old pending snapshot and local data remain intact`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        var failReplacement = false
        var preservedSnapshot: String? = null
        var failedReplacements = 0
        val repo = StorageRepository(
            filesDir = tempDir.toFile(),
            settingsRepository = settings,
            safDataSource = saf,
            ioDispatcher = dispatcher,
            replaceSnapshot = { temp, target ->
                if (failReplacement) {
                    assertTrue(temp.readText().contains("treeUri"))
                    val old = target.readText()
                    if (preservedSnapshot == null) preservedSnapshot = old else assertEquals(preservedSnapshot, old)
                    failedReplacements++
                    false
                } else {
                    temp.renameTo(target)
                }
            },
        )
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))
        val localFile = File(repo.storageDirectoryFor(image), "host.txt")
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        val snapshot = File(metadataDir, "$image.json")
        val temp = File(metadataDir, ".$image.json.tmp")
        assertTrue(saf.deleteFile(tree, "host.txt"))
        saf.onList = { failReplacement = true }
        assertFalse(repo.syncAfterStop(image))
        assertTrue(failedReplacements >= 1)
        assertEquals(preservedSnapshot, snapshot.readText())
        assertTrue(snapshot.readText().contains("\"hasPendingLocalChanges\": true"))
        assertFalse(temp.exists())
        assertEquals("host-data", localFile.readText())
        assertNotNull(repo.storageInfoFor(image).syncError)
        saf.onList = null
        failReplacement = false
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.prepareForStart(image))
        assertEquals("host-data", saf.readFileText(tree, "host.txt"))
        assertEquals("host-data", localFile.readText())
    }

    @Test
    fun `given unreadable snapshot when syncing or changing folder then reports failure without changing data`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))
        val localFile = File(repo.storageDirectoryFor(image), "host.txt")
        localFile.writeText("guest-data")
        val snapshot = File(File(tempDir.toFile(), StorageRepository.METADATA_DIR), "$image.json")
        assertTrue(snapshot.setReadable(false, false))
        try {
            assertFalse(repo.prepareForStart(image))
            assertFalse(repo.syncAfterStop(image))
            assertFalse(repo.clearCustomStorageFolder(image))
            assertFalse(repo.setCustomStorageFolder(image, "content://tree/customB"))
            assertFalse(repo.setCustomStorageFolder(image, tree))
            assertNotNull(repo.storageInfoFor(image).syncError)
            assertEquals(tree, settings.customStorageUriFor(image))
            assertEquals("guest-data", localFile.readText())
            assertEquals("host-data", saf.readFileText(tree, "host.txt"))
        } finally {
            assertTrue(snapshot.setReadable(true, false))
        }
    }

    @Test
    fun `given metadata path is non-empty directory when prepareForStart and syncAfterStop run then returns false with syncError without throwing or deleting SAF files`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val treeUri = "content://tree/customA"

        saf.putFile(treeUri, "host.txt", "host-data", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder(image, treeUri))

        val metaFile = File(File(tempDir.toFile(), StorageRepository.METADATA_DIR), "alpine.img.json").apply {
            deleteRecursively()
            mkdirs()
        }
        File(metaFile, "blocker").writeText("block")

        assertFalse(repo.prepareForStart(image))
        assertNotNull(repo.storageInfoFor(image).syncError)
        assertEquals("host-data", saf.readFileText(treeUri, "host.txt"))

        val localDir = repo.storageDirectoryFor(image).apply { mkdirs() }
        File(localDir, "guest.txt").writeText("guest-data")

        assertFalse(repo.syncAfterStop(image))
        assertNotNull(repo.storageInfoFor(image).syncError)
        assertEquals("host-data", saf.readFileText(treeUri, "host.txt"))
        assertEquals("guest-data", File(localDir, "guest.txt").readText())
    }

    @Test
    fun `given metadata delete fails when clearStorage clearCustomStorageFolder setCustomStorageFolder or deleteImageStorage run then aborts preserving URI local data and SAF files`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val treeA = "content://tree/customA"
        val treeB = "content://tree/customB"

        saf.putFile(treeA, "host.txt", "keep-me", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder(image, treeA))
        assertTrue(repo.prepareForStart(image))

        val localDir = repo.storageDirectoryFor(image)
        assertEquals("keep-me", File(localDir, "host.txt").readText())

        val metaFile = File(File(tempDir.toFile(), StorageRepository.METADATA_DIR), "alpine.img.json")
        val savedSnapshotJson = metaFile.readText()
        assertTrue(metaFile.delete())
        assertTrue(metaFile.mkdirs())
        File(metaFile, "blocker").writeText("block")

        // All destructive transitions must abort before clearing local files or changing the bound URI.
        assertFalse(repo.clearStorage(image))
        assertNotNull(repo.storageInfoFor(image).syncError)
        assertEquals(treeA, settings.customStorageUriFor(image))
        assertEquals("keep-me", File(localDir, "host.txt").readText())

        assertFalse(repo.clearCustomStorageFolder(image))
        assertNotNull(repo.storageInfoFor(image).syncError)
        assertEquals(treeA, settings.customStorageUriFor(image))
        assertEquals("keep-me", File(localDir, "host.txt").readText())

        assertFalse(repo.setCustomStorageFolder(image, treeB))
        assertNotNull(repo.storageInfoFor(image).syncError)
        assertEquals(treeA, settings.customStorageUriFor(image))
        assertEquals("keep-me", File(localDir, "host.txt").readText())

        assertFalse(repo.deleteImageStorage(image))
        assertNotNull(repo.storageInfoFor(image).syncError)
        assertEquals(treeA, settings.customStorageUriFor(image))
        assertEquals("keep-me", File(localDir, "host.txt").readText())

        // Restore the snapshot file as if the delete had failed leaving the old snapshot intact;
        // because local data was never cleared, the next prepareForStart must not delete SAF files.
        assertTrue(metaFile.deleteRecursively())
        metaFile.writeText(savedSnapshotJson)

        assertTrue(repo.prepareForStart(image))
        assertEquals("keep-me", saf.readFileText(treeA, "host.txt"))
        assertEquals("keep-me", File(localDir, "host.txt").readText())
    }

    @Test
    fun `given unwritable metadata directory when prepareForStart or syncAfterStop fails fallback write then returns false with syncError without throwing`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val treeUri = "content://tree/customA"

        saf.putFile(treeUri, "host.txt", "v1", lastModified = 1_000L)
        assertTrue(repo.setCustomStorageFolder(image, treeUri))
        assertTrue(repo.prepareForStart(image))

        val localDir = repo.storageDirectoryFor(image)
        File(localDir, "host.txt").apply {
            writeText("v2-guest")
            setLastModified(5_000L)
        }

        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        assertTrue(metadataDir.setWritable(false))
        try {
            // Both primary and fallback snapshot writes fail when the directory is read-only.
            assertFalse(repo.syncAfterStop(image))
            assertNotNull(repo.storageInfoFor(image).syncError)

            saf.failWriteWith = IOException("saf write failed")
            assertFalse(repo.prepareForStart(image))
            assertNotNull(repo.storageInfoFor(image).syncError)
        } finally {
            saf.failWriteWith = null
            assertTrue(metadataDir.setWritable(true))
        }

        assertEquals("v1", saf.readFileText(treeUri, "host.txt"))
        assertEquals("v2-guest", File(localDir, "host.txt").readText())
    }

    @Test
    fun `given snapshot write failure during host deletion when host restores file then stale baseline never deletes it`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))

        val localFile = File(repo.storageDirectoryFor(image), "host.txt")
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        val snapshot = File(metadataDir, "$image.json")
        assertTrue(saf.deleteFile(tree, "host.txt"))
        assertTrue(snapshot.setWritable(false, false))
        assertTrue(metadataDir.setWritable(false, false))
        try {
            assertFalse(repo.prepareForStart(image))
            assertNotNull(repo.storageInfoFor(image).syncError)
            assertEquals("host-data", localFile.readText())
        } finally {
            assertTrue(metadataDir.setWritable(true, false))
            assertTrue(snapshot.setWritable(true, false))
        }

        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.prepareForStart(image))
        assertEquals("host-data", saf.readFileText(tree, "host.txt"))
        assertEquals("host-data", localFile.readText())
    }

    @Test
    fun `given metadata fails after pull starts when host restores deleted file then old baseline is absent and host file survives`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "old.txt", "old")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))

        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        val snapshot = File(metadataDir, "$image.json")
        assertTrue(saf.deleteFile(tree, "old.txt"))
        saf.putFile(tree, "new.txt", "new")
        saf.onOpen = { assertTrue(metadataDir.setWritable(false, false)) }
        try {
            assertFalse(repo.prepareForStart(image))
            assertNotNull(repo.storageInfoFor(image).syncError)
            assertFalse(snapshot.exists())
        } finally {
            saf.onOpen = null
            assertTrue(metadataDir.setWritable(true, false))
        }

        saf.putFile(tree, "old.txt", "old")
        assertTrue(repo.prepareForStart(image))
        assertEquals("old", saf.readFileText(tree, "old.txt"))
        assertEquals("old", File(repo.storageDirectoryFor(image), "old.txt").readText())
    }

    @Test
    fun `given snapshot write failure during stop sync when host restores file then local data and host file survive`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))

        val localFile = File(repo.storageDirectoryFor(image), "host.txt")
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        assertTrue(saf.deleteFile(tree, "host.txt"))
        saf.onList = { assertTrue(metadataDir.setWritable(false, false)) }
        try {
            assertFalse(repo.syncAfterStop(image))
            assertNotNull(repo.storageInfoFor(image).syncError)
            assertEquals("host-data", localFile.readText())
        } finally {
            saf.onList = null
            assertTrue(metadataDir.setWritable(true, false))
        }

        saf.putFile(tree, "host.txt", "host-data")
        assertTrue(repo.prepareForStart(image))
        assertEquals("host-data", saf.readFileText(tree, "host.txt"))
        assertEquals("host-data", localFile.readText())
    }

    @Test
    fun `given partial local cleanup failure during pending flush when host restores files then stale baseline is not restored`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        saf.putFile(tree, "a.txt", "host-a")
        saf.putFile(tree, "locked/b.txt", "host-b")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))

        val dir = repo.storageDirectoryFor(image)
        File(dir, "guest.txt").writeText("guest")
        assertTrue(saf.deleteFile(tree, "a.txt"))
        assertTrue(saf.deleteFile(tree, "locked/b.txt"))
        val lockedDir = File(dir, "locked")
        assertTrue(lockedDir.setWritable(false, false))
        try {
            assertFalse(repo.prepareForStart(image))
            assertFalse(File(dir, "a.txt").exists())
        } finally {
            assertTrue(lockedDir.setWritable(true, false))
        }

        saf.putFile(tree, "a.txt", "host-a")
        saf.putFile(tree, "locked/b.txt", "host-b")
        assertTrue(repo.prepareForStart(image))
        assertEquals("host-a", saf.readFileText(tree, "a.txt"))
        assertEquals("host-b", saf.readFileText(tree, "locked/b.txt"))
    }

    @Test
    fun `given conflicting new files and snapshot write failure when stopping then original local file remains`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "alpine.img"
        val tree = "content://tree/customA"
        assertTrue(repo.setCustomStorageFolder(image, tree))
        assertTrue(repo.prepareForStart(image))
        val localFile = File(repo.storageDirectoryFor(image), "shared.txt")
        localFile.writeText("guest-data")
        saf.putFile(tree, "shared.txt", "host-data")
        val metadataDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        saf.onList = { assertTrue(metadataDir.setWritable(false, false)) }
        try {
            assertFalse(repo.syncAfterStop(image))
            assertEquals("guest-data", localFile.readText())
            assertEquals("host-data", saf.readFileText(tree, "shared.txt"))
        } finally {
            saf.onList = null
            assertTrue(metadataDir.setWritable(true, false))
        }
        assertTrue(repo.prepareForStart(image))
        assertEquals("host-data", saf.readFileText(tree, "shared.txt"))
        assertEquals("guest-data", saf.readFileText(tree, "shared (local).txt"))
        assertEquals("host-data", localFile.readText())
    }

    @Test
    fun `given metadata failure during stop sync when VmRepository stops VM then state collector survives and syncs subsequent runs`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val engine = FakeVmEngine()
        val storageRepo = StorageRepository(tempDir.toFile(), settings, saf, engine.state, dispatcher)
        val vmRepo = VmRepository(engine, storageRepo, dispatcher)
        val image = "alpine.img"
        val treeUri = "content://tree/customA"

        saf.putFile(treeUri, "host.txt", "host-v1", lastModified = 1_000L)
        assertTrue(storageRepo.setCustomStorageFolder(image, treeUri))

        val vmConfig = VmConfig(
            arch = VmArch.ARM,
            imagePath = "/data/user/0/ch.stix.android_cluster/files/vm-images/$image",
        )
        vmRepo.start(vmConfig)
        assertTrue(vmRepo.state.value is VmState.Running)

        val localDir = storageRepo.storageDirectoryFor(image)
        File(localDir, "guest.txt").writeText("from-run-1")

        // Force metadata failure during stop-sync by replacing the snapshot file with a non-empty directory.
        val metaFile = File(File(tempDir.toFile(), StorageRepository.METADATA_DIR), "alpine.img.json")
        val savedSnapshotJson = metaFile.readText()
        assertTrue(metaFile.delete())
        assertTrue(metaFile.mkdirs())
        File(metaFile, "blocker").writeText("block")

        vmRepo.stop()
        assertEquals(VmState.Stopped(0), vmRepo.state.value)
        assertNotNull(storageRepo.storageInfoFor(image).syncError)
        assertEquals("host-v1", saf.readFileText(treeUri, "host.txt"))

        // Restore metadata file and verify the VmRepository state collector is still alive on the next run.
        assertTrue(metaFile.deleteRecursively())
        metaFile.writeText(savedSnapshotJson)

        vmRepo.start(vmConfig)
        assertTrue(vmRepo.state.value is VmState.Running)
        vmRepo.stop()
        assertEquals(VmState.Stopped(0), vmRepo.state.value)
        assertNull(storageRepo.storageInfoFor(image).syncError)
        assertEquals("host-v1", saf.readFileText(treeUri, "host.txt"))
        assertEquals("from-run-1", saf.readFileText(treeUri, "guest.txt"))
    }

    @Test
    fun `given names differing only in unsafe characters when resolving storage then directories differ and clearing one leaves the other intact`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val repo = StorageRepository(tempDir.toFile(), settings, FakeSafTreeDataSource(), ioDispatcher = dispatcher)
        val names = listOf(
            "debian,v1.qcow2", "debian v1.qcow2", "debian_v1.qcow2",
            "образ.img", "диска.img", "../x", ".._x", "..", "debian v1.qcow2 ",
        )
        val storageRoot = File(tempDir.toFile(), "vm-storage").canonicalFile

        val keys = names.map { StorageRepository.storageKeyFor(it) }
        assertEquals(names.size, keys.toSet().size)
        val dirs = names.map { repo.storageDirectoryFor(it) }
        assertEquals(names.size, dirs.map { it.canonicalPath }.toSet().size)
        dirs.forEach {
            assertTrue(it.canonicalFile.toPath().startsWith(storageRoot.toPath()))
            assertNotEquals(storageRoot, it.canonicalFile)
            assertFalse(it.name.contains(","))
        }
        assertEquals("debian_v1.qcow2", StorageRepository.storageKeyFor("debian_v1.qcow2"))
        assertEquals(StorageRepository.storageKeyFor(names[0]), StorageRepository.storageKeyFor(names[0]))

        names.forEachIndexed { i, name ->
            repo.storageDirectoryFor(name).apply { mkdirs() }
            File(repo.storageDirectoryFor(name), "data.txt").writeText("data-$i")
        }
        assertTrue(repo.clearStorage(names[0]))
        assertFalse(File(repo.storageDirectoryFor(names[0]), "data.txt").exists())
        names.drop(1).forEachIndexed { i, name ->
            assertEquals("data-${i + 1}", File(repo.storageDirectoryFor(name), "data.txt").readText())
        }
        assertTrue(repo.deleteImageStorage(names[1]))
        assertFalse(repo.storageDirectoryFor(names[1]).exists())
        names.drop(2).forEachIndexed { i, name ->
            assertEquals("data-${i + 2}", File(repo.storageDirectoryFor(name), "data.txt").readText())
        }
    }

    @Test
    fun `given colliding legacy names with custom folders when syncing then snapshots and data stay separate`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val a = "debian v1.qcow2"
        val b = "debian,v1.qcow2"
        val treeA = "content://tree/A"
        val treeB = "content://tree/B"
        saf.putFile(treeA, "a.txt", "from-a")
        saf.putFile(treeB, "b.txt", "from-b")
        assertTrue(repo.setCustomStorageFolder(a, treeA))
        assertTrue(repo.setCustomStorageFolder(b, treeB))
        assertTrue(repo.prepareForStart(a))
        assertTrue(repo.prepareForStart(b))
        val metaDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR)
        val metaFiles = metaDir.listFiles()!!.filter { it.name.endsWith(".json") }
        assertEquals(2, metaFiles.size)
        assertTrue(File(metaDir, "${StorageRepository.storageKeyFor(a)}.json").isFile)
        assertTrue(File(metaDir, "${StorageRepository.storageKeyFor(b)}.json").isFile)

        assertEquals("from-a", File(repo.storageDirectoryFor(a), "a.txt").readText())
        assertFalse(File(repo.storageDirectoryFor(a), "b.txt").exists())
        assertEquals("from-b", File(repo.storageDirectoryFor(b), "b.txt").readText())
        assertFalse(File(repo.storageDirectoryFor(b), "a.txt").exists())

        assertTrue(repo.clearStorage(a))
        assertEquals("from-b", File(repo.storageDirectoryFor(b), "b.txt").readText())
        File(repo.storageDirectoryFor(b), "guest.txt").writeText("guest-b")
        assertTrue(repo.syncAfterStop(b))
        assertEquals("guest-b", saf.readFileText(treeB, "guest.txt"))
        assertEquals("from-b", saf.readFileText(treeB, "b.txt"))
        assertEquals("from-a", saf.readFileText(treeA, "a.txt"))
        assertNull(saf.readFileText(treeA, "guest.txt"))
        assertTrue(repo.prepareForStart(a))
        assertEquals("from-a", File(repo.storageDirectoryFor(a), "a.txt").readText())
    }

    @Test
    fun `given unambiguous legacy storage folder when resolving directory then migrates data and metadata`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val saf = FakeSafTreeDataSource()
        val repo = StorageRepository(tempDir.toFile(), settings, saf, ioDispatcher = dispatcher)
        val image = "debian v1.qcow2"
        val tree = "content://tree/A"
        saf.putFile(tree, "host.txt", "host")
        assertTrue(repo.setCustomStorageFolder(image, tree))
        File(tempDir.toFile(), "vm-images").apply { mkdirs() }
        File(tempDir.toFile(), "vm-images/$image").writeText("img")
        val legacyDir = File(tempDir.toFile(), "vm-storage/debian_v1.qcow2").apply { mkdirs() }
        File(legacyDir, "old.txt").writeText("old")
        val metaDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR).apply { mkdirs() }
        val legacyMeta = File(metaDir, "debian_v1.qcow2.json").apply { writeText("{}") }

        val dir = repo.storageDirectoryFor(image)

        assertEquals(StorageRepository.storageKeyFor(image), dir.name)
        assertEquals("old", File(dir, "old.txt").readText())
        assertFalse(legacyDir.exists())
        assertFalse(legacyMeta.exists())
        assertEquals("{}", File(metaDir, "${StorageRepository.storageKeyFor(image)}.json").readText())
    }

    @Test
    fun `given ambiguous legacy storage folder when resolving directory then leaves it untouched and does not import it`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settings = SettingsRepository(tempDir.toFile(), dispatcher)
        val repo = StorageRepository(tempDir.toFile(), settings, FakeSafTreeDataSource(), ioDispatcher = dispatcher)
        val imagesDir = File(tempDir.toFile(), "vm-images").apply { mkdirs() }
        File(imagesDir, "debian v1.qcow2").writeText("img1")
        File(imagesDir, "debian,v1.qcow2").writeText("img2")
        val legacyDir = File(tempDir.toFile(), "vm-storage/debian_v1.qcow2").apply { mkdirs() }
        File(legacyDir, "shared.txt").writeText("shared")
        val metaDir = File(tempDir.toFile(), StorageRepository.METADATA_DIR).apply { mkdirs() }
        val legacyMeta = File(metaDir, "debian_v1.qcow2.json").apply { writeText("{}") }

        val dirSpace = repo.storageDirectoryFor("debian v1.qcow2")
        val dirComma = repo.storageDirectoryFor("debian,v1.qcow2")

        assertFalse(File(dirSpace, "shared.txt").exists())
        assertFalse(File(dirComma, "shared.txt").exists())
        assertEquals("shared", File(legacyDir, "shared.txt").readText())
        assertEquals("{}", legacyMeta.readText())
        assertEquals(legacyDir, repo.storageDirectoryFor("debian_v1.qcow2"))

        File(imagesDir, "debian_v1.qcow2").writeText("img3")
        assertTrue(repo.clearStorage("debian v1.qcow2"))
        assertEquals("shared", File(legacyDir, "shared.txt").readText())
    }

    @Test
    fun `given safe image names when resolving storage key then existing directories are reused verbatim`(
        @TempDir tempDir: Path,
    ) = runTest {
        for (name in listOf("alpine.img", "debian-12_amd64.qcow2", ".hidden", "a.b.c")) {
            assertEquals(name, StorageRepository.storageKeyFor(name))
        }
        for (name in listOf("", "   ", ".", "..", "a..b", "a b", "a/b", "x".repeat(200))) {
            val key = StorageRepository.storageKeyFor(name)
            assertTrue(key.contains('~'), name)
            assertTrue(key.length < 255)
            assertFalse(key.contains('/'))
        }
    }
}
