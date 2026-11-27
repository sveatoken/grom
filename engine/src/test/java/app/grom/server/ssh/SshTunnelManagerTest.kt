// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import app.grom.server.core.SuperBindPolicy
import app.grom.server.store.SecretBox
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end proof of D58 against a real SSH server on loopback.
 *
 * Apache MINA SSHD's own server implementation is started in-process, the Grom
 * client connects to it, learns and pins the host key, opens a remote
 * `tcpip-forward`, and a plain TCP client on the "VPS side" reaches a local
 * echo server through the tunnel. Nothing here is mocked except the network
 * host itself.
 */
class SshTunnelManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class ReversingBox : SecretBox {
        override fun seal(plaintext: String): String = "sealed:" + plaintext.reversed()
        override fun open(sealed: String): String? =
            sealed.removePrefix("sealed:").takeIf { sealed.startsWith("sealed:") }?.reversed()
    }

    private lateinit var sshServer: SshServer
    private var sshPort: Int = 0

    private lateinit var echo: ServerSocket
    private lateinit var echoThread: Thread
    private var echoConnections = CountDownLatch(1)

    @Before
    fun setUp() {
        SshKeys.ensureProvider()

        sshServer = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File(temp.root, "hostkey.ser").toPath()
            )
            passwordAuthenticator = PasswordAuthenticator { username, password, _ ->
                username == "grom" && password == "s3cret-pass"
            }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        }
        sshServer.start()
        sshPort = sshServer.port

        echo = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        echoThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val client = runCatching { echo.accept() }.getOrNull() ?: return@Thread
                echoConnections.countDown()
                runCatching {
                    val request = client.getInputStream().bufferedReader().readLine()
                    client.getOutputStream().write("pong:$request\n".toByteArray())
                    client.getOutputStream().flush()
                }
                runCatching { client.close() }
            }
        }, "echo-server")
        echoThread.isDaemon = true
        echoThread.start()
    }

    @After
    fun tearDown() {
        echoThread.interrupt()
        runCatching { echo.close() }
        runCatching { sshServer.stop(true) }
    }

    private fun newStore(): SshTargetStore =
        SshTargetStore(File(temp.root, SshTargetStore.FILE_NAME), ReversingBox())

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun manager(store: SshTargetStore): SshTunnelManager =
        SshTunnelManager(
            store = store,
            audit = { _, _ -> },
            logger = { _ -> },
            localTarget = { "127.0.0.1" to echo.localPort }
        )

    @Test(timeout = 120_000)
    fun `tofu, reverse forward, end-to-end bytes and measured status`() {
        val store = newStore()
        val remotePort = freePort()
        val saved = store.upsert(
            SshTargetDraft(
                label = "test vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = remotePort
            )
        )
        val manager = manager(store)

        // 1. No pin and no trust -> the caller must confirm the fingerprint.
        val first = manager.connect(saved.id, trustHostKey = null)
        assertTrue("expected host key confirmation, got $first", first is SshTunnelManager.Result.HostKeyUnverified)
        val fingerprint = (first as SshTunnelManager.Result.HostKeyUnverified).fingerprint
        assertTrue(fingerprint.startsWith("SHA256:"))
        assertEquals(SshTunnelManager.State.DISCONNECTED, manager.state(saved.id))

        // 2. Confirm -> connected and the pin is learned.
        val second = manager.connect(saved.id, trustHostKey = fingerprint)
        assertTrue("expected connected, got $second", second is SshTunnelManager.Result.Connected)
        assertTrue((second as SshTunnelManager.Result.Connected).learned)
        assertEquals(SshTunnelManager.State.CONNECTED, manager.state(saved.id))
        assertEquals(fingerprint, store.find(saved.id)!!.hostKeySha256)

        // 3. Bytes really flow through the reverse forward.
        var response: String? = null
        runCatching {
            Socket("127.0.0.1", remotePort).use { socket ->
                socket.soTimeout = 15_000
                socket.getOutputStream().write("ping\n".toByteArray())
                socket.getOutputStream().flush()
                response = socket.getInputStream().bufferedReader().readLine()
            }
        }
        assertTrue(
            "the forwarded connection must reach the local target",
            echoConnections.await(15, TimeUnit.SECONDS)
        )
        assertEquals("pong:ping", response)

        // 4. Status is measured: no host or user in the version that may leave
        //    the device, both in the on-screen version.
        val server = manager.statusForServer()
        assertEquals(true, server["connected"])
        val tunnels = server["tunnels"] as List<*>
        val tunnel = tunnels.single() as Map<*, *>
        assertFalse("server status must not carry the host", tunnel.containsKey("host"))
        assertFalse("server status must not carry the user", tunnel.containsKey("username"))
        assertEquals("connected", tunnel["state"])

        val ui = manager.statusForUi().single()
        assertEquals("127.0.0.1", ui["host"])
        assertEquals("grom", ui["username"])

        // 5. A deliberate disconnect is state, not a failure.
        manager.disconnect(saved.id)
        assertEquals(SshTunnelManager.State.DISCONNECTED, manager.state(saved.id))
    }

    @Test(timeout = 120_000)
    fun `a changed pin is refused instead of trusted`() {
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "test vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = freePort()
            )
        )
        // Learn the real fingerprint, then replace it with a wrong pin.
        val manager = manager(store)
        val first = manager.connect(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
        store.pinHostKey(saved.id, "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")

        val result = manager.connect(saved.id, null)
        assertTrue("expected a mismatch, got $result", result is SshTunnelManager.Result.HostKeyMismatch)
        assertEquals(SshTunnelManager.State.FAILED, manager.state(saved.id))
        assertFalse(manager.isActive(saved.id))
        // Even a caller-supplied trust value cannot override an existing pin.
        assertTrue(manager.connect(saved.id, first.fingerprint) is SshTunnelManager.Result.HostKeyMismatch)
    }

    @Test(timeout = 120_000)
    fun `a stopped data plane refuses to open a tunnel`() {
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "test vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = freePort()
            )
        )
        val manager = SshTunnelManager(
            store = store,
            audit = { _, _ -> },
            logger = { _ -> },
            localTarget = { null }
        )
        val result = manager.connect(saved.id, null)
        assertTrue(result is SshTunnelManager.Result.Failed)
        assertEquals(
            "DATA_PLANE_NOT_RUNNING",
            (result as SshTunnelManager.Result.Failed).code
        )
    }

    @Test(timeout = 120_000)
    fun `auto-generated keys authenticate against a public key authorizer`() {
        sshServer.publickeyAuthenticator =
            org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key, _ ->
                key != null && SshKeys.publicKeyLine(key) != null
            }

        val generated = SshKeys.generate()!!
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "key vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.KEY,
                privateKey = generated.privateKeyText,
                remoteForwardPort = freePort()
            )
        )
        val manager = manager(store)
        val probe = manager.connect(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
        val connected = manager.connect(saved.id, probe.fingerprint)
        assertTrue("expected key auth to connect, got $connected", connected is SshTunnelManager.Result.Connected)
        manager.disconnect(saved.id)
    }

    @Test(timeout = 120_000)
    fun `the active tunnel cap refuses one tunnel beyond the limit`() {
        val store = newStore()
        val manager = manager(store)
        val ids = (1..(SshTarget.MAX_ACTIVE + 1)).map { index ->
            store.upsert(
                SshTargetDraft(
                    label = "cap $index",
                    host = "127.0.0.1",
                    port = sshPort,
                    username = "grom",
                    auth = SshAuth.PASSWORD,
                    password = "s3cret-pass",
                    remoteForwardPort = freePort()
                )
            ).id
        }
        fun up(id: String) {
            val probe = manager.connect(id, null) as SshTunnelManager.Result.HostKeyUnverified
            val result = manager.connect(id, probe.fingerprint)
            assertTrue("expected connected, got $result", result is SshTunnelManager.Result.Connected)
        }
        for (index in 0 until SshTarget.MAX_ACTIVE) up(ids[index])
        assertEquals(SshTarget.MAX_ACTIVE, manager.activeCount())

        val extra = manager.connect(ids[SshTarget.MAX_ACTIVE], null)
        assertTrue(extra is SshTunnelManager.Result.Failed)
        assertEquals("TOO_MANY_TUNNELS", (extra as SshTunnelManager.Result.Failed).code)
        assertEquals(SshTarget.MAX_ACTIVE, manager.activeCount())

        manager.stopAll()
        assertEquals(0, manager.activeCount())
    }

    /**
     * A server that refuses the remote port (already bound, or forwarding
     * forbidden) is a distinct, actionable failure (D78). Mapping it to
     * `SSH_FAILED` would send the operator hunting for a network problem.
     */
    @Test(timeout = 120_000)
    fun `a refused remote port is reported distinctly`() {
        val occupied = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        try {
            val store = newStore()
            val saved = store.upsert(
                SshTargetDraft(
                    label = "busy port vps",
                    host = "127.0.0.1",
                    port = sshPort,
                    username = "grom",
                    auth = SshAuth.PASSWORD,
                    password = "s3cret-pass",
                    remoteForwardPort = occupied.localPort
                )
            )
            val manager = manager(store)
            val probe = manager.connect(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
            val result = manager.connect(saved.id, probe.fingerprint)

            assertTrue("expected a refusal, got $result", result is SshTunnelManager.Result.Failed)
            assertEquals(
                "SSH_FORWARD_REFUSED",
                (result as SshTunnelManager.Result.Failed).code
            )
            assertEquals(SshTunnelManager.State.FAILED, manager.state(saved.id))
            assertFalse(manager.isActive(saved.id))
        } finally {
            occupied.close()
        }
    }

    /**
     * A rejected password is `SSH_AUTH_FAILED` (D105). MINA raises an
     * `SshException`, which *is* an `IOException`, so the old mapping called
     * this "network unreachable" and sent the operator to look at a firewall.
     */
    @Test(timeout = 120_000)
    fun `a wrong password is an auth failure not an unreachable host`() {
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "wrong password",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "not-the-password",
                remoteForwardPort = freePort()
            )
        )
        val manager = manager(store)
        val probe = manager.connect(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
        val result = manager.connect(saved.id, probe.fingerprint)

        assertTrue("expected a failure, got $result", result is SshTunnelManager.Result.Failed)
        val failed = result as SshTunnelManager.Result.Failed
        assertEquals("SSH_AUTH_FAILED", failed.code)
        assertEquals(SshTunnelManager.Stage.AUTH, failed.stage)
        assertFalse(manager.isActive(saved.id))
    }

    /**
     * The most common real answer to "my password does not work": the server
     * does not take passwords at all (D110). The reply says which methods it
     * offers, so the screen can say "publickey only" instead of "wrong password".
     */
    @Test(timeout = 120_000)
    fun `a server that takes no passwords is diagnosed as such`() {
        // A separate server, built that way from the start: changing the
        // authenticator of a running one leaves the advertised list stale, and
        // the point of this test is what the server *offers*.
        val keysOnly = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File(temp.root, "hostkey-keysonly.ser").toPath()
            )
            passwordAuthenticator = null
            publickeyAuthenticator = PublickeyAuthenticator { _, _, _ -> false }
            // Named explicitly so the advertised list is a fact of this test
            // rather than of the driver's default wiring.
            userAuthFactories = listOf(UserAuthPublicKeyFactory.INSTANCE)
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        }
        keysOnly.start()
        try {
            val store = newStore()
            val saved = store.upsert(
                SshTargetDraft(
                    label = "keys only",
                    host = "127.0.0.1",
                    port = keysOnly.port,
                    username = "grom",
                    auth = SshAuth.PASSWORD,
                    password = "s3cret-pass",
                    remoteForwardPort = freePort()
                )
            )
            val manager = manager(store)
            val probe = manager.connect(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
            val result = manager.connect(saved.id, probe.fingerprint)

            assertTrue("expected a failure, got $result", result is SshTunnelManager.Result.Failed)
            val failed = result as SshTunnelManager.Result.Failed
            assertEquals(
                "offered=${failed.authMethods} message=${failed.message}",
                "SSH_AUTH_METHOD_NOT_ALLOWED",
                failed.code
            )
            assertEquals(SshTunnelManager.Stage.AUTH, failed.stage)
            assertEquals(listOf("publickey"), failed.authMethods)
        } finally {
            runCatching { keysOnly.stop(true) }
        }
    }

    /**
     * The data plane bound to the LAN address (which the mesh needs) must still
     * deliver through the tunnel. Before D111 the forward policy only accepted
     * loopback, so this connected green and then dropped every connection.
     */
    @Test(timeout = 120_000)
    fun `a data plane on the lan address still carries bytes`() {
        val lan: InetAddress? = SuperBindPolicy.selectLan(SuperBindPolicy.localAddresses())
        Assume.assumeTrue("this host has no non-loopback IPv4 address", lan != null)
        val lanEcho = ServerSocket(0, 4, lan)
        val accepted = CountDownLatch(1)
        val lanThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val client = runCatching { lanEcho.accept() }.getOrNull() ?: return@Thread
                accepted.countDown()
                runCatching {
                    val request = client.getInputStream().bufferedReader().readLine()
                    client.getOutputStream().write("lan:$request\n".toByteArray())
                    client.getOutputStream().flush()
                }
                runCatching { client.close() }
            }
        }, "lan-echo")
        lanThread.isDaemon = true
        lanThread.start()

        try {
            val store = newStore()
            val remotePort = freePort()
            val saved = store.upsert(
                SshTargetDraft(
                    label = "lan bind",
                    host = "127.0.0.1",
                    port = sshPort,
                    username = "grom",
                    auth = SshAuth.PASSWORD,
                    password = "s3cret-pass",
                    remoteForwardPort = remotePort
                )
            )
            val manager = SshTunnelManager(
                store = store,
                audit = { _, _ -> },
                logger = { _ -> },
                localTarget = { lan!!.hostAddress to lanEcho.localPort }
            )
            val probe = manager.connect(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
            val result = manager.connect(saved.id, probe.fingerprint)
            assertTrue("expected connected, got $result", result is SshTunnelManager.Result.Connected)

            var response: String? = null
            runCatching {
                Socket("127.0.0.1", remotePort).use { socket ->
                    socket.soTimeout = 15_000
                    socket.getOutputStream().write("hello\n".toByteArray())
                    socket.getOutputStream().flush()
                    response = socket.getInputStream().bufferedReader().readLine()
                }
            }
            assertTrue(
                "the forwarded connection must reach the LAN-bound data plane",
                accepted.await(15, TimeUnit.SECONDS)
            )
            assertEquals("lan:hello", response)
            manager.disconnect(saved.id)
        } finally {
            lanThread.interrupt()
            runCatching { lanEcho.close() }
        }
    }

    /** A port with nothing listening is a different sentence from a bad password. */
    @Test(timeout = 120_000)
    fun `a closed port is an unreachable host`() {
        val closed = freePort()
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "closed port",
                host = "127.0.0.1",
                port = closed,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = freePort()
            )
        )
        val manager = manager(store)
        val result = manager.connect(saved.id, null)

        assertTrue("expected a failure, got $result", result is SshTunnelManager.Result.Failed)
        val failed = result as SshTunnelManager.Result.Failed
        assertEquals("SSH_HOST_UNREACHABLE", failed.code)
        assertEquals(SshTunnelManager.Stage.CONNECT, failed.stage)
    }

    /** A name that does not resolve is reported as a name problem. */
    @Test(timeout = 120_000)
    fun `an unresolvable name is its own diagnosis`() {
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "no such host",
                host = "grom-does-not-resolve.invalid",
                port = 22,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = freePort()
            )
        )
        val manager = manager(store)
        val result = manager.connect(saved.id, null)

        assertTrue("expected a failure, got $result", result is SshTunnelManager.Result.Failed)
        val failed = result as SshTunnelManager.Result.Failed
        assertEquals("SSH_HOST_UNKNOWN", failed.code)
        assertEquals(SshTunnelManager.Stage.RESOLVE, failed.stage)
    }

    /**
     * The check the operator actually needs when a public address and a
     * password do not work: prove name, port, host key and password without a
     * data plane, and say plainly that the forward was not tested (D106).
     */
    @Test(timeout = 120_000)
    fun `verify proves the credentials with the data plane off`() {
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = freePort()
            )
        )
        val offline = SshTunnelManager(
            store = store,
            audit = { _, _ -> },
            logger = { _ -> },
            localTarget = { null }
        )

        // First use still asks the operator to confirm the fingerprint.
        val first = offline.verify(saved.id, null)
        assertTrue(first is SshTunnelManager.Result.HostKeyUnverified)
        val fingerprint = (first as SshTunnelManager.Result.HostKeyUnverified).fingerprint

        val verified = offline.verify(saved.id, fingerprint)
        assertTrue("expected verified, got $verified", verified is SshTunnelManager.Result.Verified)
        val ok = verified as SshTunnelManager.Result.Verified
        assertFalse("the forward cannot be claimed without a data plane", ok.forwardChecked)
        assertEquals(fingerprint, ok.fingerprint)
        assertTrue(ok.learned)

        // A check leaves nothing running and opens no tunnel.
        assertEquals(SshTunnelManager.State.DISCONNECTED, offline.state(saved.id))
        assertEquals(0, offline.activeCount())
        assertEquals(fingerprint, store.find(saved.id)!!.hostKeySha256)

        // A check that failed is a measurement too, so the row must not stay red
        // after a later check proves the same credentials work.
        val wrongOnce = newStore()
        val typo = wrongOnce.upsert(
            SshTargetDraft(
                label = "vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "nope",
                remoteForwardPort = freePort()
            )
        )
        val recovered = SshTunnelManager(
            store = wrongOnce,
            audit = { _, _ -> },
            logger = { _ -> },
            localTarget = { null }
        )
        val failedFirst = recovered.verify(typo.id, fingerprint)
        assertEquals(
            "SSH_AUTH_FAILED",
            (failedFirst as SshTunnelManager.Result.Failed).code
        )
        assertEquals(SshTunnelManager.State.FAILED, recovered.state(typo.id))

        // The operator fixes the password and checks again.
        wrongOnce.upsert(
            SshTargetDraft(
                id = typo.id,
                label = "vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = typo.remoteForwardPort
            )
        )
        assertTrue(
            recovered.verify(typo.id, fingerprint) is SshTunnelManager.Result.Verified
        )
        assertEquals(SshTunnelManager.State.DISCONNECTED, recovered.state(typo.id))

        // The same check now reports the wrong password as such.
        val wrong = newStore()
        val bad = wrong.upsert(
            SshTargetDraft(
                label = "vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "nope",
                remoteForwardPort = freePort()
            )
        )
        val mismatch = SshTunnelManager(
            store = wrong,
            audit = { _, _ -> },
            logger = { _ -> },
            localTarget = { null }
        ).verify(bad.id, fingerprint)
        assertEquals(
            "SSH_AUTH_FAILED",
            (mismatch as SshTunnelManager.Result.Failed).code
        )
    }

    /** With a data plane the same check reports the forward as tested. */
    @Test(timeout = 120_000)
    fun `verify reports the forward when the data plane is up`() {
        val store = newStore()
        val saved = store.upsert(
            SshTargetDraft(
                label = "vps",
                host = "127.0.0.1",
                port = sshPort,
                username = "grom",
                auth = SshAuth.PASSWORD,
                password = "s3cret-pass",
                remoteForwardPort = freePort()
            )
        )
        val manager = manager(store)
        val probe = manager.verify(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
        val verified = manager.verify(saved.id, probe.fingerprint)

        assertTrue("expected verified, got $verified", verified is SshTunnelManager.Result.Verified)
        assertTrue((verified as SshTunnelManager.Result.Verified).forwardChecked)
        // And it is still not a tunnel: the operator's state did not change.
        assertEquals(0, manager.activeCount())
        assertEquals(SshTunnelManager.State.DISCONNECTED, manager.state(saved.id))
    }

    /**
     * OpenSSH with PAM accepts a password login through keyboard-interactive.
     * Without a handler the correct password fails as "exhausted methods",
     * which is indistinguishable from a wrong one (D105).
     */
    @Test(timeout = 120_000)
    fun `a keyboard interactive only server accepts the stored password`() {
        val kbd = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File(temp.root, "kbd-hostkey.ser").toPath()
            )
            // Only the interactive method, like an sshd that logs in through PAM.
            userAuthFactories = listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
            keyboardInteractiveAuthenticator =
                object : org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator {
                    override fun generateChallenge(
                        session: org.apache.sshd.server.session.ServerSession?,
                        username: String?,
                        lang: String?,
                        subMethods: String?
                    ): org.apache.sshd.server.auth.keyboard.InteractiveChallenge =
                        org.apache.sshd.server.auth.keyboard.InteractiveChallenge().apply {
                            interactionName = "password"
                            addPrompt("Password: ", false)
                        }

                    override fun authenticate(
                        session: org.apache.sshd.server.session.ServerSession?,
                        username: String?,
                        responses: List<String>?
                    ): Boolean = username == "grom" && responses?.singleOrNull() == "s3cret-pass"
                }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        }
        kbd.start()
        try {
            val store = newStore()
            val saved = store.upsert(
                SshTargetDraft(
                    label = "pam vps",
                    host = "127.0.0.1",
                    port = kbd.port,
                    username = "grom",
                    auth = SshAuth.PASSWORD,
                    password = "s3cret-pass",
                    remoteForwardPort = freePort()
                )
            )
            val manager = manager(store)
            val probe = manager.connect(saved.id, null) as SshTunnelManager.Result.HostKeyUnverified
            val result = manager.connect(saved.id, probe.fingerprint)
            assertTrue(
                "the stored password must answer the interactive prompt, got $result",
                result is SshTunnelManager.Result.Connected
            )
            manager.disconnect(saved.id)
        } finally {
            runCatching { kbd.stop(true) }
        }
    }
}
class SshForwardingPolicyTest {

    private val policy = SshForwardingPolicy("127.0.0.1" to 47002)

    @Test
    fun `only the requested local endpoint may be reached`() {
        assertTrue(policy.canConnect(Forwarded, address("127.0.0.1", 47002), null))
        // The same endpoint, spelled the way another stack may spell it.
        assertTrue(policy.canConnect(Forwarded, address("localhost", 47002), null))
        assertFalse(policy.canConnect(Forwarded, address("127.0.0.1", 22), null))
        assertFalse(policy.canConnect(Forwarded, address("10.0.0.1", 47002), null))
    }

    /**
     * The data plane may be bound to the phone's LAN address, which the mesh
     * needs. The forward has to land there — a loopback-only rule reported the
     * tunnel as connected and then dropped every connection (D111).
     */
    @Test
    fun `a data plane on the lan address may be reached there`() {
        val lan = SshForwardingPolicy("192.168.1.50" to 47002)
        assertTrue(lan.canConnect(Forwarded, address("192.168.1.50", 47002), null))
        assertFalse(lan.canConnect(Forwarded, address("192.168.1.51", 47002), null))
        assertFalse(lan.canConnect(Forwarded, address("127.0.0.1", 47002), null))
        assertFalse(lan.canConnect(Forwarded, address("192.168.1.50", 22), null))
    }

    /** With no data plane there is no endpoint, and nothing may be reached. */
    @Test
    fun `no local endpoint accepts nothing`() {
        val none = SshForwardingPolicy(null)
        assertFalse(none.canConnect(Forwarded, address("127.0.0.1", 47002), null))
    }

    @Test
    fun `direct channels agent and x11 are all refused`() {
        assertFalse(policy.canConnect(TcpForwardingFilter.Type.Direct, address("127.0.0.1", 47002), null))
        assertFalse(policy.canForwardAgent(null, "auth-agent-req@openssh.com"))
        assertFalse(policy.canForwardX11(null, "x11-req"))
        assertFalse(policy.canListen(address("127.0.0.1", 47002), null))
    }

    private fun address(host: String, port: Int): SshdSocketAddress = SshdSocketAddress(host, port)

    private companion object {
        val Forwarded: TcpForwardingFilter.Type = TcpForwardingFilter.Type.Forwarded
    }
}
