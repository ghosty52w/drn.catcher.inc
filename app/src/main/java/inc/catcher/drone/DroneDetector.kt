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
        /** No taps for this long after starting or resuming, while the background and animations are learned. */
        val warmupMs: Int = 3000,
        /** Only tap things that first appeared near the left/right edge or the region's top/bottom. */
        val requireEdgeEntry: Boolean = true,
        /** How close to the left/right edge (in screen widths) counts as entering from it. */
        val edgeMargin: Float = 0.15f,
        /**
         * A drone hidden behind on-screen buttons first shows up part-way in. Such late
         * arrivals are still tapped if they head away from their nearest side and fly at
         * least this far (in screen widths) in a steady direction.
         */
        val lateEntryTravel: Float = 0.22f,
        /**
         * A late arrival heading toward its nearest side (e.g. a drone lost against
         * something the same colour, found again past the middle) must fly this far.
         */
        val lateEntryTowardSideTravel: Float = 0.3f,
        /**
         * How closely the current heading must match the overall path (cosine of the angle
         * between them). Drones keep their heading; parts of something spinning don't.
         */
        val minAlignment: Float = 0.92f,
        /** Give up on something after tapping it this many times without it disappearing. */
        val maxTapsPerTrack: Int = 4,
        /** How far ahead (in ms) to aim along the drone's path: the delay until a tap lands. */
        val leadMs: Int = 100,
        /** Learn the aim-ahead from which taps catch fast drones, starting from [leadMs]. */
        val autoLead: Boolean = true,
    )

    /** A tap point in capture coordinates, aimed [leadMs] ahead. */
    data class Target(val x: Float, val y: Float, val trackId: Int, val leadMs: Float = 0f)

    /** Why something that looked like a drone was never tapped. */
    enum class Skip(val label: String) {
        SPEED("moving too slow or too fast"),
        SHORT_FLIGHT("didn't fly far enough"),
        NOT_FROM_SIDE("didn't come in from the side"),
        WOBBLY("path too wobbly"),
        TOO_VERTICAL("flying too steeply up or down"),
        TURNING("kept changing direction"),
        GAVE_UP("still there after every retry"),
        AVOIDED_SPOT("over a spot where a tap once opened a menu"),
        OVER_BUTTONS("over the game's buttons"),
    }

    private class Blob(val cx: Float, val cy: Float, val size: Float)

    private class Sample(val x: Float, val y: Float, val ms: Long)

    private class Track(val id: Int, var x: Float, var y: Float, var size: Float, var lastMs: Long) {
        val originX = x
        val originY = y
        val originMs = lastMs
        /**
         * Positions sampled every [SAMPLE_MS], oldest first. Measuring the path between
         * samples rather than between frames keeps a drone's up-and-down bobbing from
         * looking like wobble or turning.
         */
        val samples = ArrayList<Sample>().apply { add(Sample(x, y, lastMs)) }
        var lastSampleMs = lastMs
        /** Each tap sent at it: when it was aimed and how far ahead. */
        val tapLog = ArrayList<FloatArray>(4)
        /** The aim-ahead picked for its first tap (kept while it waits for the pause between taps). */
        var firstLeadMs = 0f
        var sampledPath = 0f
        var taps = 0
        var lastSkip: Skip? = null
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
    private val onsets = FloatArray(n)
    private val wasMoving = BooleanArray(n)
    private val stillFrames = ByteArray(n)
    private val stack = IntArray(n)
    private val tracks = ArrayList<Track>()
    private val zones = ArrayList<Zone>()
    private var hasBg = false
    private var bgTop = -1
    private var bgBottom = -1
    private var nextId = 1

    private var warmUntilMs = Long.MIN_VALUE
    private val skipCounts = IntArray(Skip.entries.size)

    /** Things that flew like a drone and were tapped, and that were never tapped, by reason. */
    var tappedCount = 0
        private set

    fun skipCount(reason: Skip): Int = skipCounts[reason.ordinal]

    /**
     * Called for each drone-like thing that was skipped, when it disappears, with where it
     * was first and last seen (capture coordinates). For tuning and tests.
     */
    var onSkipped: ((reason: Skip, fromX: Float, fromY: Float, toX: Float, toY: Float) -> Unit)? = null

    /** Spots never to tap, as (x, y) fractions of the screen. */
    private var avoidSpots: List<FloatArray> = emptyList()

    /**
     * Sets spots never to tap, as (x, y) fractions of the screen, e.g. buildings where a
     * tap once opened a menu.
     */
    fun setAvoidSpots(spots: List<FloatArray>) {
        avoidSpots = spots
    }

    private fun isAvoided(x: Float, y: Float): Boolean {
        val r = AVOID_RADIUS * width
        return avoidSpots.any { hypot(x - it[0] * width, y - it[1] * height) < r }
    }

    /** Areas never to tap, as (left, top, right, bottom) fractions of the screen. */
    private var noTapRects: List<FloatArray> = emptyList()

    /** Sets areas never to tap, such as the game's buttons, as (left, top, right, bottom) fractions. */
    fun setNoTapAreas(rects: List<FloatArray>) {
        noTapRects = rects
    }

    private fun isOverButtons(x: Float, y: Float): Boolean {
        val fx = x / width
        val fy = y / height
        return noTapRects.any { fx >= it[0] && fx < it[2] && fy >= it[1] && fy < it[3] }
    }

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
        lastTapTrackId = target.trackId
        lastTapAtMs = nowMs
        tracks.find { it.id == target.trackId }?.let {
            if (it.taps == 0) tappedCount++
            it.taps++
            it.tapLog += floatArrayOf((nowMs - it.originMs).toFloat(), target.leadMs)
        }
    }

    private var lastTapTrackId = -1
    private var lastTapAtMs = Long.MIN_VALUE / 2

    /**
     * Whether [target] may be tapped now: [cooldownMs] after the last tap, or sooner
     * ([RETRY_GAP_MS]) to try again at the same drone, which a fast drone needs to get a
     * second chance before it's gone.
     */
    fun readyToTap(target: Target, nowMs: Long, cooldownMs: Long): Boolean {
        val since = nowMs - lastTapAtMs
        return since >= cooldownMs || (target.trackId == lastTapTrackId && since >= RETRY_GAP_MS)
    }

    /** The aim-ahead learned so far (ms), or NaN if nothing has been learned yet. */
    var learnedLeadMs = Float.NaN
        private set

    /** Restores a previously learned aim-ahead. */
    fun setLearnedLead(ms: Float) {
        learnedLeadMs = if (ms.isNaN()) ms else ms.coerceIn(MIN_LEAD_MS, MAX_LEAD_MS)
    }

    private var ditherState = 0x9E3779B97F4A7C15uL.toLong()

    /**
     * How often first taps at fast drones miss, recently. While they mostly hit, the aim is
     * fine and isn't varied; the more they miss, the more it's varied to find a better one.
     */
    var firstTapMissRate = 0.3f
        private set

    /**
     * A multiplier for the first tap's aim-ahead: up to about ×0.7 to ×1.4 (evenly spread
     * on a log scale) when first taps keep missing, none when they hit. Only drones fast
     * enough to teach anything get it.
     */
    private fun dither(t: Track): Float {
        if (hypot(t.vx, t.vy) < LEARN_MIN_SPEED * width) return 1f
        val amount = ((firstTapMissRate - CALM_MISS_RATE) / (BUSY_MISS_RATE - CALM_MISS_RATE)).coerceIn(0f, 1f)
        if (amount == 0f) return 1f
        var s = ditherState
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        ditherState = s
        val u = ((s ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat() // 0..1
        return kotlin.math.exp(amount * DITHER * (2 * u - 1))
    }

    private fun leadMs(p: Params): Float =
        if (p.autoLead && !learnedLeadMs.isNaN()) learnedLeadMs else p.leadMs.toFloat()

    /**
     * A fast drone that vanished right after a tap, somewhere it should have stayed in view,
     * was caught by it: move the learned aim-ahead toward the one that tap used. A drone that
     * was about to fly off the screen or behind the game's buttons proves nothing.
     */
    private fun learnFrom(t: Track, p: Params) {
        if (!p.autoLead || t.taps == 0) return
        val speed = hypot(t.vx, t.vy)
        if (speed < LEARN_MIN_SPEED * width) return // slow drones forgive any aim
        // If a tap aimed L ms ahead caught it, that tap landed about L ms after it was aimed
        // and the drone was last seen in the frame or two before. Several taps can be on
        // their way at once, so credit the one whose landing fits; if none or more than one
        // fits, the drone went for another reason (e.g. blended into a building) or we can't
        // tell which tap did it.
        val lastSeen = (t.lastMs - t.originMs).toFloat()
        val fits = t.tapLog.filter { (aimedAt, lead) ->
            val after = lastSeen - aimedAt
            after >= lead - CAUGHT_EARLY_MS && after <= lead + CAUGHT_LATE_MS
        }
        // A drone-and-a-half further on off screen, or last seen in the button strips (whose
        // margins it reaches before it's hidden): it may simply have flown out of sight.
        val aheadX = t.x + t.vx * VISIBLE_AHEAD_SIZES * t.size / speed
        val mayHaveFlownOff = aheadX < EDGE_SLACK * width || aheadX > (1 - EDGE_SLACK) * width || isOverButtons(t.x, t.y)
        // Only first taps vary the aim evenly both ways; retries are always tried in the same
        // order, so learning from them would lean that way.
        val first = t.tapLog[0]
        val firstHit = fits.size == 1 && fits[0] === first && !mayHaveFlownOff
        val firstMissed = lastSeen - first[0] > first[1] + CAUGHT_LATE_MS + MISS_MARGIN_MS
        if (firstHit || firstMissed) firstTapMissRate += ((if (firstHit) 0f else 1f) - firstTapMissRate) * MISS_RATE_ALPHA
        if (!firstHit) return
        val current = leadMs(p)
        learnedLeadMs = (current + (first[1] - current) * LEARN_RATE).coerceIn(MIN_LEAD_MS, MAX_LEAD_MS)
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
            warmUntilMs = nowMs + p.warmupMs
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

        if (nowMs < warmUntilMs) return null
        if (lastSceneChangeMs != Long.MIN_VALUE && nowMs - lastSceneChangeMs < p.settleMs) return null

        val edge = p.edgeMargin * width
        val vEdge = EDGE_MARGIN_VERTICAL * width
        var best: Target? = null
        var bestHits = 0
        for (t in tracks) {
            if (t.lastMs != nowMs || t.hits < p.confirmHits) continue
            t.lastSkip = skipReason(t, p, top, bottom, edge, vEdge)
            if (t.lastSkip != null) continue
            if (zones.any { hypot(t.x - it.x, t.y - it.y) < it.r }) continue
            // The first tap at a drone varies the aim-ahead a little around the usual one, so
            // that which ones catch drones shows whether it should be longer or shorter. If
            // a tap missed, the aim was probably off: try a bit later, then a bit earlier.
            val lead = if (t.taps == 0) {
                t.firstLeadMs.takeIf { it > 0f } ?: (leadMs(p) * (if (p.autoLead) dither(t) else 1f)).also { t.firstLeadMs = it }
            } else {
                leadMs(p) * RETRY_LEAD[min(t.taps, RETRY_LEAD.size - 1)]
            }
            val (ax, ay) = aimPoint(t, t.lastMs + lead.toLong())
            val tx = ax.coerceIn(0f, width - 1f)
            val ty = ay.coerceIn(top.toFloat(), bottom - 1f)
            if (isOverButtons(tx, ty)) {
                t.lastSkip = Skip.OVER_BUTTONS
                continue
            }
            if (isAvoided(tx, ty)) {
                t.lastSkip = Skip.AVOIDED_SPOT
                continue
            }
            if (best == null || t.hits > bestHits) {
                best = Target(tx, ty, t.id, lead)
                bestHits = t.hits
            }
        }
        return best
    }

    /** Where [t] will be at [atMs], carrying on at its current velocity. */
    private fun aimPoint(t: Track, atMs: Long): Pair<Float, Float> {
        val dt = (atMs - t.lastMs) / 1000f
        return Pair(t.x + t.vx * dt, t.y + t.vy * dt)
    }

    /**
     * The newest sample at least half a second old and a fair distance back, to measure
     * the recent heading over. Far enough back that bobbing up and down doesn't swamp it,
     * even for slow drones. Null if the track is too young.
     */
    private fun headingReference(t: Track): Sample? {
        var ref: Sample? = null
        for (sample in t.samples) {
            if (t.lastMs - sample.ms >= HEADING_WINDOW_MS && hypot(t.x - sample.x, t.y - sample.y) >= HEADING_DISTANCE * width) ref = sample
        }
        return ref
    }

    /** The first rule [t] fails, or null if it may be tapped. */
    private fun skipReason(t: Track, p: Params, top: Int, bottom: Int, edge: Float, vEdge: Float): Skip? {
        if (t.taps >= p.maxTapsPerTrack) return Skip.GAVE_UP
        val speed = hypot(t.vx, t.vy) / width
        if (speed < p.minSpeed || speed > p.maxSpeed) return Skip.SPEED
        val dx = t.x - t.originX
        val dy = t.y - t.originY
        val travel = hypot(dx, dy)
        if (travel < p.minTravel * width) return Skip.SHORT_FLIGHT
        val enteredFromEdge = t.originX <= edge || t.originX >= width - 1 - edge ||
            t.originY <= top + vEdge || t.originY >= bottom - 1 - vEdge
        if (p.requireEdgeEntry && !enteredFromEdge) {
            val headingAwayFromNearSide = if (t.originX < width / 2f) dx > 0 else dx < 0
            val needed = if (headingAwayFromNearSide) p.lateEntryTravel else p.lateEntryTowardSideTravel
            if (travel < needed * width) return if (headingAwayFromNearSide) Skip.SHORT_FLIGHT else Skip.NOT_FROM_SIDE
        }
        val last = t.samples[t.samples.size - 1]
        val path = t.sampledPath + hypot(t.x - last.x, t.y - last.y)
        if (travel < p.minStraightness * path) return Skip.WOBBLY
        if (abs(dy) > p.maxVerticalShare * travel) return Skip.TOO_VERTICAL
        val ref = headingReference(t) ?: t.samples[0]
        val rx = t.x - ref.x
        val ry = t.y - ref.y
        val recent = hypot(rx, ry)
        if (recent > 0f && (rx * dx + ry * dy) / (recent * travel) < p.minAlignment) return Skip.TURNING
        return null
    }

    /**
     * A coarse brightness sample of the search area, used to tell whether a menu is still
     * covering the screen some time after a tap. Compare two with [signatureDifference].
     */
    fun signature(px: IntArray, p: Params): IntArray {
        val top = (p.roiTop * height).toInt().coerceIn(0, height - 1)
        val bottom = (p.roiBottom * height).toInt().coerceIn(top + 1, height)
        val out = IntArray(((bottom - top + SIGNATURE_STEP - 1) / SIGNATURE_STEP) * ((width + SIGNATURE_STEP - 1) / SIGNATURE_STEP))
        var k = 0
        for (y in top until bottom step SIGNATURE_STEP) {
            for (x in 0 until width step SIGNATURE_STEP) {
                val c = px[y * width + x]
                out[k++] = (((c shr 16) and 0xff) * 77 + ((c shr 8) and 0xff) * 150 + (c and 0xff) * 29) shr 8
            }
        }
        return out
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
                onsets[i] *= ONSET_DECAY
                // Only a fresh start after a quiet spell counts: a passing drone's bobbing
                // edge flickers on and off quickly, a looping animation comes back later.
                if (isMoving && !wasMoving[i] && stillFrames[i] >= MIN_QUIET_FRAMES) onsets[i] += 1f
                wasMoving[i] = isMoving
                stillFrames[i] = if (isMoving) 0 else min(stillFrames[i] + 1, 127).toByte()
                val busy = activity[i] > BUSY_ACTIVITY || onsets[i] > BUSY_ONSETS
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
                // A new track could be moving at any speed. A young one's velocity is still
                // rough. An established one is only allowed to stray a little from where it
                // was heading (so a slow animation's track can't grab a drone flying past it),
                // plus a share of its own travel while unseen (crossing something its colour).
                val speed = hypot(t.vx, t.vy)
                val lost = LOST_DRIFT * speed * max(0f, dt - 0.2f)
                val reach = when {
                    t.hits == 1 -> p.maxSpeed * width * dt + b.size
                    t.hits == 2 -> 0.5f * p.maxSpeed * width * min(dt, 0.2f) + b.size + lost
                    else -> 0.75f * b.size + 0.5f * speed * min(dt, 0.2f) + STRAY_PX + lost
                }
                val d = hypot(b.cx - predX, b.cy - predY)
                if (d < reach && d < bestD) {
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
            best.x = b.cx
            best.y = b.cy
            val prev = best.samples[best.samples.size - 1]
            if (nowMs - best.lastSampleMs >= SAMPLE_MS && hypot(b.cx - prev.x, b.cy - prev.y) >= SAMPLE_DISTANCE * width) {
                best.sampledPath += hypot(b.cx - prev.x, b.cy - prev.y)
                best.samples.add(Sample(b.cx, b.cy, nowMs))
                if (best.samples.size > MAX_SAMPLES) best.samples.removeAt(0)
                best.lastSampleMs = nowMs
            }
            best.size = b.size
            best.lastMs = nowMs
            best.hits++
            best.matched = true
        }
        tracks.removeAll { t ->
            val timeout = if (t.hits >= p.confirmHits) LOST_TIMEOUT_MS else TRACK_TIMEOUT_MS
            val gone = nowMs - t.lastMs > timeout
            val skip = t.lastSkip
            if (gone) learnFrom(t, p)
            val droneLike = t.taps == 0 && hypot(t.x - t.originX, t.y - t.originY) >= DRONE_LIKE_TRAVEL * width
            if (gone && skip != null && (droneLike || skip == Skip.GAVE_UP)) {
                skipCounts[skip.ordinal]++
                onSkipped?.invoke(skip, t.originX, t.originY, t.x, t.y)
            }
            gone
        }
    }

    companion object {
        /**
         * Where Egg, Inc. puts buttons over the farm, as (left, top, right, bottom) screen
         * fractions: a strip down the left for the column of contract eggs and the
         * event/offer pills under it ("x10", "80% OFF"), which grows and shrinks as
         * contracts come and go, and the side tab on the right. Drones from the left are
         * hidden behind those buttons until about here anyway, and drones from the right
         * have crossed most of the screen (and been tapped) before they get there.
         */
        val EGG_INC_SIDE_BUTTONS: List<FloatArray> = listOf(
            floatArrayOf(0f, 0.13f, 0.30f, 0.72f),
            floatArrayOf(0.89f, 0.31f, 1f, 0.40f),
        )

        /** Share of sample points (0-1) whose brightness differs clearly between two signatures. */
        fun signatureDifference(a: IntArray, b: IntArray): Float {
            if (a.size != b.size || a.isEmpty()) return 1f
            var changed = 0
            for (i in a.indices) if (abs(a[i] - b[i]) > SIGNATURE_THRESHOLD) changed++
            return changed.toFloat() / a.size
        }

        private const val SIGNATURE_STEP = 4
        private const val SIGNATURE_THRESHOLD = 40

        private const val STILL: Byte = 0
        private const val MOVING: Byte = 1
        private const val VISITED: Byte = 2

        /** How fast still pixels and moving pixels blend into the background. */
        private const val BG_ALPHA = 0.10f
        private const val FG_ALPHA = 0.02f

        /** More than this fraction of the region moving at once means the scene changed. */
        private const val SCENE_CHANGE_FRACTION = 0.20f

        /**
         * Pixels that were moving in more than this share of recent frames (roughly the
         * last 10 s) are looping animations, not drones, and are ignored.
         */
        private const val ACTIVITY_ALPHA = 0.01f
        private const val BUSY_ACTIVITY = 0.12f

        /**
         * Pixels that keep starting to change are looping animations too, even if each
         * change is brief (a wheel turning, a window blinking, text floating up every few
         * seconds). A drone sets a pixel off once as it passes; three times within roughly
         * the last 10 s marks the pixel as busy.
         */
        private const val ONSET_DECAY = 0.994f
        private const val BUSY_ONSETS = 2.5f
        private const val MIN_QUIET_FRAMES = 6

        /** Moving pixels up to this far apart belong to the same blob. */
        private const val GAP = 2
        private const val MIN_FILL = 0.10f
        private const val MAX_ASPECT = 4

        /** How close to the region's top/bottom (in screen widths) counts as entering from it. */
        private const val EDGE_MARGIN_VERTICAL = 0.06f

        /** A track survives this long without being seen, e.g. while crossing a busy area. */
        private const val TRACK_TIMEOUT_MS = 600L

        /**
         * A track that has been followed for a while survives this long unseen, so a drone
         * that vanishes against something the same colour and reappears further along its
         * path is still the same drone (and keeps where it came in from).
         */
        private const val LOST_TIMEOUT_MS = 1500L

        /** How far (as a share of its own travel) a drone may stray from its predicted path while unseen. */
        private const val LOST_DRIFT = 0.35f

        /** Extra room (capture pixels) for an established track to stray from its prediction. */
        private const val STRAY_PX = 4f

        /** Track positions are sampled this often for the wobble and turning checks. */
        private const val SAMPLE_MS = 300L

        /** ...and only once it has moved this far (screen widths), so slow drones' bobbing doesn't add up. */
        private const val SAMPLE_DISTANCE = 0.1f

        /** The recent heading is measured over at least this distance (screen widths). */
        private const val HEADING_DISTANCE = 0.1f
        private const val MAX_SAMPLES = 8

        /** The recent heading is measured over at least this long. */
        private const val HEADING_WINDOW_MS = 500L

        /** Shortest gap before tapping the same drone again. */
        const val RETRY_GAP_MS = 150L

        /**
         * A drone counts as caught by a tap aimed L ms ahead if it was last seen between
         * L - [CAUGHT_EARLY_MS] and L + [CAUGHT_LATE_MS] after that tap was aimed: up to two
         * frames before the tap landed, allowing for the delay varying a little.
         */
        private const val CAUGHT_EARLY_MS = 160f
        private const val CAUGHT_LATE_MS = 40f

        /** Only drones at least this fast (screen widths/s) teach the aim: slow ones forgive any aim. */
        private const val LEARN_MIN_SPEED = 0.3f

        /** A vanished drone only counts as caught if it would still be in view this many of its sizes further on... */
        private const val VISIBLE_AHEAD_SIZES = 1.5f

        /** ...and that far from the left and right edges (screen widths). */
        private const val EDGE_SLACK = 0.05f
        private const val LEARN_RATE = 0.5f
        private const val MIN_LEAD_MS = 20f
        private const val MAX_LEAD_MS = 400f

        /** Aim-ahead multipliers for the 2nd, 3rd and 4th tap on the same drone (the 1st is [dither]ed). */
        private val RETRY_LEAD = floatArrayOf(1f, 1.35f, 0.7f, 1.7f)

        /** First taps vary the aim-ahead by up to e^±[DITHER] (about ×0.7 to ×1.4). */
        private const val DITHER = 0.35f

        /** First-tap miss rates between which the aim goes from not varied to fully varied. */
        private const val CALM_MISS_RATE = 0.15f
        private const val BUSY_MISS_RATE = 0.45f
        private const val MISS_RATE_ALPHA = 0.15f

        /** A drone still seen this long after a tap should have landed was missed by it. */
        private const val MISS_MARGIN_MS = 100f

        /** Taps within this distance (screen widths) of an avoided spot are skipped. */
        private const val AVOID_RADIUS = 0.09f

        /** Untapped tracks that moved at least this far (screen widths) count as skipped drones. */
        private const val DRONE_LIKE_TRAVEL = 0.06f
    }
}
