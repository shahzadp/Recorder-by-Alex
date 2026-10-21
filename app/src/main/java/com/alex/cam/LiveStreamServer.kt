package com.alex.cam

import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A small HTTP/1.1 server for the live dashboard.
 *
 * Written from scratch for this project: the original app used NanoHTTPD,
 * which is no longer published under its original coordinates.
 *
 * It is deliberately minimal - it serves the dashboard, the status JSON and the
 * HLS segments, and it accepts the recording commands.
 */
class LiveStreamServer(
    private val port: Int,
    private val handler: (Request) -> Response
) {

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun bodyText(): String = String(body, Charsets.UTF_8)
    }

    data class Response(
        val status: Int = 200,
        val contentType: String = "text/plain; charset=utf-8",
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap()
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool()

    /** The port actually bound (0 until started). */
    @Volatile
    var boundPort: Int = 0
        private set

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            boundPort = socket.localPort
            Thread({ acceptLoop(socket) }, "live-http-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { workers.shutdownNow() }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                workers.execute { handle(client) }
            } catch (e: Exception) {
                if (running.get()) continue else break
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            val request = readRequest(client.getInputStream()) ?: return
            val response = try {
                handler(request)
            } catch (e: Exception) {
                Response(500, "application/json", """{"error":"internal error"}""".toByteArray())
            }
            writeResponse(client.getOutputStream(), response)
        } catch (e: Exception) {
            // A dropped client is not an error worth reporting.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val size = minOf(contentLength, 64 * 1024)
            val buffer = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buffer, read, size - read)
                if (n <= 0) break
                read += n
            }
            buffer.copyOf(read)
        } else {
            ByteArray(0)
        }

        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        return Request(method, path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) {
                val text = builder.toString()
                return if (text.endsWith("\r")) text.dropLast(1) else text
            }
            builder.append(value.toChar())
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = HashMap<String, String>()
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            result[decode(pair.substringBefore('='))] = decode(pair.substringAfter('=', ""))
        }
        return result
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        value
    }

    private fun writeResponse(output: java.io.OutputStream, response: Response) {
        val out = BufferedOutputStream(output)
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            for ((key, value) in response.headers) {
                append(key).append(": ").append(value).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(response.body)
        out.flush()
    }
}
