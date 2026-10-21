# Third-Party Notices

Voice Journal is GPLv3 (see `LICENSE`). It includes or downloads the components below, all under licenses compatible with GPLv3. The app ships these notices and the full license texts in its assets under `licenses/`, readable from the About screen.

## Compiled into the app

- **whisper.cpp** (ggml-org/whisper.cpp) - MIT License, Copyright (c) The ggml authors. Vendored at `app/src/main/cpp/whisper.cpp`; license text in that directory, shipped as `licenses/LICENSE-whisper.cpp.txt`.
- **ggml** (ggml-org/ggml, the tensor library inside whisper.cpp) - MIT License, same authors and same license text as whisper.cpp. Vendored at `app/src/main/cpp/whisper.cpp/ggml`.
- **ONNX Runtime** (Microsoft) - MIT License. Consumed as the prebuilt `com.microsoft.onnxruntime:onnxruntime-android` Maven artifact, which contains native binaries not built from this repository.
- **Jetpack Compose, Room, AndroidX, Kotlin standard library and kotlinx.coroutines** (Google, JetBrains) - Apache License 2.0. Consumed as Gradle dependencies.
- **Literata typeface** (TypeTogether, commissioned by Google; Copyright 2017 The Literata Project Authors) - SIL Open Font License 1.1. Font file at `app/src/main/res/font/literata.ttf`; license text in `OFL-Literata.txt`, shipped as `licenses/OFL-Literata.txt`.

## Models bundled into the APK (downloaded at build time, not tracked in git)

`scripts/fetch-models.sh` and `scripts/fetch-models.ps1` download these from pinned HuggingFace revisions and check them against `scripts/models.sha256`.

- **Whisper base.en** (OpenAI) - MIT License. Weights converted to ggml and quantized to q5_1 by the whisper.cpp project (`ggerganov/whisper.cpp` on HuggingFace, MIT).
- **Silero VAD v5.1.2** (snakers4/silero-vad, Silero Team) - MIT License. Converted to ggml by the whisper.cpp project (`ggml-org/whisper-vad` on HuggingFace).
- **bge-small-en-v1.5** (BAAI) - MIT License. The vocabulary comes from `BAAI/bge-small-en-v1.5`; the int8 ONNX model is the conversion published by Xenova (`Xenova/bge-small-en-v1.5`, `onnx/model_quantized.onnx`), distributed under the same MIT license.

## Debug builds only

- **LibriSpeech test-clean excerpts** - Creative Commons Attribution 4.0 (CC BY 4.0), derived from public-domain LibriVox recordings. The clips in `app/src/debug/assets/accuracy/` are concatenated utterances listed by ID in `manifest.json`, converted to 16 kHz mono WAV; nothing else was changed. Source: V. Panayotov, G. Chen, D. Povey, S. Khudanpur, "LibriSpeech: an ASR corpus based on public domain audio books", ICASSP 2015, https://www.openslr.org/12. They are used by the accuracy benchmark and never ship in a release APK.

## Build and test only (not distributed)

- **JUnit 4** - Eclipse Public License 1.0. **org.json** - Public Domain. **ONNX Runtime JVM artifact** - MIT License. Used by the JVM unit tests.
