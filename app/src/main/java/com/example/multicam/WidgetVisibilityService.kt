package com.example.multicam

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

class WidgetVisibilityService : AccessibilityService() {

    companion object {
        var instance: WidgetVisibilityService? = null
    }

    private lateinit var libVLC: LibVLC
    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeStreams = mutableMapOf<Int, StreamSession>()
    private val missedChecksCount = mutableMapOf<Int, Int>()
    private var isScreenOn = true

    private inner class StreamSession(
        val appWidgetId: Int,
        val container: FrameLayout,
        val textureView: TextureView,
        val params: WindowManager.LayoutParams,
        val mediaPlayer: MediaPlayer,
        var isPlaying: Boolean = false
    )

    private val visibilityCheckRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn) {
                evaluateVisibleWidgets()
                val delay = if (activeStreams.isNotEmpty()) 250L else 800L
                mainHandler.postDelayed(this, delay)
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

        // Reducir tiempo de respuesta de eventos para detectar cambios de ventana al instante
        serviceInfo = serviceInfo.apply {
            notificationTimeout = 30
        }

        libVLC = LibVLC(
            applicationContext,
            arrayListOf("--network-caching=400", "--rtsp-tcp")
        )

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        mainHandler.post(visibilityCheckRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (isScreenOn && event != null) {
            evaluateVisibleWidgets()
        }
    }

    fun forceCheckNow() {
        mainHandler.removeCallbacks(visibilityCheckRunnable)
        mainHandler.post(visibilityCheckRunnable)
    }

    private fun evaluateVisibleWidgets() {
        val root = rootInActiveWindow
        val displayMetrics = resources.displayMetrics
        val screenRect = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        val visibleWidgets = mutableMapOf<Int, Rect>()

        if (root != null) {
            findWidgetsInNode(root, screenRect, visibleWidgets)
            root.recycle()
        }

        val currentIds = activeStreams.keys.toList()
        for (id in currentIds) {
            val session = activeStreams[id] ?: continue
            val rect = visibleWidgets[id]

            if (rect == null) {
                // Ocultar y silenciar al instante al deslizar a otra ventana o abrir una app
                session.container.alpha = 0f
                session.mediaPlayer.volume = 0

                val misses = (missedChecksCount[id] ?: 0) + 1
                missedChecksCount[id] = misses
                // Si permanece fuera de vista por ~750ms, desconectar cámara para ahorrar energía
                if (misses >= 3) {
                    stopStream(id)
                    missedChecksCount.remove(id)
                }
            } else {
                missedChecksCount[id] = 0
                updateOverlayBounds(session, rect)
                if (session.isPlaying) {
                    session.container.alpha = 1f
                    val item = WidgetPrefs.getWidget(this, id)
                    session.mediaPlayer.volume = if (item?.isMuted == true) 0 else 100
                }
            }
        }

        for ((id, rect) in visibleWidgets) {
            missedChecksCount[id] = 0
            if (!activeStreams.containsKey(id)) {
                startStream(id, rect)
            }
        }
    }

    private fun findWidgetsInNode(
        node: AccessibilityNodeInfo?,
        screenRect: Rect,
        visibleWidgets: MutableMap<Int, Rect>
    ) {
        if (node == null) return

        val desc = node.contentDescription?.toString()
        if (desc != null && desc.startsWith("MULTICAM_WIDGET_")) {
            if (node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val intersection = Rect(bounds)
                // Mostrar video solo cuando el widget está asentado en la página actual (>= 85% visible)
                if (intersection.intersect(screenRect) &&
                    bounds.width() > 80 &&
                    bounds.height() > 80 &&
                    intersection.width() >= (bounds.width() * 0.85f) &&
                    intersection.height() >= (bounds.height() * 0.85f)
                ) {
                    val id = desc.removePrefix("MULTICAM_WIDGET_").toIntOrNull()
                    if (id != null && !visibleWidgets.containsKey(id)) {
                        visibleWidgets[id] = bounds
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findWidgetsInNode(child, screenRect, visibleWidgets)
                child.recycle()
            }
        }
    }

    private fun calculateVideoRect(widgetBounds: Rect): Rect {
        val density = resources.displayMetrics.density
        // Respetar los 36dp superiores donde están el título y el botón de silenciar del widget
        val headerPx = (36 * density).toInt()
        val top = widgetBounds.top + headerPx
        val width = widgetBounds.width().coerceAtLeast(100)
        val height = (widgetBounds.height() - headerPx).coerceAtLeast(80)
        return Rect(widgetBounds.left, top, widgetBounds.left + width, top + height)
    }

    private fun updateOverlayBounds(session: StreamSession, widgetBounds: Rect) {
        if (!session.container.isAttachedToWindow) return
        val videoRect = calculateVideoRect(widgetBounds)

        // Calibrar coordenadas exactas de pantalla (compensa barra de estado o notch)
        val loc = IntArray(2)
        session.container.getLocationOnScreen(loc)
        val offsetX = loc[0] - session.params.x
        val offsetY = loc[1] - session.params.y

        val targetX = videoRect.left - offsetX
        val targetY = videoRect.top - offsetY
        val targetW = videoRect.width()
        val targetH = videoRect.height()

        if (session.params.x != targetX ||
            session.params.y != targetY ||
            session.params.width != targetW ||
            session.params.height != targetH
        ) {
            session.params.x = targetX
            session.params.y = targetY
            session.params.width = targetW
            session.params.height = targetH
            windowManager.updateViewLayout(session.container, session.params)
            session.mediaPlayer.vlcVout.setWindowSize(targetW, targetH)
        }
    }

    private fun startStream(appWidgetId: Int, initialBounds: Rect) {
        val item = WidgetPrefs.getWidget(this, appWidgetId) ?: return
        CameraWidgetProvider.updateWidgetUI(this, appWidgetId, null, "Conectando...")

        val videoRect = calculateVideoRect(initialBounds)
        val cornerRadius = 14f * resources.displayMetrics.density

        val container = FrameLayout(this).apply {
            alpha = 0f // Invisible mientras carga para mostrar el texto "Conectando..."
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, -cornerRadius.toInt(), view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
        }

        val textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(textureView)

        val params = WindowManager.LayoutParams(
            videoRect.width(),
            videoRect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = videoRect.left
            y = videoRect.top
        }

        windowManager.addView(container, params)

        val mediaPlayer = MediaPlayer(libVLC)
        val session = StreamSession(appWidgetId, container, textureView, params, mediaPlayer)
        activeStreams[appWidgetId] = session

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                updateOverlayBounds(session, initialBounds)

                val vout = mediaPlayer.vlcVout
                vout.setVideoView(textureView)
                vout.setWindowSize(width, height)
                vout.attachViews()

                val media = Media(libVLC, Uri.parse(item.rtspUrl)).apply {
                    setHWDecoderEnabled(true, false)
                    addOption(":network-caching=400")
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
                                val currentItem = WidgetPrefs.getWidget(this@WidgetVisibilityService, appWidgetId)
                                mediaPlayer.volume = if (currentItem?.isMuted == true) 0 else 100
                                session.container.alpha = 1f
                                CameraWidgetProvider.updateWidgetUI(
                                    this@WidgetVisibilityService,
                                    appWidgetId,
                                    null,
                                    null
                                )
                            }
                            MediaPlayer.Event.EncounteredError -> {
                                session.isPlaying = false
                                session.container.alpha = 0f
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
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                mediaPlayer.vlcVout.setWindowSize(width, height)
            }

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

        // Guardar última captura como vista previa estática cuando el widget entra en ahorro de energía
        var previewBmp: Bitmap? = null
        if (session.textureView.isAvailable) {
            val raw = session.textureView.getBitmap(480, 270)
            if (raw != null) {
                previewBmp = raw.copy(Bitmap.Config.RGB_565, false)
                raw.recycle()
            }
        }

        session.mediaPlayer.stop()
        session.mediaPlayer.vlcVout.detachViews()
        session.mediaPlayer.release()

        if (session.container.isAttachedToWindow) {
            windowManager.removeView(session.container)
        }

        CameraWidgetProvider.updateWidgetUI(
            this,
            appWidgetId,
            previewBmp,
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
        if (::libVLC.isInitialized) {
            libVLC.release()
        }
    }
}
