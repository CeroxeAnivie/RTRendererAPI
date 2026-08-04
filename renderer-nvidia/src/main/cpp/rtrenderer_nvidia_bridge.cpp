#include <jni.h>

#include <cstdint>
#include <exception>

#include "nrd_session.hpp"
#include "rtxmu_session.hpp"

namespace {

constexpr jint kAbiVersion = 4;
constexpr jint kNrdCapability = 1 << 3;
constexpr jint kRtxmuCapability = 1 << 4;
constexpr const char* kDiagnostic =
        "NRD REBLUR_DIFFUSE_SPECULAR and NVIDIA RTXMU v1.4 are statically linked; "
        "device-bound execution remains gated by native session creation and GPU work completion";

void throwIllegalState(JNIEnv* environment, const char* message) {
    jclass type = environment->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) environment->ThrowNew(type, message);
}

}

extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeAbiVersion(JNIEnv*, jclass) {
    return kAbiVersion;
}

extern "C" JNIEXPORT jint JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeCapabilityMask(JNIEnv*, jclass) {
    // This is a build-time SDK capability. Device-specific validation remains in nativeOpen,
    // where NRI binds the renderer's actual Vulkan instance, physical device and queue family.
    return kNrdCapability | kRtxmuCapability;
}

extern "C" JNIEXPORT jstring JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeDiagnostic(JNIEnv* environment, jclass) {
    return environment->NewStringUTF(kDiagnostic);
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeOpenNrd(
        JNIEnv* environment,
        jclass,
        jlong instance,
        jlong physicalDevice,
        jlong device,
        jint queueFamilyIndex
) {
    if (instance == 0 || physicalDevice == 0 || device == 0 || queueFamilyIndex < 0) {
        throwIllegalState(environment, "NRD received an invalid native open request");
        return 0;
    }
    try {
        return reinterpret_cast<jlong>(new rtrenderer::nvidia::NrdSession(
                static_cast<std::uint64_t>(instance),
                static_cast<std::uint64_t>(physicalDevice),
                static_cast<std::uint64_t>(device),
                static_cast<std::uint32_t>(queueFamilyIndex)
        ));
    } catch (const std::exception& error) {
        throwIllegalState(environment, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeCloseNrd(JNIEnv*, jclass, jlong session) {
    delete reinterpret_cast<rtrenderer::nvidia::NrdSession*>(session);
}

extern "C" JNIEXPORT jlong JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeOpenRtxmu(
        JNIEnv* environment,
        jclass,
        jlong instance,
        jlong physicalDevice,
        jlong device
) {
    if (instance == 0 || physicalDevice == 0 || device == 0) {
        throwIllegalState(environment, "RTXMU received an invalid native open request");
        return 0;
    }
    try {
        return reinterpret_cast<jlong>(new rtrenderer::nvidia::RtxmuSession(
                static_cast<std::uint64_t>(instance),
                static_cast<std::uint64_t>(physicalDevice),
                static_cast<std::uint64_t>(device)
        ));
    } catch (const std::exception& error) {
        throwIllegalState(environment, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeCloseRtxmu(JNIEnv*, jclass, jlong session) {
    delete reinterpret_cast<rtrenderer::nvidia::RtxmuSession*>(session);
}
