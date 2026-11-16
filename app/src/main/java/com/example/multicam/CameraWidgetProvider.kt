package com.example.multicam

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CameraItem(
    val appWidgetId: Int,
    val name: String,
    val rtspUrl: String,
    var isMuted: Boolean = false
)

object WidgetPrefs {
    private const val PREFS_NAME = "multicam_widget_prefs"
    private const val KEY_MAP = "widgets_map"

    // Clase explícita para evitar errores de compilación con objetos anónimos en Kotlin
    private class MapTypeToken : TypeToken>()

    fun saveWidget(context: Context, item: CameraItem) {
        val map = getAllWidgets(context).toMutableMap()
        map[item.appWidgetId] = item
        saveMap(context, map)
    }

    fun getWidget(context: Context, appWidgetId: Int): CameraItem? {
        return getAllWidgets(context)[appWidgetId]
    }

    fun removeWidget(context: Context, appWidgetId: Int) {
        val map = getAllWidgets(context).toMutableMap()
        map.remove(appWidgetId)
        saveMap(context, map)
    }

    fun getAllWidgets(context: Context): Map<Int, CameraItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MAP, null) ?: return emptyMap()
        
        return Gson().fromJson(json, MapTypeToken().type) ?: emptyMap()
    }

    private fun saveMap(context: Context, map: Map<Int, CameraItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MAP, Gson().toJson(map)).apply()
    }
}

class CameraWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_MUTE = "com.example.multicam.ACTION_TOGGLE_MUTE"
        const val ACTION_MANUAL_WAKE = "com.example.multicam.ACTION_MANUAL_WAKE"
        const val EXTRA_WIDGET_ID = "extra_widget_id"

        fun updateWidgetUI(
            context: Context,
            appWidgetId: Int,
            bitmap: Bitmap? = null,
            statusText: String? = "En pausa (Ahorro de energía)"
        ) {
            val item = WidgetPrefs.getWidget(context, appWidgetId) ?: return
            val views = RemoteViews(context.packageName, R.layout.camera_widget)

            val tag = "MULTICAM_WIDGET_$appWidgetId"
            views.setContentDescription(R.id.widgetRoot, tag)
            views.setContentDescription(R.id.ivWidgetStream, tag)
            views.setTextViewText(R.id.tvWidgetName, item.name)

            val muteIcon = if (item.isMuted) {
                android.R.drawable.ic_lock_silent_mode
            } else {
                android.R.drawable.ic_lock_silent_mode_off
            }
            views.setImageViewResource(R.id.btnWidgetMute, muteIcon)

            if (bitmap != null) {
                views.setImageViewBitmap(R.id.ivWidgetStream, bitmap)
            }

            if (statusText != null) {
                views.setViewVisibility(R.id.tvWidgetStatus, View.VISIBLE)
                views.setTextViewText(R.id.tvWidgetStatus, statusText)
            } else {
                views.setViewVisibility(R.id.tvWidgetStatus, View.GONE)
            }

            val muteIntent = Intent(context, CameraWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_MUTE
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val mutePending = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetMute, mutePending)

            val wakeIntent = Intent(context, CameraWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_WAKE
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val wakePending = PendingIntent.getBroadcast(
                context,
                appWidgetId + 10000,
                wakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.ivWidgetStream, wakePending)

            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidgetUI(context, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val id = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        when (intent.action) {
            ACTION_TOGGLE_MUTE -> {
                val item = WidgetPrefs.getWidget(context, id) ?: return
                item.isMuted = !item.isMuted
                WidgetPrefs.saveWidget(context, item)
                WidgetVisibilityService.instance?.updateMuteState(id, item.isMuted)
                updateWidgetUI(context, id, null, null)
            }
            ACTION_MANUAL_WAKE -> {
                WidgetVisibilityService.instance?.forceCheckNow()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            WidgetVisibilityService.instance?.stopStream(id)
            WidgetPrefs.removeWidget(context, id)
        }
    }
}
