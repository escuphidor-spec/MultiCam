package com.example.multicam

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.random.Random

class WidgetVisibilityService : AccessibilityService() {

    companion object {
        var instance: WidgetVisibilityService? = null
    }

    private lateinit var libVLC: LibVLC
    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ptzExecutor = Executors.newCachedThreadPool()
    private val activeStreams = mutableMapOf<Int, StreamSession>()
    private val missedChecksCount = mutableMapOf<Int, Int>()
    private val profileTokensCache = mutableMapOf<String, String>()
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
                session.container.visibility = View.GONE
                session.mediaPlayer.volume = 0

                val misses = (missedChecksCount[id] ?: 0) + 1
                missedChecksCount[id] = misses
                if (misses >= 3) {
                    stopStream(id)
                    missedChecksCount.remove(id)
                }
            } else {
                missedChecksCount[id] = 0
                updateOverlayBounds(session, rect)
                if (session.isPlaying) {
                    session.container.visibility = View.VISIBLE
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
        val headerPx = (36 * density).toInt()
        val top = widgetBounds.top + headerPx
        val width = widgetBounds.width().coerceAtLeast(100)
        val height = (widgetBounds.height() - headerPx).coerceAtLeast(80)
        return Rect(widgetBounds.left, top, widgetBounds.left + width, top + height)
    }

    private fun updateOverlayBounds(session: StreamSession, widgetBounds: Rect) {
        if (!session.container.isAttachedToWindow) return
        val videoRect = calculateVideoRect(widgetBounds)

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

    @SuppressLint("ClickableViewAccessibility")
    private fun startStream(appWidgetId: Int, initialBounds: Rect) {
        val item = WidgetPrefs.getWidget(this, appWidgetId) ?: return
        CameraWidgetProvider.updateWidgetUI(this, appWidgetId, null, "Conectando...")

        val videoRect = calculateVideoRect(initialBounds)
        val cornerRadius = 14f * resources.displayMetrics.density
        val swipeThreshold = 35f * resources.displayMetrics.density

        val container = FrameLayout(this).apply {
            alpha = 0f
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

        // Detector de Zoom por pellizco (ScaleGestureDetector)
        var currentScale = 1f
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale *= detector.scaleFactor
                currentScale = currentScale.coerceIn(1f, 4f) // Zoom máximo de 4x
                textureView.scaleX = currentScale
                textureView.scaleY = currentScale
                return true
            }
        })

        // Detector de gestos de deslizamiento para PTZ y Zoom táctil
        var downX = 0f
        var downY = 0f
        container.setOnTouchListener { _, event ->
            missedChecksCount[appWidgetId] = 0
            scaleDetector.onTouchEvent(event)

            if (!scaleDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dx) > swipeThreshold || abs(dy) > swipeThreshold) {
                            val currentItem = WidgetPrefs.getWidget(this@WidgetVisibilityService, appWidgetId)
                            if (currentItem != null) {
                                if (abs(dx) > abs(dy)) {
                                    val pan = if (dx > 0) 0.6f else -0.6f
                                    moveCameraPtz(currentItem.rtspUrl, pan, 0f)
                                } else {
                                    val tilt = if (dy < 0) 0.6f else -0.6f
                                    moveCameraPtz(currentItem.rtspUrl, 0f, tilt)
                                }
                            }
                        }
                        true
                    }
                    else -> true
                }
            } else {
                true
            }
        }

        val params = WindowManager.LayoutParams(
            videoRect.width(),
            videoRect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
                                session.container.visibility = View.VISIBLE
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
                                session.container.visibility = View.GONE
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

    private fun moveCameraPtz(rtspUrl: String, panX: Float, tiltY: Float) {
        ptzExecutor.execute {
            try {
                val uri = Uri.parse(rtspUrl)
                val host = uri.host ?: return@execute
                val rtspPort = if (uri.port != -1) uri.port else 554
                val onvifPort = if (rtspPort > 1000) (rtspPort - 554) + 8899 else 8899

                val userInfo = uri.userInfo ?: ""
                val user = userInfo.substringBefore(":", "admin")
                val pass = if (userInfo.contains(":")) userInfo.substringAfter(":") else ""

                val cacheKey = "$host:$onvifPort"
                val token = profileTokensCache[cacheKey] ?: fetchProfileToken(host, onvifPort, user, pass).also {
                    profileTokensCache[cacheKey] = it
                }

                val moveBody = """
                    <tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
                        <tptz:ProfileToken>$token</tptz:ProfileToken>
                        <tptz:Velocity>
                            <tt:PanTilt x="$panX" y="$tiltY"/>
                        </tptz:Velocity>
                    </tptz:ContinuousMove>
                """.trimIndent()

                val stopBody = """
                    <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                        <tptz:ProfileToken>$token</tptz:ProfileToken>
                        <tptz:PanTilt>true</tptz:PanTilt>
                        <tptz:Zoom>true</tptz:Zoom>
                    </tptz:Stop>
                """.trimIndent()

                sendOnvifSoap(host, onvifPort, "/onvif/ptz_service", user, pass, moveBody)
                Thread.sleep(450)
                sendOnvifSoap(host, onvifPort, "/onvif/ptz_service", user, pass, stopBody)
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchProfileToken(host: String, onvifPort: Int, user: String, pass: String): String {
        val body = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>"""
        val response = sendOnvifSoap(host, onvifPort, "/onvif/media_service", user, pass, body)
        val match = Regex("""Profiles[^>]*token="([^"]+)"""").find(response)
        return match?.groupValues?.get(1) ?: "Profile_1"
    }

    private fun sendOnvifSoap(
        host: String,
        port: Int,
        path: String,
        user: String,
        pass: String,
        bodyXml: String
    ): String {
        val securityHeader = if (user.isNotEmpty()) buildWsSecurityHeader(user, pass) else ""
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
                $securityHeader
                <s:Body>$bodyXml</s:Body>
            </s:Envelope>
        """.trimIndent()

        val endpoints = listOf(path, "/onvif/ptz", "/onvif/device_service")
        for (ep in endpoints) {
            try {
                val url = URL("http://$host:$port$ep")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2000
                    readTimeout = 2000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
                }
                conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                }
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun buildWsSecurityHeader(user: String, pass: String): String {
        val nonceBytes = ByteArray(16).also { Random.nextBytes(it) }
        val nonceBase64 = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val created = sdf.format(Date())

        val md = MessageDigest.getInstance("SHA-1")
        md.update(nonceBytes)
        md.update(created.toByteArray(Charsets.UTF_8))
        md.update(pass.toByteArray(Charsets.UTF_8))
        val digestBase64 = Base64.encodeToString(md.digest(), Base64.NO_WRAP)

        return """
            <s:Header>
                <Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                    <UsernameToken>
                        <Username>$user</Username>
                        <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestBase64</Password>
                        <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceBase64</Nonce>
                        <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
                    </UsernameToken>
                </Security>
            </s:Header>
        """.trimIndent()
    }

    fun updateMuteState(appWidgetId: Int, isMuted: Boolean) {
        activeStreams[appWidgetId]?.mediaPlayer?.volume = if (isMuted) 0 else 100
    }

    fun stopStream(appWidgetId: Int) {
        val session = activeStreams.remove(appWidgetId) ?: return
        session.isPlaying = false

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
        ptzExecutor.shutdownNow()
        if (::libVLC.isInitialized) {
            libVLC.release()
        }
    }
}
