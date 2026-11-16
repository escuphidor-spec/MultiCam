package com.example.multicam

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class WidgetVisibilityService : AccessibilityService() {

    companion object {
        var instance: WidgetVisibilityService? = null
        // Resolución HD optimizada para widgets sin superar el límite de memoria Binder de Android
        private const val VIDEO_WIDTH = 768
        private const val VIDEO_HEIGHT = 432
    }

    private lateinit var libVLC: LibVLC
    private lateinit var windowManager: WindowManager
    private lateinit var hiddenContainer: LinearLayout
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeStreams = mutableMapOf<Int, StreamSession>()
    private val missedChecksCount = mutableMapOf<Int, Int>()
    private var isScreenOn = true

    private inner class StreamSession(
        val appWidgetId: Int,
        val textureView: TextureView,
        val mediaPlayer: MediaPlayer,
        var isPlaying: Boolean = false
    )

    private val frameUpdateRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn && activeStreams.isNotEmpty()) {
                for ((widgetId, session) in activeStreams) {
                    if (session.isPlaying && session.textureView.isAvailable) {
                        val rawBmp = session.textureView.getBitmap(VIDEO_WIDTH, VIDEO_HEIGHT)
                        if (rawBmp != null) {
                            // Convertir a RGB_565 permite enviar alta resolución al widget usando la mitad de RAM
                            val hdBmp = rawBmp.copy(Bitmap.Config.RGB_565, false)
                            rawBmp.recycle()
                            if (hdBmp != null) {
                                CameraWidgetProvider.updateWidgetUI(
                                    this@WidgetVisibilityService,
                                    widgetId,
                                    hdBmp,
                                    null
                                )
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, 220L)
            }
        }
    }

    private val visibilityCheckRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn) {
                evaluateVisibleWidgets()
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    stopAllStreams()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    forceCheckNow()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Forzar RTSP sobre TCP para evitar pixelación y pérdida de fotogramas
        libVLC = LibVLC(
            applicationContext,
            arrayListOf("--network-caching=500", "--rtsp-tcp")
        )

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hiddenContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            alpha = 0.01f
        }

        val params = WindowManager.LayoutParams(
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(hiddenContainer, params)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        mainHandler.post(visibilityCheckRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Ignorar eventos provocados por la propia actualización de fotogramas para evitar parpadeos
        if (isScreenOn && event != null) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
            ) {
                evaluateVisibleWidgets()
            }
        }
    }

    fun forceCheckNow() {
        mainHandler.removeCallbacks(visibilityCheckRunnable)
        mainHandler.post(visibilityCheckRunnable)
    }

    private fun evaluateVisibleWidgets() {
        val root = rootInActiveWindow ?: return
        val displayMetrics = resources.displayMetrics
        val screenRect = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        val visibleIds = mutableSetOf<Int>()

        findWidgetsInNode(root, screenRect, visibleIds)
        root.recycle()

        // Tolerancia de 2 verificaciones antes de pausar para evitar cortes al refrescar imagen
        val currentIds = activeStreams.keys.toList()
        for (id in currentIds) {
            if (!visibleIds.contains(id)) {
                val misses = (missedChecksCount[id] ?: 0) + 1
                missedChecksCount[id] = misses
                if (misses >= 2) {
                    stopStream(id)
                    missedChecksCount.remove(id)
                }
            } else {
                missedChecksCount[id] = 0
            }
        }

        for (id in visibleIds) {
            missedChecksCount[id] = 0
            if (!activeStreams.containsKey(id)) {
                startStream(id)
            }
        }
    }

    private fun findWidgetsInNode(
        node: AccessibilityNodeInfo?,
        screenRect: Rect,
        visibleIds: MutableSet<Int>
    ) {
        if (node == null) return

        val desc = node.contentDescription?.toString()
        if (desc != null && desc.startsWith("MULTICAM_WIDGET_")) {
            if (node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val intersection = Rect(bounds)
                if (intersection.intersect(screenRect) &&
                    bounds.width() > 0 &&
                    intersection.width() >= (bounds.width() * 0.5f)
                ) {
                    val id = desc.removePrefix("MULTICAM_WIDGET_").toIntOrNull()
                    if (id != null) {
                        visibleIds.add(id)
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findWidgetsInNode(child, screenRect, visibleIds)
                child.recycle()
            }
        }
    }

    private fun startStream(appWidgetId: Int) {
        val item = WidgetPrefs.getWidget(this, appWidgetId) ?: return
        CameraWidgetProvider.updateWidgetUI(this, appWidgetId, null, "Conectando...")

        val textureView = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(VIDEO_WIDTH, VIDEO_HEIGHT)
        }
        hiddenContainer.addView(textureView)

        val mediaPlayer = MediaPlayer(libVLC)
        val session = StreamSession(appWidgetId, textureView, mediaPlayer)
        activeStreams[appWidgetId] = session

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val vout = mediaPlayer.vlcVout
                vout.setVideoView(textureView)
                vout.setWindowSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                vout.attachViews()

                val media = Media(libVLC, Uri.parse(item.rtspUrl)).apply {
                    setHWDecoderEnabled(true, false)
                    addOption(":network-caching=500")
                    addOption(":rtsp-tcp")
                }
                mediaPlayer.media = media
                media.release()

                mediaPlayer.setEventListener { event ->
                    mainHandler.post {
                        when (event.type) {
                            MediaPlayer.Event.Playing,
                            MediaPlayer.Event.Vout -> {
                                session.isPlaying = true
                                mediaPlayer.volume = if (item.isMuted) 0 else 100
                            }
                            MediaPlayer.Event.EncounteredError -> {
                                session.isPlaying = false
                                CameraWidgetProvider.updateWidgetUI(
                                    this@WidgetVisibilityService,
                                    appWidgetId,
                                    null,
                                    "Error RTSP"
                                )
                            }
                        }
                    }
                }

                mediaPlayer.play()
                mainHandler.removeCallbacks(frameUpdateRunnable)
                mainHandler.post(frameUpdateRunnable)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    fun updateMuteState(appWidgetId: Int, isMuted: Boolean) {
        activeStreams[appWidgetId]?.mediaPlayer?.volume = if (isMuted) 0 else 100
    }

    fun stopStream(appWidgetId: Int) {
        val session = activeStreams.remove(appWidgetId) ?: return
        session.isPlaying = false
        session.mediaPlayer.stop()
        session.mediaPlayer.vlcVout.detachViews()
        session.mediaPlayer.release()
        hiddenContainer.removeView(session.textureView)

        CameraWidgetProvider.updateWidgetUI(
            this,
            appWidgetId,
            null,
            "En pausa (Ahorro de energía)"
        )
    }

    private fun stopAllStreams() {
        val ids = activeStreams.keys.toList()
        for (id in ids) {
            stopStream(id)
        }
        missedChecksCount.clear()
    }

    override fun onInterrupt() {
        stopAllStreams()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(screenReceiver)
        stopAllStreams()
        if (::hiddenContainer.isInitialized) {
            windowManager.removeView(hiddenContainer)
        }
        if (::libVLC.isInitialized) {
            libVLC.release()
        }
    }
}
