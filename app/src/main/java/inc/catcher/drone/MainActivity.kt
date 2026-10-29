package inc.catcher.drone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CAPTURE = 1
        private const val REQUEST_NOTIFICATIONS = 2

        private const val TIPS = """• Tap Start, choose "Entire screen" if Android asks, then play Egg, Inc. on the farm view.
• Tap the floating 🛸 button to pause or resume. Long-press it to stop. Drag it out of the way.
• Use "Preview search area" to check the blue box covers the sky where drones fly, but not the money counter, chickens, trucks or buttons.
• Missing drones? Lower "Motion threshold" or "Smallest drone". Taps landing behind drones? Raise "Aim ahead".
• Tapping things that aren't drones? Raise "Shortest flight", "Motion threshold" or "Smallest drone", or shrink the search area.
• Not tapping drones at all? Try turning off "Only tap things that fly in from the side".
• Taps only happen while Egg, Inc. is on screen (unless you turn that off below)."""
    }

    private lateinit var statusView: TextView
    private val switches = ArrayList<Pair<Switch, Prefs.Toggle>>()
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })

        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(statusView)

        root.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(note("Find \"Drone Catcher taps\" and turn it on. If it's greyed out (\"Restricted setting\"), open App info, tap ⋮ in the corner, choose \"Allow restricted settings\", then try again."))
        root.addView(button("Open app info") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(button("2. Start catching") { requestCapture() })
        root.addView(button("Stop") { CaptureService.instance?.stopCapture() })
        root.addView(button("Preview search area in Egg, Inc.") { previewRegion() })

        root.addView(heading("Settings"))
        for (s in Prefs.SLIDERS) root.addView(sliderRow(s))
        for (t in Prefs.TOGGLES) root.addView(toggleRow(t))
        root.addView(button("Forget spots it learned to avoid") {
            Prefs.clearAvoidSpots(this)
            toast("Forgot all avoided spots")
        })
        root.addView(button("Reset to defaults") {
            Prefs.reset(this)
            recreate()
        })

        root.addView(heading("Tips"))
        root.addView(note(TIPS))

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        // The service can change a setting itself (e.g. switching auto-close off).
        for ((view, t) in switches) view.isChecked = Prefs.get(this, t)
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refreshStatus() {
        val capture = CaptureService.instance
        statusView.text = buildString {
            append(if (TapService.instance != null) "✅ Accessibility service is on" else "❌ Accessibility service is off")
            append('\n')
            append(
                when {
                    capture == null -> "⏹ Not catching"
                    capture.paused -> "⏸ Paused · ${capture.taps} taps"
                    else -> "🛸 Catching · ${capture.taps} taps"
                },
            )
            if (capture != null) append("\n📋 ${capture.report()}")
            if (capture != null && capture.menuOpen) {
                append("\n⏸ A tap opened a menu. Close it in Egg, Inc. and catching carries on.")
            }
            val avoided = Prefs.avoidSpots(this@MainActivity).size
            if (avoided > 0) append("\n🚫 Avoiding $avoided spots where a tap opened a menu")
            if (capture != null && capture.menusClosed > 0) {
                append("\n↩ Closed ${capture.menusClosed} menus opened by mistake (by switching here and back)")
            }
            if (eggIncLaunchIntent() == null) append("\n⚠ Egg, Inc. isn't installed on this device")
        }
    }

    private fun requestCapture() {
        if (TapService.instance == null) {
            toast("Turn on the Drone Catcher accessibility service first")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (CaptureService.instance != null) {
            toast("Already catching")
            launchEggInc()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Screen capture is needed to spot drones")
            return
        }
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data),
        )
        if (Prefs.get(this, Prefs.LAUNCH_EGG)) ui.postDelayed({ launchEggInc() }, 300)
    }

    private fun previewRegion() {
        if (TapService.instance == null) {
            toast("Turn on the Drone Catcher accessibility service first")
            return
        }
        val p = Prefs.params(this)
        launchEggInc()
        ui.postDelayed({ TapService.instance?.previewRegion(p.roiTop, p.roiBottom) }, 700)
    }

    private fun eggIncLaunchIntent(): Intent? = packageManager.getLaunchIntentForPackage(Prefs.EGG_INC)

    private fun launchEggInc() {
        val intent = eggIncLaunchIntent()
        if (intent == null) {
            toast("Egg, Inc. isn't installed")
            return
        }
        startActivity(intent)
    }

    private fun sliderRow(s: Prefs.Slider): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val title = TextView(this).apply { textSize = 15f }
        fun show(v: Int) {
            title.text = "${s.label}: $v ${s.unit}".trimEnd()
        }
        val bar = SeekBar(this).apply {
            max = s.max - s.min
            progress = Prefs.get(this@MainActivity, s) - s.min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    show(progress + s.min)
                    if (fromUser) Prefs.set(this@MainActivity, s, progress + s.min)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        show(Prefs.get(this, s))
        box.addView(title)
        box.addView(bar)
        if (s.help.isNotEmpty()) box.addView(note(s.help))
        return box
    }

    private fun toggleRow(t: Prefs.Toggle): View = Switch(this).also { switches.add(it to t) }.apply {
        text = t.label
        textSize = 15f
        isChecked = Prefs.get(this@MainActivity, t)
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, checked -> Prefs.set(this@MainActivity, t, checked) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        alpha = 0.75f
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
