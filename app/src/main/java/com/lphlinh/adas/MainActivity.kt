package com.lphlinh.adas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ai.onnxruntime.*
import java.nio.FloatBuffer
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLawDist: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnSpeedDown: Button
    private lateinit var btnSpeedUp: Button
    private lateinit var btnRain: Button
    private lateinit var btnSwitchCam: Button

    private var toneGen: ToneGenerator? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var egoSpeedKmh = 50f
    private var isLiveGPS = false
    private var isRain = false
    private var lastBeepTime = 0L

    // Bộ lọc làm mượt khoảng cách (Moving Average)
    private val distHistory = ArrayDeque<Float>()

    // ONNX Runtime Model
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Thông số vật lý xe (mét)
    private val realWidths = mapOf(2 to 1.75f, 3 to 0.75f, 5 to 2.55f, 7 to 2.40f)
    private val classNames = mapOf(2 to "CAR", 3 to "MOTO", 5 to "BUS", 7 to "TRUCK")
    private val FOCAL_LENGTH = 850f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        overlayView = findViewById(R.id.overlayView)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvLawDist = findViewById(R.id.tvLawDist)
        tvDistance = findViewById(R.id.tvDistance)
        tvStatus = findViewById(R.id.tvStatus)
        btnSpeedDown = findViewById(R.id.btnSpeedDown)
        btnSpeedUp = findViewById(R.id.btnSpeedUp)
        btnRain = findViewById(R.id.btnRain)
        btnSwitchCam = findViewById(R.id.btnSwitchCam)

        toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        cameraExecutor = Executors.newSingleThreadExecutor()

        loadAIModel()

        btnSpeedDown.setOnClickListener {
            egoSpeedKmh = Math.max(0f, egoSpeedKmh - 10f)
            isLiveGPS = false
            tvSpeed.text = "TỐC ĐỘ: ${egoSpeedKmh.toInt()} km/h (ĐIỀU CHỈNH TAY)"
            updateLawDistance()
        }

        btnSpeedUp.setOnClickListener {
            egoSpeedKmh += 10f
            isLiveGPS = false
            tvSpeed.text = "TỐC ĐỘ: ${egoSpeedKmh.toInt()} km/h (ĐIỀU CHỈNH TAY)"
            updateLawDistance()
        }

        btnRain.setOnClickListener {
            isRain = !isRain
            btnRain.text = if (isRain) "MƯA: BẬT (+35%)" else "MƯA: TẮT"
            updateLawDistance()
        }

        btnSwitchCam.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }

        updateLawDistance()
        requestPermissions()
    }

    private fun loadAIModel() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = assets.open("yolov8n.onnx").readBytes()
            ortSession = ortEnv?.createSession(modelBytes)
        } catch (e: Exception) {}
    }

    private fun requestPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
            startLocation()
        } else {
            ActivityCompat.requestPermissions(this, permissions, 101)
        }
    }

    private fun startLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
        } catch (e: SecurityException) {}
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasSpeed() && loc.speed >= 0) {
            egoSpeedKmh = loc.speed * 3.6f
            isLiveGPS = true
            tvSpeed.text = "TỐC ĐỘ: ${egoSpeedKmh.toInt()} km/h (GPS LIVE)"
            updateLawDistance()
        }
    }

    private fun getVnLawDistance(speed: Float, rain: Boolean): Float {
        var d = when {
            speed <= 10f -> 3.5f
            speed <= 60f -> Math.max(5f, speed * (2f / 3.6f))
            speed <= 80f -> 35f
            speed <= 100f -> 55f
            else -> 70f
        }
        if (rain) d *= 1.35f
        return Math.round(d * 10f) / 10f
    }

    private fun updateLawDistance() {
        val law = getVnLawDistance(egoSpeedKmh, isRain)
        tvLawDist.text = "LUẬT ĐỊNH (TT 31): >= $law m"
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Đồng bộ hướng xoay chuẩn với tư thế đặt điện thoại trên xe
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_90

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            if (bitmap != null && ortSession != null && ortEnv != null) {
                detectVehicles(bitmap, imageProxy.width, imageProxy.height)
            }
        } catch (e: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    private fun detectVehicles(bitmap: Bitmap, origW: Int, origH: Int) {
        val scaled = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
        val floatBuffer = FloatBuffer.allocate(1 * 3 * 640 * 640)
        val pixels = IntArray(640 * 640)
        scaled.getPixels(pixels, 0, 640, 0, 0, 640, 640)

        for (i in 0 until 640 * 640) {
            floatBuffer.put(i, ((pixels[i] shr 16) and 0xFF) / 255.0f)
        }
        for (i in 0 until 640 * 640) {
            floatBuffer.put(640 * 640 + i, ((pixels[i] shr 8) and 0xFF) / 255.0f)
        }
        for (i in 0 until 640 * 640) {
            floatBuffer.put(2 * 640 * 640 + i, (pixels[i] and 0xFF) / 255.0f)
        }
        floatBuffer.rewind()

        val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, longArrayOf(1, 3, 640, 640))
        val output = ortSession!!.run(Collections.singletonMap("images", inputTensor))
        val raw = (output[0].value as Array<Array<FloatArray>>)[0] // [84, 8400]

        var leadBox: RectF? = null
        var leadClass = ""
        var minRawDist = 999f

        val viewW = overlayView.width.toFloat()
        val viewH = overlayView.height.toFloat()

        for (a in 0 until 8400) {
            var bestScore = 0f
            var bestCls = -1
            for ((clsId, _) in realWidths) {
                val score = raw[4 + clsId][a]
                if (score > bestScore) {
                    bestScore = score
                    bestCls = clsId
                }
            }

            if (bestScore > 0.40f) {
                val cx = raw[0][a]
                val cy = raw[1][a]
                val w = raw[2][a]
                val h = raw[3][a]

                // Chỉ xét xe ở làn giữa
                if (cx in 160f..480f) {
                    val realW = realWidths[bestCls] ?: 1.75f
                    val dist = (realW * FOCAL_LENGTH) / w

                    if (dist < minRawDist) {
                        minRawDist = dist
                        leadClass = classNames[bestCls] ?: "CAR"

                        // Map tọa độ 640x640 ra kích thước màn hình
                        val left = (cx - w / 2f) / 640f * viewW
                        val top = (cy - h / 2f) / 640f * viewH
                        val right = (cx + w / 2f) / 640f * viewW
                        val bottom = (cy + h / 2f) / 640f * viewH
                        leadBox = RectF(left, top, right, bottom)
                    }
                }
            }
        }

        runOnUiThread {
            updateSafetyStatus(leadBox, leadClass, minRawDist)
        }
    }

    private fun updateSafetyStatus(box: RectF?, className: String, rawDist: Float) {
        if (box != null && rawDist < 120f) {
            if (distHistory.size >= 5) distHistory.removeFirst()
            distHistory.addLast(rawDist)
            val smoothDist = Math.round((distHistory.average().toFloat()) * 10f) / 10f

            tvDistance.text = "KHOẢNG CÁCH XE TRƯỚC: $smoothDist m"
            val lawDist = getVnLawDistance(egoSpeedKmh, isRain)
            val now = System.currentTimeMillis()

            var color = Color.GREEN
            var label = "$className: ${smoothDist}m [AN TOÀN]"

            if (smoothDist < 0.7f * lawDist) {
                color = Color.RED
                label = "$className: ${smoothDist}m [NGUY HIỂM]"
                tvStatus.text = "NGUY HIỂM - PHANH GẤP!"
                tvStatus.setTextColor(Color.RED)
                tvStatus.setBackgroundColor(Color.parseColor("#44FF0000"))
                if (now - lastBeepTime > 300) {
                    toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 180)
                    lastBeepTime = now
                }
            } else if (smoothDist < lawDist) {
                color = Color.parseColor("#FFC800")
                label = "$className: ${smoothDist}m [CẢNH BÁO]"
                tvStatus.text = "CẢNH BÁO - DƯỚI CỰ LY LUẬT"
                tvStatus.setTextColor(Color.parseColor("#FFC800"))
                tvStatus.setBackgroundColor(Color.parseColor("#44FFC800"))
                if (now - lastBeepTime > 1200) {
                    toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                    lastBeepTime = now
                }
            } else {
                tvStatus.text = "KHOẢNG CÁCH AN TOÀN"
                tvStatus.setTextColor(Color.GREEN)
                tvStatus.setBackgroundColor(Color.parseColor("#3300FF66"))
            }

            overlayView.updateTarget(box, color, label)
        } else {
            tvDistance.text = "KHOẢNG CÁCH XE TRƯỚC: -- m"
            tvStatus.text = "ĐƯỜNG THÔNG THOÁNG"
            tvStatus.setTextColor(Color.GREEN)
            tvStatus.setBackgroundColor(Color.parseColor("#3300FF66"))
            overlayView.updateTarget(null, Color.GREEN, "")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        ortSession?.close()
        ortEnv?.close()
        toneGen?.release()
    }
}
