package ch.stix.android_cluster.engine

import android.content.res.AssetManager
import ch.stix.android_cluster.engine.PortMappingValidator.Result
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class QemuEngineCommandTest {

    private val engine = QemuEngine(
        nativeLibraryDir = "/data/app/lib/arm64",
        qemuDataDirectory = File("/data/data/app/files/qemu"),
        assetManager = mockk<AssetManager>(relaxed = true),
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

        assertEquals("/data/app/lib/arm64/libqemu-system-aarch64.so", command.first())
        assertTrue(command.containsAll(listOf("-M", "virt")))
        assertTrue(command.containsAll(listOf("-cpu", "cortex-a57")))
        assertTrue(command.containsAll(listOf("-kernel", config.imagePath)))
        assertTrue(command.any { it.contains("hostfwd=tcp::31415-:22") })
        assertTrue(command.contains("virtio-net-pci,netdev=n0"))
    }

    @Test
    fun `given ARM64 qcow2 image when buildCommand then attaches it as a disk`() {
        val config = VmConfig(
            arch = VmArch.ARM,
            imagePath = "/data/data/app/files/vm-images/debian-12-nocloud-arm64.qcow2",
            portMappings = listOf(PortMapping(31415, 22)),
        )

        val command = engine.buildCommand(config)

        assertEquals("/data/app/lib/arm64/libqemu-system-aarch64.so", command.first())
        assertTrue(command.containsAll(listOf("-M", "virt", "-cpu", "cortex-a57")))
        assertTrue(command.containsAll(listOf("-m", "1024")))
        assertTrue(command.any { it.contains("format=qcow2") && it.contains(config.imagePath) })
        assertTrue(command.contains("virtio-blk-pci,drive=hd0,bootindex=0"))
        assertTrue(command.any { it.contains("edk2-aarch64-code.fd") })
        assertTrue(command.any { it.contains("edk2-arm-vars.fd") })
        assertTrue(command.contains("virtio-net-pci,netdev=n0"))
        assertTrue(command.none { it == "-kernel" })
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

    @Test
    fun `given storagePath on kernel image when buildCommand then attaches virtio 9p device and rom dir`() {
        val config = VmConfig(
            arch = VmArch.ARM,
            imagePath = "/data/data/app/files/vm-images/alpine.img",
            storagePath = "/data/data/app/files/vm-storage/alpine.img",
        )

        val command = engine.buildCommand(config)

        val fsdevIndex = command.indexOf("-fsdev")
        assertTrue(fsdevIndex >= 0)
        assertEquals(
            "local,id=fsdev0,path=/data/data/app/files/vm-storage/alpine.img,security_model=none",
            command[fsdevIndex + 1],
        )
        assertTrue(command.contains("virtio-9p-pci,fsdev=fsdev0,mount_tag=host0"))
        val romDirIndex = command.indexOf("-L")
        assertTrue(romDirIndex >= 0)
        assertEquals("/data/data/app/files/qemu", command[romDirIndex + 1])
    }

    @Test
    fun `given storagePath on qcow2 image when buildCommand then attaches both disk and virtio 9p device`() {
        val config = VmConfig(
            arch = VmArch.ARM,
            imagePath = "/data/data/app/files/vm-images/centos.qcow2",
            storagePath = "/data/data/app/files/vm-storage/centos.qcow2",
            storageMountTag = "custom0",
        )

        val command = engine.buildCommand(config)

        assertTrue(command.contains("virtio-blk-pci,drive=hd0,bootindex=0"))
        val fsdevIndex = command.indexOf("-fsdev")
        assertTrue(fsdevIndex >= 0)
        assertEquals(
            "local,id=fsdev0,path=/data/data/app/files/vm-storage/centos.qcow2,security_model=none",
            command[fsdevIndex + 1],
        )
        assertTrue(command.contains("virtio-9p-pci,fsdev=fsdev0,mount_tag=custom0"))
    }

    @Test
    fun `given storagePath containing commas when buildCommand then escapes commas in fsdev path`() {
        val config = VmConfig(
            arch = VmArch.ARM,
            imagePath = "/data/data/app/files/vm-images/debian,v1.qcow2",
            storagePath = "/data/data/app/files/vm-storage/debian,v1,data",
        )

        val command = engine.buildCommand(config)

        val fsdevIndex = command.indexOf("-fsdev")
        assertTrue(fsdevIndex >= 0)
        assertEquals(
            "local,id=fsdev0,path=/data/data/app/files/vm-storage/debian,,v1,,data,security_model=none",
            command[fsdevIndex + 1],
        )
    }

    @Test
    fun `given null storagePath when buildCommand then omits fsdev and virtio 9p args`() {
        val config = VmConfig(
            arch = VmArch.ARM,
            imagePath = "/data/data/app/files/vm-images/alpine.img",
            storagePath = null,
        )

        val command = engine.buildCommand(config)

        assertTrue(command.none { it == "-fsdev" })
        assertTrue(command.none { it.startsWith("virtio-9p-pci") })
        assertTrue(command.none { it == "-L" })
    }

    @Test
    fun `given kernel image with storagePath and no port mappings when checking runtime assets then stages efi virtio rom`() {
        val withoutStorage = VmConfig(
            arch = VmArch.ARM,
            imagePath = "/data/data/app/files/vm-images/alpine.img",
        )
        val withStorage = withoutStorage.copy(
            storagePath = "/data/data/app/files/vm-storage/alpine.img",
        )

        assertTrue(engine.requiredRuntimeAssets(withoutStorage).isEmpty())
        assertEquals(
            listOf(QemuEngine.EFI_VIRTIO_ROM_ASSET),
            engine.requiredRuntimeAssets(withStorage),
        )
    }

    @Test
    fun `given missing storage directory when ensureStorageDirectory then creates writable directory`(@TempDir tempDir: Path) {
        val storageDir = tempDir.resolve("vm-storage/alpine").toFile()
        assertFalse(storageDir.exists())

        assertTrue(QemuEngine.ensureStorageDirectory(storageDir))
        assertTrue(storageDir.isDirectory)
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

class QemuFailureMessageTest {

    @Test
    fun `given QEMU stderr when formatting nonzero exit then failure includes diagnostic output`() {
        val message = formatQemuExitFailure(1, listOf("failed to find romfile 'efi-virtio.rom'"))

        assertEquals(
            "QEMU exited with code 1: failed to find romfile 'efi-virtio.rom'",
            message,
        )
    }
}

class QemuFirmwareCacheTest {

    @Test
    fun `given exact firmware flash size when validating variable store then accepts it`() {
        assertTrue(QemuEngine.isUefiFlashImageSizeValid(64L * 1024 * 1024))
    }

    @Test
    fun `given truncated firmware flash when validating variable store then rejects it`() {
        assertFalse(QemuEngine.isUefiFlashImageSizeValid(64L * 1024 * 1024 - 1))
    }

    @Test
    fun `given matching firmware fingerprint and flash size when checking cache then reuses image`() {
        assertTrue(QemuEngine.canReuseUefiCodeImage(64L * 1024 * 1024, "current", "current"))
    }

    @Test
    fun `given changed firmware fingerprint when checking cache then rebuilds image`() {
        assertFalse(QemuEngine.canReuseUefiCodeImage(64L * 1024 * 1024, "previous", "current"))
    }
}
