// Ulfur Video 1.8.0 - Real-ESRGAN AnimeVideo v3 (x2) tile runner on ncnn. See esrgan_core.h.
#include "esrgan_core.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "gpu.h"

namespace ulfur {

static inline uint8_t clamp8(float v) {
    return (uint8_t) (v < 0.f ? 0.f : (v > 255.f ? 255.f : v + 0.5f));
}

Esrgan::Esrgan(bool useGpu, int tile) : gpu_(useGpu), tile_(tile) {
    net_.opt.use_vulkan_compute = useGpu;
    net_.opt.use_fp16_packed = true;
    net_.opt.use_fp16_storage = true;
    net_.opt.use_fp16_arithmetic = false;  // keeps colours stable on Mali/PowerVR
    net_.opt.num_threads = 4;
}

Esrgan::~Esrgan() { net_.clear(); }

int Esrgan::loadFromFiles(const char* param, const char* bin) {
    if (net_.load_param(param) != 0) return -1;
    if (net_.load_model(bin) != 0) return -1;
    return 0;
}

#if defined(__ANDROID__)
int Esrgan::loadFromAssets(AAssetManager* mgr, const char* param, const char* bin) {
    if (net_.load_param(mgr, param) != 0) return -1;
    if (net_.load_model(mgr, bin) != 0) return -1;
    return 0;
}
#endif

int Esrgan::process(const uint8_t* in, int w, int h, int inStride, uint8_t* out, int outStride) {
    const int P = PREPADDING;
    const int S = SCALE;
    std::vector<uint8_t> tileRgb;
    for (int ty = 0; ty < h; ty += tile_) {
        for (int tx = 0; tx < w; tx += tile_) {
            if (cancelled_.load()) return 1;
            const int tw = std::min(tile_, w - tx);
            const int th = std::min(tile_, h - ty);
            // padded tile, edge-replicated (same as realesrgan-ncnn-vulkan's prepadding)
            const int x0 = tx - P, y0 = ty - P;
            const int pw = tw + 2 * P, ph = th + 2 * P;
            tileRgb.resize((size_t) pw * ph * 3);
            for (int yy = 0; yy < ph; yy++) {
                const int sy = std::min(std::max(y0 + yy, 0), h - 1);
                const uint8_t* row = in + (size_t) sy * inStride;
                uint8_t* dst = &tileRgb[(size_t) yy * pw * 3];
                for (int xx = 0; xx < pw; xx++) {
                    const int sx = std::min(std::max(x0 + xx, 0), w - 1);
                    dst[xx * 3 + 0] = row[sx * 4 + 0];
                    dst[xx * 3 + 1] = row[sx * 4 + 1];
                    dst[xx * 3 + 2] = row[sx * 4 + 2];
                }
            }
            ncnn::Mat inMat = ncnn::Mat::from_pixels(tileRgb.data(), ncnn::Mat::PIXEL_RGB, pw, ph);
            const float norm[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
            inMat.substract_mean_normalize(0, norm);

            ncnn::Extractor ex = net_.create_extractor();
            ex.input("data", inMat);
            ncnn::Mat outMat;
            if (ex.extract("output", outMat) != 0) return -1;
            if (outMat.w != pw * S || outMat.h != ph * S || outMat.c != 3) return -1;

            // copy the centre (drop the prepadding) into the output frame
            const float* r = outMat.channel(0);
            const float* g = outMat.channel(1);
            const float* b = outMat.channel(2);
            const int ow = outMat.w;
            for (int yy = 0; yy < th * S; yy++) {
                const int sy = yy + P * S;
                uint8_t* dst = out + (size_t) (ty * S + yy) * outStride + (size_t) tx * S * 4;
                for (int xx = 0; xx < tw * S; xx++) {
                    const int si = sy * ow + xx + P * S;
                    dst[xx * 4 + 0] = clamp8(r[si] * 255.f);
                    dst[xx * 4 + 1] = clamp8(g[si] * 255.f);
                    dst[xx * 4 + 2] = clamp8(b[si] * 255.f);
                    dst[xx * 4 + 3] = 255;
                }
            }
        }
    }
    return 0;
}

std::string vulkanDeviceName() {
#if NCNN_VULKAN
    ncnn::create_gpu_instance();
    if (ncnn::get_gpu_count() <= 0) return std::string();
    const ncnn::GpuInfo& info = ncnn::get_gpu_info(ncnn::get_default_gpu_index());
    return std::string(info.device_name());
#else
    return std::string();
#endif
}

// ----------------------------------------------------------------------------- colour

struct Coeffs { float kr, kb; };
static inline Coeffs coeffs(bool bt709) { return bt709 ? Coeffs{0.2126f, 0.0722f} : Coeffs{0.299f, 0.114f}; }

void yuv420ToRgba(const uint8_t* y, int yRowStride, int yPixelStride,
                  const uint8_t* u, const uint8_t* v, int uvRowStride, int uvPixelStride,
                  int w, int h, bool bt709, uint8_t* rgba, int rgbaStride) {
    const Coeffs c = coeffs(bt709);
    const float kg = 1.f - c.kr - c.kb;
    const float crR = 2.f * (1.f - c.kr), cbB = 2.f * (1.f - c.kb);
    const float cbG = -2.f * c.kb * (1.f - c.kb) / kg, crG = -2.f * c.kr * (1.f - c.kr) / kg;
    const float ys = 255.f / 219.f, cs = 255.f / 224.f;
    for (int j = 0; j < h; j++) {
        const uint8_t* yr = y + (size_t) j * yRowStride;
        const uint8_t* ur = u + (size_t) (j / 2) * uvRowStride;
        const uint8_t* vr = v + (size_t) (j / 2) * uvRowStride;
        uint8_t* o = rgba + (size_t) j * rgbaStride;
        for (int i = 0; i < w; i++) {
            const float Y = (yr[i * yPixelStride] - 16.f) * ys;
            const float Cb = (ur[(i / 2) * uvPixelStride] - 128.f) * cs;
            const float Cr = (vr[(i / 2) * uvPixelStride] - 128.f) * cs;
            o[i * 4 + 0] = clamp8(Y + crR * Cr);
            o[i * 4 + 1] = clamp8(Y + cbG * Cb + crG * Cr);
            o[i * 4 + 2] = clamp8(Y + cbB * Cb);
            o[i * 4 + 3] = 255;
        }
    }
}

void rgbaToYuv420(const uint8_t* rgba, int rgbaStride, int w, int h, bool bt709,
                  uint8_t* y, int yRowStride, int yPixelStride,
                  uint8_t* u, uint8_t* v, int uvRowStride, int uvPixelStride) {
    const Coeffs c = coeffs(bt709);
    const float kg = 1.f - c.kr - c.kb;
    for (int j = 0; j < h; j++) {
        const uint8_t* s = rgba + (size_t) j * rgbaStride;
        uint8_t* yr = y + (size_t) j * yRowStride;
        for (int i = 0; i < w; i++) {
            const float L = c.kr * s[i * 4] + kg * s[i * 4 + 1] + c.kb * s[i * 4 + 2];
            yr[i * yPixelStride] = clamp8(16.f + L * 219.f / 255.f);
        }
    }
    for (int j = 0; j < h / 2; j++) {
        const uint8_t* s0 = rgba + (size_t) (2 * j) * rgbaStride;
        const uint8_t* s1 = s0 + rgbaStride;
        uint8_t* ur = u + (size_t) j * uvRowStride;
        uint8_t* vr = v + (size_t) j * uvRowStride;
        for (int i = 0; i < w / 2; i++) {
            float r = 0, g = 0, b = 0;
            for (int k = 0; k < 2; k++) {
                const uint8_t* p0 = s0 + (2 * i + k) * 4;
                const uint8_t* p1 = s1 + (2 * i + k) * 4;
                r += p0[0] + p1[0]; g += p0[1] + p1[1]; b += p0[2] + p1[2];
            }
            r *= 0.25f; g *= 0.25f; b *= 0.25f;
            const float L = c.kr * r + kg * g + c.kb * b;
            const float Cb = (b - L) / (2.f * (1.f - c.kb));
            const float Cr = (r - L) / (2.f * (1.f - c.kr));
            ur[i * uvPixelStride] = clamp8(128.f + Cb * 224.f / 255.f);
            vr[i * uvPixelStride] = clamp8(128.f + Cr * 224.f / 255.f);
        }
    }
}

}  // namespace ulfur
