// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

/**
 * What this device has heard nearby, with an expiry clock (D99).
 *
 * Three rules keep a hostile or busy network from turning discovery into state:
 *
 *   - **bounded**: at most [MeshLimits.MAX_CANDIDATES] entries, oldest dropped;
 *   - **expiring**: a candidate that has not been heard for
 *     [MeshLimits.MAX_CANDIDATE_TTL_MS] is gone, so a switched-off phone stops
 *     looking "nearby";
 *   - **never trusted**: [offer] performs no authentication beyond what the
 *     transport already did, and the book is only ever a list of hints. Pairing
 *     is the only thing that creates a [app.grom.server.cluster.PeerRecord].
 *
 * Dedupe is by node id when the transport revealed one, otherwise by endpoint,
 * otherwise by the BLE prefix. A later, better sighting (a full identity or an
 * address) upgrades an earlier one without resetting `firstSeenMs`, so the UI
 * can say "known for a while" honestly.
 */
class DiscoveryBook(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val selfNodeId: () -> String? = { null },
    private val ttlMs: Long = MeshLimits.CANDIDATE_TTL_MS,
    private val maxCandidates: Int = MeshLimits.MAX_CANDIDATES
) {

    private val entries = LinkedHashMap<String, MeshCandidate>()

    /**
     * Records one sighting.
     *
     * Returns false when the candidate is this device, malformed, or when it
     * carries neither an identity nor an endpoint (nothing could ever be done
     * with it).
     */
    @Synchronized
    fun offer(candidate: MeshCandidate): Boolean {
        if (candidate.nodeId != null && candidate.nodeId == selfNodeId()) return false
        if (candidate.nodeId == null && candidate.address.isEmpty()) {
            // A BLE advert with no prefix is not a candidate at all.
            if (candidate.nodeIdPrefix.isNullOrEmpty()) return false
        }
        if (candidate.address.isNotEmpty() &&
            app.grom.server.cluster.PeerAddress.normalize(candidate.address) != candidate.address
        ) {
            return false
        }

        prune()
        // The key is derived here, never accepted from a radio: two adapters
        // must not be able to disagree about what deduplicates.
        val key = when {
            candidate.nodeId != null -> "id:${candidate.nodeId}"
            candidate.address.isNotEmpty() -> "addr:${candidate.transport.wireName}:${candidate.address}"
            candidate.nodeIdPrefix != null -> "prefix:${candidate.transport.wireName}:${candidate.nodeIdPrefix}"
            else -> return false
        }
        val previous = entries[key]
        val merged = if (previous == null) {
            candidate
        } else {
            candidate.copy(
                firstSeenMs = previous.firstSeenMs,
                // A sighting that knows less than the stored one must not erase
                // what was learned: keep the full id/address when present.
                nodeId = candidate.nodeId ?: previous.nodeId,
                publicKey = candidate.publicKey ?: previous.publicKey,
                label = candidate.label ?: previous.label,
                address = candidate.address.ifEmpty { previous.address },
                signedBeacon = candidate.signedBeacon || previous.signedBeacon
            )
        }
        entries.remove(key)
        entries[key] = merged
        while (entries.size > maxCandidates) {
            val oldest = entries.keys.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        return true
    }

    /** Live candidates, newest sighting first. */
    @Synchronized
    fun list(): List<MeshCandidate> {
        prune()
        return entries.values.sortedByDescending { it.lastSeenMs }
    }

    @Synchronized
    fun find(key: String): MeshCandidate? {
        prune()
        return entries[key]
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int {
        prune()
        return entries.size
    }

    private fun prune() {
        val now = clock()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeenMs > ttlMs) iterator.remove()
        }
    }
}
