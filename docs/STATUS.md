# Project status

Updated for **v1.0.0** (July 2026).

## Where things stand

- **1.0.0 shipped.** First tagged release; signed APK attached to the GitHub Release.
- **Accuracy gate closed.** Model and VAD decisions were made from committed on-device
  numbers, not vibes: [`vj-accuracy-ce5f560.json`](../vj-accuracy-ce5f560.json) and
  [`vj-benchmark-ce5f560.json`](../vj-benchmark-ce5f560.json), measured on a Pixel 10 Pro XL.
  - Corpus WER on a LibriSpeech test-clean subset: **6.31% VAD off / 4.50% VAD on**,
    zero deletions in every row. `base.en-q5_1` stays; `small.en`'s ~3% doesn't justify 3x the size.
  - VAD (silero v5.1.2) is **on by default** (`c286f5c`): 10.9 s → 2.3 s on a pause-heavy
    30s clip, and it suppressed a hallucination on the 60s clip. Dense speech pays ~1.4x.
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
