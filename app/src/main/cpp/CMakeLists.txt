# Ulfur Video 1.8.0 - native "Upscale on export (2x)" (Real-ESRGAN AnimeVideo v3 on ncnn + Vulkan).
# Built for arm64-v8a only. NCNN_ROOT = an unpacked ncnn-<ver>-android-vulkan release
# (tools/ncnn/fetch_ncnn.sh puts it in third_party/; app/build.gradle passes the path).
cmake_minimum_required(VERSION 3.22.1)
project(ulfur_esrgan CXX)

if(NOT NCNN_ROOT)
    message(FATAL_ERROR "NCNN_ROOT not set (run tools/ncnn/fetch_ncnn.sh)")
endif()
set(ncnn_DIR ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(ulfur_esrgan SHARED esrgan_core.cpp realesr_jni.cpp)
target_compile_options(ulfur_esrgan PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(ulfur_esrgan PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(ulfur_esrgan ncnn android log)
