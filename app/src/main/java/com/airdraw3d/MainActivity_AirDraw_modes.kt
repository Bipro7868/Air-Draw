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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : ComponentActivity() {

    private lateinit var preview: PreviewView
    private lateinit var overlay: DrawOverlay
    private lateinit var status: TextView
    private lateinit var modeButton: Button
    private lateinit var surfaceSpinner: Spinner

    private val executor = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null

    private enum class MainMode {
        ANY_SHAPE,
        STRAIGHT_LINES
    }

    private enum class SurfaceMode {
        TABLE,
        WALL
    }

    @Volatile
    private var mainMode = MainMode.ANY_SHAPE

    @Volatile
    private var surfaceMode = SurfaceMode.TABLE

    @Volatile
    private var drawing = false

    private var clearGestureLatched = false
    private var modeGestureLatched = false

    // ==========================================
    // EDITABLE SETTINGS
    // ==========================================

    private val PINCH_START = 0.055f
    private val PINCH_RELEASE = 0.085f

    private val CLEAR_PINCH_DISTANCE = 0.075f

    // 0 = no smoothing; higher = smoother but slower
    private val SMOOTHING = 0.55f

    // Coordinate adjustments
    private val SWAP_AXES = false
    private val FLIP_X = false
    private val FLIP_Y = false

    private val LINE_WIDTH = 6f

    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        overlay = DrawOverlay(this, LINE_WIDTH)

        root.addView(
            preview,
            FrameLayout.LayoutParams(-1, -1)
        )

        root.addView(
            overlay,
            FrameLayout.LayoutParams(-1, -1)
        )

        // Top control bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xAA101820.toInt())
        }

        status = TextView(this).apply {
            text = "AirDraw 3D • Any Shape • Table"
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        modeButton = Button(this).apply {
            text = "Any Shape"
            setOnClickListener {
                switchMainMode()
            }
        }

        surfaceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Table", "Wall")
            )

            onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {

                    override fun onNothingSelected(
                        parent: AdapterView<*>?
                    ) {}

                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        surfaceMode =
                            if (position == 0) {
                                SurfaceMode.TABLE
                            } else {
                                SurfaceMode.WALL
                            }

                        drawing = false
                        overlay.endStroke()

                        refreshStatus(
                            "Surface: ${surfaceLabel()}"
                        )
                    }
                }
        }

        val clearButton = Button(this).apply {
            text = "Clear"

            setOnClickListener {
                overlay.clear()
                drawing = false
                refreshStatus("Canvas cleared")
            }
        }

        bar.addView(
            status,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        bar.addView(modeButton)
        bar.addView(surfaceSpinner)
        bar.addView(clearButton)

        root.addView(
            bar,
            FrameLayout.LayoutParams(
                -1,
                -2,
                Gravity.TOP
            )
        )

        setContentView(root)

        // Camera permission
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            setupLandmarker()
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                40
            )
        }
    }

    // ==========================================
    // STATUS AND MODE
    // ==========================================

    private fun surfaceLabel(): String {
        return if (surfaceMode == SurfaceMode.TABLE) {
            "Table"
        } else {
            "Wall"
        }
    }

    private fun modeLabel(): String {
        return if (mainMode == MainMode.ANY_SHAPE) {
            "Any Shape"
        } else {
            "Straight Lines"
        }
    }

    private fun refreshStatus(prefix: String? = null) {
        val message = "${modeLabel()} • ${surfaceLabel()}"

        status.text =
            if (prefix != null) {
                "$prefix • $message"
            } else {
                message
            }

        modeButton.text = modeLabel()
    }

    private fun switchMainMode() {
        mainMode =
            if (mainMode == MainMode.ANY_SHAPE) {
                MainMode.STRAIGHT_LINES
            } else {
                MainMode.ANY_SHAPE
            }

        drawing = false
        overlay.endStroke()

        overlay.setStraightMode(
            mainMode == MainMode.STRAIGHT_LINES
        )

        refreshStatus("Mode changed")
    }

    // ==========================================
    // CAMERA PERMISSION
    // ==========================================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            results
        )

        if (
            requestCode == 40 &&
            results.isNotEmpty() &&
            results[0] == PackageManager.PERMISSION_GRANTED
        ) {
            setupLandmarker()
            startCamera()
        } else {
            status.text = "Camera permission is required"
        }
    }

    // ==========================================
    // MEDIAPIPE HAND TRACKER
    // ==========================================

    private fun setupLandmarker() {
        try {
            val base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options =
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build()

            landmarker =
                HandLandmarker.createFromOptions(
                    this,
                    options
                )

        } catch (e: Exception) {
            status.text =
                "Model setup failed: ${e.message ?: "check model file"}"
        }
    }

    // ==========================================
    // CAMERA
    // ==========================================

    private fun startCamera() {
        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            val provider = providerFuture.get()

            val cameraPreview =
                Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(
                            preview.surfaceProvider
                        )
                    }

            val analysis =
                ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 360))
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                    )
                    .build()

            analysis.setAnalyzer(executor) { image ->

                var bitmap: Bitmap? = null

                try {
                    bitmap = imageToBitmap(image)

                    val result =
                        landmarker?.detect(
                            BitmapImageBuilder(bitmap).build()
                        )

                    if (result != null) {
                        processResult(result)
                    }

                } catch (e: Exception) {
                    runOnUiThread {
                        status.text =
                            "Tracking error: ${e.message ?: "unknown"}"
                    }
                } finally {
                    bitmap?.recycle()
                    image.close()
                }
            }

            try {
                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    cameraPreview,
                    analysis
                )

            } catch (e: Exception) {
                status.text = "Camera error: ${e.message}"
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        buffer.rewind()

        return Bitmap.createBitmap(
            image.width,
            image.height,
            Bitmap.Config.ARGB_8888
        ).also {
            it.copyPixelsFromBuffer(buffer)
        }
    }

    // ==========================================
    // HAND GESTURE DETECTION
    // ==========================================

    private fun distance(
        a: NormalizedLandmark,
        b: NormalizedLandmark
    ): Float {
        return hypot(
            (a.x() - b.x()).toDouble(),
            (a.y() - b.y()).toDouble()
        ).toFloat()
    }

    private fun isTwoFingerGesture(
        hand: List<NormalizedLandmark>
    ): Boolean {
        if (hand.size < 21) return false

        val indexUp =
            hand[8].y() < hand[6].y()

        val middleUp =
            hand[12].y() < hand[10].y()

        val ringDown =
            hand[16].y() > hand[14].y()

        val pinkyDown =
            hand[20].y() > hand[18].y()

        return indexUp &&
                middleUp &&
                ringDown &&
                pinkyDown
    }

    // ==========================================
    // PROCESS HAND LANDMARKS
    // ==========================================

    @Synchronized
    private fun processResult(
        result: HandLandmarkerResult
    ) {
        val hands = result.landmarks()

        var activePinch = false
        var px = 0f
        var py = 0f

        var clearGesture = false
        var modeGesture = false

        for (hand in hands) {
            if (hand.size < 21) continue

            val thumb = hand[4]
            val index = hand[8]
            val pinky = hand[20]

            // Clear gesture: thumb + pinky
            val clearDistance =
                distance(thumb, pinky)

            if (clearDistance < CLEAR_PINCH_DISTANCE) {
                clearGesture = true
            }

            // Mode-switch gesture
            if (isTwoFingerGesture(hand)) {
                modeGesture = true
            }

            // Drawing gesture: thumb + index
            val drawDistance =
                distance(thumb, index)

            val threshold =
                if (drawing) {
                    PINCH_RELEASE
                } else {
                    PINCH_START
                }

            if (drawDistance < threshold) {
                activePinch = true

                var x = index.x()
                var y = index.y()

                // Coordinate adjustments
                if (SWAP_AXES) {
                    val temp = x
                    x = y
                    y = temp
                }

                if (FLIP_X) {
                    x = 1f - x
                }

                if (FLIP_Y) {
                    y = 1f - y
                }

                px = (
                    x * overlay.width
                ).coerceIn(
                    0f,
                    overlay.width.toFloat()
                )

                py = (
                    y * overlay.height
                ).coerceIn(
                    0f,
                    overlay.height.toFloat()
                )
            }
        }

        // Update the UI on the main thread
        runOnUiThread {

            // Clear gesture: trigger once per gesture
            if (
                clearGesture &&
                !clearGestureLatched
            ) {
                overlay.clear()
                drawing = false
                refreshStatus("Canvas cleared")
            }

            clearGestureLatched = clearGesture

            // Mode gesture: trigger once per gesture
            if (
                modeGesture &&
                !modeGestureLatched
            ) {
                switchMainMode()
            }

            modeGestureLatched = modeGesture

            // Do not draw while performing another gesture
            if (clearGesture || modeGesture) {
                drawing = false
                overlay.endStroke()

            } else if (activePinch) {

                if (!drawing) {
                    overlay.begin(px, py)
                } else {
                    overlay.add(
                        px,
                        py,
                        SMOOTHING
                    )
                }

                drawing = true
                refreshStatus("Drawing")

            } else {
                drawing = false
                overlay.endStroke()
                refreshStatus("Pinch thumb + index to draw")
            }
        }
    }

    // ==========================================
    // CLEANUP
    // ==========================================

    override fun onDestroy() {
        super.onDestroy()

        landmarker?.close()
        executor.shutdown()
    }
}

// ==============================================
// DRAWING OVERLAY
// ==============================================

class DrawOverlay(
    context: android.content.Context,
    lineWidth: Float
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 210, 255)
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(
            10f,
            0f,
            0f,
            Color.CYAN
        )
    }

    private val paths = mutableListOf<Path>()

    private var current: Path? = null

    private var lastX = 0f
    private var lastY = 0f

    private var startX = 0f
    private var startY = 0f

    private var straightMode = false

    // ==========================================
    // MODE CONTROL
    // ==========================================

    fun setStraightMode(enabled: Boolean) {
        straightMode = enabled
        endStroke()
    }

    // ==========================================
    // START A NEW STROKE
    // ==========================================

    fun begin(x: Float, y: Float) {
        startX = x
        startY = y

        val path = Path().apply {
            moveTo(x, y)
        }

        current = path
        paths.add(path)

        lastX = x
        lastY = y

        invalidate()
    }

    // ==========================================
    // ADD POINT TO STROKE
    // ==========================================

    fun add(
        x: Float,
        y: Float,
        smoothing: Float
    ) {
        val path = current ?: return

        if (straightMode) {

            // One straight segment per pinch-drag.
            // Release and pinch again to start another segment.

            path.reset()
            path.moveTo(startX, startY)
            path.lineTo(x, y)

        } else {

            val factor =
                smoothing.coerceIn(0f, 0.95f)

            val smoothX =
                lastX + (x - lastX) * (1f - factor)

            val smoothY =
                lastY + (y - lastY) * (1f - factor)

            val midX =
                (lastX + smoothX) / 2f

            val midY =
                (lastY + smoothY) / 2f

            path.quadTo(
                lastX,
                lastY,
                midX,
                midY
            )

            lastX = smoothX
            lastY = smoothY
        }

        invalidate()
    }

    // ==========================================
    // END CURRENT STROKE
    // ==========================================

    fun endStroke() {
        current = null
    }

    // ==========================================
    // CLEAR CANVAS
    // ==========================================

    fun clear() {
        paths.clear()
        current = null
        invalidate()
    }

    // ==========================================
    // DRAW ALL PATHS
    // ==========================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (path in paths) {
            canvas.drawPath(path, paint)
        }
    }
}