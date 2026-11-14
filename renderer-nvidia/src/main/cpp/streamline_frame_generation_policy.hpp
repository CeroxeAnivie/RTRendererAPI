#pragma once

#include <algorithm>
#include <cstdint>

namespace rtrenderer::nvidia {

enum class StreamlineCadenceStatus : std::uint8_t {
    ready,
    invalidRequest,
    unavailable,
    exceedsDeviceLimit
};

struct StreamlineCadenceDecision final {
    StreamlineCadenceStatus status;
    std::uint32_t generatedFrames;
};

/** Pure cross-generation cadence policy driven exclusively by SDK-reported capability. */
constexpr StreamlineCadenceDecision selectStreamlineCadence(
        std::int32_t requestedFrames,
        std::uint32_t deviceMaximum
) noexcept {
    if (requestedFrames == 0 || requestedFrames < -3 || requestedFrames > 3) {
        return {StreamlineCadenceStatus::invalidRequest, 0};
    }
    const std::uint32_t requestedLimit = static_cast<std::uint32_t>(
            requestedFrames < 0 ? -requestedFrames : requestedFrames
    );
    if (requestedFrames < 0) {
        const std::uint32_t selected = std::min(requestedLimit, deviceMaximum);
        return selected == 0
                ? StreamlineCadenceDecision{StreamlineCadenceStatus::unavailable, 0}
                : StreamlineCadenceDecision{StreamlineCadenceStatus::ready, selected};
    }
    if (requestedLimit > deviceMaximum) {
        return {StreamlineCadenceStatus::exceedsDeviceLimit, 0};
    }
    return {StreamlineCadenceStatus::ready, requestedLimit};
}

static_assert(selectStreamlineCadence(-3, 0).status == StreamlineCadenceStatus::unavailable);
static_assert(selectStreamlineCadence(-3, 1).generatedFrames == 1); // SDK exposes 2x only.
static_assert(selectStreamlineCadence(-3, 2).generatedFrames == 2); // SDK exposes up to 3x.
static_assert(selectStreamlineCadence(-3, 5).generatedFrames == 3); // Configured ceiling remains 4x.
static_assert(selectStreamlineCadence(3, 2).status == StreamlineCadenceStatus::exceedsDeviceLimit);
static_assert(selectStreamlineCadence(0, 5).status == StreamlineCadenceStatus::invalidRequest);

}
