package com.alex.cam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.Locale

/**
 * Viewfinder / controls. Capture lives in [RecordingService] so that recording
 * keeps running when this Activity stops (background / screen off), and so the
 * clock and session state survive being detached and re-attached.
 *
 * The Activity renders state and forwards commands; it never owns the camera or
 * the microphone.
 */
class MainActivity : AppCompatActivity(), RecordingService.Listener {

    private lateinit var previewView: PreviewView
    private lateinit var audioPanel: View
    private lateinit var zoomBar: View
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var btnRecord: Button
    private lateinit var btnPause: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var btnSettings: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvZoom: TextView

    private var service: RecordingService? = null
    private var bound = false
    private var linearZoom = 0f
    private var suppressModeCallback = false
    private var pendingMode: RecordingService.Mode? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Reads elapsed time from the service so the clock is correct after a
     *  background/screen-off session and freezes while paused. */
    private val timerRunnable = object : Runnable {
        override fun run() {
            val s = service
            when {
                s == null -> tvTimer.text = ""
                // The encoder is still warming up - the clock only starts when
                // the file really does, so a counter here would lie.
                s.isStarting() -> tvTimer.text = getString(R.string.starting_recording)
                else -> {
                    val ms = s.elapsedMillis()
                    val totalSec = ms / 1000
                    val clock = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
                    tvTimer.text = if (s.isPaused()) {
                        getString(R.string.paused_prefix, clock)
                    } else {
                        clock
                    }
                }
            }
            // 100 ms keeps the display tight without flooding the main thread.
            handler.postDelayed(this, 100)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? RecordingService.LocalBinder)?.service() ?: return
            service = s
            bound = true
            s.setListener(this@MainActivity)

            linearZoom = s.linearZoom()
            syncModeUi(s.mode())
            s.previewOrNull()?.let { attachPreview(it) }

            updateUi(s.isRecording(), s.isPaused())
            scheduleZoomLabelUpdate()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val want = pendingMode
        pendingMode = null

        if (hasAudioPermission()) startAndBindService()

        when {
            want == null -> if (!hasAudioPermission()) {
                Toast.makeText(this, getString(R.string.need_mic), Toast.LENGTH_LONG).show()
            }
            want == RecordingService.Mode.AUDIO || hasCameraPermission() -> {
                service?.setMode(want)
                syncModeUi(service?.mode() ?: want)
            }
            else -> {
                Toast.makeText(this, getString(R.string.need_camera), Toast.LENGTH_LONG).show()
                syncModeUi(service?.mode() ?: RecordingService.Mode.AUDIO)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        audioPanel = findViewById(R.id.audioPanel)
        zoomBar = findViewById(R.id.zoomBar)
        modeGroup = findViewById(R.id.modeGroup)
        btnRecord = findViewById(R.id.btnRecord)
        btnPause = findViewById(R.id.btnPause)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnSettings = findViewById(R.id.btnSettings)
        tvTimer = findViewById(R.id.tvTimer)
        tvZoom = findViewById(R.id.tvZoom)

        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressModeCallback) return@addOnButtonCheckedListener
            requestMode(
                if (checkedId == R.id.btnModeAudio) RecordingService.Mode.AUDIO
                else RecordingService.Mode.VIDEO
            )
        }

        btnRecord.setOnClickListener {
            val s = service ?: run {
                Toast.makeText(this, getString(R.string.starting), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (s.isRecording()) {
                s.stopRecording()
            } else {
                // Goes through the service intent, so it becomes a foreground
                // service and shows the notification for the recording.
                startRecording()
            }
        }

        btnPause.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (!s.isRecording()) return@setOnClickListener
            if (s.isPaused()) s.resumeRecording() else s.pauseRecording()
        }

        btnSwitch.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.switchCamera()) {
                linearZoom = s.linearZoom()
                scheduleZoomLabelUpdate()
            } else {
                Toast.makeText(this, getString(R.string.stop_first), Toast.LENGTH_SHORT).show()
            }
        }

        // Zoom is button-only (pinch disabled) and works on both lenses.
        btnZoomIn.setOnClickListener {
            service?.zoomIn()
            linearZoom = service?.linearZoom() ?: linearZoom
            scheduleZoomLabelUpdate()
        }
        btnZoomOut.setOnClickListener {
            service?.zoomOut()
            linearZoom = service?.linearZoom() ?: linearZoom
            scheduleZoomLabelUpdate()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Audio is the primary mode: always open the app in Audio mode.
        syncModeUi(RecordingService.Mode.AUDIO)
        updateUi(recording = false, paused = false)

        if (!hasAudioPermission() || !hasCameraPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasAudioPermission()) startAndBindService()
    }

    override fun onStop() {
        // Detach the viewfinder but leave the service running so a recording
        // continues with the screen off or the app backgrounded.
        service?.previewOrNull()?.let { runCatching { it.setSurfaceProvider(null) } }
        // A preview without a surface would keep the camera waiting, so it is
        // let go of completely.
        service?.setViewfinderVisible(false)
        if (bound) {
            service?.setListener(null)
            runCatching { unbindService(connection) }
            bound = false
        }
        service = null
        handler.removeCallbacks(timerRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerRunnable)
    }

    // ------------------------------------------------------- RecordingService.Listener

    override fun onRecordingStateChanged(recording: Boolean, paused: Boolean) {
        runOnUiThread { updateUi(recording, paused) }
    }

    override fun onPreviewReady(preview: Preview) {
        runOnUiThread {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                attachPreview(preview)
            }
        }
    }

    // ----------------------------------------------------------------------- modes

    private fun requestMode(target: RecordingService.Mode) {
        if (target == RecordingService.Mode.VIDEO && !hasCameraPermission()) {
            pendingMode = RecordingService.Mode.VIDEO
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            syncModeUi(service?.mode() ?: RecordingService.Mode.AUDIO)
            return
        }

        val s = service
        if (s == null) {
            Toast.makeText(this, getString(R.string.starting), Toast.LENGTH_SHORT).show()
            syncModeUi(RecordingService.Mode.AUDIO)
            return
        }
        if (s.isRecording()) {
            Toast.makeText(this, getString(R.string.stop_first), Toast.LENGTH_SHORT).show()
            syncModeUi(s.mode())
            return
        }
        s.setMode(target)
        linearZoom = s.linearZoom()
        syncModeUi(s.mode())
    }

    private fun syncModeUi(m: RecordingService.Mode) {
        suppressModeCallback = true
        modeGroup.check(
            if (m == RecordingService.Mode.AUDIO) R.id.btnModeAudio else R.id.btnModeVideo
        )
        suppressModeCallback = false

        val video = m == RecordingService.Mode.VIDEO
        previewView.visibility = if (video) View.VISIBLE else View.GONE
        audioPanel.visibility = if (video) View.GONE else View.VISIBLE
        zoomBar.visibility = if (video) View.VISIBLE else View.GONE
        btnSwitch.visibility = if (video) View.VISIBLE else View.GONE
        updateZoomLabel()
    }

    // ----------------------------------------------------------------------- helpers

    private fun attachPreview(preview: Preview) {
        preview.surfaceProvider = previewView.surfaceProvider
        // The viewfinder is on screen, so the camera may serve it.
        service?.setViewfinderVisible(true)
    }

    private fun applyZoom() {
        service?.setLinearZoom(linearZoom)
        scheduleZoomLabelUpdate()
    }

    private fun scheduleZoomLabelUpdate() {
        handler.postDelayed({ updateZoomLabel() }, 150)
    }

    private fun updateZoomLabel() {
        val ratio = service?.zoomRatio() ?: 1f
        tvZoom.text = String.format(Locale.US, "%.1fx", ratio)
    }

    private fun updateUi(recording: Boolean, paused: Boolean) {
        btnRecord.text = getString(if (recording) R.string.stop else R.string.record)

        btnPause.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        btnPause.text = getString(if (paused) R.string.resume else R.string.pause)
        btnPause.isEnabled = recording

        btnSwitch.isEnabled = !recording
        modeGroup.isEnabled = !recording

        if (recording) {
            tvTimer.visibility = View.VISIBLE
            handler.removeCallbacks(timerRunnable)
            handler.post(timerRunnable)
        } else {
            handler.removeCallbacks(timerRunnable)
            tvTimer.visibility = View.GONE
            tvTimer.text = ""
        }
    }

    private fun startAndBindService() {
        // Only bind: the service stays a plain bound service, so it shows no
        // notification. It is promoted to a foreground service (with the
        // notification) for as long as a recording is actually running.
        if (!bound) {
            bindService(
                Intent(this, RecordingService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
            bound = true
        }
    }

    /** Starts a recording, which makes the service visible in the foreground. */
    private fun startRecording() {
        val intent = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START_RECORDING)
        runCatching { ContextCompat.startForegroundService(this, intent) }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
