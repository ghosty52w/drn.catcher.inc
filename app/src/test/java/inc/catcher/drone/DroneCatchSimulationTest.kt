package inc.catcher.drone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests on [FarmSim], a stand-in for the Egg, Inc. farm laid out after a real
 * screenshot, played through the detector the way the app does (frame timing, dropped
 * frames, tap delay, pause between taps). A tap only counts if it lands on the drone when
 * it reaches the screen.
 *
 * Every scenario checks how many drones are caught and that taps stay off the game's
 * buttons (they open menus) and almost never land on buildings.
 */
class DroneCatchSimulationTest {

    private fun run(
        seed: Long = 1,
        durationMs: Long = 90_000,
        opts: FarmSim.Options = FarmSim.Options(),
        frameMs: IntRange = 73..93,
        dropShare: Float = 0.04f,
        latencyMs: IntRange = 90..170,
        params: DroneDetector.Params = DroneDetector.Params(),
        stampJitterMs: Int = 0,
    ): CatchRun.Result =
        CatchRun(FarmSim(seed, opts), params, seed, frameMs, dropShare, latencyMs, stampJitterMs = stampJitterMs).run(durationMs)

    private fun mix(f: FarmSim.DroneMix.() -> FarmSim.DroneMix) = FarmSim.Options(drones = FarmSim.DroneMix().f())

    /**
     * [maxBuildingTapShare]: misses that land on a building. The simulated phone's tap delay
     * varies by up to 80 ms, which at elite speed is about a drone's width, so the fastest
     * and most erratic drones get a looser limit.
     */
    private fun check(r: CatchRun.Result, minCatchRate: Float, maxBuildingTapShare: Float = 0.05f) {
        assertTrue("too few drones in this run: $r", r.spawned.size >= 8)
        assertTrue("caught too few (want ${(minCatchRate * 100).toInt()}%): $r", r.catchRate >= minCatchRate)
        assertEquals("tapped the game's buttons: $r", 0, r.buttonTaps)
        assertTrue("tapped buildings too often: $r", r.buildingTaps <= maxBuildingTapShare * r.taps + 1)
    }

    // --- The whole farm, mixed drones ---------------------------------------------------

    @Test
    fun catchesNearlyAllDronesOnABusyFarm() {
        var spawned = 0
        var caught = 0
        var building = 0
        var taps = 0
        for (seed in 1L..4L) {
            val r = run(seed = seed, durationMs = 150_000)
            assertEquals("tapped the game's buttons: $r", 0, r.buttonTaps)
            spawned += r.spawned.size
            caught += r.caught
            building += r.buildingTaps
            taps += r.taps
        }
        val rate = caught.toFloat() / spawned
        assertTrue("caught $caught/$spawned", rate >= 0.93f)
        assertTrue("$building of $taps taps hit buildings", building <= 0.03f * taps)
    }

    @Test
    fun neverTapsAnythingWhenNoDronesCome() {
        val r = CatchRun(FarmSim(5)).run(300_000, scheduled = false)
        assertEquals("taps with no drones around: $r", 0, r.taps)
    }

    @Test
    fun neverTapsAnythingOnAnEmptyFarmWithHeavyNoise() {
        val r = CatchRun(FarmSim(6, FarmSim.Options(noise = 9))).run(120_000, scheduled = false)
        assertEquals("taps with no drones around: $r", 0, r.taps)
    }

    // --- Kinds of drone ------------------------------------------------------------------

    @Test
    fun slowDrones() = check(run(seed = 11, opts = mix { copy(eliteShare = 0f, speed = 0.08f..0.15f) }), 0.9f)

    @Test
    fun fastEliteDrones() = check(run(seed = 12, opts = mix { copy(eliteShare = 1f) }), 0.8f, 0.15f)

    @Test
    fun veryFastEliteDrones() = check(run(seed = 13, opts = mix { copy(eliteShare = 1f, eliteSpeed = 1.3f..1.6f) }), 0.7f, 0.2f)

    @Test
    fun dronesThatBobALot() = check(run(seed = 14, opts = mix { copy(bobAmp = 8f..13f, bobPeriodMs = 350f..900f) }), 0.9f, 0.2f)

    @Test
    fun dronesFlyingAtAnAngle() = check(run(seed = 15, opts = mix { copy(slope = 0.2f..0.35f) }), 0.85f)

    @Test
    fun dronesFlyingAtAnAngleUpward() = check(run(seed = 16, opts = mix { copy(slope = -0.35f..-0.2f, height = 0.35f..0.59f) }), 0.85f)

    @Test
    fun smallDrones() = check(run(seed = 17, opts = mix { copy(size = 14..17) }), 0.85f)

    @Test
    fun bigDrones() = check(run(seed = 18, opts = mix { copy(size = 32..42) }), 0.9f)

    @Test
    fun whiteDrones() = check(run(seed = 19, opts = mix { copy(colors = listOf(0xF0F0F0)) }), 0.9f)

    @Test
    fun blackDrones() = check(run(seed = 20, opts = mix { copy(colors = listOf(0x202020)) }), 0.9f)

    @Test
    fun redDrones() = check(run(seed = 21, opts = mix { copy(colors = listOf(0xC03C32)) }), 0.85f)

    // --- Where they fly -------------------------------------------------------------------

    @Test
    fun dronesFromTheLeftBehindTheButtons() =
        check(run(seed = 22, opts = mix { copy(fromLeftShare = 1f, height = 0.17f..0.42f) }), 0.9f)

    @Test
    fun dronesFromTheRight() = check(run(seed = 23, opts = mix { copy(fromLeftShare = 0f) }), 0.9f)

    @Test
    fun dronesHighOverTheDome() = check(run(seed = 24, opts = mix { copy(height = 0.16f..0.24f) }), 0.85f)

    @Test
    fun dronesLowOverTheSilos() = check(run(seed = 25, opts = mix { copy(height = 0.45f..0.6f) }), 0.85f)

    @Test
    fun manyDronesAtOnce() = check(
        run(seed = 26, opts = FarmSim.Options(droneGapMs = 1000..2000)),
        0.8f,
    )

    // --- The left column changes as contracts come and go -----------------------------------

    @Test
    fun noContracts() = check(run(seed = 27, opts = FarmSim.Options(contracts = 0)), 0.9f)

    @Test
    fun oneContract() = check(run(seed = 28, opts = FarmSim.Options(contracts = 1)), 0.9f)

    @Test
    fun sixContracts() = check(run(seed = 29, opts = FarmSim.Options(contracts = 6)), 0.88f)

    @Test
    fun eightContractsFromTheLeft() =
        check(run(seed = 30, opts = FarmSim.Options(contracts = 8, drones = FarmSim.DroneMix(fromLeftShare = 1f))), 0.85f)

    // --- The screen and the phone ------------------------------------------------------------

    @Test
    fun noisyCapture() = check(run(seed = 31, opts = FarmSim.Options(noise = 9)), 0.88f)

    @Test
    fun dimScreen() = check(run(seed = 32, opts = FarmSim.Options(brightness = 0.7f)), 0.85f)

    @Test
    fun quietFarmWithoutAnimationsOrTraffic() =
        check(run(seed = 33, opts = FarmSim.Options(animations = false, traffic = false)), 0.93f)

    @Test
    fun farmWithoutSideButtons() = check(run(seed = 34, opts = FarmSim.Options(sideButtons = false)), 0.93f)

    @Test
    fun slowPhone() = check(run(seed = 35, frameMs = 150..190, dropShare = 0.08f), 0.8f)

    @Test
    fun unevenFrameTimestamps() = check(run(seed = 45, stampJitterMs = 15), 0.9f)

    @Test
    fun veryUnevenFrameTimestamps() = check(run(seed = 46, stampJitterMs = 30), 0.85f)

    @Test
    fun unevenFrameTimestampsWithElites() =
        check(run(seed = 47, opts = mix { copy(eliteShare = 0.5f) }, stampJitterMs = 20), 0.8f, 0.1f)

    @Test
    fun manyDroppedFrames() = check(run(seed = 36, dropShare = 0.2f), 0.85f)

    @Test
    fun laggyTaps() = check(run(seed = 37, latencyMs = 140..220), 0.8f) // still learning the aim for this phone

    @Test
    fun laggyTapsWithMoreAimAhead() =
        check(run(seed = 37, latencyMs = 140..220, params = DroneDetector.Params(leadMs = 180)), 0.85f)

    @Test
    fun snappyTaps() = check(run(seed = 38, latencyMs = 30..60, params = DroneDetector.Params(leadMs = 45)), 0.9f)

    // --- Learning the aim-ahead ---------------------------------------------------------------

    /** Plays elites on a phone with [latencyMs] tap delay, starting from the default aim-ahead. */
    private fun learnOn(latencyMs: IntRange, seed: Long): Pair<Float, CatchRun.Result> {
        val run = CatchRun(FarmSim(seed, mix { copy(eliteShare = 1f) }), seed = seed, latencyMs = latencyMs)
        val result = run.run(150_000)
        return Pair(run.detector.learnedLeadMs, result)
    }

    @Test
    fun learnsALongerAimAheadOnALaggyPhone() {
        val (learned, _) = learnOn(160..200, 50)
        assertTrue("learned $learned ms for a 160-200 ms delay", learned in 145f..215f)
    }

    @Test
    fun staysPutOrComesDownOnASnappyPhone() {
        // The default aim still lands on drones with a short delay (only misses teach it
        // anything), so it mustn't wander upward, and elites must still be caught.
        val (learned, result) = learnOn(50..80, 51)
        assertTrue("learned $learned ms for a 50-80 ms delay", learned.isNaN() || learned <= 125f)
        check(result, 0.85f, 0.1f)
    }

    @Test
    fun keepsTheAimAheadOnAPhoneThatMatchesIt() {
        val (learned, _) = learnOn(80..120, 52)
        assertTrue("learned $learned ms for a 80-120 ms delay", learned in 75f..130f)
    }

    @Test
    fun catchesElitesOnALaggyPhoneOnceTheAimIsLearned() {
        val (learned, _) = learnOn(160..200, 53)
        // Next session starts from what was learned (the app saves it).
        val run = CatchRun(FarmSim(54, mix { copy(eliteShare = 1f) }), seed = 54, latencyMs = 160..200)
        run.detector.setLearnedLead(learned)
        check(run.run(120_000), 0.8f, 0.1f)
    }

    // --- Settings people may change -------------------------------------------------------------

    @Test
    fun edgeEntryOff() = check(run(seed = 39, params = DroneDetector.Params(requireEdgeEntry = false)), 0.9f, 0.08f)

    @Test
    fun higherMotionThreshold() = check(run(seed = 40, params = DroneDetector.Params(threshold = 50)), 0.85f)

    @Test
    fun lowerMotionThreshold() = check(run(seed = 41, params = DroneDetector.Params(threshold = 18)), 0.88f)

    @Test
    fun tallerSearchArea() =
        check(run(seed = 42, params = DroneDetector.Params(roiTop = 0.14f, roiBottom = 0.7f)), 0.9f)
}
