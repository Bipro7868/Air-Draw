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

    // ===== AIRDRAW SETTINGS =====
    private val PINCH_START = 0.055f
    private val PINCH_RELEASE = 0.085f
    private val SMOOTHING = 0.55f // 0 = no smoothing; higher = smoother but more delay
    private val SWAP_AXES = false
    private val FLIP_X = false
    private val FLIP_Y = false
    private val LINE_WIDTH = 6f
    // ===== END SETTINGS =====

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        overlay = DrawOverlay(this, LINE_WIDTH)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 10, 16, 10)
            setBackgroundColor(0xAA101820.toInt())
        }
        status = TextView(this).apply {
            text = "AirDraw 3D • Pinch to draw"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
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
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 40)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 40 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            setupLandmarker()
            startCamera()
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
            val cameraPreview = Preview.Builder().build().also {
                it.setSurfaceProvider(preview.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(480, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { image ->
                var bmp: Bitmap? = null
                try {
                    bmp = imageToBitmap(image)
                    val result = landmarker?.detect(BitmapImageBuilder(bmp).build())
                    if (result != null) processResult(result)
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Tracking frame error: ${e.message ?: "unknown"}" }
                } finally {
                    bmp?.recycle()
                    image.close()
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
            } catch (e: Exception) {
                status.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        return Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(buffer)
        }
    }

    @Synchronized
    private fun processResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        var pinched = false
        var px = 0f
        var py = 0f

        for (hand in hands) {
            if (hand.size < 9) continue
            val thumb = hand[4]
            val index = hand[8]
            val dist = hypot(
                (thumb.x() - index.x()).toDouble(),
                (thumb.y() - index.y()).toDouble()
            ).toFloat()

            // Different thresholds reduce flickering around the pinch boundary.
            val threshold = if (drawing) PINCH_RELEASE else PINCH_START
            if (dist < threshold) {
                pinched = true
                var x = index.x()
                var y = index.y()
                if (SWAP_AXES) {
                    val temp = x
                    x = y
                    y = temp
                }
                if (FLIP_X) x = 1f - x
                if (FLIP_Y) y = 1f - y
                px = (x * overlay.width).coerceIn(0f, overlay.width.toFloat())
                py = (y * overlay.height).coerceIn(0f, overlay.height.toFloat())
                break
            }
        }

        runOnUiThread {
            if (pinched) {
                if (!drawing) overlay.begin(px, py) else overlay.add(px, py, SMOOTHING)
                drawing = true
                status.text = "Pinch detected • Drawing"
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

class DrawOverlay(context: android.content.Context, lineWidth: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 210, 255)
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(10f, 0f, 0f, Color.CYAN)
    }
    private val paths = mutableListOf<Path>()
    private var current: Path? = null
    private var lastX = 0f
    private var lastY = 0f

    fun begin(x: Float, y: Float) {
        current = Path().apply { moveTo(x, y) }
        paths.add(current!!)
        lastX = x
        lastY = y
        invalidate()
    }

    fun add(x: Float, y: Float, smoothing: Float) {
        val path = current ?: return
        val factor = smoothing.coerceIn(0f, 0.95f)
        val smoothX = lastX + (x - lastX) * (1f - factor)
        val smoothY = lastY + (y - lastY) * (1f - factor)
        val midX = (lastX + smoothX) / 2f
        val midY = (lastY + smoothY) / 2f
        path.quadTo(lastX, lastY, midX, midY)
        lastX = smoothX
        lastY = smoothY
        invalidate()
    }

    fun clear() {
        paths.clear()
        current = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (path in paths) canvas.drawPath(path, paint)
    }
}
