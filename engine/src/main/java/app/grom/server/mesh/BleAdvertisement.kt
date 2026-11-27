// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

/**
 * The BLE advertisement that says "a Grom server is here" (D99).
 *
 * A legacy advertisement is **31 bytes in total**, and the budget is spent like
 * this:
 *
 * ```
 *   flags                   1 length + 1 type + 1 value        =  3
 *   128-bit service data    1 length + 1 type + 16 uuid + 8    = 26
 *                                                          -----------
 *                                                             29 bytes
 * ```
 *
 * That leaves two bytes unused, and there is **no room for the 128-bit service
 * UUID list as well**: that field alone is `1 + 1 + 16 = 18` bytes and would
 * make 47. An advertisement over the limit is rejected by the stack with
 * `ADVERTISE_FAILED_DATA_TOO_LARGE`, and discovery then looks "on" while nothing
 * is ever sent — which is why the adapters publish the service **data** only
 * (it already names the UUID) and this file owns the arithmetic the test pins.
 *
 * The payload is therefore eight bytes and deliberately says no more than it
 * has to:
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

    /** The legacy advertising PDU limit; anything larger is refused by the stack. */
    const val LEGACY_AD_MAX_BYTES: Int = 31

    /** Flags AD structure: length, type, one value byte. */
    const val AD_FLAGS_BYTES: Int = 3

    /** 128-bit service data: length, type, the UUID, then the payload. */
    const val AD_SERVICE_DATA_128_OVERHEAD_BYTES: Int = 2 + 16

    /** 128-bit service UUID list — 18 bytes this advertisement cannot afford. */
    const val AD_SERVICE_UUID_128_BYTES: Int = 2 + 16

    /**
     * The exact size of the advertisement the adapters publish. A test pins it
     * under [LEGACY_AD_MAX_BYTES]; adding the UUID list would exceed it.
     */
    fun legacyAdvertisementBytes(): Int =
        AD_FLAGS_BYTES + AD_SERVICE_DATA_128_OVERHEAD_BYTES + PAYLOAD_BYTES

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
