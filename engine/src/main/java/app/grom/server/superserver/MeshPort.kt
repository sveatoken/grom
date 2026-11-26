// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver

/**
 * The server network as the data plane sees it (D99, D100).
 *
 * The router owns authentication and scope checks; the mesh never sees a
 * principal. Exactly like [AiPort], the projection is split in two:
 *
 *   - [document] is the address-free roster served at `GET /v1/mesh` to a
 *     caller holding `mesh.read`. It never lists a nearby, unpaired device:
 *     that would publish the surrounding topology to whoever can read this
 *     device's view;
 *   - [call] is the scoped relay at `POST /v1/mesh/call`, and it only ever
 *     names a server this device has paired with.
 */
data class MeshAnswer(val status: Int, val payload: Map<String, Any?>)

interface MeshPort {

    /** `{enabled, advertise, scan, servers, localServices}`; never an address. */
    fun document(): Map<String, Any?>

    /** One measured probe of the catalogue, on demand; no scheduler (D74, D99). */
    fun refresh(): Map<String, Any?>

    /** Calls one service on a paired server, through this device's own key. */
    fun call(body: String, client: String): MeshAnswer

    /** The status block: counts only, no identities (D59). */
    fun summary(): Map<String, Any?> =
        linkedMapOf("enabled" to false, "servers" to 0, "nearby" to 0)

    companion object {
        val DISABLED: MeshPort = object : MeshPort {
            override fun document(): Map<String, Any?> = linkedMapOf(
                "enabled" to false,
                "advertise" to false,
                "scan" to false,
                "servers" to emptyList<Any?>(),
                "localServices" to emptyList<Any?>()
            )

            override fun refresh(): Map<String, Any?> =
                linkedMapOf("ok" to false, "code" to "MESH_DISABLED", "servers" to 0)

            override fun call(body: String, client: String): MeshAnswer = MeshAnswer(
                503,
                mapOf(
                    "ok" to false,
                    "error" to mapOf(
                        "code" to "MESH_DISABLED",
                        "message" to "the server network is not available in this build"
                    )
                )
            )
        }
    }
}
