package com.mrhish.cinecam

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Range
import android.widget.Button
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

@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private var recording: Recording? = null
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var stabilizer: StabilizerHelper
    private val CAMERA_PERMISSION_CODE = 100

    private var currentIso = 100
    private var currentShutterNs = 16666667L // 1/60s in ns
    private var currentWbKelvin = 5500

    private var previewBuilder: Preview.Builder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)

        val isoLabel = findViewById<TextView>(R.id.isoLabel)
        val shutterLabel = findViewById<TextView>(R.id.shutterLabel)
        val wbLabel = findViewById<TextView>(R.id.wbLabel)

        findViewById<SeekBar>(R.id.isoSeek).setOnSeekBarChangeListener(simpleSeek {
            currentIso = 100 + it
            isoLabel.text = "ISO: $currentIso"
            restartCamera()
        })

        findViewById<SeekBar>(R.id.shutterSeek).setOnSeekBarChangeListener(simpleSeek {
            val denom = 60 + it
            currentShutterNs = (1_000_000_000L / denom)
            shutterLabel.text = "Shutter: 1/$denom"
            restartCamera()
        })

        findViewById<SeekBar>(R.id.wbSeek).setOnSeekBarChangeListener(simpleSeek {
            currentWbKelvin = 2500 + it
            wbLabel.text = "White Balance: ${currentWbKelvin}K"
            restartCamera()
        })

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

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
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
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
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
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterNs)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
    }

    private fun restartCamera() {
        startCamera()
    }

    private fun toggleRecording() {
        if (recording != null) {
            recording?.stop()
            recording = null
            recordButton.text = "Record"
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

        recordButton.text = "Stop"
    }
}
