# Building

## Toolchain

- Gradle wrapper: 8.13
- Android Gradle Plugin: 8.12.0
- Kotlin: 2.0.21
- Compose compiler plugin: 2.0.21
- Gradle runtime JDK: 17 or newer
- Java target bytecode: 11
- `compileSdk`: 36
- `targetSdk`: 36
- `minSdk`: 24

## Local setup

1. Install JDK 17 or newer.
2. Install Android SDK Platform 36 and matching build tools from Android Studio.
3. Ensure `local.properties` exists and points `sdk.dir` to your Android SDK location.

Example:

```properties
sdk.dir=/path/to/Android/Sdk
```

`local.properties` is ignored by Git and should stay local to each machine.
If you build inside WSL, do not point `sdk.dir` at a Windows SDK mounted under `/mnt/c`. Those packages contain Windows executables such as `aapt.exe`, while Gradle running on Linux expects Linux binaries. Install an Android SDK inside WSL and point `sdk.dir` to that Linux-native location instead.

## Common commands

Build a debug APK:

```bash
./gradlew assembleDebug
```

Install the debug build on a connected device:

```bash
./gradlew installDebug
```

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

List available Gradle tasks:

```bash
./gradlew tasks
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Build outputs

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Unit test reports: `app/build/reports/tests/`

## Notes

- The repository now includes `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`, so a separate Gradle installation is not required.
- Android SDK and Java are separate requirements: `sdk.dir` points to the Android SDK, while `java -version` verifies the JDK used by Gradle.
- This project was validated to the point of Gradle startup on WSL, and project configuration then failed under Java 11 because AGP 8.12 requires Java 17. Use JDK 17+ for actual builds.
- After upgrading to Java 17, Gradle configuration succeeded in WSL. The next blocker was Android build-tools: a Windows SDK mounted from `C:` is not usable for Linux-side builds because it ships `.exe` binaries instead of Linux tools.
