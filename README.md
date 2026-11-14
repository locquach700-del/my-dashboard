# Klyro Screen Studio

A native Android screen recorder for Android 10 (API 29)+.

## Build

GitHub Actions builds an installable, Android-debug-signed APK when the finalized build commit is pushed to main (or when manually dispatched). Download the Klyro-APK artifact from the workflow run.

## Features in this first implementation

- MP4 screen recording through Android MediaProjection.
- Microphone audio on video recordings, or microphone-only M4A recordings.
- Pause/resume and stop from a movable floating controller and notification.
- 3-second countdown toggle.
- Resolution, FPS and bitrate settings with hardware-capability checks and safe fallbacks.
- Local recording history in Movies/Klyro and Music/Klyro.
- Vietnamese-first automatic language selection plus manual language setting.
- Custom tap-marker settings and optional Accessibility Service marker. The marker uses clicked-view bounds (not raw touch coordinates), so games/canvas surfaces may not emit events.
- Local-only operation; no analytics, accounts or network permission.

## Platform limitations

Android requires user approval for every MediaProjection session. Internal audio capture depends on Android version and whether the target app permits playback capture. This build's recorder currently captures the microphone, not protected/system audio. FLAG_SECURE windows may appear blank. 4K/high-FPS output is limited by the phone display and hardware encoder; selecting a higher value does not create real detail on a lower-resolution display.

The tap marker service is opt-in and only draws a local highlight around accessibility click events. It does not record screen text or send data anywhere. For accurate system-wide raw touch dots, use Android/OEM Developer Options' built-in “Show taps” setting.

## Signing

The workflow uses Android Gradle's automatically generated debug signing key. The APK is signed and installable for testing, but a fresh CI runner may generate a different debug certificate, so a future build may require uninstalling the previous build before installing the new one. This is not a Play Store production signing setup.
