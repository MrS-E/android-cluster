package ch.stix.android_cluster.data

import ch.stix.android_cluster.engine.PortMapping
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppSettingsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `given settings when serialized and restored then values are preserved`() {
        val settings = AppSettings(
            selectedImage = "alpine.img",
            portMappings = listOf(PortMapping(31415, 22), PortMapping(8080, 80)),
        )

        val restored = json.decodeFromString<AppSettings>(json.encodeToString(settings))

        assertEquals(settings, restored)
    }

    @Test
    fun `given empty json when decoded then defaults are used`() {
        val restored = json.decodeFromString<AppSettings>("{}")

        assertEquals(AppSettings(), restored)
    }
}
