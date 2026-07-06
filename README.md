# Voice Journal

A fully offline voice journal for Android. Record your thoughts, get them transcribed on-device, and search them by meaning, not just keywords. Nothing ever leaves your phone — but nothing is trapped there either: export the whole journal (audio and transcripts) to a zip anywhere you choose, and import it back later, so your journal survives an uninstall or a new phone.

<p align="center">
  <img src="docs/screenshot.png" alt="Voice Journal home screen" width="300">
</p>

## Why offline matters

A journal is one of the most private things a person owns. This app has **no INTERNET permission**. Not "we don't upload your data," but "the OS will not let this app touch the network." Check for yourself:

- Source: [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) declares `RECORD_AUDIO` plus local-only plumbing (foreground service, wakelock, notifications) so recording survives the screen turning off. No network permission of any kind
- Built APK: `aapt dump permissions <apk>` shows the same

Transcription runs locally through [whisper.cpp](https://github.com/ggerganov/whisper.cpp) (base.en, q5_1). Semantic search runs locally through [bge-small-en-v1.5](https://huggingface.co/BAAI/bge-small-en-v1.5) (int8 ONNX). Your recordings, transcripts, and embeddings live in app-private storage; the export/import feature copies a zip to a location you pick, and only when you ask it to.

## Performance

Measured on a Pixel 10 Pro XL at commit `ce5f560`. Raw data: [`vj-benchmark-ce5f560.json`](vj-benchmark-ce5f560.json) and [`vj-accuracy-ce5f560.json`](vj-accuracy-ce5f560.json).

| Operation | Median | Notes |
|---|---|---|
| Transcribe 10s clip | 1.51 s | 0.151x realtime |
| Transcribe 30s clip | 1.68 s | 0.056x realtime |
| Transcribe 60s clip | 3.37 s | 0.056x realtime |
| Embed a typical entry (163 chars) | 8 ms | |
| Semantic search, 5,000 entries | 10 ms | brute-force cosine, top 20 |

Accuracy: **4.50% word error rate** on a LibriSpeech test-clean subset, with zero deletions on every clip — words don't get silently dropped. Per-clip numbers, including the raw hypotheses, are in the committed accuracy JSON.

Voice activity detection (silero v5.1.2) is enabled by default. On pause-heavy audio — the realistic journaling case — it cut a 30-second clip that is half silence from 10.9 s to 2.3 s, and it improved corpus WER from 6.31% to 4.50% by suppressing a hallucination on the 60s clip. Dense continuous speech pays a modest latency overhead instead; the transcription rows above were measured with VAD off.

A one-minute entry is searchable text in a few seconds. Search stays instant at journal scale, which is why there is no vector database in this app.

## Building

Requirements:

- Android Studio (latest stable)
- Via SDK Manager: Android SDK Platform 36, NDK **28.2.13676358** (exact, pinned for 16 KB page alignment), CMake 3.22.1

The model files are not tracked in git. Fetch them once before the first build:

```powershell
powershell scripts\fetch-models.ps1
```

On Linux/macOS, download the same three files listed in that script into `app/src/main/assets/models/` with `curl -L`.

Then open the project root in Android Studio and build, or:

```
gradlew.bat assembleDebug
```

More detail in [README.BUILD.md](README.BUILD.md).

## Stack

Kotlin, Jetpack Compose, Room. Native transcription via whisper.cpp over JNI (arm64). Embeddings via ONNX Runtime. 107 unit tests, including real ONNX inference against the bundled model on the JVM.

## License

GPLv3. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The app is free software. A paid, identical build will be available on Google Play for people who prefer to install it that way and support development.
