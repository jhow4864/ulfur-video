// Desktop check of app/src/main/cpp/esrgan_core.cpp (same code the app runs), CPU path.
//   build.sh, then: ./esrgan_test in.rgba W H out.rgba models/realesr-animevideov3-x2.{param,bin}
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <vector>
#include "esrgan_core.h"

int main(int argc, char** argv) {
    if (argc < 7) { std::fprintf(stderr, "usage: in.rgba W H out.rgba param bin\n"); return 2; }
    int w = std::atoi(argv[2]), h = std::atoi(argv[3]);
    std::vector<uint8_t> in((size_t) w * h * 4), out((size_t) w * h * 16);
    FILE* f = std::fopen(argv[1], "rb");
    if (!f || std::fread(in.data(), 1, in.size(), f) != in.size()) return 3;
    std::fclose(f);
    std::string gpu = ulfur::vulkanDeviceName();
    std::printf("vulkan device: %s\n", gpu.empty() ? "(none - CPU path)" : gpu.c_str());
    ulfur::Esrgan e(!gpu.empty(), 128);
    if (e.loadFromFiles(argv[5], argv[6]) != 0) { std::fprintf(stderr, "model load failed\n"); return 4; }
    auto t0 = std::chrono::steady_clock::now();
    int r = e.process(in.data(), w, h, w * 4, out.data(), w * 8);
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    std::printf("process: %d, %dx%d -> %dx%d in %.0f ms\n", r, w, h, w * 2, h * 2, ms);
    // YUV round trip check (decoder/encoder bridge)
    std::vector<uint8_t> Y((size_t) w * h), U((size_t) w * h / 4), V((size_t) w * h / 4), back((size_t) w * h * 4);
    ulfur::rgbaToYuv420(in.data(), w * 4, w, h, true, Y.data(), w, 1, U.data(), V.data(), w / 2, 1);
    ulfur::yuv420ToRgba(Y.data(), w, 1, U.data(), V.data(), w / 2, 1, w, h, true, back.data(), w * 4);
    double err = 0; for (size_t i = 0; i < in.size(); i++) if (i % 4 != 3) err += std::abs(in[i] - back[i]);
    std::printf("yuv420 round trip mean abs error: %.2f (chroma subsampling included)\n", err / (w * h * 3));
    f = std::fopen(argv[4], "wb"); std::fwrite(out.data(), 1, out.size(), f); std::fclose(f);
    return r;
}
