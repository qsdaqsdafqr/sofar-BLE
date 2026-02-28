# Sofar MiniBox3-35 BLE Controller

[中文 README](README.md)

This is an Android controller app for the Sofar `MiniBox3-35` home booster pump.
It connects to the device over BLE and is intended to replace the "苏法尔智控" mini program with a simpler native client that is easier to maintain and troubleshoot.

## Target Device

- Brand: Sofar
- Model: `MiniBox3-35`
- Use case: home booster pump control and diagnostics

## Purpose

- Provide a dedicated controller for the `MiniBox3-35`
- Replace the existing mini program with a native Android app
- Keep the control path local and easier to debug
- Serve as a base for protocol analysis and further maintenance

## Current Features

- BLE scanning and device selection
- BLE connection and disconnect management
- Protocol encoding and session handling for Sofar / Modbus-style messages
- A Jetpack Compose control screen

## Build Environment

- Stable Android Studio, or command-line Android SDK tooling
- Android SDK Platform 36
- JDK 17 or newer

The app code still targets Java 11 bytecode, but Android Gradle Plugin `8.12.0` requires JDK 17+ for the build runtime.

`local.properties` is not committed. If you build from the command line, make sure `sdk.dir` points to your local Android SDK.

Windows is currently the preferred build environment with the existing SDK setup. Use `gradlew.bat` first.

## Quick Start

On Windows:

```bat
gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For more build details, see [BUILDING.md](BUILDING.md).

## Project Layout

- `app/`: Android application module
- `gradle/`: Gradle Wrapper configuration
- `gradlew` / `gradlew.bat`: Gradle Wrapper launch scripts

## License

This project is released under the [MIT License](LICENSE).

## Notes

- This is a personal project and is not an official Sofar release.
- The current priority is practical control and debugging; UI, protocol details, and feature coverage can continue to evolve.
