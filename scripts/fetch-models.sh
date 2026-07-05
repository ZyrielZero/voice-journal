#!/usr/bin/env bash
# Fetches the on-device models this app needs. Run once before first build.
# Bash port of fetch-models.ps1 for Linux/macOS/CI. Keep the two in sync.
set -euo pipefail

dir="app/src/main/assets/models"
mkdir -p "$dir"

fetch() {
    local out="$1" url="$2"
    if [ -s "$dir/$out" ]; then
        echo "$out already present, skipping"
    else
        curl -fL --retry 3 -o "$dir/$out" "$url"
    fi
}

fetch ggml-base.en-q5_1.bin \
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
fetch bge_small_en_v15_q8.onnx \
    "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx"
fetch bge_vocab.txt \
    "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/vocab.txt"

echo "Models fetched. Build away."
