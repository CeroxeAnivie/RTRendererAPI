#include "streamline_jni.hpp"

#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string>

namespace rtrenderer::nvidia {
namespace {

jmethodID method(JNIEnv* environment, jclass type, const char* name, const char* signature) {
    jmethodID result = environment->GetMethodID(type, name, signature);
    if (result == nullptr) throw std::runtime_error(std::string("missing JNI method ") + name);
    return result;
}

void requireNoJavaException(JNIEnv* environment, const char* operation) {
    if (environment->ExceptionCheck()) {
        throw std::runtime_error(std::string("Java exception while reading Streamline ") + operation);
    }
}

StreamlineImage readImage(JNIEnv* environment, jobject image) {
    if (image == nullptr) throw std::invalid_argument("null Streamline image metadata");
    jclass type = environment->GetObjectClass(image);
    if (type == nullptr) throw std::runtime_error("failed to resolve Streamline image metadata type");
    StreamlineImage result{
            static_cast<std::uint64_t>(environment->CallLongMethod(image, method(environment, type, "handle", "()J"))),
            static_cast<std::uint64_t>(environment->CallLongMethod(image, method(environment, type, "memory", "()J"))),
            static_cast<std::uint64_t>(environment->CallLongMethod(image, method(environment, type, "view", "()J"))),
            environment->CallIntMethod(image, method(environment, type, "format", "()I")),
            environment->CallIntMethod(image, method(environment, type, "width", "()I")),
            environment->CallIntMethod(image, method(environment, type, "height", "()I")),
            environment->CallIntMethod(image, method(environment, type, "usageFlags", "()I"))
    };
    requireNoJavaException(environment, "image metadata");
    return result;
}

std::array<float, 16> readMatrix(JNIEnv* environment, jobject source, jclass type, const char* name) {
    auto array = static_cast<jfloatArray>(environment->CallObjectMethod(source, method(environment, type, name, "()[F")));
    requireNoJavaException(environment, name);
    if (array == nullptr || environment->GetArrayLength(array) != 16) {
        throw std::invalid_argument(std::string("invalid temporal matrix ") + name);
    }
    std::array<float, 16> result{};
    environment->GetFloatArrayRegion(array, 0, 16, result.data());
    requireNoJavaException(environment, name);
    return result;
}

std::array<float, 3> readVector(JNIEnv* environment, jobject source, jclass type, const char* name) {
    auto array = static_cast<jfloatArray>(environment->CallObjectMethod(source, method(environment, type, name, "()[F")));
    requireNoJavaException(environment, name);
    if (array == nullptr || environment->GetArrayLength(array) != 3) {
        throw std::invalid_argument(std::string("invalid Streamline vector ") + name);
    }
    std::array<float, 3> result{};
    environment->GetFloatArrayRegion(array, 0, 3, result.data());
    requireNoJavaException(environment, name);
    return result;
}

void readTemporalConstants(JNIEnv* environment, jobject constants, StreamlineFrame& result) {
    jclass type = environment->GetObjectClass(constants);
    if (type == nullptr) throw std::runtime_error("failed to resolve Streamline frame constants type");
    result.viewToClip = readMatrix(environment, constants, type, "cameraViewToClip");
    result.clipToView = readMatrix(environment, constants, type, "clipToCameraView");
    result.clipToPrevious = readMatrix(environment, constants, type, "clipToPrevClip");
    result.previousToClip = readMatrix(environment, constants, type, "prevClipToClip");
    result.position = readVector(environment, constants, type, "cameraPosition");
    result.up = readVector(environment, constants, type, "cameraUp");
    result.right = readVector(environment, constants, type, "cameraRight");
    result.forward = readVector(environment, constants, type, "cameraForward");
    result.jitterX = environment->CallFloatMethod(constants, method(environment, type, "currentJitterX", "()F"));
    result.jitterY = environment->CallFloatMethod(constants, method(environment, type, "currentJitterY", "()F"));
    result.motionScaleX = environment->CallFloatMethod(constants, method(environment, type, "motionScaleX", "()F"));
    result.motionScaleY = environment->CallFloatMethod(constants, method(environment, type, "motionScaleY", "()F"));
    result.nearPlane = environment->CallFloatMethod(constants, method(environment, type, "nearPlane", "()F"));
    result.farPlane = environment->CallFloatMethod(constants, method(environment, type, "farPlane", "()F"));
    result.fovRadians = environment->CallFloatMethod(constants, method(environment, type, "fovRadians", "()F"));
    result.aspectRatio = environment->CallFloatMethod(constants, method(environment, type, "aspectRatio", "()F"));
    result.reset = environment->CallBooleanMethod(constants, method(environment, type, "reset", "()Z")) == JNI_TRUE;
    requireNoJavaException(environment, "temporal constants");

    auto finiteArray = [](const auto& values) {
        return std::all_of(values.begin(), values.end(), [](float value) { return std::isfinite(value); });
    };
    if (!finiteArray(result.viewToClip) || !finiteArray(result.clipToView)
            || !finiteArray(result.clipToPrevious) || !finiteArray(result.previousToClip)
            || !finiteArray(result.position) || !finiteArray(result.up)
            || !finiteArray(result.right) || !finiteArray(result.forward)) {
        throw std::invalid_argument("Streamline temporal matrices and camera vectors must be finite");
    }
    auto validJitter = [](float value) {
        return std::isfinite(value) && value >= -0.5f && value <= 0.5f;
    };
    if (!validJitter(result.jitterX) || !validJitter(result.jitterY)) {
        throw std::invalid_argument("Streamline camera jitter must be finite and in [-0.5, 0.5]");
    }
    if (!std::isfinite(result.motionScaleX) || result.motionScaleX <= 0.0f
            || !std::isfinite(result.motionScaleY) || result.motionScaleY <= 0.0f) {
        throw std::invalid_argument("Streamline motion-vector scale must be finite and positive");
    }
    if (!std::isfinite(result.nearPlane) || result.nearPlane <= 0.0f
            || !std::isfinite(result.farPlane) || result.farPlane <= result.nearPlane) {
        throw std::invalid_argument("Streamline projection planes must be finite and ordered");
    }
    if (!std::isfinite(result.fovRadians) || result.fovRadians <= 0.0f
            || !std::isfinite(result.aspectRatio) || result.aspectRatio <= 0.0f) {
        throw std::invalid_argument("Streamline FOV and aspect ratio must be finite and positive");
    }
}

}

StreamlineFrame readStreamlineFrame(
        JNIEnv* environment,
        jobject resources,
        jobject constants,
        jlong sequence
) {
    if (environment == nullptr || resources == nullptr || constants == nullptr || sequence < 0
            || static_cast<std::uint64_t>(sequence) > std::numeric_limits<std::uint32_t>::max()) {
        throw std::invalid_argument("invalid Streamline frame JNI arguments");
    }
    jclass resourcesType = environment->GetObjectClass(resources);
    if (resourcesType == nullptr) throw std::runtime_error("failed to resolve Streamline resource contract type");
    const char* imageSignature =
            "()Ltop/ceroxe/rt/renderer/feature/VulkanFrameReconstructionResourceContract$Image;";
    jobject input = environment->CallObjectMethod(
            resources, method(environment, resourcesType, "inputColor", imageSignature)
    );
    jobject output = environment->CallObjectMethod(
            resources, method(environment, resourcesType, "outputColor", imageSignature)
    );
    jobject depth = environment->CallObjectMethod(
            resources, method(environment, resourcesType, "depth", imageSignature)
    );
    jobject motion = environment->CallObjectMethod(
            resources, method(environment, resourcesType, "motionVectors", imageSignature)
    );
    jobject exposure = environment->CallObjectMethod(
            resources, method(environment, resourcesType, "exposure", imageSignature)
    );
    requireNoJavaException(environment, "frame resources");

    StreamlineFrame result{};
    result.inputColor = readImage(environment, input);
    result.outputColor = readImage(environment, output);
    result.depth = readImage(environment, depth);
    result.motion = readImage(environment, motion);
    result.exposure = readImage(environment, exposure);
    readTemporalConstants(environment, constants, result);
    result.frameIndex = static_cast<std::uint32_t>(sequence);
    return result;
}

StreamlineFrame readStreamlineFrameGeneration(
        JNIEnv* environment,
        jobject hudless,
        jobject depth,
        jobject motion,
        jobject constants,
        jlong sequence
) {
    if (environment == nullptr || hudless == nullptr || depth == nullptr || motion == nullptr
            || constants == nullptr || sequence < 0
            || static_cast<std::uint64_t>(sequence) > std::numeric_limits<std::uint32_t>::max()) {
        throw std::invalid_argument("invalid Streamline frame-generation JNI arguments");
    }
    StreamlineFrame result{};
    result.inputColor = readImage(environment, hudless);
    result.depth = readImage(environment, depth);
    result.motion = readImage(environment, motion);
    readTemporalConstants(environment, constants, result);
    result.frameIndex = static_cast<std::uint32_t>(sequence);
    return result;
}

}
