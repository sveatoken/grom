// SPDX-License-Identifier: Apache-2.0
package app.grom.server.mesh.net

import app.grom.server.superserver.net.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The Bluetooth Classic carrier, over a real loopback stream (D102).
 *
 * The "RFCOMM link" is a plain TCP socket in this test, which is the same thing
 * the Android adapter hands the engine: a duplex byte stream. The server side
 * is a real socket that reads what the transport writes, so a wrong request
 * line, header or body framing fails here rather than on a phone.
 */
class BluetoothJsonTransportTest {

    @Test
    fun `a bt url is carried as the same HTTP request the TCP carrier uses`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val seen = arrayOf("", "")
        val serverThread = thread(name = "bt-test-peer") {
            server.accept().use { socket ->
                val (head, body) = readRequest(socket.getInputStream())
                seen[0] = head
                seen[1] = body
                val answer = """{"ok":true,"result":{"pong":true}}"""
                val bytes = answer.toByteArray()
                val output = socket.getOutputStream()
                output.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
                )
                output.write(bytes)
                output.flush()
            }
        }

        val port = server.localPort
        val link = DuplexLink { remote ->
            assertEquals("AA:BB:CC:DD:EE:FF", remote)
            val socket = Socket("127.0.0.1", port)
            object : DuplexStream {
                override val input: InputStream = socket.getInputStream()
                override val output: OutputStream = socket.getOutputStream()

                override fun close() {
                    runCatching { socket.close() }
                }
            }
        }

        val transport = BluetoothJsonTransport(link)
        val response = transport.request(
            "POST",
            "bt://AA:BB:CC:DD:EE:FF/v1/plugins/agent/rpc",
            mapOf("Content-Type" to "application/json", "x-grom-key" to "abc"),
            """{"op":"ping"}""",
            2_000
        )
        assertEquals(200, response.status)
        assertTrue(response.body.contains("pong"))

        serverThread.join(5_000)
        server.close()
        assertTrue(seen[0].startsWith("POST /v1/plugins/agent/rpc HTTP/1.1"))
        assertTrue(seen[0].contains("x-grom-key: abc"))
        assertEquals("""{"op":"ping"}""", seen[1])
    }

    @Test(expected = TransportException::class)
    fun `an unbonded or unreachable link is a stable failure`() {
        BluetoothJsonTransport { null }
            .request("GET", "bt://AA:BB:CC:DD:EE:FF/v1/status", emptyMap(), null, 1_000)
    }

    @Test(expected = TransportException::class)
    fun `a non-bluetooth url is refused`() {
        BluetoothJsonTransport { null }
            .request("GET", "http://10.0.0.5:47002/v1/status", emptyMap(), null, 1_000)
    }

    @Test
    fun `a malformed mac is refused before the link is opened`() {
        var opened = false
        val transport = BluetoothJsonTransport {
            opened = true
            null
        }
        val failure = runCatching {
            transport.request("GET", "bt://not-a-mac/v1/status", emptyMap(), null, 1_000)
        }.exceptionOrNull()
        assertTrue(failure is TransportException)
        assertEquals(false, opened)
    }

    private fun readRequest(input: InputStream): Pair<String, String> {
        val head = ByteArrayOutputStream()
        var state = 0
        while (state < 4) {
            val next = input.read()
            if (next < 0) break
            head.write(next)
            state = when {
                next == '\r'.code && (state == 0 || state == 2) -> state + 1
                next == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
        }
        val text = head.toString("ISO-8859-1")
        val length = Regex("(?i)content-length: *(\\d+)").find(text)
            ?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val got = input.read(body, read, length - read)
            if (got < 0) break
            read += got
        }
        return text.substringBefore("\r\n\r\n") to String(body, 0, read)
    }
}
