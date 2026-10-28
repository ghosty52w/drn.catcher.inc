# Drone Catcher

An Android app that spots the drones flying over your farm in **Egg, Inc.** and taps them for you.

It watches the screen (Android screen capture), finds small objects that move steadily across
the sky, and taps slightly ahead of them through an accessibility service. No root is needed.

## Install

1. On your Android phone, open the
   [latest release](../../releases/latest) and download **DroneCatcher.apk**.
   (Every push to this repo rebuilds it. The APK is also attached to each run on the
   [Actions](../../actions) tab.)
2. Open the downloaded file. If Android asks, allow your browser/files app to install unknown apps.
3. Requires Android 8.0 or newer.

## Set up (once)

1. Open **Drone Catcher** and tap **1. Enable accessibility service**.
2. Find **Drone Catcher taps** in the list and turn it on.
   - On Android 13+ it may be greyed out as a *restricted setting* because the app was
     sideloaded. Tap **Open app info** in Drone Catcher, tap **⋮** in the top corner, choose
     **Allow restricted settings**, then go back and turn the service on.

## Use

1. Tap **2. Start catching** and allow screen capture (pick **Entire screen** if asked).
2. Egg, Inc. opens. Stay on the farm view and let it run.
3. A floating **🛸** button shows how many taps it has made.
   - **Tap** it to pause or resume.
   - **Long-press** it to stop.
   - **Drag** it anywhere out of the way.
4. You can also stop it from the notification.

By default it only taps while Egg, Inc. is the app on screen, so it won't tap anything if you
switch apps or pull down the notification shade.

## Tuning

Tap **Preview search area in Egg, Inc.** to see the blue box where it looks for drones. It should
cover the sky but not the money counter at the top, or the chickens, trucks and buttons at the
bottom. Adjust **Search area top / bottom** until it does.

While it runs, the main screen shows how many drones it tapped and, for anything that flew like
a drone but wasn't tapped, which rule stopped it (for example "kept changing direction" or
"didn't come in from the side"). Use that to pick what to change below.

| Problem | Try |
| --- | --- |
| Misses drones, nothing listed as skipped | It isn't seeing them: check the search area covers where they fly, then lower **Motion threshold** or **Smallest drone** |
| Skipped: "didn't fly far enough" | Lower **Shortest flight** |
| Skipped: "didn't come in from the side" | Turn off **Only tap things that fly in from the side** |
| Skipped: "still there after every retry" | The taps are missing: adjust **Aim ahead** |
| Taps land behind/in front of fast drones | Leave **Learn the aim ahead by itself** on and give it a few minutes; or set **Aim ahead** yourself |
| Taps the contract eggs or event pills | Keep **Never tap near the contract eggs and event pills** on |
| Taps things that aren't drones | Raise **Shortest flight**, **Motion threshold** or **Smallest drone**, or shrink the search area |
| Never taps drones at all | Turn off **Only tap things that fly in from the side** |
| Battery drain | Lower **Checks per second** or raise **Downscale** |

## How it works

- `CaptureService` mirrors the screen into a small (¼ size by default) image about 12 times a second.
- `DroneDetector` keeps a slowly updating background picture of the search area, marks pixels
  that changed, groups them into blobs, and follows blobs across frames. A blob is only tapped
  once it has flown in from the side, travelled a real distance in a fairly straight, mostly
  sideways line, and is drone-sized and drone-fast. A drone that was hidden behind the game's
  side buttons and first shows up part-way in is still caught once it has flown a bit further.
  Animated buildings and buttons wobble or spin in place, so they don't qualify; areas that animate constantly are learned and ignored.
  Big sudden changes (menus opening) reset the model, and nothing is tapped until the screen
  settles.
- If a tap opens a menu (most of a second later the screen still looks very different from
  just before the tap), it remembers that spot and never taps there again, and stops tapping
  until the menu is closed (the floating button shows "⏸ menu"; tap it to carry on anyway).
  **Forget spots it learned to avoid** clears the list.
- It can also press Back to close such a menu. If Back takes you out of Egg, Inc. instead, it
  brings the game straight back and switches this off.
- A tap that misses is retried up to 4 times (a fast drone gets a second try after 0.15 s),
  aiming a little later, then earlier.
- **Aim ahead learns itself**: when the first taps at fast drones keep missing, it varies their
  aim slightly and moves toward the aims that catch drones. It's saved, so it carries over to
  the next session. The main screen shows the current value.
- It never taps the strip down the left where the contract eggs and event pills are (however
  long that column gets) or the tab on the right.
- `TapService` (accessibility service) taps the predicted position, draws a brief ring there,
  and checks Egg, Inc. is in the foreground.

## Tests

The detector is plain Kotlin and has three test suites (`app/src/test`):

- `DroneDetectorTest`: focused cases (spinning, wobbling, rising things are ignored; bobbing,
  late-appearing and missed drones are caught; avoided spots and no-tap areas are respected).
- `MenuWatcherTest`: telling an opened menu from a passing flash.
- `DroneCatchSimulationTest`: end to end on `FarmSim`, a stand-in for the farm laid out after a
  real screenshot, with many kinds of drones (slow, elite, bobbing, angled, small, white...),
  phones (laggy, snappy, slow, dropping frames) and farms (contract columns of different
  lengths, noisy or dim screens). A tap counts only if it lands on the drone when it reaches the
  screen; every scenario also checks taps stay off the game's buttons and buildings.

## Build it yourself

```sh
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Needs JDK 17+ and the Android SDK (API 35). The signing key in `keystore/` is a throwaway shared
key so every build installs over the previous one; don't reuse it for anything else.

## Note

Automating taps may go against Egg, Inc.'s terms of service. Use at your own risk.
