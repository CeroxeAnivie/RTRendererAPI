#include <jni.h>

#include <cstdint>
#include <stdexcept>

#include "nrd_jni.hpp"
#include "nrd_session.hpp"
#include "rtxmu_jni.hpp"
#include "rtxmu_session.hpp"

namespace {

using rtrenderer::nvidia::NrdFrameConstants;
using rtrenderer::nvidia::NrdSession;
using rtrenderer::nvidia::RtxmuSession;
using rtrenderer::nvidia::readNrdFrameConstants;
using rtrenderer::nvidia::recordRtxmuBuildFromJava;
using rtrenderer::nvidia::recordRtxmuCompactionFromJava;

void throwIllegalState(JNIEnv* environment, const char* message) {
    jclass type = environment->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) environment->ThrowNew(type, message);
}

}

extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeRtxmuRecordBuild(
        JNIEnv* env,
        jclass,
        jlong session,
        jlong commandBuffer,
        jlongArray positionAddresses,
        jlongArray indexAddresses,
        jintArray vertexCounts,
        jintArray primitiveCounts,
        jbooleanArray opaque
) {
    if (session == 0 || commandBuffer == 0 || positionAddresses == nullptr
            || indexAddresses == nullptr || vertexCounts == nullptr
            || primitiveCounts == nullptr || opaque == nullptr) {
        throwIllegalState(env, "RTXMU build received null session, command buffer, or geometry arrays");
        return nullptr;
    }
    try {
        return recordRtxmuBuildFromJava(
                env,
                *reinterpret_cast<RtxmuSession*>(session),
                static_cast<std::uint64_t>(commandBuffer),
                positionAddresses,
                indexAddresses,
                vertexCounts,
                primitiveCounts,
                opaque
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeRtxmuRecordCompaction(
        JNIEnv* env, jclass, jlong session, jlong commandBuffer, jlong accelerationStructureId
) {
    if (session == 0 || commandBuffer == 0 || accelerationStructureId <= 0) {
        throwIllegalState(env, "RTXMU compaction received invalid ownership metadata");
        return nullptr;
    }
    try {
        return recordRtxmuCompactionFromJava(
                env,
                *reinterpret_cast<RtxmuSession*>(session),
                static_cast<std::uint64_t>(commandBuffer),
                static_cast<std::uint64_t>(accelerationStructureId)
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeRtxmuGarbageCollect(
        JNIEnv* env, jclass, jlong session, jlong accelerationStructureId
) {
    try {
        if (session == 0 || accelerationStructureId <= 0) {
            throw std::invalid_argument("RTXMU garbage collection received invalid ownership metadata");
        }
        reinterpret_cast<RtxmuSession*>(session)->garbageCollect(
                static_cast<std::uint64_t>(accelerationStructureId)
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeRtxmuRemove(
        JNIEnv* env, jclass, jlong session, jlong accelerationStructureId
) {
    try {
        if (session == 0 || accelerationStructureId <= 0) {
            throw std::invalid_argument("RTXMU release received invalid ownership metadata");
        }
        reinterpret_cast<RtxmuSession*>(session)->remove(
                static_cast<std::uint64_t>(accelerationStructureId)
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_top_ceroxe_rt_renderer_nvidia_NvidiaNativeBridge_nativeRecordPostTrace(
        JNIEnv* env, jclass, jlong session, jlong commandBuffer, jlong, jlong, jlong, jlong,
        jlong normalRoughness, jlong viewZ, jlong motionVectors, jlong diffuseInput,
        jlong specularInput, jlong diffuseOutput, jlong specularOutput, jboolean denoisingActive,
        jint width, jint height, jobject constants
) {
    if (session == 0 || !denoisingActive) {
        throwIllegalState(env, "NRD post-trace recording requires an active session and resources");
        return;
    }
    try {
        NrdFrameConstants frameConstants = readNrdFrameConstants(env, constants);
        reinterpret_cast<NrdSession*>(session)->record(
                static_cast<std::uint64_t>(commandBuffer),
                static_cast<std::uint64_t>(normalRoughness),
                static_cast<std::uint64_t>(viewZ),
                static_cast<std::uint64_t>(motionVectors),
                static_cast<std::uint64_t>(diffuseInput),
                static_cast<std::uint64_t>(specularInput),
                static_cast<std::uint64_t>(diffuseOutput),
                static_cast<std::uint64_t>(specularOutput),
                frameConstants,
                width,
                height
        );
    } catch (const std::exception& error) {
        throwIllegalState(env, error.what());
    }
}
