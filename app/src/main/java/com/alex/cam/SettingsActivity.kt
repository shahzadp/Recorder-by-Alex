package com.alex.cam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Settings screen.
 *
 * The live stream section turns the phone's web server on and off and shows the
 * address to open. The server hosts the dashboard, which can watch the camera
 * live and start or stop the recording.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER_URL = "extra_server_url"
    }

    private var service: RecordingService? = null
    private var bound = false

    private lateinit var btnStreaming: Button
    private lateinit var rowServer: View
    private lateinit var tvServerUrl: TextView
    private lateinit var tvStreamingHint: TextView
    private lateinit var btnCopyUrl: Button
    private lateinit var btnOpenUrl: Button
    private lateinit var btnKeepAlive: Button
    private lateinit var tvKeepAliveHint: TextView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? RecordingService.LocalBinder)?.service() ?: return
            service = s
            bound = true
            render(s.isLiveStreamEnabled(), s.liveServerUrl())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        btnStreaming = findViewById(R.id.btnStreaming)
        rowServer = findViewById(R.id.rowServer)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvStreamingHint = findViewById(R.id.tvStreamingHint)
        btnCopyUrl = findViewById(R.id.btnCopyUrl)
        btnOpenUrl = findViewById(R.id.btnOpenUrl)
        btnKeepAlive = findViewById(R.id.btnKeepAlive)
        tvKeepAliveHint = findViewById(R.id.tvKeepAliveHint)

        findViewById<TextView>(R.id.tvVersion).text =
            "Recorder by Alex ${packageManager.getPackageInfo(packageName, 0).versionName}"

        btnStreaming.setOnClickListener {
            val s = service
            if (s == null) {
                Toast.makeText(this, R.string.streaming_not_ready, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val enabled = !s.isLiveStreamEnabled()
            if (enabled) {
                // Start the service properly (not just bind it), so the live
                // stream keeps running after the app is left.
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, RecordingService::class.java)
                        .setAction(RecordingService.ACTION_START_STREAM)
                )
            }
            val ok = s.setLiveStreamEnabled(enabled)
            render(enabled, s.liveServerUrl())
            if (enabled && !ok) {
                // Say what went wrong instead of leaving a dead toggle behind.
                val reason = s.liveServerError().orEmpty()
                Toast.makeText(
                    this,
                    if (reason.isBlank()) R.string.streaming_failed else R.string.streaming_failed_reason,
                    Toast.LENGTH_LONG
                ).show()
                render(false, "")
            }
        }

        btnCopyUrl.setOnClickListener { copyUrl() }
        btnOpenUrl.setOnClickListener { openInBrowser() }

        btnKeepAlive.setOnClickListener { requestKeepAlive() }

        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        renderKeepAlive()
    }

    /**
     * Shows whether the system is allowed to put the app to sleep.
     *
     * If it is, the live dashboard stops answering while the app is closed -
     * the buttons on the website then only work after the app is opened.
     */
    private fun renderKeepAlive() {
        val ignoring = isIgnoringBatteryOptimizations()
        btnKeepAlive.isEnabled = !ignoring
        btnKeepAlive.text = getString(
            if (ignoring) R.string.keep_alive_done else R.string.settings_keep_alive_button
        )
        tvKeepAliveHint.text = if (ignoring) {
            getString(R.string.keep_alive_done)
        } else {
            getString(R.string.settings_keep_alive_desc)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
    }

    /** Asks the system to let the app run in the background. */
    private fun requestKeepAlive() {
        if (isIgnoringBatteryOptimizations()) {
            renderKeepAlive()
            return
        }
        val intent = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Some ROMs do not have that screen - send the user to the app's
            // battery settings instead.
            val fallback = Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            )
            try {
                startActivity(fallback)
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.keep_alive_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(
            Intent(this, RecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        service = null
        super.onStop()
    }

    private fun render(enabled: Boolean, url: String) {
        btnStreaming.text = getString(
            if (enabled) R.string.streaming_on else R.string.streaming_off
        )
        if (enabled && url.isNotBlank()) {
            tvStreamingHint.text = getString(R.string.streaming_hint)
            rowServer.visibility = View.VISIBLE
            tvServerUrl.visibility = View.VISIBLE
            tvServerUrl.text = url
            btnCopyUrl.isEnabled = true
            btnOpenUrl.isEnabled = true
        } else {
            tvStreamingHint.text = getString(R.string.streaming_hint)
            rowServer.visibility = View.GONE
            tvServerUrl.visibility = View.GONE
            btnCopyUrl.isEnabled = false
            btnOpenUrl.isEnabled = false
        }
    }

    private fun currentUrl(): String = tvServerUrl.text?.toString().orEmpty()

    private fun copyUrl() {
        val url = currentUrl()
        if (url.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(
            android.content.ClipData.newPlainText("Recorder live stream", url)
        )
        Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openInBrowser() {
        val url = currentUrl()
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
