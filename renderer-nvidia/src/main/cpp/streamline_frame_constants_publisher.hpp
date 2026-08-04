#pragma once

#include <array>
#include <cstdint>
#include <unordered_map>

#include <sl.h>

#include "streamline_sdk.hpp"
#include "streamline_types.hpp"

namespace rtrenderer::nvidia {

/** Publishes Streamline common constants exactly once for each viewport/frame-token pair. */
class StreamlineFrameConstantsPublisher final {
public:
    void bind(const StreamlineApiBindings& bindings) noexcept;
    void clear() noexcept;

    sl::FrameToken& publish(
            const sl::ViewportHandle& viewport,
            const StreamlineFrame& frame
    );

private:
    struct Snapshot final {
        std::array<float, 16> viewToClip{};
        std::array<float, 16> clipToView{};
        std::array<float, 16> clipToPrevious{};
        std::array<float, 16> previousToClip{};
        std::array<float, 3> position{};
        std::array<float, 3> up{};
        std::array<float, 3> right{};
        std::array<float, 3> forward{};
        float jitterX = 0.0F;
        float jitterY = 0.0F;
        float motionScaleX = 0.0F;
        float motionScaleY = 0.0F;
        float nearPlane = 0.0F;
        float farPlane = 0.0F;
        float fovRadians = 0.0F;
        float aspectRatio = 0.0F;
        bool reset = false;

        static Snapshot from(const StreamlineFrame& frame) noexcept;
        bool operator==(const Snapshot&) const = default;
    };

    struct Publication final {
        std::uint32_t frameIndex = 0;
        Snapshot snapshot{};
        sl::FrameToken* token = nullptr;
        bool published = false;
    };

    PFun_slGetNewFrameToken* getNewFrameToken_ = nullptr;
    PFun_slSetConstants* setConstants_ = nullptr;
    std::unordered_map<std::uint32_t, Publication> publications_;
};

}
