// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import app.grom.server.http.ApiException
import app.grom.server.store.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SshTargetStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Deterministic stand-in for the Android Keystore box. */
    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String =
            "sealed:" + plaintext.reversed()

        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:").takeIf { sealed.startsWith("sealed:") }?.reversed()
    }

    private fun store() = SshTargetStore(File(temp.root, SshTargetStore.FILE_NAME), ReversingBox())

    private fun passwordDraft(id: String? = null) = SshTargetDraft(
        id = id,
        label = "Mostro VPS",
        host = "203.0.113.10",
        port = 22,
        username = "grom",
        auth = SshAuth.PASSWORD,
        password = "s3cret-pass",
        remoteForwardPort = 47002
    )

    @Test
    fun `password is sealed, never written in clear text`() {
        val store = store()
        val saved = store.upsert(passwordDraft())
        assertEquals("grom", saved.username)
        assertNotNull(saved.passwordSealed)

        val text = File(temp.root, SshTargetStore.FILE_NAME).readText()
        assertFalse("the plaintext password must not be on disk", text.contains("s3cret-pass"))
        assertTrue(text.contains("sealed:"))
        assertEquals("s3cret-pass", store.openSecret(saved))
    }

    @Test
    fun `key targets store the sealed private text and a public line`() {
        val generated = SshKeys.generate()!!
        val store = store()
        val saved = store.upsert(
            SshTargetDraft(
                label = "key host",
                host = "vps.example.org",
                username = "grom",
                auth = SshAuth.KEY,
                privateKey = generated.privateKeyText
            )
        )
        assertNotNull(saved.privateKeySealed)
        assertNull(saved.passwordSealed)
        assertNotNull(saved.publicKeyLine)
        assertTrue(saved.publicKeyLine!!.startsWith("ssh-ed25519 "))

        val text = File(temp.root, SshTargetStore.FILE_NAME).readText()
        assertFalse(text.contains("OPENSSH PRIVATE KEY"))
        assertEquals(generated.privateKeyText, store.openSecret(saved))
    }

    @Test
    fun `an unparseable private key is refused`() {
        val failure = runCatching {
            store().upsert(
                SshTargetDraft(
                    label = "bad",
                    host = "vps.example.org",
                    username = "grom",
                    auth = SshAuth.KEY,
                    privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----"
                )
            )
        }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.code == "BAD_KEY")
    }

    @Test
    fun `key auth without a key is refused`() {
        val failure = runCatching {
            store().upsert(
                SshTargetDraft(
                    label = "no key",
                    host = "vps.example.org",
                    username = "grom",
                    auth = SshAuth.KEY
                )
            )
        }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.code == "KEY_REQUIRED")
    }

    @Test
    fun `two servers on one host may not share a forward port`() {
        val store = store()
        store.upsert(passwordDraft())

        // The same VPS and the same remote port would collide at connect time.
        val clash = runCatching {
            store.upsert(passwordDraft().copy(label = "Second tunnel"))
        }.exceptionOrNull()
        assertTrue(clash is ApiException && clash.code == "BAD_FORWARD_PORT_CONFLICT")

        // A different port is fine, and so is the same port on another host.
        store.upsert(passwordDraft().copy(label = "Second tunnel", remoteForwardPort = 41002))
        store.upsert(passwordDraft().copy(label = "Other VPS", host = "198.51.100.7"))
        assertEquals(3, store.list().size)

        // Editing a server does not conflict with itself.
        val first = store.list().first { it.label == "Mostro VPS" }
        store.upsert(passwordDraft(id = first.id).copy(port = 2222))
        assertEquals(3, store.list().size)
    }

    @Test
    fun `hostile hosts and labels are refused before any socket exists`() {
        for (host in listOf("https://evil", "user@host", "host/path", "ho st", "-leading")) {
            val failure = runCatching { store().upsert(passwordDraft().copy(host = host)) }
                .exceptionOrNull()
            assertTrue(
                "expected BAD_HOST for '$host', got $failure",
                failure is ApiException && failure.code == "BAD_HOST"
            )
        }
        val failure = runCatching {
            store().upsert(passwordDraft().copy(label = "x".repeat(65)))
        }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.code == "BAD_LABEL")
    }

    @Test
    fun `editing a target keeps the pin only when host and port are unchanged`() {
        val store = store()
        val saved = store.upsert(passwordDraft())
        store.pinHostKey(saved.id, "SHA256:abc")

        val edited = store.upsert(passwordDraft(id = saved.id).copy(label = "renamed"))
        assertEquals("SHA256:abc", edited.hostKeySha256)

        val moved = store.upsert(
            passwordDraft(id = saved.id).copy(host = "198.51.100.7")
        )
        assertNull("a different host must not inherit the old pin", moved.hostKeySha256)
    }

    @Test
    fun `a secret can be cleared explicitly`() {
        val store = store()
        val saved = store.upsert(passwordDraft())
        val cleared = store.upsert(
            passwordDraft(id = saved.id).copy(password = null, clearSecret = true)
        )
        assertNull(cleared.passwordSealed)
        assertFalse(cleared.isConfigured())
        assertNull(store.openSecret(cleared))
    }

    @Test
    fun `delete and unknown ids are reported`() {
        val store = store()
        val saved = store.upsert(passwordDraft())
        store.delete(saved.id)
        assertTrue(store.list().isEmpty())

        val failure = runCatching { store.delete(saved.id) }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.code == "TARGET_NOT_FOUND")
    }

    @Test
    fun `saved targets have no count cap`() {
        // D64: only live tunnels are capped. Saving many servers must keep
        // working, so the operator is never told "no" for storage reasons.
        val store = store()
        repeat(20) { index ->
            store.upsert(passwordDraft().copy(label = "host $index", host = "10.0.0.$index"))
        }
        assertEquals(20, store.list().size)
    }

    @Test
    fun `a full store refuses the write instead of becoming unreadable`() {
        // A deliberately tiny cap: the guard must trip at write time, because an
        // over-size file would make every record unreadable on the next start.
        val store = SshTargetStore(
            File(temp.root, SshTargetStore.FILE_NAME),
            ReversingBox(),
            maxFileBytes = 800
        )
        var saved = 0
        var refused: ApiException? = null
        while (saved < 50 && refused == null) {
            val attempt = runCatching {
                store.upsert(passwordDraft().copy(label = "host $saved", host = "10.0.0.$saved"))
            }
            refused = attempt.exceptionOrNull() as? ApiException
            if (refused == null) saved++
        }
        assertEquals("SSH_STORE_FULL", refused?.code)
        assertTrue("at least the first target must fit", saved > 0)
        // Whatever was written before the refusal is still readable.
        assertEquals(saved, store.list().size)
    }

    @Test
    fun `a corrupt file degrades to empty with a reason`() {
        val file = File(temp.root, SshTargetStore.FILE_NAME)
        file.writeText("not json")
        val store = store()
        assertTrue(store.list().isEmpty())
        assertNotNull(store.lastError)
    }

    /**
     * A pasted password usually arrives with a trailing newline. Storing it
     * would create a secret that can never authenticate, and the server's
     * answer to it is indistinguishable from a wrong password (D105).
     */
    @Test
    fun `a password with a pasted line break is refused with the reason`() {
        val store = store()
        val failure = runCatching {
            store.upsert(passwordDraft().copy(password = "s3cret-pass\n"))
        }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertEquals("BAD_PASSWORD", (failure as ApiException).code)
        assertTrue(
            "the message has to say what to do",
            failure.message.orEmpty().contains("control character")
        )
        assertTrue("nothing may be written", store.list().isEmpty())
    }

    @Test
    fun `the current server is one id that survives save, clear and delete`() {
        val store = store()
        val first = store.upsert(passwordDraft())
        val second = store.upsert(
            passwordDraft().copy(label = "other vps", host = "198.51.100.7", remoteForwardPort = 47003)
        )
        assertNull("nothing is current until the operator says so", store.activeId())

        store.setActive(second.id)
        assertEquals(second.id, store.activeId())

        // It is a file fact, not process state: a new instance sees it.
        assertEquals(second.id, store().activeId())

        // Editing an unrelated record must not move it.
        store.upsert(passwordDraft(id = first.id))
        assertEquals(second.id, store.activeId())

        // Deleting the current one clears the selection instead of dangling.
        store.delete(second.id)
        assertNull(store.activeId())

        store.setActive(first.id)
        assertEquals(first.id, store.activeId())
        store.setActive(null)
        assertNull(store.activeId())
    }

    @Test
    fun `an unknown current server is refused and never written`() {
        val store = store()
        store.upsert(passwordDraft())
        val failure = runCatching { store.setActive("0123456789abcdef") }.exceptionOrNull()
        assertTrue(failure is ApiException && failure.code == "TARGET_NOT_FOUND")
        assertNull(store.activeId())
    }

    @Test
    fun `the pin is round-tripped and cleared deliberately`() {
        val store = store()
        val saved = store.upsert(passwordDraft())
        store.pinHostKey(saved.id, "SHA256:xyz")
        assertEquals("SHA256:xyz", store.find(saved.id)!!.hostKeySha256)
        store.clearPin(saved.id)
        assertNull(store.find(saved.id)!!.hostKeySha256)

        val bad = runCatching { store.pinHostKey(saved.id, "MD5:123") }.exceptionOrNull()
        assertTrue(bad is ApiException && bad.code == "BAD_FINGERPRINT")
    }
}
