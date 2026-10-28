package inc.catcher.drone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import kotlin.math.max

/**
 * Foreground service that mirrors the screen into a small [ImageReader], runs
 * [DroneDetector] on each frame and asks [TapService] to tap the drones it finds.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_STOP = "inc.catcher.drone.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "catching"
        private const val NOTIFICATION_ID = 1
        private const val SETTINGS_REFRESH_MS = 1000L

        /**
         * A menu opened by our tap is looked for once, this long after the tap: the screen
         * must still look very different from just before the tap (a passing flash won't).
         */
        private const val MENU_CHECK_AFTER_MS = 800L
        private const val MENU_CHECK_UNTIL_MS = 2000L
        private const val MENU_DIFFERENCE = 0.35f

        /** The menu counts as closed once the screen is this close to how it looked before the tap. */
        private const val MENU_CLOSED_DIFFERENCE = 0.2f

        /** How long after pressing Back to check we're still in Egg, Inc. */
        private const val BACK_VERIFY_MS = 900L

        @Volatile
        var instance: CaptureService? = null
            private set
    }

    @Volatile
    var paused = false
        private set

    @Volatile
    var taps = 0
        private set

    @Volatile
    var menusClosed = 0
        private set

    /** A tap of ours opened a menu that is still open; no taps until it closes. */
    @Volatile
    var menuOpen = false
        private set

    /** Set when Back took us out of Egg, Inc. instead of closing a menu. */
    @Volatile
    var backLeftGame = false
        private set

    /** When the last tap was actually sent (main thread clock = uptime). */
    @Volatile
    private var lastSentTapMs = 0L

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var detector: DroneDetector? = null

    private var screenW = 0
    private var screenH = 0
    private var capW = 0
    private var capH = 0
    private var pixels = IntArray(0)
    private var bytes = ByteArray(0)

    // Only touched on the capture thread.
    private var lastFrameMs = 0L
    private var lastTapMs = 0L
    private var lastSettingsMs = 0L
    private var beforeTapSignature: IntArray? = null
    private var beforeTapMs = 0L
    private var beforeTapX = 0f
    private var beforeTapY = 0f
    private var menuBaseline: IntArray? = null
    private var params = DroneDetector.Params()
    private var frameIntervalMs = 83L
    private var cooldownMs = 450L
    private var onlyEgg = true
    private var showMarks = true
    @Volatile
    private var autoBack = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        startInForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0

        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            if (data != null) mpm.getMediaProjection(code, data) else null
        } catch (e: RuntimeException) {
            null
        }
        if (proj == null) {
            stopCapture()
            return START_NOT_STICKY
        }
        projection = proj
        if (!startCapture(proj)) {
            stopCapture()
            return START_NOT_STICKY
        }
        instance = this
        TapService.instance?.showControls()
        refreshControls()
        return START_NOT_STICKY
    }

    private fun startCapture(proj: MediaProjection): Boolean {
        val metrics = Screen.realMetrics(this)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        val scale = Prefs.get(this, Prefs.SCALE)
        capW = max(64, screenW / scale)
        capH = max(64, screenH / scale)
        pixels = IntArray(capW * capH)
        detector = DroneDetector(capW, capH)
        readSettings()

        val t = HandlerThread("drone-capture").also { it.start() }
        thread = t
        val handler = Handler(t.looper)

        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopCapture() }
            }
        }, handler)

        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        virtualDisplay = try {
            proj.createVirtualDisplay(
                "DroneCatcher", capW, capH, max(1, metrics.densityDpi / scale),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
            )
        } catch (e: RuntimeException) {
            null
        }
        return virtualDisplay != null
    }

    /**
     * What happened to things that flew like drones this session, e.g.
     * "12 tapped · skipped: 3 kept changing direction, 1 didn't come in from the side".
     */
    fun report(): String {
        val det = detector ?: return ""
        val skipped = DroneDetector.Skip.entries
            .map { it to det.skipCount(it) }
            .filter { it.second > 0 }
            .joinToString(", ") { "${it.second} ${it.first.label}" }
        return "${det.tappedCount} drones tapped" + if (skipped.isEmpty()) "" else " · skipped: $skipped"
    }

    /** Floating button tap: dismisses the "menu open" wait if there is one, else pauses/resumes. */
    fun togglePause() {
        if (menuOpen) menuOpen = false else paused = !paused
        refreshControls()
    }

    fun stopCapture() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun refreshControls() {
        val label = when {
            paused -> "⏸ $taps"
            menuOpen -> "⏸ menu"
            else -> "🛸 $taps"
        }
        val active = !paused && !menuOpen
        main.post { TapService.instance?.setControlsState(label, active) }
    }

    override fun onDestroy() {
        instance = null
        virtualDisplay?.release()
        virtualDisplay = null
        val r = reader
        val t = thread
        reader = null
        thread = null
        if (t != null) {
            // Close the reader on the capture thread so a frame being processed finishes first.
            Handler(t.looper).post { r?.close() }
            t.quitSafely()
        } else {
            r?.close()
        }
        projection?.stop()
        projection = null
        TapService.instance?.hideControls()
        super.onDestroy()
    }

    private fun readSettings() {
        params = Prefs.params(this)
        frameIntervalMs = 1000L / Prefs.get(this, Prefs.FPS)
        cooldownMs = Prefs.get(this, Prefs.COOLDOWN).toLong()
        onlyEgg = Prefs.get(this, Prefs.ONLY_EGG)
        showMarks = Prefs.get(this, Prefs.MARKERS)
        autoBack = Prefs.get(this, Prefs.AUTO_BACK)
        detector?.setAvoidSpots(Prefs.avoidSpots(this))
    }

    private fun onImage(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: RuntimeException) {
            null
        } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameMs < frameIntervalMs) return
            lastFrameMs = now
            if (now - lastSettingsMs > SETTINGS_REFRESH_MS) {
                lastSettingsMs = now
                readSettings()
            }
            val det = detector ?: return
            if (paused) {
                det.reset()
                return
            }
            if (image.width != capW || image.height != capH) return

            val plane = image.planes[0]
            val buf = plane.buffer
            buf.rewind()
            val len = buf.remaining()
            if (bytes.size < len) bytes = ByteArray(len)
            buf.get(bytes, 0, len)
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val b = bytes
            val px = pixels
            for (y in 0 until capH) {
                var o = y * rowStride
                val row = y * capW
                for (x in 0 until capW) {
                    px[row + x] = ((b[o].toInt() and 0xff) shl 16) or
                        ((b[o + 1].toInt() and 0xff) shl 8) or
                        (b[o + 2].toInt() and 0xff)
                    o += pixelStride
                }
            }

            val target = det.process(px, now, params, excludedRect())
            watchForMenu(det, px, now)
            if (menuOpen || target == null || now - lastTapMs < cooldownMs) return
            lastTapMs = now
            det.onTapped(target, now)
            beforeTapSignature = det.signature(px, params)
            beforeTapMs = now
            beforeTapX = target.x
            beforeTapY = target.y

            val sx = target.x * screenW / capW
            val sy = target.y * screenH / capH
            val requireEgg = onlyEgg
            val mark = showMarks
            main.post {
                val svc = TapService.instance ?: return@post
                if (paused || instance == null) return@post
                if (requireEgg && !svc.isEggIncForeground()) return@post
                if (svc.tap(sx, sy)) {
                    lastSentTapMs = SystemClock.uptimeMillis()
                    taps++
                    if (mark) svc.mark(sx, sy)
                    refreshControls()
                }
            }
        } finally {
            image.close()
        }
    }

    /**
     * Notices when one of our taps hit a building or button and opened a menu: most of a
     * second later, the screen still looks very different from just before the tap. The spot is then remembered and never tapped again,
     * tapping stops until the screen looks like it did before the tap (the menu was
     * closed), and, if allowed, Back is pressed to close it.
     */
    private fun watchForMenu(det: DroneDetector, px: IntArray, now: Long) {
        val baseline = menuBaseline
        if (baseline != null) {
            // Cleared from the floating button: the player says carry on.
            if (!menuOpen) {
                menuBaseline = null
            } else if (DroneDetector.signatureDifference(baseline, det.signature(px, params)) < MENU_CLOSED_DIFFERENCE) {
                menuOpen = false
                menuBaseline = null
                refreshControls()
            }
            return
        }
        val before = beforeTapSignature ?: return
        val sentAt = lastSentTapMs
        if (sentAt < beforeTapMs) {
            // That tap was never sent (e.g. Egg, Inc. wasn't on screen).
            if (now - beforeTapMs > MENU_CHECK_UNTIL_MS) beforeTapSignature = null
            return
        }
        if (now - sentAt < MENU_CHECK_AFTER_MS) return
        beforeTapSignature = null
        if (now - sentAt > MENU_CHECK_UNTIL_MS) return
        if (DroneDetector.signatureDifference(before, det.signature(px, params)) < MENU_DIFFERENCE) return

        Prefs.addAvoidSpot(this, beforeTapX / capW, beforeTapY / capH)
        det.setAvoidSpots(Prefs.avoidSpots(this))
        menuBaseline = before
        menuOpen = true
        refreshControls()
        if (!autoBack) {
            main.post {
                Toast.makeText(
                    this,
                    "A tap opened a menu. Close it and catching carries on (that spot won't be tapped again).",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        main.post {
            val svc = TapService.instance ?: return@post
            if (!svc.isEggIncForeground() || !svc.pressBack()) return@post
            menusClosed++
            refreshControls()
            main.postDelayed({ returnToGameIfBackLeftIt() }, BACK_VERIFY_MS)
        }
    }

    /** Back exited Egg, Inc. instead of closing a menu: bring the game back and stop using Back. */
    private fun returnToGameIfBackLeftIt() {
        val svc = TapService.instance ?: return
        if (instance == null || svc.isEggIncForeground()) return
        svc.bringEggIncToFront()
        autoBack = false
        backLeftGame = true
        Prefs.set(this, Prefs.AUTO_BACK, false)
        Toast.makeText(
            this,
            "Back left Egg, Inc. instead of closing a menu, so \"Press Back if a tap opens a menu\" is now off",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** Our floating button, mapped into capture coordinates, so its updates aren't seen as motion. */
    private fun excludedRect(): IntArray? {
        val r = TapService.instance?.controlsRect() ?: return null
        return intArrayOf(
            r[0] * capW / screenW - 1,
            r[1] * capH / screenH - 1,
            r[2] * capW / screenW + 2,
            r[3] * capH / screenH + 2,
        )
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Drone catching", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_drone)
            .setContentTitle("Drone Catcher is running")
            .setContentText("Watching the sky for drones")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
