// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading another server's connect document (D91, D100).
 *
 * `listed:false` is preserved as a fact ("it answered, but did not enumerate"),
 * never flattened into "it has no services". A document that names a different
 * node id than the paired one is a changed identity, not a catalogue.
 */
class MeshCatalogTest {

    private val nodeId = "0123456789abcdef"
    private val now = 1_700_000_000_000L

    private fun document(
        id: String = nodeId,
        listed: Boolean = true,
        ai: Boolean = true
    ): String = """
        {
          "ok": true,
          "api": 1,
          "grom": "0.18.0-server29",
          "principal": {"kind": "peer", "nodeId": "$id"},
          "services": {
            "listed": $listed,
            "items": [
              {
                "id": "agent",
                "title": "Agent",
                "description": "Node agent",
                "capabilities": ["ping", "status"],
                "scopedOps": ["echo", "ping"]
              }
            ]
          },
          "ai": {"enabled": $ai, "listed": $ai, "items": [{"id": "m1"}]}
        }
    """.trimIndent()

    @Test
    fun `a listed catalogue keeps only callable operations`() {
        val catalog = MeshCatalog.parse(nodeId, document(), now)
        assertNull(catalog.error)
        assertTrue(catalog.listed)
        assertEquals(1, catalog.services.size)
        val agent = catalog.service("agent")
        assertNotNull(agent)
        assertTrue(agent!!.callable("ping"))
        assertFalse(agent.callable("status"))
        assertEquals(setOf("echo", "ping"), agent.scopedOps)
        assertEquals(1, catalog.aiModels.size)
        assertEquals("ai.read", catalog.aiOp("models"))
        assertEquals("ai.chat", catalog.aiOp("chat"))
        assertNull(catalog.aiOp("images"))
    }

    @Test
    fun `an unlisted catalogue says so instead of pretending to be empty`() {
        val catalog = MeshCatalog.parse(nodeId, document(listed = false), now)
        assertNull(catalog.error)
        assertFalse(catalog.listed)
        assertTrue(catalog.services.isEmpty())
    }

    @Test
    fun `the principal names the caller, so it is not an identity check`() {
        // `principal.nodeId` is this device (the caller); the server's identity
        // is proven by the signed probe. A catalogue from another caller is
        // still a valid catalogue.
        val catalog = MeshCatalog.parse(nodeId, document(id = "ffffffffffffffff"), now)
        assertEquals(null, catalog.error)
        assertTrue(catalog.listed)
    }

    @Test
    fun `a document that does not parse is a failed measurement`() {
        assertEquals("MESH_BAD_RESPONSE", MeshCatalog.parse(nodeId, "{", now).error)
        assertEquals("MESH_BAD_RESPONSE", MeshCatalog.parse(nodeId, "[]", now).error)
    }

    @Test
    fun `operation names outside the alphabet never become callable`() {
        val catalog = MeshCatalog.parse(
            nodeId,
            document().replace("\"echo\", \"ping\"", "\"ok?\", \"ping\""),
            now
        )
        val agent = catalog.service("agent")
        assertNotNull(agent)
        assertFalse(agent!!.callable("ok?"))
        assertEquals(setOf("ping"), agent.scopedOps)
    }
}
