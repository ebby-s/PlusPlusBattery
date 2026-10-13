# OnePlus 6 (enchilada) / generic-device build

This fork of [PlusPlusBattery](https://github.com/dijia1124/PlusPlusBattery) adapts the
app to run correctly on a **OnePlus 6 running LineageOS (AOSP) with Magisk root**.

Upstream targets newer OnePlus/Oppo/Realme (ColorOS/OxygenOS) kernels that expose
`/sys/class/oplus_chg/*` and silicon-carbon battery compensation. The OnePlus 6 has
neither, so several root-mode entries showed `Unknown` and the framework current was
off by 1000x. This build fixes both and auto-detects the device.

## Changes

### 1. Device backend auto-detection
- Added `RootBackend` (`OPLUS` / `GENERIC`) and `detectRootBackend()` in
  `data/util/BattUtils.kt`. Detection is cached; `OPLUS` means `/sys/class/oplus_chg`
  exists, everything else uses the standard Linux `power_supply` nodes.
- `getRootBatteryInfo()` now dispatches to the original OPlus implementation or a new
  generic implementation. OPlus phones behave exactly as before.

### 2. Generic (Qualcomm) root entries
`BatteryInfoRepository.getGenericRootBatteryInfo()` reads, on the OnePlus 6:

| Entry                    | Node                                        |
|--------------------------|---------------------------------------------|
| Remaining Charge Counter | `/sys/class/power_supply/bms/charge_counter` |
| Full Charge Capacity     | `/sys/class/power_supply/bms/charge_full`    |
| Design Capacity          | `/sys/class/power_supply/bms/charge_full_design` |
| State of Health          | FCC / design x 100                           |
| Battery Type             | `/sys/class/power_supply/bms/battery_type`   |
| Voltage / Current        | `/sys/class/power_supply/battery/voltage_now`, `current_now` |

`charge_*` values are microampere-hours, `voltage_now` microvolts and `current_now`
microamps, so they are converted to mAh / mV / mA. New `BatteryInfoType` entries and
strings were added for these.

### 3. Current / power unit fix (the big one)
`BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` returns **microamperes** per the Android
API, but the app treated it as milliamps. On the OnePlus 6 this showed e.g.
`88000 mA` and `348 W`. All framework current reads now divide by 1000
(non-root display, root fallback, and the FCC estimator threshold), giving
`88 mA` / `0.35 W`.

### 4. Cycle count fallback
If the framework doesn't report `EXTRA_CYCLE_COUNT`, the app now falls back to
`bms/cycle_count` / `battery/cycle_count` in root mode (used for the dashboard and the
history log). Note: this device's fuel gauge currently reports `0`, so the value is 0.

### 5. Hide inapplicable UI
When the backend is not OPlus, the "Show OPlus exclusive entries" toggle and the
"Experimental: logcat battery data" entry are hidden (both are OPlus-only and
meaningless on LineageOS). OPlus entries are also filtered out automatically.

### 6. Android 15 foreground-service fix
`dataSync` foreground services are capped at 6 hours on Android 15, which would stop
the battery monitor. Both services now use `foregroundServiceType="specialUse"`
(manifest + typed `startForeground`), so monitoring runs indefinitely.

## Build

```
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
apksigner sign --ks <keystore> --out PlusPlusBattery-OnePlus6.apk aligned.apk
```

Tested on OnePlus 6 (ONEPLUS A6003), LineageOS for microG 22.2 (Android 15),
kernel 4.9.337, Magisk 30.7.
