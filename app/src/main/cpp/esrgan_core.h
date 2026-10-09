// Ulfur Video 1.8.0 - "Upscale on export (2x)": Real-ESRGAN AnimeVideo v3 through ncnn.
// Portable core (no JNI) so it can be built and checked on a desktop too
// (tools/realesrgan-desktop). Model: realesr-animevideov3-x2 (Real-ESRGAN, BSD-3-Clause,
// see assets/licenses/REALESRGAN_LICENSE.txt). Runtime: Tencent ncnn (BSD-3-Clause,
// assets/licenses/NCNN_LICENSE.txt).
#pragma once

#include <atomic>
#include <cstdint>
#include <string>

#include "net.h"

namespace ulfur {

class Esrgan {
public:
    // useGpu: Vulkan compute (ncnn); tile: input tile edge in pixels.
    Esrgan(bool useGpu, int tile);
    ~Esrgan();

    int loadFromFiles(const char* param, const char* bin);
#if defined(__ANDROID__)
    int loadFromAssets(AAssetManager* mgr, const char* param, const char* bin);
#endif

    // RGBA8888 in (w x h, stride inStride bytes) -> RGBA8888 out (2w x 2h, stride outStride).
    // Streams tile by tile (prepadding 10 px, edge-replicated), so memory stays bounded.
    // Returns 0, -1 on error, 1 when cancelled.
    int process(const uint8_t* in, int w, int h, int inStride, uint8_t* out, int outStride);

    void cancel() { cancelled_.store(true); }
    void resetCancel() { cancelled_.store(false); }

    static constexpr int SCALE = 2;
    static constexpr int PREPADDING = 10;

private:
    ncnn::Net net_;
    bool gpu_;
    int tile_;
    std::atomic<bool> cancelled_{false};
};

// Vulkan compute device name, or empty when ncnn finds no usable GPU.
std::string vulkanDeviceName();

// Colour conversion helpers for the MediaCodec <-> RGBA bridge (BT.601 / BT.709, limited range).
void yuv420ToRgba(const uint8_t* y, int yRowStride, int yPixelStride,
                  const uint8_t* u, const uint8_t* v, int uvRowStride, int uvPixelStride,
                  int w, int h, bool bt709, uint8_t* rgba, int rgbaStride);
void rgbaToYuv420(const uint8_t* rgba, int rgbaStride, int w, int h, bool bt709,
                  uint8_t* y, int yRowStride, int yPixelStride,
                  uint8_t* u, uint8_t* v, int uvRowStride, int uvPixelStride);

}  // namespace ulfur
