package ch.stix.android_cluster.engine

import kotlinx.serialization.Serializable

/**
 * A host-to-guest TCP port mapping applied as a QEMU `hostfwd` rule:
 * connections to [hostPort] on the Android device are forwarded to
 * [guestPort] inside the VM, making guest services reachable from the LAN.
 */
@Serializable
data class PortMapping(val hostPort: Int, val guestPort: Int) {
    init {
        require(hostPort in 1..65535) { "hostPort must be in 1..65535, was $hostPort" }
        require(guestPort in 1..65535) { "guestPort must be in 1..65535, was $guestPort" }
    }

    companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65535

        /** Returns null when either port is invalid, otherwise the mapping. */
        fun create(hostPort: Int, guestPort: Int): PortMapping? =
            if (hostPort in MIN_PORT..MAX_PORT && guestPort in MIN_PORT..MAX_PORT) {
                PortMapping(hostPort, guestPort)
            } else {
                null
            }
    }
}

/**
 * Full boot configuration handed to the engine when starting the VM.
 */
data class VmConfig(
    val arch: VmArch,
    val imagePath: String,
    val memoryMb: Int = 256,
    val portMappings: List<PortMapping> = emptyList(),
)
