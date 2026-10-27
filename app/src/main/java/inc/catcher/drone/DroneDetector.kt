package inc.catcher.drone

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds drones in a stream of downscaled screen captures.
 *
 * A drone is a small object that flies in from the edge and across the sky. The detector
 * keeps a running background model of the search region, marks pixels that differ from it,
 * groups them into blobs, and follows blobs from frame to frame. A blob becomes a target
 * only once it has entered from the edge of the region and flown a real distance in a
 * fairly straight, mostly sideways line at drone-like speed: animated buildings and buttons
 * stay put and wobble in place, and floating text rises straight up.
 * Pixels that keep changing (looping animations) are learned and ignored.
 * The returned tap point is led ahead along its velocity to make up for capture latency.
 *
 * Plain Kotlin with no Android dependencies so it can be unit-tested on the JVM.
 * Pixels are packed 0xRRGGBB ints, row-major, [width] x [height].
 */
class DroneDetector(val width: Int, val height: Int) {

    data class Params(
        /** Search region, as fractions of the screen height. */
        val roiTop: Float = 0.14f,
        val roiBottom: Float = 0.62f,
        /** Per-channel colour difference (0-255) that counts as motion. */
        val threshold: Int = 30,
        /** Drone size bounds, as fractions of the screen width. */
        val minSize: Float = 0.05f,
        val maxSize: Float = 0.22f,
        /** Drone speed bounds, in screen widths per second. */
        val minSpeed: Float = 0.04f,
        val maxSpeed: Float = 2.5f,
        /** Frames a blob must be seen in before it is tapped. */
        val confirmHits: Int = 4,
        /** Distance a blob must have flown since it appeared, in screen widths. */
        val minTravel: Float = 0.08f,
        /** Straight-line distance over distance travelled; wobbling things score low. */
        val minStraightness: Float = 0.7f,
        /** Largest vertical share of the flight direction (1 = straight up/down allowed). */
        val maxVerticalShare: Float = 0.87f,
        /** No taps for this long after the whole scene changes (a menu opening/closing). */
        val settleMs: Int = 1200,
        /** Only tap things that first appeared near the left/right edge or the region's top/bottom. */
        val requireEdgeEntry: Boolean = true,
        /** How close to the left/right edge (in screen widths) counts as entering from it. */
        val edgeMargin: Float = 0.15f,
        /** Give up on something after tapping it this many times without it disappearing. */
        val maxTapsPerTrack: Int = 3,
        /** How far ahead (in ms) to aim along the drone's path. */
        val leadMs: Int = 100,
    )

    /** A tap point in capture coordinates. */
    data class Target(val x: Float, val y: Float, val trackId: Int)

    private class Blob(val cx: Float, val cy: Float, val size: Float)

    private class Track(val id: Int, var x: Float, var y: Float, var size: Float, var lastMs: Long) {
        val originX = x
        val originY = y
        var pathLength = 0f
        var taps = 0
        var vx = 0f
        var vy = 0f
        var hits = 1
        var matched = true
    }

    private class Zone(val x: Float, val y: Float, val r: Float, val untilMs: Long)

    private val n = width * height
    private val bg = FloatArray(n * 3)
    private val fg = ByteArray(n)
    private val activity = FloatArray(n)
    private val stack = IntArray(n)
    private val tracks = ArrayList<Track>()
    private val zones = ArrayList<Zone>()
    private var hasBg = false
    private var bgTop = -1
    private var bgBottom = -1
    private var nextId = 1

    /** When the last whole-scene change was seen, e.g. a menu opening. */
    var lastSceneChangeMs = Long.MIN_VALUE
        private set

    /** Forget the background and all tracks; the next frame re-seeds the model. */
    fun reset() {
        hasBg = false
        tracks.clear()
        zones.clear()
    }

    /**
     * Records a tap on [target]: detections near it are ignored for a while, so the
     * explosion of a caught drone is not tapped. If the drone was missed it keeps being
     * tracked and is tried again later, up to [Params.maxTapsPerTrack] times.
     */
    fun onTapped(target: Target, nowMs: Long, radiusFrac: Float = 0.12f, durationMs: Long = 900L) {
        zones.add(Zone(target.x, target.y, width * radiusFrac, nowMs + durationMs))
        tracks.find { it.id == target.trackId }?.let { it.taps++ }
    }

    /**
     * Feeds one frame. [exclude] is an optional (left, top, right, bottom) rectangle in
     * capture coordinates to ignore, such as our own floating button.
     * Returns where to tap, or null if no drone is confirmed in this frame.
     */
    fun process(px: IntArray, nowMs: Long, p: Params, exclude: IntArray? = null): Target? {
        require(px.size >= n) { "frame is ${px.size} pixels, expected $n" }
        val top = (p.roiTop * height).toInt().coerceIn(0, height - 1)
        val bottom = (p.roiBottom * height).toInt().coerceIn(top + 1, height)
        if (!hasBg || top != bgTop || bottom != bgBottom) {
            seed(px, top, bottom)
            return null
        }

        val moving = markMovingPixels(px, top, bottom, p.threshold.toFloat(), exclude)
        if (moving > (bottom - top) * width * SCENE_CHANGE_FRACTION) {
            // A menu opened, the screen scrolled, or similar: start over on the new scene.
            seed(px, top, bottom)
            lastSceneChangeMs = nowMs
            return null
        }

        val blobs = findBlobs(top, bottom, p)
        updateTracks(blobs, nowMs, p)
        zones.removeAll { it.untilMs <= nowMs }

        if (lastSceneChangeMs != Long.MIN_VALUE && nowMs - lastSceneChangeMs < p.settleMs) return null

        val edge = p.edgeMargin * width
        val vEdge = EDGE_MARGIN_VERTICAL * width
        var best: Track? = null
        for (t in tracks) {
            if (t.lastMs != nowMs || t.hits < p.confirmHits || t.taps >= p.maxTapsPerTrack) continue
            if (p.requireEdgeEntry &&
                t.originX > edge && t.originX < width - 1 - edge &&
                t.originY > top + vEdge && t.originY < bottom - 1 - vEdge
            ) continue
            val speed = hypot(t.vx, t.vy) / width
            if (speed < p.minSpeed || speed > p.maxSpeed) continue
            val dx = t.x - t.originX
            val dy = t.y - t.originY
            val travel = hypot(dx, dy)
            if (travel < p.minTravel * width) continue
            if (travel < p.minStraightness * t.pathLength) continue
            if (abs(dy) > p.maxVerticalShare * travel) continue
            if (zones.any { hypot(t.x - it.x, t.y - it.y) < it.r }) continue
            if (best == null || t.hits > best.hits) best = t
        }
        if (best == null) return null

        val lead = p.leadMs / 1000f
        val tx = (best.x + best.vx * lead).coerceIn(0f, width - 1f)
        val ty = (best.y + best.vy * lead).coerceIn(top.toFloat(), bottom - 1f)
        return Target(tx, ty, best.id)
    }

    private fun seed(px: IntArray, top: Int, bottom: Int) {
        for (i in top * width until bottom * width) {
            val c = px[i]
            val j = i * 3
            bg[j] = ((c shr 16) and 0xff).toFloat()
            bg[j + 1] = ((c shr 8) and 0xff).toFloat()
            bg[j + 2] = (c and 0xff).toFloat()
        }
        hasBg = true
        bgTop = top
        bgBottom = bottom
        tracks.clear()
    }

    /**
     * Marks pixels that differ from the background and updates the background.
     * Returns how many pixels changed, including ones in busy (always-animating) areas,
     * which are counted but not marked.
     */
    private fun markMovingPixels(px: IntArray, top: Int, bottom: Int, threshold: Float, exclude: IntArray?): Int {
        var count = 0
        for (y in top until bottom) {
            val row = y * width
            val excludedRow = exclude != null && y >= exclude[1] && y < exclude[3]
            for (x in 0 until width) {
                val i = row + x
                val c = px[i]
                val r = ((c shr 16) and 0xff).toFloat()
                val g = ((c shr 8) and 0xff).toFloat()
                val b = (c and 0xff).toFloat()
                val j = i * 3
                val d = max(abs(r - bg[j]), max(abs(g - bg[j + 1]), abs(b - bg[j + 2])))
                val isMoving = d > threshold && !(excludedRow && x >= exclude!![0] && x < exclude[2])
                val busy = activity[i] > BUSY_ACTIVITY
                val alpha: Float
                if (isMoving) {
                    fg[i] = if (busy) STILL else MOVING
                    count++
                    alpha = FG_ALPHA
                    activity[i] += (1f - activity[i]) * ACTIVITY_ALPHA
                } else {
                    fg[i] = STILL
                    alpha = BG_ALPHA
                    activity[i] -= activity[i] * ACTIVITY_ALPHA
                }
                bg[j] += (r - bg[j]) * alpha
                bg[j + 1] += (g - bg[j + 1]) * alpha
                bg[j + 2] += (b - bg[j + 2]) * alpha
            }
        }
        return count
    }

    /** Groups moving pixels into drone-sized blobs, bridging small gaps between parts. */
    private fun findBlobs(top: Int, bottom: Int, p: Params): List<Blob> {
        val minPx = p.minSize * width
        val maxPx = p.maxSize * width
        val blobs = ArrayList<Blob>()
        for (y in top until bottom) {
            for (x in 0 until width) {
                val start = y * width + x
                if (fg[start] != MOVING) continue
                var sp = 0
                stack[sp++] = start
                fg[start] = VISITED
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var area = 0
                while (sp > 0) {
                    val k = stack[--sp]
                    val kx = k % width
                    val ky = k / width
                    area++
                    minX = min(minX, kx)
                    maxX = max(maxX, kx)
                    minY = min(minY, ky)
                    maxY = max(maxY, ky)
                    for (ny in max(top, ky - GAP)..min(bottom - 1, ky + GAP)) {
                        val nrow = ny * width
                        for (nx in max(0, kx - GAP)..min(width - 1, kx + GAP)) {
                            val m = nrow + nx
                            if (fg[m] == MOVING) {
                                fg[m] = VISITED
                                stack[sp++] = m
                            }
                        }
                    }
                }
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                val size = max(bw, bh).toFloat()
                if (size < minPx || size > maxPx) continue
                if (area < size * size * MIN_FILL) continue
                if (max(bw, bh) > MAX_ASPECT * min(bw, bh)) continue
                blobs.add(Blob((minX + maxX) / 2f, (minY + maxY) / 2f, size))
            }
        }
        return blobs
    }

    private fun updateTracks(blobs: List<Blob>, nowMs: Long, p: Params) {
        for (t in tracks) t.matched = false
        for (b in blobs) {
            var best: Track? = null
            var bestD = Float.MAX_VALUE
            for (t in tracks) {
                if (t.matched) continue
                val dt = (nowMs - t.lastMs) / 1000f
                val predX = t.x + t.vx * dt
                val predY = t.y + t.vy * dt
                val reach = p.maxSpeed * width * dt * (if (t.hits == 1) 1f else 0.5f)
                val d = hypot(b.cx - predX, b.cy - predY)
                if (d < reach + b.size && d < bestD) {
                    best = t
                    bestD = d
                }
            }
            if (best == null) {
                tracks.add(Track(nextId++, b.cx, b.cy, b.size, nowMs))
                continue
            }
            val dt = max((nowMs - best.lastMs) / 1000f, 0.001f)
            val nvx = (b.cx - best.x) / dt
            val nvy = (b.cy - best.y) / dt
            if (best.hits == 1) {
                best.vx = nvx
                best.vy = nvy
            } else {
                best.vx = best.vx * 0.4f + nvx * 0.6f
                best.vy = best.vy * 0.4f + nvy * 0.6f
            }
            best.pathLength += hypot(b.cx - best.x, b.cy - best.y)
            best.x = b.cx
            best.y = b.cy
            best.size = b.size
            best.lastMs = nowMs
            best.hits++
            best.matched = true
        }
        tracks.removeAll { nowMs - it.lastMs > TRACK_TIMEOUT_MS }
    }

    private companion object {
        const val STILL: Byte = 0
        const val MOVING: Byte = 1
        const val VISITED: Byte = 2

        /** How fast still pixels and moving pixels blend into the background. */
        const val BG_ALPHA = 0.10f
        const val FG_ALPHA = 0.02f

        /** More than this fraction of the region moving at once means the scene changed. */
        const val SCENE_CHANGE_FRACTION = 0.20f

        /**
         * Pixels that were moving in more than this share of recent frames (roughly the
         * last 10 s) are looping animations, not drones, and are ignored.
         */
        const val ACTIVITY_ALPHA = 0.01f
        const val BUSY_ACTIVITY = 0.12f

        /** Moving pixels up to this far apart belong to the same blob. */
        const val GAP = 2
        const val MIN_FILL = 0.10f
        const val MAX_ASPECT = 4

        /** How close to the region's top/bottom (in screen widths) counts as entering from it. */
        const val EDGE_MARGIN_VERTICAL = 0.06f

        /** A track survives this long without being seen, e.g. while crossing a busy area. */
        const val TRACK_TIMEOUT_MS = 600L
    }
}
