// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.superserver.auth.Ed25519
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The pairing transcript (D71).
 *
 * Pairing is mutual and explicit: one device shows a short code, the other
 * proves possession of its own key with a signature, and both operators compare
 * the other side's fingerprint before anything is stored. There is no
 * "auto-accept" path here, and there is no way to pair by knowing a node id.
 *
 * Every signed string is domain-separated (its own `grom-pair-*-v1` prefix) so a
 * transcript from one protocol step can never be replayed as another.
 */
object PairingProtocol {

    const val SESSION_TTL_MS: Long = 300_000
    const val PENDING_TTL_MS: Long = 300_000
    const val MAX_ATTEMPTS: Int = 5

    const val CODE_LENGTH: Int = 8
    const val CODE_ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Crockford's alphabet without I, L, O, U — 40 bits of a typed code. */
    val CODE_PATTERN: Regex = Regex("^[0-9A-HJKMNP-TV-Z]{$CODE_LENGTH}$")
    val CODE_HASH_PATTERN: Regex = Regex("^[0-9a-f]{64}$")

    fun helloString(aNodeId: String, bNodeId: String, codeHash: String): String =
        "grom-pair-hello-v1\n$aNodeId\n$bNodeId\n$codeHash"

    fun acceptString(
        aNodeId: String,
        bNodeId: String,
        codeHash: String,
        scopes: List<String>
    ): String = "grom-pair-accept-v1\n$aNodeId\n$bNodeId\n$codeHash\n${scopes.joinToString(",")}"

    /**
     * Binds the code to the joining device: the same code shown to two devices
     * produces two different hashes, so a hash observed in transit is useless
     * to a third device.
     */
    fun computeCodeHash(code: String, bNodeId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("grom-pair-v1\n$code\n$bNodeId".toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    fun randomCode(random: SecureRandom): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) {
            append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)])
        }
    }

    fun randomId(random: SecureRandom): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * One short-lived pairing session on the device that shows the code.
 *
 * At most one session exists at a time, it expires after
 * [PairingProtocol.SESSION_TTL_MS], and a wrong code is counted: after
 * [PairingProtocol.MAX_ATTEMPTS] the session closes instead of allowing an
 * unlimited guess. The code itself is held in memory only and never written.
 */
class PairingSession(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom()
) {

    data class Opened(val code: String, val expiresAtMs: Long)

    sealed class Verify {
        data object Ok : Verify()
        data class Mismatch(val attemptsLeft: Int) : Verify()
        data object Expired : Verify()
        data object Closed : Verify()
    }

    private var code: String? = null
    private var expiresAtMs: Long = 0L
    private var attempts: Int = 0

    @Synchronized
    fun open(): Opened {
        val fresh = PairingProtocol.randomCode(random)
        code = fresh
        expiresAtMs = clock() + PairingProtocol.SESSION_TTL_MS
        attempts = 0
        return Opened(fresh, expiresAtMs)
    }

    @Synchronized
    fun close() {
        code = null
        expiresAtMs = 0L
        attempts = 0
    }

    @Synchronized
    fun isOpen(): Boolean = active()

    /**
     * Compares the hash supplied by the joining device with the hash of the
     * current code bound to that device. The comparison is constant-time and
     * every mismatch spends one attempt.
     */
    @Synchronized
    fun verifyHash(codeHash: String, peerNodeId: String): Verify {
        val current = code ?: return Verify.Closed
        if (!active()) {
            close()
            return Verify.Expired
        }
        if (!PairingProtocol.CODE_HASH_PATTERN.matches(codeHash)) {
            return spendAttempt()
        }
        val expected = PairingProtocol.computeCodeHash(current, peerNodeId)
        if (!MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                codeHash.toByteArray(Charsets.UTF_8)
            )
        ) {
            return spendAttempt()
        }
        return Verify.Ok
    }

    private fun spendAttempt(): Verify {
        attempts += 1
        if (attempts >= PairingProtocol.MAX_ATTEMPTS) {
            close()
            return Verify.Closed
        }
        return Verify.Mismatch(PairingProtocol.MAX_ATTEMPTS - attempts)
    }

    private fun active(): Boolean = code != null && clock() < expiresAtMs
}

/** A pre-authenticated pairing route answer: status plus a JSON payload. */
data class PairAnswer(val status: Int, val payload: Map<String, Any?>)

/**
 * The pairing routes the data plane exposes while a session is open (D71).
 *
 * They are intentionally the *only* endpoints reachable without a credential.
 * Every one of them answers `PAIR_SESSION_CLOSED` when no operator has opened a
 * session, so a closed device exposes no pairing surface at all.
 */
interface PairingPort {

    /** The device's public identity, or null when no session is open. */
    fun identityDocument(): Map<String, Any?>?

    fun hello(body: Map<String, Any?>, client: String): PairAnswer

    fun poll(body: Map<String, Any?>): PairAnswer

    companion object {

        val DISABLED: PairingPort = object : PairingPort {
            override fun identityDocument(): Map<String, Any?>? = null

            override fun hello(body: Map<String, Any?>, client: String): PairAnswer =
                PairAnswer(409, error("PAIR_SESSION_CLOSED", "no pairing session is open"))

            override fun poll(body: Map<String, Any?>): PairAnswer =
                PairAnswer(404, error("PAIR_UNKNOWN", "no such pairing request"))
        }

        fun error(code: String, message: String): Map<String, Any?> = mapOf(
            "ok" to false,
            "error" to mapOf("code" to code, "message" to message)
        )
    }
}

/**
 * The stateful side of pairing on the device that shows the code (D71, D73).
 *
 * A hello is accepted only when (1) a session is open, (2) the joining device
 * proves possession of the key it claims, (3) the code hash matches this
 * session *and* that device, and (4) the operator then explicitly approves with
 * a scope subset. Approval writes the peer and returns a signed acceptance the
 * joining device must verify against this device's fingerprint.
 */
class PairingEndpoint(
    private val identity: () -> NodeIdentity?,
    private val seed: () -> ByteArray?,
    private val peers: PeerStore,
    private val session: PairingSession = PairingSession(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
    private val audit: (String, String) -> Unit = { _, _ -> }
) : PairingPort {

    private data class Pending(
        val pendingId: String,
        val nodeId: String,
        val publicKey: String,
        val label: String,
        val address: String,
        val requestedScopes: Set<PeerScope>,
        val codeHash: String,
        val expiresAtMs: Long,
        var decision: Map<String, Any?>? = null
    )

    private var pending: Pending? = null

    // ------------------------------------------------------------ operator side

    /** Opens a session and returns the code to show. */
    @Synchronized
    fun start(): Map<String, Any?> {
        val self = identity()
            ?: throw ApiException(409, "IDENTITY_MISSING", "the device identity is not available")
        pending = null
        val opened = session.open()
        audit("pair.open", "")
        return linkedMapOf(
            "ok" to true,
            "code" to opened.code,
            "expiresAtMs" to opened.expiresAtMs,
            "nodeId" to self.nodeId,
            "fingerprint" to self.fingerprint()
        )
    }

    /** The joining device waiting for this operator's decision, if any. */
    @Synchronized
    fun pendingDocument(): Map<String, Any?> = pendingDocumentLocked()

    /**
     * Approves or rejects the waiting device. Approval requires a non-empty
     * subset of what the peer asked for; an empty grant is refused because a
     * pairing without a scope would be a connection that can do nothing.
     */
    @Synchronized
    fun decide(accept: Boolean, scopes: Set<PeerScope>?): Map<String, Any?> {
        val current = pending
            ?: throw ApiException(404, "PAIR_NOT_PENDING", "no device is waiting to pair")
        if (clock() >= current.expiresAtMs) {
            pending = null
            throw ApiException(409, "PAIR_EXPIRED", "the pairing request has expired")
        }
        if (!accept) {
            current.decision = mapOf("state" to "rejected")
            audit("pair.reject", "peer=${current.nodeId}")
            return linkedMapOf("ok" to true, "state" to "rejected")
        }
        val granted = scopes ?: throw ApiException(400, "SCOPE_REQUIRED", "choose at least one scope")
        if (granted.isEmpty()) {
            throw ApiException(400, "SCOPE_REQUIRED", "choose at least one scope")
        }
        if (!current.requestedScopes.containsAll(granted)) {
            throw ApiException(400, "BAD_SCOPE", "a scope was not requested by the peer")
        }

        val self = identity()
            ?: throw ApiException(409, "IDENTITY_MISSING", "the device identity is not available")
        val secret = seed()
            ?: throw ApiException(409, "IDENTITY_CORRUPT", "the device identity cannot be used")

        val names = PeerScope.wireNames(granted)
        val transcript = PairingProtocol.acceptString(
            self.nodeId,
            current.nodeId,
            current.codeHash,
            names
        )
        val signature = Ed25519.sign(secret, transcript.toByteArray(Charsets.UTF_8))

        val record = PeerRecord(
            nodeId = current.nodeId,
            publicKey = current.publicKey,
            label = current.label,
            address = current.address,
            grantedByMe = granted,
            grantedByPeer = granted,
            pairedAtMs = clock()
        )
        peers.add(record)
        current.decision = linkedMapOf(
            "state" to "accepted",
            "nodeId" to self.nodeId,
            "publicKey" to self.publicKey,
            "label" to self.label,
            "fingerprint" to self.fingerprint(),
            "grantedScopes" to names,
            "codeHash" to current.codeHash,
            "signature" to Base64.getEncoder().encodeToString(signature)
        )
        audit("pair.accept", "peer=${current.nodeId}")
        return linkedMapOf(
            "ok" to true,
            "state" to "accepted",
            "peer" to peerDocument(record)
        )
    }

    /** Closes everything pairing-related; called when the data plane stops. */
    @Synchronized
    fun close() {
        session.close()
        pending = null
    }

    // ---------------------------------------------------------- joining side

    override fun identityDocument(): Map<String, Any?>? {
        synchronized(this) {
            if (!session.isOpen()) return null
            return identity()?.toJson()
        }
    }

    override fun hello(body: Map<String, Any?>, client: String): PairAnswer {
        synchronized(this) {
            val self = identity()
                ?: return PairAnswer(409, PairingPort.error("IDENTITY_MISSING", "no identity"))
            if (!session.isOpen()) {
                return PairAnswer(
                    409,
                    PairingPort.error("PAIR_SESSION_CLOSED", "no pairing session is open")
                )
            }
            val nodeId = (body["nodeId"] as? String)?.trim().orEmpty()
            val publicKeyText = (body["publicKey"] as? String)?.trim().orEmpty()
            val label = NodeIdentity.normalizeLabel(body["label"] as? String)
            // The joining device reports the port it listens on; the reachable
            // host is the address this request actually arrived from. That is
            // something the peer cannot fake and the operator does not have to
            // type twice.
            val port = (body["port"] as? Number)?.toInt()
                ?: return badRequest("PAIR_BAD_ADDRESS", "the listening port is missing")
            if (port !in 1..65535) {
                return badRequest("PAIR_BAD_ADDRESS", "the listening port is invalid")
            }
            val sourceHost = client.substringBeforeLast(':', client).trim()
            val address = PeerAddress.normalize("http://$sourceHost:$port")
                ?: return badRequest("PAIR_BAD_ADDRESS", "the peer source address is unusable")
            val codeHash = (body["codeHash"] as? String)?.trim().orEmpty()
            val signatureText = (body["signature"] as? String)?.trim().orEmpty()
            val scopes = PeerScope.fromJson(body["scopes"])
                ?: return badRequest("BAD_SCOPE", "the requested scopes are malformed")

            if (!NodeIdentity.NODE_ID_PATTERN.matches(nodeId)) {
                return unauthorized("PAIR_BAD_IDENTITY", "the peer identity is malformed")
            }
            if (nodeId == self.nodeId) {
                return unauthorized("PAIR_SELF", "a device cannot pair with itself")
            }
            val publicKey = decodePublicKey(publicKeyText)
                ?: return unauthorized("PAIR_BAD_IDENTITY", "the peer key is malformed")
            if (Ed25519.keyId(publicKey) != nodeId) {
                return unauthorized("PAIR_BAD_IDENTITY", "the peer key does not match its id")
            }
            if (scopes.isEmpty()) {
                return badRequest("SCOPE_REQUIRED", "the peer requested no scope")
            }
            // D104: only peer grants cross a pairing. A request naming a
            // local-client scope is refused here as well as on the joining
            // side, so a hand-written hello cannot put a permission the
            // operator cannot interpret in front of the approval screen.
            if (scopes.any { it !in PeerScope.PEER_GRANTABLE }) {
                return badRequest("BAD_SCOPE", "a pairing cannot grant a local-client scope")
            }

            // Prove the caller holds the key first: only a device that can sign
            // gets to spend a code attempt.
            val hello = PairingProtocol.helloString(self.nodeId, nodeId, codeHash)
            val signature = runCatching { Base64.getDecoder().decode(signatureText) }.getOrNull()
            if (signature == null || signature.size != Ed25519.SIGNATURE_BYTES ||
                !Ed25519.verify(publicKey, hello.toByteArray(Charsets.UTF_8), signature)
            ) {
                return unauthorized("PAIR_BAD_SIGNATURE", "the joining device did not sign")
            }

            when (session.verifyHash(codeHash, nodeId)) {
                is PairingSession.Verify.Ok -> Unit
                is PairingSession.Verify.Mismatch ->
                    return unauthorized("PAIR_BAD_CODE", "the code does not match")
                is PairingSession.Verify.Expired ->
                    return PairAnswer(
                        409,
                        PairingPort.error("PAIR_EXPIRED", "the pairing session expired")
                    )
                is PairingSession.Verify.Closed ->
                    return PairAnswer(
                        409,
                        PairingPort.error(
                            "PAIR_SESSION_CLOSED",
                            "the pairing session is closed"
                        )
                    )
            }

            val existing = pending
            if (existing != null && clock() < existing.expiresAtMs) {
                return PairAnswer(
                    409,
                    PairingPort.error("PAIR_BUSY", "another device is already waiting")
                )
            }

            val created = Pending(
                pendingId = PairingProtocol.randomId(random),
                nodeId = nodeId,
                publicKey = publicKeyText,
                label = label,
                address = address,
                requestedScopes = scopes,
                codeHash = codeHash,
                expiresAtMs = clock() + PairingProtocol.PENDING_TTL_MS
            )
            pending = created
            audit("pair.hello", "peer=$nodeId")
            return PairAnswer(
                200,
                linkedMapOf(
                    "ok" to true,
                    "state" to "pending",
                    "pendingId" to created.pendingId,
                    "expiresAtMs" to created.expiresAtMs
                )
            )
        }
    }

    override fun poll(body: Map<String, Any?>): PairAnswer {
        synchronized(this) {
            val pendingId = (body["pendingId"] as? String)?.trim().orEmpty()
            val current = pending
            if (current == null || current.pendingId != pendingId) {
                return PairAnswer(
                    404,
                    PairingPort.error("PAIR_UNKNOWN", "no such pairing request")
                )
            }
            if (clock() >= current.expiresAtMs) {
                pending = null
                return PairAnswer(404, PairingPort.error("PAIR_EXPIRED", "the request expired"))
            }
            val decision = current.decision
                ?: return PairAnswer(200, mapOf("ok" to true, "state" to "pending"))
            return PairAnswer(200, decision)
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun pendingDocumentLocked(): Map<String, Any?> {
        val current = pending ?: return emptyMap()
        if (clock() >= current.expiresAtMs) {
            pending = null
            return emptyMap()
        }
        return linkedMapOf(
            "nodeId" to current.nodeId,
            "publicKey" to current.publicKey,
            "label" to current.label,
            "fingerprint" to NodeIdentity.formatFingerprint(current.nodeId),
            "address" to current.address,
            "requestedScopes" to PeerScope.wireNames(current.requestedScopes),
            "expiresAtMs" to current.expiresAtMs,
            "state" to if (current.decision == null) "pending" else "decided"
        )
    }

    private fun peerDocument(record: PeerRecord): Map<String, Any?> = linkedMapOf(
        "nodeId" to record.nodeId,
        "publicKey" to record.publicKey,
        "label" to record.label,
        "fingerprint" to record.fingerprint(),
        "address" to record.address,
        "scopes" to PeerScope.wireNames(record.grantedByMe)
    )

    private fun decodePublicKey(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        return bytes.takeIf { it.size == Ed25519.PUBLIC_KEY_BYTES }
    }

    private fun badRequest(code: String, message: String): PairAnswer =
        PairAnswer(400, PairingPort.error(code, message))

    private fun unauthorized(code: String, message: String): PairAnswer =
        PairAnswer(401, PairingPort.error(code, message))
}
