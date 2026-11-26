// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh.net

import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import java.io.InputStream
import java.io.OutputStream

/**
 * One byte pipe to another device (D102).
 *
 * The engine never sees an Android `BluetoothSocket`. It sees this interface,
 * which the Android adapter implements with a *secure* RFCOMM socket to a
 * bonded device. That keeps the whole carrier testable on the JVM with pipes
 * and keeps every `android.bluetooth` symbol in one small adapter file.
 */
interface DuplexStream : AutoCloseable {
    val input: InputStream
    val output: OutputStream
}

/**
 * Opens a carrier to a Bluetooth MAC address.
 *
 * [open] must return null (or throw) rather than block forever when the device
 * is not bonded, not reachable or the adapter is off; the transport turns that
 * into a stable failure. There is no retry loop here: reconnection is a
 * user-visible action, not a background timer.
 */
fun interface DuplexLink {
    fun open(remote: String): DuplexStream?
}

/**
 * HTTP/JSON over a stream carrier, addressed as `bt://<MAC>` (D102).
 *
 * Only plain JSON request/response is supported. A streaming answer (SSE) has
 * no length and never ends by itself, so it is not offered over this carrier —
 * the AI gateway's non-streaming routes work, `aiChatStream` does not.
 */
class BluetoothJsonTransport(private val link: DuplexLink) : JsonTransport {

    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMs: Int
    ): HttpJsonResponse {
        if (!url.startsWith(SCHEME)) throw TransportException("unsupported scheme")
        val rest = url.removePrefix(SCHEME)
        val slash = rest.indexOf('/')
        if (slash <= 0) throw TransportException("malformed bluetooth url")
        val mac = rest.substring(0, slash)
        val path = rest.substring(slash)
        if (!MAC.matches(mac)) throw TransportException("malformed bluetooth address")

        val stream = try {
            link.open(mac)
        } catch (_: Exception) {
            null
        } ?: throw TransportException("bluetooth link unavailable")

        try {
            // Do not close the output before reading: on a socket carrier that
            // would close the whole link. `writeRequest` already flushes.
            HttpWire.writeRequest(stream.output, method, path, headers, body.orEmpty())
            return HttpWire.readResponse(stream.input)
        } catch (_: TransportException) {
            throw TransportException("bluetooth request failed")
        } catch (_: Exception) {
            throw TransportException("bluetooth request failed")
        } finally {
            runCatching { stream.close() }
        }
    }

    companion object {
        const val SCHEME: String = "bt://"

        private val MAC = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    }
}
