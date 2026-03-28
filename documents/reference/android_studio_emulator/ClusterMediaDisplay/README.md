# ClusterMediaDisplay

## After you have run this at lease once.
## You must always after an emulator reboot or fresh start. Send 'adb shell cmd car_service inject-vhal-event 0x11400F34 2'
## THe cluster display DOES NOT autoswitch to its Media Mode on its own. Unlike the Nav portion.


A standalone media metadata display app for the AAOS instrument cluster. Observes the active `MediaSession` and renders track title, artist, album art, and playback state on the cluster display.

Works with **any** media source — CarLink, Spotify, YouTube Music, or any app that publishes a `MediaSession`.

## How It Works

```
ClusterHomeSample receives CLUSTER_SWITCH_UI=2 (music)
  → launches ClusterMediaActivity (via RRO override of config_clusterMusicActivity)
    → MediaSessionManager.getActiveSessions()
      → observes active session metadata + playback state
      → renders on cluster display (528x692, 516x680 usable)
```

The AOSP `ClusterMusicActivity` is a skeleton that shows static text. This app replaces it with a real implementation that reads from `MediaSessionManager`.

## Requirements

- **AAOS emulator** with cluster display (API 32+, tested on API 35)
- **adb root** and **adb remount** access (userdebug build)
- **Gradle** and **Android SDK** installed

## Project Structure

```
ClusterMediaDisplay/
├── app/                          # Main application
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/.../ClusterMediaActivity.java
│       └── res/
│           ├── layout/activity_cluster_media.xml
│           ├── drawable/ic_music_note.xml
│           └── values/strings.xml
├── overlay/                      # RRO overlay (pre-built)
│   ├── AndroidManifest.xml       # Overlay manifest (source)
│   ├── res/values/config.xml     # Overrides config_clusterMusicActivity
│   └── CarlinkClusterOverlay.apk # Signed overlay APK (ready to push)
├── permissions/                  # Privapp permissions
│   └── privapp-permissions-clustermedia.xml
├── deploy.sh                     # One-command deploy script
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Quick Start

### 1. Build

```bash
./gradlew assembleDebug
```

### 2. Deploy

```bash
./deploy.sh              # defaults to emulator-5554
./deploy.sh emulator-5556  # or specify a serial
```

The deploy script:
1. Roots and remounts the emulator
2. Pushes the APK to `/system/priv-app/`
3. Pushes privapp permissions XML to `/system/etc/permissions/`
4. Pushes the RRO overlay to `/product/overlay/`
5. Reboots and verifies installation

### 3. Test

Switch the cluster to music view:

```bash
adb shell cmd car_service inject-vhal-event 0x11400F34 2
```

Switch back to home:

```bash
adb shell cmd car_service inject-vhal-event 0x11400F34 0
```

## Why Priv-App?

The app requires `android.permission.MEDIA_CONTENT_CONTROL` to call `MediaSessionManager.getActiveSessions()`. This is a `signature|privileged` permission — only granted to apps installed in `/system/priv-app/` (or signed with the platform key). A normal `adb install` cannot obtain this permission.

## Why RRO Overlay?

The AAOS `ClusterHomeSample` reads `config_clusterMusicActivity` from its resources to determine which Activity to launch on the cluster when the music UI type is requested. The RRO (Runtime Resource Overlay) overrides this string to point to our `ClusterMediaActivity` instead of the stock skeleton.

The overlay must reside on `/product/overlay/` (not `/data/`) because the target resource's overlayable policy requires `product|system|vendor` partition placement.

## Cluster Display Details (AAOS Emulator)

| Property | Value |
|----------|-------|
| Display ID | 3 (virtual, type=CLUSTER) |
| Resolution | 528 x 692 @ 160dpi |
| Usable bounds | Rect(12, 12 - 516, 680) = 504 x 668 |
| Insets | left=90, top=15, right=90, bottom=15 |
| Orientation | Portrait |

## Cluster UI Types (CLUSTER_SWITCH_UI / 0x11400F34)

| Value | UI Type | Activity |
|-------|---------|----------|
| 0 | HOME | ClusterHomeActivity |
| 1 | MAPS | Navigation activity |
| 2 | MUSIC | ClusterMediaActivity (ours) |
| 3 | PHONE | ClusterPhoneActivity |

## Rebuilding the RRO Overlay

If you need to change the target activity component:

```bash
# Edit overlay/res/values/config.xml with new component name, then:
export ANDROID_HOME="$HOME/Library/Android/sdk"
AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"

cd overlay
$AAPT2 compile --dir res -o compiled.zip
$AAPT2 link -o CarlinkClusterOverlay.apk \
  -I $ANDROID_HOME/platforms/android-35/android.jar \
  --manifest AndroidManifest.xml compiled.zip

# Sign (create keystore first if needed):
# keytool -genkey -v -keystore debug.keystore -storepass android \
#   -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
#   -validity 10000 -dname "CN=Debug"
$APKSIGNER sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --v2-signing-enabled true \
  CarlinkClusterOverlay.apk

rm -f compiled.zip
```
