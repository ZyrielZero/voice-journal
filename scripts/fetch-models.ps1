# Fetches the on-device models this app needs. Run once before first build.
# PowerShell twin of fetch-models.sh. Keep the two in sync.
#
# Every file is verified against scripts/models.sha256. URLs are pinned to
# HuggingFace commits, so a mismatch means a corrupt or truncated download.
# A present file that fails the check is fetched again; a fresh download that
# still fails stops the script.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dir = Join-Path $root "app/src/main/assets/models"
New-Item -ItemType Directory -Force $dir | Out-Null

$sums = @{}
foreach ($line in Get-Content (Join-Path $PSScriptRoot "models.sha256")) {
    $parts = $line -split '\s+', 2
    if ($parts.Count -eq 2) { $sums[$parts[1].Trim()] = $parts[0].ToLower() }
}

function Get-Model($out, $url) {
    $want = $sums[$out]
    if (-not $want) { throw "no checksum for $out in models.sha256" }
    $path = Join-Path $dir $out
    if ((Test-Path $path) -and (Get-Item $path).Length -gt 0) {
        if ((Get-FileHash -Algorithm SHA256 $path).Hash.ToLower() -eq $want) {
            Write-Host "$out already present, checksum OK"
            return
        }
        Write-Host "$out present but checksum mismatch, fetching again"
        Remove-Item $path
    }
    $part = "$path.part"
    curl.exe -fL --retry 3 -o $part $url
    if ($LASTEXITCODE -ne 0) { throw "download failed for $out" }
    $got = (Get-FileHash -Algorithm SHA256 $part).Hash.ToLower()
    if ($got -ne $want) {
        Remove-Item $part
        throw "checksum mismatch for ${out}: expected $want, got $got"
    }
    Move-Item -Force $part $path
}

Get-Model "ggml-base.en-q5_1.bin" "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en-q5_1.bin"
Get-Model "bge_small_en_v15_q8.onnx" "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/ea104dacec62c0de699686887e3f920caeb4f3e3/onnx/model_quantized.onnx"
Get-Model "bge_vocab.txt" "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/5c38ec7c405ec4b44b94cc5a9bb96e735b38267a/vocab.txt"
Get-Model "ggml-silero-v5.1.2.bin" "https://huggingface.co/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v5.1.2.bin"
Write-Host "Models fetched and verified. Build away."
