package inc.catcher.drone

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A rough stand-in for Egg, Inc.'s farm screen at capture resolution (270 x 600), laid out
 * after a real screenshot: the giant habitat dome at the top, red silos, dirt roads with
 * chickens running along them, the shipping depot and trucks at the bottom, the HUD, the
 * column of egg buttons and offer pills down the left (drawn over the farm, so drones fly
 * behind them), the tab on the right and the button bar at the bottom. Drones fly over it.
 */
class FarmSim(seed: Long, val opts: Options = Options()) {

    data class Options(
        /** Habitat dome, silos, depot and other buildings. */
        val buildings: Boolean = true,
        /** Progress rings on the egg buttons, a pulsing shop button, floating "+$" text. */
        val animations: Boolean = true,
        /** The column of egg buttons and offer pills down the left, and the tab on the right. */
        val sideButtons: Boolean = true,
        /** Contract eggs in the left column; the pills sit below them. */
        val contracts: Int = 4,
        /** Chickens running along the roads and trucks driving at the bottom. */
        val traffic: Boolean = true,
        /** Per-channel random noise amplitude, like video compression. */
        val noise: Int = 3,
        /** Multiplies every colour, e.g. 0.7 for a darker phone screen recording. */
        val brightness: Float = 1f,
        val drones: DroneMix = DroneMix(),
        /** Gap between drones; occasionally two overlap. */
        val droneGapMs: IntRange = 2500..6000,
    )

    /** What kind of drones to send. Ranges are sampled uniformly. */
    data class DroneMix(
        val eliteShare: Float = 0.15f,
        val speed: ClosedFloatingPointRange<Float> = 0.15f..0.45f, // screen widths per second
        val eliteSpeed: ClosedFloatingPointRange<Float> = 0.9f..1.3f,
        val size: IntRange = 18..30,
        val bobAmp: ClosedFloatingPointRange<Float> = 0f..9f,
        val bobPeriodMs: ClosedFloatingPointRange<Float> = 400f..1400f,
        val slope: ClosedFloatingPointRange<Float> = -0.15f..0.15f,
        /** Heights as fractions of the screen. */
        val height: ClosedFloatingPointRange<Float> = 0.17f..0.59f,
        val fromLeftShare: Float = 0.5f,
        val colors: List<Int> = listOf(0x3C4146, 0x5A5F64, 0x2B2B2B, 0xE6E6E6, 0xB4463C),
    )

    class Drone(
        val id: Int,
        val spawnMs: Long,
        val fromLeft: Boolean,
        val y0: Float,
        val speed: Float,
        val slope: Float,
        val bobAmp: Float,
        val bobPeriodMs: Float,
        val size: Int,
        val color: Int,
        val elite: Boolean,
    ) {
        var caughtMs = -1L

        fun x(t: Long, w: Int): Float {
            val travel = speed * (t - spawnMs) / 1000f
            return if (fromLeft) -size + travel else w + size - travel
        }

        fun y(t: Long): Float {
            val travel = speed * (t - spawnMs) / 1000f
            return y0 + slope * travel + bobAmp * sin(2 * PI * (t - spawnMs) / bobPeriodMs).toFloat()
        }

        fun flying(t: Long, w: Int): Boolean {
            if (t < spawnMs || caughtMs >= 0) return false
            val x = x(t, w)
            return x > -size - 1 && x < w + size + 1
        }

        fun finished(t: Long, w: Int): Boolean = caughtMs >= 0 || (t > spawnMs && !flying(t, w))

        override fun toString() = buildString {
            append(if (elite) "elite " else "")
            append("drone#$id from ${if (fromLeft) "left" else "right"} y=${y0.roundToInt()}")
            append(" speed=${(speed / W * 100).roundToInt()}%w/s size=$size")
            append(" bob=${bobAmp.roundToInt()}px/${bobPeriodMs.roundToInt()}ms slope=${"%.2f".format(slope)}")
            append(" color=${Integer.toHexString(color)}")
        }
    }

    private class Rect(val l: Int, val t: Int, val r: Int, val b: Int, val color: Int) {
        fun contains(x: Float, y: Float) = x >= l && x < r && y >= t && y < b
    }

    /** What a tap lands on. BUILDING and BUTTON open menus in the real game. */
    enum class Hit { DRONE, BUILDING, BUTTON, GROUND }

    companion object {
        const val W = 270
        const val H = 600

        private val GRASS = rgb(165, 225, 95)
        private val SHADOW = rgb(115, 165, 70)
        private val ROAD = rgb(215, 165, 85)
        private val DOME = rgb(135, 172, 200)
        private val DARK_UI = rgb(38, 40, 38)
    }

    val w = W
    val h = H
    private val rnd = Random(seed)
    private var noiseState = seed xor 0x5DEECE66DL or 1L
    val drones = ArrayList<Drone>()
    private val buildings = ArrayList<Rect>()
    private val buttons = ArrayList<Rect>()
    private val explosions = ArrayList<FloatArray>() // x, y, startMs
    private var nextSpawnMs = 3500L // after the detector's start-up warm-up
    private var nextId = 1
    private val background = IntArray(W * H)

    init {
        // HUD and bottom bar are always there.
        buttons += Rect(7, 42, 263, 77, DARK_UI) // money / chickens / eggs bar
        buttons += Rect(223, 39, 263, 80, rgb(170, 60, 220)) // shop
        buttons += Rect(0, 440, 46, 466, rgb(60, 150, 60)) // drone counter
        for (k in 0 until 4) buttons += Rect(8 + k * 41, 483, 41 + k * 41, 516, DARK_UI)
        buttons += Rect(243, 500, 263, 520, rgb(40, 120, 230))
        buttons += Rect(7, 525, 263, 593, rgb(235, 15, 15)) // hatch chickens
        if (opts.sideButtons) {
            val columnBottom = 87 + 27 * opts.contracts + 1
            buttons += Rect(10, 87, 37, columnBottom, DARK_UI) // contract eggs column
            buttons += Rect(10, columnBottom + 6, 57, columnBottom + 29, DARK_UI) // "x10"
            buttons += Rect(10, columnBottom + 35, 73, columnBottom + 56, DARK_UI) // "80% OFF"
            buttons += Rect(249, 199, 270, 230, rgb(40, 60, 35)) // side tab
        }
        if (opts.buildings) {
            buildings += Rect(0, 0, 78, 144, rgb(70, 70, 76)) // habitat top-left
            buildings += Rect(120, 156, 254, 210, rgb(90, 90, 96)) // dome supports
            buildings += Rect(89, 243, 136, 312, rgb(190, 45, 45)) // silo
            buildings += Rect(24, 279, 72, 352, rgb(190, 45, 45)) // silo
            buildings += Rect(0, 200, 15, 300, rgb(190, 45, 45)) // silo at the edge
            buildings += Rect(215, 276, 270, 369, rgb(150, 190, 210)) // glass building
            buildings += Rect(102, 363, 270, 504, rgb(250, 205, 0)) // shipping depot
        }
        drawBackground()
    }

    private fun drawBackground() {
        val px = background
        px.fill(GRASS)
        // Two dirt roads crossing the farm diagonally.
        line(px, 0f, 162f, 270f, 276f, 11f, ROAD)
        line(px, 262f, 272f, 60f, 420f, 11f, ROAD)
        if (opts.buildings) {
            // The giant habitat dome with a pattern of lit windows.
            ellipse(px, 185f, 75f, 126f, 99f, DOME)
            val r = Random(1)
            for (k in 0 until 260) {
                val x = 70 + r.nextInt(200)
                val y = r.nextInt(170)
                val dx = (x - 185f) / 126f
                val dy = (y - 75f) / 99f
                if (dx * dx + dy * dy < 0.95f) rect(px, x, y, x + 2, y + 3, rgb(240, 245, 250))
            }
            for (b in buildings.drop(1)) {
                // Shadows to the lower left, then the building.
                rect(px, b.l - 12, b.b - 10, b.r - 12, b.b + 8, SHADOW)
            }
            for (b in buildings) rect(px, b.l, b.t, b.r, b.b, b.color)
            // Silo roofs, depot roof, habitat windows.
            rect(px, 89, 243, 136, 268, rgb(200, 200, 200))
            rect(px, 24, 279, 72, 304, rgb(200, 200, 200))
            rect(px, 120, 375, 262, 430, rgb(235, 235, 235))
            rect(px, 215, 276, 270, 290, rgb(60, 60, 60))
            val r2 = Random(2)
            for (k in 0 until 60) {
                val x = r2.nextInt(76)
                val y = r2.nextInt(140)
                rect(px, x, y, x + 2, y + 2, rgb(220, 220, 220))
            }
        }
        // The road at the bottom left where trucks drive.
        rect(px, 0, 405, 102, 510, rgb(70, 70, 76))
    }

    private fun f(r: ClosedFloatingPointRange<Float>) = r.start + rnd.nextFloat() * (r.endInclusive - r.start)
    private fun i(r: IntRange) = r.first + rnd.nextInt(r.last - r.first + 1)

    /** Adds a drone at time [t]. */
    fun spawn(t: Long, mix: DroneMix = opts.drones): Drone {
        val elite = rnd.nextFloat() < mix.eliteShare
        val d = Drone(
            id = nextId++,
            spawnMs = t,
            fromLeft = rnd.nextFloat() < mix.fromLeftShare,
            y0 = f(mix.height) * h,
            speed = f(if (elite) mix.eliteSpeed else mix.speed) * w,
            slope = f(mix.slope),
            bobAmp = f(mix.bobAmp),
            bobPeriodMs = f(mix.bobPeriodMs),
            size = i(mix.size),
            color = mix.colors[rnd.nextInt(mix.colors.size)],
            elite = elite,
        )
        drones += d
        return d
    }

    /** Spawns drones on the configured schedule until [untilMs]; call once per frame. */
    fun scheduleDrones(t: Long, untilMs: Long) {
        while (nextSpawnMs <= t && nextSpawnMs < untilMs) {
            spawn(nextSpawnMs)
            nextSpawnMs += if (rnd.nextFloat() < 0.15f) 800L else i(opts.droneGapMs).toLong()
        }
    }

    /** What a tap at (x, y) at time [t] lands on; catches the drone if it hits one. */
    fun tap(x: Float, y: Float, t: Long): Hit {
        for (b in buttons) if (b.contains(x, y)) return Hit.BUTTON
        for (d in drones) {
            if (!d.flying(t, w)) continue
            if (hypot(x - d.x(t, w), y - d.y(t)) < d.size * 0.65f) {
                d.caughtMs = t
                explosions += floatArrayOf(d.x(t, w), d.y(t), t.toFloat())
                return Hit.DRONE
            }
        }
        if (opts.buildings) {
            for (b in buildings) if (b.contains(x, y)) return Hit.BUILDING
            val dx = (x - 185f) / 126f
            val dy = (y - 75f) / 99f
            if (dx * dx + dy * dy < 1f) return Hit.BUILDING
        }
        return Hit.GROUND
    }

    fun render(t: Long): IntArray {
        val px = background.copyOf()
        if (opts.traffic) {
            // Chickens running along both roads, and a truck now and then.
            for (k in 0 until 14) {
                val u = ((t / 1000f * 0.12f + k / 14f) % 1f)
                val (x, y) = if (k % 2 == 0) {
                    Pair(u * 270f, 162f + u * 114f)
                } else {
                    Pair(262f - u * 202f, 272f + u * 148f)
                }
                rect(px, x.toInt() - 2, y.toInt() - 2, x.toInt() + 2, y.toInt() + 2, rgb(255, 255, 255))
            }
            val cycle = t % 12000
            if (cycle < 5000) {
                val u = cycle / 5000f
                val x = -60 + u * 200
                val y = 500 - u * 80
                rect(px, x.toInt() - 30, y.toInt() - 15, x.toInt() + 30, y.toInt() + 15, rgb(45, 45, 50))
                rect(px, x.toInt() - 15, y.toInt() - 12, x.toInt() + 15, y.toInt() + 8, rgb(240, 225, 160))
            }
        }
        if (opts.animations && opts.buildings) {
            // "+$" floating up from a silo for 1.5 s every 3 s.
            val phase = t % 3000
            if (phase < 1500) {
                val y = (240 - phase * 30 / 1000).toInt()
                rect(px, 100, y - 6, 124, y + 6, rgb(255, 255, 255))
            }
        }
        // Drones fly over the farm but under the buttons.
        for (d in drones) {
            if (!d.flying(t, w)) continue
            drawDrone(px, d.x(t, w), d.y(t), d.size, d.color, t)
        }
        explosions.removeAll { t - it[2] > 500 }
        for (e in explosions) {
            val age = (t - e[2]) / 500f
            for (k in 0 until 6) {
                val a = k * PI / 3
                val r = 4 + age * 22
                val ex = (e[0] + r * cos(a)).toInt()
                val ey = (e[1] + r * sin(a)).toInt()
                rect(px, ex - 2, ey - 2, ex + 2, ey + 2, rgb(255, 210, 60))
            }
        }
        for (b in buttons) rect(px, b.l, b.t, b.r, b.b, b.color)
        if (opts.sideButtons) {
            // Egg buttons with progress rings that slowly fill.
            val eggs = intArrayOf(rgb(240, 140, 30), rgb(200, 30, 40), rgb(30, 30, 30), rgb(90, 200, 90))
            for (k in 0 until opts.contracts) {
                val cy = 100 + k * 27
                rect(px, 13, cy - 11, 34, cy + 11, rgb(60, 180, 60))
                rect(px, 16, cy - 8, 31, cy + 8, eggs[k % eggs.size])
                if (opts.animations) {
                    val fill = (((t / 1000f) * 0.05f + k * 0.25f) % 1f * 22).toInt()
                    rect(px, 13, cy - 11, 13 + fill, cy - 9, rgb(30, 220, 30))
                }
            }
            val columnBottom = 87 + 27 * opts.contracts + 1
            rect(px, 14, columnBottom + 10, 32, columnBottom + 25, rgb(40, 180, 230))
            rect(px, 14, columnBottom + 39, 32, columnBottom + 53, rgb(240, 20, 130))
        }
        if (opts.animations) {
            // The shop button pulses, the chicken counter ticks.
            val glow = (190 + 60 * sin(2 * PI * t / 900.0)).toInt()
            rect(px, 230, 46, 256, 72, rgb(glow, 80, 235))
            rect(px, 60, 62, 60 + ((t / 100) % 40).toInt(), 70, rgb(255, 255, 255))
        }
        if (opts.brightness != 1f || opts.noise > 0) finish(px)
        return px
    }

    private fun drawDrone(px: IntArray, cx: Float, cy: Float, size: Int, color: Int, t: Long) {
        val half = size / 2
        val bodyH = (size * 0.45f).toInt().coerceAtLeast(4)
        val x0 = cx.toInt() - half
        val y0 = cy.toInt() - bodyH / 2
        rect(px, x0, y0, x0 + size, y0 + bodyH, color)
        // Rotors flicker as they spin.
        val rotor = if ((t / 50) % 2 == 0L) rgb(200, 200, 200) else rgb(150, 150, 150)
        rect(px, x0 - 2, y0 - 3, x0 + size / 3, y0 - 1, rotor)
        rect(px, x0 + size - size / 3, y0 - 3, x0 + size + 2, y0 - 1, rotor)
        // A parcel hanging underneath.
        rect(px, cx.toInt() - 3, y0 + bodyH, cx.toInt() + 3, y0 + bodyH + 6, rgb(170, 120, 60))
    }

    private fun rect(px: IntArray, l: Int, t: Int, r: Int, b: Int, c: Int) {
        for (y in maxOf(0, t) until minOf(h, b)) {
            val row = y * w
            for (x in maxOf(0, l) until minOf(w, r)) px[row + x] = c
        }
    }

    private fun ellipse(px: IntArray, cx: Float, cy: Float, rx: Float, ry: Float, c: Int) {
        for (y in maxOf(0, (cy - ry).toInt()) until minOf(h, (cy + ry).toInt() + 1)) {
            val dy = (y - cy) / ry
            if (abs(dy) > 1) continue
            val half = rx * kotlin.math.sqrt(1 - dy * dy)
            for (x in maxOf(0, (cx - half).toInt()) until minOf(w, (cx + half).toInt() + 1)) px[y * w + x] = c
        }
    }

    /** A thick line, for the roads. */
    private fun line(px: IntArray, x0: Float, y0: Float, x1: Float, y1: Float, half: Float, c: Int) {
        val len = hypot(x1 - x0, y1 - y0)
        val steps = len.toInt()
        for (k in 0..steps) {
            val x = x0 + (x1 - x0) * k / steps
            val y = y0 + (y1 - y0) * k / steps
            rect(px, (x - half).toInt(), (y - half / 2).toInt(), (x + half).toInt(), (y + half / 2).toInt(), c)
        }
    }

    private fun finish(px: IntArray) {
        val amp = opts.noise
        val span = 2 * amp + 1
        val k = opts.brightness
        for (i in px.indices) {
            var n = 0
            if (amp > 0) {
                var s = noiseState
                s = s xor (s shl 13)
                s = s xor (s ushr 7)
                s = s xor (s shl 17)
                noiseState = s
                n = ((s ushr 33).toInt() and 0x7fffffff) % span - amp
            }
            val c = px[i]
            val r = ((((c shr 16) and 0xff) * k).toInt() + n).coerceIn(0, 255)
            val g = ((((c shr 8) and 0xff) * k).toInt() + n).coerceIn(0, 255)
            val b = (((c and 0xff) * k).toInt() + n).coerceIn(0, 255)
            px[i] = (r shl 16) or (g shl 8) or b
        }
    }
}

fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

/**
 * Plays a [FarmSim] through a [DroneDetector] the way CaptureService does: frames every
 * ~83 ms with jitter and drops, a pause between taps, and taps landing on the screen some
 * time after the frame they were aimed from.
 */
class CatchRun(
    private val sim: FarmSim,
    private val params: DroneDetector.Params = DroneDetector.Params(),
    seed: Long = 7,
    private val frameMs: IntRange = 73..93,
    private val dropShare: Float = 0.04f,
    private val latencyMs: IntRange = 70..150,
    private val cooldownMs: Long = 450,
    /** Like the app: a tap that opens a menu (building or button) is remembered and never repeated there. */
    private val learnAvoidSpots: Boolean = true,
) {
    data class Result(
        val spawned: List<FarmSim.Drone>,
        val caught: Int,
        val missed: List<FarmSim.Drone>,
        val taps: Int,
        val buildingTaps: Int,
        val buttonTaps: Int,
        val groundTaps: Int,
        val tapsPerCatch: Float,
    ) {
        val catchRate get() = if (spawned.isEmpty()) 1f else caught.toFloat() / spawned.size

        override fun toString() =
            "caught $caught/${spawned.size} (${(catchRate * 100).roundToInt()}%), taps=$taps " +
                "building=$buildingTaps button=$buttonTaps ground=$groundTaps" +
                if (missed.isEmpty()) "" else "\n  missed:\n    " + missed.joinToString("\n    ")
    }

    private val rnd = Random(seed)
    val detector = DroneDetector(sim.w, sim.h).apply { setNoTapAreas(DroneDetector.EGG_INC_SIDE_BUTTONS) }

    /** Runs for [durationMs], sending scheduled drones until [dronesUntilMs]. */
    fun run(durationMs: Long, dronesUntilMs: Long = durationMs - 3000, scheduled: Boolean = true): Result {
        val pending = ArrayList<LongArray>() // landAtMs, x*1000, y*1000
        val avoid = ArrayList<FloatArray>()
        var taps = 0
        var building = 0
        var button = 0
        var ground = 0
        var t = 0L
        while (t < durationMs) {
            if (scheduled) sim.scheduleDrones(t, dronesUntilMs)
            // Taps that have reached the screen by now.
            val due = pending.filter { it[0] <= t }.sortedBy { it[0] }
            pending.removeAll(due.toSet())
            for (p in due) {
                taps++
                val hit = sim.tap(p[1] / 1000f, p[2] / 1000f, p[0])
                if (learnAvoidSpots && (hit == FarmSim.Hit.BUILDING || hit == FarmSim.Hit.BUTTON)) {
                    avoid += floatArrayOf(p[1] / 1000f / sim.w, p[2] / 1000f / sim.h)
                    detector.setAvoidSpots(avoid.toList())
                }
                when (hit) {
                    FarmSim.Hit.BUILDING -> building++
                    FarmSim.Hit.BUTTON -> button++
                    FarmSim.Hit.GROUND -> ground++
                    FarmSim.Hit.DRONE -> {}
                }
            }
            if (rnd.nextFloat() >= dropShare) {
                val target = detector.process(sim.render(t), t, params)
                if (target != null && detector.readyToTap(target, t, cooldownMs)) {
                    detector.onTapped(target, t)
                    val land = t + latencyMs.first + rnd.nextInt(latencyMs.last - latencyMs.first + 1)
                    pending += longArrayOf(land, (target.x * 1000).toLong(), (target.y * 1000).toLong())
                }
            }
            t += frameMs.first + rnd.nextInt(frameMs.last - frameMs.first + 1)
        }
        val done = sim.drones.filter { it.finished(t, sim.w) }
        val caught = done.count { it.caughtMs >= 0 }
        return Result(
            spawned = done,
            caught = caught,
            missed = done.filter { it.caughtMs < 0 },
            taps = taps,
            buildingTaps = building,
            buttonTaps = button,
            groundTaps = ground,
            tapsPerCatch = if (caught == 0) 0f else taps.toFloat() / caught,
        )
    }
}
