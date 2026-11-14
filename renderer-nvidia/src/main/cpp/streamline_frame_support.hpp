#pragma once

#include <sl.h>

#include "streamline_types.hpp"

namespace rtrenderer::nvidia::streamline {

/** Renderer frame-slot images are immutable until the matching proxy present completes. */
inline constexpr sl::ResourceLifecycle kFrameSlotResourceLifecycle =
        sl::ResourceLifecycle::eValidUntilPresent;

bool validSampledImage(const StreamlineImage& image) noexcept;
bool validStorageImage(const StreamlineImage& image) noexcept;
sl::Resource resource(const StreamlineImage& image) noexcept;
void setConstantsForFrame(
        PFun_slSetConstants* setConstants,
        const sl::ViewportHandle& viewport,
        sl::FrameToken& token,
        const StreamlineFrame& frame
);
void requireOk(sl::Result result, const char* operation);

}
