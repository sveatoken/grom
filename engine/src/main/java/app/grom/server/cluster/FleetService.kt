// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.SuperAuth
import app.grom.server.superserver.auth.Ed25519
import java.util.Base64

/**
 * Orchestration for this device's fleet membership (D70-D75).
 *
 * It owns three things: the local identity, the paired-device store, and the
 * pairing handshake. It deliberately has no background loop — probes run only
 * when the operator opens the section or taps Refresh (D74), so a sleeping phone
 * never pretends to be watching anything.
 */
class FleetService(
    private val identityStore: NodeIdentityStore,
    private val store: PeerStore,
    private val client: PeerClient = PeerClient(),
    private val defaultLabel: () -> String = { NodeIdentity.DEFAULT_LABEL },
    private val localPort: () -> Int = { 0 },
    private val audit: (String, String) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val joinPollIntervalMs: Long = 2_000,
    private val joinTimeoutMs: Long = 110_000,
    private val maxRefresh: Int = 16
) {

    private data class VerifiedJoin(
        val nodeId: String,
        val publicKey: String,
        val label: String,
        val address: String,
        val scopes: Set<PeerScope>,
        val codeHash: String
    )

    private val verifiedJoins = LinkedHashMap<String, VerifiedJoin>()

    val pairing: PairingEndpoint = PairingEndpoint(
        identity = { runCatching { identity() }.getOrNull() },
        seed = { identityStore.seed() },
        peers = store,
        clock = clock,
        audit = audit
    )

    // ------------------------------------------------------------- identity

    /**
     * The identity is created on first use, never silently recreated after a
     * corrupt read: [NodeIdentityStore] throws in that case and the caller
     * reports it (D72).
     */
    @Synchronized
    fun identity(): NodeIdentity = identityStore.loadOrCreate(defaultLabel())

    fun identityOrNull(): NodeIdentity? = identityStore.identity()

    fun identityJson(): String {
        val identity = identity()
        return Json.encode(
            identity.toJson() + linkedMapOf<String, Any?>(
                "createdAtMs" to identity.createdAtMs,
                "lastError" to identityStore.lastError
            )
        )
    }

    /**
     * Replaces the identity and forgets every peer. The operator must type
     * `RESET`, because every other device will see a stranger until re-paired.
     */
    @Synchronized
    fun resetIdentity(confirmation: String): String {
        if (confirmation != RESET_CONFIRMATION) {
            throw ApiException(
                400,
                "RESET_CONFIRMATION_REQUIRED",
                "type $RESET_CONFIRMATION to replace the identity and forget every pairing"
            )
        }
        val identity = identityStore.reset(defaultLabel())
        store.clear()
        verifiedJoins.clear()
        pairing.close()
        audit("identity.reset", "node=${identity.nodeId}")
        return Json.encode(linkedMapOf("ok" to true, "identity" to identity.toJson()))
    }

    /** The sanitised fleet block: a name and an id, never an address (D59). */
    fun statusDocument(): Map<String, Any?> =
        FleetStatus.document(runCatching { identity() }.getOrNull())

    // -------------------------------------------------------------- pairing

    fun startPairingJson(): String {
        identity()
        return Json.encode(pairing.start())
    }

    fun pendingJson(): String = Json.encode(mapOf("pending" to pairing.pendingDocument()))

    fun completePairingJson(body: String): String {
        val raw = JsonParser.parseObjectOrNull(body)
            ?: throw ApiException(400, "BAD_PAIR_DECISION", "body must be a JSON object")
        val accept = raw["accept"] as? Boolean
            ?: throw ApiException(400, "BAD_PAIR_DECISION", "field accept is required")
        val scopes = if (accept) {
            PeerScope.fromJson(raw["scopes"])
                ?: throw ApiException(400, "BAD_SCOPE", "scopes must be a list of known names")
        } else {
            null
        }
        return Json.encode(pairing.decide(accept, scopes))
    }

    /**
     * Joins a device that is showing a code. It blocks while the other operator
     * decides, so callers must run it off the main thread (S4-R039).
     */
    fun joinJson(body: String): String {
        val raw = JsonParser.parseObjectOrNull(body)
            ?: throw ApiException(400, "BAD_PAIR_REQUEST", "body must be a JSON object")
        val base = PeerAddress.normalize(raw["address"] as? String)
            ?: throw ApiException(400, "BAD_PEER_ADDRESS", "enter host:port of the other device")
        val code = (raw["code"] as? String)?.trim()?.uppercase().orEmpty()
        if (!PairingProtocol.CODE_PATTERN.matches(code)) {
            throw ApiException(400, "BAD_PAIR_CODE", "the pairing code has 8 characters")
        }
        val requestedScopes = requestedPairingScopes(raw)

        val self = identity()
        val seed = identityStore.seed()
            ?: throw ApiException(409, "IDENTITY_CORRUPT", "the device identity cannot be used")
        val remote = client.identity(base)
            ?: throw ApiException(
                502,
                "PAIR_UNREACHABLE",
                "the other device did not answer; is its public API on and is the code shown?"
            )
        if (remote.nodeId == self.nodeId) {
            throw ApiException(400, "PAIR_SELF", "that is this device")
        }
        val remoteKey = Base64.getDecoder().decode(remote.publicKey)
        val codeHash = PairingProtocol.computeCodeHash(code, self.nodeId)
        val hello = PairingProtocol.helloString(remote.nodeId, self.nodeId, codeHash)
        val signature = Ed25519.sign(seed, hello.toByteArray(Charsets.UTF_8))

        val helloAnswer = client.hello(
            base,
            linkedMapOf(
                "nodeId" to self.nodeId,
                "publicKey" to self.publicKey,
                "label" to self.label,
                "port" to localPort(),
                "codeHash" to codeHash,
                "scopes" to PeerScope.wireNames(requestedScopes),
                "signature" to Base64.getEncoder().encodeToString(signature)
            )
        )
        if (helloAnswer.status != 200) {
            throw refusal(helloAnswer)
        }
        val pendingId = helloAnswer.payload["pendingId"] as? String
            ?: throw ApiException(502, "PAIR_BAD_RESPONSE", "the peer returned no request id")

        val deadline = clock() + joinTimeoutMs
        while (clock() < deadline) {
            val polled = client.poll(base, pendingId)
            when (polled.payload["state"] as? String) {
                "accepted" -> return Json.encode(accept(base, remote.nodeId, remoteKey, polled.payload, codeHash))
                "rejected" -> throw ApiException(403, "PAIR_REJECTED", "the other operator declined")
            }
            if (polled.status != 200 && polled.status != 404) {
                throw refusal(polled)
            }
            try {
                Thread.sleep(joinPollIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ApiException(503, "PAIR_INTERRUPTED", "the pairing was interrupted")
            }
        }
        throw ApiException(504, "PAIR_TIMEOUT", "the other operator did not decide in time")
    }

    /**
     * The scopes this device asks the other one to grant (D104).
     *
     * Absent keeps the pre-mesh default of `status.read` and `config.read`, so
     * `Devices` behaves exactly as before. A present list must be a non-empty
     * subset of [PeerScope.PEER_GRANTABLE]: a local-client scope (`mesh.read`,
     * `mesh.call`) is never handed to another device, and an unknown name is a
     * typo rather than something to ignore.
     */
    private fun requestedPairingScopes(raw: Map<*, *>): Set<PeerScope> {
        if (!raw.containsKey("scopes")) {
            return setOf(PeerScope.STATUS_READ, PeerScope.CONFIG_READ)
        }
        val parsed = PeerScope.fromJson(raw["scopes"])
            ?: throw ApiException(400, "BAD_SCOPE", "scopes must be a list of known names")
        if (parsed.isEmpty()) {
            throw ApiException(400, "BAD_SCOPE", "ask for at least one scope")
        }
        val refused = parsed.filterNot { it in PeerScope.PEER_GRANTABLE }
        if (refused.isNotEmpty()) {
            throw ApiException(
                400,
                "BAD_SCOPE",
                "a pairing cannot grant ${refused.sortedBy { it.wireName }.joinToString(",") { it.wireName }}"
            )
        }
        return parsed
    }

    /**
     * The operator on the joining device confirms the fingerprint. Until this
     * runs, nothing is stored: an acceptance that has not been checked on this
     * screen is not a pairing (D71).
     */
    @Synchronized
    fun confirmJson(body: String): String {
        val raw = JsonParser.parseObjectOrNull(body)
            ?: throw ApiException(400, "BAD_PAIR_CONFIRM", "body must be a JSON object")
        val nodeId = (raw["nodeId"] as? String)?.trim().orEmpty()
        val join = verifiedJoins.remove(nodeId)
            ?: throw ApiException(404, "PAIR_NOT_PENDING", "no verified pairing is waiting")
        val record = PeerRecord(
            nodeId = join.nodeId,
            publicKey = join.publicKey,
            label = join.label,
            address = join.address,
            grantedByMe = join.scopes,
            grantedByPeer = join.scopes,
            pairedAtMs = clock()
        )
        store.add(record)
        audit("pair.confirm", "peer=${record.nodeId}")
        return Json.encode(
            linkedMapOf(
                "ok" to true,
                "peer" to record.toJson(clock(), includeAddress = true)
            )
        )
    }

    private fun accept(
        address: String,
        remoteNodeId: String,
        remoteKey: ByteArray,
        payload: Map<String, Any?>,
        codeHash: String
    ): Map<String, Any?> {
        val scopes = PeerScope.fromJson(payload["grantedScopes"])
            ?: throw ApiException(502, "PAIR_BAD_RESPONSE", "the peer returned no scopes")
        if (scopes.isEmpty()) {
            throw ApiException(502, "PAIR_BAD_RESPONSE", "the peer granted no scope")
        }
        val signatureText = payload["signature"] as? String
            ?: throw ApiException(502, "PAIR_BAD_RESPONSE", "the peer did not sign the acceptance")
        val signature = runCatching { Base64.getDecoder().decode(signatureText) }.getOrNull()
            ?: throw ApiException(502, "PAIR_BAD_RESPONSE", "the acceptance signature is malformed")
        val acceptString = PairingProtocol.acceptString(
            remoteNodeId,
            identity().nodeId,
            codeHash,
            PeerScope.wireNames(scopes)
        )
        if (!Ed25519.verify(remoteKey, acceptString.toByteArray(Charsets.UTF_8), signature)) {
            throw ApiException(401, "PAIR_BAD_SIGNATURE", "the acceptance does not verify")
        }
        val label = NodeIdentity.normalizeLabel(payload["label"] as? String)
        val fingerprint = NodeIdentity.formatFingerprint(remoteNodeId)
        synchronized(this) {
            verifiedJoins[remoteNodeId] = VerifiedJoin(
                nodeId = remoteNodeId,
                publicKey = Base64.getEncoder().encodeToString(remoteKey),
                label = label,
                address = address,
                scopes = scopes,
                codeHash = codeHash
            )
        }
        return linkedMapOf(
            "ok" to true,
            "state" to "accepted",
            "peer" to linkedMapOf(
                "nodeId" to remoteNodeId,
                "publicKey" to Base64.getEncoder().encodeToString(remoteKey),
                "label" to label,
                "fingerprint" to fingerprint,
                "address" to address,
                "scopes" to PeerScope.wireNames(scopes)
            )
        )
    }

    // ---------------------------------------------------------------- peers

    fun peersJson(): String = Json.encode(
        linkedMapOf(
            "peers" to store.list().map { it.toJson(clock(), includeAddress = true) },
            "advisory" to if (store.list().size > PeerStore.ADVISORY_PEERS) {
                "MANY_PEERS"
            } else {
                null
            },
            "error" to store.lastError
        )
    )

    /**
     * Trusts a key that only calls in (D77), e.g. a hub on the operator's VPS.
     *
     * There is no handshake and no return address: the operator pastes a public
     * key and picks scopes, and the key id is derived from the key. A key that
     * promises an id it does not hash to is refused, and so is this device's own
     * key. It is *not* an operator key: the record's scopes are the only thing
     * the caller may do (D73).
     */
    @Synchronized
    fun grantJson(body: String): String {
        val raw = JsonParser.parseObjectOrNull(body)
            ?: throw ApiException(400, "BAD_GRANT", "body must be a JSON object")
        val publicKey = (raw["publicKey"] as? String)?.trim().orEmpty()
        if (publicKey.isEmpty()) {
            throw ApiException(400, "BAD_PUBLIC_KEY", "paste the reader's Ed25519 public key")
        }
        val keyBytes = runCatching { Base64.getDecoder().decode(publicKey) }.getOrNull()
            ?: throw ApiException(400, "BAD_PUBLIC_KEY", "the public key is not base64")
        if (keyBytes.size != Ed25519.PUBLIC_KEY_BYTES) {
            throw ApiException(400, "BAD_PUBLIC_KEY", "an Ed25519 public key is 32 bytes")
        }
        val derived = Ed25519.keyId(keyBytes)
        val claimed = (raw["nodeId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (claimed != null && claimed != derived) {
            throw ApiException(400, "BAD_PUBLIC_KEY", "the public key does not match that node id")
        }
        if (derived == identity().nodeId) {
            throw ApiException(400, "PEER_SELF", "that is this device's own key")
        }
        val scopes = PeerScope.fromJson(raw["scopes"])
            ?: throw ApiException(400, "BAD_SCOPE", "scopes must be a list of known names")
        val label = NodeIdentity.normalizeLabel(raw["label"] as? String, GRANT_LABEL)
        val record = store.grant(publicKey, label, scopes)
        audit("peer.grant", "peer=${record.nodeId} scopes=${PeerScope.wireNames(scopes)}")
        return Json.encode(
            linkedMapOf(
                "ok" to true,
                "peer" to record.toJson(clock(), includeAddress = true)
            )
        )
    }

    fun removePeer(nodeId: String) {
        store.remove(nodeId)
        audit("peer.remove", "peer=$nodeId")
    }

    /** Runs the probes now; nothing is scheduled (D74). */
    fun refreshJson(): String {
        val peers = store.list().take(maxRefresh)
        // Make sure the identity exists before reporting "cannot be used".
        runCatching { identity() }
        val seed = identityStore.seed()
            ?: return Json.encode(
                mapOf(
                    "ok" to false,
                    "code" to "IDENTITY_CORRUPT",
                    "message" to "the device identity cannot be used"
                )
            )
        var refreshed = 0
        var reachable = 0
        var inboundOnly = 0
        for (peer in peers) {
            // A one-way grant has nothing to dial (D77). Recording a failed
            // probe would turn "never called out" into a red lamp.
            if (peer.isInboundOnly()) {
                inboundOnly += 1
                continue
            }
            val result = client.probe(peer, seed)
            val ok = result is PeerClient.Probe.Ok
            val code = (result as? PeerClient.Probe.Failed)?.code
            store.recordProbe(peer.nodeId, ok, code)
            refreshed += 1
            if (ok) reachable += 1
        }
        audit("peer.refresh", "checked=$refreshed reachable=$reachable inbound=$inboundOnly")
        return Json.encode(
            linkedMapOf(
                "ok" to true,
                "refreshed" to refreshed,
                "reachable" to reachable,
                "inboundOnly" to inboundOnly,
                "total" to store.list().size,
                "peers" to store.list().map { it.toJson(clock(), includeAddress = true) }
            )
        )
    }

    /** The key material the data plane may verify a peer with (D73). */
    fun peerKeys(): Map<String, ByteArray> = store.list().associate { peer ->
        peer.nodeId to Base64.getDecoder().decode(peer.publicKey)
    }

    /**
     * A key id belongs to a peer only when it was paired here. Everything else
     * is treated as an operator key, which is exactly the previous behaviour.
     */
    fun principalOf(keyId: String): SuperAuth.Principal {
        val peer = store.find(keyId) ?: return SuperAuth.Principal.Operator
        // The per-peer service allow-list is read live with the record, so an
        // operator's change applies to the next request without a restart
        // (D54, D101).
        return SuperAuth.Principal.Peer(peer.nodeId, peer.grantedByMe, peer.services)
    }

    /**
     * Changes which services a paired server may use here (D101).
     *
     * The ids are checked against the services this device actually serves, so
     * the operator cannot grant a name that does not exist; an empty list means
     * "everything its scopes already allow".
     */
    fun setSharesJson(nodeId: String, services: List<String>): String {
        val known = knownServiceIds()
        val cleaned = LinkedHashSet<String>(services.size)
        for (raw in services) {
            val id = raw.trim()
            if (known.isNotEmpty() && id !in known) {
                throw ApiException(400, "UNKNOWN_SERVICE", "no service with that id: $id")
            }
            cleaned.add(id)
        }
        val updated = store.setServices(nodeId, cleaned)
        audit("peer.shares", "peer=${updated.nodeId} services=${updated.services.sorted()}")
        return Json.encode(
            linkedMapOf(
                "ok" to true,
                "peer" to updated.toJson(clock(), includeAddress = true)
            )
        )
    }

    /** The service ids this runtime serves; injected so the store stays pure. */
    var serviceIds: () -> Set<String> = { emptySet() }

    private fun knownServiceIds(): Set<String> = runCatching { serviceIds() }.getOrDefault(emptySet())

    fun onDataPlaneStopped() {
        pairing.close()
    }

    private fun refusal(answer: PairAnswer): ApiException {
        val error = answer.payload["error"] as? Map<*, *>
        val code = error?.get("code") as? String ?: "PAIR_REFUSED"
        val message = error?.get("message") as? String ?: "the peer refused the pairing"
        return ApiException(answer.status.coerceIn(400, 599), code, message)
    }

    companion object {
        const val RESET_CONFIRMATION: String = "RESET"

        /** Used when the operator pastes a key without naming the reader. */
        const val GRANT_LABEL: String = "Trusted reader"
    }
}
