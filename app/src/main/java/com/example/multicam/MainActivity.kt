package com.example.multicam

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.example.multicam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val isActive = WidgetVisibilityService.instance != null
        if (isActive) {
            binding.tvServiceStatus.text = "Detector de Pantalla Principal: ACTIVO"
            binding.tvServiceStatus.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            binding.tvServiceStatus.text = "Detector de Pantalla Principal: INACTIVO (Toca el botón de abajo para activarlo)"
            binding.tvServiceStatus.setTextColor(Color.parseColor("#FF9800"))
        }

        val widgets = WidgetPrefs.getAllWidgets(this)
        val summary = if (widgets.isEmpty()) {
            "No hay widgets activos aún."
        } else {
            "Widgets registrados (${widgets.size}):\n" +
                widgets.values.joinToString("\n") { "• ${it.name}: ${it.rtspUrl}" }
        }
        binding.tvWidgetsList.text = "$summary\n\nCómo agregar un widget:\n1. Activa 'Detector MultiCam' en Accesibilidad.\n2. En tu pantalla de inicio, mantén presionado un espacio vacío -> Widgets -> MultiCam.\n3. Ingresa la dirección RTSP de la cámara para esa ventana."
    }
}
