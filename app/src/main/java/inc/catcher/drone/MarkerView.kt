package inc.catcher.drone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/** Full-screen, touch-through overlay that draws tap rings and the search-area preview. */
class MarkerView(ctx: Context) : View(ctx) {

    private class Mark(val x: Float, val y: Float, val t: Long)

    private val d = resources.displayMetrics.density
    private val marks = ArrayList<Mark>()
    private var region: RectF? = null
    private var regionUntil = 0L
    private val loc = IntArray(2)

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        color = 0xFFFF4081.toInt()
    }
    private val fill = Paint().apply { color = 0x3300E5FF }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        color = 0xFF00E5FF.toInt()
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16 * resources.displayMetrics.scaledDensity
        setShadowLayer(4 * d, 0f, 0f, Color.BLACK)
    }

    /** [x], [y] are screen coordinates. */
    fun addMark(x: Float, y: Float) {
        marks.add(Mark(x, y, SystemClock.uptimeMillis()))
        invalidate()
    }

    /** [rect] is in screen coordinates. */
    fun showRegion(rect: RectF, durationMs: Long) {
        region = rect
        regionUntil = SystemClock.uptimeMillis() + durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        getLocationOnScreen(loc)
        val ox = -loc[0].toFloat()
        val oy = -loc[1].toFloat()

        marks.removeAll { now - it.t > MARK_MS }
        for (m in marks) {
            val f = (now - m.t) / MARK_MS.toFloat()
            ring.alpha = ((1 - f) * 255).toInt()
            canvas.drawCircle(m.x + ox, m.y + oy, (12 + 16 * f) * d, ring)
        }

        region?.let {
            if (now < regionUntil) {
                val r = RectF(it).apply { offset(ox, oy) }
                canvas.drawRect(r, fill)
                canvas.drawRect(r, border)
                canvas.drawText("Drone search area", r.left + 12 * d, r.top + 28 * d, label)
            } else {
                region = null
            }
        }

        if (marks.isNotEmpty() || region != null) postInvalidateOnAnimation()
    }

    private companion object {
        const val MARK_MS = 400L
    }
}
