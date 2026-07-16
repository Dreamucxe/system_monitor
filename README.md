# System Monitor

A lightweight Android system monitor showing live CPU, RAM, GPU, and network stats via a home-screen widget and an optional floating usage dock.

## Features

- Live **CPU / RAM / GPU / network** readouts
- Home-screen **app widget** (`MonitorWidget`)
- Floating **usage dock** overlay (`DockService`, `SYSTEM_ALERT_WINDOW`)
- Foreground **monitor service** with data-sync updates
- Auto-start on boot (`BootReceiver`)
- Optional **root** probes for deeper stats (`RootHelper`)
- Per-app RAM breakdown and a simple history graph (`GraphView`)

## Package

`com.monitor.sysmon` — minSdk 24, targetSdk 34.

## Source layout

```
src/com/monitor/sysmon/
  MainActivity.java     App entry / dashboard
  MonitorService.java   Foreground data-sync service
  DockService.java      Floating overlay dock
  MonitorWidget.java    Home-screen widget provider
  WidgetState.java      Widget state
  BootReceiver.java     Start on boot
  SysInfo.java          CPU / system info
  RamLog.java           RAM sampling
  AppRam.java           Per-app RAM
  GpuProbe.java         GPU stats
  NetStats.java         Network throughput
  RootHelper.java       Root command helper
  GraphView.java        History graph view
  DockPrefs.java        Dock preferences
  AlertPrefs.java       Alert thresholds
monitor.py              Helper script
build.sh                Manual aapt2/javac/d8 build
```

## Build

Manual Android toolchain build (no Gradle):

```bash
./build.sh
```

Requires `aapt2`, `javac`, `d8`, `zipalign`, and `apksigner` on PATH plus an `android.jar`. The script signs with a local debug keystore.

## Note

The `SYSTEM_ALERT_WINDOW` (overlay) and boot permissions are used for the floating dock and auto-start. Root probes are optional and degrade gracefully when root is unavailable.
