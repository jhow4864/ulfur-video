# Video Player (com.jamh.videoplayer)

Build: `source /home/box/android-tools/env.sh && ./gradlew assembleDebug` → app/build/outputs/apk/debug/app-debug.apk (copied to VideoPlayer.apk)
Previews/smoke test: `./gradlew testDebugUnitTest` (Robolectric, writes previews/*.png)

- Media3 1.9.4 (exoplayer, -hls, -dash, -ui) + org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1 (prebuilt FFmpeg audio, GPLv3)
- compileSdk 36 (Media3 1.9 needs ≥35), targetSdk 34, minSdk 24, AGP 8.5.2 / Gradle 8.7, Java 17
- Key files: PlayerFactory.java (LoadControl/renderers), PlayerActivity.java (scrubbing, gestures, PiP, tracks), MainActivity.java (library)

## 1.1.0 – "Set as default player" flow
- DefaultPlayerHelper.java: resolveActivity(ACTION_VIEW, content://…, video/mp4 + video/x-matroska) → this app / none / other app
- Library overflow "Set as default player" + one-time banner; dialog adapts to state; re-check + snackbar in onResume
- No default → real ACTION_VIEW (no createChooser) on first library video, or res/raw/sample_video.mp4 via FileProvider
- Android stores "Always" per MIME type, so the dialog runs a second round for MKV when needed

## v1.2.0 (versionCode 3)
- **Night mode / Dialogue**: persisted toggle; custom Media3 `AudioProcessor` (`DialogueBoostProcessor`) in the audio sink:
  low shelf −4 dB @180 Hz, +6 dB peak @2.2 kHz (centre channel +3 dB more on 5.1), linked 3:1 compressor (−26 dB, 8/250 ms, +8 dB makeup),
  −1 dBFS look-through limiter plus a hard clamp (no clipping), 60 ms crossfade on toggle.
- **Precision scrubbing**: finger height sets the rate. Top third = normal (screen width ≈ 90 s, or the whole video if shorter), middle = half speed,
  bottom third = fine (12 dp per 0.1 s, EXACT seeks). The mode is shown in the scrub overlay; normal/half keep the CLOSEST_SYNC live preview.
- **Background audio**: menu toggle → `PlaybackService` (Media3 `MediaSessionService`). Media notification + lock screen controls (play/pause, ±10 s, prev/next).
  Video decoding is disabled while in the background. Sleep timer: 15/30/45/60 min, end of video, custom, with a 10 s fade-out.
- **Material You**: Material 3 dark theme with `DynamicColors` (Android 12+); the library is edge-to-edge.
- **Clips tab**: portrait OR < 3 min (switchable). Full-screen vertical `ViewPager2` with autoplay, loop, tap to pause, and a pool of 2 ExoPlayers (preloads the next clip).
- **Make clip / GIF**: trim slider (auto-zoomed window) with ±1 frame / ±0.1 s nudges, live preview loop, crop (free/1:1/9:16/16:9), caption (position/size/colour).
  Output: MP4 via Media3 Transformer (H.264/AAC hardware encode, audio optional) or GIF (320/480/640 px, 10/15/20 fps, OPTION_CLOSEST frames → bundled median-cut + Floyd–Steinberg + LZW `GifEncoder`).
  Progress with cancel; saved to Movies/VideoPlayer or Pictures/VideoPlayer via MediaStore, then Share/Open. Warns on GIFs over 15 s.
