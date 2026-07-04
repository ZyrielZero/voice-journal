# Fetches the on-device models this app needs. Run once before first build.
$dir = "app/src/main/assets/models"
New-Item -ItemType Directory -Force $dir | Out-Null
curl.exe -L -o "$dir/ggml-base.en-q5_1.bin" "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
curl.exe -L -o "$dir/bge_small_en_v15_q8.onnx" "https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/main/onnx/model_quantized.onnx"
curl.exe -L -o "$dir/bge_vocab.txt" "https://huggingface.co/BAAI/bge-small-en-v1.5/resolve/main/vocab.txt"
Write-Host "Models fetched. Build away."
