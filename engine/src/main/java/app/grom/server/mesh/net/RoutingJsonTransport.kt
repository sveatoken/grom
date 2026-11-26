// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh.net

import app.grom.server.superserver.net.HttpJsonClient
import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException

/**
 * One transport for every peer endpoint (D102).
 *
 * A peer record stores what the operator approved or a discovery carrier
 * produced: `http(s)://host:port` for LAN, Wi-Fi Direct and VPN, or
 * `bt://<MAC>` for a bonded Bluetooth Classic link. The request is identical in
 * every case; only the bytes' carrier changes.
 *
 * `http`/`https` go to [HttpJsonClient] unchanged — the same client every peer
 * call already used. `bt` is available only when the runtime installed a
 * [BluetoothJsonTransport]; without one the failure is a stable
 * `TransportException`, never a silent fallback to a different host.
 */
class RoutingJsonTransport(
    private val http: JsonTransport = HttpJsonClient(),
    private val classic: JsonTransport? = null
) : JsonTransport {

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int
    ): HttpJsonResponse {
        val scheme = url.substringBefore(':', "").lowercase()
        return when (scheme) {
            "http", "https" -> http.request(method, url, headers, body, timeoutMs)
            "bt" -> (classic ?: throw TransportException("bluetooth carrier unavailable"))
                .request(method, url, headers, body, timeoutMs)
            else -> throw TransportException("unsupported scheme")
        }
    }
}
