#!/usr/bin/env bash
# Fetches the on-device models this app needs. Run once before first build.
# Bash port of fetch-models.ps1 for Linux/macOS/CI. Keep the two in sync.
#
# Every file is verified against scripts/models.sha256. URLs are pinned to
# HuggingFace commits, so a mismatch means a corrupt or truncated download,
# never an upstream update. A file that is present but fails the check is
# deleted and fetched again; a fresh download that still fails aborts the
# script, so CI can never cache a bad model.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname "$script_dir")"
dir="$repo_root/app/src/main/assets/models"
sums="$script_dir/models.sha256"
mkdir -p "$dir"

if command -v sha256sum > /dev/null; then
    sha256() { sha256sum "$1" | cut -d' ' -f1; }
else
    sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }   # macOS
fi

expected() { awk -v f="$1" '$2 == f { print $1 }' "$sums"; }

fetch() {
    local out="$1" url="$2" want
    want="$(expected "$out")"
    if [ -z "$want" ]; then
        echo "no checksum for $out in $sums" >&2
        exit 1
    fi
    if [ -s "$dir/$out" ]; then
        if [ "$(sha256 "$dir/$out")" = "$want" ]; then
            echo "$out already present, checksum OK"
            return
        fi
        echo "$out present but checksum mismatch, fetching again"
        rm -f "$dir/$out"
    fi
    curl -fL --retry 3 -o "$dir/$out.part" "$url"
    local got
    got="$(sha256 "$dir/$out.part")"
    if [ "$got" != "$want" ]; then
        rm -f "$dir/$out.part"
        echo "checksum mismatch for $out: expected $want, got $got" >&2
        exit 1
    fi
    mv "$dir/$out.part" "$dir/$out"
}

fetch ggml-base.en-q5_1.bin \
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en-q5_1.bin"
fetch gte_base_q8.onnx \
    "https://huggingface.co/Xenova/gte-base/resolve/ffdeea73386c8c0f73931ebed6878aa715eca7d3/onnx/model_quantized.onnx"
fetch bert_vocab.txt \
    "https://huggingface.co/thenlper/gte-base/resolve/c078288308d8dee004ab72c6191778064285ec0c/vocab.txt"
fetch ggml-silero-v5.1.2.bin \
    "https://huggingface.co/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v5.1.2.bin"

echo "Models fetched and verified. Build away."
