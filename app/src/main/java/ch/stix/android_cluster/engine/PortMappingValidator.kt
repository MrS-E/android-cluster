package ch.stix.android_cluster.engine

/**
 * Validation shared by the settings repository and the UI for host → guest
 * port mappings. Kept pure so it can be unit-tested without Android.
 */
object PortMappingValidator {

    /** Host port reserved for `adb forward`; mapping it would break adb. */
    const val ADB_FORWARD_PORT = 5037

    sealed interface Result {
        data class Valid(val mapping: PortMapping) : Result
        data class Invalid(val message: String) : Result
    }

    /**
     * Validates a new mapping against [existing]: both ports must be in
     * 1..65535, the host port must not be the adb-forward port and must not
     * already be mapped.
     */
    fun validate(
        existing: List<PortMapping>,
        hostPort: Int,
        guestPort: Int,
    ): Result {
        if (hostPort !in PortMapping.MIN_PORT..PortMapping.MAX_PORT ||
            guestPort !in PortMapping.MIN_PORT..PortMapping.MAX_PORT
        ) {
            return Result.Invalid(
                "Ports must be between ${PortMapping.MIN_PORT} and ${PortMapping.MAX_PORT}"
            )
        }
        if (hostPort == ADB_FORWARD_PORT) {
            return Result.Invalid("Host port $ADB_FORWARD_PORT is reserved for adb")
        }
        if (existing.any { it.hostPort == hostPort }) {
            return Result.Invalid("Host port $hostPort is already mapped")
        }
        return Result.Valid(PortMapping(hostPort, guestPort))
    }
}
