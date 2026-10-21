package com.alex.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exercises the live dashboard's HTTP layer on the JVM: it binds a real
 * socket, parses a request and writes a response.
 */
class LiveStreamServerTest {

    private fun get(path: String, port: Int): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        connection.disconnect()
        return status to body
    }

    @Test
    fun servesTheDashboardAndTheApi() {
        val server = LiveStreamServer(0) { request ->
            when (request.path) {
                "/" -> LiveStreamServer.Response(200, "text/html", "<html>dash</html>".toByteArray())
                "/status" -> LiveStreamServer.Response(
                    200, "application/json", """{"recording":false}""".toByteArray()
                )
                "/record/start" -> LiveStreamServer.Response(
                    200, "application/json", """{"status":"ok"}""".toByteArray()
                )
                else -> LiveStreamServer.Response(404, "text/plain", "nope".toByteArray())
            }
        }
        assertTrue("the server must bind a port", server.start())
        assertTrue("the bound port must be reported", server.boundPort > 0)

        try {
            val (dashboardStatus, dashboardBody) = get("/", server.boundPort)
            assertEquals(200, dashboardStatus)
            assertEquals("<html>dash</html>", dashboardBody)

            val (statusStatus, statusBody) = get("/status", server.boundPort)
            assertEquals(200, statusStatus)
            assertEquals("""{"recording":false}""", statusBody)

            val (missingStatus, _) = get("/nope", server.boundPort)
            assertEquals(404, missingStatus)
        } finally {
            server.stop()
        }
    }

    @Test
    fun acceptsPostCommands() {
        val server = LiveStreamServer(0) { request ->
            LiveStreamServer.Response(
                200, "application/json", """{"method":"${request.method}"}""".toByteArray()
            )
        }
        assertTrue(server.start())
        try {
            val connection = URL("http://127.0.0.1:${server.boundPort}/record/start")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write("{}".toByteArray()) }
            assertEquals(200, connection.responseCode)
            assertEquals("""{"method":"POST"}""", connection.inputStream.bufferedReader().readText())
            connection.disconnect()
        } finally {
            server.stop()
        }
    }
}
