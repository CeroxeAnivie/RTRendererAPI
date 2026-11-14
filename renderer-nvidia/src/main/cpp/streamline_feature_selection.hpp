#pragma once

#include <cstdint>

#include "streamline_types.hpp"

namespace rtrenderer::nvidia {

struct StreamlineFeatureSelection final {
    std::int32_t executable;
    std::int32_t missingRequired;
};

/**
 * Selects only device-supported Streamline features. Reflex and PCL form one atomic low-latency
 * capability; DLSS-G additionally depends on that pair, while the pair remains executable when
 * DLSS-G itself is unsupported. The inputs are SDK-derived bit sets, never GPU-name rules.
 */
constexpr StreamlineFeatureSelection selectStreamlineFeatures(
        std::int32_t requested,
        std::int32_t required,
        std::int32_t supported
) noexcept {
    constexpr std::int32_t lowLatencyGroup = kStreamlineReflex | kStreamlinePcl;
    const bool lowLatencySupported = (supported & lowLatencyGroup) == lowLatencyGroup;
    std::int32_t normalizedSupport = lowLatencySupported
            ? supported : supported & ~lowLatencyGroup;
    if (!lowLatencySupported) normalizedSupport &= ~kStreamlineFrameGeneration;
    const std::int32_t executable = requested & normalizedSupport;
    return {executable, required & ~executable};
}

constexpr std::int32_t kReconstructionCandidates = kStreamlineDlss | kStreamlineNis;
constexpr std::int32_t kGenerationGroup =
        kStreamlineFrameGeneration | kStreamlineReflex | kStreamlinePcl;

// Cross-generation contracts are expressed as support matrices, not architecture-name branches.
static_assert(selectStreamlineFeatures(
        kReconstructionCandidates | kGenerationGroup, 0, kReconstructionCandidates
).executable == kReconstructionCandidates);
static_assert(selectStreamlineFeatures(
        kReconstructionCandidates | kGenerationGroup, 0, kStreamlineDlss | kGenerationGroup
).executable == (kStreamlineDlss | kGenerationGroup));
static_assert(selectStreamlineFeatures(
        kReconstructionCandidates | kGenerationGroup, kGenerationGroup, kReconstructionCandidates
).missingRequired == kGenerationGroup);
static_assert(selectStreamlineFeatures(
        kGenerationGroup, 0, kStreamlineReflex | kStreamlinePcl
).executable == (kStreamlineReflex | kStreamlinePcl));
static_assert(selectStreamlineFeatures(
        kStreamlineReflex | kStreamlinePcl,
        kStreamlineReflex | kStreamlinePcl,
        kStreamlineReflex
).missingRequired == (kStreamlineReflex | kStreamlinePcl));
static_assert(selectStreamlineFeatures(
        kReconstructionCandidates, 0, kStreamlineNis
).executable == kStreamlineNis);

}
