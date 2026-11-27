// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server network screen's pure model (D30, D99).
 *
 * The lamp is green only for a measured `alive`; a nearby hint is never a peer;
 * an explicit share list is shown in words rather than as an empty list; and a
 * BLE-only sighting cannot be turned into a pairing request by the UI.
 */
class MeshModelTest {

    private val document = mapOf(
        "enabled" to true,
        "advertise" to true,
        "scan" to true,
        "dataPlanePort" to 47002,
        "serverRunning" to true,
        "identity" to mapOf("nodeId" to "aaaaaaaaaaaaaaaa"),
        "localServices" to listOf("agent", "ai"),
        "radios" to listOf(
            mapOf("transport" to "lan", "available" to true, "missingPermissions" to emptyList<String>()),
            mapOf(
                "transport" to "bluetooth_le",
                "available" to false,
                "missingPermissions" to listOf("BLUETOOTH_SCAN"),
                "error" to null
            )
        ),
        "nearby" to listOf(
            mapOf(
                "key" to "id:0123456789abcdef",
                "nodeId" to "0123456789abcdef",
                "fingerprint" to "0123-4567-89ab-cdef",
                "transport" to "lan",
                "address" to "http://192.168.1.20:47002",
                "port" to 47002,
                "signedBeacon" to true,
                "dataCapable" to true
            ),
            mapOf(
                "key" to "prefix:bluetooth_le:11111111",
                "nodeIdPrefix" to "11111111",
                "transport" to "bluetooth_le",
                "address" to "",
                "port" to 47002,
                "signedBeacon" to false,
                "dataCapable" to false
            )
        ),
        "servers" to listOf(
            mapOf(
                "nodeId" to "0123456789abcdef",
                "label" to "Phone B",
                "fingerprint" to "0123-4567-89ab-cdef",
                "state" to "alive",
                "transports" to listOf("lan"),
                "inboundOnly" to false,
                "sharedServices" to listOf("agent"),
                "servicesListed" to true,
                "services" to listOf(
                    mapOf("id" to "agent", "title" to "Agent", "scopedOps" to listOf("ping"))
                ),
                "ai" to mapOf("enabled" to false, "models" to emptyList<String>())
            ),
            mapOf(
                "nodeId" to "ffffffffffffffff",
                "label" to "Phone C",
                "fingerprint" to "ffff-ffff-ffff-ffff",
                "state" to "unknown",
                "transports" to emptyList<String>(),
                "inboundOnly" to true,
                "sharedServices" to emptyList<String>(),
                "servicesListed" to false,
                "catalogError" to "MESH_SCOPE_NOT_GRANTED",
                "services" to emptyList<Map<String, Any?>>(),
                "ai" to mapOf("enabled" to false, "models" to emptyList<String>())
            )
        )
    )

    @Test
    fun `the document is parsed without inventing values`() {
        val screen = MeshModel.parse(document)
        assertTrue(screen.enabled)
        assertEquals(47002, screen.dataPlanePort)
        assertEquals(2, screen.nearby.size)
        assertEquals(2, screen.servers.size)
        assertEquals(2, screen.radios.size)
        assertEquals(1, screen.measuredAlive())
        assertEquals("1/2", MeshModel.summary(screen.servers))
        assertFalse(screen.needsDataPlane())
        assertTrue(screen.identityPresent)
    }

    @Test
    fun `a lamp is green only for a measured alive`() {
        val screen = MeshModel.parse(document)
        assertEquals(Lamp.ON, MeshModel.lampOf(screen.servers[0]))
        assertEquals(Lamp.UNKNOWN, MeshModel.lampOf(screen.servers[1]))
        val missing = screen.servers[1]
        assertEquals(false, missing.servicesListed)
        assertEquals("MESH_SCOPE_NOT_GRANTED", missing.catalogError)
        assertEquals("", MeshModel.transportsText(missing.transports))
    }

    @Test
    fun `only a sighting with an address can open the pairing dialog`() {
        val screen = MeshModel.parse(document)
        assertTrue(screen.nearby[0].canPair())
        assertFalse(screen.nearby[1].canPair())
        assertEquals("0123-4567-89ab-cdef", MeshModel.nearbyLabel(screen.nearby[0]))
        assertEquals("11111111", MeshModel.nearbyLabel(screen.nearby[1]))
    }

    @Test
    fun `shares are shown in words, and empty means the scopes default`() {
        val screen = MeshModel.parse(document)
        assertEquals("agent", MeshModel.sharesText(screen.servers[0]))
        // Empty share list: what the peer's scopes allow, which the model shows
        // as the services it published rather than as a wildcard word.
        assertEquals("", MeshModel.sharesText(screen.servers[1]))
    }

    @Test
    fun `a beacon that advertises a loopback port asks for the data plane`() {
        val off = MeshModel.parse(document + ("dataPlanePort" to 0))
        assertTrue(off.needsDataPlane())
        val disabled = MeshModel.parse(mapOf("enabled" to false))
        assertFalse(disabled.needsDataPlane())
        assertEquals("0/0", MeshModel.summary(disabled.servers))
        assertNull(disabled.lastError)
    }

    @Test
    fun `the bluetooth switch and the system radio are two different facts`() {
        // D109: an engine that predates the switch omits the key, and "on" is
        // the safe reading — it preserves the behaviour discovery always had.
        val legacy = MeshModel.parse(mapOf("enabled" to true))
        assertTrue(legacy.bluetooth)
        assertNull(legacy.bluetoothOn)
        assertFalse(legacy.needsSystemBluetooth())

        val switchedOff = MeshModel.parse(
            mapOf("enabled" to true, "bluetooth" to false, "bluetoothOn" to false)
        )
        assertFalse(switchedOff.bluetooth)
        assertFalse(switchedOff.needsSystemBluetooth())

        val radioOff = MeshModel.parse(
            mapOf("enabled" to true, "bluetooth" to true, "bluetoothOn" to false)
        )
        assertTrue(radioOff.needsSystemBluetooth())

        val radioOn = MeshModel.parse(mapOf("bluetooth" to true, "bluetoothOn" to true))
        assertFalse(radioOn.needsSystemBluetooth())
    }

    @Test
    fun `a radio that is switched off for the mesh is not an error`() {
        val screen = MeshModel.parse(
            mapOf(
                "bluetooth" to false,
                "radios" to listOf(
                    mapOf(
                        "transport" to "bluetooth_le",
                        "inUse" to false,
                        "available" to true,
                        "platformOn" to true,
                        "missingPermissions" to emptyList<String>(),
                        "error" to null
                    )
                )
            )
        )
        val row = screen.radios.single()
        assertFalse(row.inUse)
        assertTrue(row.available)
        assertNull(MeshModel.radioErrorRes(row.error))
    }

    @Test
    fun `a radio code is a sentence, and an unknown code stays visible`() {
        assertEquals(
            app.grom.server.R.string.mesh_err_ble_advertise_failed,
            MeshModel.radioErrorRes("BLE_ADVERTISE_FAILED:1")
        )
        assertEquals(
            app.grom.server.R.string.mesh_err_bluetooth_disabled,
            MeshModel.radioErrorRes("BLUETOOTH_DISABLED")
        )
        assertNull(MeshModel.radioErrorRes("SOMETHING_NEW"))
        assertNull(MeshModel.radioErrorRes(null))
        assertEquals(
            app.grom.server.R.string.mesh_err_wifi_direct_disabled,
            MeshModel.platformOffRes("wifi_direct")
        )
        assertEquals(
            app.grom.server.R.string.mesh_err_bluetooth_disabled,
            MeshModel.platformOffRes("bluetooth_classic")
        )
    }

    @Test
    fun `every transport has a real string`() {
        for (transport in MeshModel.TRANSPORT_ORDER) {
            assertTrue(MeshModel.transportRes(transport) != 0)
        }
        assertTrue(MeshModel.transportRes("nonsense") != 0)
    }

    @Test
    fun `a mesh pairing asks for the service scopes and never a local one`() {
        // D104: the Devices flow keeps its two scopes; the server network adds
        // exactly the two it needs to see and call the other server's services.
        assertEquals(
            listOf("status.read", "config.read"),
            MeshModel.pairScopes(withServices = false, withCalls = false)
        )
        assertEquals(
            listOf("status.read", "config.read", "services.read", "services.call"),
            MeshModel.pairScopes(withServices = true, withCalls = true)
        )
        val all = MeshModel.pairScopes(withServices = true, withCalls = true)
        assertFalse(all.any { it.startsWith("mesh.") })
        assertFalse(all.contains("ai.chat"))
    }
}
