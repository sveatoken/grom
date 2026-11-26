// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh.net

import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.TransportException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The smallest HTTP/1.1 client codec that the stream carriers need (D102).
 *
 * A Bluetooth Classic link is a byte stream, not a TCP socket: the platform's
 * HTTP client cannot open it. Rather than invent a protocol, the mesh writes the *same*
 * HTTP/1.1 request it would have written over TCP and reads the same response.
 * That keeps one request format, one signature and one server parser.
 *
 * Bounds mirror the data plane: [MAX_HEADER_BYTES] headers, [MAX_BODY_BYTES]
 * body. A response without `Content-Length` is read to end-of-stream (`Connection:
 * close`), which is what [app.grom.server.superserver.SuperServer] sends; a
 * chunked or streaming response is therefore not usable over this carrier and
 * the caller must not ask for one.
 */
object HttpWire {

    const val MAX_HEADER_BYTES: Int = 8 * 1024
    const val MAX_BODY_BYTES: Int = 1024 * 1024

    private val CRLFCRLF = byteArrayOf(13, 10, 13, 10)

    /**
     * Writes one request. The caller supplies the path (already including any
     * query, which is never signed) and the headers.
     */
    fun writeRequest(
        output: OutputStream,
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder(256)
        head.append(method.uppercase()).append(' ').append(path).append(" HTTP/1.1\r\n")
        head.append("Host: grom-mesh\r\n")
        head.append("Connection: close\r\n")
        if (bodyBytes.isNotEmpty()) {
            head.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        }
        for ((name, value) in headers) {
            if (name.equals("Host", ignoreCase = true) ||
                name.equals("Connection", ignoreCase = true) ||
                name.equals("Content-Length", ignoreCase = true)
            ) {
                continue
            }
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (bodyBytes.isNotEmpty()) output.write(bodyBytes)
        output.flush()
    }

    /**
     * Reads one response. Status 0 means the stream ended before a status line,
     * which the caller reports as a failed call rather than as a 200.
     */
    fun readResponse(input: InputStream): HttpJsonResponse {
        val prefix = ByteArrayOutputStream(512)
        val buffer = ByteArray(1024)
        var headerEnd = -1
        while (headerEnd < 0) {
            val read = input.read(buffer)
            if (read < 0) break
            prefix.write(buffer, 0, read)
            if (prefix.size() > MAX_HEADER_BYTES + MAX_BODY_BYTES) {
                throw TransportException("response too large")
            }
            headerEnd = indexOf(prefix.toByteArray(), CRLFCRLF)
            if (headerEnd < 0 && prefix.size() > MAX_HEADER_BYTES) {
                throw TransportException("response headers too large")
            }
        }
        if (headerEnd < 0) {
            if (prefix.size() == 0) return HttpJsonResponse(0, "")
            throw TransportException("malformed response")
        }

        val raw = prefix.toByteArray()
        val headerText = String(raw, 0, headerEnd, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val status = parseStatus(lines.firstOrNull())
            ?: throw TransportException("malformed status line")
        val declared = lines.asSequence()
            .drop(1)
            .map { it.trim() }
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()

        val bodyStart = headerEnd + CRLFCRLF.size
        val already = raw.size - bodyStart
        val body = ByteArrayOutputStream(256)
        if (already > 0) body.write(raw, bodyStart, already)

        if (declared != null) {
            if (declared < 0 || declared > MAX_BODY_BYTES) throw TransportException("response too large")
            var remaining = declared - already
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                body.write(buffer, 0, read)
                remaining -= read
            }
        } else {
            // No length: read to the end of the stream, bounded.
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (body.size() + read > MAX_BODY_BYTES) throw TransportException("response too large")
                body.write(buffer, 0, read)
            }
        }
        return HttpJsonResponse(status, body.toString("UTF-8"))
    }

    private fun parseStatus(line: String?): Int? {
        val text = line?.trim().orEmpty()
        if (!text.startsWith("HTTP/1.")) return null
        val parts = text.split(' ')
        if (parts.size < 2) return null
        val status = parts[1].toIntOrNull() ?: return null
        return status.takeIf { it in 100..599 }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (index in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) continue@outer
            }
            return index
        }
        return -1
    }

    /** Kept for callers that need to know the exact failure shape. */
    class WireException(message: String) : IOException(message)
}
