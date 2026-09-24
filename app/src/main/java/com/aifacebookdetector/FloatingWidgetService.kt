package com.aifacebookdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max

class FloatingWidgetService : Service() {

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var capturing = false
    private var warningBanner: View? = null
    private var hideBannerJob: Job? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)

    private val geminiModel by lazy {
        GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = GEMINI_API_KEY,
            generationConfig = generationConfig {
                temperature = 0.2f
                responseMimeType = "application/json"
            }
        )
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCaptureSurface()
            mediaProjection = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("screen-capture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.projectionData()
        if (resultCode != 0 && data != null && mediaProjection == null) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)?.also { projection ->
                projection.registerCallback(projectionCallback, mainHandler)
            }
        }

        if (overlayView == null) {
            showOverlay()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        hideWarningBanner()
        serviceScope.cancel()
        releaseCaptureSurface()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "floating_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.view_floating_widget, null)
        val size = resources.getDimensionPixelSize(android.R.dimen.app_icon_size).coerceAtLeast(dp(56))
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }

        view.setOnTouchListener(DragClickListener(params) { captureCurrentFrame() })
        windowManager.addView(view, params)
        overlayView = view
        overlayParams = params
    }

    private fun captureCurrentFrame() {
        val projection = mediaProjection
        if (projection == null) {
            Toast.makeText(this, R.string.projection_permission_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (capturing) return
        capturing = true

        overlayView?.visibility = View.INVISIBLE

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        releaseCaptureSurface()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage()
            if (image == null) return@setOnImageAvailableListener
            try {
                val bitmap = image.toBitmap()
                mainHandler.post {
                    processCapturedImage(bitmap)
                    overlayView?.visibility = View.VISIBLE
                    capturing = false
                }
            } catch (error: Exception) {
                Log.e(TAG, "Capture failed", error)
                mainHandler.post {
                    Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show()
                    overlayView?.visibility = View.VISIBLE
                    capturing = false
                }
            } finally {
                image.close()
                releaseCaptureSurface()
            }
        }, captureHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "floating-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
    }

    fun processCapturedImage(bitmap: Bitmap) {
        CapturedImageProcessor.processCapturedImage(bitmap)
        Toast.makeText(this, R.string.capture_success, Toast.LENGTH_SHORT).show()

        if (GEMINI_API_KEY == PLACEHOLDER_API_KEY) {
            Toast.makeText(this, R.string.gemini_key_missing, Toast.LENGTH_LONG).show()
            return
        }

        serviceScope.launch {
            try {
                val analysis = withContext(Dispatchers.IO) {
                    analyzeWithGemini(bitmap)
                }
                if (analysis.is_ai && analysis.score > 70) {
                    showAiWarningBanner(analysis.score)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Gemini analysis failed", error)
                Toast.makeText(
                    this@FloatingWidgetService,
                    R.string.gemini_analyze_failed,
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    private suspend fun analyzeWithGemini(bitmap: Bitmap): AiImageAnalysis {
        val upload = bitmap.scaledForUpload()
        try {
            val response = geminiModel.generateContent(
                content {
                    image(upload)
                    text(GEMINI_PROMPT)
                }
            )
            val raw = response.text.orEmpty()
            return analysisJson.decodeFromString(
                AiImageAnalysis.serializer(),
                raw.extractJsonObject()
            )
        } finally {
            if (upload !== bitmap && !upload.isRecycled) {
                upload.recycle()
            }
        }
    }

    private fun showAiWarningBanner(score: Int) {
        hideWarningBanner()
        val banner = LayoutInflater.from(this).inflate(R.layout.view_ai_warning_banner, null)
        banner.findViewById<TextView>(R.id.warningText).text =
            getString(R.string.ai_warning_banner, score)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(banner, params)
        warningBanner = banner
        hideBannerJob = serviceScope.launch {
            delay(BANNER_VISIBLE_MS)
            hideWarningBanner()
        }
    }

    private fun hideWarningBanner() {
        hideBannerJob?.cancel()
        hideBannerJob = null
        warningBanner?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        warningBanner = null
    }

    private fun Bitmap.scaledForUpload(): Bitmap {
        val longest = max(width, height)
        if (longest <= MAX_UPLOAD_SIDE) return this
        val scale = MAX_UPLOAD_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun releaseCaptureSurface() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inner class DragClickListener(
        private val params: WindowManager.LayoutParams,
        private val onClick: () -> Unit
    ) : View.OnTouchListener {
        private var downX = 0
        private var downY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragged = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = params.x
                    downY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > CLICK_SLOP || abs(dy) > CLICK_SLOP) {
                        dragged = true
                    }
                    params.x = downX + dx
                    params.y = downY + dy
                    windowManager.updateViewLayout(view, params)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragged) onClick()
                    return true
                }
            }
            return false
        }
    }

    companion object {
        private const val TAG = "FloatingWidgetService"
        private const val NOTIFICATION_ID = 1002
        private const val CLICK_SLOP = 12
        private const val MAX_UPLOAD_SIDE = 1280
        private const val BANNER_VISIBLE_MS = 5_000L
        private const val PLACEHOLDER_API_KEY = "AQ.Ab8RN6JH7FS27QkWBczi6qOwUAKv65pSpc9MH-BoShuKothFIg"
        val GEMINI_API_KEY = "AQ.Ab8RN6JH7FS27QkWBczi6qOwUAKv65pSpc9MH-BoShuKothFIg"
        private const val GEMINI_PROMPT =
            "Hãy phân tích bức ảnh này xem có phải do AI tạo ra (DALL-E, Midjourney, Stable Diffusion...) hay không. " +
                "Trả về định dạng JSON gồm: is_ai (boolean), score (số từ 0 đến 100), và reason (lý do ngắn gọn bằng tiếng Việt)"
        private val analysisJson = Json { ignoreUnknownKeys = true; isLenient = true }
        const val ACTION_STOP = "com.aifacebookdetector.STOP_FLOATING_WIDGET"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, FloatingWidgetService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWidgetService::class.java))
        }
    }
}

private fun Intent.projectionData(): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(FloatingWidgetService.EXTRA_DATA, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(FloatingWidgetService.EXTRA_DATA)
    }
}

private fun Image.toBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val fullWidth = width + rowPadding / pixelStride
    val full = Bitmap.createBitmap(fullWidth, height, Bitmap.Config.ARGB_8888)
    full.copyPixelsFromBuffer(buffer)
    return if (rowPadding == 0) {
        full
    } else {
        Bitmap.createBitmap(full, 0, 0, width, height).also { full.recycle() }
    }
}

private fun String.extractJsonObject(): String {
    val start = indexOf('{')
    val end = lastIndexOf('}')
    return if (start >= 0 && end > start) substring(start, end + 1) else this
}
