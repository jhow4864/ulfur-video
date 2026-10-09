#!/bin/sh
# 1.8.0: builds a size-trimmed ncnn (arm64-v8a, Vulkan) containing only the layers Real-ESRGAN
# AnimeVideo v3 needs (plus the helpers ncnn creates internally). Cuts libulfur_esrgan.so by about
# half compared with the full prebuilt release. Output: third_party/ncnn-20260526-android-vulkan-min
# Needs: Android NDK 27.2.12479018 + CMake 3.22.1 from the SDK (ANDROID_HOME), curl, unzip.
set -e
VER=20260526
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK="$ROOT/third_party/build-ncnn"
OUT="$ROOT/third_party/ncnn-$VER-android-vulkan-min"
NDK="$ANDROID_HOME/ndk/27.2.12479018"
CMAKE="$ANDROID_HOME/cmake/3.22.1/bin/cmake"
mkdir -p "$WORK"
cd "$WORK"
if [ ! -d src ]; then
  curl -sSLO "https://github.com/Tencent/ncnn/releases/download/$VER/ncnn-$VER-full-source.zip"
  echo "$(cat "$ROOT/tools/ncnn/ncnn-$VER-full-source.zip.sha256")  ncnn-$VER-full-source.zip" | sha256sum -c -
  unzip -q "ncnn-$VER-full-source.zip" -d src
fi
KEEP="Input Split Convolution PReLU PixelShuffle Interp BinaryOp Padding Packing Cast Crop Scale Bias Flatten"
OFF=""
for L in $(sed -n 's/^ncnn_add_layer(\([A-Za-z0-9]*\).*/\1/p' src/src/CMakeLists.txt); do
  case " $KEEP " in *" $L "*) ;; *) OFF="$OFF -DWITH_LAYER_$(echo $L | tr 'A-Z' 'a-z')=OFF";; esac
done
"$CMAKE" -S src -B build-arm64 -G Ninja -DCMAKE_MAKE_PROGRAM="$ANDROID_HOME/cmake/3.22.1/bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$OUT/arm64-v8a" \
  -DNCNN_VULKAN=ON -DNCNN_SHARED_LIB=OFF -DNCNN_BUILD_TOOLS=OFF -DNCNN_BUILD_EXAMPLES=OFF \
  -DNCNN_BUILD_BENCHMARK=OFF -DNCNN_BUILD_TESTS=OFF -DNCNN_INT8=OFF -DNCNN_BF16=OFF \
  -DNCNN_PIXEL_ROTATE=OFF -DNCNN_PIXEL_AFFINE=OFF -DNCNN_PIXEL_DRAWING=OFF $OFF > cmake.log
"$CMAKE" --build build-arm64 -j"$(nproc)" > build.log
"$CMAKE" --install build-arm64 > install.log
echo "ncnn (trimmed) ready: $OUT"
