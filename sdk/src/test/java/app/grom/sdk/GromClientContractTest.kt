// SPDX-License-Identifier: Apache-2.0
package app.grom.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The constants an outside client compiles against (S4-R012, S4-R020).
 *
 * The API version is bumped whenever a method is added, so a client built for
 * API 9 knows to check before calling an API 10 method (the AI gateway, D94).
 */
class GromClientContractTest {

    @Test
    fun `api version is 10`() {
        assertEquals(10, GromClient.API_VERSION)
    }

    @Test
    fun `the standalone component identity is stable`() {
        assertEquals("app.grom.server", GromClient.SERVER_PACKAGE)
        assertEquals("app.grom.server.service.GromService", GromClient.SERVICE_CLASS)
        assertEquals("app.grom.sdk.ipc.GROM_SERVER", GromClient.ACTION)
        assertEquals("app.grom.sdk.ipc.BIND_GROMSERVER", GromClient.PERMISSION)
    }

    @Test
    fun `the embedded target is a distinct mode`() {
        assertNotEquals(GromClient.Target.STANDALONE, GromClient.Target.EMBEDDED)
    }
}
