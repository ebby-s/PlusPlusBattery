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

---

## Fork addition: charge/discharge event tracking, health & wear

Adds an AccuBattery-style subsystem (clean-room, no third-party code copied).

### Tracking (`BatteryTrackerService`)
- Always-on `specialUse` foreground service, **enabled by default**.
- Low power: fully event driven (battery/screen/power broadcasts) plus a slow
  safety tick (60 s screen-on / 5 min screen-off) that holds **no wakelock**;
  sample and flash writes are throttled.
- mAh per session from the charge-counter delta, falling back to trapezoidal
  current integration. Deep sleep from `elapsedRealtime - uptimeMillis` deltas.
- Durable in-progress row survives process death/reboot; restarts on boot.

### Session coalescing
- Brief opposite-state blips (e.g. a charge-limit controller flickering at the
  top of the range) do **not** split a session.
- A new session starts only when the level moves >= 2% in the new direction, or
  >= 50 mAh of charge has moved, or the opposite state persists for 10 minutes.
- On restart, a session is resumed unless it has been idle > 30 min **and** the
  level/counter moved materially.

### History tab (replaces the old daily cycle-count view)
- Every charge and discharge session: `start% -> end%`, mAh, duration, screen
  on/off/deep sleep, wear, delete, and CSV export.

### Health tab (new)
- Capacity estimate from sessions `<15% -> >80%`, averaged over the last 5.
- The thresholds are user-configurable in Settings (default 15% / 80%).
- Estimated vs design capacity, health %, data-point count, total %/mAh.
- Capacity-vs-time scatter with linear regression: loss/year and R².
- Wear: per-session cycles (throughput / design capacity), cycles/day over
  7/30/365 days with totals, cumulative-wear chart, plus the legacy cycle-count
  chart.

### Database
- Room v1 -> v2 -> v3 with real migrations; existing cycle history preserved.

### Root read robustness
- libsu can cache a non-root shell if the first su request raced the Magisk
  prompt (e.g. the device was locked). Root reads then all came back empty
  ("unknown") even though `su -c whoami` still succeeded.
- Root reads now check the cached shell and, when it is not root, fall back to a
  batched `su -c` read (one invocation for all nodes), so root mode works
  regardless.
- `hasRoot` now reflects libsu's shell state, and root mode is a persisted user
  preference that is no longer auto-disabled when root is momentarily
  unavailable.


