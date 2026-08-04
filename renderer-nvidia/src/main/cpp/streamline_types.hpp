#pragma once

#include <array>
#include <cstdint>

namespace rtrenderer::nvidia {

constexpr std::int32_t kStreamlineDlss = 1;
constexpr std::int32_t kStreamlineNis = 1 << 1;
constexpr std::int32_t kStreamlineFrameGeneration = 1 << 2;
constexpr std::int32_t kStreamlineRayReconstruction = 1 << 3;
constexpr std::int32_t kStreamlineReflex = 1 << 4;
constexpr std::int32_t kStreamlinePcl = 1 << 5;

struct StreamlineImage final {
    std::uint64_t image;
    std::uint64_t memory;
    std::uint64_t view;
    std::int32_t format;
    std::int32_t width;
    std::int32_t height;
    std::int32_t usage;
};

/** SDK-neutral frame contract shared by JNI translation and feature executors. */
struct StreamlineFrame final {
    StreamlineImage inputColor;
    StreamlineImage outputColor;
    StreamlineImage depth;
    StreamlineImage motion;
    StreamlineImage exposure;
    std::array<float, 16> viewToClip;
    std::array<float, 16> clipToView;
    std::array<float, 16> clipToPrevious;
    std::array<float, 16> previousToClip;
    std::array<float, 3> position;
    std::array<float, 3> up;
    std::array<float, 3> right;
    std::array<float, 3> forward;
    float jitterX;
    float jitterY;
    float motionScaleX;
    float motionScaleY;
    float nearPlane;
    float farPlane;
    float fovRadians;
    float aspectRatio;
    bool reset;
    std::uint32_t frameIndex;
};

}
