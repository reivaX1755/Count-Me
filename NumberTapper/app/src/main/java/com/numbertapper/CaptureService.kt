package com.numbertapper

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/** Captura la pantalla, lee los números con OCR y ordena el tap. */
class CaptureService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
        // Ajustes (fracción de la altura de pantalla)
        const val TOP_MIN = 0.04f
        const val TOP_MAX = 0.38f      // fila superior de 8 números
        const val GRID_MIN = 0.38f     // botones de colores
        const val TAP_DELAY_MS = 250L  // espera antes del tap
        const val COOLDOWN_MS = 250L   // pausa tras cada tap
    }

    private data class D(val c: Char, val r: Rect)

    private val h = Handler(Looper.getMainLooper())
    private var proj: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var busy = false
    private var lastKey = ""
    private var lastTap = 0L
    private var W = 0
    private var H = 0

    private val loop = object : Runnable {
        override fun run() { if (!busy) grab(); h.postDelayed(this, 250) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i == null || i.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startFg()
        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data") ?: run { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        proj = mpm.getMediaProjection(code, data)
        proj?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, h)

        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        W = dm.widthPixels; H = dm.heightPixels
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        vd = proj?.createVirtualDisplay("nt", W, H, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, h)
        h.postDelayed(loop, 1500)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("nt", "NumberTapper", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nt")
            .setContentTitle("NumberTapper activo")
            .setContentText("Analizando la pantalla")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun grab() {
        val img = reader?.acquireLatestImage() ?: return
        val bmp = try {
            val p = img.planes[0]
            val pad = (p.rowStride - p.pixelStride * W) / p.pixelStride
            Bitmap.createBitmap(W + pad, H, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(p.buffer) }
        } finally { img.close() }
        busy = true
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { analyze(bmp, it) }
            .addOnFailureListener { busy = false }
    }

    private fun analyze(bmp: Bitmap, t: Text) {
        var hold = false
        try {
            val ds = mutableListOf<D>()
            for (b in t.textBlocks) for (l in b.lines) for (e in l.elements) {
                val s = e.text; val r = e.boundingBox ?: continue
                if (s.isEmpty() || !s.all { it.isDigit() }) continue
                val cw = r.width().toFloat() / s.length
                s.forEachIndexed { k, ch ->
                    ds += D(ch, Rect((r.left + k * cw).toInt(), r.top, (r.left + (k + 1) * cw).toInt(), r.bottom))
                }
            }
            // --- fila superior: el grupo más numeroso alineado en Y ---
            val top = ds.filter { it.r.centerY() in (TOP_MIN * H).toInt()..(TOP_MAX * H).toInt() }
            val row = top.map { d -> top.filter { abs(it.r.centerY() - d.r.centerY()) < d.r.height() * 0.6f } }
                .maxByOrNull { it.size }?.sortedBy { it.r.left } ?: return
            if (row.size < 2) return
            // el más a la izquierda que no esté ya marcado como hecho (fondo verde)
            val cur = row.firstOrNull { !isDone(bmp, it.r) } ?: return
            val target = cur.c
            val rowStr = row.joinToString("") { it.c.toString() }
            val key = "$rowStr@${cur.r.left / 20}"
            val now = System.currentTimeMillis()
            if (key == lastKey && now - lastTap < 2000) return

            val svc = TapService.instance
            val grid = ds.filter { it.c == target && it.r.centerY() > GRID_MIN * H }
                .maxByOrNull { it.r.height() }
            if (grid == null) { svc?.say("Detectado: [$target] (botón no encontrado)"); return }

            svc?.say("Detectado: [$target]")
            lastKey = key
            hold = true
            h.postDelayed({
                TapService.instance?.tap(grid.r.centerX().toFloat(), grid.r.centerY().toFloat())
                lastTap = System.currentTimeMillis()
                h.postDelayed({ busy = false }, COOLDOWN_MS)
            }, TAP_DELAY_MS)
        } finally { if (!hold) busy = false }
    }

    /** Fondo verde/teal (por encima/debajo del dígito) = ya completado. */
    private fun isDone(b: Bitmap, r: Rect): Boolean {
        val off = (r.height() * 0.6f).toInt()
        var rs = 0; var gs = 0; var n = 0
        for (y in listOf(r.top - off, r.bottom + off)) {
            val x = r.centerX()
            if (x in 0 until b.width && y in 0 until b.height) {
                val c = b.getPixel(x, y)
                rs += (c shr 16) and 255; gs += (c shr 8) and 255; n++
            }
        }
        return n > 0 && gs / n > rs / n + 25 && gs / n > 100
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        vd?.release(); reader?.close(); proj?.stop()
        TapService.instance?.say("NumberTapper detenido")
        super.onDestroy()
    }
}
