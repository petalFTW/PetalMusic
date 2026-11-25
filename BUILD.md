# Building Petal Music from source

## What you need

- **JDK 17** (the project compiles for Java 17; Gradle 8.9 runs on JDK 17 to 21).
- **Git LFS**, before cloning. The ONNX models (`*.onnx`) and the ONNX Runtime library (`*.aar`) are stored in LFS. Without it, those files check out as small pointer files and the `rom` flavour won't build. If you cloned first, run `git lfs pull`.
- **Android SDK** with:
  - platform `android-35` (compileSdk and targetSdk 35, minSdk 31);
  - a recent build-tools;
  - platform-tools, for `adb`.
- **Internet on the first build.** Gradle downloads its own 8.9 distribution through the wrapper, plus the dependencies from Google, Maven Central and JitPack.
- Optional, for the AutoMix bench only: **ffmpeg** on `PATH`, and **Python 3**.

You don't need Gradle installed: use the wrapper, `./gradlew`. Only the Unix wrapper script is in the tree (the project has been built on Linux). On Windows, run the same commands from Git Bash or WSL, or create `gradlew.bat` once with an installed Gradle: `gradle wrapper --gradle-version 8.9`.

## One-time setup

1. Copy this folder to a disk with at least 10 GB free; a build creates about 4 GB in `app/build`.
2. Create `local.properties` in the project root, pointing at your SDK. It is not on the USB copy because it is machine-specific.
   ```
   sdk.dir=/home/you/Android/Sdk
   ```
   On Windows: `sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`
3. On Linux or macOS, if the execute bit was lost on the FAT32 stick, run `chmod +x gradlew`.

## Flavors

- **`rom`**: the petalOS prebuilt (platform-signed, BridgeFX HAL, island hooks, on-device Apollo through ONNX Runtime + QNN).
- **`fdroid`**: none of that. AutoMix runs on the internal DSP.
  - The fdroid release key is read from `~/.android/petal-fdroid/signing.properties`, or from `-PfdroidSigning=/path/to/signing.properties`.
  - Without it, a release APK is left unsigned. Debug builds always use the debug key.

## Commands

Run all of these from the project root.

```bash
# AutoMix composer and v3 Context tests
./gradlew :app:testRomDebugUnitTest --rerun --tests "*DjComposerTest*" --tests "*DjContextTest*"

# Every unit test
./gradlew :app:testRomDebugUnitTest --rerun

# Debug APKs  ->  app/build/outputs/apk/<flavor>/debug/
./gradlew :app:assembleRomDebug
./gradlew :app:assembleFdroidDebug

# Install on a connected phone
adb install -r app/build/outputs/apk/fdroid/debug/*.apk

# Release (minified; fdroid needs the signing file above)
./gradlew :app:assembleFdroidRelease
```

`--rerun` matters: without it, Gradle reports an unchanged test task as up-to-date and runs nothing.

The `:desktop` module is also part of the build (`./gradlew :desktop:tasks` lists what it offers).

## After changing `DjComposer.kt`

The test probes are generated copies of the engine. Regenerate them, or the sweep tests compile against old code:

```bash
python3 tools/automix-bench/sweep/make_probes.py .
```

## AutoMix bench (optional, needs ffmpeg and songs)

1. Put audio files (m4a, flac, mp3 or wav) in `tools/automix-bench/songs/`. They are not included on the USB copy.
2. Run the bench:

```bash
# Sweep every ordered pair ("sweep") and 30 shuffled sequences ("seq"); writes tools/automix-bench/out/sweep/*.tsv
# Render a list instead with PETAL_RENDER=<file> (lines: name|from|to|CREATIVE|CLEAN|FAMILY)
PETAL_BENCH_DIR=$PWD/tools/automix-bench PETAL_SWEEP=sweep,seq \
  ./gradlew :app:testRomDebugUnitTest --rerun --tests "*AutomixCreativeSweep*"

# Context prototype over every pair, plus renders from a list (lines: name|from|to[|MOVE])
PETAL_BENCH_DIR=$PWD/tools/automix-bench PETAL_CONTEXT=all \
  PETAL_CONTEXT_RENDER=$PWD/tools/automix-bench/sweep/context.txt \
  ./gradlew :app:testRomDebugUnitTest --rerun --tests "*ContextPrototype*"
```

The first run analyses each song and caches its profile in `out/sweep/profiles/`; later runs reuse the cache. Renders are written to `tools/automix-bench/out/renders/` as mp3.

See `HANDOFF.md` for where the AutoMix work stands.

```bash
# Downbeat ground truth: tap beat 1 in tools/automix-bench/downbeats/tapper.html, paste into labels.tsv,
# then score the cached profiles (needs a sweep run first for out/sweep/profiles)
PETAL_BENCH_DIR=$PWD/tools/automix-bench ./gradlew :app:testRomDebugUnitTest --rerun --tests "*DownbeatEval*"

# Platform-signed rom release into prebuilt/app-release.apk (keys from petal-priv)
./gradlew :app:signPetal -PpetalKeys=/path/to/petal-priv/keys
```
