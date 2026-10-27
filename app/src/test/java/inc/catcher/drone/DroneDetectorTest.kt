package inc.catcher.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DroneDetectorTest {

    private val w = 270
    private val h = 600
    private val params = DroneDetector.Params()
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
        d.suppressAt(first!!.x, first.y, t)
        // The very next frames, near the same spot, must not produce a target.
        repeat(3) {
            val px = sky()
            square(px, x, 200, 22, rgb(50, 55, 60))
            assertNull(d.process(px, t, params))
            x += 6
            t += frameMs
        }
    }
}
