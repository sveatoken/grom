// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.cluster.NodeIdentity
import app.grom.server.cluster.NodeIdentityStore
import app.grom.server.cluster.PeerAddress
import app.grom.server.cluster.PeerRecord
import app.grom.server.cluster.PeerScope
import app.grom.server.cluster.PeerStore
import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.mesh.net.LanBeaconNode
import app.grom.server.superserver.MeshAnswer
import app.grom.server.superserver.MeshPort
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The server network (D99-D102).
 *
 * It is not a second server and it is not a new protocol: every call it makes is
 * the data plane's own `/grom/v1/connect`, `/v1/plugins/{id}/rpc` or
 * `/v1/ai/…`, signed with this device's identity and authorized by the scopes
 * the peer already enforces (D100). What the mesh adds is the three things a
 * phone needs to find and use another phone:
 *
 *   1. discovery — a signed LAN beacon, BLE advertisements, Wi-Fi Direct and a
 *      bonded Bluetooth Classic link (D99, D102);
 *   2. a roster — paired servers, their measured state and *their* published
 *      services, refreshed on demand and never polled (D74);
 *   3. a scoped relay — `POST /v1/mesh/call`, so a local app can use a service
 *      that runs on another server without ever seeing its key or address.
 *
 * Discovery never creates trust. A candidate is a hint until the operator runs
 * the existing pairing handshake with the code shown on the other screen.
 *
 * Threading: [refresh] and [call] do network work and must not run on the main
 * thread; the Binder and the UI already dispatch them. [document] and
 * [statusJson] are pure snapshots.
 */
class MeshService(
    private val identityStore: NodeIdentityStore,
    private val peers: PeerStore,
    private val settingsStore: MeshSettingsStore,
    private val caller: MeshCaller,
    private val beacon: LanBeaconNode? = null,
    private val radios: List<MeshRadio> = emptyList(),
    private val localServices: () -> Set<String> = { emptySet() },
    private val localPort: () -> Int = { 0 },
    private val serverRunning: () -> Boolean = { true },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val audit: (String, String) -> Unit = { _, _ -> },
    private val logger: (String) -> Unit = {},
    private val random: SecureRandom = SecureRandom()
) : MeshPort {

    @Volatile
    private var settings: MeshSettings = MeshSettings()

    private val book = DiscoveryBook(
        clock = clock,
        selfNodeId = { identityOrNull()?.nodeId }
    )

    /** Fetched catalogues, memory only: the topology is not written down. */
    private val catalogs = ConcurrentHashMap<String, RemoteCatalog>()

    @Volatile
    private var running: Boolean = false

    @Volatile
    var lastError: String? = null
        private set

    // ---------------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        if (running) return
        running = true
        settings = settingsStore.load()
        lastError = settingsStore.lastError
        if (settings.enabled) startComponents() else stopComponents()
    }

    @Synchronized
    fun stop() {
        running = false
        stopComponents()
        book.clear()
    }

    fun settings(): MeshSettings = settings

    /** Applies a partial configuration and persists it. */
    @Synchronized
    fun configureJson(body: String): String {
        val raw = JsonParser.parseObjectOrNull(body)
            ?: throw ApiException(400, "BAD_MESH_CONFIG", "body must be a JSON object")
        val next = settings.apply(raw)
            ?: throw ApiException(400, "BAD_MESH_CONFIG", "enabled, advertise, scan and bluetooth are booleans")
        settingsStore.save(next)
        val droppedBluetooth = if (settings.bluetooth && !next.bluetooth) {
            book.retain { !it.transport.isBluetooth }
        } else {
            0
        }
        settings = next
        if (running) {
            stopComponents()
            if (next.enabled) startComponents()
        }
        audit(
            "mesh.configure",
            "enabled=${next.enabled} advertise=${next.advertise} scan=${next.scan} " +
                "bluetooth=${next.bluetooth} droppedBluetooth=$droppedBluetooth"
        )
        return statusJson()
    }

    /**
     * One bounded scan now: asks each radio for a fresh pass and sends this
     * device's beacon immediately, so two operators who tap Scan at the same
     * time see each other without waiting for the interval.
     */
    fun scanJson(): String {
        if (!settings.enabled) {
            throw ApiException(409, "MESH_DISABLED", "turn the server network on first")
        }
        for (radio in activeRadios()) {
            runCatching { radio.scanNow() }
        }
        val line = beaconLine()
        if (line != null) runCatching { beacon?.send(line) }
        return statusJson()
    }

    // ------------------------------------------------------------------ roster

    override fun refresh(): Map<String, Any?> {
        if (!settings.enabled) {
            return linkedMapOf(
                "ok" to false,
                "code" to "MESH_DISABLED",
                "total" to 0,
                "listed" to 0
            )
        }
        val seed = identityStore.seed()
            ?: return linkedMapOf(
                "ok" to false,
                "code" to "IDENTITY_CORRUPT",
                "servers" to 0,
                "listed" to 0
            )
        var listed = 0
        var failed = 0
        for (peer in peers.list().take(MAX_REFRESH)) {
            if (peer.isInboundOnly()) continue
            val catalog = caller.fetchCatalog(peer, seed)
            catalogs[peer.nodeId] = catalog
            if (catalog.error == null) listed += 1 else failed += 1
        }
        audit("mesh.refresh", "listed=$listed failed=$failed")
        return linkedMapOf(
            "ok" to true,
            "total" to peers.list().size,
            "listed" to listed,
            "failed" to failed
        ) + document()
    }

    override fun document(): Map<String, Any?> {
        val now = clock()
        return linkedMapOf(
            "ok" to true,
            "enabled" to settings.enabled,
            "advertise" to settings.advertise,
            "scan" to settings.scan,
            "bluetooth" to settings.bluetooth,
            "generatedAtMs" to now,
            "local" to linkedMapOf<String, Any?>(
                "nodeId" to identityOrNull()?.nodeId,
                "label" to identityOrNull()?.label,
                "services" to localServices().sorted()
            ),
            "servers" to MeshDocuments.servers(peers.list(), catalogs, book.list(), now)
        )
    }

    override fun summary(): Map<String, Any?> =
        MeshDocuments.summary(settings, peers.list().size, book.size())

    /** The operator view: the roster plus the nearby list, returned only over Binder. */
    fun statusJson(): String {
        val now = clock()
        return Json.encode(
            linkedMapOf<String, Any?>(
                "ok" to true,
                "enabled" to settings.enabled,
                "advertise" to settings.advertise,
                "scan" to settings.scan,
                "bluetooth" to settings.bluetooth,
                // Measured, never assumed: null when the phone has no adapter,
                // false when the operator has the system radio off (which Grom
                // may not change itself), true when it is on right now.
                "bluetoothOn" to bluetoothOn(),
                "identity" to identityOrNull()?.toJson(),
                "dataPlanePort" to localPort(),
                "serverRunning" to serverRunning(),
                "radios" to radios.map { radio ->
                    linkedMapOf<String, Any?>(
                        "transport" to radio.transport.wireName,
                        // Whether *this* switch lets the radio run. A radio that
                        // is left out is not an error and is not reported as one.
                        "inUse" to radioInUse(radio),
                        "available" to radio.isAvailable(),
                        "platformOn" to radio.platformEnabled(),
                        "missingPermissions" to radio.missingPermissions(),
                        "error" to radio.lastError
                    )
                },
                "nearby" to MeshDocuments.nearby(book.list(), includeAddress = true),
                "servers" to MeshDocuments.servers(peers.list(), catalogs, book.list(), now),
                "localServices" to localServices().sorted(),
                "lastError" to lastError
            )
        )
    }

    // ------------------------------------------------------------------- call

    override fun call(body: String, client: String): MeshAnswer {
        val raw = JsonParser.parseObjectOrNull(body)
            ?: return error(400, "MESH_BAD_REQUEST", "body must be a JSON object")
        val server = (raw["server"] as? String)?.trim() ?: ""
        if (!NodeIdentity.NODE_ID_PATTERN.matches(server)) {
            return error(400, "MESH_UNKNOWN_SERVER", "server must be a node id")
        }
        val service = (raw["service"] as? String)?.trim() ?: ""
        val op = (raw["op"] as? String)?.trim() ?: ""
        if (service.isEmpty() || op.isEmpty()) {
            return error(400, "MESH_BAD_REQUEST", "service and op are required")
        }
        val args = when (val value = raw["args"]) {
            null -> emptyMap<String, Any?>()
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                value as Map<String, Any?>
            }

            else -> return error(400, "MESH_BAD_REQUEST", "args must be an object")
        }
        val aiBody = when (val value = raw["body"]) {
            null -> null
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                value as Map<String, Any?>
            }

            else -> return error(400, "MESH_BAD_REQUEST", "body must be an object")
        }

        val peer = peers.find(server)
            ?: return error(404, "MESH_UNKNOWN_SERVER", "this server is not paired with that node")
        val seed = identityStore.seed()
            ?: return error(409, "IDENTITY_CORRUPT", "the device identity cannot be used")

        audit("mesh.call", "server=$server service=$service op=$op")
        return when (val reply = caller.call(peer, catalogs[server], seed, service, op, args, aiBody)) {
            is MeshCaller.Reply.Failed -> error(reply.status, reply.code, reply.message)
            is MeshCaller.Reply.Json -> MeshAnswer(
                reply.status,
                linkedMapOf(
                    "ok" to (reply.status in 200..299),
                    "server" to server,
                    "service" to service,
                    "op" to op,
                    "status" to reply.status,
                    "result" to reply.payload
                )
            )
        }
    }

    // --------------------------------------------------------------- internals

    private fun identityOrNull(): NodeIdentity? = runCatching { identityStore.identity() }.getOrNull()

    /** Whether the current switches let [radio] run (D109). */
    private fun radioInUse(radio: MeshRadio): Boolean =
        settings.bluetooth || !radio.transport.isBluetooth

    private fun activeRadios(): List<MeshRadio> = radios.filter { radioInUse(it) }

    /**
     * The system Bluetooth switch, measured from whichever Bluetooth radio can
     * read it; `null` on a phone without an adapter. Never cached: the operator
     * can turn the radio off while the screen is open.
     */
    private fun bluetoothOn(): Boolean? {
        for (radio in radios) {
            if (!radio.transport.isBluetooth) continue
            val measured = radio.platformEnabled() ?: continue
            return measured
        }
        return null
    }

    private fun startComponents() {
        lastError = null
        if (beacon != null && (settings.advertise || settings.scan)) {
            val started = beacon.start { host, line -> onBeacon(host, line) }
            if (!started) lastError = beacon.lastError
            if (started && settings.advertise) {
                beacon.startBeaconing(BEACON_INTERVAL_MS) { beaconLine() }
            }
        }
        if (settings.scan) {
            for (radio in activeRadios()) {
                val started = runCatching { radio.start { candidate -> offer(candidate) } }
                    .getOrDefault(false)
                if (!started && radio.lastError != null) lastError = radio.lastError
            }
        }
    }

    private fun stopComponents() {
        runCatching { beacon?.stop() }
        for (radio in radios) runCatching { radio.stop() }
    }

    /** One signed line, or null when advertising is off or nothing to announce. */
    private fun beaconLine(): String? {
        if (!settings.enabled || !settings.advertise) return null
        val port = localPort()
        if (port !in MeshLimits.MIN_PORT..MeshLimits.MAX_PORT) return null
        if (!serverRunning()) return null
        val seed = identityStore.seed() ?: return null
        val nonceBytes = ByteArray(12)
        random.nextBytes(nonceBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        return MeshBeacon.encode(seed, port, clock() / 1000, nonce)
    }

    private fun onBeacon(host: String, line: String) {
        // The address family is deliberately IPv4-only: `PeerAddress` refuses
        // bracketed IPv6 literals, and inventing a second address syntax here
        // would make the stored endpoint ambiguous.
        if (host.contains(':')) return
        val decoded = MeshBeacon.decode(line) ?: return
        val address = PeerAddress.normalize("http://$host:${decoded.port}") ?: return
        val now = clock()
        offer(
            MeshCandidate(
                key = "id:${decoded.nodeId}",
                nodeId = decoded.nodeId,
                nodeIdPrefix = decoded.nodeId.substring(0, 8),
                publicKey = decoded.publicKey,
                label = null,
                transport = MeshTransport.LAN,
                address = address,
                port = decoded.port,
                signedBeacon = true,
                firstSeenMs = now,
                lastSeenMs = now
            )
        )
    }

    /**
     * Normalises an adapter's sighting: the key is derived here, never accepted
     * from a radio, so two adapters cannot disagree about what deduplicates.
     */
    private fun offer(raw: MeshCandidate) {
        val key = when {
            raw.nodeId != null -> "id:${raw.nodeId}"
            raw.address.isNotEmpty() -> "addr:${raw.transport.wireName}:${raw.address}"
            raw.nodeIdPrefix != null -> "prefix:${raw.transport.wireName}:${raw.nodeIdPrefix}"
            else -> return
        }
        val candidate = raw.copy(key = key)
        val first = book.size() == 0 || book.find(key) == null
        if (book.offer(candidate) && first) {
            audit("mesh.seen", "transport=${candidate.transport.wireName}")
        }
    }

    private fun error(status: Int, code: String, message: String): MeshAnswer =
        MeshAnswer(
            status,
            linkedMapOf(
                "ok" to false,
                "error" to linkedMapOf("code" to code, "message" to message)
            )
        )

    companion object {
        /** A beacon every 30 s while advertising is on; negligible on a phone. */
        const val BEACON_INTERVAL_MS: Long = 30_000

        /** The same bound the fleet refresh uses (D74). */
        const val MAX_REFRESH: Int = 16
    }
}
