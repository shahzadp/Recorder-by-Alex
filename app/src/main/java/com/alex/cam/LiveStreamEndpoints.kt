package com.alex.cam

import org.json.JSONObject
import java.util.Locale

/**
 * The HTTP endpoints of the live dashboard.
 *
 * Two things are offered:
 *  - **live watch**: the camera stream as HLS (/live.m3u8, /init.mp4, /seg-N.m4s)
 *  - **remote recording control**: start and stop the recording from a browser
 */
class LiveStreamEndpoints(
    private val dashboardProvider: () -> ByteArray,
    private val hlsPlayerProvider: () -> ByteArray,
    private val host: Host
) {

    interface Host {
        /** JSON snapshot of the recorder state. */
        fun statusJson(): String

        /** Starts an audio recording. */
        fun startAudioRecording(): Boolean

        /** Starts a video recording. */
        fun startVideoRecording(): Boolean

        /** Stops whatever is being recorded. */
        fun stopRecording(): Boolean

        fun isRecording(): Boolean
        fun isStreaming(): Boolean

        /** True while the recorder needs the camera, so the feed waits. */
        fun isFeedPaused(): Boolean

        /** The init segment of the current stream (ftyp + moov). */
        fun initSegment(): ByteArray?

        /** One media fragment. */
        fun fragment(sequence: Int): ByteArray?

        /** Fragment sequence numbers currently buffered, oldest first. */
        fun sequences(): List<Int>

        /** How long the fragment is, in milliseconds. */
        fun duration(sequence: Int): Int
    }

    private val dashboard: ByteArray by lazy { dashboardProvider() }

    /**
     * The HLS player.
     *
     * It is part of the app, so the dashboard also works on a phone without
     * internet.
     */
    private val hlsPlayer: ByteArray by lazy { hlsPlayerProvider() }

    fun handle(request: LiveStreamServer.Request): LiveStreamServer.Response {
        val path = request.path
        val method = request.method

        if (method == "OPTIONS") {
            return LiveStreamServer.Response(200, "text/plain")
        }

        return when {
            path == "/" || path == "/index.html" ->
                LiveStreamServer.Response(200, "text/html; charset=utf-8", dashboard)

            path == "/hls.min.js" ->
                LiveStreamServer.Response(200, "text/javascript; charset=utf-8", hlsPlayer)

            path == "/status" ->
                LiveStreamServer.Response(
                    200,
                    "application/json; charset=utf-8",
                    host.statusJson().toByteArray()
                )
            path == "/record/audio/start" -> jsonResult(host.startAudioRecording())
            path == "/record/audio/stop" -> jsonResult(host.stopRecording())
            path == "/record/video/start" -> jsonResult(host.startVideoRecording())
            path == "/record/video/stop" -> jsonResult(host.stopRecording())
            path == "/record/start" -> jsonResult(
                if (host.isRecording()) host.stopRecording() else host.startVideoRecording()
            )
            path == "/record/stop" -> jsonResult(host.stopRecording())

            path == "/live.m3u8" || path == "/stream.m3u8" -> playlistResponse()

            path == "/init.mp4" -> {
                val init = host.initSegment()
                if (init == null) {
                    jsonError(404, "no stream")
                } else {
                    LiveStreamServer.Response(200, "video/mp4", init)
                }
            }

            path.startsWith("/seg-") && path.endsWith(".m4s") -> {
                val sequence = path.removePrefix("/seg-").removeSuffix(".m4s").toIntOrNull()
                val data = if (sequence != null) host.fragment(sequence) else null
                if (data == null) {
                    jsonError(404, "unknown segment")
                } else {
                    LiveStreamServer.Response(200, "video/iso.segment", data)
                }
            }

            else -> jsonError(404, "not found: $path")
        }
    }

    private fun jsonResult(ok: Boolean): LiveStreamServer.Response {
        val body = JSONObject()
            .put("status", if (ok) "ok" else "error")
            .put("recording", host.isRecording())
            .toString()
        return LiveStreamServer.Response(200, "application/json; charset=utf-8", body.toByteArray())
    }

    /**
     * The state of the live feed.
     *
     * The feed waits while the recorder has the camera - that is not the
     * same as the feed being broken, and the dashboard says so.
     */
    private fun feedState(): String = when {
        host.isFeedPaused() -> "paused"
        host.isStreaming() -> "running"
        else -> "stopped"
    }

    private fun jsonError(code: Int, message: String): LiveStreamServer.Response {
        val body = JSONObject().put("error", message).toString()
        return LiveStreamServer.Response(code, "application/json; charset=utf-8", body.toByteArray())
    }

    /** The rolling HLS playlist. */
    private fun playlistResponse(): LiveStreamServer.Response {
        val sequences = host.sequences()
        val builder = StringBuilder()
        builder.append("#EXTM3U\n")
        builder.append("#EXT-X-VERSION:7\n")
        builder.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        // The target duration has to be the longest segment - the player needs
        // it to know how long it has to wait for the next one.
        val longest = sequences.maxOfOrNull { host.duration(it) } ?: 1000
        builder.append("#EXT-X-TARGETDURATION:")
            .append((longest + 999) / 1000)
            .append("\n")
        if (sequences.isNotEmpty()) {
            builder.append("#EXT-X-MEDIA-SEQUENCE:").append(sequences.first()).append("\n")
        }
        builder.append("#EXT-X-MAP:URI=\"/init.mp4\"\n")
        for (sequence in sequences) {
            builder.append("#EXTINF:")
                .append(String.format(Locale.US, "%.3f", host.duration(sequence) / 1000.0))
                .append(",\n")
            builder.append("/seg-").append(sequence).append(".m4s\n")
        }
        return LiveStreamServer.Response(
            200,
            "application/vnd.apple.mpegurl",
            builder.toString().toByteArray()
        )
    }
}
