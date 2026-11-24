package com.numbertapper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("nt", MODE_PRIVATE) }
    private val LAV = 0xFFD0BCFF.toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = 0xFF141218.toInt()
        val sv = ScrollView(this).apply { setBackgroundColor(0xFF141218.toInt()) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 60) }
        sv.addView(root); setContentView(sv)
    }

    override fun onResume() { super.onResume(); render() }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, TapService::class.java)
        return s.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
    private fun notifOn() = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun appPkg() = prefs.getString("pkg", null)
    private fun appName(p: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
    } catch (e: Exception) { p }

    private fun tv(t: String, size: Float, color: Int = 0xFFE6E0E9.toInt(), bold: Boolean = false, center: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (center) gravity = Gravity.CENTER
            setPadding(0, 24, 0, 12)
        }

    private fun btn(t: String, on: Boolean = true, click: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; isEnabled = on; textSize = 15f
        setTextColor(if (on) 0xFF381E72.toInt() else 0xFF8A8A8A.toInt())
        background = GradientDrawable().apply {
            cornerRadius = 80f; setColor(if (on) LAV else 0xFF2B2930.toInt())
        }
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, 150).apply { topMargin = 8 }
    }

    private fun render() {
        root.removeAllViews()
        val a = a11yOn(); val n = notifOn(); val p = appPkg()
        val all = a && n && p != null
        root.addView(tv("🎯 NumberTapper", 26f, bold = true))
        root.addView(tv(if (all) "✅ Todo listo" else "Faltan permisos o ajustes (marcados con ❌)", 18f, bold = true))

        root.addView(tv((if (a) "✅" else "❌") + " Servicio de accesibilidad (para pulsar)", 16f))
        root.addView(btn(if (a) "Accesibilidad activada" else "Activar servicio de accesibilidad", !a) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(tv("¿Aparece bloqueado? Abrir info de la app", 14f, LAV, center = true).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        root.addView(tv((if (n) "✅" else "❌") + " Notificaciones", 16f))
        root.addView(btn(if (n) "Notificaciones concedidas" else "Conceder notificaciones", !n) {
            if (prefs.getBoolean("asked", false))
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            else {
                prefs.edit().putBoolean("asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })

        root.addView(tv(if (p != null) "✅ App: ${appName(p)}" else "❌ Ninguna app seleccionada", 16f))
        root.addView(btn(if (p != null) "Cambiar app a vigilar" else "Seleccionar app a vigilar") { pickApp() })

        root.addView(android.view.View(this), LinearLayout.LayoutParams(-1, 60))
        root.addView(btn("Iniciar", all) { startProjection() })
        root.addView(tv("Al pulsar «Iniciar», Android pedirá permiso para capturar la pantalla (último permiso). " +
                "Después se abrirá la app seleccionada y NumberTapper detectará el número más a la izquierda de la fila " +
                "superior, lo mostrará en pantalla y, tras 0,2 s, pulsará el botón de color con ese número, " +
                "mostrando un marcador cian en el punto exacto del toque.", 12f))
    }

    private fun pickApp() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this).setTitle("Selecciona la app")
            .setItems(apps.map { it.second }.toTypedArray()) { _, k ->
                prefs.edit().putString("pkg", apps[k].first).apply(); render()
            }.show()
    }

    private fun startProjection() {
        val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION") startActivityForResult(m.createScreenCaptureIntent(), 42)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 42) return
        if (res != RESULT_OK || data == null) { Toast.makeText(this, "Captura de pantalla denegada", Toast.LENGTH_SHORT).show(); return }
        startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", res).putExtra("data", data))
        appPkg()?.let { packageManager.getLaunchIntentForPackage(it)?.let { li -> startActivity(li) } }
    }
}
