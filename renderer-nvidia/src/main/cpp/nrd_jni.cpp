#include "nrd_jni.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <string>

namespace rtrenderer::nvidia {
namespace {

jmethodID method(JNIEnv* environment, jclass type, const char* name, const char* signature) {
    jmethodID result = environment->GetMethodID(type, name, signature);
    if (result == nullptr) throw std::runtime_error(std::string("missing NRD JNI method ") + name);
    return result;
}

std::array<float, 16> readMatrix(
        JNIEnv* environment,
        jobject source,
        jclass type,
        const char* name
) {
    auto array = static_cast<jfloatArray>(
            environment->CallObjectMethod(source, method(environment, type, name, "()[F"))
    );
    if (array == nullptr || environment->GetArrayLength(array) != 16) {
        throw std::invalid_argument(std::string("invalid NRD temporal matrix ") + name);
    }
    std::array<float, 16> result{};
    environment->GetFloatArrayRegion(array, 0, 16, result.data());
    return result;
}

}

NrdFrameConstants readNrdFrameConstants(JNIEnv* environment, jobject constants) {
    if (environment == nullptr || constants == nullptr) {
        throw std::invalid_argument("null NRD JNI environment or frame constants");
    }
    jclass type = environment->GetObjectClass(constants);
    NrdFrameConstants result = {
            readMatrix(environment, constants, type, "viewToClipMatrix"),
            readMatrix(environment, constants, type, "viewToClipMatrixPrev"),
            readMatrix(environment, constants, type, "worldToViewMatrix"),
            readMatrix(environment, constants, type, "worldToViewMatrixPrev"),
            environment->CallFloatMethod(constants, method(environment, type, "currentJitterX", "()F")),
            environment->CallFloatMethod(constants, method(environment, type, "currentJitterY", "()F")),
            environment->CallFloatMethod(constants, method(environment, type, "previousJitterX", "()F")),
            environment->CallFloatMethod(constants, method(environment, type, "previousJitterY", "()F")),
            environment->CallFloatMethod(constants, method(environment, type, "motionVectorScaleX", "()F")),
            environment->CallFloatMethod(constants, method(environment, type, "motionVectorScaleY", "()F")),
            environment->CallBooleanMethod(constants, method(environment, type, "reset", "()Z")) == JNI_TRUE
    };
    auto finiteMatrix = [](const std::array<float, 16>& matrix) {
        return std::all_of(matrix.begin(), matrix.end(), [](float value) { return std::isfinite(value); });
    };
    if (!finiteMatrix(result.viewToClip) || !finiteMatrix(result.viewToClipPrev)
            || !finiteMatrix(result.worldToView) || !finiteMatrix(result.worldToViewPrev)) {
        throw std::invalid_argument("NRD temporal matrices must contain only finite values");
    }
    auto validJitter = [](float value) {
        return std::isfinite(value) && value >= -0.5f && value <= 0.5f;
    };
    if (!validJitter(result.jitterX) || !validJitter(result.jitterY)
            || !validJitter(result.jitterPrevX) || !validJitter(result.jitterPrevY)) {
        throw std::invalid_argument("NRD camera jitter must be finite and in [-0.5, 0.5]");
    }
    if (!std::isfinite(result.motionScaleX) || !std::isfinite(result.motionScaleY)
            || result.motionScaleX <= 0.0f || result.motionScaleY <= 0.0f) {
        throw std::invalid_argument("NRD motion-vector scale must be finite and positive");
    }
    return result;
}

}
