package inc.catcher.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneDetectorTest {

    private val w = 270
    private val h = 600
    /** Most tests start a drone straight away, so skip the start-up warm-up. */
    private val params = DroneDetector.Params(warmupMs = 0)
    private val frameMs = 83L

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    /** A vertical sky gradient with a few dim clouds. */
    private fun sky(): IntArray = IntArray(w * h) { i ->
        val y = i / w
        rgb(110, 170 + y / 20, 250 - y / 6)
    }

    private fun square(px: IntArray, cx: Int, cy: Int, size: Int, color: Int) {
        for (y in cy - size / 2 until cy + size / 2) {
            for (x in cx - size / 2 until cx + size / 2) {
                if (x in 0 until w && y in 0 until h) px[y * w + x] = color
            }
        }
    }

    @Test
    fun tapsAheadOfAMovingDrone() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        var hit: DroneDetector.Target? = null
        var hitX = 0
        var x = 20
        var t = frameMs
        repeat(20) {
            val px = sky()
            square(px, x, 200, 22, rgb(50, 55, 60))
            val r = d.process(px, t, params)
            if (r != null && hit == null) {
                hit = r
                hitX = x
            }
            x += 6
            t += frameMs
        }
        assertNotNull(hit)
        val target = hit!!
        assertEquals(200f, target.y, 3f)
        assertTrue("should lead the drone, got ${target.x} vs $hitX", target.x > hitX)
        assertTrue("lead too far, got ${target.x} vs $hitX", target.x < hitX + 20)
    }

    @Test
    fun ignoresAStillScene() {
        val d = DroneDetector(w, h)
        repeat(30) { i ->
            val px = sky()
            square(px, 135, 250, 30, rgb(200, 30, 30))
            assertNull(d.process(px, i * frameMs, params))
        }
    }

    @Test
    fun ignoresThingsTooSmallToBeDrones() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        var x = 20
        repeat(20) { i ->
            val px = sky()
            square(px, x, 300, 4, rgb(255, 255, 255))
            assertNull(d.process(px, (i + 1) * frameMs, params))
            x += 5
        }
    }

    @Test
    fun ignoresObjectsOutsideTheSearchRegion() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        var x = 20
        repeat(20) { i ->
            val px = sky()
            square(px, x, 500, 22, rgb(50, 55, 60)) // below roiBottom (0.62 * 600 = 372)
            assertNull(d.process(px, (i + 1) * frameMs, params))
            x += 6
        }
    }

    @Test
    fun ignoresWholeScreenChanges() {
        val d = DroneDetector(w, h)
        repeat(20) { i ->
            val c = if (i % 2 == 0) rgb(20, 20, 20) else rgb(230, 230, 230)
            assertNull(d.process(IntArray(w * h) { c }, i * frameMs, params))
        }
    }

    @Test
    fun ignoresTheExcludedRectangle() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        val exclude = intArrayOf(0, 150, w, 250)
        var x = 20
        repeat(20) { i ->
            val px = sky()
            square(px, x, 200, 22, rgb(50, 55, 60))
            assertNull(d.process(px, (i + 1) * frameMs, params, exclude))
            x += 6
        }
    }

    @Test
    fun suppressesTheSpotJustTapped() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        var x = 20
        var t = frameMs
        var first: DroneDetector.Target? = null
        while (first == null && t < 2000) {
            val px = sky()
            square(px, x, 200, 22, rgb(50, 55, 60))
            first = d.process(px, t, params)
            x += 6
            t += frameMs
        }
        assertNotNull(first)
        d.onTapped(first!!, t)
        // The very next frames, near the same spot, must not produce a target.
        repeat(3) {
            val px = sky()
            square(px, x, 200, 22, rgb(50, 55, 60))
            assertNull(d.process(px, t, params))
            x += 6
            t += frameMs
        }
    }

    @Test
    fun ignoresThingsWobblingInPlace() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        repeat(60) { i ->
            val px = sky()
            // An animated button: jumps back and forth and bobs up and down.
            val x = 135 + if (i % 2 == 0) -8 else 8
            val y = 250 + (i % 4) * 3
            square(px, x, y, 22, rgb(240, 200, 40))
            assertNull(d.process(px, (i + 1) * frameMs, params))
        }
    }

    @Test
    fun ignoresThingsRisingStraightUp() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        var y = 360
        repeat(20) { i ->
            val px = sky()
            square(px, 135, y, 22, rgb(255, 255, 255)) // floating "+$" text
            assertNull(d.process(px, (i + 1) * frameMs, params))
            y -= 6
        }
    }

    @Test
    fun waitsForTheSceneToSettleAfterAMenuOpens() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        // A menu covers the screen, then closes again.
        val menu = IntArray(w * h) { rgb(30, 30, 30) }
        assertNull(d.process(menu, frameMs, params))
        assertNull(d.process(sky(), 2 * frameMs, params))
        val changedAt = d.lastSceneChangeMs
        assertEquals(2 * frameMs, changedAt)

        var x = 20
        var t = 3 * frameMs
        var hitAt = -1L
        while (hitAt < 0 && t < 5000) {
            val px = sky()
            square(px, x, 200, 22, rgb(50, 55, 60))
            if (d.process(px, t, params) != null) hitAt = t
            x = (x + 6) % w
            t += frameMs
        }
        assertTrue("never caught the drone", hitAt > 0)
        assertTrue("tapped ${hitAt - changedAt} ms after the scene changed", hitAt - changedAt >= params.settleMs)
    }

    @Test
    fun learnsToIgnoreLoopingAnimations() {
        val p = DroneDetector.Params()
        val d = DroneDetector(w, h)
        d.process(sky(), 0, p)
        // Something spinning in place (a windmill, a wheel): parts of its circle look like
        // short straight flights, but it never came in from the edge.
        val falseTaps = ArrayList<Int>()
        repeat(400) { i ->
            val px = sky()
            val a = i * 2 * Math.PI / 12
            square(px, 135 + (40 * Math.cos(a)).toInt(), 250 + (40 * Math.sin(a)).toInt(), 22, rgb(200, 60, 60))
            if (d.process(px, (i + 1) * frameMs, p) != null) falseTaps.add(i)
        }
        assertTrue("tapped the spinning thing at frames $falseTaps", falseTaps.isEmpty())

        // A real drone flying past it is still caught.
        var x = 20
        var t = 401 * frameMs
        var hit: DroneDetector.Target? = null
        while (hit == null && x < w) {
            val px = sky()
            square(px, x, 150, 22, rgb(50, 55, 60))
            hit = d.process(px, t, p)
            x += 6
            t += frameMs
        }
        assertNotNull("drone was missed", hit)
    }

    @Test
    fun retriesAMissedDroneThenGivesUp() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        var x = 10
        var t = frameMs
        var taps = 0
        // A drone that survives every tap, flying slowly enough to be tried several times.
        while (x < w) {
            val px = sky()
            square(px, x, 200, 22, rgb(50, 55, 60))
            val r = d.process(px, t, params)
            if (r != null) {
                taps++
                d.onTapped(r, t)
            }
            x += 3
            t += frameMs
        }
        assertEquals(params.maxTapsPerTrack, taps)
    }

    @Test
    fun ignoresShortMovesThatStartMidScreen() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        // Something mid-screen slides a little way and stops, like a panel animating in.
        var x = 100
        repeat(30) { i ->
            val px = sky()
            square(px, x, 250, 22, rgb(50, 55, 60))
            assertNull(d.process(px, (i + 1) * frameMs, params))
            if (i < 8) x += 5
        }
    }

    @Test
    fun ignoresMidScreenThingsHeadingToTheirNearSide() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        var x = 120
        repeat(18) { i ->
            val px = sky()
            square(px, x, 250, 22, rgb(50, 55, 60))
            assertNull(d.process(px, (i + 1) * frameMs, params))
            x -= 5
        }
    }

    @Test
    fun catchesDronesComingOutFromBehindSideButtons() {
        val d = DroneDetector(w, h)
        // A column of game buttons down the left side, drawn on top of the drone.
        fun withButtons(px: IntArray): IntArray {
            for (y in 150 until 350) for (x in 0 until 70) px[y * w + x] = rgb(250, 190, 40)
            return px
        }
        d.process(withButtons(sky()), 0, params)
        var x = -10
        var t = frameMs
        var hit: DroneDetector.Target? = null
        var hitX = 0
        while (hit == null && x < w) {
            val px = sky()
            square(px, x, 250, 22, rgb(50, 55, 60))
            hit = d.process(withButtons(px), t, params)
            hitX = x
            x += 6
            t += frameMs
        }
        assertNotNull("drone coming out from behind the buttons was missed", hit)
        assertEquals(250f, hit!!.y, 3f)
        assertTrue("caught too late, at x=$hitX", hitX < w / 2)
    }

    @Test
    fun edgeEntryCanBeSwitchedOff() {
        val p = params.copy(requireEdgeEntry = false)
        val d = DroneDetector(w, h)
        d.process(sky(), 0, p)
        var x = 120
        var hit: DroneDetector.Target? = null
        repeat(18) { i ->
            val px = sky()
            square(px, x, 250, 22, rgb(50, 55, 60))
            hit = hit ?: d.process(px, (i + 1) * frameMs, p)
            x -= 5
        }
        assertNotNull(hit)
    }

    @Test
    fun neverTapsSpinningThingsAnywhere() {
        val p = DroneDetector.Params()
        for (cx in listOf(80, 135, 190)) for (radius in listOf(25, 40, 60)) for (period in listOf(8, 12, 20, 30)) {
            val d = DroneDetector(w, h)
            d.process(sky(), 0, p)
            repeat(300) { i ->
                val px = sky()
                val a = i * 2 * Math.PI / period
                square(px, cx + (radius * Math.cos(a)).toInt(), 250 + (radius * Math.sin(a)).toInt(), 22, rgb(200, 60, 60))
                assertNull("tapped spinner cx=$cx r=$radius period=$period at frame $i", d.process(px, (i + 1) * frameMs, p))
            }
        }
    }

    @Test
    fun signatureSeesAnOpenMenuButNotSmallChanges() {
        val d = DroneDetector(w, h)
        val farm = sky()
        square(farm, 200, 300, 60, rgb(180, 120, 80)) // a building

        // The same farm with a drone, a small explosion and a building animation frame.
        val busyFarm = farm.copyOf()
        square(busyFarm, 60, 200, 22, rgb(50, 55, 60))
        square(busyFarm, 130, 250, 30, rgb(255, 230, 120))
        square(busyFarm, 200, 300, 40, rgb(220, 140, 90))
        val small = DroneDetector.signatureDifference(d.signature(farm, params), d.signature(busyFarm, params))
        assertTrue("small changes scored $small", small < 0.1f)

        // A menu panel covering most of the screen.
        val menu = farm.copyOf()
        for (y in 120 until 560) for (x in 15 until 255) menu[y * w + x] = rgb(245, 245, 240)
        val big = DroneDetector.signatureDifference(d.signature(farm, params), d.signature(menu, params))
        assertTrue("open menu scored $big", big > 0.6f)
    }

    @Test
    fun catchesDronesThatBobUpAndDown() {
        // Drones that bob as they fly, at different heights, speeds and bob rates.
        var missed = ArrayList<String>()
        for (amp in listOf(4, 8, 12)) for (period in listOf(6, 10, 16)) for (step in listOf(4, 7, 11)) {
            val d = DroneDetector(w, h)
            d.process(sky(), 0, params)
            var x = 0
            var i = 0
            var hit: DroneDetector.Target? = null
            while (hit == null && x < w) {
                val px = sky()
                val y = 230 + (amp * Math.sin(i * 2 * Math.PI / period)).toInt()
                square(px, x, y, 22, rgb(50, 55, 60))
                hit = d.process(px, (i + 1) * frameMs, params)
                x += step
                i++
            }
            if (hit == null || x > w * 0.75) missed.add("amp=$amp period=$period step=$step caughtAt=${if (hit == null) "never" else "x=$x"}")
        }
        assertTrue("missed bobbing drones: $missed", missed.isEmpty())
    }

    @Test
    fun countsWhyDroneLikeThingsWereSkipped() {
        val d = DroneDetector(w, h)
        d.process(sky(), 0, params)
        var t = frameMs
        // Appears mid-screen and flies toward its nearest side, then leaves.
        var x = 120
        repeat(18) {
            val px = sky()
            square(px, x, 250, 22, rgb(50, 55, 60))
            d.process(px, t, params)
            x -= 5
            t += frameMs
        }
        repeat(10) {
            d.process(sky(), t, params)
            t += frameMs
        }
        assertEquals(1, d.skipCount(DroneDetector.Skip.NOT_FROM_SIDE))
        assertEquals(0, d.tappedCount)

        // A normal drone gets tapped and counted as such.
        x = 10
        var hit: DroneDetector.Target? = null
        while (hit == null && x < w) {
            val px = sky()
            square(px, x, 200, 22, rgb(50, 55, 60))
            hit = d.process(px, t, params)
            x += 6
            t += frameMs
        }
        d.onTapped(hit!!, t)
        assertEquals(1, d.tappedCount)
    }

    @Test
    fun neverTapsAvoidedSpotsButStillCatchesTheDroneElsewhere() {
        val d = DroneDetector(w, h)
        // A building at about x=110..160 where a tap once opened a menu.
        d.setAvoidSpots(listOf(floatArrayOf(135f / w, 200f / h)))
        d.process(sky(), 0, params)
        var x = 10
        var t = frameMs
        val taps = ArrayList<DroneDetector.Target>()
        while (x < w) {
            val px = sky()
            square(px, x, 200, 22, rgb(50, 55, 60))
            d.process(px, t, params)?.let {
                taps.add(it)
                d.onTapped(it, t)
            }
            x += 4
            t += frameMs
        }
        val r = 0.09f * w
        assertTrue("never tapped the drone", taps.isNotEmpty())
        for (tap in taps) {
            assertTrue("tapped the avoided spot at ${tap.x},${tap.y}", Math.hypot((tap.x - 135.0), (tap.y - 200.0)) >= r)
        }
    }
}
