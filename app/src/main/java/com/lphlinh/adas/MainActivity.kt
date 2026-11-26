package com.lphlinh.adas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ai.onnxruntime.*
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLawDistance: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView

    private lateinit var btnSpeedDown: Button
    private lateinit var btnSpeedUp: Button
    private lateinit var btnRain: Button
    private lateinit var btnRotate: Button
    private lateinit var btnSwitchCam: Button

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    // Trí tuệ nhân tạo YOLOv8
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Thông số ADAS và Thông tư 31
    private var currentSpeedKmh = 50f
    private var isManualSpeed = true
    private var isRainMode = false
    private var smoothedDistance = -1f

    // Bù góc xoay thủ công (0: Tự động hoàn toàn theo cảm biến)
    private var manualOffsetDegrees = 0

    // Âm thanh cảnh báo
    private var toneGenerator: ToneGenerator? = null
    private var lastBeepTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvLawDistance = findViewById(R.id.tvLawDistance)
        tvStatus = findViewById(R.id.tvStatus)
        tvDistance = findViewById(R.id.tvDistance)

        btnSpeedDown = findViewById(R.id.btnSpeedDown)
        btnSpeedUp = findViewById(R.id.btnSpeedUp)
        btnRain = findViewById(R.id.btnRain)
        btnRotate = findViewById(R.id.btnRotate)
        btnSwitchCam = findViewById(R.id.btnSwitchCam)

        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            Log.e("ADAS", "Khong the khoi tao ToneGenerator", e)
        }

        setupButtons()
        updateHudDisplay(null)

        if (allPermissionsGranted()) {
            initOnnxModel()
            startCamera()
            startGps()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }
    }

    private fun setupButtons() {
        btnSpeedDown.setOnClickListener {
            isManualSpeed = true
            currentSpeedKmh = max(0f, currentSpeedKmh - 10f)
            updateHudDisplay(null)
        }

        btnSpeedUp.setOnClickListener {
            isManualSpeed = true
            currentSpeedKmh += 10f
            updateHudDisplay(null)
        }

        btnRain.setOnClickListener {
            isRainMode = !isRainMode
            btnRain.text = if (isRainMode) "MƯA: BẬT" else "MƯA: TẮT"
            btnRain.setBackgroundColor(if (isRainMode) Color.parseColor("#1D4ED8") else Color.parseColor("#374151"))
            updateHudDisplay(null)
        }

        btnRotate.setOnClickListener {
            manualOffsetDegrees = (manualOffsetDegrees + 90) % 360
            btnRotate.text = if (manualOffsetDegrees == 0) "XOAY: TỰ ĐỘNG" else "XOAY: +${manualOffsetDegrees}°"
            Toast.makeText(this, "Chế độ xoay: ${btnRotate.text}", Toast.LENGTH_SHORT).show()
        }

        btnSwitchCam.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }
    }

    private fun getSafeDistanceTT31(speedKmh: Float, isRain: Boolean): Float {
        val baseDist = when {
            speedKmh <= 10f -> 3.5f
            speedKmh <= 60f -> max(5.0f, speedKmh * 2f / 3.6f)
            speedKmh <= 80f -> 35.0f
            speedKmh <= 100f -> 55.0f
            else -> 70.0f
        }
        return if (isRain) baseDist * 1.35f else baseDist
    }

    private fun updateHudDisplay(detectedDist: Float?) {
        val safeDist = getSafeDistanceTT31(currentSpeedKmh, isRainMode)

        tvSpeed.text = "${currentSpeedKmh.toInt()} km/h ${if (isManualSpeed) "(MÔ PHỎNG)" else "(GPS)"}"
        tvLawDistance.text = "Luật 31: ≥ ${String.format("%.1f", safeDist)}m${if (isRainMode) " [Mưa]" else ""}"

        if (detectedDist == null || detectedDist <= 0f) {
            tvDistance.text = "-- m"
            tvStatus.text = "ĐƯỜNG THOÁNG"
            tvStatus.setTextColor(Color.parseColor("#10B981"))
            tvStatus.setBackgroundColor(Color.parseColor("#2210B981"))
            return
        }

        tvDistance.text = "${String.format("%.1f", detectedDist)} m"

        val now = SystemClock.uptimeMillis()
        if (detectedDist < safeDist * 0.6f || detectedDist < 8.0f) {
            tvStatus.text = "PHANH GẤP!"
            tvStatus.setTextColor(Color.parseColor("#EF4444"))
            tvStatus.setBackgroundColor(Color.parseColor("#44EF4444"))
            if (now - lastBeepTime > 250L) {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 150)
                lastBeepTime = now
            }
        } else if (detectedDist < safeDist) {
            tvStatus.text = "CẢNH BÁO!"
            tvStatus.setTextColor(Color.parseColor("#F59E0B"))
            tvStatus.setBackgroundColor(Color.parseColor("#33F59E0B"))
            if (now - lastBeepTime > 750L) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
                lastBeepTime = now
            }
        } else {
            tvStatus.text = "AN TOÀN"
            tvStatus.setTextColor(Color.parseColor("#10B981"))
            tvStatus.setBackgroundColor(Color.parseColor("#2210B981"))
        }
    }

    private fun initOnnxModel() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = assets.open("yolov8n.onnx").readBytes()
            ortSession = ortEnv?.createSession(modelBytes, OrtSession.SessionOptions())
            Log.i("ADAS", "Khoi tao YOLOv8 ONNX thanh cong!")
        } catch (e: Exception) {
            Log.e("ADAS", "Loi khi tai model YOLOv8", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetRotation(displayRotation)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setTargetRotation(displayRotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e("ADAS", "Loi khoi dong camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        imageAnalysis?.targetRotation = displayRotation
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val session = ortSession ?: run {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val sensorRotation = imageProxy.imageInfo.rotationDegrees
            val finalRotation = (sensorRotation + manualOffsetDegrees) % 360

            val uprightBitmap = if (finalRotation != 0) {
                val matrix = Matrix().apply { postRotate(finalRotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            detectVehicles(uprightBitmap, session)

        } catch (e: Exception) {
            Log.e("ADAS", "Loi xu ly frame AI", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun detectVehicles(uprightBitmap: Bitmap, session: OrtSession) {
        val wRot = uprightBitmap.width.toFloat()
        val hRot = uprightBitmap.height.toFloat()
        val isPortrait = hRot > wRot

        val resized = Bitmap.createScaledBitmap(uprightBitmap, 640, 640, true)

        val pixels = IntArray(640 * 640)
        resized.getPixels(pixels, 0, 640, 0, 0, 640, 640)

        val floatBuffer = FloatBuffer.allocate(1 * 3 * 640 * 640)
        floatBuffer.rewind()

        val rOffset = 0
        val gOffset = 640 * 640
        val bOffset = 2 * 640 * 640

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f

            floatBuffer.put(rOffset + i, r)
            floatBuffer.put(gOffset + i, g)
            floatBuffer.put(bOffset + i, b)
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnv,
            floatBuffer,
            longArrayOf(1, 3, 640, 640)
        )

        val output = session.run(mapOf(session.inputNames.iterator().next() to inputTensor))
        val outputTensor = output[0] as OnnxTensor
        val outputData = outputTensor.floatBuffer

        val numAnchors = 8400
        val candidateBoxes = ArrayList<DetectedBox>()
        val safeDist = getSafeDistanceTT31(currentSpeedKmh, isRainMode)

        val vW = overlayView.width.toFloat()
        val vH = overlayView.height.toFloat()
        val scale = max(vW / wRot, vH / hRot)
        val dx = (vW - wRot * scale) / 2f
        val dy = (vH - hRot * scale) / 2f

        val vehicleClasses = setOf(2, 3, 5, 7) // 2: car, 3: moto, 5: bus, 7: truck

        for (i in 0 until numAnchors) {
            var maxScore = 0f
            var bestClass = -1

            for (c in vehicleClasses) {
                val score = outputData.get((4 + c) * numAnchors + i)
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            if (maxScore > 0.28f) {
                val cx = outputData.get(0 * numAnchors + i)
                val cy = outputData.get(1 * numAnchors + i)
                val w = outputData.get(2 * numAnchors + i)
                val h = outputData.get(3 * numAnchors + i)

                val x1 = (cx - w / 2f) / 640f
                val y1 = (cy - h / 2f) / 640f
                val x2 = (cx + w / 2f) / 640f
                val y2 = (cy + h / 2f) / 640f

                val screenRect = RectF(
                    x1 * wRot * scale + dx,
                    y1 * hRot * scale + dy,
                    x2 * wRot * scale + dx,
                    y2 * hRot * scale + dy
                )

                val realHeight = when (bestClass) {
                    3 -> 1.2f // Xe máy
                    5, 7 -> 2.8f // Xe tải / Buýt
                    else -> 1.5f // Ô tô
                }

                val label = when (bestClass) {
                    3 -> "MOTO"
                    5 -> "BUS"
                    7 -> "TRUCK"
                    else -> "OTO"
                }

                val boxPixelHeight = max(10f, (y2 - y1) * hRot)
                val focal = if (isPortrait) 900f else 650f
                val estDistance = (focal * realHeight) / boxPixelHeight

                if (estDistance in 1.5f..120f) {
                    candidateBoxes.add(
                        DetectedBox(
                            rect = screenRect,
                            label = label,
                            distance = estDistance,
                            isDanger = estDistance < safeDist * 0.6f || estDistance < 8f,
                            isWarning = estDistance < safeDist
                        )
                    )
                }
            }
        }

        candidateBoxes.sortBy { it.distance }
        val finalBoxes = ArrayList<DetectedBox>()
        for (b in candidateBoxes) {
            var overlaps = false
            for (f in finalBoxes) {
                if (RectF.intersects(b.rect, f.rect)) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) {
                finalBoxes.add(b)
            }
            if (finalBoxes.size >= 5) break
        }

        val closestVehicle = finalBoxes.firstOrNull()
        val currentDist = closestVehicle?.distance

        if (currentDist != null) {
            smoothedDistance = if (smoothedDistance <= 0f) currentDist else (0.6f * currentDist + 0.4f * smoothedDistance)
        } else {
            smoothedDistance = -1f
        }

        runOnUiThread {
            overlayView.setDetections(finalBoxes)
            updateHudDisplay(if (smoothedDistance > 0f) smoothedDistance else null)
        }
    }

    private fun startGps() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (location.hasSpeed() && location.speed > 0.5f) {
                            currentSpeedKmh = location.speed * 3.6f
                            isManualSpeed = false
                            updateHudDisplay(if (smoothedDistance > 0f) smoothedDistance else null)
                        }
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
            )
        }
    }

    private fun allPermissionsGranted() = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ortSession?.close()
        ortEnv?.close()
        toneGenerator?.release()
    }
}