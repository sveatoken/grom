// SPDX-License-Identifier: Apache-2.0
package app.grom.server.superserver.net

import app.grom.server.superserver.plugins.ai.AiHttpReply
import app.grom.server.superserver.plugins.ai.AiLimits
import app.grom.server.superserver.plugins.ai.AiTransport
import app.grom.server.superserver.plugins.ai.AiTimeoutException
import app.grom.server.superserver.plugins.ai.AiTransportException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * The one place the AI gateway touches a socket (D94, policy #63).
 *
 * Properties that matter for a request carrying a provider API key:
 *   - only `http` and `https` are opened. The gateway already refused anything
 *     else when the provider was saved; this is the second line, so a malformed
 *     stored URL cannot become a local file read;
 *   - redirects are never followed — a `3xx` is returned to the caller, because
 *     following `Location` could send the key to another host;
 *   - every failure is a constant message. The URL and headers hold the
 *     credential, so they must never reach an exception that may be logged;
 *   - both bodies are bounded: a provider that streams a gigabyte of "JSON"
 *     cannot exhaust the phone.
 */
class AiHttpTransport : AiTransport {

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int
    ): AiHttpReply {
        val connection = open(url, headers, body, timeoutMs)
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = readCapped(stream, AiLimits.MAX_RESPONSE_BYTES)
            return AiHttpReply(status, text, retryAfter(connection))
        } catch (_: SocketTimeoutException) {
            throw AiTimeoutException()
        } catch (_: IOException) {
            throw AiTransportException(NETWORK_FAILURE)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    override fun stream(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int,
        onLine: (String) -> Boolean
    ): AiHttpReply {
        val connection = open(url, headers, body, timeoutMs)
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = readCapped(connection.errorStream, AiLimits.MAX_RESPONSE_BYTES)
                return AiHttpReply(status, text, retryAfter(connection))
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val line = StringBuilder(128)
                while (true) {
                    val ch = reader.read()
                    if (ch < 0) {
                        if (line.isNotEmpty()) onLine(line.toString())
                        break
                    }
                    if (ch == '\n'.code) {
                        // Drop the CR of CRLF; providers may use either.
                        if (line.isNotEmpty() && line[line.length - 1] == '\r') {
                            line.setLength(line.length - 1)
                        }
                        val text = line.toString()
                        line.setLength(0)
                        if (!onLine(text)) break
                    } else {
                        if (line.length >= AiLimits.MAX_STREAM_LINE_BYTES) {
                            throw AiTransportException(LINE_TOO_LONG)
                        }
                        line.append(ch.toChar())
                    }
                }
            }
            return AiHttpReply(200, "")
        } catch (_: SocketTimeoutException) {
            throw AiTimeoutException()
        } catch (_: IOException) {
            throw AiTransportException(NETWORK_FAILURE)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun open(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int
    ): HttpURLConnection {
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") {
            throw AiTransportException("unsupported scheme")
        }
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            throw AiTransportException(NETWORK_FAILURE)
        } catch (_: ClassCastException) {
            throw AiTransportException("unsupported scheme")
        }

        connection.instanceFollowRedirects = false
        connection.connectTimeout = timeoutMs.coerceAtMost(CONNECT_TIMEOUT_CAP_MS)
        connection.readTimeout = timeoutMs
        connection.requestMethod = "POST"
        connection.doOutput = true
        for ((name, value) in headers) {
            if (RESTRICTED_HEADERS.any { it.equals(name, ignoreCase = true) }) continue
            connection.setRequestProperty(name, value)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        } catch (_: SocketTimeoutException) {
            runCatching { connection.disconnect() }
            throw AiTimeoutException()
        } catch (_: IOException) {
            runCatching { connection.disconnect() }
            throw AiTransportException(NETWORK_FAILURE)
        }
        return connection
    }

    private fun readCapped(stream: InputStream?, cap: Int): String {
        if (stream == null) return ""
        val buffer = ByteArrayOutputStream(1024)
        val chunk = ByteArray(4096)
        var total = 0
        try {
            while (total < cap) {
                val read = stream.read(chunk, 0, minOf(chunk.size, cap - total))
                if (read < 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
        } catch (_: IOException) {
            // Return what arrived; the adapter reports an incomplete document.
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun retryAfter(connection: HttpURLConnection): Int =
        connection.getHeaderField("Retry-After")?.trim()?.toIntOrNull() ?: 0

    companion object {
        private const val CONNECT_TIMEOUT_CAP_MS = 10_000
        private const val NETWORK_FAILURE = "the provider did not answer"
        private const val LINE_TOO_LONG = "the provider sent an oversized stream event"

        /** Set by the JVM, never by the caller: a header could smuggle a hop. */
        private val RESTRICTED_HEADERS = listOf(
            "connection", "content-length", "host", "keep-alive",
            "proxy-authorization", "proxy-connection", "te", "trailer",
            "transfer-encoding", "upgrade"
        )
    }
}
