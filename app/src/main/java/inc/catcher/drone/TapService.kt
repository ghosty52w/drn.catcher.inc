package inc.catcher.drone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlin.math.hypot

/**
 * The accessibility service: sends taps, shows the floating pause/stop button and the
 * tap markers, and tells [CaptureService] whether Egg, Inc. is the app on screen.
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
            private set

        private const val TAP_MS = 40L
        private const val ACTIVE_COLOR = 0xDD2E7D32.toInt()
        private const val PAUSED_COLOR = 0xDD424242.toInt()
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    @Volatile
    private var lastPackage: String? = null

    private var controls: TextView? = null
    private var controlsLp: WindowManager.LayoutParams? = null
    @Volatile
    private var controlsRect: IntArray? = null
    private var markers: MarkerView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        instance = this
        CaptureService.instance?.let {
            showControls()
            it.refreshControls()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        lastPackage = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        instance = null
        hideControls()
        markers?.let { runCatching { wm.removeView(it) } }
        markers = null
    }

    /** Must be called on the main thread. */
    fun isEggIncForeground(): Boolean {
        val active = try {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            @Suppress("DEPRECATION")
            root?.recycle()
            pkg
        } catch (e: RuntimeException) {
            null
        }
        return (active ?: lastPackage) == Prefs.EGG_INC
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Brings Egg, Inc. back to the front (resuming it if it's still running). */
    fun bringEggIncToFront(): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(Prefs.EGG_INC) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    fun mark(x: Float, y: Float) = ensureMarkers()?.addMark(x, y)

    /** Highlights the search area for a few seconds. Fractions of the screen height. */
    fun previewRegion(top: Float, bottom: Float) {
        val m = Screen.realMetrics(this)
        val rect = RectF(0f, top * m.heightPixels, m.widthPixels.toFloat(), bottom * m.heightPixels)
        ensureMarkers()?.showRegion(rect, 5000)
    }

    /** Our floating button in screen coordinates (left, top, right, bottom), if shown. */
    fun controlsRect(): IntArray? = controlsRect

    fun showControls() {
        if (controls != null || !::wm.isInitialized) return
        val d = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = "🛸 0"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(ACTIVE_COLOR)
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (Screen.realMetrics(this@TapService).heightPixels * 0.68f).toInt()
        }
        view.setOnTouchListener(DragListener(lp))
        try {
            wm.addView(view, lp)
        } catch (e: RuntimeException) {
            return
        }
        controls = view
        controlsLp = lp
        updateControlsRect()
    }

    fun hideControls() {
        controls?.let { runCatching { wm.removeView(it) } }
        controls = null
        controlsLp = null
        controlsRect = null
    }

    fun setControlsState(label: String, active: Boolean) {
        val view = controls ?: return
        view.text = label
        (view.background as? GradientDrawable)?.setColor(if (active) ACTIVE_COLOR else PAUSED_COLOR)
        updateControlsRect()
    }

    private fun updateControlsRect() {
        val view = controls ?: return
        view.post {
            if (controls !== view) return@post
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            controlsRect = intArrayOf(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        }
    }

    private fun ensureMarkers(): MarkerView? {
        markers?.let { return it }
        if (!::wm.isInitialized) return null
        val view = MarkerView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(view, lp)
        } catch (e: RuntimeException) {
            return null
        }
        markers = view
        return view
    }

    /** Drag to move the button, tap to pause/resume, long-press to stop. */
    private inner class DragListener(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@TapService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            CaptureService.instance?.stopCapture()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging && controls === v) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!dragging && !longPressed) CaptureService.instance?.togglePause()
                    updateControlsRect()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }
}
