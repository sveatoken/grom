// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.cluster.NodeIdentityStore
import app.grom.server.cluster.PeerRecord
import app.grom.server.cluster.PeerScope
import app.grom.server.cluster.PeerStore
import app.grom.server.core.BindResolution
import app.grom.server.core.BindPolicy
import app.grom.server.core.BindMode
import app.grom.server.store.SecretBox
import app.grom.server.superserver.AuthMode
import app.grom.server.superserver.EventBus
import app.grom.server.superserver.Plugin
import app.grom.server.superserver.PluginRegistry
import app.grom.server.superserver.PluginResult
import app.grom.server.superserver.SuperAuth
import app.grom.server.superserver.SuperRouter
import app.grom.server.superserver.SuperServer
import app.grom.server.superserver.SuperServerConfig
import app.grom.server.superserver.auth.LoginLimiter
import app.grom.server.superserver.auth.RateLimiter
import app.grom.server.superserver.auth.RequestSigner
import app.grom.server.superserver.auth.SessionStore
import app.grom.server.superserver.net.HttpJsonClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * Two and three real Grom servers over real loopback sockets (D99-D102).
 *
 * There is no mock in the path that matters: each node runs the actual
 * [SuperServer] listener, authenticates the other with [RequestSigner], applies
 * its own scopes and shares, and the caller uses the same [MeshCaller] the
 * phone uses. The test therefore fails if a path, a header, a signature, a
 * scope or a service allow-list is wrong.
 */
class MeshIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val servers = ArrayList<SuperServer>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        servers.clear()
    }

    private class Box : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext

        override fun open(sealed: String): String? = sealed.removePrefix("sealed:")
            .takeIf { sealed.startsWith("sealed:") }
    }

    /** A service that publishes one callable op and nothing else. */
    private class EchoPlugin : Plugin {
        override val id: String = "echo"
        override val title: String = "Echo"
        override val description: String = "Echoes and counts"

        override fun capabilities(): Set<String> = setOf("ping")

        override fun scopedOps(): Set<String> = setOf("ping")

        override fun status(): Map<String, Any?> = mapOf("ready" to true)

        override fun rpc(op: String, args: Map<String, Any?>): PluginResult {
            if (op != "ping") return PluginResult.Failure(400, "UNKNOWN_OP", "unknown op")
            return PluginResult.Ok(mapOf("pong" to true, "args" to args))
        }
    }

    /** One real node: identity, peer store and a listening data plane. */
    private inner class Node(val label: String) {
        val home = File(temp.root, label).apply { mkdirs() }
        val identityStore = NodeIdentityStore(File(home, NodeIdentityStore.FILE_NAME), Box())
        val peers = PeerStore(File(home, PeerStore.FILE_NAME))
        val identity = identityStore.loadOrCreate(label)
        val seed = identityStore.seed()!!
        lateinit var server: SuperServer

        fun start() {
            val config = SuperServerConfig(
                enabled = true,
                bind = BindMode.LOOPBACK,
                port = 0,
                auth = AuthMode.KEY
            )
            val signer = RequestSigner(
                keyLookup = { id ->
                    peers.list().firstOrNull { it.nodeId == id }
                        ?.let { Base64.getDecoder().decode(it.publicKey) }
                }
            )
            val auth = SuperAuth(
                config = config,
                passwords = null,
                sessions = SessionStore(),
                requestLimiter = RateLimiter(),
                loginLimiter = LoginLimiter(),
                signer = signer,
                principalOf = { keyId ->
                    val peer = peers.find(keyId)
                    if (peer == null) {
                        SuperAuth.Principal.Operator
                    } else {
                        SuperAuth.Principal.Peer(peer.nodeId, peer.grantedByMe, peer.services)
                    }
                }
            )
            server = SuperServer(
                config = config,
                bind = BindResolution.Bound(BindPolicy.loopbackAddress(), "127.0.0.1"),
                router = SuperRouter(
                    config = config,
                    auth = auth,
                    plugins = PluginRegistry(listOf(EchoPlugin())),
                    events = EventBus(),
                    uptimeMs = { 1L },
                    serverStatus = { mapOf("fleet" to mapOf("nodeId" to identity.nodeId)) }
                )
            )
            server.start()
            servers.add(server)
        }

        fun address(): String = "http://127.0.0.1:${server.boundPort}"

        /** How this node sees [other]: what it grants, and what it was granted. */
        fun record(
            other: Node,
            grantedByMe: Set<PeerScope>,
            grantedByPeer: Set<PeerScope>,
            services: Set<String> = emptySet()
        ): PeerRecord = PeerRecord(
            nodeId = other.identity.nodeId,
            publicKey = other.identity.publicKey,
            label = other.label,
            address = other.address(),
            grantedByMe = grantedByMe,
            grantedByPeer = grantedByPeer,
            services = services,
            pairedAtMs = 0L
        )
    }

    private fun node(label: String): Node = Node(label).also { it.start() }

    private fun meshFor(node: Node, localServices: Set<String> = setOf("echo")): MeshService {
        val settings = MeshSettingsStore(File(node.home, MeshSettings.FILE_NAME))
        settings.save(MeshSettings(enabled = true))
        return MeshService(
            identityStore = node.identityStore,
            peers = node.peers,
            settingsStore = settings,
            caller = MeshCaller(transport = HttpJsonClient(), timeoutMs = 5_000),
            localServices = { localServices }
        ).also { it.start() }
    }

    @Test
    fun `one server calls another with a signature and a scope`() {
        val a = node("A")
        val b = node("B")

        b.peers.add(
            b.record(
                a,
                grantedByMe = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
                // A pairing grants both directions the same scopes, so B's view
                // of what A granted it must match what A stored.
                grantedByPeer = setOf(
                    PeerScope.STATUS_READ,
                    PeerScope.SERVICES_READ,
                    PeerScope.SERVICES_CALL
                )
            )
        )
        a.peers.add(
            a.record(
                b,
                grantedByMe = setOf(
                    PeerScope.STATUS_READ,
                    PeerScope.SERVICES_READ,
                    PeerScope.SERVICES_CALL
                ),
                grantedByPeer = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL)
            )
        )

        val mesh = meshFor(a)
        val refresh = mesh.refresh()
        assertEquals(1, refresh["total"])
        assertEquals(1, refresh["listed"])

        val document = mesh.document()
        val roster = document["servers"] as List<*>
        assertEquals(1, roster.size)
        val encoded = app.grom.server.http.Json.encode(document)
        assertFalse(encoded.contains(b.address()))
        assertFalse(encoded.contains("127.0.0.1"))
        val server = roster.first() as Map<*, *>
        assertEquals(b.identity.nodeId, server["nodeId"])
        val services = server["services"] as List<*>
        assertEquals("echo", (services.first() as Map<*, *>)["id"])

        val answer = mesh.call(
            """{"server":"${b.identity.nodeId}","service":"echo","op":"ping","args":{"x":1}}""",
            "test"
        )
        assertEquals(200, answer.status)
        assertEquals(true, answer.payload["ok"])
        // `result` is the peer's whole answer document, exactly as S8 describes.
        val remote = answer.payload["result"] as Map<*, *>
        assertEquals(true, (remote["result"] as Map<*, *>)["pong"])

        // The same trust works in the other direction.
        val reverseCaller = MeshCaller(transport = HttpJsonClient(), timeoutMs = 5_000)
        val recordOnB = b.peers.find(a.identity.nodeId)!!
        val catalog = reverseCaller.fetchCatalog(recordOnB, b.seed)
        assertEquals(null, catalog.error)
        val reverse = reverseCaller.call(recordOnB, catalog, b.seed, "echo", "ping")
        assertTrue(reverse is MeshCaller.Reply.Json)
        assertEquals(200, (reverse as MeshCaller.Reply.Json).status)
    }

    @Test
    fun `an operation outside the peer's published set is refused`() {
        val a = node("A")
        val b = node("B")
        b.peers.add(
            b.record(
                a,
                grantedByMe = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
                grantedByPeer = setOf(PeerScope.STATUS_READ)
            )
        )
        a.peers.add(
            a.record(
                b,
                grantedByMe = setOf(PeerScope.STATUS_READ),
                grantedByPeer = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL)
            )
        )
        val mesh = meshFor(a)
        mesh.refresh()
        val answer = mesh.call(
            """{"server":"${b.identity.nodeId}","service":"echo","op":"status"}""",
            "test"
        )
        assertEquals(403, answer.status)
        val error = answer.payload["error"] as Map<*, *>
        assertEquals("MESH_OP_NOT_ALLOWED", error["code"])
    }

    @Test
    fun `revoking the key stops the next call`() {
        val a = node("A")
        val b = node("B")
        val fromA = a.record(
            b,
            grantedByMe = setOf(PeerScope.STATUS_READ),
            grantedByPeer = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL)
        )
        a.peers.add(fromA)
        b.peers.add(
            b.record(
                a,
                grantedByMe = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
                grantedByPeer = setOf(PeerScope.STATUS_READ)
            )
        )
        val mesh = meshFor(a)
        mesh.refresh()
        assertEquals(
            200,
            mesh.call("""{"server":"${b.identity.nodeId}","service":"echo","op":"ping"}""", "t").status
        )

        // The peer removes the key; the very next request is refused, with no
        // restart and no cached decision.
        b.peers.remove(a.identity.nodeId)
        val refused = mesh.call(
            """{"server":"${b.identity.nodeId}","service":"echo","op":"ping"}""",
            "t"
        )
        assertEquals(401, refused.status)
        assertEquals(
            "MESH_UNAUTHORIZED",
            (refused.payload["error"] as Map<*, *>)["code"]
        )
    }

    @Test
    fun `three servers form one roster and each keeps its own grants`() {
        val a = node("A")
        val b = node("B")
        val c = node("C")

        for (peer in listOf(b, c)) {
            a.peers.add(
                a.record(
                    peer,
                    grantedByMe = setOf(PeerScope.STATUS_READ),
                    grantedByPeer = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL)
                )
            )
            peer.peers.add(
                peer.record(
                    a,
                    grantedByMe = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
                    grantedByPeer = setOf(PeerScope.STATUS_READ)
                )
            )
        }

        val mesh = meshFor(a)
        val refresh = mesh.refresh()
        assertEquals(2, refresh["total"])
        assertEquals(2, refresh["listed"])
        val roster = mesh.document()["servers"] as List<*>
        assertEquals(2, roster.size)
        val ids = roster.map { (it as Map<*, *>)["nodeId"] as String }.toSet()
        assertEquals(setOf(b.identity.nodeId, c.identity.nodeId), ids)

        for (peer in listOf(b, c)) {
            val answer = mesh.call(
                """{"server":"${peer.identity.nodeId}","service":"echo","op":"ping"}""",
                "t"
            )
            assertEquals(200, answer.status)
        }
    }

    @Test
    fun `the per-peer service allow-list is enforced on the serving side`() {
        val a = node("A")
        val b = node("B")

        b.peers.add(
            b.record(
                a,
                grantedByMe = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
                grantedByPeer = setOf(PeerScope.STATUS_READ),
                services = setOf("echo")
            )
        )
        a.peers.add(
            a.record(
                b,
                grantedByMe = setOf(PeerScope.STATUS_READ),
                grantedByPeer = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL)
            )
        )
        val mesh = meshFor(a)
        mesh.refresh()
        assertEquals(
            200,
            mesh.call("""{"server":"${b.identity.nodeId}","service":"echo","op":"ping"}""", "t").status
        )

        // An explicit list that does not include the service closes it even
        // though the scopes are unchanged (D101).
        b.peers.setServices(a.identity.nodeId, setOf("other"))
        val refused = mesh.call(
            """{"server":"${b.identity.nodeId}","service":"echo","op":"ping"}""",
            "t"
        )
        assertEquals(403, refused.status)
        assertEquals(
            "SERVICE_NOT_SHARED",
            (refused.payload["error"] as Map<*, *>)["code"]
        )
    }

    @Test
    fun `a server that never granted services read is reported, not invented`() {
        val a = node("A")
        val b = node("B")
        b.peers.add(
            b.record(
                a,
                grantedByMe = setOf(PeerScope.STATUS_READ),
                grantedByPeer = setOf(PeerScope.STATUS_READ)
            )
        )
        a.peers.add(
            a.record(
                b,
                grantedByMe = setOf(PeerScope.STATUS_READ),
                grantedByPeer = setOf(PeerScope.STATUS_READ)
            )
        )
        val mesh = meshFor(a)
        mesh.refresh()
        val server = (mesh.document()["servers"] as List<*>).first() as Map<*, *>
        assertEquals(false, server["servicesListed"])
        val error = server["catalogError"] as String?
        assertNotNull(error)
        assertEquals("MESH_SCOPE_NOT_GRANTED", error)
    }
}
