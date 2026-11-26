// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.http.JsonParser

/**
 * What another server says it serves, read from its own connect document (D91,
 * D100).
 *
 * The catalogue is a *cached measurement*, never configuration: it exists only
 * after this device actually fetched the peer's `/grom/v1/connect` with a
 * credential the peer accepted. It lives in memory only — the topology of the
 * network is not written to disk, so a stolen phone cannot replay yesterday's
 * roster.
 *
 * `listed = false` is the honest answer when the peer did not grant
 * `services.read`: the peer answered, but it did not enumerate anything, and
 * this device must not pretend the list is empty because it is empty.
 */
data class RemoteService(
    val id: String,
    val title: String,
    val description: String,
    val capabilities: Set<String>,
    val scopedOps: Set<String>
) {

    fun callable(op: String): Boolean = scopedOps.contains(op)
}

data class RemoteCatalog(
    val nodeId: String,
    val apiVersion: Int,
    val gromVersion: String,
    val listed: Boolean,
    val services: List<RemoteService>,
    val aiEnabled: Boolean,
    val aiModels: List<String>,
    val fetchedAtMs: Long,
    val error: String?
) {

    fun service(id: String): RemoteService? = services.firstOrNull { it.id == id }

    /** The ops the AI gateway publishes, with the scope each one needs. */
    fun aiOp(op: String): String? = when (op) {
        "models" -> "ai.read"
        "chat", "messages" -> "ai.chat"
        else -> null
    }

    companion object {

        /** A failed fetch that still needs a place in the roster. */
        fun failed(nodeId: String, nowMs: Long, code: String): RemoteCatalog = RemoteCatalog(
            nodeId = nodeId,
            apiVersion = 0,
            gromVersion = "",
            listed = false,
            services = emptyList(),
            aiEnabled = false,
            aiModels = emptyList(),
            fetchedAtMs = nowMs,
            error = code
        )
    }
}

/**
 * Parsing rules for a connect document (D91) as seen by the mesh.
 *
 * Nothing here trusts the peer beyond the shape: ids must be plugin ids, ops are
 * bounded strings, and a document that does not parse is a failed measurement
 * with a stable code, not a half-filled catalogue.
 */
object MeshCatalog {

    /** Hard caps, mirrored by the router's own limits. */
    const val MAX_SERVICES: Int = 64
    const val MAX_OPS: Int = 64
    const val MAX_ID_CHARS: Int = 32
    const val MAX_OP_CHARS: Int = 64

    private val SERVICE_ID = Regex("^[a-z][a-z0-9-]{1,31}$")
    private val OP_ID = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    fun parse(nodeId: String, body: String, nowMs: Long): RemoteCatalog {
        val root = JsonParser.parseObjectOrNull(body)
            ?: return RemoteCatalog.failed(nodeId, nowMs, "MESH_BAD_RESPONSE")

        val api = (root["api"] as? Number)?.toInt() ?: 0
        val grom = (root["grom"] as? String).orEmpty()
        val servicesBlock = root["services"] as? Map<*, *>
        val listed = servicesBlock?.get("listed") as? Boolean ?: false

        val services = ArrayList<RemoteService>()
        if (listed) {
            val items = servicesBlock?.get("items") as? List<*> ?: emptyList<Any?>()
            if (items.size > MAX_SERVICES) {
                return RemoteCatalog.failed(nodeId, nowMs, "MESH_BAD_RESPONSE")
            }
            for (item in items) {
                val service = parseService(item as? Map<*, *> ?: continue) ?: continue
                services.add(service)
            }
        }

        val ai = root["ai"] as? Map<*, *>
        val aiEnabled = ai?.get("enabled") as? Boolean ?: false
        val aiModels = if (ai?.get("listed") as? Boolean == true) {
            val items = ai["items"] as? List<*> ?: emptyList<Any?>()
            items.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
                .filter { it.length in 1..128 }
                .take(128)
        } else {
            emptyList()
        }

        // `principal` names the *caller* (this device), not the server, so it
        // is not an identity check. The peer's identity is proven by the signed
        // `/v1/status` probe, whose fleet block must name the paired node id
        // (`PeerClient` refuses `PEER_IDENTITY_CHANGED`), and by this device
        // verifying the signature of every answer.

        return RemoteCatalog(
            nodeId = nodeId,
            apiVersion = api,
            gromVersion = grom,
            listed = listed,
            services = services,
            aiEnabled = aiEnabled,
            aiModels = aiModels,
            fetchedAtMs = nowMs,
            error = null
        )
    }

    private fun parseService(raw: Map<*, *>): RemoteService? {
        val id = raw["id"] as? String ?: return null
        if (!SERVICE_ID.matches(id)) return null
        val title = (raw["title"] as? String).orEmpty().take(64)
        val description = (raw["description"] as? String).orEmpty().take(256)
        val capabilities = strings(raw["capabilities"]).toSet()
        val scopedOps = strings(raw["scopedOps"]).toSet()
        return RemoteService(
            id = id,
            title = title,
            description = description,
            capabilities = capabilities,
            scopedOps = scopedOps
        )
    }

    private fun strings(raw: Any?): List<String> {
        val list = raw as? List<*> ?: return emptyList()
        if (list.size > MAX_OPS) return emptyList()
        val out = ArrayList<String>(list.size)
        for (item in list) {
            val value = item as? String ?: continue
            if (value.length > MAX_OP_CHARS || !OP_ID.matches(value)) continue
            out.add(value)
        }
        return out
    }
}
