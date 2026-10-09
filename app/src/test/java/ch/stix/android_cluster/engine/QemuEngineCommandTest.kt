package ch.stix.android_cluster.engine

import ch.stix.android_cluster.engine.PortMappingValidator.Result
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QemuEngineCommandTest {

    private val engine = QemuEngine(
        nativeLibraryDir = "/data/app/lib/arm64",
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    @Test
    fun `given ARM config when buildCommand then virt machine args with hostfwd rules`() {
        val config = VmConfig(
            arch = VmArch.ARM,
            imagePath = "/data/data/app/files/vm-images/alpine.img",
            memoryMb = 256,
            portMappings = listOf(PortMapping(31415, 22)),
        )

        val command = engine.buildCommand(config)

        assertEquals("/data/app/lib/arm64/libqemu-system-arm.so", command.first())
        assertTrue(command.containsAll(listOf("-M", "virt")))
        assertTrue(command.containsAll(listOf("-cpu", "cortex-a15")))
        assertTrue(command.containsAll(listOf("-kernel", config.imagePath)))
        assertTrue(command.any { it.contains("hostfwd=tcp::31415-:22") })
        assertTrue(command.contains("virtio-net-pci,netdev=n0"))
    }

    @Test
    fun `given no port mappings when buildCommand then no netdev args`() {
        val config = VmConfig(arch = VmArch.ARM, imagePath = "/tmp/img")

        val command = engine.buildCommand(config)

        assertTrue(command.none { it.startsWith("hostfwd=") })
        assertTrue(command.none { it == "-netdev" })
    }

    @Test
    fun `given multiple mappings when buildCommand then one hostfwd rule per mapping`() {
        val config = VmConfig(
            arch = VmArch.ARM,
            imagePath = "/tmp/img",
            portMappings = listOf(PortMapping(31415, 22), PortMapping(8080, 80)),
        )

        val command = engine.buildCommand(config)
        val netdev = command[command.indexOf("-netdev") + 1]

        assertTrue(netdev.contains("hostfwd=tcp::31415-:22"))
        assertTrue(netdev.contains("hostfwd=tcp::8080-:80"))
    }
}

class PortMappingValidatorTest {

    @Test
    fun `given valid ports when validate then mapping is returned`() {
        val result = PortMappingValidator.validate(emptyList(), 31415, 22)

        assertTrue(result is Result.Valid)
        assertEquals(PortMapping(31415, 22), (result as Result.Valid).mapping)
    }

    @Test
    fun `given duplicate host port when validate then invalid`() {
        val existing = listOf(PortMapping(31415, 22))

        val result = PortMappingValidator.validate(existing, 31415, 80)

        assertTrue(result is Result.Invalid)
        assertTrue((result as Result.Invalid).message.contains("already mapped"))
    }

    @Test
    fun `given out of range port when validate then invalid`() {
        val result = PortMappingValidator.validate(emptyList(), 70000, 22)

        assertTrue(result is Result.Invalid)
    }

    @Test
    fun `given adb forward port when validate then invalid`() {
        val result = PortMappingValidator.validate(
            emptyList(),
            PortMappingValidator.ADB_FORWARD_PORT,
            22,
        )

        assertTrue(result is Result.Invalid)
        assertTrue((result as Result.Invalid).message.contains("reserved"))
    }
}
