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

## v1.4.1 (versionCode 7): no delete pop-up with media management
- Declares `android.permission.MANAGE_MEDIA` (Android 12+, "Media management apps" special access; the user grants it in Settings).
- When `MediaStore.canManageMedia()` is true, Move to Private deletes the originals directly where MediaStore allows it, and sends the rest
  through `createDeleteRequest`, which Android then approves without a dialog.
- The first time a move would show Android's "Allow Ulfur Video to delete…" dialog (Android 12+, not granted), an Ulfur dialog offers
  "Open settings" (`Settings.ACTION_REQUEST_MANAGE_MEDIA` for this package) / "Not now" with "Don't ask again" (`Prefs.mediaManagePromptOff`).
  It is shown before anything is copied, at most once per app session. ⋮ › "Media management permission" opens it any time (ticked when granted).
  Re-checked in onResume (snackbar when the state changes).
- Not granted / Android 11: unchanged fallback, one system confirmation per batch. Android 10: per video. All-files access is not used.

## v1.4.2 (versionCode 8): instant seeking

- Seek-bar release/tap no longer issues a second frame-exact (EXACT) seek after the keyframe preview seek; it lands on the keyframe that was previewed. Exact seeks are kept for the fine (0.1 s) scrub zone.
- Local playback resumes after 250 ms buffered (500 ms after a rebuffer) instead of 1.5 s / 3 s; streaming keeps the old values.
- The background scrub-thumbnail sweep pauses for 4 s after any seek/skip/rebuffer so it doesn't compete with the player for the decoder and disk.
- Private (vault) videos are plain files served through FileProvider and are seekable (lseek), so they were not the cause.

## v1.5.0 (versionCode 9): real-time Upscale

Live GPU upscale + sharpen while playing (nothing is saved). Built on Media3 video effects
(`ExoPlayer.setVideoEffects` + a custom `GlEffect` / `BaseGlShaderProgram`).

- **Toggle**: "HD✦" button in the player's top bar (tap: Off → Balanced → Sharp; long-press: picker),
  also "Upscale" at the top of the ⋮ player menu. The icon turns Ulfur red when on, and a small
  gradient-bordered label shows e.g. "Upscale · Balanced / 640×360 → 1920×1080". Global setting
  (`Prefs.upscaleMode`, default Off).
- **Pipeline** (`UpscaleShaderProgram`):
  - video smaller than the area it's drawn in → **FSR 1.0 EASU** to that size (capped at ~1080p
    worth of pixels and 4×), then **RCAS** sharpening (Balanced 0.8 stops, Sharp 0.25 stops);
  - video already fills the screen → Balanced passes it through, Sharp applies light RCAS only
    (never for > 1080p, e.g. 4K);
  - "Lite" quality = Catmull-Rom bicubic (9 bilinear taps) + RCAS, used automatically as a fallback.
- **Shaders**: `app/src/main/assets/shaders/ulfur_fsr_easu.glsl`, `ulfur_rcas.glsl`,
  `ulfur_catmull_rom.glsl`, `ulfur_copy.glsl`, `ulfur_upscale_vertex.glsl` (GLSL ES 1.00, validated
  with glslangValidator). EASU/RCAS are ports of AMD FidelityFX FSR 1.0 (`ffx_fsr1.h`),
  © 2021 Advanced Micro Devices, Inc., MIT licence: `app/src/main/assets/licenses/FSR1_LICENSE.txt`.
- **Safety nets**:
  - The GL pipeline is only created when Upscale is on (with Off, playback is exactly 1.4.2's).
  - HDR videos are never processed: the player is rebuilt without the pipeline when an HDR track is
    detected, so HDR output / tone-mapping is untouched.
  - GL init/processing error → retry with Lite → turn Upscale off (persisted) with a message.
  - Frame-drop watchdog: two 2 s windows with > 12 % dropped frames → Lite → paused for the session.
  - Paused in picture-in-picture; re-targets on rotation / aspect-mode changes; rotation is applied
    by the decoder before the effect. Seek settings from 1.4.2 are unchanged.
- **Before/after**: `design/upscale-before-after.png`, rendered by running these exact shaders through
  desktop Mesa GL (`tools/upscale-compare/render.py`, `compose.py`) on a 640×360 frame of
  Big Buck Bunny (© Blender Foundation, CC BY 3.0).

## v1.5.1 (versionCode 10): bulk Delete in the library

- History: 1.2.0/1.3.0 had no way to delete gallery videos at all. The library long-press popup offered only
  "Move to Private"; the only delete was "Delete permanently" in the Private folder's long-press popup.
  1.4.0 turned long-press into multi-select in both screens (bulk Move to Private / Move out / Delete
  permanently in Private), so the Private popup's single delete became "select → delete".
- New: in selection mode (Videos, inside folders, Clips; grid and list) a red trash icon in the selection
  toolbar and a round red Delete button in the bottom bar next to "Move N to Private". Single delete =
  long-press a video → Delete (no extra menu, so long-press-to-select is unchanged).
- One confirmation per batch (`DeleteVideosFlow`): with media management allowed (1.4.1) or on Android ≤ 10,
  our "Delete N videos?" dialog, then direct MediaStore deletes with no system prompt (leftovers via the
  auto-approved createDeleteRequest). On Android 11+ without media management, Android's own
  "Allow … to delete N videos?" is the single confirmation (one request for the whole batch).
- Deleted videos disappear from the list immediately, selection mode ends, resume points / "Continue
  watching" shortcuts are cleared, then MediaStore is re-queried. Cancelling keeps the selection.
- Private folder bulk "Delete permanently" unchanged (now tinted danger red); covered by a test.

## v1.8.0 (versionCode 13): Sharp = Anime4K, Upscale on export (2×)

The Off / Balanced / Sharp control and the HD✦ button are unchanged; only what Sharp runs is new.

- **Off**: unchanged (no GL pipeline). **HDR is never processed** in any mode (the 1.5.0 HDR rebuild
  path is kept, and `Upscaler.route()` copies HDR frames untouched).
- **Balanced**: unchanged FSR 1.0 EASU + RCAS, Lite (Catmull-Rom) fallback, weak-GPU kill switch.
  No FSR path was removed.
- **Sharp**: Anime4K v4 **Restore CNN (M)** + **Upscale CNN x2 (M)** (the fast variants), ported from the
  mpv GLSL hooks to GLSL ES 3.00 passes inside the same Media3 effect (`UpscaleShaderProgram` →
  `Anime4kPipeline`). Routing (`Upscaler.route`):
  - video smaller than its drawing area: Restore + CNN x2 **at the source resolution**, then FSR EASU
    (2× → larger output) or Catmull-Rom (2× → smaller output, or Lite quality) to the same output size
    Balanced uses (≤ ~1080p of pixels). No CAS/RCAS on top of Anime4K;
  - video already fills the screen (≤ 1080p): Restore only, no upscale;
  - sources above 1080p: exactly Balanced.
  - Picker label: "Sharp: Anime4K CNN · best for anime & cartoons".
- **Shaders**: `assets/shaders/ulfur_a4k_restore_cnn_m.glsl`, `ulfur_a4k_upscale_cnn_x2_m.glsl`,
  `ulfur_a4k_vertex.glsl`, generated by `tools/anime4k-port/port.py` from the unmodified mpv files in
  `tools/anime4k-port/mpv/` (one `#if PASS == n` block per mpv pass; weights unchanged; exact
  edge-clamped `texelFetch` taps; vertical offsets and the depth-to-space order mirrored because GL
  textures are bottom-up). Feature maps are RGBA16F (needs ES 3.2 or `EXT_color_buffer_(half_)float`).
  `render18.py --selftest` checks the GL port against a numpy implementation of the mpv shaders
  (max error 0.002 Restore / 0.009 Upscale, i.e. half-float rounding). Validated with glslangValidator (ES 3.00).
  Anime4K © 2019-2021 bloc97, MIT: `assets/licenses/ANIME4K_LICENSE.txt`.
- **Safety net for Sharp** (all 1.5.0 fallbacks stay: Lite, kill switch, PiP pause, rotation re-target):
  - GPU time of the upscale passes is measured on the GL thread (`GpuFrameTimer`):
    `GL_EXT_disjoint_timer_query` (`GL_TIME_ELAPSED_EXT`, ring of 4, disjoint results dropped) when
    available, otherwise every 8th frame bracketed by `glFinish()` + wall clock.
  - Every ~2 s window of steady playback (`Upscaler.SharpGuard`): mean GPU time > 80 % of the frame
    interval (fps × speed), or the 1.5.0 drop watchdog striking out, or Anime4K not buildable on this
    GPU (no ES 3 / half-float targets / shader error) → **Sharp drops to Balanced for the session**
    with a "Sharp → Balanced" toast. The saved choice stays Sharp; picking a mode again retries.
  - An Anime4K GL error only costs Sharp; it never consumes the FSR → Lite → off chain.
  - The stats dialog shows the measured GPU ms/frame and the timing source.
- **Upscale on export (2×)** in the clip editor (MP4 only): Real-ESRGAN AnimeVideo v3
  (`realesr-animevideov3-x2`, BSD-3-Clause) on ncnn Vulkan (BSD-3-Clause) via NDK/JNI
  (`app/src/main/cpp`, `RealEsrgan`, `ExportUpscaler`). Runs after Transformer's trim/crop/caption pass
  (which is written at 24 Mb/s to limit generation loss): MediaExtractor → MediaCodec decode →
  YUV→RGBA → tiled Real-ESRGAN (128 px tiles, 10 px prepadding) → RGBA→YUV → H.264 encode → MediaMuxer,
  audio samples copied as-is, rotation kept. Only one frame is in memory at a time. Progress
  ("Upscaling 2× · frame N · about M min left") and Cancel (stops at the next tile).
  The switch is disabled with the reason when the phone isn't arm64, the library can't load, or ncnn
  finds no Vulkan compute device; errors out clearly if the encoder can't take the 2× size.
  - Feature flag `BuildConfig.UPSCALE_EXPORT`: true only when the native library is built. Build ncnn
    with `tools/ncnn/build_ncnn_min.sh` (trimmed: only the layers this model needs; preferred) or fetch
    the full prebuilt with `tools/ncnn/fetch_ncnn.sh`; both land in git-ignored `third_party/`.
    `-Pulfur.noNativeUpscale=true` builds without it.
  - arm64-v8a only. APK size: 16.4 MB (1.7.0) → ~22.2 MB (+4.5 MB `libulfur_esrgan.so`, stored
    uncompressed/page-aligned; +1.25 MB model; +0.07 MB Anime4K shaders). The full prebuilt ncnn
    would make the .so 8.7 MB.
  - Licences: `assets/licenses/REALESRGAN_LICENSE.txt`, `NCNN_LICENSE.txt`, `GLSLANG_LICENSE.txt`.
  - Desktop check of the same native code: `tools/realesrgan-desktop/build.sh`.
- **Comparisons**: `design/upscale-1.8-compare-anime.png` (Hero, Blender Foundation, CC BY 4.0, scaled
  to 854×358) and `design/upscale-1.8-compare-liveaction.png` (Tears of Steel official 720p,
  1280×534, Blender Foundation, CC BY 3.0), rendered through the real shaders with
  `tools/upscale-compare/render18.py` + `compose18.py` (Mesa llvmpipe).
- Tests: `V18UpscaleTest` (routing, Off/Balanced unchanged, Sharp guard + session fallback in the player,
  HDR bypass, shader/pipeline sampler consistency, export sizes/estimates, editor availability).

## v1.9.0 (versionCode 14): Find duplicates

Library ⋮ → **Find duplicates…** (`DuplicatesActivity`, engine in `DuplicateFinder` / `DuplicateScan`).

- **Exact copies** (byte-identical), cheapest check first: group by file size (free, from MediaStore /
  the Private index) → partial hash of only the same-size videos (SHA-256 of size + first and last
  64 KiB, the existing `VideoKey`; ≤ 128 KiB read per file) → full SHA-256 only for partial-hash
  collisions, to confirm. Private videos reuse the plaintext SHA-256 and key already stored in their
  encrypted index, so they are usually not decrypted at all.
- **Look-alikes** (re-encodes, other resolutions, re-downloads): same duration ±1 s (sorted sliding
  window, no all-pairs compare) and a 64-bit dHash of the keyframe nearest 10 %, 50 % and 90 %
  (`MediaMetadataRetriever` `OPTION_CLOSEST_SYNC`, scaled to 64 px, then 9×8 luma). Frames are only
  decoded for videos that have a duration neighbour. A pair matches when at least two frames are
  informative in both (flat/black frames are ignored, so shared black intros don't match) and each
  differs by ≤ 10 of 64 bits. Videos under 2 s are exact-matched only.
- Groups are connected components of both relations ("Exact copies" or "Look alike"), biggest
  reclaimable size first. **Suggested keeper**: highest resolution, then largest file, then oldest
  (shown with a KEEP badge and the reason). The other copies start ticked; the user changes the ticks
  (per-group "Suggest", ⋮ Tick all suggestions / Untick all).
- **Never auto-deletes**: removal only after Move to Trash + a confirmation; a group with every copy
  ticked is refused ("Keep at least one copy"). Library videos go through the existing
  `DeleteVideosFlow` (Android 11+ system Trash, auto-approved with media management; Android 10 and
  older: its permanent-delete confirmation); Private videos go to the encrypted Private trash. One
  combined "Deleted N · Undo" restores both. If Android's prompt is declined, nothing is touched.
- **Include Private videos** (optional): asks for the fingerprint/screen lock when Private is locked;
  the screen is FLAG_SECURE while Private videos may be shown, and Private results disappear when
  Private locks again.
- **Background + cache**: one app-wide scan on a low-priority worker thread with phase progress
  ("Comparing pictures · 12 of 40") and Cancel; it keeps running if the screen is closed or rotated
  (not across process death). Hashes are cached in `files/dupes/hashes.tsv` (app-private), keyed by
  MediaStore id and invalidated by size/date/duration/name; gone videos are pruned; a cancelled scan
  keeps what it already read. Hashes of Private videos are kept in memory only.
- Tests: `V19DuplicateTest` (cheap-first ordering, unreadable files, ±1 s window, flat frames, merge of
  exact + look-alike, keeper order, dHash robustness across resolutions, cache reuse/invalidation/
  pruning and no Private hashes on disk, cancel, real hashing of a library file vs its Private copy,
  the screen: suggestions, last-copy guard, combined Trash + Undo, declined prompt, locking Private).
  Preview: `previews/v19_duplicates.png`.

## v1.9.1 (versionCode 15): crash safety net (hotfix)

Reported: 1.9.0 closed immediately on the first launch after updating from 1.7.0 / 1.6.0. Static review
and the 1.7 → 1.9 diff found no startup code change (the native Real-ESRGAN library is only loaded
lazily, guarded, from the clip editor; `minifyEnabled false`, so nothing is stripped). This release makes
startup defensive and makes the next crash reportable:

- `CrashGuard`: any uncaught crash is written to `files/last_crash.txt` (version, Android version,
  device, thread, ms since start, stack trace). The next launch shows it in a dialog with
  **Copy crash log** and **Share** (then it is deleted).
- **Safe mode**: two crashes in a row, each within 10 s of the process starting, make the next start a
  safe one: Upscale forced Off (the saved choice is kept), the Private encryption migration skipped, and
  a "Started in safe mode" banner in the library. Staying up for 10 s clears the counter.
- `Application.onCreate`: `VaultLock.install` and `VaultMigration.start` wrapped in try/catch; library
  Cast mini controller / Cast button, the default-player check and the Find duplicates entry guarded;
  `GpuFrameTimer` creation guarded in `UpscaleShaderProgram` (timing is diagnostics only).
- Tests: `V191CrashGuardTest`.

## v1.9.2 (versionCode 16): Android 17 beta launch crash fixed (hotfix)

Root cause (from the bug report of a Pixel on the Android 17 beta, SDK 37, DP11.260918.006):

```
java.lang.NullPointerException: Attempt to read from null array
  at com.google.android.gms.cast.framework.media.widget.MiniControllerFragment.zza(play-services-cast-framework@@22.3.1)
  at MiniControllerFragment.onCreateView
  at androidx.fragment.app.Fragment.performCreateView … FragmentActivity.onStart → MainActivity
```

Google's Cast mini controller fragment, added to the library (and Private) screen on every launch,
crashed while being created on the Android 17 beta. That happens in `onStart`, outside the 1.9.1
try/catch (which only covered queueing the fragment in `onCreate`), and before `onResume`, where 1.9.1
showed the crash dialog, so the dialog never appeared either.

- The Cast `MiniControllerFragment` is gone from every screen. Ulfur now has its own mini bar
  (`CastMiniBar`: title, "Casting to <TV>", play/pause, stop, tap for the TV controls) made of plain
  views on `RemoteMediaClient`. It is only inflated when a Cast session is connected and something is
  playing, and every Cast call in it is wrapped.
- `CastContext` is created lazily and asynchronously (`CastContext.getSharedInstance(context, executor)`)
  after the first frame. Until it is ready the Cast buttons stay hidden, then appear. Cast can no longer
  block or crash launch.
- Cast is skipped entirely in safe mode and while a crash report from the last launch is pending.
- The crash dialog is now also shown from `onCreate`, so a crash later in startup can't hide it.
- The TV controls screen gets an explicit `castControlButtons` array (no reliance on the widget defaults).
- Tests: `V192CastStartupTest` (Main, Private and Player start with Cast unavailable and create no Cast
  fragment; Cast not even initialised after a crash or in safe mode; the mini bar shows only while
  casting and Stop ends the session; no layout or source uses `MiniControllerFragment`).
