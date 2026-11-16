package com.example.multicam

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.multicam.databinding.ActivityWidgetConfigBinding

class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetConfigBinding
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        binding = ActivityWidgetConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        binding.etConfigName.setText("Cámara $appWidgetId")
        binding.etConfigUrl.setText("rtsp://admin:Camara2026@192.168.1.201:554/live/ch00_0")

        binding.btnSaveWidget.setOnClickListener {
            val name = binding.etConfigName.text.toString().trim()
            val url = binding.etConfigUrl.text.toString().trim()

            if (name.isNotEmpty() && url.isNotEmpty()) {
                val item = CameraItem(
                    appWidgetId = appWidgetId,
                    name = name,
                    rtspUrl = url,
                    isMuted = false
                )
                WidgetPrefs.saveWidget(this, item)
                CameraWidgetProvider.updateWidgetUI(this, appWidgetId, null, "Listo para conectar")

                val resultValue = Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                setResult(Activity.RESULT_OK, resultValue)
                WidgetVisibilityService.instance?.forceCheckNow()
                finish()
            }
        }
    }
}
