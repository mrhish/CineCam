package com.mrhish.cinecam

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Range
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.video.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

@ExperimentalCamera2Interop
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var recordButton: TextView
    private lateinit var recDot: android.view.View
    private lateinit var histView: TextView
    private var recording: Recording? = null
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var stabilizer: StabilizerHelper
    private val CAMERA_PERMISSION_CODE = 100

    private var currentIso = 100
    private var currentShutterDenom = 60
    private var currentWbKelvin = 5500
    private var currentEv = 0
    private var currentFps = 30
    private var manualFocusValue = 5.0f
    private var useAF = true
    private var quality = Quality.FHD
    private var histVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)
        recDot = findViewById(R.id.recDot)
        histView = findViewById(R.id.histView)

        val isoValue = findViewById<TextView>(R.id.isoValue)
        val shutterValue = findViewById<TextView>(R.id.shutterValue)
        val wbValue = findViewById<TextView>(R.id.wbValue)
        val evValue = findViewById<TextView>(R.id.evValue)

        findViewById<TextView>(R.id.isoMinus).setOnClickListener {
            currentIso = (currentIso - 50).coerceAtLeast(50)
            isoValue.text = "$currentIso"
            restartCamera()
        }
        findViewById<TextView>(R.id.isoPlus).setOnClickListener {
            currentIso = (currentIso + 50).coerceAtMost(3200)
            isoValue.text = "$currentIso"
            restartCamera()
        }

        findViewById<TextView>(R.id.shutterMinus).setOnClickListener {
            currentShutterDenom = (currentShutterDenom - 10).coerceAtLeast(10)
            shutterValue.text = "1/$currentShutterDenom"
            restartCamera()
        }
        findViewById<TextView>(R.id.shutterPlus).setOnClickListener {
            currentShutterDenom = (currentShutterDenom + 10).coerceAtMost(2000)
            shutterValue.text = "1/$currentShutterDenom"
            restartCamera()
        }

        findViewById<TextView>(R.id.wbMinus).setOnClickListener {
            currentWbKelvin = (currentWbKelvin - 100).coerceAtLeast(2000)
            wbValue.text = "${currentWbKelvin}K"
            restartCamera()
        }
        findViewById<TextView>(R.id.wbPlus).setOnClickListener {
            currentWbKelvin = (currentWbKelvin + 100).coerceAtMost(9500)
            wbValue.text = "${currentWbKelvin}K"
            restartCamera()
        }

        findViewById<TextView>(R.id.evMinus).setOnClickListener {
            currentEv = (currentEv - 1).coerceAtLeast(-6)
            evValue.text = String.format("%.1f", currentEv.toFloat())
            restartCamera()
        }
        findViewById<TextView>(R.id.evPlus).setOnClickListener {
            currentEv = (currentEv + 1).coerceAtMost(6)
            evValue.text = String.format("%.1f", currentEv.toFloat())
            restartCamera()
        }

        findViewById<SeekBar>(R.id.focusSeek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    useAF = false
                    manualFocusValue = (100 - progress) / 10f
                    restartCamera()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<TextView>(R.id.afButton).setOnClickListener {
            useAF = true
            restartCamera()
        }

        findViewById<TextView>(R.id.fps24).setOnClickListener { currentFps = 24; restartCamera() }
        findViewById<TextView>(R.id.fps30).setOnClickListener { currentFps = 30; restartCamera() }
        findViewById<TextView>(R.id.fps60).setOnClickListener { currentFps = 60; restartCamera() }

        findViewById<TextView>(R.id.resToggle).setOnClickListener { view ->
            val chip = view as TextView
            quality = if (quality == Quality.FHD) Quality.UHD else Quality.FHD
            chip.text = if (quality == Quality.UHD) "4K" else "1080p"
            restartCamera()
        }

        findViewById<TextView>(R.id.histToggle).setOnClickListener {
            histVisible = !histVisible
            histView.visibility = if (histVisible) android.view.View.VISIBLE else android.view.View.GONE
        }

        stabilizer = StabilizerHelper(this) { x, y ->
            val offsetX = (-x * 6f).coerceIn(-20f, 20f)
            val offsetY = (-y * 6f).coerceIn(-20f, 20f)
            previewView.translationX = offsetX
            previewView.translationY = offsetY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), CAMERA_PERMISSION_CODE
            )
        } else {
            startCamera()
        }

        recordButton.setOnClickListener { toggleRecording() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                statusText.text = "Camera permission denied"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        stabilizer.start()
    }

    override fun onPause() {
        super.onPause()
        stabilizer.stop()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val builder = Preview.Builder()
            applyManualControls(builder)
            val preview = builder.build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                statusText.text = "CineCam Ready"
            } catch (e: Exception) {
                statusText.text = "Bind failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyManualControls(builder: Preview.Builder) {
        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_000_000_000L / currentShutterDenom)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(currentFps, currentFps))

        if (useAF) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        } else {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusValue)
        }
    }

    private fun restartCamera() {
        startCamera()
    }

    private fun toggleRecording() {
        if (recording != null) {
            recording?.stop()
            recording = null
            recordButton.text = "REC"
            recDot.visibility = android.view.View.INVISIBLE
            return
        }

        val name = "CineCam_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCapture.output.prepareRecording(this, mediaStoreOutput)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { }

        recordButton.text = "STOP"
        recDot.visibility = android.view.View.VISIBLE
    }
}
