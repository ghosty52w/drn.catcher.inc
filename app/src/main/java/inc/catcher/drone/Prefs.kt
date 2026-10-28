package inc.catcher.drone

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/** User settings, stored in SharedPreferences and edited from [MainActivity]. */
object Prefs {
    const val EGG_INC = "com.auxbrain.egginc"

    class Slider(
        val key: String,
        val label: String,
        val min: Int,
        val max: Int,
        val def: Int,
        val unit: String,
        val help: String,
    )

    class Toggle(val key: String, val label: String, val def: Boolean)

    val ROI_TOP = Slider("roi_top", "Search area top", 0, 90, 14, "% of screen",
        "Keep it below the money counter at the top.")
    val ROI_BOTTOM = Slider("roi_bottom", "Search area bottom", 10, 100, 62, "% of screen",
        "Keep it above the chickens, trucks and buttons.")
    val THRESHOLD = Slider("threshold", "Motion threshold", 8, 120, 30, "",
        "Lower catches fainter drones; higher ignores more noise.")
    val MIN_SIZE = Slider("min_size", "Smallest drone", 1, 30, 5, "% of width",
        "Raise this if it taps chickens or sparkles.")
    val MAX_SIZE = Slider("max_size", "Largest drone", 5, 50, 22, "% of width", "")
    val MIN_TRAVEL = Slider("min_travel", "Shortest flight", 0, 40, 8, "% of width",
        "How far something must fly before it's tapped. Raise if it taps animations.")
    val MIN_SPEED = Slider("min_speed", "Slowest drone", 0, 50, 4, "% of width/s",
        "Things slower than this (clouds, counters) are ignored.")
    val LEAD = Slider("lead_ms", "Aim ahead", 0, 400, 100, "ms",
        "Raise if taps land behind drones, lower if they land in front.")
    val COOLDOWN = Slider("cooldown_ms", "Pause between taps", 100, 3000, 450, "ms", "")
    val FPS = Slider("fps", "Checks per second", 2, 30, 12, "/s",
        "Higher reacts faster but uses more battery.")
    val SCALE = Slider("scale", "Downscale", 2, 8, 4, "x",
        "Capture resolution divider. Applies on next start.")

    val SLIDERS = listOf(ROI_TOP, ROI_BOTTOM, THRESHOLD, MIN_SIZE, MAX_SIZE, MIN_TRAVEL, MIN_SPEED, LEAD, COOLDOWN, FPS, SCALE)

    val ONLY_EGG = Toggle("only_egg", "Only tap while Egg, Inc. is on screen", true)
    val LAUNCH_EGG = Toggle("launch_egg", "Open Egg, Inc. after starting", true)
    val MARKERS = Toggle("markers", "Show a ring where it taps", true)
    val EDGE_ENTRY = Toggle("edge_entry", "Only tap things that fly in from the side", true)
    val AUTO_BACK = Toggle("auto_back", "Press Back if a tap opens a menu", true)
    val AVOID_BUTTONS = Toggle("avoid_buttons", "Never tap near the contract eggs and event pills at the sides", true)

    val TOGGLES = listOf(ONLY_EGG, AVOID_BUTTONS, EDGE_ENTRY, AUTO_BACK, LAUNCH_EGG, MARKERS)

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun get(ctx: Context, s: Slider): Int = prefs(ctx).getInt(s.key, s.def).coerceIn(s.min, s.max)

    fun set(ctx: Context, s: Slider, value: Int) = prefs(ctx).edit().putInt(s.key, value).apply()

    fun get(ctx: Context, t: Toggle): Boolean = prefs(ctx).getBoolean(t.key, t.def)

    fun set(ctx: Context, t: Toggle, value: Boolean) = prefs(ctx).edit().putBoolean(t.key, value).apply()

    fun reset(ctx: Context) = prefs(ctx).edit().clear().apply()

    private const val AVOID_SPOTS = "avoid_spots"
    private const val MAX_AVOID_SPOTS = 40

    /** Spots never to tap (where a tap once opened a menu), as (x, y) fractions of the screen. */
    fun avoidSpots(ctx: Context): List<FloatArray> =
        prefs(ctx).getString(AVOID_SPOTS, "").orEmpty().split(';').mapNotNull { spot ->
            val parts = spot.split(',')
            val x = parts.getOrNull(0)?.toFloatOrNull()
            val y = parts.getOrNull(1)?.toFloatOrNull()
            if (x != null && y != null) floatArrayOf(x, y) else null
        }

    fun addAvoidSpot(ctx: Context, x: Float, y: Float) {
        val spots = (avoidSpots(ctx) + floatArrayOf(x, y)).takeLast(MAX_AVOID_SPOTS)
        prefs(ctx).edit().putString(AVOID_SPOTS, spots.joinToString(";") { "${it[0]},${it[1]}" }).apply()
    }

    fun clearAvoidSpots(ctx: Context) = prefs(ctx).edit().remove(AVOID_SPOTS).apply()

    fun params(ctx: Context): DroneDetector.Params {
        val top = get(ctx, ROI_TOP)
        val bottom = maxOf(get(ctx, ROI_BOTTOM), top + 5)
        val minSize = get(ctx, MIN_SIZE)
        val maxSize = maxOf(get(ctx, MAX_SIZE), minSize + 1)
        return DroneDetector.Params(
            roiTop = top / 100f,
            roiBottom = bottom.coerceAtMost(100) / 100f,
            threshold = get(ctx, THRESHOLD),
            minSize = minSize / 100f,
            maxSize = maxSize / 100f,
            minSpeed = get(ctx, MIN_SPEED) / 100f,
            minTravel = get(ctx, MIN_TRAVEL) / 100f,
            requireEdgeEntry = get(ctx, EDGE_ENTRY),
            leadMs = get(ctx, LEAD),
        )
    }
}

object Screen {
    /** Full size of the default display in its current rotation, including system bars. */
    @Suppress("DEPRECATION")
    fun realMetrics(ctx: Context): DisplayMetrics {
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }
}
