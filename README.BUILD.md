# Voice Journal — Build Setup

Complete source. Everything needed to build is in this zip, including
whisper.cpp and both model files. Unzip and open in Android Studio.

## Requirements

- Android Studio (latest stable). Not Visual Studio, not VS Code.
- Through Android Studio's SDK Manager (Settings > Languages & Frameworks >
  Android SDK > SDK Tools tab), install:
  - Android SDK Platform 36
  - NDK version 28.2.13676358  (exact version; it is pinned in app/build.gradle.kts)
  - CMake 3.22.1
- JDK 17+ (Android Studio bundles one; no separate install needed)

## Build

1. Open the project root (this folder) in Android Studio.
2. Let Gradle sync. First sync downloads dependencies; takes a few minutes.
3. Build > Build APK, or run directly on a connected device.

Command line alternative (from project root, Windows):
    gradlew.bat assembleDebug
Output lands in app/build/outputs/apk/debug/.

## Layout notes

- app/src/main/cpp/whisper.cpp/   vendored whisper.cpp source (ggml-org/whisper.cpp)
- app/src/main/assets/models/     whisper base.en q5_1 (60 MB) + bge-small int8 (34 MB) + vocab
- Native code is forced to -O3 even in debug builds (see cpp/CMakeLists.txt).
  Do not remove that; debug-variant -O0 makes whisper 30-60x slower.

## Git

Run in the project root:
    git init
    git add .
    git commit -m "Working semantic search build (0.4.0)"

The included .gitignore excludes build output. Model files ARE currently
tracked for simplicity; if the repo goes public later, move them to Git LFS
or a setup script before pushing.

## Known state (honesty section)

- 36 unit tests pass (gradlew.bat testDebugUnitTest), including real ONNX
  inference against the bundled bge model.
- Transcription and semantic search verified working on one physical device.
- No committed benchmarks yet. That is the next task.
