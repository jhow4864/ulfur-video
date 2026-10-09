# Ulfur Video (formerly Video Player, package com.jamh.videoplayer)

Release (1.4.0+): `./gradlew assembleRelease -Pulfur.keystoreProperties=/path/to/keystore.properties` (see "Signing" below).
Debug build: `source /home/box/android-tools/env.sh && ./gradlew assembleDebug` → app/build/outputs/apk/debug/app-debug.apk (copied to VideoPlayer.apk)
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

## v1.3.0 (versionCode 5)
- **Review merge** (`/workspace/video-player-review`): patch applied (P1-1 DialogueBoost flush, P1-2 editor config changes, P1-3 deferred rebuild, P2-1/2/3),
  plus the P0 proper fix: `MediaProbe` + four local buffer tiers sized from the probed bitrate and a byte cap (`PlayerFactory.tierForBitrate`), no main-thread I/O on open,
  P1-4 (editor metadata off the main thread), P1-5 (decoders released in onStop), several P2s. Baseline in git (`31efb72`).
- **Share sheet**: SEND / SEND_MULTIPLE video/* → PlayerActivity; several videos play as a playlist in share order (max 200).
- **Launcher shortcuts**: static "Clips" (res/xml/shortcuts.xml) and a dynamic "Continue watching" with the video's thumbnail (MediaStore/file/http only).
- **Orientation**: the player opens in the phone's current orientation and follows the system auto-rotate setting (`SCREEN_ORIENTATION_USER`).
  Rotate button and rotation lock unchanged; menu setting "Auto-rotate to match video" (default off) restores the old behaviour.
- **Scrub thumbnails** (`ScrubThumbnails`): local files only. A background-priority sweep (coarse-to-fine, one keyframe every max(2 s, duration/600))
  uses `getScaledFrameAtTime` CLOSEST_SYNC (API 27+, `getFrameAtTime` + scale below that). Frames are stored in a per-video pack file in cacheDir/scrub (64 MB LRU)
  and shown instantly from memory (nearest frame). Gaps are filled on demand once the finger rests. The sweep pauses while scrubbing and is cancelled when leaving the player.
  Streams show only the time (centre overlay).
- **Private videos** (`Vault`, `VaultActivity`, `MoveToPrivateFlow`, `VaultLock`, `VaultAuth`): library long-press → "Move to Private" copies into filesDir/vault,
  verifies (byte count + SHA-256), then deletes the original (API 30+ `createDeleteRequest`, API 29 RecoverableSecurityException, ≤28 direct). If the deletion is declined, the copy is removed.
  The lock icon opens the screen after BiometricPrompt (BIOMETRIC_WEAK | DEVICE_CREDENTIAL). It re-locks when the app goes to the background.
  FLAG_SECURE is set on the vault screen and on private playback. Private playback has no resume points, shortcuts, PiP or background audio, and thumbnails stay in memory.
  The vault is excluded from backup and device transfer. The screen shows storage used and warns that uninstalling or clearing data deletes the videos. "Move out" copies to Movies/VideoPlayer. Not encrypted.

## v1.4.0 (versionCode 6): Ulfur Video
- **Rebrand**: the app is now "Ulfur Video" (launcher label, toolbar, dialogs, share target, notifications). Same package
  (`com.jamh.videoplayer`) and same signing key, so it installs as an update over Video Player 1.3.0 and keeps the private videos.
  New clips/GIFs and "Move out" now go to Movies/UlfurVideo (Pictures/UlfurVideo for GIFs); files already in Movies/VideoPlayer stay where they are.
- **Ulfur look**: the Ulfur browser's night tokens (bg #0E0E12, surfaces #16161C/#1C1C24/#25252F, stroke #2C2C37, text #F2F2F7/#A0A0AE)
  and its default Blood Moon accent (#FF2D55 -> #F72FA0 -> #E040FB gradient, wolf eye #FF7A9A). DynamicColors (wallpaper colours) removed.
  Inter for text, Chakra Petch SemiBold for titles (both OFL, licences in assets/licenses), spaced-caps section labels with the accent bar,
  20dp cards / 14dp thumbnails / gradient pill buttons, gradient tab indicator and resume bar. The private screen uses Ulfur's private-mode purple.
- **New logo**: Blood Moon play triangle with the Ulfur howling wolf (the 2.7.1 logo's wolf) and pink eye, on the Ulfur icon background.
  Adaptive icon (foreground/background/monochrome themed icon) + legacy PNGs; `ic_app_mark` in the toolbar and empty state.
- **Multi-select**: long-press a video (Videos, inside a folder, or Clips; grid and list) to start selecting; tap to toggle; Select all;
  "N selected" in the toolbar; bulk "Move N to Private" (bottom bar or the lock action). Overflow "Select videos" also starts it.
  Private videos screen: the same multi-select with bulk "Move out of Private" and bulk "Delete permanently".
- **One warning per move**: the Move to Private warning is shown once for the whole batch with a "Don't ask me again" box
  (`Prefs.warnBeforePrivate`, SharedPreferences "settings"/`warn_before_private`). Overflow menu "Warn before moving to Private" turns it back on.
  Android 11+ asks once (one system delete request for the whole batch); Android 10 still asks per video (platform limit).
- Tests: `V14FeatureTest` (multi-select grid/list/clips, bulk warning + don't-ask-again persistence + menu toggle, vault multi-select). Previews go to `previews/`.

## Signing
1.3.0 and earlier were signed with the build box's `~/.android/debug.keystore` (CN=Android Debug,
SHA-256 1f94113ddeec8369736e0c9825245411f0467c9c8f55214ba827694cca3509e4). Updates must use the same key.
`app/build.gradle` reads the key from a properties file outside the repo (storeFile, storePassword, keyAlias, keyPassword)
given with `-Pulfur.keystoreProperties=...` or `ULFUR_VIDEO_KEYSTORE_PROPERTIES`. Back that keystore up: without it no update can be installed in place.
