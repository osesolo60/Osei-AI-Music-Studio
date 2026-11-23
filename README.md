# Osei AI Music Studio

**Package:** `com.osei.aimusicstudio`  
**Platform:** Android (Kotlin, Jetpack Compose, Material 3, Room, Media3, ONNX Runtime)  
**Minimum SDK:** Android 7.0 (API 24) · **Target / Compile SDK:** Android 16 (API 36)  
**Hardware Target:** 4 GB RAM Android phones · **Development Budget:** GH₵0 (free & open-source stack only)

---

## Feature Status Overview

### Genuinely Working Local Audio & Studio Features
- **Vocal Separation (`VocalSeparationScreen` — Home & AI Tools):**
  - Import audio directly (`OpenDocument`) or choose from Library, run genuine 2-stem ONNX Runtime inference (`UVR_MDXNET_KARA_2.onnx` when downloaded with consent or imported locally) via **Extract Vocals** and **Remove Vocals**, preview **Vocals** and **Instrumental** playback (`AudioPlayerManager`), save stems permanently into Library (`filesDir/saved_stems/` + Room), export WAV via SAF, and share via scoped `FileProvider`. Never generates fake output files if ONNX weights are absent.
- **Song Analysis (`SongAnalysisScreen` — Home & AI Tools):**
  - Streaming low-RAM local DSP analysis (`SongAudioAnalyzer`) using 8192-point STFT chromagram + Krumhansl-Schmuckler key profiles and onset-strength autocorrelation for BPM (`60–190 BPM`), displaying confidence metrics and a 12-pitch chroma energy chart. Refuses to invent results when signal is silent, unpitched noise, or below confidence thresholds. Supports **Manual Key Correction** (24 major/minor keys) and saves `musicalKey` and `bpm` to Room.
- **Audio Import & Project Library (Room v5):** Import local audio files via Android Storage Access Framework (`OpenDocument`), inspect durations/headers, persist projects with Room migrations (`MIGRATION_1_2` through `MIGRATION_4_5`), and open project audio directly in the Audio Editor or Mixer.
- **Six-Track Synchronized Studio Mixer:**
  - Six independent instrument tracks (`Drums`, `Bass`, `Guitar`, `Strings`, `Keys`, `Other`) that work directly with user-imported audio files or genuine separated WAV stems.
  - Per-track controls: Volume, Constant-Power Pan, Mute, Solo, Enable/Disable, Timeline Offset (ms), Waveform preview, Track clear/replace, Single-track WAV export, and Master Volume.
  - Memory-safe streaming mixing via `MultitrackAudioEngine` (`PcmWavSeekReader` + single `AudioTrack` output) and `MultitrackWavExporter`.
- **Advanced Audio Editor (Non-Destructive Timeline + Undo/Redo):**
  - Trim start/end bounds, split clips into sections, move clips along the timeline, sequentially merge clips to WAV, delete sections, and multi-step Undo/Redo persisted locally in Room (`editor_projects`, `editor_clips`).
- **Real DSP Audio Effects & Vocal Controls (`StreamingAudioDspChain` & `WsolaTimePitchProcessor`):**
  - 3-Band Equalizer (Low/Mid/High biquad filters), Bass Boost, Delay (with feedback), Schroeder Reverb, Dynamic Range Compressor, Noise Gate, Volume & Stereo Balance, plus Soft Safety Limiter.
  - WSOLA Time-Stretching & Pitch-Shifting (semitone shift and tempo ratio with optional pitch preservation).
- **Anti-Aliased Multi-Rate WAV Export & Scoped Sharing:**
  - Streaming 24-tap Blackman-sinc FIR resampling export (`StreamingStereoResampler`) at **22.05 kHz**, **44.1 kHz**, and **48 kHz** (16-bit PCM stereo WAV) via SAF (`CreateDocument`).
  - Android `ACTION_SEND` sharing restricted strictly to `cacheDir/shared_exports/` via `FileProvider` (`file_paths.xml`), unique non-overwriting share filenames, active-share file protection, and automatic 7-day age-based cache cleanup (`SafeTempStorageCleanup`).
- **Karaoke Studio:**
  - LRC lyric file parsing & synchronized playback (`AudioPlayerManager`), global lyric timing offset controls, live microphone WAV recording (`AudioRecord`), and per-recording vocal take timing alignment (`recordedTakeOffsetMs`) saved in Room.
- **2-Stem & 4-Stem ONNX Engine Architecture (`StemSeparatorEngine` & `SeparationModelCatalog`):**
  - Chunked CPU ONNX Runtime pipeline designed for low-RAM devices (`UVR_MDXNET_KARA_2.onnx` 2-stem, quantized 4-stem Spleeter U-Net, high-RAM warning/blocking for HTDemucs v4). Requires downloading or loading verified `.onnx` weights via SAF; **on-device neural separation has not yet been benchmarked on physical Android hardware**.

### Unavailable / Unfinished Features (Explicitly Marked in UI)
- **AI Music Generation (`AiMusicGeneratorScreen`):** **UNAVAILABLE.** No authorized free (GH₵0) remote text-to-music provider is configured, and on-device generative audio diffusion/transformer models exceed 4 GB phone RAM limits. The screen displays explicit status (`Unavailable`), disables the generate button, does not simulate progress or fabricate audio, and links to local Audio Import and Audio Editor workflows.
- **AI Music Video Export (`AiMusicVideoScreen`):** **EXPORT UNAVAILABLE** (Local Picker & Preview **WORKING**). Selecting local video/image media works via zero-permission Android Photo Picker (`PickVisualMedia`) with Media3 `ExoPlayer`/`PlayerView` and Coil `AsyncImage` previews. Video rendering/export is disabled (`Unavailable`) because a local `MediaCodec` + `MediaMuxer` frame/audio encoding pipeline is not yet implemented and paid cloud video APIs are disallowed.
- **Six-Instrument AI Stem Separation (`StemSeparatorScreen`):** **UNAVAILABLE.** No verified open-source 4 GB-compatible Android ONNX model separates `[drums, bass, guitar, strings, keys, other]`. Researched 6-stem models (`HTDemucs 6s`, `BS-RoFormer-SW`) output `vocals` and `piano` rather than `strings` and `keys` and exceed 4 GB RAM; ZFTurbo's 53-stem BS-RoFormer (~1.37 GB) requires 12–16+ GB RAM. Use the 6-Track Mixer with manual imports instead.
- **Music Analyzer (`MusicAnalyzerScreen`):** **STATIC DISPLAY ONLY.** Displays a placeholder metrics layout; automated BPM/Key/Chord DSP extraction has not been wired into `MusicAnalyzerScreen`.

---

## Permissions & Play Policy Compliance

Declared in `app/src/main/AndroidManifest.xml`:
- `android.permission.INTERNET` — Normal install-time permission reserved for optional open-source ONNX model downloads initiated explicitly by the user.
- `android.permission.RECORD_AUDIO` — Runtime permission requested strictly before microphone recording in Karaoke Studio.

Zero broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `WRITE_EXTERNAL_STORAGE`) are declared or requested. All user audio/model/media files use Android Storage Access Framework (`OpenDocument` / `CreateDocument`) and Android Photo Picker (`PickVisualMedia`), and WAV exports are shared strictly from `cacheDir/shared_exports/`.

---

## Build & Test Instructions

### Standalone Build (Gradle Wrapper)
The repository includes `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` pinned to Gradle **9.3.1**:

```bash
# Run all JVM & Robolectric unit/UI tests
./gradlew :app:testDebugUnitTest --console=plain

# Assemble Debug APK (signed with debug.keystore)
./gradlew :app:assembleDebug --console=plain
```

> **Note on Container Environments:** In build containers where preinstalled Gradle is preferred over wrapper downloads, run `gradle :app:testDebugUnitTest` and `gradle :app:assembleDebug`.

### Release Build Signing
`app/build.gradle.kts` (`signingConfigs.release`) reads keystore configuration from environment variables rather than committing secrets:
- `KEYSTORE_PATH` (defaults to `./my-upload-key.jks`)
- `STORE_PASSWORD`
- `KEY_PASSWORD`
- Key alias: `upload`

Running `:app:assembleRelease` without providing an upload keystore and passwords will fail during APK signing validation unless valid release signing credentials are supplied in the build environment.

### Environment & Secrets
- No Gemini API key, Firebase project, or paid third-party service key is required or used.
- `.env.example` keeps `GEMINI_API_KEY` commented out so no unused key is packaged into `BuildConfig`.

---

## Known Limitations
1. **Physical Device Testing Pending:** Automated verification uses Robolectric JVM tests (`@Config(sdk = [36])`) and synthetic deterministic audio signals; physical 4 GB Android phone testing (live `AudioTrack`/`AudioRecord` latency, real-world ONNX CPU thermal/memory pressure, and real MP3/AAC decoding codecs) has not yet been executed on hardware.
2. **Decoder Support on JVM:** Compressed formats (MP3/M4A/AAC) rely on Android `MediaExtractor`/`MediaCodec` on device; local JVM unit tests exercise WAV/PCM pipelines and Compose UI/Room state.
3. **Music Analyzer Screen:** Not yet connected to live BPM/key estimation DSP.
