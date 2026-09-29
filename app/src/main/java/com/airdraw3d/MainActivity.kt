package com.airdraw3d

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: DrawOverlay
    private lateinit var status: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null
    private var drawing = false
    private var lastX = 0f
    private var lastY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = DrawOverlay(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(0xAA101820.toInt())
        }
        status = TextView(this).apply { text = "AirDraw 3D • Pinch to draw"; setTextColor(Color.WHITE); textSize = 14f }
        val clear = Button(this).apply {
            text = "Clear"
            setOnClickListener { overlay.clear(); status.text = "Canvas cleared" }
        }
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(clear)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupLandmarker()
            startCamera()
        } else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 40)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 40 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            setupLandmarker(); startCamera()
        } else status.text = "Camera permission is required"
    }

    private fun setupLandmarker() {
        try {
            val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            landmarker = HandLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            status.text = "Model setup failed: ${e.message ?: "check model file"}"
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val bmp = imageToBitmap(image)
                    val result = landmarker?.detect(BitmapImageBuilder(bmp).build())
                    if (result != null) processResult(result, image.width, image.height)
                    bmp.recycle()
                } catch (_: Exception) {
                    runOnUiThread { status.text = "Tracking frame…"}
                } finally { image.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
            } catch (e: Exception) { status.text = "Camera error: ${e.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        return bmp
    }

    @Synchronized private fun processResult(result: HandLandmarkerResult, w: Int, h: Int) {
        val hands = result.landmarks()
        var pinched = false
        var px = 0f; var py = 0f
        for (hand in hands) {
            if (hand.size < 9) continue
            val thumb = hand[4]
            val index = hand[8]
            val dist = hypot((thumb.x() - index.x()).toDouble(), (thumb.y() - index.y()).toDouble())
            if (dist < 0.065) {
                pinched = true
                // Index fingertip is used as the brush position.
                px = (index.x() * overlay.width).coerceIn(0f, overlay.width.toFloat())
                py = (index.y() * overlay.height).coerceIn(0f, overlay.height.toFloat())
                break
            }
        }
        runOnUiThread {
            if (pinched) {
                if (!drawing) overlay.begin(px, py) else overlay.add(px, py)
                drawing = true
                status.text = if (hands.size >= 2) "Pinch detected • Two hands seen" else "Pinch detected • Drawing"
            } else {
                drawing = false
                status.text = "Show your hand and pinch to draw"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        landmarker?.close()
        executor.shutdown()
    }
}

class DrawOverlay(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 210, 255)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(12f, 0f, 0f, Color.CYAN)
    }
    private val paths = mutableListOf<Path>()
    private var current: Path? = null
    fun begin(x: Float, y: Float) {
        current = Path().apply { moveTo(x, y) }
        paths.add(current!!)
        invalidate()
    }
    fun add(x: Float, y: Float) {
        current?.lineTo(x, y)
        invalidate()
    }
    fun clear() { paths.clear(); current = null; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paths.forEach { canvas.drawPath(it, paint) }
    }
}
