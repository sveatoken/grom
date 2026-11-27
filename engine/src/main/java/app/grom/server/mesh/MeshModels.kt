// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.cluster.NodeIdentity

/**
 * How another Grom server was heard (D99).
 *
 * The transport is a *discovery* fact, not a promise of bandwidth. Only the
 * three TCP/IP carriers can move the data plane's HTTP/JSON traffic; BLE
 * advertisements are a few bytes on purpose and carry no request.
 */
enum class MeshTransport(val wireName: String, val dataCapable: Boolean) {
    /** The phone is on the same local network (or reachable through a VPN). */
    LAN("lan", true),

    /** A Wi-Fi Direct group owner address. */
    WIFI_DIRECT("wifi_direct", true),

    /** A bonded Bluetooth Classic RFCOMM link carrying HTTP/1.1 (D102). */
    BLUETOOTH_CLASSIC("bluetooth_classic", true),

    /** A BLE advertisement: enough for a name and a port, never for a call. */
    BLUETOOTH_LE("bluetooth_le", false);

    /**
     * True for the two carriers the Bluetooth switch governs (D109).
     *
     * Kept as a computed property rather than a third constructor argument so
     * the entry shapes stay exactly what the policy check pins, and so adding a
     * transport cannot forget the flag silently: the default is "not
     * Bluetooth".
     */
    val isBluetooth: Boolean
        get() = this == BLUETOOTH_CLASSIC || this == BLUETOOTH_LE

    companion object {
        fun fromWire(raw: String?): MeshTransport? =
            entries.firstOrNull { it.wireName == raw?.trim() }

        /** Data carriers, in the order the policy prefers them. */
        fun dataCarriers(): List<MeshTransport> = listOf(LAN, WIFI_DIRECT, BLUETOOTH_CLASSIC)
    }
}

/**
 * One server heard nearby, before the operator has trusted it (D99).
 *
 * A candidate is **not** a peer. Nothing here is authenticated: a LAN beacon is
 * self-signed (so a sender cannot claim somebody else's node id without that
 * node's key), a BLE advertisement is unsigned and only carries a prefix, and
 * a Wi-Fi Direct device name is whatever the other side set. Trust arrives only
 * through the existing pairing handshake (D71), which compares fingerprints on
 * both screens.
 *
 * [address] is the endpoint a *data* carrier would use. It is empty for BLE and
 * for a LAN beacon received on an interface we cannot address back (for
 * example a one-way capture). An empty address means "discovered, cannot be
 * dialled", and the caller says exactly that instead of guessing a host.
 */
data class MeshCandidate(
    /** Stable dedupe key: node id when known, otherwise the endpoint/prefix. */
    val key: String,
    val nodeId: String?,
    /** The first 4 bytes of the node id, hex, when only a BLE advert was seen. */
    val nodeIdPrefix: String?,
    val publicKey: String?,
    val label: String?,
    val transport: MeshTransport,
    val address: String,
    val port: Int,
    /**
     * True only when a LAN beacon carried a signature that verifies against the
     * public key it names. It proves the sender holds that key; it does **not**
     * prove this device should trust it.
     */
    val signedBeacon: Boolean,
    val firstSeenMs: Long,
    val lastSeenMs: Long
) {

    fun isDataCapable(): Boolean = transport.dataCapable && address.isNotEmpty()

    fun fingerprint(): String? = nodeId?.let { NodeIdentity.formatFingerprint(it) }

    /** A short, address-free description for a log or a roster. */
    fun describe(): String = "${transport.wireName}:${nodeId ?: nodeIdPrefix ?: "unknown"}"
}

/** Limits shared by the discovery book and the projections. */
object MeshLimits {

    /** How long a candidate stays "nearby" without being heard again. */
    const val CANDIDATE_TTL_MS: Long = 120_000

    /** A bounded book: a hostile LAN cannot grow it without bound. */
    const val MAX_CANDIDATES: Int = 32

    /** Beacons are small; anything bigger is not ours. */
    const val MAX_BEACON_BYTES: Int = 512

    /** Port announced by a beacon; the data plane port, never 0. */
    const val MIN_PORT: Int = 1
    const val MAX_PORT: Int = 65_535
}
