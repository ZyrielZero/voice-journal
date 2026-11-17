# Project status

Updated for **v1.1.0** (prepared, not yet tagged). The benchmark numbers below were
measured on the 0.4.1 build `ce5f560` and have not been re-run since; they predate the
dotprod native build, non-speech-token suppression and the VOICE_RECOGNITION mic source.

## Where things stand

- **1.0.0 shipped.** First tagged release; signed APK attached to the GitHub Release.
- **1.1.0 is code-complete but not device-verified.** See "Before tagging 1.1.0" below.
- **Model and VAD decisions** were made from committed on-device numbers:
  [`vj-accuracy-ce5f560.json`](benchmarks/vj-accuracy-ce5f560.json) and
  [`vj-benchmark-ce5f560.json`](benchmarks/vj-benchmark-ce5f560.json), measured on a Pixel 10 Pro XL.
  - Corpus WER on a LibriSpeech test-clean subset: **6.31% VAD off / 4.50% VAD on**,
    zero deletions in every row. The subset is 3 clips and 222 reference words, so the
    95% interval on 4.50% is roughly 2.5% to 8.1%. `base.en-q5_1` stays for size; no
    `small.en` run is committed, so there is no measured accuracy case either way yet.
    `scripts/build-accuracy-set.py` grows the set; the next step is a device run on it.
  - VAD (silero v5.1.2) is **on by default** (`c286f5c`). On real speech it removed a
    hallucination on the 60s clip and costs about 1.5x to 1.7x latency on continuous speech
    (60 s clip: 6.2 s off, 9.3 s on). The "10.9 s to 2.3 s" pause-heavy figure was a
    synthetic tone; the real-speech version (`acc.speech_gaps`) still needs a device run.
  - The committed JSONs are VAD-off for the `whisper.clip*` tone rows (the default was
    off at `ce5f560`). The bench now passes `useVad` explicitly, warms up once per VAD
    state, and times three runs per accuracy row.
  - `ce5f560` added a loud guard so an accuracy run can never silently fall back to
    no-VAD when the silero model asset is missing.
- **Toolchain:** AGP 9.2.1 / Gradle 9.6 / Kotlin 2.2.10 migration is merged (`78d8ebf`).

## Before tagging 1.1.0

Everything in this list is covered by JVM unit tests where it can be, and none of it has
run on a phone. Each item is a few minutes on a device.
[`scripts/device-verify.sh`](../scripts/device-verify.sh) walks the list over adb with the
debug build: it plants test recordings, kills the app at the right moments, reads the
service type, the log and the database itself, and asks for the parts only a person can
do. `bash scripts/device-verify.sh` runs everything; `bash scripts/device-verify.sh 2 6 9`
runs the three steps that need nobody to speak.

First device run, 2026-11-16, Pixel 11 Pro XL on Android 17 (API 37), debug build:

- **Item 2 passes, after a fix it found.** `ServiceCompat.startForeground` in
  androidx.core 1.16.0 masks the type to the ones Android 14 knew, so `mediaProcessing`
  became "none" and the system refused the start ("Starting FGS with type none ... has
  been prohibited"). Recovery still ran, but without its service. `RecordingService` now
  calls the platform `startForeground`; recovery shows as `mediaProcessing`, with and
  without the microphone permission, and survives a kill mid-transcription.
- **Item 6, first half:** three killed recoveries moved the recording to `quarantine/`.
  The notice and Retry have not been looked at by a person yet.
- **Item 9 passes on the automated part:** no crash at cold start, every entry got an
  embedding on the device. ONNX Runtime logs one warning ("Android telemetry is
  unavailable because the 1DS Java HttpClient was not initialized") and carries on.
- **Item 8 found a threading problem, now fixed.** The accuracy suite ran for over 20
  minutes without finishing. A profile showed the time in `ggml_barrier`: threads
  spinning while they wait for each other. Two causes: whisper.cpp runs the VAD on a
  hard-coded 4 threads (a 60 s clip took 80 to 130 s in the VAD alone), and the app asked
  whisper for 6 threads, where fewer were faster (see `Transcriber.WHISPER_THREADS` for
  the numbers). The VAD now runs on one thread (a local patch, see `VENDORED.md`) and so
  does whisper. With that the suite finishes in about 2.5 minutes, every row's three runs
  agree to within a few percent, and a 60 s clip takes 6.7 s with VAD on. WER on the
  222-word set: 3.60% VAD on, 5.86% VAD off. These came from an uncommitted tree, so no
  JSON is committed yet; rerun the suite and the benchmark from the commit and refresh
  the README from those.
- **Also found and fixed: speech after a pause was dropped.** The `acc.speech_gaps` rows
  (two clips, each followed by room-tone silence of its own length) lost 64 of 107 words
  with VAD on and 26 with VAD off, where each clip alone loses none. Cause: the bridge
  set `no_timestamps`, so whisper advanced a full 30 s window whenever the decoder
  stopped early at a pause. With timestamps on, both rows lose no words (VAD on: 3.7%
  WER; VAD off: 21 hallucinated words in the trailing silence, which is what VAD is on
  by default to prevent). Speed is unchanged; set-wide WER is 4.05% VAD on, 5.41% off.
  Still worth one real recording with a ten-second pause in the middle.
- Items 1, 3, 4, 5, 7, 10 and the by-hand confirmations in 2, 6 and 9 are still open.

1. **Phone call mid-recording** (audio-focus stop): the entry up to the call is saved.
2. **Kill mid-transcription, relaunch** (recovery service): "Recovering an earlier
   entry…" shows, the entry appears, and `adb shell dumpsys activity services
   dev.zyriel.voicejournal` shows the service with type `mediaProcessing` (Android 15+)
   or `dataSync`, not `microphone`. Repeat with the microphone permission revoked.
3. **Rec tapped during recovery:** the service re-enters the foreground as `microphone`
   and the recording survives the screen turning off.
4. **Full disk during recording** (capture-error salvage): the partial entry is saved.
5. **Stop from the notification with the app backgrounded**, then let it transcribe.
6. **Quarantine:** kill the app mid-recovery on three launches in a row, check the
   notice appears with Retry and Delete on the fourth, tap Retry. A corrupt model does
   not get there: a wrong-length model file is replaced from the APK, and one that fails
   to load aborts the sweep without counting against the recording.
7. **Encrypted export and import** round trip through a real document provider (Files,
   Drive), including a wrong password and a rotation while the file picker is open.
8. **Accuracy suite** with non-speech-token suppression on, and the **benchmark** on
   the dotprod build (`whisper_lib` in the JSON says which library loaded); refresh the
   README table from both.
9. **ONNX Runtime without its telemetry hook:** 1.31.0's manifest asks for INTERNET and
   registers `ai.onnxruntime.TelemetryInitializer`; both are stripped from the merged
   manifest. Confirm on a device that embedding and search still work (they do on the
   JVM) and that logcat shows no crash or retry loop from the library at startup.
10. **Search floor:** the similarity floor and margin (`HybridSearch.SEMANTIC_FLOOR`,
   `SEMANTIC_MARGIN`, `SEMANTIC_STRONG`) are calibrated on two small fixtures
   (`SearchCalibrationTest`, twelve entries; `ConceptSearchTest`, 36), which is a small
   sample. Try them on a real journal; if real queries come back empty too often, lower
   the floor and re-run those tests.
11. **gte-base embedding model (replaces bge-small).** Run on the Pixel 11 Pro XL,
   2026-11-17, debug build from an uncommitted tree: the 110 MB model loads through a
   memory mapping of the APK asset (`ModelAssets.map`) in about 0.4 s with the Java heap
   at 15 MB (305 MB total PSS, 153 MB of it native); the existing 54-entry journal was
   re-embedded by the backfill in 2.2 s, about 40 ms an entry at an average of 560
   characters, and every row's `embeddingModel` is now `gte-base-q8+chunk1`. That run
   also found that the backfill had never worked on a device: its vector write bound a
   FloatArray in a Room `@Query`, which Room expands into one placeholder per float, so
   every write failed with a syntax error (fixed; see `JournalDao`). Still open: the
   benchmark's embed and search rows in the README predate this model, and nobody has
   yet typed real queries into the search box on a real journal (item 10).

## Closed incident: "Acc suite hangs on device"

**Resolved — it was never a code bug.** The suite appearing to freeze mid-run was
lmkd killing the foreground process under memory pressure (device at 14.5/15 GB used
with 5 GB zram swap; kills show as `am_proc_died` with procState 2, no Java crash, no
tombstone — from the UI it looks exactly like a frozen progress dialog). With ~3 GB
`MemAvailable` the full accuracy suite completes in about 13 seconds on the same
hardware.

Protocol for any future on-device suite run:

1. `adb shell cat /proc/meminfo` first — want `MemAvailable` ≥ ~3 GB (reboot or
   force-stop resident apps if not).
2. Keep the app foregrounded, screen on, for the whole run.
3. If the app dies silently mid-suite, check `logcat -d -b events | grep am_proc_died`
   before suspecting the code.

## Known local caveat

On Windows dev machines, 4 JVM unit tests that exercise ONNX native inference fail
locally; that is the expected local baseline. CI (Linux) is authoritative and runs
the full suite green.
