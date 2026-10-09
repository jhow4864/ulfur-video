#!/bin/sh
# Downloads the pinned prebuilt ncnn (Tencent, BSD-3-Clause) used by the native "Upscale on export"
# library into third_party/ (git-ignored); tools/ncnn/build_ncnn_min.sh builds the smaller trimmed one
# that app/build.gradle prefers. Without either the app still builds, but the export option is
# compiled out (BuildConfig.UPSCALE_EXPORT = false).
set -e
VER=20260526
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST="$ROOT/third_party"
mkdir -p "$DEST"
cd "$DEST"
if [ ! -d "ncnn-$VER-android-vulkan" ]; then
  curl -sSLO "https://github.com/Tencent/ncnn/releases/download/$VER/ncnn-$VER-android-vulkan.zip"
  echo "$(cat "$ROOT/tools/ncnn/ncnn-$VER-android-vulkan.zip.sha256")  ncnn-$VER-android-vulkan.zip" | sha256sum -c -
  unzip -q "ncnn-$VER-android-vulkan.zip" "ncnn-$VER-android-vulkan/arm64-v8a/*"
  rm "ncnn-$VER-android-vulkan.zip"
fi
echo "ncnn ready: $DEST/ncnn-$VER-android-vulkan"
