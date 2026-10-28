package inc.catcher.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuWatcherTest {

    private val farm = IntArray(400) { 120 + it % 50 }
    private val menu = IntArray(400) { 240 }

    /** The farm with a small change, like a drone exploding or a counter ticking. */
    private val farmWithFlash = farm.copyOf().also { for (i in 0 until 20) it[i] = 255 }

    /** The farm after closing the menu, with a few things moved on. */
    private val farmLater = farm.copyOf().also { for (i in 100 until 130) it[i] = 20 }

    private fun opened(w: MenuWatcher, frames: List<Pair<IntArray, Long>>): MenuWatcher.Event? =
        frames.firstNotNullOfOrNull { (sig, t) -> w.onFrame(sig, t) }

    @Test
    fun noticesAMenuOpenedByOurTap() {
        val w = MenuWatcher()
        w.onTapDecided(farm, 50f, 60f, 1000)
        w.onTapSent(1100)
        assertNull(w.onFrame(menu, 1300)) // too soon to tell
        val e = w.onFrame(menu, 2000)
        assertEquals(MenuWatcher.Event.Opened(50f, 60f), e)
        assertTrue(w.menuOpen)
    }

    @Test
    fun ignoresAFlashThatIsGoneBySecondLook() {
        val w = MenuWatcher()
        w.onTapDecided(farm, 50f, 60f, 1000)
        w.onTapSent(1100)
        assertNull(w.onFrame(menu, 1200)) // a full-screen flash right after the tap...
        assertNull(w.onFrame(farmWithFlash, 2000)) // ...is gone by the time we look
        assertFalse(w.menuOpen)
        assertNull(w.onFrame(menu, 2500)) // and later changes aren't blamed on that tap
        assertFalse(w.menuOpen)
    }

    @Test
    fun ignoresTapsThatWereNeverSent() {
        val w = MenuWatcher()
        w.onTapDecided(farm, 50f, 60f, 1000)
        assertNull(opened(w, listOf(menu to 2000, menu to 2500, menu to 4000)))
        assertFalse(w.menuOpen)
    }

    @Test
    fun ignoresChangesLongAfterTheTap() {
        val w = MenuWatcher()
        w.onTapDecided(farm, 50f, 60f, 1000)
        w.onTapSent(1100)
        assertNull(w.onFrame(menu, 5000))
        assertFalse(w.menuOpen)
    }

    @Test
    fun reportsTheMenuClosedOnceTheFarmIsBack() {
        val w = MenuWatcher()
        w.onTapDecided(farm, 50f, 60f, 1000)
        w.onTapSent(1100)
        w.onFrame(menu, 2000)
        assertNull(w.onFrame(menu, 5000))
        assertTrue(w.menuOpen)
        assertEquals(MenuWatcher.Event.Closed, w.onFrame(farmLater, 9000))
        assertFalse(w.menuOpen)
    }

    @Test
    fun newTapsAreIgnoredWhileAMenuIsOpen() {
        val w = MenuWatcher()
        w.onTapDecided(farm, 50f, 60f, 1000)
        w.onTapSent(1100)
        w.onFrame(menu, 2000)
        w.onTapDecided(menu, 10f, 10f, 2100)
        w.onTapSent(2150)
        assertNull(w.onFrame(menu, 3000))
        assertEquals(MenuWatcher.Event.Closed, w.onFrame(farm, 4000))
    }

    @Test
    fun theFloatingButtonCanDismissTheWait() {
        val w = MenuWatcher()
        w.onTapDecided(farm, 50f, 60f, 1000)
        w.onTapSent(1100)
        w.onFrame(menu, 2000)
        w.dismiss()
        assertFalse(w.menuOpen)
        assertNull(w.onFrame(menu, 2100))
        assertFalse(w.menuOpen)
    }

    @Test
    fun aSecondMistakeIsNoticedToo() {
        val w = MenuWatcher()
        w.onTapDecided(farm, 50f, 60f, 1000)
        w.onTapSent(1100)
        w.onFrame(menu, 2000)
        w.onFrame(farm, 3000)
        w.onTapDecided(farm, 90f, 120f, 4000)
        w.onTapSent(4050)
        assertEquals(MenuWatcher.Event.Opened(90f, 120f), w.onFrame(menu, 5000))
    }

    @Test
    fun signaturesOfTheSimulatedFarmTellAMenuFromADrone() {
        val sim = FarmSim(1)
        val d = DroneDetector(sim.w, sim.h)
        val p = DroneDetector.Params()
        val before = d.signature(sim.render(0), p)
        sim.spawn(0)
        sim.spawn(0)
        val withDrones = d.signature(sim.render(1500), p)
        assertTrue(DroneDetector.signatureDifference(before, withDrones) < 0.1f)
        val menuScreen = sim.render(1500).also { px ->
            for (y in 90 until 480) for (x in 20 until 250) px[y * sim.w + x] = rgb(250, 250, 245)
        }
        assertTrue(DroneDetector.signatureDifference(before, d.signature(menuScreen, p)) > 0.5f)
    }
}
