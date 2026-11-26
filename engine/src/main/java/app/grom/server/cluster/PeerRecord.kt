// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.superserver.auth.Ed25519
import java.util.Base64

/**
 * What one connected client may do on this device (D73, D92).
 *
 * Scopes are granted explicitly and never implicitly: a peer that was granted
 * `status.read` cannot read the configuration just because it knows the URL.
 * Unknown scope names are refused rather than ignored, so a typo cannot quietly
 * widen a grant.
 *
 * `services.read` and `services.call` exist for applications that use Grom as a
 * local server (D92). They are deliberately narrower than an operator key: a
 * caller with `services.call` may only invoke the operations a plugin lists in
 * [app.grom.server.superserver.Plugin.scopedOps], which never include status,
 * credentials, configuration or anything that moves funds. A connected app is
 * never an operator.
 *
 * `ai.read` and `ai.chat` are the AI gateway grants (D94). They are separate
 * from `services.call` because an AI request spends the operator's credit at a
 * third party: reading the catalogue and paying for a completion are two
 * decisions, so they are two scopes. A provider API key is never part of any
 * grant — no scope lets a caller see or copy one.
 */
enum class PeerScope(val wireName: String) {
    STATUS_READ("status.read"),
    CONFIG_READ("config.read"),

    /** Enumerate services, their descriptions and their capabilities. */
    SERVICES_READ("services.read"),

    /** Invoke the operations a service explicitly exposes to scoped clients. */
    SERVICES_CALL("services.call"),

    /** See the AI catalogue and which providers are usable (D94). */
    AI_READ("ai.read"),

    /** Send chat requests through the operator's stored provider keys (D94). */
    AI_CHAT("ai.chat"),

    /** See this device's server network: peers, measured state and services (D99). */
    MESH_READ("mesh.read"),

    /**
     * Ask this device to call a service on another server it is paired with
     * (D100). It is a relay grant, deliberately separate from `services.call`:
     * using a service locally and borrowing this device's federation are two
     * decisions.
     */
    MESH_CALL("mesh.call");

    companion object {

        /**
         * The scopes a *pairing* may carry to another device (D104).
         *
         * `mesh.read` and `mesh.call` are grants a local client holds on this
         * device: handing them to a remote peer would name a capability the
         * peer can never use meaningfully, and the approval dialog would then
         * show a permission the other operator cannot interpret. Both the
         * joining request and the accepting endpoint are held to this set.
         */
        val PEER_GRANTABLE: Set<PeerScope> = setOf(
            STATUS_READ,
            CONFIG_READ,
            SERVICES_READ,
            SERVICES_CALL,
            AI_READ,
            AI_CHAT
        )

        fun fromWire(raw: String?): PeerScope? =
            entries.firstOrNull { it.wireName == raw?.trim() }

        /** Null when any name is unknown; an empty list is a valid empty set. */
        fun fromJson(raw: Any?): Set<PeerScope>? {
            val list = raw as? List<*> ?: return null
            val out = LinkedHashSet<PeerScope>(list.size)
            for (item in list) {
                val scope = fromWire(item as? String ?: return null) ?: return null
                out.add(scope)
            }
            return out
        }

        fun wireNames(scopes: Set<PeerScope>): List<String> =
            scopes.map { it.wireName }.sorted()
    }
}

/**
 * A device this one has paired with (D70, D74).
 *
 * [address] is the operator's entered endpoint and is deliberately absent from
 * every public projection: `/v1/status` and the config export name the device,
 * never where it lives (D59). The Binder may return it because the caller is
 * already signature-gated and the operator typed it.
 *
 * An empty [address] is a *one-way grant* (D77): the operator trusted a key that
 * only ever calls in, such as a hub on their VPS. Such a record is never
 * dialled — there is nowhere to dial — and its state stays `unknown` until the
 * other side is seen by other means.
 *
 * Liveness is measured, never derived: [lastProbeOk] starts as `null` (never
 * checked) and only a completed probe sets it. Pairing alone proves nothing
 * about reachability, so it can never make a lamp green.
 */
data class PeerRecord(
    val nodeId: String,
    val publicKey: String,
    val label: String,
    val address: String = "",
    /** What *this* device allows the peer to read. */
    val grantedByMe: Set<PeerScope>,
    /** What the peer allows this device to read. */
    val grantedByPeer: Set<PeerScope>,
    /**
     * The services the operator opened to this peer, by plugin id (D101).
     *
     * Empty is the established meaning: every operation this peer's scopes
     * already allow. A non-empty set is an explicit allow-list, so an operator
     * who wants to share only the agent's key/value store can say so without
     * revoking the peer's scopes.
     */
    val services: Set<String> = emptySet(),
    val pairedAtMs: Long,
    val lastProbeMs: Long = 0L,
    val lastProbeOk: Boolean? = null,
    val lastError: String? = null
) {

    /**
     * `unknown` = never measured, `alive` = a successful probe within
     * [ALIVE_AFTER_MS], `stale` = an older success, `unreachable` = the last
     * probe failed.
     */
    fun state(nowMs: Long): String = when {
        lastProbeOk == null -> STATE_UNKNOWN
        !lastProbeOk -> STATE_UNREACHABLE
        nowMs - lastProbeMs <= ALIVE_AFTER_MS -> STATE_ALIVE
        else -> STATE_STALE
    }

    fun fingerprint(): String = NodeIdentity.formatFingerprint(nodeId)

    /**
     * True when this record is a one-way grant: the key may call in, but this
     * device has no address to call out to (D77). A probe must never open a
     * connection for such a record.
     */
    fun isInboundOnly(): Boolean = address.isEmpty()

    /** True when the operator's allow-list opens [serviceId] to this peer. */
    fun mayUseService(serviceId: String): Boolean =
        services.isEmpty() || services.contains(serviceId)

    fun toJson(nowMs: Long, includeAddress: Boolean = false): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "nodeId" to nodeId,
            "publicKey" to publicKey,
            "label" to label,
            "fingerprint" to fingerprint(),
            "grantedByMe" to PeerScope.wireNames(grantedByMe),
            "grantedByPeer" to PeerScope.wireNames(grantedByPeer),
            "services" to services.sorted(),
            "pairedAtMs" to pairedAtMs,
            "lastProbeMs" to lastProbeMs,
            "lastProbeOk" to lastProbeOk,
            "lastError" to lastError,
            "state" to state(nowMs)
        ).apply {
            // The address is device state, not status: only the operator-facing
            // Binder projection carries it (D59). A one-way grant has none, so
            // the field is omitted rather than sent empty.
            if (includeAddress && address.isNotEmpty()) put("address", address)
        }

    companion object {

        const val ALIVE_AFTER_MS: Long = 90_000

        const val STATE_UNKNOWN: String = "unknown"
        const val STATE_ALIVE: String = "alive"
        const val STATE_STALE: String = "stale"
        const val STATE_UNREACHABLE: String = "unreachable"

        /** Returns a record, or null when any field fails validation. */
        fun fromJson(raw: Map<*, *>): PeerRecord? {
            val nodeId = raw["nodeId"] as? String ?: return null
            val publicKey = raw["publicKey"] as? String ?: return null
            val label = NodeIdentity.normalizeLabel(raw["label"] as? String)
            // Absent means a one-way grant (D77); a present value must already be
            // canonical, exactly as the pairing path stores it.
            val address = (raw["address"] as? String).orEmpty()
            val byMe = PeerScope.fromJson(raw["grantedByMe"]) ?: return null
            val byPeer = PeerScope.fromJson(raw["grantedByPeer"]) ?: return null
            val services = serviceIds(raw["services"]) ?: return null
            val pairedAt = (raw["pairedAtMs"] as? Number)?.toLong() ?: 0L
            val lastProbe = (raw["lastProbeMs"] as? Number)?.toLong() ?: 0L
            val lastOk = raw["lastProbeOk"] as? Boolean
            val error = (raw["lastError"] as? String)?.takeIf { it.isNotBlank() }
            return NodeIdentityRules.validatePeer(
                nodeId = nodeId,
                publicKey = publicKey,
                label = label,
                address = address,
                byMe = byMe,
                byPeer = byPeer,
                services = services,
                pairedAt = pairedAt,
                lastProbe = lastProbe,
                lastOk = lastOk,
                error = error
            )
        }
    }
}

/**
 * Service allow-list parsing shared by the record reload (D101).
 *
 * Absent means the compatibility default (empty = every scope-allowed service);
 * a present list must contain only well-formed plugin ids and is bounded, so a
 * corrupt file cannot open an unbounded set.
 */
internal fun serviceIds(raw: Any?): Set<String>? {
    if (raw == null) return emptySet()
    val list = raw as? List<*> ?: return null
    if (list.size > NodeIdentityRules.MAX_SHARED_SERVICES) return null
    val out = LinkedHashSet<String>(list.size)
    for (item in list) {
        val id = item as? String ?: return null
        if (!NodeIdentityRules.SERVICE_ID_PATTERN.matches(id)) return null
        out.add(id)
    }
    return out
}

/** Validation shared by the pairing path and the store reload. */
object NodeIdentityRules {

    val SERVICE_ID_PATTERN: Regex = Regex("^[a-z][a-z0-9-]{1,31}$")

    const val MAX_SHARED_SERVICES: Int = 32

    fun validatePeer(
        nodeId: String,
        publicKey: String,
        label: String,
        address: String,
        byMe: Set<PeerScope>,
        byPeer: Set<PeerScope>,
        services: Set<String> = emptySet(),
        pairedAt: Long,
        lastProbe: Long,
        lastOk: Boolean?,
        error: String?
    ): PeerRecord? {
        if (!NodeIdentity.NODE_ID_PATTERN.matches(nodeId)) return null
        val keyBytes = runCatching { Base64.getDecoder().decode(publicKey) }.getOrNull()
            ?: return null
        if (keyBytes.size != Ed25519.PUBLIC_KEY_BYTES) return null
        if (Ed25519.keyId(keyBytes) != nodeId) return null
        if (label.isEmpty() || label.length > NodeIdentity.MAX_LABEL_CHARS) return null
        if (address.isNotEmpty() && PeerAddress.normalize(address) != address) return null
        if (byMe.isEmpty()) return null
        if (services.size > MAX_SHARED_SERVICES) return null
        if (services.any { !SERVICE_ID_PATTERN.matches(it) }) return null
        return PeerRecord(
            nodeId = nodeId,
            publicKey = publicKey,
            label = label,
            address = address,
            grantedByMe = byMe,
            grantedByPeer = byPeer,
            services = services,
            pairedAtMs = pairedAt,
            lastProbeMs = lastProbe,
            lastProbeOk = lastOk,
            lastError = error
        )
    }
}

/**
 * A peer endpoint the operator typed or a discovery transport supplied:
 * `http(s)://host:port`, or `bt://<MAC>` for a bonded Bluetooth Classic link
 * (D102).
 *
 * The canonical form is what is stored and what the client opens, so parsing
 * can never be ambiguous. Userinfo, paths, queries, fragments and whitespace are
 * refused: a paired device is a host, not a URL that can be pointed anywhere.
 * A Bluetooth address is a *carrier*, not a location: `bt://` names the bonded
 * peer whose RFCOMM stream carries the same HTTP/JSON requests, so nothing
 * about the request, signature or scope changes.
 */
object PeerAddress {

    const val MAX_CHARS: Int = 300

    private val HOST_PATTERN = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
    private val BLUETOOTH_MAC = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    /** Returns the canonical `scheme://host:port`, or null when it is invalid. */
    fun normalize(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_CHARS) return null
        if (text.any { it.isWhitespace() || it.isISOControl() }) return null
        if (text.contains('@') || text.contains('?') || text.contains('#')) return null

        val separator = text.indexOf("://")
        val scheme: String
        val rest: String
        if (separator >= 0) {
            scheme = text.substring(0, separator).lowercase()
            if (!SCHEME_PATTERN.matches(scheme)) return null
            if (scheme != "http" && scheme != "https" && scheme != "bt") return null
            rest = text.substring(separator + 3)
        } else {
            scheme = "http"
            rest = text
        }
        if (rest.isEmpty() || rest.contains('/')) return null

        // A Bluetooth carrier is a MAC, never a host:port. It is canonicalised
        // to upper case so the same bonded device always has one spelling.
        if (scheme == "bt") {
            val mac = rest.uppercase()
            return if (BLUETOOTH_MAC.matches(mac)) "bt://$mac" else null
        }

        val colon = rest.lastIndexOf(':')
        if (colon <= 0 || colon == rest.lastIndex) return null
        val host = rest.substring(0, colon).lowercase()
        val portText = rest.substring(colon + 1)
        if (!HOST_PATTERN.matches(host)) return null
        if (host.contains("..") || host.startsWith('.') || host.endsWith('.')) return null
        if (!portText.all { it.isDigit() }) return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return "$scheme://$host:$port"
    }
}
