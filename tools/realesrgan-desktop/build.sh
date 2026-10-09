#!/bin/sh
# Builds the desktop check against the prebuilt ncnn release for Ubuntu 24.04 (CPU + Vulkan).
set -e
NCNN=${NCNN:-/workspace/ulfur-video/ncnn/ncnn-20260526-ubuntu-2404}
SRC=$(dirname "$0")/../../app/src/main/cpp
${CXX:-g++-14} -O2 -std=c++17 -fopenmp -I"$NCNN/include/ncnn" -I"$SRC" "$SRC/esrgan_core.cpp" "$(dirname "$0")/test_main.cpp" \
  -L"$NCNN/lib" -lncnn -lglslang -lSPIRV -lMachineIndependent -lOSDependent -lGenericCodeGen \
  -lglslang-default-resource-limits -lpthread -ldl -o esrgan_test
