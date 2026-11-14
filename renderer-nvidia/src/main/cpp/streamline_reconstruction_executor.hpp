#pragma once

#include <array>
#include <cstdint>

#include <sl.h>

#include "streamline_sdk.hpp"
#include "streamline_types.hpp"

namespace rtrenderer::nvidia {

class StreamlineFrameConstantsPublisher;

/** Owns only DLSS/DLAA/NIS execution bindings; the process SDK lifecycle remains external. */
class StreamlineReconstructionExecutor final {
public:
    void bind(
            const StreamlineApiBindings& bindings,
            StreamlineFrameConstantsPublisher& frameConstants
    ) noexcept;
    void clear() noexcept;

    void recordDlss(
            std::uint64_t commandBuffer,
            std::int32_t reconstructionMode,
            std::int32_t quality,
            const StreamlineFrame& frame
    );
    void recordNis(
            std::uint64_t commandBuffer,
            std::int32_t quality,
            const StreamlineFrame& frame
    );
    std::array<std::int32_t, 2> dlssOptimalSettings(
            std::int32_t quality,
            std::int32_t outputWidth,
            std::int32_t outputHeight
    );

private:
    struct DlssConfiguration final {
        sl::DLSSMode mode = sl::DLSSMode::eOff;
        std::uint32_t outputWidth = 0;
        std::uint32_t outputHeight = 0;

        bool operator==(const DlssConfiguration&) const = default;
    };

    static sl::DLSSMode dlssMode(std::int32_t reconstructionMode, std::int32_t quality);
    static float nisSharpness(std::int32_t quality);

    PFun_slGetNewFrameToken* getNewFrameToken_ = nullptr;
    StreamlineFrameConstantsPublisher* frameConstants_ = nullptr;
    PFun_slEvaluateFeature* evaluateFeature_ = nullptr;
    PFun_slFreeResources* freeResources_ = nullptr;
    PFun_slDLSSGetOptimalSettings* dlssOptimalSettings_ = nullptr;
    PFun_slDLSSSetOptions* dlssSetOptions_ = nullptr;
    PFun_slNISSetOptions* nisSetOptions_ = nullptr;
    sl::ViewportHandle viewport_{0};
    DlssConfiguration dlssConfiguration_{};
    bool hasDlssResources_ = false;
};

}
