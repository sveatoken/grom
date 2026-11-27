// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure part of D110: what the server offered is filtered before it can
 * reach a screen, a status document or a log line.
 */
class SshAuthMethodsTest {

    @Test
    fun `names are lowercased, deduplicated and sorted`() {
        assertEquals(
            listOf("keyboard-interactive", "password", "publickey"),
            SshAuthMethods.sanitize(listOf("publickey", "Password", "keyboard-interactive", "password"))
        )
    }

    @Test
    fun `anything that is not a method name is dropped`() {
        val offered = listOf(
            "publickey",
            "password\nGrom: injected",
            "key with space",
            "",
            "../../etc/passwd",
            "x".repeat(64)
        )
        assertEquals(listOf("publickey"), SshAuthMethods.sanitize(offered))
    }

    @Test
    fun `the list is bounded`() {
        val many = (1..40).map { "method-$it" }
        assertEquals(SshAuthMethods.MAX_METHODS, SshAuthMethods.sanitize(many).size)
    }

    @Test
    fun `not measured is an empty list, not a claim`() {
        assertEquals(emptyList<String>(), SshAuthMethods.sanitize(null))
    }

    @Test
    fun `keyboard-interactive counts as password login`() {
        assertTrue(SshAuthMethods.acceptsPassword(listOf("keyboard-interactive", "publickey")))
        assertTrue(SshAuthMethods.acceptsPassword(listOf("password")))
        assertFalse(SshAuthMethods.acceptsPassword(listOf("publickey")))
        assertTrue(SshAuthMethods.acceptsPublicKey(listOf("publickey")))
        assertFalse(SshAuthMethods.acceptsPublicKey(listOf("password")))
    }
}
