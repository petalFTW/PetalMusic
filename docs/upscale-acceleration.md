# Apollo upscaling and Qualcomm HTP

## Processing

The APK bundles Apollo's fixed two-second stereo export as a partitioned pipeline:

- `frontend.onnx`: float32 STFT, CPU.
- `core.onnx`: float32 reconstruction core, CPU reference/fallback.
- `encoder.onnx` / `decoder.onnx`: CPU band encoding and decoding.
- `block-0-fp16.onnx` through `block-5-fp16.onnx`: six 111-node BSNet blocks
  with float16 input/output, Qualcomm HTP candidates.
- `backend.onnx`: float32 inverse FFT and overlap-add, CPU.

Keeping the FFTs outside the accelerator graph avoids fragmented CPU/NPU
partitions. The dense BSNet layers run on HTP; the smaller per-band frontend and
output heads stay on CPU. Float16 I/O conversion is done in Java, outside HTP. The original
signal is retained below an 8 kHz crossover; only half-strength high-frequency
reconstruction residual is added. Symmetric FIR filtering has no timing delay.
Segments overlap by 100 ms and the written WAV is checked against the decoded
frame count. Digital silence bypasses inference.

### Hi-Fi mastering stage

Apollo repairs codec damage; it does not master the track. After the overlap-add
output is assembled, a deterministic DSP chain runs on the continuous stream:

1. **Presence detector** — measures the 3 kHz+ energy ratio and adds only a
   small high-band lift when the source is genuinely dull.
2. **Dynamics / transients** — light upward compression of quiet passages and a
   fast/slow envelope difference for punch, capped at +2.6 dB.
3. **Adaptive M/S width** — measures the side/mid ratio and widens only narrow
   material; already-wide tracks are left alone. A one-pole split keeps content
   below ~140 Hz centred. The amount is controlled by the Stereo width setting
   (0 = off, default 0.5).
4. **Soft-knee limiter** — a 0.97 ceiling with a soft clip guard so model
   reconstruction and mastering never produce clipped PCM.

The stage is stateful across segments (it runs after overlap-add), so filters
and envelopes are not applied twice at segment seams. Device Link sends the
width setting over the same endpoint and runs the equivalent desktop chain.

The model manifest pins all assets by SHA-256. Apollo attribution and its CC
BY-SA 4.0 license are included beside the graphs. Older generated copies have
an older processing revision or a different width; toggling them off and on
queues a regeneration rather than reusing them. Original downloads remain
available.

## Signal routing and modes

Before any model runs, the download is decoded and analysed in 4096-point FFT
windows. The analyser reports the spectral cutoff (largest 1 kHz drop), the
high-frequency energy above 16/18/20 kHz, L/R correlation and side/mid energy,
RMS, crest factor, sample peak and clipped samples. Routing is based on the
signal, not the container or nominal bitrate, so a transcoded AAC/MP3 wrapped
in FLAC is still treated as band-limited.

- **Auto** — full-band material uses the DSP-only finishing chain; material with
  a real codec cliff uses Apollo.
- **Restore** — always uses Apollo, then the finishing chain.
- **Enhance** — DSP only, for genuine full-band music. No generative model.

Apollo's high-band residual is now blended only above the detected cutoff, with
the input preserved below it. The finishing chain uses a multiband M/S curve
(bass unchanged, side rising through mids/highs), a correlation guard that backs
off before phase problems, a quiet 6 kHz harmonic exciter, gentle presence and
transient lift, and a soft-knee limiter. The old width feedback loop was removed;
its level breathing could sound like slow stereo movement.

Auto/Enhance can run without the Apollo unlock because they never load the model.
Restore remains behind the ten-tap warning. Device Link receives the mode; the
desktop uses its own DSP chain for Enhance and cannot run Apollo, so use on-device
Restore for codec-damaged tracks.

Reference Master (Matchering) and North-Star are not active yet. North-Star's
public checkpoint is not published; Matchering remains an optional desktop stage
(GPL-3.0) with a user-supplied reference.

## On-device activation

On-device Apollo is opt-in. A fresh install keeps it locked: tapping the
upscale control counts toward ten deliberate taps, then shows a warning about
CPU time, heat and battery use. Confirming unlocks on-device processing for
future tracks. A configured Device Link is never gated because the linked PC
does the work. The Downloads list and Settings share the same unlock counter,
and the unlock is persisted.

## Outcome (2026-10-04)

Direct QNN/HTP execution is **disabled** (`OnnxUpscalePipeline.ENABLE_NPU = false`).
The shipped path is CPU. Reasons, established on the SM8845 device:

- The DSP is reachable: the six BSNet blocks compile on-device and their
  embedded contexts cache to disk (66-85 s each, ~29.5 MB each).
- Loading all six contexts at once exhausted memory and the low-memory killer
  killed the process (~1 GB swap). Execution was therefore changed to open one
  block session at a time, copying each block's output so the session can close.
- With bounded memory the blocks load and run, but the first block fails with
  `ORT_FAIL ... QNN graph execute error. Error code: 1003` (system
  communication/DSP failure). Qualcomm's own compiler warns it cannot tile
  Apollo intermediates (for example a `[1,402,2048,80]` concat needing
  ~263 MB), which far exceeds Hexagon on-DSP memory. The model compiles but
  cannot execute on HTP as exported.
- The player already falls back to CPU on any QNN failure, so enabling the
  probe is safe but would first spend ~7 minutes compiling contexts that cannot
  run. It is left disabled to avoid that cost.

A viable NPU path would require rewriting Apollo's band attention/serialisation
so intermediates fit HTP memory (or targeting the QNN GPU backend), then
re-verifying waveform parity.

## Runtime selection

The arm64 runtime is ONNX Runtime 1.30.0 built with static QNN support against
QAIRT 2.44.0.260225. SDK-matched HTP, preparation, system, v81 stub and DSP
libraries are packaged in the local AAR. Other ABIs retain stock CPU runtimes.
Native library extraction and optional `libcdsprpc.so` access are enabled.

When `ENABLE_NPU` is true, the app prepares the six HTP blocks with CPU
fallback disabled for those graphs, then compares output against the float32
CPU core and times two warmed runs per backend. HTP is selected only for a
speedup exceeding 15% with finite output, <=1% relative RMS error (with an
absolute floor for silence), and a bounded peak error. Unsupported graphs,
failed comparisons, or slower execution use CPU, as does any later HTP
exception. Only one block session is held at a time. FFTs and audio file I/O
always stay on CPU.

Compiled contexts are cached by each block's model hash and QAIRT version.
Compilation runs in the `:npu_prepare` process, which skips the player's app
container initialization. It writes temporary embedded contexts and publishes
them only after graph finalization succeeds. The player loads cached contexts
only and refuses to compile uncached graphs in its own process. Worker death or
timeouts fall back to CPU, with a failure marker to prevent repeated bad
preparation attempts. Completed block caches are retained on cancellation.

Preparation is limited to 120 seconds per block and ten minutes total. These
limits bound the native compiler even though a Java interruption cannot stop
it. Clients joining the same worker receive the current block and completion;
cancelling one client does not kill the worker while another is using it.

First use includes compilation and benchmarking. The notification reports
block count and elapsed seconds, comparison/benchmark stages, then `NPU ·
Qualcomm HTP` or `CPU`. It does not claim acceleration solely because a provider
was registered. Finished WAVs are atomically published from a temporary file.

## Findings on 2026-10-04

- The SM8845 phone exposed one NNAPI device: `nnapi-reference`, type CPU. Direct
  QNN is therefore used instead of NNAPI.
- The previous stereo frame-count bug doubled inference length and repeated
  stale PCM after EOF. Frame counting and strict EOF reads were corrected.
- The affected WAV's timing offset remained approximately 46 ms at every
  sampled section; no cumulative drift was observed. Reconstruction artifacts
  and playback behavior still require listening verification.
- CPU split-pipeline parity passed on actual audio excerpts at 0, 10 and 60 s;
  worst RMS difference from the original waveform export was about 1.5e-7.
- Float16 spectral inference on the local CPU was finite and differed from the
  float32 core by about 0.152% relative RMS on the tested excerpt.
- Qualcomm conversion of the explicit-fp16 core succeeded. Offline v81 graph
  preparation reached target sequencing but did not finish within the four-minute
  diagnostic limit. This is not a completed HTP compilation or execution test.
- Live testing subsequently confirmed FastRPC/DSP access, but the monolithic
  graph aborted inside `libQnnHtpPrepare.so` target sequencing with
  `Scudo ERROR: internal map failure (Out of memory)`. Java exception fallback
  cannot contain that native abort; this motivated process isolation and block
  partitioning.
- The first partitioned block compiled successfully in 66.2 seconds on SM8845.
  A 45-second initial watchdog was too short and was increased to 120 seconds
  per block. Hardware execution and speed selection still require a completed
  device benchmark.

## Rebuilding

The generated graphs and local AAR are in the project, so normal Gradle builds
do not require the SDK. Generation tools:

```sh
python tools/prepare_apollo_npu.py model.onnx app/src/main/assets/upscale \
  --license-file /path/to/Apollo/LICENSE
python tools/verify_apollo_pipeline.py model.onnx app/src/main/assets/upscale \
  stereo-44100-float32.pcm
python tools/package_qnn_runtime.py stock-onnxruntime-android-1.30.0.aar \
  /path/to/ort-build/Release /path/to/qairt/2.44.0.260225 \
  app/libs/onnxruntime-qnn-1.30.0.aar
./gradlew :app:signPetal
```

Model generation/verification requires `onnx`, `numpy` and `onnxruntime==1.30.0`.
The stock AAR is available from the project's Maven cache.

Native build uses the ONNX Runtime `v1.30.0` tag with:

```sh
./build.sh --config Release --parallel 8 --update --build --skip_tests \
  --android --android_sdk_path "$ANDROID_HOME" --android_ndk_path "$ANDROID_NDK_HOME" \
  --android_abi arm64-v8a --android_api 31 --build_shared_lib --build_java \
  --use_qnn static_lib --qnn_home "$QNN_SDK_ROOT" --cmake_generator Ninja
```

The Android static-QNN build needs an empty-file-list guard on the Java-test
`QNN_LIB_FILES` copy command in `cmake/onnxruntime_unittests.cmake`; otherwise
CMake invokes `cmake -E copy` with only a destination. Apply the same guard to
the SDK-library copy commands in `cmake/onnxruntime_providers_qnn.cmake`.

For device verification, inspect `PetalMusic/Upscale` logs for the benchmark and
selection reason, and compare the regenerated audio with the original with
playback speed at 1x. The APK is signed with the petal platform key by
`:app:signPetal` into `prebuilt/app-release.apk`.
