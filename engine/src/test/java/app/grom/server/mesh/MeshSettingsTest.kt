// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The switches, their persistence and their fail-closed default (D99). */
class MeshSettingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `discovery is off until an operator turns it on`() {
        val store = MeshSettingsStore(File(temp.root, MeshSettings.FILE_NAME))
        val loaded = store.load()
        assertFalse(loaded.enabled)
        assertTrue(loaded.advertise)
        assertTrue(loaded.scan)
    }

    @Test
    fun `a saved document loads back unchanged`() {
        val store = MeshSettingsStore(File(temp.root, MeshSettings.FILE_NAME))
        store.save(MeshSettings(enabled = true, advertise = false, scan = true))
        val loaded = store.load()
        assertTrue(loaded.enabled)
        assertFalse(loaded.advertise)
        assertTrue(loaded.scan)
        assertNull(store.lastError)
    }

    @Test
    fun `a partial document keeps the fields it does not name`() {
        val next = MeshSettings(enabled = true, advertise = true, scan = true)
            .apply(JsonParser.parseObjectOrNull("""{"scan":false}""")!!)
        assertNotNull(next)
        assertEquals(true, next!!.enabled)
        assertEquals(true, next.advertise)
        assertEquals(false, next.scan)
    }

    @Test
    fun `a value that is not a boolean is refused rather than coerced`() {
        assertNull(
            MeshSettings().apply(JsonParser.parseObjectOrNull("""{"enabled":"yes"}""")!!)
        )
    }

    @Test
    fun `bluetooth is used for the mesh until an operator turns it off`() {
        // The default is "on" so an upgrade does not silently remove a
        // transport that discovery has always started (D109).
        assertTrue(MeshSettings().bluetooth)
        val store = MeshSettingsStore(File(temp.root, MeshSettings.FILE_NAME))
        store.save(MeshSettings(enabled = true, bluetooth = false))
        val loaded = store.load()
        assertTrue(loaded.enabled)
        assertFalse(loaded.bluetooth)
        assertNull(store.lastError)
    }

    @Test
    fun `turning bluetooth off leaves discovery on`() {
        val next = MeshSettings(enabled = true)
            .apply(JsonParser.parseObjectOrNull("""{"bluetooth":false}""")!!)
        assertNotNull(next)
        assertFalse(next!!.bluetooth)
        assertTrue(next.enabled)
        assertTrue(next.advertise)
        assertTrue(next.scan)
    }

    @Test
    fun `an older settings file without the key keeps bluetooth on`() {
        val file = File(temp.root, MeshSettings.FILE_NAME)
        file.writeText("""{"enabled":true,"advertise":true,"scan":true}""")
        val loaded = MeshSettingsStore(file).load()
        assertTrue(loaded.enabled)
        assertTrue(loaded.bluetooth)
    }

    @Test
    fun `a corrupt file fails closed and is reported`() {
        val file = File(temp.root, MeshSettings.FILE_NAME)
        file.writeText("{not json")
        val store = MeshSettingsStore(file)
        val loaded = store.load()
        assertFalse(loaded.enabled)
        assertNotNull(store.lastError)
        // The operator's file is never silently deleted.
        assertTrue(file.isFile)
    }

    @Test
    fun `the stored document is small and valid json`() {
        val file = File(temp.root, MeshSettings.FILE_NAME)
        MeshSettingsStore(file).save(MeshSettings(enabled = true))
        val parsed = JsonParser.parseObjectOrNull(file.readText())
        assertNotNull(parsed)
        assertEquals(true, parsed!!["enabled"])
        assertTrue(Json.encode(MeshSettings(enabled = true)).length < 200)
    }
}
