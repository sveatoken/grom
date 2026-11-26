// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.cluster.NodeIdentity
import app.grom.server.cluster.PeerRecord
import app.grom.server.cluster.PeerScope

/**
 * Projections of the server network (D59, D99).
 *
 * Two audiences, two documents, and the difference is the point:
 *
 *   - [servers] is what `GET /v1/mesh` and the Binder share. It names each
 *     paired server, the fingerprint to compare, its measured state and the
 *     services it published. It never contains an endpoint: a caller who may
 *     read the roster must not learn where the other phones live.
 *   - [nearby] is the operator's discovery list. It *does* contain the
 *     discovered endpoint, because the operator has to be able to pair with it,
 *     and it is only ever returned over the signature-gated Binder.
 */
object MeshDocuments {

    fun servers(
        peers: List<PeerRecord>,
        catalogs: Map<String, RemoteCatalog>,
        discovered: List<MeshCandidate>,
        nowMs: Long
    ): List<Map<String, Any?>> = peers.sortedBy { it.label.lowercase() }.map { peer ->
        val catalog = catalogs[peer.nodeId]
        val transports = discovered
            .filter { it.nodeId == peer.nodeId }
            .map { it.transport.wireName }
            .distinct()
            .sorted()
        linkedMapOf<String, Any?>(
            "nodeId" to peer.nodeId,
            "label" to peer.label,
            "fingerprint" to peer.fingerprint(),
            "state" to peer.state(nowMs),
            "lastProbeMs" to peer.lastProbeMs,
            "inboundOnly" to peer.isInboundOnly(),
            "transports" to transports,
            "grantedByMe" to PeerScope.wireNames(peer.grantedByMe),
            "grantedByPeer" to PeerScope.wireNames(peer.grantedByPeer),
            "sharedServices" to peer.services.sorted(),
            "servicesListed" to (catalog?.listed == true),
            "catalogError" to catalog?.error,
            "services" to (catalog?.services ?: emptyList<RemoteService>()).map { service ->
                linkedMapOf<String, Any?>(
                    "id" to service.id,
                    "title" to service.title,
                    "capabilities" to service.capabilities.sorted(),
                    "scopedOps" to service.scopedOps.sorted()
                )
            },
            "ai" to linkedMapOf<String, Any?>(
                "enabled" to (catalog?.aiEnabled == true),
                "models" to (catalog?.aiModels ?: emptyList<String>())
            )
        )
    }

    /**
     * The operator's discovery list.
     *
     * [includeAddress] exists so a future read-only surface cannot accidentally
     * publish endpoints; today only the Binder asks for them.
     */
    fun nearby(candidates: List<MeshCandidate>, includeAddress: Boolean): List<Map<String, Any?>> =
        candidates.map { candidate ->
            linkedMapOf<String, Any?>(
                "key" to candidate.key,
                "nodeId" to candidate.nodeId,
                "nodeIdPrefix" to candidate.nodeIdPrefix,
                "label" to candidate.label,
                "fingerprint" to candidate.fingerprint(),
                "transport" to candidate.transport.wireName,
                "port" to candidate.port,
                "signedBeacon" to candidate.signedBeacon,
                "dataCapable" to candidate.isDataCapable(),
                "firstSeenMs" to candidate.firstSeenMs,
                "lastSeenMs" to candidate.lastSeenMs
            ).apply {
                if (includeAddress && candidate.address.isNotEmpty()) {
                    put("address", candidate.address)
                }
            }
        }

    /** Counts only: this is the block `/v1/status` may carry (D59). */
    fun summary(settings: MeshSettings, servers: Int, near: Int): Map<String, Any?> =
        linkedMapOf(
            "enabled" to settings.enabled,
            "advertise" to settings.advertise,
            "scan" to settings.scan,
            "servers" to servers,
            "nearby" to near
        )

    /** Sanity for a label read from a transport that could carry anything. */
    fun safeLabel(raw: String?): String? =
        raw?.let { NodeIdentity.normalizeLabel(it) }?.takeIf { it != NodeIdentity.DEFAULT_LABEL }
}
