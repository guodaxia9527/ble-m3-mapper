# Bluetooth Remote Mapper for YouTube

[简体中文](README.md) · [English](README_EN.md)

Use a Bluetooth remote to control YouTube playback and navigate transcript lines while studying English or any other language. Skip back to a sentence you missed, then move to the next one without reaching for the phone.

## Features

- With the YouTube transcript open, Left and Right move to the previous or next caption. When it is closed, those buttons seek by the configured number of seconds.
- Up and Down seek. While paused, triple press Left to go to the start or Right to go near the end.
- Use the remote to seek, let the video play for at least half a second, and pause. While paused, double press Center to loop the segment. Press Center once to pause and exit the loop. Triple press Center to like the video.
- The sixth button toggles the transcript panel.
- Enable the optional transcript highlight and grant Android permission to display over other apps. The outline follows the visible current line in the YouTube transcript.
- Configure all six actions per app. Multiple remotes can be enabled and used at the same time.
- Learn additional remotes when Android exposes them as standard external input devices.

## Hardware compatibility

The app does not depend on a particular Bluetooth chipset or MCU. It depends on the **input interface Android exposes**:

- Built-in profiles: BLE-M3 and Beauty-R1. Both were tested together on one phone.
- BLE-M3 reports relative mouse movement (`EV_REL`, `REL_X` / `REL_Y`, `BTN_LEFT`) plus a Consumer Control interface.
- Beauty-R1 reports absolute touch coordinates (`EV_ABS`, `ABS_X` / `ABS_Y`, `BTN_TOUCH`) plus a Consumer Control interface.
- The learner also supports standard key/consumer-key events, relative mouse gestures, and absolute touch gestures.

A remote is a candidate if Android lists it as an external keyboard/media-key, relative mouse, or absolute touch input device and exposes the corresponding Linux evdev events. A matching brand name or Bluetooth version alone does not establish compatibility. Remotes that use a proprietary BLE GATT protocol, infrared, or a private vendor channel without compatible Android input events are not supported by the learner yet.

Support for other devices and Android builds is not guaranteed until tested. The confirmed phone is a realme GT Neo2 running Android 13. Beauty-R1 model information is listed in this [FCC device record](https://fccid.io/2A5C6-BEAUTY-R1); the protocol details above come from input events observed on Android, not from the FCC listing.

## Requirements

- An Android phone and YouTube.
- A supported Bluetooth HID remote connected in Android Bluetooth settings.
- The official [Shizuku](https://shizuku.rikka.app/) app, installed and running with this app authorized. After a phone reboot, start Shizuku again.

No computer connection is needed for normal use. The app does not require an Accessibility service. Shizuku provides the privileged access used to grab the matching input devices and control media.

## Default YouTube mapping

| Button | Action |
| --- | --- |
| Up / Down | Seek backward / forward 5 seconds |
| Left / Right | Previous / next transcript line; otherwise seek ±10 seconds |
| Center, single press | Play / pause |
| Left / Right, triple press while paused | Go to the start / about 1.5 seconds before the end |
| Center, double press while paused | Loop the segment after a remote seek, or loop the current transcript line with sentence looping enabled and the transcript open |
| Center, triple press | Like |
| Sixth button | Toggle transcript |

The app briefly shows the loop's start and end times. For sentence looping, keep the current line and the next line visible. Actions and seek durations can be changed in the app. Transcript-panel controls depend on YouTube's current interface and may need updates if YouTube changes it.

## Build

The Android app is in `android-app/` (application ID `com.guodaxia.blem3mapper`). The computer-connected prototype remains in `tools/`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd android-app
.\gradlew.bat :app:assembleDebug
```

The current debug build targets ARM64. See [validation notes](android-app/VALIDATION.md) and the Chinese README for setup history and detailed implementation notes.

## Search keywords

YouTube remote control, Bluetooth HID remote, BLE remote, Android media remote, transcript navigation, language learning, BLE-M3, Beauty-R1, Shizuku, Android evdev input.

## License and attribution

See the project files and [native provenance notes](native/PROVENANCE.md) for implementation and attribution details.
