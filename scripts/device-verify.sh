#!/usr/bin/env bash
# Walks the "Before tagging" device checks in docs/STATUS.md on a phone
# attached over adb. It automates what adb can see (service types, logcat,
# files, the database) and asks you for what only a person can do (speak,
# place a call, tap Retry).
#
#   bash scripts/device-verify.sh            # every step, in order
#   bash scripts/device-verify.sh 2 6 9      # only these steps
#   ADB=/path/to/adb.exe bash scripts/...    # a different adb (WSL: adb.exe)
#   ANDROID_SERIAL=... bash scripts/...      # pick one of several devices
#
# It needs the DEBUG build (run-as), which it installs from
# app/build/outputs/apk/debug/app-debug.apk. It never uninstalls the app and
# never clears its data. It does add test recordings (vjverify_*.wav) that
# become journal entries; delete those entries afterwards.
#
# Steps:
#   0 preflight        install, memory, no network permission
#   1 call             phone call mid-recording
#   2 recovery         kill mid-transcription, service type, mic revoked
#   3 rec-in-recovery  Rec tapped while recovering, screen off
#   4 full-disk        capture-error salvage (fills storage; asks first)
#   5 notif-stop       Stop from the notification, app backgrounded
#   6 quarantine       three killed recoveries, notice, Retry
#   7 transfer         encrypted export and import
#   8 bench            accuracy and benchmark suites, pull the JSON
#   9 onnx             embedding works with the telemetry hook stripped
#  10 search           similarity floor on a real journal

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# adb from the environment, else PATH, else the SDK's copy. Under WSL the
# Windows adb.exe comes first: only it can see USB devices, and a Linux adb
# server, once started, takes port 5037 and hides the phone from adb.exe too.
if [ -z "${ADB:-}" ]; then
    ADB=""
    grep -qi microsoft /proc/version 2>/dev/null && ADB="$(command -v adb.exe || true)"
    [ -n "$ADB" ] || ADB="$(command -v adb || true)"
    for c in "${ANDROID_HOME:-}/platform-tools/adb" "$HOME/Android/Sdk/platform-tools/adb"; do
        [ -z "$ADB" ] && [ -x "$c" ] && ADB="$c"
    done
    [ -n "$ADB" ] || { echo "adb not found; set ADB=/path/to/adb"; exit 1; }
fi
PKG="dev.zyriel.voicejournal"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
CLIP="$ROOT/app/src/debug/assets/accuracy/clip60s.wav"
REC_DIR="files/recordings"
TAGS="RecordingController RecordingService Transcriber WhisperBridge JournalStore"
STAMP="$(date +%Y%m%d-%H%M%S)"
# Test clips carry the run time in their name: a clip named like one an
# earlier run turned into an entry would be skipped as already journaled.
RUN="${STAMP#*-}"
LOG="$ROOT/build/device-verify-$STAMP.log"
TMP="$(mktemp -d)"
RESULTS=()
FILLED=0

mkdir -p "$ROOT/build"
exec > >(tee -a "$LOG") 2>&1

cleanup() {
    [ "$FILLED" = 1 ] && adb_sh "run-as $PKG rm -f cache/vjverify_fill" >/dev/null 2>&1
    rm -rf "$TMP"
}
trap cleanup EXIT

# ---------- helpers ----------

adb_sh() { "$ADB" shell "$@" </dev/null | tr -d '\r'; }
say()    { printf '\n\033[1m%s\033[0m\n' "$*"; }
note()   { printf '   %s\n' "$*"; }
record() { RESULTS+=("$1|$2|$3"); printf '   => %s: %s\n' "$2" "$3"; }
pass()   { record "$1" PASS "$2"; }
fail()   { record "$1" FAIL "$2"; }
skip()   { record "$1" SKIP "$2"; }

pause() { printf '   >> %s  [Enter] ' "$*"; read -r _ 2>/dev/null </dev/tty || echo; }

# confirm <step> <question>: a human verdict, recorded as PASS or FAIL.
confirm() {
    local a=s
    printf '   ?? %s [y/n/s=skip] ' "$2"; read -r a 2>/dev/null </dev/tty || { a=s; echo; }
    case "$a" in
        y|Y) pass "$1" "$2 (confirmed by hand)" ;;
        s|S) skip "$1" "$2" ;;
        *)   fail "$1" "$2" ;;
    esac
}

yesno() { local a=n; printf '   ?? %s [y/N] ' "$1"; read -r a 2>/dev/null </dev/tty || { a=n; echo; }; [ "$a" = y ] || [ "$a" = Y ]; }

launch()    { adb_sh am start -n "$PKG/.MainActivity" >/dev/null; }
kill_app()  { adb_sh "run-as $PKG sh -c 'kill -9 \$(pidof $PKG)'" >/dev/null 2>&1 || adb_sh am force-stop "$PKG"; }
stop_app()  { adb_sh am force-stop "$PKG"; }
clear_log() { "$ADB" logcat -c </dev/null; }

app_log() {
    local spec="" t
    for t in $TAGS; do spec="$spec $t:V"; done
    # shellcheck disable=SC2086
    "$ADB" logcat -d -s $spec AndroidRuntime:E DEBUG:F libc:F </dev/null | tr -d '\r'
}

# wait_log <seconds> <extended regex>: true once the app log matches.
wait_log() {
    local deadline=$((SECONDS + $1))
    while [ $SECONDS -lt $deadline ]; do
        app_log | grep -Eq "$2" && return 0
        sleep 1
    done
    return 1
}

# Foreground service type of RecordingService right now, as lowercase hex
# without leading zeros ("" when it is not in the foreground).
fgs_type() {
    adb_sh dumpsys activity services "$PKG" \
        | grep -E 'isForeground=true' \
        | sed -nE 's/.*(types|foregroundServiceType)=(0x)?0*([0-9A-Fa-f]+).*/\3/p' \
        | head -1 | tr 'A-F' 'a-f'
}

type_name() {
    case "$1" in
        80) echo microphone ;; 2000) echo mediaProcessing ;; 1) echo dataSync ;;
        "") echo none ;; *) echo "0x$1" ;;
    esac
}

# watch_types <seconds>: every distinct foreground type seen, space separated.
watch_types() {
    local deadline=$((SECONDS + $1)) seen="" t
    while [ $SECONDS -lt $deadline ]; do
        t="$(fgs_type)"
        if [ -n "$t" ]; then case " $seen " in *" $t "*) ;; *) seen="$seen $t" ;; esac; fi
        sleep 0.4
    done
    echo "$seen"
}

# Drops a copy of the 60 s LibriSpeech clip into the recordings directory
# with no database row: exactly what a process death mid-transcription
# leaves behind, without anyone having to speak for a minute.
push_orphan() {
    adb_sh "run-as $PKG mkdir -p $REC_DIR" >/dev/null
    "$ADB" exec-in run-as "$PKG" sh -c "cat > $REC_DIR/$1" < "$CLIP"
    local size; size="$(adb_sh "run-as $PKG stat -c %s $REC_DIR/$1")"
    [ "$size" = "$(stat -c %s "$CLIP")" ] || { note "push of $1 failed (size $size)"; return 1; }
}

# wait_attempt <name> <count> [seconds]: true once the sweep has started its
# <count>th attempt on that orphan. The attempt is written to the .failures
# sidecar just before transcription begins, so this is the moment recovery
# is really running. Recovery does not start until the app lock is passed,
# which is why nothing here is timed from the launch.
wait_attempt() {
    local deadline=$((SECONDS + ${3:-120})) told=0
    while [ $SECONDS -lt $deadline ]; do
        [ "$(adb_sh "run-as $PKG cat $REC_DIR/$1.failures" 2>/dev/null)" = "$2" ] && return 0
        if [ $told = 0 ] && [ $SECONDS -gt $((deadline - ${3:-120} + 5)) ]; then
            note "waiting for recovery to start; unlock the app if it is asking"; told=1
        fi
        sleep 0.5
    done
    return 1
}

orphan_state() { adb_sh "run-as $PKG ls -R $REC_DIR" 2>/dev/null | grep -E 'vjverify|quarantine|\.failures' | tr '\n' ' '; }

# Copies the database out and runs one SQL query against it on this machine.
db_query() {
    local f
    for f in journal.db journal.db-wal journal.db-shm; do
        "$ADB" exec-out run-as "$PKG" cat "databases/$f" > "$TMP/$f" 2>/dev/null </dev/null
    done
    python3 - "$TMP/journal.db" "$1" <<'PY'
import sqlite3, sys
con = sqlite3.connect(sys.argv[1])
print(con.execute(sys.argv[2]).fetchone()[0])
PY
}

expected_recovery_type() { [ "$API" -ge 35 ] && echo 2000 || echo 1; }

# ---------- steps ----------

step0() {
    say "0. Preflight"
    local n; n="$("$ADB" devices </dev/null | tr -d '\r' | grep -c -E '\sdevice$')"
    if [ "$n" -lt 1 ]; then
        note "No device. Plug the phone in, enable USB debugging, accept the prompt."
        note "Under WSL, use the Windows adb.exe, and if a Linux adb server is running"
        note "(pgrep -x adb) stop it: it holds port 5037 and hides the phone."
        exit 1
    fi
    API="$(adb_sh getprop ro.build.version.sdk)"
    note "device: $(adb_sh getprop ro.product.model), Android API $API"
    note "source: $(git -C "$ROOT" rev-parse --short HEAD)$(git -C "$ROOT" diff --quiet || echo ' (dirty)')"

    [ -f "$APK" ] || { note "Missing $APK; run ./gradlew assembleDebug first."; exit 1; }
    [ -f "$CLIP" ] || { note "Missing $CLIP."; exit 1; }

    if adb_sh pm path "$PKG" | grep -q package: && ! adb_sh "run-as $PKG true" >/dev/null 2>&1; then
        note "A non-debug build of $PKG is installed. The debug APK has a different"
        note "signature and cannot replace it, and uninstalling would delete the journal"
        note "on this phone. Export the journal from the app first, then uninstall it"
        note "yourself and run this again."
        exit 1
    fi
    # adb.exe is a Windows program and needs a Windows path to the APK.
    local apk="$APK"
    case "$ADB" in *.exe) apk="$(wslpath -w "$APK")" ;; esac
    if ! "$ADB" install -r "$apk" </dev/null | tr -d '\r' | grep -q Success; then
        note "install failed"; exit 1
    fi
    note "installed $(adb_sh dumpsys package "$PKG" | grep -m1 versionName | tr -d ' ')"

    if adb_sh dumpsys package "$PKG" | grep -Eq 'android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)'; then
        fail 0 "installed app requests a network permission"
    else
        pass 0 "installed app requests no network permission"
    fi

    local avail; avail="$(adb_sh cat /proc/meminfo | awk '/MemAvailable/ {print int($2/1024)}')"
    note "MemAvailable: ${avail} MB (the suites in step 8 want about 3000; lmkd kills look like hangs)"
    adb_sh pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1
    [ "$API" -ge 33 ] && adb_sh pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
    note "The app lock prompts on every cold start, and recovery waits behind it:"
    note "unlock each time. Settings > App lock turns it off for a quicker run."
}

step1() {
    say "1. Phone call mid-recording (audio focus)"
    clear_log; launch
    pause "Tap Rec and speak a sentence. Then call this phone from another one and answer"
    if wait_log 30 'audio focus lost'; then
        pass 1 "recording stopped on audio focus loss ($(app_log | grep -o 'audio focus lost ([^)]*)' | tail -1))"
    else
        fail 1 "no 'audio focus lost' in the log within 30 s"
    fi
    pause "Hang up and return to the app"
    confirm 1 "The entry spoken before the call is in the list"
}

# One recovery run: push an orphan, launch, report the service type seen.
recovery_run() {
    local label="$1" want; want="$(expected_recovery_type)"
    stop_app; push_orphan "vjverify_${RUN}_$label.wav" || { fail 2 "$label: could not push the test clip"; return; }
    clear_log; launch
    wait_attempt "vjverify_${RUN}_$label.wav" 1 || { fail 2 "$label: recovery never started"; return; }
    local seen; seen="$(watch_types 6)"
    local names="" t; for t in $seen; do names="$names $(type_name "$t")"; done
    if [ "$seen" = " $want" ]; then
        pass 2 "$label: recovery ran as$names"
    elif [ -z "$seen" ]; then
        fail 2 "$label: no foreground service seen during recovery (see the log for 'startForeground refused')"
    else
        fail 2 "$label: recovery ran as$names, expected $(type_name "$want") only"
    fi
    if wait_log 90 'sweep: [1-9][0-9]* recovered'; then
        pass 2 "$label: $(app_log | grep -o 'sweep: .*' | tail -1)"
    else
        fail 2 "$label: no successful sweep within 90 s: $(app_log | grep -o 'sweep: .*' | tail -1)"
    fi
    app_log | grep -E 'startForeground refused|FATAL|time limit' | sed 's/^/   log: /'
}

step2() {
    say "2. Kill mid-transcription, relaunch (recovery service)"
    note "Part A: a real kill. An orphan is planted, the app is killed 2 s into"
    note "transcribing it, and the relaunch must finish the job."
    stop_app; push_orphan vjverify_${RUN}_kill.wav || { fail 2 "could not push the test clip"; return; }
    clear_log; launch
    wait_attempt vjverify_${RUN}_kill.wav 1 || { fail 2 "recovery never started"; return; }
    sleep 2; kill_app
    note "after the kill: $(orphan_state)"
    clear_log; launch
    wait_attempt vjverify_${RUN}_kill.wav 2 || { fail 2 "after kill: recovery never restarted"; return; }
    local seen; seen="$(watch_types 6)"
    if [ "$seen" = " $(expected_recovery_type)" ]; then pass 2 "after kill: recovery ran as $(type_name "${seen# }")"
    else fail 2 "after kill: service types seen: '${seen}' expected $(type_name "$(expected_recovery_type)")"; fi
    if wait_log 90 'sweep: [1-9][0-9]* recovered'; then pass 2 "after kill: $(app_log | grep -o 'sweep: .*' | tail -1)"
    else fail 2 "after kill: no successful sweep within 90 s"; fi
    confirm 2 "'Recovering an earlier entry…' showed and a LibriSpeech entry appeared"

    note "Part B: the same with the microphone permission revoked."
    adb_sh pm revoke "$PKG" android.permission.RECORD_AUDIO >/dev/null
    recovery_run nomic
    adb_sh pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null
}

step3() {
    say "3. Rec tapped during recovery"
    stop_app
    push_orphan vjverify_${RUN}_r1.wav && push_orphan vjverify_${RUN}_r2.wav && push_orphan vjverify_${RUN}_r3.wav \
        || { fail 3 "could not push the test clips"; return; }
    note "Three orphans are planted, about 30 s of recovery. As soon as the app shows"
    note "'Recovering an earlier entry…', tap Rec and keep talking; this script then"
    note "turns the screen off for 15 s."
    pause "Ready"
    clear_log; launch
    wait_attempt vjverify_${RUN}_r1.wav 1 || { fail 3 "recovery never started"; return; }
    local seen; seen="$(watch_types 15)"
    case " $seen " in
        *" 80 "*) pass 3 "service re-entered the foreground as microphone (seen:$(for t in $seen; do printf ' %s' "$(type_name "$t")"; done))" ;;
        *)        fail 3 "microphone type never seen (seen: '${seen}'); was Rec tapped in time?" ;;
    esac
    adb_sh input keyevent KEYCODE_SLEEP; sleep 15
    if [ "$(fgs_type)" = 80 ]; then pass 3 "still a microphone foreground service after 15 s with the screen off"
    else fail 3 "service type after screen off: $(type_name "$(fgs_type)")"; fi
    adb_sh input keyevent KEYCODE_WAKEUP
    pause "Unlock, tap Stop, wait for the transcript"
    confirm 3 "The entry contains what you said while the screen was off"
}

step4() {
    say "4. Full disk during recording (capture-error salvage)"
    note "This fills the phone's free storage with one file in the app's cache, so the"
    note "recording runs out of space, then deletes the file. Other apps may complain"
    note "about low storage for that minute."
    yesno "Fill the storage now?" || { skip 4 "not run (declined)"; return; }
    clear_log; launch
    pause "Tap Rec and start talking; keep talking until the recording stops by itself"
    FILLED=1
    # Leaves about 3 MB (roughly 90 s of audio) so the failure comes mid-recording.
    adb_sh "run-as $PKG sh -c 'dd if=/dev/zero of=cache/vjverify_fill bs=1048576 2>/dev/null; \
        s=\$(stat -c %s cache/vjverify_fill); truncate -s \$((s - 3145728)) cache/vjverify_fill'" >/dev/null
    note "storage filled; waiting up to 3 minutes for the capture error"
    if wait_log 180 'capture failed mid-recording'; then pass 4 "capture error caught: $(app_log | grep -o 'capture failed.*' | tail -1)"
    else fail 4 "no 'capture failed mid-recording' in the log"; fi
    adb_sh "run-as $PKG rm -f cache/vjverify_fill"; FILLED=0
    note "fill file removed"
    confirm 4 "The partial entry (what you said before the disk filled) was saved"
}

step5() {
    say "5. Stop from the notification with the app backgrounded"
    clear_log; launch
    pause "Tap Rec and speak for ten seconds or so"
    adb_sh input keyevent KEYCODE_HOME
    pause "The app is now in the background. Pull down the shade and tap Stop on the notification"
    local deadline=$((SECONDS + 120))
    while [ $SECONDS -lt $deadline ] && [ -n "$(fgs_type)" ]; do sleep 1; done
    if [ -z "$(fgs_type)" ]; then pass 5 "service left the foreground after the notification Stop"
    else fail 5 "service still in the foreground after 120 s ($(type_name "$(fgs_type)"))"; fi
    launch
    confirm 5 "The entry was transcribed while the app was in the background"
}

step6() {
    say "6. Quarantine"
    note "A broken model does not quarantine anything (by design: it is not the"
    note "recording's fault). What does is a recording whose transcription never"
    note "comes back, three launches running. So: one orphan, killed three times."
    stop_app; push_orphan vjverify_${RUN}_quar.wav || { fail 6 "could not push the test clip"; return; }
    local i
    for i in 1 2 3; do
        clear_log; launch
        wait_attempt vjverify_${RUN}_quar.wav "$i" || { fail 6 "attempt $i never started: $(orphan_state)"; return; }
        sleep 2; kill_app
        note "kill $i: $(orphan_state)"
        sleep 1
    done
    clear_log; launch
    local deadline=$((SECONDS + 120))
    note "waiting for the fourth launch to set it aside; unlock the app if it is asking"
    while [ $SECONDS -lt $deadline ] && ! adb_sh "run-as $PKG ls $REC_DIR/quarantine" 2>/dev/null | grep -q vjverify_${RUN}_quar.wav; do sleep 1; done
    if adb_sh "run-as $PKG ls $REC_DIR/quarantine" 2>/dev/null | grep -q vjverify_${RUN}_quar.wav; then
        pass 6 "recording moved to quarantine/ after three failed attempts"
    else
        fail 6 "recording not in quarantine/: $(orphan_state)"
    fi
    confirm 6 "The red notice '1 recording could not be transcribed' shows with Delete and Retry"
    clear_log
    pause "Tap Retry and leave the app open"
    if wait_log 120 'sweep: [1-9][0-9]* recovered'; then pass 6 "Retry transcribed it: $(app_log | grep -o 'sweep: .*' | tail -1)"
    else fail 6 "Retry did not recover it within 120 s: $(app_log | grep -o 'sweep: .*' | tail -1)"; fi
    confirm 6 "The notice is gone and the entry is in the list"
    note "Delete is the same notice's other button; to try it, rerun step 6 and tap Delete instead."
}

step7() {
    say "7. Encrypted export and import"
    launch
    note "All by hand. Use a real provider: Files, then Drive."
    confirm 7 "Settings > Export journal with a password writes a .vjbackup file"
    confirm 7 "Rotating the phone while the file picker is open still exports (or refuses cleanly)"
    confirm 7 "Import with the WRONG password is refused with a clear message and changes nothing"
    confirm 7 "Import with the right password restores entries and audio (delete one first, check it returns and plays)"
    confirm 7 "A plain .zip export imports too"
}

step8() {
    say "8. Accuracy suite and benchmark"
    local avail; avail="$(adb_sh cat /proc/meminfo | awk '/MemAvailable/ {print int($2/1024)}')"
    note "MemAvailable: ${avail} MB"
    [ "$avail" -lt 3000 ] && note "Below 3000 MB: close apps or reboot first, or a silent lmkd kill is likely."
    launch
    pause "Open Settings, scroll to Debug, tap Acc, keep the screen on and the app in front, wait for 'Saved', then Close"
    pause "Tap Bench, wait for 'Saved', then Close"
    local f n=0
    for f in $(adb_sh "run-as $PKG ls files" | grep -E '^vj-(accuracy|benchmark)-.*\.json$'); do
        "$ADB" exec-out run-as "$PKG" cat "files/$f" > "$ROOT/docs/benchmarks/$f" </dev/null
        note "pulled docs/benchmarks/$f  whisper_lib=$(grep -o '"whisper_lib"[^,}]*' "$ROOT/docs/benchmarks/$f" | head -1)"
        n=$((n + 1))
    done
    if [ "$n" -ge 2 ]; then pass 8 "pulled $n result files into docs/benchmarks/"
    else fail 8 "expected an accuracy and a benchmark JSON, found $n"; fi
    app_log | grep -E 'did not survive|falling back' | sed 's/^/   log: /'
    note "Next: refresh the README performance table from these files."
}

step9() {
    say "9. ONNX Runtime without its telemetry hook"
    stop_app; "$ADB" logcat -c </dev/null; launch; sleep 12
    local all; all="$("$ADB" logcat -d </dev/null | tr -d '\r')"
    if echo "$all" | grep -E 'FATAL EXCEPTION|Fatal signal' | grep -q . && echo "$all" | grep -q "$PKG"; then
        fail 9 "crash at startup:"; echo "$all" | grep -E -A8 'FATAL EXCEPTION|Fatal signal' | head -30
    else
        pass 9 "no crash in the first 12 s after a cold start"
    fi
    local tele; tele="$(echo "$all" | grep -i -E 'onnxruntime|TelemetryInitializer' | grep -i -E 'telemetry|ClassNotFound|provider|retry' | head -5)"
    if [ -n "$tele" ]; then note "telemetry-related log lines to read:"; echo "$tele" | sed 's/^/   log: /'
    else pass 9 "no telemetry or provider complaints from ONNX Runtime in logcat"; fi

    local total embedded
    total="$(db_query 'SELECT COUNT(*) FROM entries')"
    embedded="$(db_query 'SELECT COUNT(*) FROM entries WHERE embedding IS NOT NULL AND embeddingModel IS NOT NULL')"
    note "entries: ${total:-?}, with an embedding: ${embedded:-?}"
    if [ "${total:-0}" -gt 0 ] && [ "$embedded" = "$total" ]; then pass 9 "every entry has an embedding written on the device"
    elif [ "${total:-0}" -eq 0 ]; then skip 9 "no entries yet; run step 2 first, then this"
    else fail 9 "$((total - embedded)) of $total entries have no embedding (backfill still running? rerun step 9)"; fi
    confirm 9 "Searching for a paraphrase (words NOT in the entry) finds the entry"
}

step10() {
    say "10. Search floor on a real journal"
    note "By hand, on a journal with real entries (import one if this phone has none)."
    confirm 10 "Queries about things you did say return the right entries near the top"
    confirm 10 "Queries about things you never said mostly return nothing"
    note "If real queries come back empty too often, lower HybridSearch.SEMANTIC_FLOOR and"
    note "rerun SearchCalibrationTest."
}

# ---------- main ----------

API=0
step0
STEPS=("$@"); [ ${#STEPS[@]} -eq 0 ] && STEPS=(1 2 3 4 5 6 7 8 9 10)
for s in "${STEPS[@]}"; do
    case "$s" in
        0) ;; # always runs
        1|2|3|4|5|6|7|8|9|10) "step$s" ;;
        *) note "unknown step: $s" ;;
    esac
done

say "Summary"
fails=0
for r in "${RESULTS[@]}"; do
    IFS='|' read -r step verdict text <<<"$r"
    printf '  %-4s step %-2s  %s\n' "$verdict" "$step" "$text"
    [ "$verdict" = FAIL ] && fails=$((fails + 1))
done
echo
echo "Full log: $LOG"
echo "Test entries from vjverify_*.wav clips are still in the journal; delete them in the app."
[ "$fails" -eq 0 ]
