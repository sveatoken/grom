// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.cluster.NodeIdentityStore
import app.grom.server.cluster.PeerRecord
import app.grom.server.cluster.PeerScope
import app.grom.server.cluster.PeerStore
import app.grom.server.http.JsonParser
import app.grom.server.store.SecretBox
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * The server network orchestrator, with fake radios and a fake transport.
 *
 * The point is the contract between the engine and the adapters: discovery is
 * off until configured, a sighting is a hint with an address that only the
 * operator view carries, a disabled network answers `MESH_DISABLED`, and a
 * relayed call reaches the peer's own route.
 */
class MeshServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Box : SecretBox {
        override fun seal(plaintext: String): String = "s:" + plaintext

        override fun open(sealed: String): String? =
            sealed.removePrefix("s:").takeIf { sealed.startsWith("s:") }
    }

    private class FakeRadio(
        override val transport: MeshTransport = MeshTransport.LAN,
        private val platform: Boolean? = null
    ) : MeshRadio {
        var started = 0
        var stopped = 0
        var scans = 0
        var sink: ((MeshCandidate) -> Unit)? = null
        override var lastError: String? = null
        override fun isAvailable(): Boolean = true
        override fun platformEnabled(): Boolean? = platform
        override fun missingPermissions(): List<String> = emptyList()

        override fun start(sink: (MeshCandidate) -> Unit): Boolean {
            this.sink = sink
            started += 1
            return true
        }

        override fun stop() {
            stopped += 1
        }

        override fun scanNow() {
            scans += 1
        }
    }

    private class FakeTransport : JsonTransport {
        var calls = 0
        var answer: () -> HttpJsonResponse = {
            HttpJsonResponse(
                200,
                """{"api":1,"principal":{"kind":"peer","nodeId":"REMOTE"},"services":{"listed":true,"items":[{"id":"echo","title":"Echo","scopedOps":["ping"]}]}}"""
            )
        }

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int
        ): HttpJsonResponse {
            calls += 1
            return answer()
        }
    }

    private val radio = FakeRadio()
    private val transport = FakeTransport()

    private fun harness(
        radios: List<MeshRadio> = listOf(radio)
    ): Triple<MeshService, PeerStore, NodeIdentityStore> {
        val home = temp.newFolder()
        val identityStore = NodeIdentityStore(File(home, NodeIdentityStore.FILE_NAME), Box())
        identityStore.loadOrCreate("Phone A")
        val peers = PeerStore(File(home, PeerStore.FILE_NAME))
        val settings = MeshSettingsStore(File(home, MeshSettings.FILE_NAME))
        val service = MeshService(
            identityStore = identityStore,
            peers = peers,
            settingsStore = settings,
            caller = MeshCaller(transport = transport),
            radios = radios,
            localServices = { setOf("echo") },
            localPort = { 47002 },
            serverRunning = { true }
        )
        return Triple(service, peers, identityStore)
    }

    @Test
    fun `discovery is off until configured and a disabled network refuses`() {
        val (service, _, _) = harness()
        service.start()
        assertEquals(0, radio.started)
        assertFalse(service.document()["enabled"] as Boolean)
        val disabled = service.refresh()
        assertEquals("MESH_DISABLED", disabled["code"])
        val refused = runCatching { service.scanJson() }.exceptionOrNull()
        assertTrue(refused != null)
    }

    @Test
    fun `enabling starts the radios and a sighting is a nearby hint only`() {
        val (service, _, _) = harness()
        service.start()
        service.configureJson("""{"enabled":true}""")
        assertEquals(1, radio.started)
        assertEquals(true, service.document()["enabled"])

        radio.sink?.invoke(
            MeshCandidate(
                key = "ignored",
                nodeId = "0123456789abcdef",
                nodeIdPrefix = "01234567",
                publicKey = null,
                label = null,
                transport = MeshTransport.LAN,
                address = "http://192.168.1.20:47002",
                port = 47002,
                signedBeacon = true,
                firstSeenMs = System.currentTimeMillis(),
                lastSeenMs = System.currentTimeMillis()
            )
        )
        val status = JsonParser.parseObjectOrNull(service.statusJson())!!
        val nearby = status["nearby"] as List<*>
        assertEquals(1, nearby.size)
        assertEquals("http://192.168.1.20:47002", (nearby.first() as Map<*, *>)["address"])
        // The shared roster never carries it.
        assertFalse(service.document().toString().contains("192.168.1.20"))

        service.stop()
        assertTrue(radio.stopped >= 1)
    }

    @Test
    fun `a relayed call reaches the peer route and unknown servers are refused`() {
        val (service, peers, _) = harness()
        service.start()
        service.configureJson("""{"enabled":true}""")
        val seed = Ed25519.generatePrivateSeed()
        val key = Ed25519.derivePublicKey(seed)
        val id = Ed25519.keyId(key)
        peers.add(
            PeerRecord(
                nodeId = id,
                publicKey = Base64.getEncoder().encodeToString(key),
                label = "Phone B",
                address = "http://127.0.0.1:47002",
                grantedByMe = setOf(PeerScope.STATUS_READ),
                grantedByPeer = setOf(PeerScope.SERVICES_READ, PeerScope.SERVICES_CALL),
                pairedAtMs = 0L
            )
        )
        transport.answer = {
            HttpJsonResponse(
                200,
                """{"api":1,"principal":{"kind":"peer","nodeId":"$id"},"services":{"listed":true,"items":[{"id":"echo","title":"Echo","scopedOps":["ping"]}]}}"""
            )
        }
        service.refresh()

        transport.answer = { HttpJsonResponse(200, """{"ok":true,"result":{"pong":true}}""") }
        val answer = service.call(
            """{"server":"$id","service":"echo","op":"ping"}""",
            "test"
        )
        assertEquals(200, answer.status)
        assertEquals(true, answer.payload["ok"])

        val unknown = service.call("""{"server":"ffffffffffffffff","service":"echo","op":"ping"}""", "t")
        assertEquals(404, unknown.status)
        assertEquals(
            "MESH_UNKNOWN_SERVER",
            (unknown.payload["error"] as Map<*, *>)["code"]
        )
    }

    @Test
    fun `a bluetooth switch that is off leaves its radios out without calling them broken`() {
        val lan = FakeRadio(MeshTransport.LAN)
        val le = FakeRadio(MeshTransport.BLUETOOTH_LE)
        val classic = FakeRadio(MeshTransport.BLUETOOTH_CLASSIC)
        val (service, _, _) = harness(listOf(lan, le, classic))
        service.start()
        service.configureJson("""{"enabled":true,"bluetooth":false}""")

        // The network carriers run; the Bluetooth ones are never started, but
        // they are also not reported as failures.
        assertEquals(1, lan.started)
        assertEquals(0, le.started)
        assertEquals(0, classic.started)

        val status = JsonParser.parseObjectOrNull(service.statusJson())!!
        assertEquals(false, status["bluetooth"])
        val rows = (status["radios"] as List<*>).map { it as Map<*, *> }
        assertEquals(false, rows.first { it["transport"] == "bluetooth_le" }["inUse"])
        assertEquals(false, rows.first { it["transport"] == "bluetooth_classic" }["inUse"])
        assertEquals(true, rows.first { it["transport"] == "lan" }["inUse"])
    }

    @Test
    fun `turning bluetooth back on starts its radios again`() {
        val le = FakeRadio(MeshTransport.BLUETOOTH_LE)
        val (service, _, _) = harness(listOf(le))
        service.start()
        service.configureJson("""{"enabled":true,"bluetooth":false}""")
        assertEquals(0, le.started)
        service.configureJson("""{"bluetooth":true}""")
        assertEquals(1, le.started)
        val status = JsonParser.parseObjectOrNull(service.statusJson())!!
        assertEquals(true, status["bluetooth"])
        // A fake radio has no platform switch to read: null, never a guess.
        assertNull(status["bluetoothOn"])
    }

    @Test
    fun `the system switch is measured and reported, not assumed`() {
        val off = FakeRadio(MeshTransport.BLUETOOTH_LE, platform = false)
        val (service, _, _) = harness(listOf(off))
        service.start()
        service.configureJson("""{"enabled":true}""")
        val status = JsonParser.parseObjectOrNull(service.statusJson())!!
        assertEquals(false, status["bluetoothOn"])
        val row = (status["radios"] as List<*>)
            .map { it as Map<*, *> }
            .first { it["transport"] == "bluetooth_le" }
        assertEquals(false, row["platformOn"])

        val on = FakeRadio(MeshTransport.BLUETOOTH_CLASSIC, platform = true)
        val (other, _, _) = harness(listOf(on))
        other.start()
        other.configureJson("""{"enabled":true}""")
        assertEquals(
            true,
            JsonParser.parseObjectOrNull(other.statusJson())!!["bluetoothOn"]
        )
    }

    @Test
    fun `a carrier switched off stops offering its sightings`() {
        val le = FakeRadio(MeshTransport.BLUETOOTH_LE)
        val lan = FakeRadio(MeshTransport.LAN)
        val (service, _, _) = harness(listOf(lan, le))
        service.start()
        service.configureJson("""{"enabled":true}""")

        val now = System.currentTimeMillis()
        le.sink?.invoke(
            MeshCandidate(
                key = "ignored",
                nodeId = null,
                nodeIdPrefix = "11111111",
                publicKey = null,
                label = null,
                transport = MeshTransport.BLUETOOTH_LE,
                address = "",
                port = 47002,
                signedBeacon = false,
                firstSeenMs = now,
                lastSeenMs = now
            )
        )
        lan.sink?.invoke(
            MeshCandidate(
                key = "ignored",
                nodeId = "0123456789abcdef",
                nodeIdPrefix = "01234567",
                publicKey = null,
                label = null,
                transport = MeshTransport.LAN,
                address = "http://192.168.1.30:47002",
                port = 47002,
                signedBeacon = true,
                firstSeenMs = now,
                lastSeenMs = now
            )
        )
        val before = JsonParser.parseObjectOrNull(service.statusJson())!!
        assertEquals(2, (before["nearby"] as List<*>).size)

        service.configureJson("""{"bluetooth":false}""")
        val nearby = JsonParser.parseObjectOrNull(service.statusJson())!!
            .let { it["nearby"] as List<*> }
        assertEquals(1, nearby.size)
        assertEquals("lan", (nearby.first() as Map<*, *>)["transport"])
    }

    @Test
    fun `the status summary carries counts, not identities`() {
        val (service, _, _) = harness()
        service.start()
        val summary = service.summary()
        assertEquals(0, summary["servers"])
        assertEquals(0, summary["nearby"])
        assertFalse(summary.toString().contains("nodeId"))
    }
}
