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

| Problem | Try |
| --- | --- |
| Misses drones | Lower **Motion threshold** or **Smallest drone** |
| Taps land behind drones | Raise **Aim ahead** |
| Taps land in front of drones | Lower **Aim ahead** |
| Taps things that aren't drones | Raise **Shortest flight**, **Motion threshold** or **Smallest drone**, or shrink the search area |
| Never taps drones at all | Turn off **Only tap things that fly in from the side** |
| Battery drain | Lower **Checks per second** or raise **Downscale** |

## How it works

- `CaptureService` mirrors the screen into a small (¼ size by default) image about 12 times a second.
- `DroneDetector` keeps a slowly updating background picture of the search area, marks pixels
  that changed, groups them into blobs, and follows blobs across frames. A blob is only tapped
  once it has flown in from the side, travelled a real distance in a fairly straight, mostly
  sideways line, and is drone-sized and drone-fast. A drone that was hidden behind the game's
  side buttons and first shows up part-way in is still caught once it has flown a bit further. Animated buildings and buttons wobble or
  spin in place, so they don't qualify; areas that animate constantly are learned and ignored.
  Big sudden changes (menus opening) reset the model, and nothing is tapped until the screen
  settles.
- If the screen changes completely right after a tap, that tap probably opened a menu, so it
  presses Back once to close it (can be switched off).
- `TapService` (accessibility service) taps the predicted position, draws a brief ring there,
  and checks Egg, Inc. is in the foreground.

## Build it yourself

```sh
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Needs JDK 17+ and the Android SDK (API 35). The signing key in `keystore/` is a throwaway shared
key so every build installs over the previous one; don't reuse it for anything else.

## Note

Automating taps may go against Egg, Inc.'s terms of service. Use at your own risk.
