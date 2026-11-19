package ch.stix.android_cluster.data

import ch.stix.android_cluster.engine.PortMapping
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `given settings when serialized and restored then values are preserved`() {
        val settings = AppSettings(
            selectedImage = "alpine.img",
            portMappings = listOf(PortMapping(31415, 22), PortMapping(8080, 80)),
            customStorageUris = mapOf(
                "alpine.img" to "content://com.android.externalstorage.documents/tree/primary%3AVmShare",
            ),
        )

        val restored = json.decodeFromString<AppSettings>(json.encodeToString(settings))

        assertEquals(settings, restored)
    }

    @Test
    fun `given empty json when decoded then defaults are used`() {
        val restored = json.decodeFromString<AppSettings>("{}")

        assertEquals(AppSettings(), restored)
    }

    @Test
    fun `given legacy json without customStorageUris when decoded then customStorageUris defaults to emptyMap`() {
        val legacyJson = """{"selectedImage":"debian.qcow2","portMappings":[{"hostPort":2222,"guestPort":22}]}"""

        val restored = json.decodeFromString<AppSettings>(legacyJson)

        assertEquals("debian.qcow2", restored.selectedImage)
        assertEquals(listOf(PortMapping(2222, 22)), restored.portMappings)
        assertEquals(emptyMap<String, String>(), restored.customStorageUris)
    }

    @Test
    fun `given settings repository when custom storage uri is set and cleared then persists across instances`(
        @TempDir tempDir: Path,
    ) {
        val dispatcher = UnconfinedTestDispatcher()
        val repo1 = SettingsRepository(tempDir.toFile(), dispatcher)
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADemoStorage"

        repo1.setCustomStorageUri("centos.qcow2", uri)
        assertEquals(uri, repo1.customStorageUriFor("centos.qcow2"))

        val repo2 = SettingsRepository(tempDir.toFile(), dispatcher)
        assertEquals(uri, repo2.customStorageUriFor("centos.qcow2"))

        repo2.clearCustomStorageUri("centos.qcow2")
        assertNull(repo2.customStorageUriFor("centos.qcow2"))

        val repo3 = SettingsRepository(tempDir.toFile(), dispatcher)
        assertNull(repo3.customStorageUriFor("centos.qcow2"))
    }
}
