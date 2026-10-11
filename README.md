<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="140" height="140" alt="Extension Box Logo" />
</p>

<h1 align="center">Extension Box</h1>

<p align="center">
  <strong>A modular system monitoring framework, device utility suite, and live telemetry engine for Android.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/omersusin/ExtensionBox?style=flat-square&color=blue" alt="License"></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B%20%7C%20Target%2036%29-green?style=flat-square&logo=android" alt="Platform"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Language-Kotlin%202.1-purple?style=flat-square&logo=kotlin" alt="Kotlin"></a>
  <a href="https://developer.android.com/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-orange?style=flat-square&logo=jetpackcompose" alt="Compose"></a>
  <a href="https://github.com/rikka-apps/Shizuku"><img src="https://img.shields.io/badge/Elevated%20Access-Shizuku-informational?style=flat-square" alt="Shizuku"></a>
  <a href="https://github.com/omersusin/ExtensionBox/actions"><img src="https://img.shields.io/github/actions/workflow/status/omersusin/ExtensionBox/build.yml?branch=main&style=flat-square&logo=github-actions" alt="Build Status"></a>
</p>

---

## 📌 Overview

**Extension Box** is a modern, open-source Android application designed for high-fidelity hardware and software system monitoring. Built with an extensible modular architecture, Extension Box isolates each telemetry source into independent **extensions** (modules). This modular core allows users to granularly toggle telemetry sources, adjust refresh intervals, reorder dashboard cards, and monitor system performance without sacrificing battery life or background resources.

In addition to system monitoring, Extension Box includes powerful system utilities like an intelligent **Debloater** with safety risk levels, a real-time **Privacy Guard**, network speed benchmarking, and **14+ Material 3 Home Screen Widgets**.

---

## ✨ Key Features

### 🧩 1. Modular Telemetry Engine
- **Independent Extension Lifecycle**: Toggle telemetry sources on or off dynamically.
- **Custom Refresh Rates**: Set individual update intervals for every active extension.
- **Dynamic Reorderable Dashboard**: Drag and drop cards to prioritize hardware metrics.
- **Foreground Service Runtime**: Powered by a unified `MonitorService` that guarantees persistent data collection with minimal power usage.

### 🔋 2. Comprehensive System Modules (15+ Telemetry Extensions)
- 🔋 **Battery & Energy**: Real-time current (mA), power draw (W), voltage, temperature, health status, and charging state diagnostics.
- ⚡ **CPU Utilization**: Live multi-core processor utilization rates and load metrics.
- 🧠 **RAM / Memory**: Total, active, free memory tracking, swap space allocation, and live memory charts.
- 📱 **Screen Time**: Screen-on vs. screen-off duration, unlock frequencies, and per-app foreground time tracking.
- 🌙 **Sleep Tracker**: Deep sleep / Doze mode detection, device idle efficiency, and sleep duration analytics.
- 📶 **Network Speed**: Live uplink and downlink throughput with status bar / notification banner monitor.
- 📊 **Data Usage**: Cellular and Wi-Fi traffic accounting with per-interface breakdown.
- 🔓 **Unlock Counter**: Daily device unlock frequency, session durations, and interaction intervals.
- 💾 **Storage Telemetry**: Internal storage availability, system partition stats, and usage distribution.
- 🌐 **Connection Monitor**: Active Wi-Fi SSID/BSSID, IP addresses, VPN connection state, and network interface details.
- ⏱️ **System Uptime**: Kernel uptime, time since last boot, and deep sleep vs. active state breakdown.
- 👟 **Step Counter**: Sensor-based physical activity and daily step tracking.
- 🚀 **Speed Test**: Integrated network performance testing (Ping, Download, and Upload speeds).
- 🛡️ **Privacy & Permission Auditing**: Real-time camera/microphone access monitoring and app permission auditing.
- 📦 **App Usage Stats**: Installed package launcher stats and recent application activity logs.

### 🗑️ 3. Safe System Debloater
- **System & User Package Management**: Catalog and manage all installed applications.
- **Color-Coded Safety Classification**: Categorizes apps based on system impact (Safe to remove vs. System critical) to prevent accidental bricking.
- **Shizuku & ADB Integration**: Uninstall, disable, or freeze bloatware on unrooted devices via Shizuku API.

### 🛡️ 4. Privacy Guard
- **Sensor Access Indicators**: Live detection when camera or microphone sensors are accessed in the background.
- **Permission Auditing**: Identify applications holding sensitive permissions (Location, Contacts, Microphones, Camera).

### 📱 5. Material 3 Home Screen Widgets (14+ AppWidgets)
- Dedicated home screen widgets for instant glanceable metrics:
  - Battery & Screen Status
  - CPU & RAM Load
  - Screen Time & Device Sleep
  - Live Network Speed & Data Usage
  - Storage & Connection Metrics
  - Unlock Counter & Step Counter
  - Speed Test Quick Launcher
  - All-in-One Comprehensive Status Board

### 📊 6. Telemetry History & CSV Data Export
- Time-series data persistence via **Room Database**.
- Export telemetry history to **CSV** format for offline research and hardware debugging.

---

## 🛠️ Architecture & Core Components

```
                   ┌────────────────────────────────────────┐
                   │             MainActivity               │
                   └───────────────────┬────────────────────┘
                                       │
                         ┌─────────────┴─────────────┐
                         ▼                           ▼
            ┌────────────────────────┐  ┌────────────────────────┐
            │    Jetpack Compose     │  │   Material 3 AppWidgets│
            │  Dynamic Dashboard UI  │  │   (ModuleWidgetProvider)│
            └────────────┬───────────┘  └────────────┬───────────┘
                         │                           │
                         └─────────────┬─────────────┘
                                       ▼
                         ┌───────────────────────────┐
                         │      MonitorService       │
                         │    (Foreground Service)   │
                         └─────────────┬─────────────┘
                                       │
       ┌───────────────────────────────┼───────────────────────────────┐
       ▼                               ▼                               ▼
┌──────────────┐                ┌──────────────┐                ┌──────────────┐
│ BatteryModule│                │  CpuModule   │                │ NetworkModule│  ... (15+ Modules)
└──────┬───────┘                └──────┬───────┘                └──────┬───────┘
       │                               │                               │
       └───────────────────────────────┼───────────────────────────────┘
                                       ▼
                         ┌───────────────────────────┐
                         │   AppDatabase (Room)      │
                         │   DataStore Preferences   │
                         └───────────────────────────┘
```

- **`MonitorService`**: Central foreground service driving update loops for enabled modules with minimal overhead.
- **`Module` Contract**: Extensible interface defining key metadata, polling logic (`tick()`), notifications, and Compose UI bindings.
- **`ModuleRegistry`**: Registry mapping module keys, priorities, dashboard cards, and widget bindings.
- **`SystemAccess`**: Layer providing low-level hardware access, battery stats, shell execution, and Shizuku inter-process calls.

---

## 💻 Tech Stack & Open-Source Libraries

| Category | Technology / Library | Description |
| :--- | :--- | :--- |
| **Language** | [Kotlin 2.1](https://kotlinlang.org) | Modern asynchronous code with Coroutines & Flow |
| **UI Framework** | [Jetpack Compose BOM 2026](https://developer.android.com/compose) | Declarative UI framework |
| **Design System** | [Material Design 3](https://m3.material.io) | Dynamic Colors (Monet) & Material Design components |
| **Architecture** | MVVM + Foreground Service | Reactive Uni-directional Data Flow (UDF) |
| **Database** | [Room 2.8.4](https://developer.android.com/training/data-storage/room) | Local time-series database with KSP compiler |
| **Preferences** | [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore) | Asynchronous key-value settings storage |
| **Networking** | [Retrofit 3.0](https://square.github.io/retrofit/) & Gson | API updates and GitHub releases checker |
| **Elevated Access**| [Shizuku API 12.1](https://shizuku.rikka.app/) | Non-root system service access |
| **Graphics** | AndroidX Graphics Shapes | Morphing dynamic card shapes and custom visualizers |
| **Utilities** | Calvin Reorderable | Smooth drag-and-drop dashboard reordering |

---

## 📋 System Requirements & Permissions

- **Operating System**: Android 8.0 (API Level 26) or higher.
- **Target SDK**: Android 16 (API Level 36).
- **Elevated Permissions** (Optional for advanced features):
  - `POST_NOTIFICATIONS`: Display persistent live telemetry monitor banners.
  - `FOREGROUND_SERVICE`: Background telemetry updates.
  - `PACKAGE_USAGE_STATS`: App usage and screen time tracking.
  - `ACTIVITY_RECOGNITION`: Sensor-based step counting.
  - `QUERY_ALL_PACKAGES`: Package visibility for debloating system bloatware.
  - `moe.shizuku.manager.permission.API_V23`: Non-root privileged system management.

---

## 🚀 Building & Getting Started

### Prerequisites
- [Android Studio Ladybug (2024.2+)](https://developer.android.com/studio) or newer
- JDK 21
- Android SDK 36

### Building from Command Line
1. **Clone the repository:**
   ```bash
   git clone https://github.com/omersusin/ExtensionBox.git
   cd ExtensionBox
   ```

2. **Build Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```
   The generated APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

3. **Install on connected device via ADB:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Build Release APK:**
   ```bash
   ./gradlew assembleRelease
   ```

---

## 🔄 CI / CD Automation

This project uses **GitHub Actions** for continuous integration and automated deployment:
- **Build Verification (`build.yml`)**: Compiles and verifies debug builds on every commit and pull request.
- **Release Automation (`release-signed-apk.yml`)**: Decodes signing keys and builds a production-ready, signed APK whenever a new tag (e.g., `v2.1`) is pushed.

---

## 📄 License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details.
