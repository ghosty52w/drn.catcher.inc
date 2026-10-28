package inc.catcher.drone

/**
 * Notices when one of our taps hit a building or button and opened a menu: most of a
 * second after the tap, the screen still looks very different from just before it (a
 * passing flash or a caught drone's explosion won't). While such a menu is open no taps
 * should be sent; it counts as closed once the screen looks like it did before the tap.
 *
 * Works on [DroneDetector.signature]s, so it is plain Kotlin and unit-tested on the JVM.
 */
class MenuWatcher(
    private val checkAfterMs: Long = 800,
    private val checkUntilMs: Long = 2000,
    private val openDifference: Float = 0.35f,
    private val closedDifference: Float = 0.2f,
) {
    sealed class Event {
        /** A tap opened a menu; ([x], [y]) is where that tap was aimed. */
        data class Opened(val x: Float, val y: Float) : Event()
        object Closed : Event()
    }

    /** A menu our tap opened is (still) open. */
    @Volatile
    var menuOpen = false
        private set

    private var before: IntArray? = null
    private var decidedMs = 0L
    private var tapX = 0f
    private var tapY = 0f

    @Volatile
    private var sentMs = Long.MIN_VALUE
    private var baseline: IntArray? = null

    /** A tap was decided at [x], [y]; [signature] is the screen just before it. */
    fun onTapDecided(signature: IntArray, x: Float, y: Float, nowMs: Long) {
        if (menuOpen) return
        before = signature
        decidedMs = nowMs
        tapX = x
        tapY = y
    }

    /** That tap actually reached the screen (it may be dropped, e.g. if Egg, Inc. isn't showing). */
    fun onTapSent(nowMs: Long) {
        sentMs = nowMs
    }

    /** The player says carry on (e.g. from the floating button), menu or not. */
    fun dismiss() {
        menuOpen = false
    }

    /** Call for every frame with its signature. */
    fun onFrame(signature: IntArray, nowMs: Long): Event? {
        val base = baseline
        if (base != null) {
            if (!menuOpen) {
                baseline = null // dismissed
                return null
            }
            if (DroneDetector.signatureDifference(base, signature) < closedDifference) {
                menuOpen = false
                baseline = null
                return Event.Closed
            }
            return null
        }
        val b = before ?: return null
        val sent = sentMs
        if (sent < decidedMs) {
            // That tap hasn't been sent (yet); forget it if it never is.
            if (nowMs - decidedMs > checkUntilMs) before = null
            return null
        }
        if (nowMs - sent < checkAfterMs) return null
        before = null
        if (nowMs - sent > checkUntilMs) return null
        if (DroneDetector.signatureDifference(b, signature) < openDifference) return null
        baseline = b
        menuOpen = true
        return Event.Opened(tapX, tapY)
    }
}
