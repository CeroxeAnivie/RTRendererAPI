#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <limits>

#include <sl_dlss_g.h>

namespace rtrenderer::nvidia {

/** Owns destructive DLSS-G state-query accounting without owning SDK lifetime or locks. */
class StreamlineFrameGenerationTracker final {
public:
    void reset() noexcept {
        saturatingIncrement(resetEpoch_);
        proxyPresentCalls_ = 0;
        stateSamples_ = 0;
        framesActuallyPresented_ = 0;
        generatedFramesActuallyPresented_ = 0;
        stateQueryFailures_ = 0;
        lastFramesActuallyPresented_ = 0;
        maxFramesToGenerate_ = 0;
        maxGeneratedFramesInSample_ = 0;
        generationRequestMisses_ = 0;
        stateQueryCalls_ = 0;
        frameGenerationStatus_ = 0;
        firstProxyPresentSequence_ = 0;
        lastProxyPresentSequence_ = 0;
        lastGeneratedObservationSequence_ = 0;
        latestQuerySucceeded_ = false;
    }

    void noteProxyPresent(std::uint64_t sequence) noexcept {
        if (proxyPresentCalls_ == 0) firstProxyPresentSequence_ = sequence;
        lastProxyPresentSequence_ = sequence;
        saturatingIncrement(proxyPresentCalls_);
    }
    void noteGenerationRequestMiss() noexcept { saturatingIncrement(generationRequestMisses_); }
    void recordQueryFailure() noexcept {
        saturatingIncrement(stateQueryCalls_);
        saturatingIncrement(stateQueryFailures_);
        latestQuerySucceeded_ = false;
    }

    void recordState(const sl::DLSSGState& state, std::uint64_t observationSequence) noexcept {
        saturatingIncrement(stateQueryCalls_);
        const std::uint64_t maximumBatchFrames =
                static_cast<std::uint64_t>(state.numFramesToGenerateMax) + 1U;
        if (state.numFramesActuallyPresented > maximumBatchFrames) {
            // Reject impossible vendor evidence instead of allowing it to activate a capability.
            saturatingIncrement(stateQueryFailures_);
            latestQuerySucceeded_ = false;
            return;
        }
        latestQuerySucceeded_ = true;
        frameGenerationStatus_ = static_cast<std::uint32_t>(state.status);
        maxFramesToGenerate_ = state.numFramesToGenerateMax;
        // The proxy pacer may publish a completed batch after the immediate post-present query.
        // A zero result carries status but is not delivery evidence and must not erase the latest
        // real batch or inflate the number of presentation samples.
        if (state.numFramesActuallyPresented == 0) return;
        saturatingIncrement(stateSamples_);
        lastFramesActuallyPresented_ = state.numFramesActuallyPresented;
        saturatingAdd(framesActuallyPresented_, state.numFramesActuallyPresented);
        // Streamline defines this gauge as the complete batch for one application present.
        // The first frame is renderer-owned; every remaining frame is generated output.
        const std::uint32_t generatedFrames = state.numFramesActuallyPresented > 0
                ? state.numFramesActuallyPresented - 1
                : 0;
        saturatingAdd(generatedFramesActuallyPresented_, generatedFrames);
        if (generatedFrames > 0) lastGeneratedObservationSequence_ = observationSequence;
        maxGeneratedFramesInSample_ = std::max<std::uint32_t>(
                maxGeneratedFramesInSample_, static_cast<std::uint32_t>(generatedFrames)
        );
    }

    std::array<std::uint64_t, 16> snapshot() const noexcept {
        return {
                proxyPresentCalls_, stateSamples_, framesActuallyPresented_,
                generatedFramesActuallyPresented_, lastFramesActuallyPresented_,
                maxFramesToGenerate_, frameGenerationStatus_, stateQueryFailures_,
                maxGeneratedFramesInSample_, generationRequestMisses_, stateQueryCalls_,
                firstProxyPresentSequence_, lastProxyPresentSequence_,
                lastGeneratedObservationSequence_, resetEpoch_,
                latestQuerySucceeded_ ? 1U : 0U
        };
    }

private:
    static constexpr std::uint64_t kCounterMaximum =
            static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max());

    static void saturatingIncrement(std::uint64_t& value) noexcept {
        if (value < kCounterMaximum) ++value;
    }

    static void saturatingAdd(std::uint64_t& value, std::uint64_t increment) noexcept {
        value = increment > kCounterMaximum - value
                ? kCounterMaximum : value + increment;
    }

    std::uint64_t proxyPresentCalls_ = 0;
    std::uint64_t stateSamples_ = 0;
    std::uint64_t framesActuallyPresented_ = 0;
    std::uint64_t generatedFramesActuallyPresented_ = 0;
    std::uint64_t stateQueryFailures_ = 0;
    std::uint32_t lastFramesActuallyPresented_ = 0;
    std::uint32_t maxFramesToGenerate_ = 0;
    std::uint32_t maxGeneratedFramesInSample_ = 0;
    std::uint64_t generationRequestMisses_ = 0;
    std::uint64_t stateQueryCalls_ = 0;
    std::uint32_t frameGenerationStatus_ = 0;
    std::uint64_t firstProxyPresentSequence_ = 0;
    std::uint64_t lastProxyPresentSequence_ = 0;
    std::uint64_t lastGeneratedObservationSequence_ = 0;
    std::uint64_t resetEpoch_ = 0;
    bool latestQuerySucceeded_ = false;
};

}
