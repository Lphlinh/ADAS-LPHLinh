package com.lphlinh.adas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var btnRain: Button
    private lateinit var btnSwitchCam: Button

    private var toneGen: ToneGenerator? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var egoSpeedKmh = 0f
    private var isRain = false
    private var lastBeepTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvLawDist = findViewById(R.id.tvLawDist)
        tvDistance = findViewById(R.id.tvDistance)
        tvStatus = findViewById(R.id.tvStatus)
        btnRain = findViewById(R.id.btnRain)
        btnSwitchCam = findViewById(R.id.btnSwitchCam)

        toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        cameraExecutor = Executors.newSingleThreadExecutor()

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

        requestPermissions()
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
        egoSpeedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f) else 0f
        tvSpeed.text = "TỐC ĐỘ: ${egoSpeedKmh.toInt()} km/h (GPS LIVE)"
        updateLawDistance()
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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (exc: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        toneGen?.release()
    }
}
