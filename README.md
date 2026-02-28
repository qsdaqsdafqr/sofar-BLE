# Sofar BLE Console

Android app for scanning BLE devices, connecting through `BluetoothGatt`, and driving a Sofar-oriented BLE session flow from a Jetpack Compose UI.

## What is included

- BLE scan and device selection
- Connection lifecycle management
- Protocol/session helpers for Sofar and Modbus-style messages
- Compose-based control surface for foreground testing

## Project layout

- `app/`: Android application module
- `gradle/`: Gradle wrapper metadata
- `gradlew` and `gradlew.bat`: cross-platform wrapper entrypoints

## Requirements

- Android Studio recent stable release, or command-line Android SDK tooling
- Android SDK platform 36
- JDK 17 or newer to run the Gradle build

The app code is configured to target Java 11 bytecode, but Android Gradle Plugin `8.12.0` requires a JDK 17+ runtime for the build itself.

`local.properties` is intentionally not committed. Android Studio usually recreates it automatically. If you build from the command line, make sure it points to your local Android SDK path.

## Quick start

```bash
./gradlew assembleDebug
```

The generated debug APK will be written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## More build details

Detailed setup, validation commands, and build outputs are documented in [BUILDING.md](BUILDING.md).
