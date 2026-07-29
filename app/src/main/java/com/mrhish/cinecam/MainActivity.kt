package com.mrhish.cinecam

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Range
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
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
    private lateinit var histView: HistogramView
    private var recording: Recording? = null
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var stabilizer: StabilizerHelper
    private lateinit var cameraProvider: ProcessCameraProvider
    private val CAMERA_PERMISSION_CODE = 100

    private var currentIso = 100
    private var currentShutterDenom = 60
    private var currentWbKelvin = 5500
    private var currentEv = 0
    private var currentFps = 30
    private var manualFocusValue = 5.0f
    private var useAF = false
    private var quality = Quality.FHD
    private var histVisible = false
    private var bitrateMbps = 50
    private var slogEnabled = false

    private lateinit var availableCameraIds: List<String>
    private var currentCameraIndex = 0

    private lateinit var fpsChips: List<TextView>

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
        val camToggle = findViewById<TextView>(R.id.camToggle)
        val bitrateToggle = findViewById<TextView>(R.id.bitrateToggle)
        val slogToggle = findViewById<TextView>(R.id.slogToggle)
        val afButton = findViewById<TextView>(R.id.afButton)
        val focusSeek = findViewById<SeekBar>(R.id.focusSeek)

        val fps24 = findViewById<TextView>(R.id.fps24)
        val fps30 = findViewById<TextView>(R.id.fps30)
        val fps60 = findViewById<TextView>(R.id.fps60)
        fpsChips = listOf(fps24, fps30, fps60)
        fps30.isSelected = true

        loadAvailableCameras()
        camToggle.text = "CAM$currentCameraIndex"

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
            currentWbKelvin = (currentWbKelvin - 200).coerceAtLeast(2000)
            wbValue.text = "${currentWbKelvin}K"
            restartCamera()
        }
        findViewById<TextView>(R.id.wbPlus).setOnClickListener {
            currentWbKelvin = (currentWbKelvin + 200).coerceAtMost(9500)
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

        focusSeek.isEnabled = !useAF
        focusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    manualFocusValue = (100 - progress) / 10f
                    restartCamera()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        afButton.setOnClickListener {
            useAF = !useAF
            afButton.isSelected = useAF
            focusSeek.isEnabled = !useAF
            restartCamera()
        }

        fps24.setOnClickListener { selectFps(24, fps24) }
        fps30.setOnClickListener { selectFps(30, fps30) }
        fps60.setOnClickListener { selectFps(60, fps60) }

        findViewById<TextView>(R.id.resToggle).setOnClickListener { view ->
            val chip = view as TextView
            quality = if (quality == Quality.FHD) Quality.UHD else Quality.FHD
            chip.text = if (quality == Quality.UHD) "4K" else "1080p"
            restartCamera()
        }

        camToggle.setOnClickListener {
            if (availableCameraIds.isNotEmpty()) {
                currentCameraIndex = (currentCameraIndex + 1) % availableCameraIds.size
                camToggle.text = "CAM$currentCameraIndex"
                restartCamera()
            }
        }

        bitrateToggle.setOnClickListener {
            bitrateMbps = when (bitrateMbps) {
                20 -> 50
                50 -> 80
                80 -> 100
                else -> 20
            }
            bitrateToggle.text = "${bitrateMbps}Mbps"
            restartCamera()
        }

        slogToggle.setOnClickListener {
            slogEnabled = !slogEnabled
            slogToggle.isSelected = slogEnabled
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

    private fun selectFps(value: Int, chip: TextView) {
        currentFps = value
        fpsChips.forEach { it.isSelected = false }
        chip.isSelected = true
        restartCamera()
    }

    private fun loadAvailableCameras() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        availableCameraIds = try {
            manager.cameraIdList.toList()
        } catch (e: Exception) {
            listOf()
        }
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
            cameraProvider = cameraProviderFuture.get()

            val previewBuilder = Preview.Builder()
            applyManualControls(previewBuilder)
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .setTargetVideoEncodingBitRate(bitrateMbps * 1_000_000)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { image ->
                computeHistogram(image)
                image.close()
            }

            val cameraSelector = buildCameraSelector()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture, analysis)
                statusText.text = "CineCam Ready"
            } catch (e: Exception) {
                statusText.text = "Bind failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildCameraSelector(): CameraSelector {
        if (availableCameraIds.isEmpty()) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        val targetId = availableCameraIds[currentCameraIndex % availableCameraIds.size]
        return CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { info ->
                    Camera2CameraInfo.from(info).cameraId == targetId
                }
            }
            .build()
    }

    private fun applyManualControls(builder: Preview.Builder) {
        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_000_000_000L / currentShutterDenom)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(currentFps, currentFps))

        val gains = kelvinToGains(currentWbKelvin)
        extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gains)

        if (useAF) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        } else {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusValue)
        }

        if (slogEnabled) {
            extender.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            val flatCurve = floatArrayOf(0f, 0.05f, 0.25f, 0.30f, 0.5f, 0.55f, 0.75f, 0.78f, 1f, 0.95f)
            val map = android.hardware.camera2.params.TonemapCurve(flatCurve, flatCurve, flatCurve)
            extender.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, map)
        }
    }

    private fun kelvinToGains(kelvin: Int): android.hardware.camera2.params.RggbChannelVector {
        val temp = kelvin / 100.0
        var red: Double
        var blue: Double

        red = if (temp <= 66) 255.0 else 329.698727446 * Math.pow(temp - 60, -0.1332047592)
        blue = if (temp >= 66) 255.0 else if (temp <= 19) 0.0 else 138.5177312231 * Math.log(temp - 10) - 305.0447927307

        red = red.coerceIn(0.0, 255.0) / 255.0
        blue = blue.coerceIn(0.0, 255.0) / 255.0

        val rGain = (1.0 / red.coerceAtLeast(0.3)).toFloat().coerceIn(0.5f, 4f)
        val bGain = (1.0 / blue.coerceAtLeast(0.3)).toFloat().coerceIn(0.5f, 4f)

        return android.hardware.camera2.params.RggbChannelVector(rGain, 1f, 1f, bGain)
    }

    private fun computeHistogram(image: ImageProxy) {
        if (!histVisible) return
        try {
            val buffer = image.planes[0].buffer
            val bins = IntArray(32)
            val step = (buffer.remaining() / 2000).coerceAtLeast(1)
            var i = 0
            while (i < buffer.remaining()) {
                val value = buffer.get(i).toInt() and 0xFF
                bins[(value * 31) / 255]++
                i += step
            }
            histView.update(bins)
        } catch (e: Exception) { }
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
