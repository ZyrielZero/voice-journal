# Project status

Updated for **v1.0.0** (July 2026). The benchmark numbers below were measured on the
0.4.1 build `ce5f560` and have not been re-run on 1.0.0.

## Where things stand

- **1.0.0 shipped.** First tagged release; signed APK attached to the GitHub Release.
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
