// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh

import app.grom.server.cluster.NodeIdentity
import app.grom.server.superserver.auth.Ed25519
import java.util.Base64

/**
 * The LAN discovery beacon (D99, S8-R004).
 *
 * A device on the same network broadcasts one line, at most every
 * [MeshLimits.MAX_BEACON_BYTES] bytes:
 *
 * ```
 *   grom-mesh-beacon-v1 <nodeId> <port> <publicKeyB64Url> <timestampSeconds>
 *                       <nonceB64Url> <signatureB64Url>
 * ```
 *
 * The signature covers a canonical string of the version, node id, port,
 * timestamp and nonce. It proves that the sender holds the key whose hash is
 * the node id — so nobody can advertise *your* node id without your key — and
 * the timestamp (checked within [MAX_SKEW_SECONDS]) stops a captured beacon from
 * keeping a dead device "nearby" forever.
 *
 * It still proves nothing about trust: any device can create a key and
 * advertise itself. The scanner therefore stores a [MeshCandidate]
 * (`signedBeacon = true`) and the operator still has to pair, exactly as with a
 * typed address.
 *
 * The beacon carries no request, no capability list and no status: it is a
 * name tag, not a protocol.
 */
object MeshBeacon {

    const val PREFIX: String = "grom-mesh-beacon-v1"
    const val SIGNING_PREFIX: String = "grom-mesh-beacon-v1"
    const val MAX_SKEW_SECONDS: Long = 120
    const val MAX_NONCE_BYTES: Int = 24

    data class Decoded(
        val nodeId: String,
        val publicKey: String,
        val port: Int,
        val timestampSeconds: Long,
        val nonce: String
    )

    /**
     * Signs a beacon. The seed never leaves this call and the returned line
     * contains only the public key.
     */
    fun encode(
        seed: ByteArray,
        port: Int,
        timestampSeconds: Long,
        nonce: String
    ): String? {
        if (port !in MeshLimits.MIN_PORT..MeshLimits.MAX_PORT) return null
        if (nonce.isEmpty() || nonce.length > 64) return null
        val publicKey = Ed25519.derivePublicKey(seed)
        val nodeId = Ed25519.keyId(publicKey)
        val canonical = canonical(nodeId, port, timestampSeconds, nonce)
        val signature = Ed25519.sign(seed, canonical.toByteArray(Charsets.UTF_8))
        val line = buildString(160) {
            append(PREFIX).append(' ')
            append(nodeId).append(' ')
            append(port).append(' ')
            append(url(publicKey)).append(' ')
            append(timestampSeconds).append(' ')
            append(nonce).append(' ')
            append(url(signature))
        }
        return line.takeIf { it.toByteArray(Charsets.UTF_8).size <= MeshLimits.MAX_BEACON_BYTES }
    }

    /**
     * Verifies a received line.
     *
     * Returns null for anything that is not a well-formed, correctly signed,
     * recent beacon. The caller never has to decide whether a partial parse is
     * "probably fine": it is not.
     */
    fun decode(
        line: String?,
        nowSeconds: Long = System.currentTimeMillis() / 1000
    ): Decoded? {
        val text = line?.trim().orEmpty()
        if (text.isEmpty() || text.length > MeshLimits.MAX_BEACON_BYTES) return null
        val parts = text.split(' ')
        if (parts.size != 7 || parts[0] != PREFIX) return null

        val nodeId = parts[1]
        val port = parts[2].toIntOrNull() ?: return null
        val timestamp = parts[4].toLongOrNull() ?: return null
        val nonce = parts[5]
        if (!NodeIdentity.NODE_ID_PATTERN.matches(nodeId)) return null
        if (port !in MeshLimits.MIN_PORT..MeshLimits.MAX_PORT) return null
        if (nonce.isEmpty() || nonce.length > 64) return null
        // An old capture is not evidence that the device is here now.
        if (kotlin.math.abs(nowSeconds - timestamp) > MAX_SKEW_SECONDS) return null

        val publicKey = unUrl(parts[3]) ?: return null
        if (publicKey.size != Ed25519.PUBLIC_KEY_BYTES) return null
        if (Ed25519.keyId(publicKey) != nodeId) return null

        val signature = unUrl(parts[6]) ?: return null
        if (signature.size != Ed25519.SIGNATURE_BYTES) return null
        val canonical = canonical(nodeId, port, timestamp, nonce)
        if (!Ed25519.verify(publicKey, canonical.toByteArray(Charsets.UTF_8), signature)) return null

        return Decoded(
            nodeId = nodeId,
            publicKey = Base64.getEncoder().encodeToString(publicKey),
            port = port,
            timestampSeconds = timestamp,
            nonce = nonce
        )
    }

    fun canonical(nodeId: String, port: Int, timestampSeconds: Long, nonce: String): String =
        "$SIGNING_PREFIX\n$nodeId\n$port\n$timestampSeconds\n$nonce"

    private fun url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unUrl(raw: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(raw) }.getOrNull()
            ?: runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
}
