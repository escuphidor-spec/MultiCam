package com.example.multicam

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    }

    private lateinit var libVLC: LibVLC
    private lateinit var windowManager: WindowManager
    private lateinit var hiddenContainer: LinearLayout
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeStreams = mutableMapOf<Int, StreamSession>()
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
                        val bmp = session.textureView.getBitmap(320, 180)
                        if (bmp != null) {
                            CameraWidgetProvider.updateWidgetUI(
                                this@WidgetVisibilityService,
                                widgetId,
                                bmp,
                                null
                            )
                        }
                    }
                }
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    private val visibilityCheckRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn) {
                evaluateVisibleWidgets()
                mainHandler.postDelayed(this, 850L)
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
        libVLC = LibVLC(applicationContext, arrayListOf("--network-caching=400"))

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hiddenContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            alpha = 0.02f
        }

        val params = WindowManager.LayoutParams(
            240,
            135,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
        if (isScreenOn) {
            evaluateVisibleWidgets()
        }
    }

    fun forceCheckNow() {
        mainHandler.removeCallbacks(visibilityCheckRunnable)
        mainHandler.post(visibilityCheckRunnable)
    }

    private fun evaluateVisibleWidgets() {
        val displayMetrics = resources.displayMetrics
        val screenRect = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        val visibleIds = mutableSetOf<Int>()

        val root = rootInActiveWindow
        if (root != null) {
            findWidgetsInNode(root, screenRect, visibleIds)
            root.recycle()
        }

        val currentIds = activeStreams.keys.toList()
        for (id in currentIds) {
            if (!visibleIds.contains(id)) {
                stopStream(id)
            }
        }

        for (id in visibleIds) {
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
            layoutParams = LinearLayout.LayoutParams(240, 135)
        }
        hiddenContainer.addView(textureView)

        val mediaPlayer = MediaPlayer(libVLC)
        val session = StreamSession(appWidgetId, textureView, mediaPlayer)
        activeStreams[appWidgetId] = session

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val vout = mediaPlayer.vlcVout
                vout.setVideoView(textureView)
                vout.setWindowSize(240, 135)
                vout.attachViews()

                val media = Media(libVLC, Uri.parse(item.rtspUrl)).apply {
                    setHWDecoderEnabled(true, false)
                    addOption(":network-caching=400")
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
