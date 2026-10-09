// Ulfur Video 1.8.0 - JNI bridge for RealEsrgan.java ("Upscale on export (2x)").
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include "esrgan_core.h"

#define TAG "UlfurEsrgan"

static uint8_t* addr(JNIEnv* env, jobject buf, jint offset) {
    auto* p = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
    return p == nullptr ? nullptr : p + offset;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_jamh_videoplayer_RealEsrgan_nativeVulkanDevice(JNIEnv* env, jclass) {
    std::string name = ulfur::vulkanDeviceName();
    if (name.empty()) return nullptr;
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_jamh_videoplayer_RealEsrgan_nativeCreate(JNIEnv* env, jclass, jobject assets, jstring param,
                                                  jstring bin, jboolean gpu, jint tile) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assets);
    const char* p = env->GetStringUTFChars(param, nullptr);
    const char* b = env->GetStringUTFChars(bin, nullptr);
    auto* e = new ulfur::Esrgan(gpu, tile);
    int r = e->loadFromAssets(mgr, p, b);
    env->ReleaseStringUTFChars(param, p);
    env->ReleaseStringUTFChars(bin, b);
    if (r != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "model load failed");
        delete e;
        return 0;
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jint JNICALL
Java_com_jamh_videoplayer_RealEsrgan_nativeProcess(JNIEnv* env, jclass, jlong h, jobject in, jint w,
                                                   jint hgt, jint inStride, jobject out, jint outStride) {
    auto* e = reinterpret_cast<ulfur::Esrgan*>(h);
    uint8_t* src = addr(env, in, 0);
    uint8_t* dst = addr(env, out, 0);
    if (e == nullptr || src == nullptr || dst == nullptr) return -1;
    return e->process(src, w, hgt, inStride, dst, outStride);
}

JNIEXPORT void JNICALL
Java_com_jamh_videoplayer_RealEsrgan_nativeCancel(JNIEnv*, jclass, jlong h) {
    auto* e = reinterpret_cast<ulfur::Esrgan*>(h);
    if (e != nullptr) e->cancel();
}

JNIEXPORT void JNICALL
Java_com_jamh_videoplayer_RealEsrgan_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<ulfur::Esrgan*>(h);
}

JNIEXPORT void JNICALL
Java_com_jamh_videoplayer_RealEsrgan_nativeYuvToRgba(JNIEnv* env, jclass,
        jobject y, jint yOff, jint yRow, jint yPix, jobject u, jint uOff, jobject v, jint vOff,
        jint uvRow, jint uvPix, jint w, jint h, jboolean bt709, jobject rgba, jint stride) {
    ulfur::yuv420ToRgba(addr(env, y, yOff), yRow, yPix, addr(env, u, uOff), addr(env, v, vOff), uvRow, uvPix,
                        w, h, bt709, addr(env, rgba, 0), stride);
}

JNIEXPORT void JNICALL
Java_com_jamh_videoplayer_RealEsrgan_nativeRgbaToYuv(JNIEnv* env, jclass, jobject rgba, jint stride,
        jint w, jint h, jboolean bt709, jobject y, jint yOff, jint yRow, jint yPix,
        jobject u, jint uOff, jobject v, jint vOff, jint uvRow, jint uvPix) {
    ulfur::rgbaToYuv420(addr(env, rgba, 0), stride, w, h, bt709, addr(env, y, yOff), yRow, yPix,
                        addr(env, u, uOff), addr(env, v, vOff), uvRow, uvPix);
}

}  // extern "C"
