package com.numbertapper

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
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

/** Captura la pantalla, localiza el cuadrado amarillo (número a pulsar) y toca el botón igual. */
class CaptureService : Service() {
    companion object {
        const val ACTION_STOP = "stop"
        // Zonas (fracción de la altura de pantalla)
        const val TOP_MIN = 0.03f      // donde se busca el cuadrado amarillo
        const val TOP_MAX = 0.42f
        const val GRID_MIN = 0.42f     // desde aquí se buscan los botones de colores
        const val TAP_DELAY_MS = 200L  // espera antes del tap
        const val COOLDOWN_MS = 700L   // pausa tras cada tap
    }

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

    // ---------- 1) captura y localización del cuadrado amarillo ----------
    private fun grab() {
        val img = reader?.acquireLatestImage() ?: return
        try { grabInner(img) } catch (e: Exception) {
            busy = false
            TapService.instance?.say("Error: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun grabInner(img: android.media.Image) {
        val bmp = try {
            val p = img.planes[0]
            val pad = (p.rowStride - p.pixelStride * W) / p.pixelStride
            Bitmap.createBitmap(W + pad, H, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(p.buffer) }
        } finally { img.close() }

        val box = findYellow(bmp)
        if (box == null) { TapService.instance?.say("Buscando cuadrado amarillo..."); return }
        TapService.instance?.say("Amarillo encontrado, leyendo número...")
        busy = true
        rec.process(InputImage.fromBitmap(tileBitmap(bmp, box), 0))
            .addOnSuccessListener { onTile(bmp, box, it) }
            .addOnFailureListener { busy = false }
    }

    /** Amarillo (FFEB3B) con número negro = el que toca pulsar. */
    private fun findYellow(bmp: Bitmap): Rect? {
        val y0 = (TOP_MIN * H).toInt(); val y1 = (TOP_MAX * H).toInt()
        val hsv = FloatArray(3)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1; var n = 0
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < W) {
                val c = bmp.getPixel(x, y)
                Color.colorToHSV(c, hsv)
                if (hsv[0] in 50f..66f && hsv[1] > 0.45f && hsv[2] > 0.8f) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    n++
                }
                x += 3
            }
            y += 3
        }
        if (n < 30) return null
        val box = Rect(minX, minY, maxX, maxY)
        val w = box.width(); val hh = box.height()
        if (w < 30 || hh < 30 || w > hh * 2.5 || hh > w * 2.5) return null   // debe ser un cuadrado
        if (n * 9f / (w * hh) < 0.35f) return null                              // y estar bien relleno
        return box
    }

    /** Recorta el cuadrado, lo amplía x2 y lo pasa a blanco/negro para que el OCR lea mejor. */
    private fun tileBitmap(bmp: Bitmap, box: Rect): Bitmap {
        val inset = (box.width() * 0.12f).toInt()
        val r = Rect(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
        val s = 2; val pad = 24
        val ow = r.width() * s + pad * 2; val oh = r.height() * s + pad * 2
        val px = IntArray(ow * oh) { Color.WHITE }
        for (y in 0 until r.height()) for (x in 0 until r.width()) {
            val c = bmp.getPixel(r.left + x, r.top + y)
            val l = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            if (l < 110) for (dy in 0 until s) for (dx in 0 until s)
                px[(pad + y * s + dy) * ow + pad + x * s + dx] = Color.BLACK
        }
        return Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { it.setPixels(px, 0, ow, 0, 0, ow, oh) }
    }

    // ---------- 2) número leído en el cuadrado amarillo ----------
    private fun onTile(bmp: Bitmap, box: Rect, t: Text) {
        val digits = t.text.filter { it.isDigit() }
        if (digits.isEmpty()) {
            TapService.instance?.say("Amarillo visto, pero OCR no lee número: '${t.text}'")
            busy = false; return
        }
        val target = digits[0]
        val key = "$target@${box.left / 20}"
        if (key == lastKey && System.currentTimeMillis() - lastTap < 2000) { busy = false; return }
        TapService.instance?.say("Detectado: [$target]")

        val gy = (GRID_MIN * H).toInt()
        val grid = Bitmap.createBitmap(bmp, 0, gy, W, H - gy)
        rec.process(InputImage.fromBitmap(grid, 0))
            .addOnSuccessListener { onGrid(it, gy, target, key) }
            .addOnFailureListener { busy = false }
    }

    // ---------- 3) buscar el botón de color con ese número y tocar ----------
    private fun onGrid(t: Text, gy: Int, target: Char, key: String) {
        var best: Rect? = null
        for (b in t.textBlocks) for (l in b.lines) for (e in l.elements) {
            val s = e.text; val r = e.boundingBox ?: continue
            if (s.isEmpty() || !s.all { it.isDigit() }) continue
            val cw = r.width().toFloat() / s.length
            s.forEachIndexed { k, ch ->
                if (ch == target) {
                    val d = Rect((r.left + k * cw).toInt(), r.top, (r.left + (k + 1) * cw).toInt(), r.bottom)
                    if (best == null || d.height() > best!!.height()) best = d
                }
            }
        }
        val hit = best
        if (hit == null) {
            TapService.instance?.say("Detectado: [$target] (botón no encontrado)")
            busy = false; return
        }
        lastKey = key
        val x = hit.centerX().toFloat(); val y = (hit.centerY() + gy).toFloat()
        h.postDelayed({
            TapService.instance?.tap(x, y)
            lastTap = System.currentTimeMillis()
            h.postDelayed({ busy = false }, COOLDOWN_MS)
        }, TAP_DELAY_MS)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        vd?.release(); reader?.close(); proj?.stop()
        TapService.instance?.say("NumberTapper detenido")
        super.onDestroy()
    }
}
