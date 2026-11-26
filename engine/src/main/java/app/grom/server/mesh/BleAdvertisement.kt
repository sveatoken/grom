// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

/**
 * The BLE advertisement that says "a Grom server is here" (D99).
 *
 * A legacy advertisement is 31 bytes in total: three go to the flags, eighteen
 * to a 128-bit service data field, which leaves ten. The payload is therefore
 * eight bytes and deliberately says no more than it has to:
 *
 * ```
 *   [0]      version (1)
 *   [1]      flags: bit 0 = the data plane is up
 *   [2..5]   the first four bytes of the node id (8 hex characters)
 *   [6..7]   the data plane port, big endian
 * ```
 *
 * The prefix is a routing hint, not an identity: two devices can collide, and
 * nothing signed is here. A scanner must treat the result as
 * [MeshCandidate.signedBeacon] = false and go through pairing before anything
 * is dialled. BLE is a discovery carrier only — the same advertisement is never
 * used to move a request.
 */
object BleAdvertisement {

    /**
     * The 128-bit service UUID a scanner filters on.
     *
     * It is derived from the ASCII text `grom-mesh-v1`, so the value is stable
     * and explainable rather than a random constant copied from a device. The
     * adapter publishes it as a `ParcelUuid` with the same string.
     */
    const val SERVICE_UUID: String = "67726f6d-6d65-7368-7631-000000000001"

    const val VERSION: Int = 1
    const val PAYLOAD_BYTES: Int = 8

    const val FLAG_DATA_PLANE_UP: Int = 0x01

    data class Decoded(
        val nodeIdPrefix: String,
        val port: Int,
        val dataPlaneUp: Boolean
    ) {
        /** The key this advertisement dedupes on when no full id is known. */
        fun key(): String = "ble:$nodeIdPrefix"
    }

    private val PREFIX = Regex("^[0-9a-f]{8}$")

    /** Returns the eight-byte payload, or null when the node id is malformed. */
    fun encode(nodeId: String, port: Int, dataPlaneUp: Boolean = true): ByteArray? {
        if (!NodeIdentityPattern.matches(nodeId)) return null
        if (port !in MeshLimits.MIN_PORT..MeshLimits.MAX_PORT) return null
        val prefix = nodeId.substring(0, 8)
        val bytes = ByteArray(PAYLOAD_BYTES)
        bytes[0] = VERSION.toByte()
        bytes[1] = (if (dataPlaneUp) FLAG_DATA_PLANE_UP else 0).toByte()
        for (index in 0 until 4) {
            bytes[2 + index] = prefix.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        bytes[6] = ((port ushr 8) and 0xFF).toByte()
        bytes[7] = (port and 0xFF).toByte()
        return bytes
    }

    /** Strict decode: a different version or a short buffer is simply not ours. */
    fun decode(raw: ByteArray?): Decoded? {
        if (raw == null || raw.size != PAYLOAD_BYTES) return null
        if (raw[0].toInt() and 0xFF != VERSION) return null
        val flags = raw[1].toInt() and 0xFF
        val prefix = buildString {
            for (index in 2..5) {
                append("%02x".format(raw[index].toInt() and 0xFF))
            }
        }
        if (!PREFIX.matches(prefix)) return null
        val port = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        if (port !in MeshLimits.MIN_PORT..MeshLimits.MAX_PORT) return null
        return Decoded(
            nodeIdPrefix = prefix,
            port = port,
            dataPlaneUp = (flags and FLAG_DATA_PLANE_UP) != 0
        )
    }

    private object NodeIdentityPattern {
        private val PATTERN = Regex("^[0-9a-f]{16}$")

        fun matches(value: String): Boolean = PATTERN.matches(value)
    }
}
