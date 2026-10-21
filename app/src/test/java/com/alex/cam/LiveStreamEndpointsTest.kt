package com.alex.cam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the routing of the dashboard's recording buttons.
 *
 * Every button has to reach its own command - an audio button that starts a
 * video recording (or the other way round) is very hard to spot on the phone,
 * so it is pinned down here.
 */
class LiveStreamEndpointsTest {

    /** Records what the dashboard asked the recorder to do. */
    private class RecordingHost : LiveStreamEndpoints.Host {
        var startedAudio = 0
        var startedVideo = 0
        var stopped = 0
        var recording = false
        var streaming = false
        var lastStatus = """{"recording":false}"""

        override fun statusJson(): String = lastStatus

        override fun startAudioRecording(): Boolean {
            startedAudio++
            recording = true
            return true
        }

        override fun startVideoRecording(): Boolean {
            startedVideo++
            recording = true
            return true
        }

        override fun stopRecording(): Boolean {
            stopped++
            recording = false
            return true
        }

        override fun isRecording(): Boolean = recording

        override fun isStreaming(): Boolean = streaming

        override fun isFeedPaused(): Boolean = recording

        override fun initSegment(): ByteArray? = null

        override fun fragment(sequence: Int): ByteArray? = null

        override fun sequences(): List<Int> = emptyList()

        override fun duration(sequence: Int): Int = 1000
    }

    private fun endpoints(host: RecordingHost) = LiveStreamEndpoints(
        { "<html>dashboard</html>".toByteArray() },
        { "// hls.js".toByteArray() },
        host
    )

    private fun request(method: String, path: String) =
        LiveStreamServer.Request(method, path, emptyMap(), emptyMap(), ByteArray(0))

    @Test
    fun theAudioButtonStartsAnAudioRecording() {
        val host = RecordingHost()
        val endpoints = endpoints(host)

        val response = endpoints.handle(request("POST", "/record/audio/start"))

        assertEquals(200, response.status)
        assertEquals(1, host.startedAudio)
        assertEquals(0, host.startedVideo)
        assertEquals("ok", JSONObject(String(response.body)).getString("status"))
    }

    @Test
    fun theVideoButtonStartsAVideoRecording() {
        val host = RecordingHost()
        val endpoints = endpoints(host)

        val response = endpoints.handle(request("POST", "/record/video/start"))

        assertEquals(200, response.status)
        assertEquals(1, host.startedVideo)
        assertEquals(0, host.startedAudio)
        assertEquals("ok", JSONObject(String(response.body)).getString("status"))
    }

    @Test
    fun theStopButtonsStopTheRecording() {
        val host = RecordingHost()
        val endpoints = endpoints(host)

        endpoints.handle(request("POST", "/record/audio/stop"))
        endpoints.handle(request("POST", "/record/video/stop"))

        assertEquals(2, host.stopped)
        assertEquals(0, host.startedAudio)
        assertEquals(0, host.startedVideo)
    }

    @Test
    fun theStatusShowsWhatIsRunning() {
        val host = RecordingHost()
        host.lastStatus = """{"recording":true,"recordingMode":"audio"}"""
        val endpoints = endpoints(host)

        val response = endpoints.handle(request("GET", "/status"))

        assertEquals(200, response.status)
        val json = JSONObject(String(response.body))
        assertTrue(json.getBoolean("recording"))
        assertEquals("audio", json.getString("recordingMode"))
    }

    @Test
    fun theDashboardIsServed() {
        val host = RecordingHost()
        val endpoints = endpoints(host)

        val response = endpoints.handle(request("GET", "/"))

        assertEquals(200, response.status)
        assertEquals("<html>dashboard</html>", String(response.body))
    }

    @Test
    fun theHlsPlayerIsServed() {
        // The player has to come from the phone, so the dashboard also works
        // without an internet connection.
        val host = RecordingHost()
        val endpoints = endpoints(host)

        val response = endpoints.handle(request("GET", "/hls.min.js"))

        assertEquals(200, response.status)
        assertEquals("// hls.js", String(response.body))
    }

    @Test
    fun unknownPathsAreRejected() {
        val host = RecordingHost()
        val endpoints = endpoints(host)

        val response = endpoints.handle(request("GET", "/nope"))

        assertEquals(404, response.status)
    }
}
