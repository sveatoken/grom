// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh.net

import app.grom.server.superserver.net.HttpJsonResponse
import app.grom.server.superserver.net.JsonTransport
import app.grom.server.superserver.net.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The HTTP/1.1 codec the stream carriers use (D102). */
class HttpWireTest {

    @Test
    fun `a request carries a method, path, host, length and body`() {
        val output = ByteArrayOutputStream()
        HttpWire.writeRequest(
            output,
            "POST",
            "/v1/plugins/agent/rpc",
            mapOf("x-grom-key" to "abc", "Content-Length" to "999"),
            """{"op":"ping"}"""
        )
        val text = output.toString("UTF-8")
        assertTrue(text.startsWith("POST /v1/plugins/agent/rpc HTTP/1.1\r\n"))
        assertTrue(text.contains("Host: grom-mesh\r\n"))
        assertTrue(text.contains("x-grom-key: abc\r\n"))
        // The caller's Content-Length is replaced by the real one.
        assertTrue(text.contains("Content-Length: 13\r\n"))
        assertTrue(!text.contains("999"))
        assertTrue(text.endsWith("\r\n\r\n{\"op\":\"ping\"}"))
    }

    @Test
    fun `a response with a declared length is read exactly`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
            "Content-Length: 11\r\n\r\n{\"ok\":true}"
        val response = HttpWire.readResponse(ByteArrayInputStream(raw.toByteArray()))
        assertEquals(200, response.status)
        assertEquals("""{"ok":true}""", response.body)
    }

    @Test
    fun `a response without a length is read to the end`() {
        val raw = "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n{\"ok\":false}"
        val response = HttpWire.readResponse(ByteArrayInputStream(raw.toByteArray()))
        assertEquals(401, response.status)
        assertEquals("""{"ok":false}""", response.body)
    }

    @Test(expected = TransportException::class)
    fun `a malformed status line is a transport failure`() {
        HttpWire.readResponse(ByteArrayInputStream("not http\r\n\r\n".toByteArray()))
    }

    // ------------------------------------------------------------ routing

    @Test
    fun `http goes to the http client and bt to the classic carrier`() {
        val httpCalls = ArrayList<String>()
        val http = object : JsonTransport {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?,
                timeoutMs: Int
            ): HttpJsonResponse {
                httpCalls.add(url)
                return HttpJsonResponse(200, "{}")
            }
        }
        val btCalls = ArrayList<String>()
        val classic = object : JsonTransport {
            override fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?,
                timeoutMs: Int
            ): HttpJsonResponse {
                btCalls.add(url)
                return HttpJsonResponse(200, "{}")
            }
        }
        val routing = RoutingJsonTransport(http, classic)
        routing.request("GET", "http://10.0.0.5:47002/v1/status", emptyMap(), null, 1_000)
        routing.request("GET", "https://vps.example:8443/v1/status", emptyMap(), null, 1_000)
        routing.request("GET", "bt://AA:BB:CC:DD:EE:FF/v1/status", emptyMap(), null, 1_000)
        assertEquals(2, httpCalls.size)
        assertEquals(1, btCalls.size)
    }

    @Test(expected = TransportException::class)
    fun `bt without a classic carrier is a stable failure`() {
        RoutingJsonTransport().request("GET", "bt://AA:BB:CC:DD:EE:FF/v1/status", emptyMap(), null, 1_000)
    }

    @Test(expected = TransportException::class)
    fun `an unknown scheme is refused`() {
        RoutingJsonTransport().request("GET", "file:///etc/passwd", emptyMap(), null, 1_000)
    }
}
