// SPDX-License-Identifier: Apache-2.0
package app.grom.server.ssh

import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.server.forward.TcpForwardingFilter
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The in-process reverse SSH tunnel (D58).
 *
 * Grom is the SSH *client*. It opens a session to an operator-owned host and
 * requests exactly one thing: a remote `tcpip-forward` from that host back to
 * the local data plane. It never opens a shell, an exec channel, a subsystem,
 * agent forwarding or a local/dynamic forward, so the session cannot be used to
 * run anything on the VPS or to reach anything except the Grom port.
 *
 * The host key is pinned on first use (TOFU). A pinned key that changes is a
 * hard refusal (`HOST_KEY_MISMATCH`); the operator must clear the pin
 * deliberately. A target with no pin is refused at connect unless the caller
 * passes the fingerprint it saw, which is how the UI asks for confirmation.
 *
 * Nothing here is optimistic: `CONNECTED` is set only after the server has
 * acknowledged the remote forward. Every other path reports a stable code.
 */
class SshTunnelManager(
    private val store: SshTargetStore,
    private val audit: (String, String) -> Unit,
    private val logger: (String) -> Unit,
    /**
     * Where the remote port should be forwarded to on this device. Null means
     * the data plane is not running, in which case a tunnel would be a lie.
     */
    private val localTarget: () -> Pair<String, Int>?,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    enum class State(val wireName: String) {
        DISCONNECTED("disconnected"),
        CONNECTING("connecting"),
        CONNECTED("connected"),
        FAILED("failed")
    }

    /** A snapshot of one target, for the UI or for the authenticated status. */
    data class TunnelInfo(
        val id: String,
        val label: String,
        val host: String,
        val port: Int,
        val username: String,
        val forwardPort: Int,
        val state: State,
        val sinceMs: Long,
        val lastError: String?,
        val hostKeySha256: String?
    )

    /**
     * Where an attempt stopped (D105).
     *
     * The failure code is derived from the stage that threw, not from the
     * exception class. MINA raises `SshException` — which *is* an `IOException`
     * — for a rejected password, so mapping by type reported "network
     * unreachable" for a wrong password and sent the operator hunting for a
     * firewall that was never the problem.
     */
    enum class Stage(val wireName: String) {
        RESOLVE("resolve"),
        CONNECT("connect"),
        HANDSHAKE("handshake"),
        AUTH("auth"),
        FORWARD("forward")
    }

    sealed class Result {
        data class Connected(val fingerprint: String, val learned: Boolean) : Result()
        data class HostKeyUnverified(val fingerprint: String) : Result()
        data class HostKeyMismatch(val pinned: String, val observed: String) : Result()
        data class Failed(
            val code: String,
            val message: String,
            val stage: Stage = Stage.AUTH
        ) : Result()

        /**
         * A credential check that did not need the data plane (D106).
         * [forwardChecked] says whether the reverse port was actually
         * requested; with the data plane off it is `false`, never "ok".
         */
        data class Verified(
            val fingerprint: String,
            val learned: Boolean,
            val forwardChecked: Boolean
        ) : Result()
    }

    /** What `open` is for: a live tunnel, or a one-shot credential check. */
    private enum class Mode { FORWARD, VERIFY }

    private class Active(
        val target: SshTarget,
        val client: SshClient,
        val session: ClientSession,
        val sinceMs: Long,
        @Volatile var stoppedByOperator: Boolean = false
    )

    private val admission = Any()
    private val active = ConcurrentHashMap<String, Active>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    /** One credential check at a time per target; it opens its own session. */
    private val verifying = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var failures: Map<String, Pair<String, Long>> = emptyMap()

    fun state(id: String): State = when {
        active.containsKey(id) -> State.CONNECTED
        connecting.contains(id) -> State.CONNECTING
        failures.containsKey(id) -> State.FAILED
        else -> State.DISCONNECTED
    }

    fun isActive(id: String): Boolean = active.containsKey(id)

    fun activeCount(): Int = active.size

    /** For the phone: includes the host and the user. Never leaves the device. */
    fun statusForUi(): List<Map<String, Any?>> {
        val now = clock()
        val activeId = store.activeId()
        // The operator's current server is shown first, and the rest keep their
        // saved order (D107). Sorting in the projection rather than in the store
        // keeps the file in the order the operator created it.
        val targets = store.list().sortedBy { if (it.id == activeId) 0 else 1 }
        return targets.map { target ->
            val current = active[target.id]
            val failure = failures[target.id]
            linkedMapOf(
                "id" to target.id,
                "label" to target.label,
                "host" to target.host,
                "port" to target.port,
                "username" to target.username,
                "auth" to target.auth.wireName,
                "configured" to target.isConfigured(),
                "forwardPort" to target.remoteForwardPort,
                "hostKeySha256" to target.hostKeySha256,
                "publicKeyLine" to target.publicKeyLine,
                "state" to state(target.id).wireName,
                "sinceMs" to (current?.sinceMs ?: failure?.second ?: 0L),
                "lastError" to failure?.first,
                "current" to (target.id == activeId),
                "nowMs" to now
            )
        }
    }

    /**
     * For `/v1/status`: label and measured state only. The host, the user and
     * the fingerprint stay on the device; the remote bridge does not need them.
     */
    fun statusForServer(): Map<String, Any?> {
        val activeId = store.activeId()
        val saved = store.list()
        val tunnels = saved.map { target ->
            val current = active[target.id]
            linkedMapOf<String, Any?>(
                "id" to target.id,
                "label" to target.label,
                "state" to state(target.id).wireName,
                "sinceMs" to (current?.sinceMs ?: failures[target.id]?.second ?: 0L),
                "lastError" to failures[target.id]?.first,
                // Which VPS is the operator's current one. The id is opaque and
                // already in this projection; no address is added (D59).
                "current" to (target.id == activeId)
            )
        }
        val anyConnected = tunnels.any { it["state"] == State.CONNECTED.wireName }
        return linkedMapOf(
            "configured" to saved.isNotEmpty(),
            "connected" to anyConnected,
            "active" to active.size,
            "tunnels" to tunnels
        )
    }

    // ------------------------------------------------------------------ connect

    /**
     * Blocking; call from a background thread. [trustHostKey] is the fingerprint
     * the operator confirmed for an unpinned target.
     */
    fun connect(id: String, trustHostKey: String? = null): Result {
        val target = store.find(id)
            ?: return Result.Failed("TARGET_NOT_FOUND", "no SSH target with that id")
        val problem = preflight(target)
        if (problem != null) return problem

        synchronized(admission) {
            if (active.containsKey(id)) {
                return Result.Failed("ALREADY_CONNECTED", "this tunnel is already connected")
            }
            if (!connecting.add(id)) {
                return Result.Failed("CONNECTING", "a connection attempt is already running")
            }
            if (active.size + connecting.size > SshTarget.MAX_ACTIVE) {
                connecting.remove(id)
                return Result.Failed(
                    "TOO_MANY_TUNNELS",
                    "at most ${SshTarget.MAX_ACTIVE} tunnels may be active at once"
                )
            }
        }

        try {
            // The forward needs somewhere to land, so a connect without the data
            // plane is refused *before* a socket exists. `verify` is the call
            // that proves the credentials without it (D106).
            val local = localTarget()
                ?: return fail(
                    id,
                    "DATA_PLANE_NOT_RUNNING",
                    "the public data plane is off, so there is nothing to forward to; " +
                        "use Test connection to check the server and password"
                )
            return open(target, local, trustHostKey, Mode.FORWARD)
        } finally {
            connecting.remove(id)
        }
    }

    /**
     * Checks one saved server without changing anything that is running
     * (D106): resolve, connect, handshake, host key and authentication are
     * always attempted. The reverse forward is attempted only when the data
     * plane is up, and the result says which of the two happened.
     *
     * It writes only what the operator asked for: a confirmed host key becomes
     * the pin, exactly as a first connect would.
     */
    fun verify(id: String, trustHostKey: String? = null): Result {
        val target = store.find(id)
            ?: return Result.Failed("TARGET_NOT_FOUND", "no SSH target with that id")
        val problem = preflight(target)
        if (problem != null) return problem
        if (!verifying.add(id)) {
            return Result.Failed("CONNECTING", "a connection attempt is already running")
        }
        return try {
            open(target, localTarget(), trustHostKey, Mode.VERIFY)
        } finally {
            verifying.remove(id)
        }
    }

    /** Shared refusals that need no socket. */
    private fun preflight(target: SshTarget): Result.Failed? {
        if (!target.enabled) {
            return Result.Failed("TARGET_DISABLED", "this target is disabled")
        }
        if (!target.isConfigured()) {
            return Result.Failed(
                if (target.auth == SshAuth.KEY) "KEY_MISSING" else "PASSWORD_MISSING",
                "no usable credential is stored for this target"
            )
        }
        if (target.auth == SshAuth.KEY && !SshKeys.isSupported()) {
            return Result.Failed(
                "SSH_UNAVAILABLE",
                "this build has no Ed25519 provider; key authentication cannot be used"
            )
        }
        return null
    }

    private fun open(
        target: SshTarget,
        local: Pair<String, Int>?,
        trustHostKey: String?,
        mode: Mode
    ): Result {
        val secret = store.openSecret(target)
            ?: return fail(
                target.id,
                if (target.auth == SshAuth.KEY) "KEY_MISSING" else "PASSWORD_MISSING",
                "no usable credential is stored for this target"
            )
        val pairs = if (target.auth == SshAuth.KEY) {
            SshKeys.keyPairFromPrivateText(secret)?.let { listOf(it) }
                ?: return fail(target.id, "KEY_INVALID", "the stored private key cannot be parsed")
        } else {
            null
        }

        val observed = AtomicReference<String?>(null)
        val mismatch = AtomicReference(false)
        // The stage is the diagnosis (D105). It is advanced immediately before
        // the call that can fail, so the code names what actually broke. A
        // reference rather than a local keeps the write on the path that throws
        // — the compiler cannot see that a Java call may unwind, and would
        // otherwise fold the assignment away as dead.
        val stage = AtomicReference(Stage.RESOLVE)

        val client = SshClient.setUpDefaultClient()
        // MINA rejects an incoming forwarded-tcpip channel when the client has no
        // forwarding filter, and an accept-all filter would let a compromised
        // server reach any port on the phone. This policy allows exactly one
        // destination: the data plane we asked to expose (D58). A port of -1
        // matches nothing, which is what a verify without a data plane wants.
        client.forwardingFilter = SshForwardingPolicy(local?.second ?: -1)
        client.serverKeyVerifier = ServerKeyVerifier { _, _, key: PublicKey ->
            val fingerprint = SshKeys.fingerprint(key)
            observed.set(fingerprint)
            val pinned = target.hostKeySha256
            val trust = trustHostKey?.trim()
            val accepted = when {
                pinned != null -> constantTimeEquals(pinned, fingerprint)
                trust != null -> constantTimeEquals(trust, fingerprint)
                else -> false
            }
            // Only a key that was checked and did not match is a mismatch. The
            // old form set this only for a pin, so an operator who confirmed the
            // fingerprint on first use and then typed a wrong password was told
            // "the host key changed" — a sentence that describes a machine
            // substitution and is the opposite of what happened (D105).
            if (!accepted && (pinned != null || trust != null)) mismatch.set(true)
            accepted
        }

        var session: ClientSession? = null
        try {
            client.start()
            // A name that does not resolve is a typo, not a firewall, and it
            // gets its own code. MINA folds the lookup into its connect future
            // and wraps the cause, so the exception carries nothing this code
            // can classify — the lookup is therefore done once, here, and its
            // failure is reported as the form problem it is (D105). The bind
            // path still never resolves (S5-R005); this is a dialled target the
            // operator typed.
            val resolved = try {
                java.net.InetAddress.getByName(target.host)
            } catch (dns: Throwable) {
                runCatching { client.stop() }
                return fail(
                    id = target.id,
                    code = "SSH_HOST_UNKNOWN",
                    message = "that host name could not be resolved; " +
                        "check the spelling or use the IP address",
                    stage = Stage.RESOLVE
                )
            }
            stage.set(Stage.CONNECT)
            val connect = client.connect(target.username, resolved.hostAddress, target.port)
            // The TCP connection is up before this; what can fail now is the key
            // exchange and the host-key check, which is a different diagnosis.
            stage.set(Stage.HANDSHAKE)
            connect.verify(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val established: ClientSession = connect.session
            session = established

            stage.set(Stage.AUTH)
            if (pairs != null) {
                established.keyIdentityProvider = KeyIdentityProvider { pairs }
            } else {
                established.addPasswordIdentity(secret)
                // OpenSSH with PAM answers a password login as a single
                // non-echoing keyboard-interactive prompt, which is not the
                // `password` method at all. Without a handler the challenge is
                // never answered and a correct password fails as "exhausted
                // methods" (D105).
                established.userInteraction = SshPasswordInteraction(secret)
            }
            established.auth().verify(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            // The only thing this session is allowed to do (D58).
            //
            // This is a separate stage because a server refusing the forward is
            // a different problem from a network failure: the port is already in
            // use, the server forbids forwards, or the address is not allowed.
            // Reporting `SSH_FAILED` there would send the operator to look at
            // the network (D78).
            // A live tunnel already holds this target's remote port, so asking
            // for it again would be refused by our own session and reported as
            // "the server refused the forward port" — the opposite of the truth.
            // The forward is therefore only requested when this target is not
            // already up, and `forwardChecked` says which happened.
            val forwardChecked = local != null && !active.containsKey(target.id)
            if (local != null && !active.containsKey(target.id)) {
                stage.set(Stage.FORWARD)
                try {
                    established.startRemotePortForwarding(
                        SshdSocketAddress("127.0.0.1", target.remoteForwardPort),
                        SshdSocketAddress(local.first, local.second)
                    )
                } catch (refused: Throwable) {
                    runCatching { established.close(true) }
                    runCatching { client.stop() }
                    return fail(
                        id = target.id,
                        code = "SSH_FORWARD_REFUSED",
                        message = "the server refused the remote forward port " +
                            "${target.remoteForwardPort} (is it already in use?)",
                        stage = Stage.FORWARD
                    )
                }
            }

            val fingerprint = observed.get() ?: "SHA256:unavailable"
            val learned = target.hostKeySha256 == null
            if (learned) {
                store.pinHostKey(target.id, fingerprint)
                audit("ssh.hostkey.learned", "target=${target.id}")
            }

            if (mode == Mode.VERIFY) {
                // Nothing is left running: a check must not change the state the
                // operator is looking at.
                runCatching { established.close(false) }
                runCatching { client.stop() }
                // A check that has just proved the credentials is a measurement,
                // so it clears the failure the row would otherwise keep showing:
                // a green sentence above a red lamp is exactly the dishonesty
                // this screen exists to avoid.
                failures = failures - target.id
                audit(
                    "ssh.verify",
                    "target=${target.id} ok=true forward=$forwardChecked"
                )
                return Result.Verified(fingerprint, learned, forwardChecked)
            }

            val current = Active(target, client, established, clock())
            active[target.id] = current
            failures = failures - target.id
            established.addSessionListener(object : SessionListener {
                override fun sessionClosed(closed: Session) {
                    val gone = active.remove(target.id) ?: return
                    runCatching { gone.client.stop() }
                    if (!gone.stoppedByOperator) {
                        failures = failures + (target.id to ("SSH_SESSION_CLOSED" to clock()))
                        audit("ssh.closed", "target=${target.id}")
                    }
                }
            })
            audit(
                "ssh.connect",
                "target=${target.id} host=${target.host}:${target.port} " +
                    "forward=127.0.0.1:${target.remoteForwardPort}"
            )
            logger("ssh tunnel up: ${target.label} -> 127.0.0.1:${target.remoteForwardPort}")
            return Result.Connected(fingerprint, learned)
        } catch (failure: Throwable) {
            runCatching { session?.close(true) }
            runCatching { client.stop() }
            val fingerprint = observed.get()
            if (fingerprint != null) {
                val pinned = target.hostKeySha256
                if (pinned == null && trustHostKey == null) {
                    audit("ssh.hostkey.unverified", "target=${target.id}")
                    return Result.HostKeyUnverified(fingerprint)
                }
                // The verifier is the only thing allowed to claim a mismatch:
                // it says a key was compared and differed. Any other failure
                // after a confirmed handshake is diagnosed by its stage.
                if (mismatch.get()) {
                    val code = "HOST_KEY_MISMATCH"
                    failures = failures + (target.id to (code to clock()))
                    audit("ssh.hostkey.mismatch", "target=${target.id}")
                    return Result.HostKeyMismatch(pinned ?: trustHostKey ?: "", fingerprint)
                }
            }
            val diagnosis = diagnose(failure, stage.get())
            return fail(target.id, diagnosis.code, diagnosis.message, diagnosis.stage)
        }
    }

    fun disconnect(id: String) {
        val current = active.remove(id) ?: return
        current.stoppedByOperator = true
        runCatching { current.session.close(true) }
        runCatching { current.client.stop() }
        failures = failures - id
        audit("ssh.disconnect", "target=$id")
        logger("ssh tunnel down: ${current.target.label}")
    }

    /** Stops every tunnel; used when the server stops or the app exits. */
    fun stopAll() {
        for (id in active.keys.toList()) {
            disconnect(id)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun fail(
        id: String,
        code: String,
        message: String,
        stage: Stage = Stage.AUTH
    ): Result {
        failures = failures + (id to (code to clock()))
        audit("ssh.failed", "target=$id code=$code stage=${stage.wireName}")
        logger("ssh failed: target=$id code=$code stage=${stage.wireName}")
        return Result.Failed(code, message, stage)
    }

    /**
     * A diagnosis: a stable code, a sentence an operator can act on, and the
     * stage it really belongs to (a refused TCP connect surfaces from MINA
     * during session verification, so the raw stage would say "handshake").
     */
    private data class Diagnosis(val code: String, val message: String, val stage: Stage)

    /**
     * Turns a failure into a code by the stage that threw (D105).
     *
     * The stages are advanced immediately before each call, so this never has
     * to guess from an exception class — and the driver's own message is never
     * used, because it can carry the host, the user name or a key fragment.
     */
    private fun diagnose(failure: Throwable, stage: Stage): Diagnosis = when (stage) {
        Stage.RESOLVE -> Diagnosis(
            "SSH_HOST_UNKNOWN",
            "that host name could not be resolved; check the spelling or use the IP address",
            Stage.RESOLVE
        )
        // MINA reports a refused TCP connection when the connect future is
        // verified, which is also where the handshake happens — so the stage
        // alone cannot tell them apart. The cause chain can: a refused port or a
        // dead host carries `ConnectException`/`NoRouteToHostException` all the
        // way down, and a handshake failure never does.
        Stage.CONNECT, Stage.HANDSHAKE -> when {
            hasCause<java.net.UnknownHostException>(failure) -> Diagnosis(
                "SSH_HOST_UNKNOWN",
                "that host name could not be resolved; " +
                    "check the spelling or use the IP address",
                Stage.RESOLVE
            )
            hasCause<java.net.NoRouteToHostException>(failure) ||
                hasCause<java.net.ConnectException>(failure) -> Diagnosis(
                "SSH_HOST_UNREACHABLE",
                "nothing accepted a connection on that address and port; " +
                    "is sshd running, and is the port open in the firewall?",
                Stage.CONNECT
            )
            hasCause<java.net.SocketTimeoutException>(failure) ||
                hasCause<java.util.concurrent.TimeoutException>(failure) -> Diagnosis(
                "SSH_TIMEOUT",
                "no answer in time; a firewall or the wrong port is the usual cause",
                Stage.CONNECT
            )
            stage == Stage.CONNECT -> Diagnosis(
                "SSH_HOST_UNREACHABLE",
                "the connection could not be established; check the address and port",
                Stage.CONNECT
            )
            else -> Diagnosis(
                "SSH_HANDSHAKE_FAILED",
                "the host answered but the SSH handshake did not complete " +
                    "(a proxy, a blocked port or a very old server)",
                Stage.HANDSHAKE
            )
        }
        Stage.AUTH -> Diagnosis(
            "SSH_AUTH_FAILED",
            "the server rejected the username or the password",
            Stage.AUTH
        )
        Stage.FORWARD -> Diagnosis(
            "SSH_FORWARD_REFUSED",
            "the server refused the remote forward port",
            Stage.FORWARD
        )
    }

    /** True when [type] appears anywhere in the cause chain (bounded walk). */
    private inline fun <reified T : Throwable> hasCause(failure: Throwable): Boolean {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < 8) {
            if (current is T) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(Charsets.UTF_8),
            right.toByteArray(Charsets.UTF_8)
        )

    companion object {
        private const val CONNECT_TIMEOUT_MS: Long = 15_000
        private const val AUTH_TIMEOUT_MS: Long = 15_000
    }
}

/**
 * The client-side forwarding policy of the SSH bridge (D58).
 *
 * It answers `true` for one thing only: a `forwarded-tcpip` channel addressed to
 * loopback on the port of the local data plane. Agent forwarding, X11, local
 * listens and every other destination are refused, so a malicious or
 * misconfigured server cannot use the session to reach another service on the
 * phone. This class is pure and unit-tested without a network.
 */
internal class SshForwardingPolicy(private val localPort: Int) : ForwardingFilter {

    override fun canForwardAgent(session: Session?, requestType: String?): Boolean = false

    override fun canForwardX11(session: Session?, requestType: String?): Boolean = false

    override fun canListen(address: SshdSocketAddress?, session: Session?): Boolean = false

    override fun canConnect(
        type: TcpForwardingFilter.Type?,
        address: SshdSocketAddress?,
        session: Session?
    ): Boolean {
        if (type != TcpForwardingFilter.Type.Forwarded || address == null) return false
        if (address.port != localPort) return false
        val host = address.hostName?.lowercase() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }
}
