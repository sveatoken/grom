// SPDX-License-Identifier: Apache-2.0
package app.grom.server.cluster

import app.grom.server.http.ApiException
import app.grom.server.http.Json
import app.grom.server.http.JsonParser
import app.grom.server.superserver.auth.Ed25519
import java.io.File
import java.util.Base64

/**
 * Persistent list of paired devices (D70, D74).
 *
 * Same storage discipline as every other Grom store (D40): one small JSON file,
 * owner-only permissions, write-to-temp-then-rename and a size cap enforced
 * *before* the write. There is no small hard count: pairing is the operator's
 * choice and a low cap would be an arbitrary refusal, so the file size is the
 * real bound (`PEER_STORE_FULL`).
 *
 * A file that cannot be parsed is reported and treated as empty; it is never
 * deleted, because a corrupt file an operator can inspect is better than a
 * silently reset fleet.
 */
class PeerStore(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxFileBytes: Int = MAX_FILE_BYTES
) {

    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun list(): List<PeerRecord> = load()

    @Synchronized
    fun find(nodeId: String): PeerRecord? = load().firstOrNull { it.nodeId == nodeId }

    /** Adds or replaces one peer; a re-pairing overwrites the old grant. */
    @Synchronized
    fun add(record: PeerRecord): PeerRecord {
        if (!NodeIdentity.NODE_ID_PATTERN.matches(record.nodeId)) {
            throw ApiException(400, "BAD_PEER", "the peer node id is malformed")
        }
        // An empty address is a one-way grant (D77); a present one must be the
        // canonical `scheme://host:port` the operator entered.
        if (record.address.isNotEmpty() && PeerAddress.normalize(record.address) != record.address) {
            throw ApiException(400, "BAD_PEER_ADDRESS", "the peer address is not canonical")
        }
        if (record.grantedByMe.isEmpty()) {
            throw ApiException(400, "SCOPE_REQUIRED", "a pairing must grant at least one scope")
        }
        val records = load().toMutableList()
        records.removeAll { it.nodeId == record.nodeId }
        records.add(record)
        save(records)
        return record
    }

    /**
     * Trusts a key (D77): a hub or a server this device has already paired with.
     *
     * The key id is derived from the key, never typed, and the caller must still
     * choose at least one scope — there is no "trust everything" path.
     *
     * A key that is **already paired** keeps its record: the endpoint, what the
     * other side granted and when it was paired all survive, and only
     * [PeerRecord.grantedByMe] is replaced (D104). Replacing the whole record
     * would turn a working pairing into an inbound-only grant and the server
     * could never be dialled again — while the screen still listed it as
     * paired. A key that is new creates an inbound-only record with an empty
     * address, so [app.grom.server.cluster.PeerClient] can never dial it.
     */
    @Synchronized
    fun grant(publicKey: String, label: String, scopes: Set<PeerScope>): PeerRecord {
        if (scopes.isEmpty()) {
            throw ApiException(400, "SCOPE_REQUIRED", "choose at least one scope")
        }
        val keyBytes = runCatching { Base64.getDecoder().decode(publicKey.trim()) }.getOrNull()
            ?: throw ApiException(400, "BAD_PUBLIC_KEY", "the public key is not base64")
        if (keyBytes.size != Ed25519.PUBLIC_KEY_BYTES) {
            throw ApiException(400, "BAD_PUBLIC_KEY", "an Ed25519 public key is 32 bytes")
        }
        val nodeId = Ed25519.keyId(keyBytes)
        val record = NodeIdentityRules.validatePeer(
            nodeId = nodeId,
            publicKey = publicKey.trim(),
            label = NodeIdentity.normalizeLabel(label),
            address = "",
            byMe = scopes,
            byPeer = emptySet(),
            pairedAt = clock(),
            lastProbe = 0L,
            lastOk = null,
            error = null
        ) ?: throw ApiException(400, "BAD_PUBLIC_KEY", "the public key could not be used")
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.nodeId == record.nodeId }
        if (index >= 0) {
            val merged = records[index].copy(
                label = record.label,
                grantedByMe = scopes
            )
            records[index] = merged
            save(records)
            return merged
        }
        records.add(record)
        save(records)
        return record
    }

    @Synchronized
    fun remove(nodeId: String) {
        val records = load().toMutableList()
        if (!records.removeAll { it.nodeId == nodeId }) {
            throw ApiException(404, "PEER_NOT_FOUND", "no paired device with that id")
        }
        save(records)
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    /**
     * Records the outcome of one probe. Only measured fields change; the grant
     * and the address are untouched.
     */
    @Synchronized
    fun recordProbe(nodeId: String, ok: Boolean, error: String?): PeerRecord? {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.nodeId == nodeId }
        if (index < 0) return null
        val updated = records[index].copy(
            lastProbeMs = clock(),
            lastProbeOk = ok,
            lastError = error
        )
        records[index] = updated
        save(records)
        return updated
    }

    /**
     * Replaces the services this peer may use here (D101).
     *
     * An empty set is the compatibility default — every service its scopes
     * already allow — so an operator can return to it. Unknown ids are refused
     * rather than stored: a typo must not quietly open nothing (or, worse, be
     * treated as a wildcard by a future reader).
     */
    @Synchronized
    fun setServices(nodeId: String, services: Set<String>): PeerRecord {
        if (services.size > NodeIdentityRules.MAX_SHARED_SERVICES) {
            throw ApiException(400, "BAD_SERVICE_LIST", "too many services")
        }
        for (id in services) {
            if (!NodeIdentityRules.SERVICE_ID_PATTERN.matches(id)) {
                throw ApiException(400, "BAD_SERVICE_LIST", "not a service id: $id")
            }
        }
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.nodeId == nodeId }
        if (index < 0) throw ApiException(404, "PEER_NOT_FOUND", "no paired device with that id")
        val updated = records[index].copy(services = services)
        records[index] = updated
        save(records)
        return updated
    }

    @Synchronized
    fun rename(nodeId: String, label: String): PeerRecord {
        val records = load().toMutableList()
        val index = records.indexOfFirst { it.nodeId == nodeId }
        if (index < 0) throw ApiException(404, "PEER_NOT_FOUND", "no paired device with that id")
        val updated = records[index].copy(label = NodeIdentity.normalizeLabel(label))
        records[index] = updated
        save(records)
        return updated
    }

    // ------------------------------------------------------------------ storage

    private fun load(): List<PeerRecord> {
        lastError = null
        if (!file.isFile) return emptyList()
        return try {
            val text = file.readText()
            if (text.length > maxFileBytes) {
                lastError = "peers file is larger than $maxFileBytes bytes"
                return emptyList()
            }
            val root = JsonParser.parseObjectOrNull(text) ?: run {
                lastError = "peers file is not a JSON object"
                return emptyList()
            }
            val raw = root["peers"] as? List<*> ?: return emptyList()
            val out = ArrayList<PeerRecord>(raw.size)
            for (item in raw) {
                val map = item as? Map<*, *> ?: continue
                val peer = PeerRecord.fromJson(map)
                if (peer == null) {
                    lastError = "a stored peer record was rejected"
                    continue
                }
                out.add(peer)
            }
            out
        } catch (failure: Exception) {
            lastError = "peers unreadable: ${failure.javaClass.simpleName}"
            emptyList()
        }
    }

    private fun save(records: List<PeerRecord>) {
        file.parentFile?.mkdirs()
        val text = Json.encode(
            mapOf(
                "version" to 1,
                "peers" to records.map { it.toJson(clock(), includeAddress = true) }
            )
        )
        // Enforced before the write: an over-size write would make every record
        // unreadable on the next start, which is worse than refusing this one.
        if (text.length > maxFileBytes) {
            throw ApiException(
                409,
                "PEER_STORE_FULL",
                "the paired-device file is full (limit $maxFileBytes bytes)"
            )
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        harden(temp)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        harden(file)
    }

    private fun harden(target: File) {
        runCatching { target.setReadable(false, false) }
        runCatching { target.setReadable(true, true) }
        runCatching { target.setWritable(false, false) }
        runCatching { target.setWritable(true, true) }
    }

    companion object {
        const val FILE_NAME: String = "peers.json"

        /** Room for hundreds of pairings; small enough to bound one bad write. */
        const val MAX_FILE_BYTES: Int = 256 * 1024

        /** The UI says "pairwise pairing does not scale" beyond this. */
        const val ADVISORY_PEERS: Int = 8
    }
}
