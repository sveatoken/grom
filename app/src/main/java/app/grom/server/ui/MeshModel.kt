// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import app.grom.server.R

/**
 * The server network section (D99-D102).
 *
 * Pure Kotlin: it parses the document the engine already measured and decides
 * which rows exist, what each one says and which lamp it gets. The activity
 * decides how to draw it. That keeps "a lamp is green only for a measured
 * `alive`" testable on the JVM, exactly like [FleetModel] and [DoctorModel]
 * (D30).
 *
 * The distinction the screen exists to make:
 *
 *  - a **nearby** row is an unpaired hint. Its fingerprint is a prefix, and its
 *    address is what the operator would type into the pairing dialog;
 *  - a **paired** row is a trust decision that was made, with a measured state
 *    and the services the *other* server publishes;
 *  - a radio row is "can this phone even scan", including the exact missing
 *    permission, so an empty nearby list is never mistaken for "nobody there".
 */
data class MeshServerRow(
    val nodeId: String,
    val label: String,
    val fingerprint: String,
    val state: String,
    val transports: List<String>,
    val services: List<MeshServiceRow>,
    val servicesListed: Boolean,
    val catalogError: String?,
    val aiEnabled: Boolean,
    val aiModels: List<String>,
    val sharedServices: List<String>,
    val inboundOnly: Boolean
) {
    fun canCall(): Boolean = services.isNotEmpty() || aiEnabled
}

data class MeshServiceRow(val id: String, val title: String)

data class MeshNearbyRow(
    val key: String,
    val nodeId: String?,
    val nodeIdPrefix: String?,
    val fingerprint: String?,
    val transport: String,
    val address: String,
    val port: Int,
    val signedBeacon: Boolean,
    val dataCapable: Boolean
) {
    /** Pairing needs an endpoint; a BLE-only sighting has none. */
    fun canPair(): Boolean = address.isNotEmpty()
}

data class MeshRadioRow(
    val transport: String,
    /**
     * Whether the operator's switches let this radio run (D109). A Bluetooth
     * radio that is left out is not broken and must not be shown as an error.
     */
    val inUse: Boolean,
    val available: Boolean,
    /**
     * The platform switch measured now: `null` when the radio has none or it
     * cannot be read. `false` means the operator has the system radio off.
     */
    val platformOn: Boolean?,
    val missingPermissions: List<String>,
    val error: String?
)

data class MeshScreen(
    val enabled: Boolean,
    val advertise: Boolean,
    val scan: Boolean,
    /** Whether Grom uses the Bluetooth radios at all (D109). */
    val bluetooth: Boolean,
    /** The system Bluetooth switch, measured; null when the phone has none. */
    val bluetoothOn: Boolean?,
    val dataPlanePort: Int,
    val serverRunning: Boolean,
    val servers: List<MeshServerRow>,
    val nearby: List<MeshNearbyRow>,
    val radios: List<MeshRadioRow>,
    val localServices: List<String>,
    val identityPresent: Boolean,
    val lastError: String?
) {

    /**
     * Discovery cannot work until the public data plane is reachable: a beacon
     * that advertises a loopback port would send other phones to an address
     * they cannot open.
     */
    fun needsDataPlane(): Boolean = enabled && dataPlanePort <= 0

    /**
     * True only when Bluetooth is switched on for the mesh *and* the phone can
     * use it. The screen then offers the system settings page — never a toggle
     * of its own, because Android 13+ will not let an app flip the radio.
     */
    fun needsSystemBluetooth(): Boolean = bluetooth && bluetoothOn == false

    fun measuredAlive(): Int = servers.count { it.state == "alive" }
}

object MeshModel {

    val TRANSPORT_ORDER: List<String> = listOf(
        "lan",
        "wifi_direct",
        "bluetooth_classic",
        "bluetooth_le"
    )

    /**
     * What a mesh pairing asks for (D104).
     *
     * [PAIR_BASE_SCOPES] is exactly what the Devices flow has always requested;
     * the two service scopes are added only by the server network's pairing
     * dialog, because a phone that is merely listed in `Devices` should not be
     * able to call services by default. The engine refuses anything outside its
     * peer-grantable set, so `mesh.read`/`mesh.call` can never cross a pairing.
     */
    val PAIR_BASE_SCOPES: List<String> = listOf("status.read", "config.read")
    val PAIR_SERVICES_READ: String = "services.read"
    val PAIR_SERVICES_CALL: String = "services.call"

    fun pairScopes(withServices: Boolean, withCalls: Boolean): List<String> = buildList {
        addAll(PAIR_BASE_SCOPES)
        if (withServices) add(PAIR_SERVICES_READ)
        if (withCalls) add(PAIR_SERVICES_CALL)
    }

    fun parse(document: Map<*, *>?): MeshScreen {
        val root = document ?: emptyMap<String, Any?>()
        return MeshScreen(
            enabled = root["enabled"] as? Boolean ?: false,
            advertise = root["advertise"] as? Boolean ?: false,
            scan = root["scan"] as? Boolean ?: false,
            // An older engine omits the key; "on" keeps the pre-D109
            // behaviour rather than silently hiding a transport.
            bluetooth = root["bluetooth"] as? Boolean ?: true,
            bluetoothOn = root["bluetoothOn"] as? Boolean,
            dataPlanePort = (root["dataPlanePort"] as? Number)?.toInt() ?: 0,
            serverRunning = root["serverRunning"] as? Boolean ?: false,
            servers = objects(root["servers"]).map { server ->
                val ai = server["ai"] as? Map<*, *>
                MeshServerRow(
                    nodeId = server["nodeId"] as? String ?: "",
                    label = server["label"] as? String ?: "",
                    fingerprint = server["fingerprint"] as? String
                        ?: FleetModel.fingerprint(server["nodeId"] as? String),
                    state = server["state"] as? String ?: "unknown",
                    transports = strings(server["transports"]),
                    services = objects(server["services"]).mapNotNull { service ->
                        val id = service["id"] as? String ?: return@mapNotNull null
                        MeshServiceRow(id = id, title = service["title"] as? String ?: id)
                    },
                    servicesListed = server["servicesListed"] as? Boolean ?: false,
                    catalogError = (server["catalogError"] as? String)?.takeIf { it.isNotEmpty() },
                    aiEnabled = ai?.get("enabled") as? Boolean ?: false,
                    aiModels = strings(ai?.get("models")),
                    sharedServices = strings(server["sharedServices"]),
                    inboundOnly = server["inboundOnly"] as? Boolean ?: false
                )
            },
            nearby = objects(root["nearby"]).map { candidate ->
                MeshNearbyRow(
                    key = candidate["key"] as? String ?: "",
                    nodeId = candidate["nodeId"] as? String,
                    nodeIdPrefix = candidate["nodeIdPrefix"] as? String,
                    fingerprint = candidate["fingerprint"] as? String,
                    transport = candidate["transport"] as? String ?: "",
                    address = candidate["address"] as? String ?: "",
                    port = (candidate["port"] as? Number)?.toInt() ?: 0,
                    signedBeacon = candidate["signedBeacon"] as? Boolean ?: false,
                    dataCapable = candidate["dataCapable"] as? Boolean ?: false
                )
            },
            radios = objects(root["radios"]).map { radio ->
                MeshRadioRow(
                    transport = radio["transport"] as? String ?: "",
                    inUse = radio["inUse"] as? Boolean ?: true,
                    available = radio["available"] as? Boolean ?: false,
                    platformOn = radio["platformOn"] as? Boolean,
                    missingPermissions = strings(radio["missingPermissions"]),
                    error = (radio["error"] as? String)?.takeIf { it.isNotEmpty() }
                )
            },
            localServices = strings(root["localServices"]),
            identityPresent = root["identity"] is Map<*, *>,
            lastError = (root["lastError"] as? String)?.takeIf { it.isNotEmpty() }
        )
    }

    fun lampOf(server: MeshServerRow): Lamp =
        FleetModel.lampOf(mapOf("state" to server.state))

    fun stateRes(server: MeshServerRow): Int = FleetModel.stateRes(server.state)

    /**
     * The sentence for "the platform switch this radio needs is off".
     *
     * Bluetooth and Wi-Fi Direct have different switches and different pages, so
     * one message for both would send the operator to the wrong screen.
     */
    fun platformOffRes(transport: String): Int = when (transport) {
        "wifi_direct" -> R.string.mesh_err_wifi_direct_disabled
        "bluetooth_classic", "bluetooth_le" -> R.string.mesh_err_bluetooth_disabled
        else -> R.string.mesh_radio_not_supported
    }

    fun transportRes(transport: String): Int = when (transport) {
        "lan" -> R.string.mesh_transport_lan
        "wifi_direct" -> R.string.mesh_transport_wifi_direct
        "bluetooth_classic" -> R.string.mesh_transport_bluetooth_classic
        "bluetooth_le" -> R.string.mesh_transport_bluetooth_le
        else -> R.string.mesh_transport_unknown
    }

    /**
     * A radio code turned into a sentence the operator can act on.
     *
     * The engine may append the platform's numeric code after a colon (for
     * example `BLE_ADVERTISE_FAILED:1`); that suffix is the useful part of a
     * bug report, so the screen shows the sentence and keeps the code visible.
     * An unknown code returns null and is printed verbatim — never swallowed.
     */
    fun radioErrorRes(error: String?): Int? = when (error?.substringBefore(':')?.trim()) {
        "BLUETOOTH_UNAVAILABLE" -> R.string.mesh_err_bluetooth_unavailable
        "BLUETOOTH_DISABLED" -> R.string.mesh_err_bluetooth_disabled
        "BLE_ADVERTISE_FAILED" -> R.string.mesh_err_ble_advertise_failed
        "BLE_ADVERTISE_UNAVAILABLE" -> R.string.mesh_err_ble_advertise_unavailable
        "BLE_SCAN_FAILED" -> R.string.mesh_err_ble_scan_failed
        "BLE_NO_IDENTITY" -> R.string.mesh_err_ble_no_identity
        "BLUETOOTH_ACCEPT_FAILED" -> R.string.mesh_err_bluetooth_accept_failed
        "BLUETOOTH_LISTEN_FAILED" -> R.string.mesh_err_bluetooth_listen_failed
        "WIFI_DIRECT_UNAVAILABLE" -> R.string.mesh_err_wifi_direct_unavailable
        "WIFI_DIRECT_DISABLED" -> R.string.mesh_err_wifi_direct_disabled
        "WIFI_DIRECT_DISCOVERY_FAILED" -> R.string.mesh_err_wifi_direct_discovery_failed
        else -> null
    }

    /** `lan, wifi_direct`, or "no measured path" when nothing was seen. */
    fun transportsText(transports: List<String>): String =
        if (transports.isEmpty()) "" else transports.sorted().joinToString(", ")

    /**
     * The heading: only a measured `alive` counts as connected, so a paired
     * server that never answered stays in the denominator.
     */
    fun summary(servers: List<MeshServerRow>): String =
        "${servers.count { it.state == "alive" }}/${servers.size}"

    /**
     * The share list in words. Empty is the engine's compatibility default:
     * every service the peer's scopes already allow.
     */
    fun sharesText(row: MeshServerRow): String =
        if (row.sharedServices.isEmpty()) {
            row.services.joinToString(", ") { it.id }.ifEmpty { "" }
        } else {
            row.sharedServices.joinToString(", ")
        }

    fun nearbyLabel(row: MeshNearbyRow): String =
        row.fingerprint?.takeIf { it.isNotEmpty() }
            ?: row.nodeIdPrefix?.takeIf { it.isNotEmpty() }
            ?: row.address.takeIf { it.isNotEmpty() }
            ?: row.key

    private fun objects(raw: Any?): List<Map<*, *>> =
        (raw as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }

    private fun strings(raw: Any?): List<String> =
        (raw as? List<*>).orEmpty().mapNotNull { it as? String }
}
