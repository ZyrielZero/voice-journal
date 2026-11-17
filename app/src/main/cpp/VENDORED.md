# Vendored: whisper.cpp

`whisper.cpp/` is a copy of [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
**1.9.1** (which bundles ggml 0.15.3), checked in as plain files and built
by `CMakeLists.txt` in this directory.

Only what the Android build uses is kept:

- `src/`, `include/` — whisper itself
- `ggml/` — the tensor library, unmodified and complete
- `cmake/`, `CMakeLists.txt` — the build
- `LICENSE`, `AUTHORS`, `README.md`

Removed from the upstream tree, because nothing here builds or reads them:
`bindings/`, `examples/`, `tests/`, `samples/`, `models/`, `scripts/`,
`media/`, `grammars/`, `ci/`, `.github/`, `.devops/`, `.pi/` and the
top-level helper files (Makefile, presets, contributor docs).

One consequence: upstream's `CMakeLists.txt` can no longer be configured on
its own (standalone mode reads `bindings/javascript`). It is only ever
included with `add_subdirectory`, as this directory's `CMakeLists.txt` does.

## Updating

1. Replace the kept directories and files from the new upstream tag.
2. Re-apply the one local patch: in `src/whisper.cpp`, where `whisper_full` creates the
   VAD context, set `vad_ctx_params.n_threads = 1` (search for "voice-journal local
   patch"). Upstream hard-codes 4 threads there and ignores the caller's count; on a
   phone that made a 60 s clip take one to two minutes in the VAD alone. Drop the patch
   if upstream starts passing the caller's thread count through.
3. Update the version above and in `THIRD_PARTY_NOTICES.md`.
4. Re-run the on-device benchmark and accuracy suites; a new ggml can move
   both speed and output.
