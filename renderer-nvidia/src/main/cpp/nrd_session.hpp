#pragma once

#include <array>
#include <cstdint>
#include <memory>

namespace rtrenderer::nvidia {

/** Validated native value object matching Java's per-frame NRD constants contract. */
struct NrdFrameConstants {
    std::array<float, 16> viewToClip;
    std::array<float, 16> viewToClipPrev;
    std::array<float, 16> worldToView;
    std::array<float, 16> worldToViewPrev;
    float jitterX;
    float jitterY;
    float jitterPrevX;
    float jitterPrevY;
    float motionScaleX;
    float motionScaleY;
    bool reset;
};

/** Owns one NRD/NRI integration and all extent-dependent denoiser state.
 * The queued-frame count must equal the renderer's maximum in-flight submissions. */
class NrdSession final {
public:
    NrdSession(
            std::uint64_t instance,
            std::uint64_t physicalDevice,
            std::uint64_t device,
            std::uint32_t queueFamilyIndex,
            std::uint32_t queuedFrameNum
    );
    ~NrdSession();

    NrdSession(const NrdSession&) = delete;
    NrdSession& operator=(const NrdSession&) = delete;

    void record(
            std::uint64_t commandBuffer,
            std::uint64_t normalRoughness,
            std::uint64_t viewZ,
            std::uint64_t motionVectors,
            std::uint64_t diffuseInput,
            std::uint64_t specularInput,
            std::uint64_t diffuseOutput,
            std::uint64_t specularOutput,
            const NrdFrameConstants& constants,
            std::int32_t width,
            std::int32_t height
    );

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

}
