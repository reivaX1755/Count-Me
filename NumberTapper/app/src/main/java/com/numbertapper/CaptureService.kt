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
import kotlin.math.max

/** Captura la pantalla, lee la secuencia superior y localiza el botón coincidente. */
class CaptureService : Service() {
    companion object {
        const val ACTION_STOP = "stop"

        // Regiones relativas a la pantalla. La fila superior y el teclado se procesan por separado.
        const val TOP_CROP_MIN = 0.12f
        const val TOP_CROP_MAX = 0.37f
        const val GRID_CROP_MIN = 0.43f

        private const val TOP_SCALE = 3f
        private const val GRID_SCALE = 2f
        private const val ACTIVE_SCALE = 4f

        const val TAP_DELAY_MS = 250L
        const val COOLDOWN_MS = 300L
        private const val SAME_TARGET_GUARD_MS = 1500L
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
        override fun run() {
            if (!busy) grab()
            h.postDelayed(this, 250)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i == null || i.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startFg()
        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data")
            ?: run {
                stopSelf()
                return START_NOT_STICKY
            }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        proj = mpm.getMediaProjection(code, data)
        proj?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, h)

        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        W = dm.widthPixels
        H = dm.heightPixels

        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        vd = proj?.createVirtualDisplay(
            "nt", W, H, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, h
        )
        h.postDelayed(loop, 1500)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("nt", "NumberTapper", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "nt")
            .setContentTitle("NumberTapper activo")
            .setContentText("Analizando la pantalla")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else
            startForeground(1, n)
    }

    private fun grab() {
        val img = reader?.acquireLatestImage() ?: return
        val bmp = try {
            val p = img.planes[0]
            val pad = (p.rowStride - p.pixelStride * W) / p.pixelStride
            Bitmap.createBitmap(W + pad, H, Bitmap.Config.ARGB_8888).also {
                it.copyPixelsFromBuffer(p.buffer)
            }
        } catch (_: Exception) {
            img.close()
            return
        }
        // ImageReader.Image debe cerrarse en cuanto sus píxeles se han copiado.
        img.close()

        busy = true
        val topStart = (TOP_CROP_MIN * H).toInt().coerceIn(0, H - 1)
        val topEnd = (TOP_CROP_MAX * H).toInt().coerceIn(topStart + 1, H)
        val gridStart = (GRID_CROP_MIN * H).toInt().coerceIn(0, H - 1)

        val topBmp: Bitmap
        val gridBmp: Bitmap
        try {
            topBmp = scaledCrop(bmp, topStart, topEnd, TOP_SCALE)
            gridBmp = scaledCrop(bmp, gridStart, H, GRID_SCALE)
        } catch (_: Exception) {
            if (!bmp.isRecycled) bmp.recycle()
            busy = false
            return
        }

        // OCR independiente: ampliamos mucho la fila pequeña y también los botones inferiores.
        rec.process(InputImage.fromBitmap(topBmp, 0))
            .addOnSuccessListener { topText ->
                rec.process(InputImage.fromBitmap(gridBmp, 0))
                    .addOnSuccessListener { gridText ->
                        recycle(topBmp)
                        recycle(gridBmp)
                        try {
                            analyze(bmp, topText, gridText, topStart, gridStart)
                        } catch (_: Exception) {
                            TapService.instance?.say("Error analizando los números")
                            busy = false
                        } finally {
                            recycle(bmp)
                        }
                    }
                    .addOnFailureListener {
                        recycle(topBmp)
                        recycle(gridBmp)
                        recycle(bmp)
                        TapService.instance?.say("No se pudo leer la zona de botones")
                        busy = false
                    }
            }
            .addOnFailureListener {
                recycle(topBmp)
                recycle(gridBmp)
                recycle(bmp)
                TapService.instance?.say("No se pudo leer la secuencia superior")
                busy = false
            }
    }

    private fun scaledCrop(source: Bitmap, top: Int, bottom: Int, scale: Float): Bitmap {
        val crop = Bitmap.createBitmap(source, 0, top, W, bottom - top)
        val scaled = Bitmap.createScaledBitmap(
            crop,
            (crop.width * scale).toInt().coerceAtLeast(1),
            (crop.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== crop) crop.recycle()
        return scaled
    }

    private fun analyze(bmp: Bitmap, topText: Text, gridText: Text, topStart: Int, gridStart: Int) {
        val topDigits = extractDigits(topText, TOP_SCALE, topStart)
        val row = findSequenceRow(topDigits)
        val sequence = row.joinToString("") { it.c.toString() }
        val gridDigits = extractDigits(gridText, GRID_SCALE, gridStart)

        // El amarillo solo identifica QUÉ casilla de la secuencia está activa.
        // La cifra se obtiene por OCR, sin depender de que ML Kit lea bien el fondo amarillo.
        val activeRect = findYellowRect(bmp, topStart, (TOP_CROP_MAX * H).toInt())
        if (activeRect == null) {
            TapService.instance?.say(
                if (sequence.isNotEmpty()) "Secuencia [$sequence] · no veo la casilla activa amarilla"
                else "No detecto la secuencia superior ni la casilla amarilla"
            )
            busy = false
            return
        }

        val activeGlyph = row.minByOrNull {
            abs(it.r.centerX() - activeRect.centerX())
        }
        val maxDistance = max(16f, activeRect.width() * 0.45f)

        if (activeGlyph != null && abs(activeGlyph.r.centerX() - activeRect.centerX()) <= maxDistance) {
            processTarget(sequence, activeRect, activeGlyph.c, gridDigits)
        } else {
            // Fallback importante: si el OCR de la fila omite justo el dígito amarillo,
            // recortamos SOLO esa casilla y la ampliamos todavía más.
            readActiveTile(bmp, activeRect) { target ->
                processTarget(sequence, activeRect, target, gridDigits)
            }
        }
    }

    private fun extractDigits(text: Text, scale: Float, offsetY: Int): List<D> {
        val result = mutableListOf<D>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val s = element.text
                    val r = element.boundingBox ?: continue
                    if (s.isEmpty() || !s.all { it.isDigit() }) continue

                    val charWidth = r.width().toFloat() / s.length
                    s.forEachIndexed { index, ch ->
                        val left = (r.left + index * charWidth).toInt()
                        val right = (r.left + (index + 1) * charWidth).toInt()
                        result += D(
                            ch,
                            Rect(
                                (left / scale).toInt(),
                                (r.top / scale).toInt() + offsetY,
                                (right / scale).toInt().coerceAtLeast((left / scale).toInt() + 1),
                                (r.bottom / scale).toInt() + offsetY
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    /** Devuelve el conjunto de dígitos que forma la fila superior, ordenado de izquierda a derecha. */
    private fun findSequenceRow(digits: List<D>): List<D> {
        if (digits.isEmpty()) return emptyList()

        val anchor = digits.maxByOrNull { candidate ->
            val tolerance = max(8f, candidate.r.height() * 0.75f)
            digits.count { abs(it.r.centerY() - candidate.r.centerY()) <= tolerance }
        } ?: return emptyList()

        val tolerance = max(10f, anchor.r.height() * 0.85f)
        return digits
            .filter { abs(it.r.centerY() - anchor.r.centerY()) <= tolerance }
            .sortedBy { it.r.left }
    }

    /** Busca el rectángulo amarillo de la fila superior mediante sus píxeles, no mediante OCR. */
    private fun findYellowRect(bmp: Bitmap, top: Int, bottom: Int): Rect? {
        var minX = W
        var minY = H
        var maxX = -1
        var maxY = -1
        var count = 0

        val y0 = top.coerceIn(0, H - 1)
        val y1 = bottom.coerceIn(y0 + 1, H)
        for (y in y0 until y1 step 2) {
            for (x in 0 until W step 2) {
                val color = bmp.getPixel(x, y)
                val red = (color shr 16) and 255
                val green = (color shr 8) and 255
                val blue = color and 255
                if (red > 170 && green > 135 && blue < 135 && green > blue * 1.3f) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                    count++
                }
            }
        }

        if (count < 60 || maxX < minX || maxY < minY) return null
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun readActiveTile(bmp: Bitmap, tile: Rect, onResult: (Char?) -> Unit) {
        val padX = max(2, tile.width() / 12)
        val padY = max(2, tile.height() / 12)
        val left = (tile.left - padX).coerceIn(0, bmp.width - 1)
        val top = (tile.top - padY).coerceIn(0, H - 1)
        val right = (tile.right + padX).coerceIn(left + 1, bmp.width)
        val bottom = (tile.bottom + padY).coerceIn(top + 1, H)

        val crop: Bitmap
        val enlarged: Bitmap
        try {
            crop = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
            enlarged = Bitmap.createScaledBitmap(
                crop,
                crop.width * ACTIVE_SCALE.toInt(),
                crop.height * ACTIVE_SCALE.toInt(),
                true
            )
            if (enlarged !== crop) crop.recycle()
        } catch (_: Exception) {
            onResult(null)
            return
        }

        rec.process(InputImage.fromBitmap(enlarged, 0))
            .addOnSuccessListener { text ->
                val digits = extractDigits(text, ACTIVE_SCALE, top)
                recycle(enlarged)
                onResult(digits.maxByOrNull { it.r.height() }?.c)
            }
            .addOnFailureListener {
                recycle(enlarged)
                onResult(null)
            }
    }

    private fun processTarget(sequence: String, activeRect: Rect, target: Char?, gridDigits: List<D>) {
        if (target == null) {
            TapService.instance?.say("Secuencia [$sequence] · amarillo visto, número no reconocido")
            busy = false
            return
        }

        val button = gridDigits
            .filter { it.c == target && it.r.centerY() >= (GRID_CROP_MIN * H).toInt() }
            .maxByOrNull { it.r.height() }

        if (button == null) {
            TapService.instance?.say("Secuencia [$sequence] · activo $target · botón $target no detectado")
            busy = false
            return
        }

        val key = "${activeRect.centerX() / 10}:$target"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastTap < SAME_TARGET_GUARD_MS) {
            busy = false
            return
        }

        val svc = TapService.instance
        if (svc == null) {
            busy = false
            return
        }

        svc.say("Secuencia [$sequence] · pulsando $target")
        lastKey = key
        h.postDelayed({
            TapService.instance?.tap(button.r.centerX().toFloat(), button.r.centerY().toFloat())
            lastTap = System.currentTimeMillis()
            h.postDelayed({ busy = false }, COOLDOWN_MS)
        }, TAP_DELAY_MS)
    }

    private fun recycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        vd?.release()
        reader?.close()
        proj?.stop()
        rec.close()
        TapService.instance?.say("NumberTapper detenido")
        super.onDestroy()
    }
}
