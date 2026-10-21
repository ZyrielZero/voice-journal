#!/usr/bin/env python3
"""Grows the accuracy benchmark set from a local LibriSpeech test-clean copy.

The committed set is 3 clips and 222 reference words, too small to separate
models a point or two apart (see README). This script adds more clips:

    1. Download and extract test-clean from https://www.openslr.org/12
       (test-clean.tar.gz, about 350 MB; CC BY 4.0).
    2. python3 scripts/build-accuracy-set.py /path/to/LibriSpeech/test-clean
    3. Rebuild the debug APK, run the accuracy suite on a device, and commit
       the new vj-accuracy-<sha>.json under docs/benchmarks/.

Utterances are picked deterministically, round-robin across speakers so no
speaker dominates, and consecutive utterances of one chapter are joined into
clips of about --clip-seconds, the same shape as the committed clips. Each new
clip is converted to 16 kHz mono s16le WAV with ffmpeg and appended to
app/src/debug/assets/accuracy/manifest.json; existing clips are kept as is.
Utterances already in the manifest are skipped, so re-running is safe.

Size note: WAV is 32 KB per second. 300 utterances (about 35 minutes) adds
roughly 65 MB to the debug assets and the repository. Release APKs are not
affected, because the accuracy assets live in the debug source set.
"""
import argparse
import json
import pathlib
import subprocess
import sys
import wave

REPO = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO / "app/src/debug/assets/accuracy"


def load_corpus(root):
    """Returns {speaker: {chapter: [(utt_id, text, flac_path)]}} in corpus order."""
    corpus = {}
    for trans in sorted(root.glob("*/*/*.trans.txt")):
        speaker, chapter = trans.parent.parent.name, trans.parent.name
        rows = []
        for line in trans.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            utt, text = line.split(" ", 1)
            flac = trans.parent / f"{utt}.flac"
            if flac.exists():
                rows.append((utt, text.strip(), flac))
        if rows:
            corpus.setdefault(speaker, {})[chapter] = rows
    return corpus


def flac_seconds(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    return float(out)


def plan_clips(corpus, want_utts, clip_seconds, skip_ids):
    """Round-robin over speakers; each turn takes the next run of utterances
    from one chapter until the clip reaches clip_seconds."""
    queues = {}
    for spk, chapters in sorted(corpus.items(), key=lambda kv: int(kv[0])):
        flat = []
        for ch in sorted(chapters, key=int):
            flat.append([u for u in chapters[ch] if u[0] not in skip_ids])
        queues[spk] = [c for c in flat if c]
    clips, taken = [], 0
    while taken < want_utts and any(queues.values()):
        for spk in list(queues):
            if taken >= want_utts:
                break
            chapters = queues[spk]
            if not chapters:
                continue
            chapter = chapters[0]
            group, secs = [], 0.0
            while chapter and secs < clip_seconds and taken < want_utts:
                utt = chapter.pop(0)
                group.append(utt)
                secs += flac_seconds(utt[2])
                taken += 1
            if not chapter:
                chapters.pop(0)
            clips.append((spk, group))
    return clips


def write_wav(group, out_wav):
    """Concatenates the group's FLACs into one 16 kHz mono s16le WAV."""
    inputs = []
    for _, _, flac in group:
        inputs += ["-i", str(flac)]
    n = len(group)
    graph = "".join(f"[{i}:a]" for i in range(n)) + f"concat=n={n}:v=0:a=1[a]"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", graph, "-map", "[a]",
         "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-map_metadata", "-1",
         "-fflags", "+bitexact", "-flags:a", "+bitexact", str(out_wav)],
        check=True,
    )
    with wave.open(str(out_wav)) as w:
        return w.getnframes() / w.getframerate()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("test_clean", type=pathlib.Path, help="path to LibriSpeech/test-clean")
    ap.add_argument("--utterances", type=int, default=300, help="new utterances to add (default 300)")
    ap.add_argument("--clip-seconds", type=float, default=30.0, help="target clip length (default 30)")
    ap.add_argument("--out", type=pathlib.Path, default=DEFAULT_OUT, help="accuracy asset dir")
    args = ap.parse_args()

    for tool in ("ffmpeg", "ffprobe"):
        if subprocess.run(["which", tool], capture_output=True).returncode != 0:
            sys.exit(f"{tool} is required")

    manifest_path = args.out / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    have = {u for c in manifest["clips"] for u in c["librispeech_ids"]}
    names = {c["file"] for c in manifest["clips"]}

    corpus = load_corpus(args.test_clean)
    if not corpus:
        sys.exit(f"no *.trans.txt found under {args.test_clean}")
    plan = plan_clips(corpus, args.utterances, args.clip_seconds, have)

    added_words = 0
    for spk, group in plan:
        name = f"ls_{group[0][0]}.wav"
        if name in names:
            continue
        secs = write_wav(group, args.out / name)
        reference = " ".join(text for _, text, _ in group)
        added_words += len(reference.split())
        manifest["clips"].append({
            "file": name,
            "duration_s": round(secs, 2),
            "speaker": spk,
            "librispeech_ids": [u for u, _, _ in group],
            "reference": reference,
        })
        names.add(name)

    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    total_words = sum(len(c["reference"].split()) for c in manifest["clips"])
    print(f"added {len(plan)} clips, {sum(len(g) for _, g in plan)} utterances, "
          f"{added_words} words; manifest now {len(manifest['clips'])} clips, {total_words} words")


if __name__ == "__main__":
    main()
