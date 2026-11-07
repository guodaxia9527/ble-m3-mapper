# BLE-M3 diagnostic capture

`ble_m3_capture.c` was independently implemented for this investigation from the
input events captured on the user's Android 13 device. It contains no copied
source from the repositories below.

The existing [BLE-M3 Android ARM64 interceptor](https://github.com/maskelog/BLE-M3-android-arm64)
provided a useful implementation precedent: open the BLE-M3 evdev device, use
`EVIOCGRAB` to request exclusive input delivery, then translate the input.
That upstream project is GPL-3.0. Its code has not been vendored here.

The diagnostic here only identifies and reports gestures. It does not inject
keypresses. It checks whether the exclusive grab actually succeeded and releases
it on normal exit, timeout, and handled termination signals. Kernel descriptor
cleanup also releases the grab if the process is killed.

Observed samples, counting movement only while `BTN_LEFT` is pressed:

| Physical key | dx | dy | Diagnostic output |
| --- | ---: | ---: | --- |
| Up | 2 | 243 | `ACTION UP` |
| Center | 0 | 0 | `ACTION CENTER` |
| Down | 2 | -153 | `ACTION DOWN` |

Movement before the button press or after its release is cursor positioning and
is ignored. Left/right have now been measured as dx=450 / dx=-450. The sixth key
combines a zero-movement mouse click with KEY_VOLUMEUP or KEY_VOLUMEDOWN from the
Consumer Control interface. Correlating their event timestamps distinguishes
PHOTO from CENTER. A 75 ms pending window accommodates delivery order differences.
Queue overflow discards the partial gesture.

Targets are found by the exact kernel names `BLE-M3 Mouse` and
`BLE-M3 Consumer Control`. Duplicate names prevent acquisition. Other input
devices are opened briefly only to query their kernel name; their events are
never read or grabbed.

Example Android NDK build (substitute the local compiler path):

```text
aarch64-linux-android23-clang -std=c11 -O2 -Wall -Wextra -Werror ble_m3_capture.c -o ble_m3_capture
```

Modes:

```text
ble_m3_capture --probe
ble_m3_capture --listen --seconds 180
```

`--probe` reports `GRAB_OK` per interface only if `EVIOCGRAB` succeeds, then releases
both descriptors. `--listen` has a default limit of 180 seconds and a maximum of
600 seconds for positive durations, including reconnect waits. The explicit
`--seconds 0` app mode listens until stopped. `PR_SET_PDEATHSIG` ensures that
parent process/thread termination also releases the devices; the parent identity
is checked after installing the signal to close the setup race. Running under `adb shell` is supported
on the tested phone without root. Other phones require validation.

`--replay FILE` classifies a text log produced by `ble_m3_record` without opening
or grabbing input devices. Replaying the complete six-button capture produced
RIGHT=90, LEFT=4, UP=2, DOWN=7, CENTER=16, PHOTO=14. Its last five actions are
PHOTO, matching the user's stated last five physical presses. This checks the
same parser that live capture uses, including interleaved interface delivery.
