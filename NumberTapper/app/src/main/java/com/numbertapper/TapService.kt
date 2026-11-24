package com.numbertapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

/** Hace los taps (gestos) y dibuja el texto de estado + marcador cian. */
class TapService : AccessibilityService() {
    companion object {
        @Volatile var instance: TapService? = null
    }

    private val h = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var status: TextView? = null

    private fun lp(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            textSize = 18f
            setPadding(32, 16, 32, 16)
            text = "NumberTapper listo"
        }
        val p = lp().apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 220 }
        wm.addView(status, p)
    }

    fun say(msg: String) { h.post { status?.text = msg } }

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        dispatchGesture(g, null, null)
        // marcador cian en el punto exacto
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x5500FFFF)
                setStroke(6, Color.CYAN)
            }
        }
        val p = lp().apply {
            width = 80; height = 80
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = (x - 40).toInt(); this.y = (y - 40).toInt()
        }
        wm.addView(v, p)
        h.postDelayed({ try { wm.removeView(v) } catch (_: Exception) {} }, 700)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        try { status?.let { wm.removeView(it) } } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }
}
