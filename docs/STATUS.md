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
run on a phone. Each item is a few minutes on a device:

1. **Phone call mid-recording** (audio-focus stop): the entry up to the call is saved.
2. **Kill mid-transcription, relaunch** (recovery service): "Recovering an earlier
   entry…" shows, the entry appears, and `adb shell dumpsys activity services
   dev.zyriel.voicejournal` shows the service with type `mediaProcessing` (Android 15+)
   or `dataSync`, not `microphone`. Repeat with the microphone permission revoked.
3. **Rec tapped during recovery:** the service re-enters the foreground as `microphone`
   and the recording survives the screen turning off.
4. **Full disk during recording** (capture-error salvage): the partial entry is saved.
5. **Stop from the notification with the app backgrounded**, then let it transcribe.
6. **Quarantine:** make transcription fail three launches in a row (for example by
   pushing a corrupt `ggml-base.en-q5_1.bin` into `files/`), check the notice appears
   with Retry and Delete, restore the model, tap Retry.
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
   `SEMANTIC_MARGIN`) are calibrated on a twelve-entry fixture (`SearchCalibrationTest`),
   which is a small sample. Try them on a real journal; if real queries come back empty
   too often, lower the floor and re-run that test.

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
